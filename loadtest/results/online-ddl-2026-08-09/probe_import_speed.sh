#!/bin/bash
# 시딩 대체안 실측 — 전송 가능 테이블스페이스(IMPORT)가 INSERT 시딩보다 얼마나 빠른가
#
# 왜 재는가: 판마다 «같은 1,000만 행» 을 처음부터 다시 만드는 데 **1,261초(21분)** 가 든다.
#   8판이면 시딩만 168분이다. 결과물이 매번 동일한데 만드는 과정만 반복하므로,
#   한 번 만들어 파일로 떠 두고 판마다 되돌리는 쪽이 성립하는지 본다.
#   비용이 «행 수» 가 아니라 «파일 크기» 에 비례하게 바뀌는 게 요점이다.
#
# ⚠️ 이 스크립트는 **측정이지 채택이 아니다.** 값을 보고 rig 에 넣을지는 사용자가 정한다.
#    채택하면 바뀌는 조건이 하나 있다 — IMPORT 직후 테이블은 버퍼풀이 차갑고,
#    INSERT 시딩 직후는 일부 더워져 있다. DDL 시작 조건이 달라지므로
#    **팔 A·B 를 전부 같은 방식으로 통일해야** 팔 간 비교가 성립한다.
#    기존 96분·24.5분 baseline 과의 직접 비교는 조건이 달라진다는 것도 결과에 적을 것.

set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_rig.sh"

SNAP_DIR=/var/lib/mysql-snapshot
DATA_DIR=/var/lib/mysql/$DB_NAME
REPEATS=${REPEATS:-3}
RESULT=$OUT/probe_import_speed.txt

require_container
: > "$RESULT"
say() { echo "$*" | tee -a "$RESULT"; }

say "# 시딩 대체안 실측 — INSERT 시딩 vs 파일 복원(IMPORT TABLESPACE)"
say "# $(date '+%Y-%m-%d %H:%M:%S')"
say ""

# ── 0. 황금 원본 만들기 ──────────────────────────────────────────────────
#
# 스냅샷을 뜨려면 «파티션이 안 걸린 1,000만 행» 이 필요하다. 버림판 직후의
# pose_data_scale 은 파티션이 걸려 있으므로 여기서 한 번 새로 시딩한다.
# 이 21분은 낭비가 아니라 **채택 시 딱 한 번 드는 준비 비용**이다.
say "## [0] 황금 원본 시딩 (채택하면 이게 «한 번만» 드는 비용)"
#
# 이미 조건을 만족하는 테이블이 있으면 다시 만들지 않는다. 시딩은 14분이고,
# 이 스크립트는 스냅샷/복원 실패로 재실행될 일이 잦다(실제로 한 번 그랬다).
# SEED_S 를 밖에서 주면 그 값을 판정에 쓴다 — 앞선 실행에서 잰 값을 이어 쓰기 위한 것.
ALREADY=$(DBQ "SELECT COUNT(*) FROM pose_data_scale;" 2>/dev/null | tr -d '[:space:]')
PARTED=$(DBQ "SELECT COUNT(*) FROM information_schema.partitions
              WHERE table_schema='$DB_NAME' AND table_name='pose_data_scale'
                AND partition_name IS NOT NULL;" | tr -d '[:space:]')
if [ "${ALREADY:-0}" = "10000000" ] && [ "${PARTED:-0}" = "0" ] && [ -n "${SEED_S:-}" ]; then
  say "  기존 황금 원본 재사용 (1,000만 행·파티션 없음). 앞선 실행의 시딩 ${SEED_S}s 를 인용한다"
else
  t0=$(date +%s)
  seed_scale
  t1=$(date +%s)
  SEED_S=$((t1-t0))
  say "  INSERT 시딩: ${SEED_S}s"
fi
say ""

# 복원용 정의는 «지금 이 테이블» 에서 그대로 뜬다. 손으로 옮겨 적으면
# 인덱스 하나·콜레이션 하나가 어긋나도 IMPORT 가 거절한다.
DDL_TEXT=$(DBQ "SHOW CREATE TABLE pose_data_scale;" | sed 's/^pose_data_scale\t//')
printf '%s\n' "$DDL_TEXT" > "$OUT/probe_import_table.sql"
[ -n "$DDL_TEXT" ] || die "테이블 정의를 못 떴다"

# ── 1. 스냅샷 ────────────────────────────────────────────────────────────
#
# 🔴 FLUSH TABLES ... FOR EXPORT 의 락은 **그 세션이 끝나면 풀린다.**
#    `mysql -e "FLUSH..."` 로 던지고 밖에서 복사하면 락이 이미 없는 상태에서 복사하는
#    셈이라 일관성이 깨진다. 그래서 같은 세션 안에서 mysql 클라이언트의 `system` 으로
#    복사까지 끝낸다. 컨테이너 안에서 실행되므로 경로는 컨테이너 기준이다.
say "## [1] 스냅샷 (FLUSH FOR EXPORT + 파일 복사)"
# 🔴 `docker exec ... mkdir -p /var/lib/mysql-snapshot` 로 쓰면 안 된다.
#    Git Bash(MSYS)가 `/` 로 시작하는 **독립 인자**를 Windows 경로로 바꿔서 넘긴다.
#    컨테이너 안에 「C:/Program Files/Git/var/lib/mysql-snapshot」 이라는 디렉터리가 생기고,
#    heredoc 안의 `system cp` 는 변환을 안 거치므로 진짜 경로를 찾다가 실패한다
#    (2026-08-10 1차 실행이 정확히 이걸로 죽었다 — 시딩 14분을 쓰고 나서).
#    bash -c "..." 안에 넣으면 인자가 `/` 로 시작하지 않아 변환 대상이 아니다.
docker exec "$CONTAINER" bash -c "mkdir -p $SNAP_DIR" || die "스냅샷 디렉터리를 못 만들었다"
t0=$(date +%s)
docker exec -i "$CONTAINER" mysql -uroot -p"$PW" "$DB_NAME" <<SQL >>"$MYSQL_ERR" 2>&1
FLUSH TABLES pose_data_scale FOR EXPORT;
system cp $DATA_DIR/pose_data_scale.ibd $DATA_DIR/pose_data_scale.cfg $SNAP_DIR/
UNLOCK TABLES;
SQL
t1=$(date +%s)
SNAP_OK=$(docker exec "$CONTAINER" bash -c "ls -1 $SNAP_DIR/pose_data_scale.ibd $SNAP_DIR/pose_data_scale.cfg 2>/dev/null | wc -l")
[ "${SNAP_OK:-0}" = "2" ] || die "스냅샷 파일이 2개가 아니다 (.ibd/.cfg) — mysql 클라이언트의 system 이 안 먹었을 수 있다"
SNAP_MB=$(docker exec "$CONTAINER" bash -c "du -sm $SNAP_DIR | cut -f1")
say "  스냅샷: $((t1-t0))s · ${SNAP_MB}MB (.ibd + .cfg)"
say ""

# ── 2. 복원을 REPEATS 회 ─────────────────────────────────────────────────
#
# 1회로는 «복원»과 «그 판의 디스크 상태» 가 분리되지 않는다([[feedback_measure_design_needs_repeats]]).
say "## [2] 파일 복원 ${REPEATS}회"
TOTAL=0
for i in $(seq 1 "$REPEATS"); do
  t0=$(date +%s)
  DB -e "DROP TABLE IF EXISTS pose_data_scale;" || die "복원 전 DROP 실패 ($i회차)"
  DB -e "$DDL_TEXT" || die "테이블 재생성 실패 ($i회차)"
  DB -e "ALTER TABLE pose_data_scale DISCARD TABLESPACE;" || die "DISCARD 실패 ($i회차)"
  docker exec "$CONTAINER" bash -c \
    "cp $SNAP_DIR/pose_data_scale.ibd $SNAP_DIR/pose_data_scale.cfg $DATA_DIR/ \
     && chown mysql:mysql $DATA_DIR/pose_data_scale.ibd $DATA_DIR/pose_data_scale.cfg" \
    || die "파일 복사/소유권 변경 실패 ($i회차)"
  DB -e "ALTER TABLE pose_data_scale IMPORT TABLESPACE;" || die "IMPORT 실패 ($i회차) — $MYSQL_ERR 확인"
  t1=$(date +%s)

  # 🔴 «복원됐다» 와 «복원이 끝났다» 는 다르다. 행 수·인덱스를 확인하지 않으면
  #    빈 테이블을 «0초 만에 복원» 으로 적게 된다.
  n=$(DBQ "SELECT COUNT(*) FROM pose_data_scale;" | tr -d '[:space:]')
  idx=$(DBQ "SELECT COUNT(*) FROM information_schema.statistics
             WHERE table_schema='$DB_NAME' AND table_name='pose_data_scale'
               AND index_name='idx_session_timestamp';" | tr -d '[:space:]')
  [ "$n" = "10000000" ] || die "복원 후 행수가 '$n' 이다 ($i회차) — 1,000만이 아니면 측정 대상이 다른 테이블이다"
  [ "${idx:-0}" -ge 1 ] || die "복원 후 보조 인덱스가 없다 ($i회차)"

  say "  ${i}회차: $((t1-t0))s  (행 ${n} · 인덱스 확인)"
  TOTAL=$((TOTAL + t1 - t0))
done
AVG=$((TOTAL / REPEATS))
say ""

# ── 판정 ─────────────────────────────────────────────────────────────────
say "## 판정"
say "  INSERT 시딩  ${SEED_S}s"
say "  파일 복원    ${AVG}s (평균 ${REPEATS}회)"
if [ "$AVG" -gt 0 ]; then
  say "  → 판당 $((SEED_S - AVG))s 절약 · 배수 $((SEED_S / AVG))x"
  say "  → 8판 기준: $((SEED_S * 8 / 60))분 → $((AVG * 8 / 60 + SEED_S / 60))분 (준비 시딩 1회 포함)"
fi
say ""
say "⚠️ 채택 시 바뀌는 조건: IMPORT 직후는 버퍼풀이 차갑다. 팔 A·B 를 전부 이 방식으로"
say "   통일해야 팔 간 비교가 성립하고, 기존 96분·24.5분 baseline 과는 조건이 달라진다."
say ""
say "결과 파일: $RESULT"
