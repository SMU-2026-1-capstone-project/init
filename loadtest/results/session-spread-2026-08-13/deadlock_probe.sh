#!/bin/bash
# 從 — 「멱등 INSERT 의 데드락은 어떤 잠금에서 나나」 (#276)
#
# ─────────────────────────────────────────────────────────────────────────
# 무엇이 열려 있나
#
# 2026-08-17 게이트 라운드에서 **같은 키를 동시에 넣으면 34.8%가 죽는 것**을 봤다:
#
#   INSERT INTO pose_data (...) ON DUPLICATE KEY UPDATE session_id = session_id
#   → Deadlock found when trying to get lock; try restarting transaction
#
# 이슈 #276 의 미검증 항목이 정확히 이것이다 — *"데드락의 정확한 기제(중복 키 검사 중 잡는
# 잠금 vs 배치 내 행 순서)는 `SHOW ENGINE INNODB STATUS` 를 안 봤다"*.
#
# 기제를 모르면 **고치는 방향을 근거 없이 고르게 된다**:
#   ㄱ 데드락 재시도    ㄴ 배치 안 키 정렬    ㄷ INSERT IGNORE 로 교체
# 셋은 서로 다른 잠금 그림을 전제한다. 이 판은 그 그림을 한 장 찍는다.
# ─────────────────────────────────────────────────────────────────────────
#
# 🔴 이 스크립트는 **일부러 실패를 만든다.** 유일한 «측정» 은 서버가 남긴 잠금 덤프이고,
#    처리량·지연은 읽지 않는다(읽으면 안 된다 — 실패가 섞인 판의 RPS 는 처리량이 아니다).
#
# 🔴 **유니크 키가 있어야 성립한다.** R8(#272)이 키를 뗀 채로 끝났다면 여기서 멈춘다.
#
# 전제: 옛(수정 전) 페이로드가 필요하다 — 요청마다 키가 같아야 중복이 난다.
#       지금 생성기는 템플릿이라 키가 매번 다르므로, `--legacy` 로 만들지 않고
#       **정적 배열 페이로드를 여기서 직접 만든다**(gen 은 그대로 두고 후처리로 템플릿을 지운다).
#
# 사용: sessions_sweep.sh 와 같은 환경변수

set -uo pipefail
cd "$(dirname "$0")"

SESS_LO=901
LEVEL=${LEVEL:-20}
SESS_HI=$(( SESS_LO + LEVEL - 1 ))
C=${C:-20}
N_REQ=${N_REQ:-2000}
REPS=${REPS:-25}
GEN=../../ghz/gen_batch_multi.py
PY=${PY:-python}

OUT="${OUT:?OUT 미설정}"
LOG="$OUT/sessions.tsv"        # _rig.sh 가 요구한다. 이 판은 여기에 안 쓴다
DUMP="$OUT/deadlock_status.txt"

source ./../commit-count-2026-08-09/_rig.sh

learn_all_hosts
echo "=== 사전 확인 ==="
assert_mysql_reachable

n=$(mysql_q "SELECT COUNT(*) FROM information_schema.statistics
             WHERE table_schema='shadowfit' AND table_name='pose_data'
               AND index_name='uk_pose_event';")
[ "${n:-0}" -gt 0 ] || die "uk_pose_event 가 없다 — 이 판은 그 키의 데드락을 보는 것이라 성립하지 않는다"
echo "  uk_pose_event 확인 (열 $n)"

# ── 중복을 만드는 페이로드 ───────────────────────────────────────────────
#
# 템플릿 자리를 **고정값으로 바꿔** 요청마다 같은 키가 되게 한다. 이게 #271 이전의 모양이고,
# 지금은 이 판에서만 일부러 되살린다.
echo "=== 중복 페이로드 생성 (레벨 $LEVEL · 키 고정) ==="
mkdir -p "$OUT/_payload"
DUPFILE=$OUT/_payload/dup_$LEVEL.json
"$PY" "$GEN" --sessions "$SESS_LO-$SESS_HI" --reps "$REPS" --out "$DUPFILE" >/dev/null \
  || die "페이로드 생성 실패"
"$PY" - "$DUPFILE" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
# `{{ add 901 (mod .RequestNumber N) }}` → 901 (한 세션에 몰아 중복을 최대화)
s = re.sub(r'\{\{\s*add\s+(\d+)\s+\(mod[^}]*\}\}', r'\1', s)
# `{{ .RequestNumber }}` → 0 (요청마다 같은 rep_number)
s = re.sub(r'\{\{\s*\.RequestNumber\s*\}\}', '0', s)
assert '{{' not in s, '템플릿이 남았다'
open(p, 'w', encoding='utf-8').write(s)
print(f"  키 고정 완료: {p} ({len(s)/1024:.1f}KB)")
PY
scp "${SCP_OPTS[@]}" -q "$DUPFILE" "ec2-user@$LOADER_PUB:/tmp/dup.json" || die "페이로드 전송 실패"

mysql_q "DELETE FROM pose_data WHERE session_id BETWEEN $SESS_LO AND $SESS_HI;" >/dev/null

# ── 일부러 데드락을 낸다 ─────────────────────────────────────────────────
echo
echo "=== 데드락 유도 (c=$C · n=$N_REQ · 같은 키) ==="
rsh "$LOADER_PUB" "$GHZ --insecure --call ExerciseService.SavePoseDataBatch \
    --metadata-file /tmp/meta.json --data-file /tmp/dup.json -c $C -n $N_REQ $APP_PRIV:6565 2>&1 \
    | grep -E 'Requests/sec|\[OK\]|\[Internal\]|Count:'" | sed 's/^/  /'

# ── 잠금 덤프 ────────────────────────────────────────────────────────────
#
# `LATEST DETECTED DEADLOCK` 절이 **마지막 한 건**을 보여준다. 두 트랜잭션이 각각 어떤
# 잠금을 쥐고 무엇을 기다렸는지가 여기 있다 — 그게 이 판이 가지러 온 전부다.
echo
echo "=== SHOW ENGINE INNODB STATUS → $DUMP ==="
rsh "$DB_PUB" "sudo docker exec $MYSQL_CTN mysql -u$MYSQL_USER -p$MYSQL_PW shadowfit -e 'SHOW ENGINE INNODB STATUS\\G'" \
  2>/dev/null > "$DUMP"

if grep -q "LATEST DETECTED DEADLOCK" "$DUMP"; then
  echo "  ✅ 덤프 회수 — LATEST DETECTED DEADLOCK 절 있음"
  sed -n '/LATEST DETECTED DEADLOCK/,/^---/p' "$DUMP" | head -60 | sed 's/^/  /'
else
  echo "  🔴 덤프에 데드락 절이 없다 — 이번 부하에서 데드락이 안 났거나 덤프를 못 걷었다" >&2
  echo "     (그 자체가 사실이다. 「기제 미확인」으로 남길 것)" >&2
fi

rows=$(mysql_q "SELECT COUNT(*) FROM pose_data WHERE session_id BETWEEN $SESS_LO AND $SESS_HI;")
echo
echo "  저장된 행: $rows (요청 $N_REQ × 5 를 기대하는 판이 아니다 — 중복이 삼켜지는 것이 정상)"
mysql_q "DELETE FROM pose_data WHERE session_id BETWEEN $SESS_LO AND $SESS_HI;" >/dev/null
echo "  정리 완료"
