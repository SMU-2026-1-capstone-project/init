#!/bin/bash
# 본 측정 — 팔 A(차단 ALTER) vs 팔 B(pt-osc). 설계 §2·§4.
#
# 실행 전에 probe.sh 가 통과해 있어야 한다. 전제 확인 없이 이 스크립트만 돌리면
# «도구가 필요 없는 상황에서 도구를 잰» 표가 나온다.
#
# 예상 소요: 판당 시딩 ~5분 + DDL(A ~7분 / B 미지) → 8판 총 3시간 내외. 밤에 돌릴 것.

set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_rig.sh"

LOG=$OUT/ddl.tsv
WRITER_MAX_SEC=${WRITER_MAX_SEC:-5400}   # DDL 보다 넉넉히 길게. 끝나면 stop_writer 가 끊는다
WRITER_GAP_MS=${WRITER_GAP_MS:-200}      # 초당 5회. 정지 구간을 200ms 해상도로 본다

require_container
init_log
install_writer
ensure_ptosc_user

# ── 판 순서 (설계 §4 라틴 방격) ──────────────────────────────────────────
#
# 팔이 2개라 3라운드로는 순서를 완전히 상쇄할 수 없다. 위치 합을 맞추는 배열을 쓴다:
#   버림  A B      ← 팔당 1판씩 버린다. 첫 판은 페이지 캐시·버퍼풀 상태를 가장 크게 탄다
#   본판  A B B A A B   ← A 는 위치 1·4·5, B 는 2·3·6 (위치 합 10 vs 11)
#
# ⚠️ 설계 문서 §4 의 «버림판 1» 은 «팔당 1판» 으로 구체화했다. 팔 하나만 버리면
#    나머지 팔의 첫 판이 여전히 «첫 판» 이라 버림의 목적이 절반만 달성된다.
SEQ=(discard:A discard:B r1:A r2:B r3:B r4:A r5:A r6:B)

# 판 목록을 밖에서 갈아끼울 수 있다. 버림 2판만 먼저 돌려 팔 B 실소요를 재는 용도다
# (설계 §9: «버림판이 소요시간 프로브를 겸한다»). 예: SWEEP_SEQ="discard:A discard:B"
#
# ⚠️ 쪼개 돌리면 버림판의 목적이 반만 남는다. 버림판은 «첫 판이 페이지 캐시·버퍼풀 상태를
#    가장 크게 탄다» 를 흡수하는 장치인데, 버림판과 본판 사이에 컨테이너를 재시작하면
#    본판의 r1 이 다시 «첫 판» 이 된다. 쪼갤 거면 **같은 컨테이너 가동 중에 이어서** 돌릴 것.
if [ -n "${SWEEP_SEQ:-}" ]; then
  read -r -a SEQ <<< "$SWEEP_SEQ"
  echo "⚠️ SWEEP_SEQ 로 판 목록을 덮어썼다: ${SEQ[*]}"
  echo "   버림판만 돌린 뒤 본판을 따로 돌릴 거면 컨테이너를 재시작하지 말 것(위 주석)."
fi
TOTAL=${#SEQ[@]}

# ── 팔 A — 차단 ALTER (baseline) ─────────────────────────────────────────
run_arm_a() {  # $1 = 태그 → stdout: 소요 초. 실패 시 non-zero
  local tag=$1 t0 t1 rc
  t0=$(date +%s)
  docker exec -i "$CONTAINER" mysql -uroot -p"$PW" "$DB_NAME" \
    -e "ALTER TABLE pose_data_scale ${PARTITION_SPEC};" > "$OUT/${tag}_alter.log" 2>&1
  rc=$?
  t1=$(date +%s)
  [ $rc -eq 0 ] || { echo "  ✗ ALTER 실패 — $OUT/${tag}_alter.log 참고" >&2; return 1; }
  echo $((t1-t0))
}

# ── 팔 B — pt-online-schema-change ───────────────────────────────────────
#
# --network container:$CONTAINER 로 MySQL 컨테이너의 네트워크 네임스페이스를 공유한다.
# compose 네트워크 이름을 알아낼 필요가 없고 127.0.0.1:3306 이 그대로 통한다.
#
# --recursion-method=none : 복제가 없는데 pt-osc 가 replica 를 찾아다니며 지연되는 걸 막는다.
#                           복제 실험(3순위)을 붙인 뒤엔 **이 옵션을 빼야 한다** —
#                           그때는 replica 지연 감시가 도구의 핵심 기능이 된다.
# --print --statistics    : 어느 인덱스로 청크를 나눴는지, 청크 크기가 어떻게 조정됐는지
#                           로그에 남긴다. 설계 §7 의 «복합 PK» 항목이 여기서 확인된다.
#
# 🔴 root 가 아니라 $PTOSC_USER 로 붙는다 — root 로는 인증에서 rc=2 로 즉사한다.
#    사유는 _rig.sh 의 PTOSC_USER 주석. 이 계정 없이 돌리면 팔 B 4판이 전부 «DDL실패» 로
#    찍히고, 그건 도구의 성질이 아니라 rig 의 결함이다.
run_arm_b() {  # $1 = 태그 → stdout: 소요 초. 실패 시 non-zero
  local tag=$1 t0 t1 rc
  t0=$(date +%s)
  docker run --rm --network "container:$CONTAINER" percona/percona-toolkit \
    pt-online-schema-change \
      --alter "$PARTITION_SPEC" \
      --execute --print --statistics --recursion-method=none \
      "h=127.0.0.1,P=3306,u=$PTOSC_USER,p=$PTOSC_PW,D=$DB_NAME,t=pose_data_scale" \
    > "$OUT/${tag}_ptosc.log" 2>&1
  rc=$?
  t1=$(date +%s)
  [ $rc -eq 0 ] || { echo "  ✗ pt-osc 실패 (rc=$rc) — $OUT/${tag}_ptosc.log 참고" >&2; return 1; }
  echo $((t1-t0))
}

# ── 한 판 ────────────────────────────────────────────────────────────────
run_one() {  # $1 = round 이름, $2 = 팔(A|B)
  local round=$1 arm=$2 tag="${round}_${arm}"
  local ddl_s bl0 bl1 summary

  echo
  echo "──────── $tag ────────"
  seed_scale

  start_disk_sampler "${tag}_disk.txt"
  bl0=$(binlog_bytes)
  start_writer "$arm" "$WRITER_MAX_SEC" "$WRITER_GAP_MS"

  echo "  [DDL] 팔 $arm 시작"
  if [ "$arm" = "A" ]; then ddl_s=$(run_arm_a "$tag"); else ddl_s=$(run_arm_b "$tag"); fi
  local ddl_rc=$?

  stop_writer
  stop_disk_sampler
  bl1=$(binlog_bytes)

  if [ $ddl_rc -ne 0 ]; then
    fail_row "$round" "$arm"; FAILED+=("$tag:DDL실패"); return 1
  fi
  if ! verify_partitioned "$tag"; then
    fail_row "$round" "$arm"; FAILED+=("$tag:검증실패"); return 1
  fi

  dump_writer_log "${tag}_writer.tsv"
  summary=$(writer_summary)   # "attempts errors max_elapsed p50_elapsed max_gap"
  local att err mx p50 gap
  read -r att err mx p50 gap <<< "$summary"

  # writer 가 한 건도 못 썼으면 이 판은 «차단 여부» 를 말할 수 없다 — 숫자로 내보내지 않는다.
  if [ "${att:-0}" -le 1 ]; then
    echo "  ✗ writer 시도가 ${att:-0}건 — 정지 구간을 판정할 근거가 없다" >&2
    fail_row "$round" "$arm"; FAILED+=("$tag:writer무효"); return 1
  fi

  local dpk blmb
  dpk=$(disk_peak "${tag}_disk.txt")
  blmb=$(( ( ${bl1:-0} - ${bl0:-0} ) / 1024 / 1024 ))

  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "$round" "$arm" "$ddl_s" "$att" "$err" "$mx" "$p50" "$gap" "$dpk" "$blmb" >> "$LOG"

  echo "  → DDL ${ddl_s}s · 시도 ${att}건(에러 ${err}) · 최대정지 ${mx}ms · 평시 p50 ${p50}ms"
  echo "    최대간격 ${gap}ms · 디스크피크 ${dpk}MB · binlog +${blmb}MB"
  return 0
}

# ── 실행 ─────────────────────────────────────────────────────────────────
echo "=== 무중단 DDL 실측 — ${TOTAL}판 (버림 2 + 본판 6) ==="
echo "    팔 A = ALTER ... PARTITION BY (차단)"
echo "    팔 B = pt-online-schema-change"
echo "    ⚠️ 절대 소요 시간은 이 하드웨어의 값이다. «운영에서 N분» 으로 인용 금지(설계 §5)."
echo

for item in "${SEQ[@]}"; do
  round=${item%%:*}; arm=${item##*:}
  run_one "$round" "$arm"
  if [[ "$round" == discard* ]]; then
    # 버림판은 표에서 뺀다. 실패해도 FAILED 에 남겨 «버림판이 안 돌았다» 를 보이게 둔다.
    sed -i "/^${round}\t${arm}\t/d" "$LOG" 2>/dev/null
    echo "  (버림판 — 표에서 제외)"
  fi
done

finish "$TOTAL"

cat <<'EOF'

────────────────────────────────────────────────────────────────
읽는 법 (설계 §6 가설 대조)

  H1  팔 A 는 DDL 전 구간 쓰기 차단
      → max_stall_ms ≈ ddl_s×1000 이면 지지. 훨씬 작으면 **H1 반증**이고,
        그건 «96분을 아픔이라 부른 게 틀렸다» 는 정정이 된다(설계 §6).
  H2  팔 B 총 소요 > 팔 A          → ddl_s 비교
  H3  팔 B 의 차단은 컷오버 순간에 국한
      → 팔 B 의 max_stall_ms 가 작고 errors 가 0~소수면 지지.
        이 값이 이 실험의 1차 산출물이다 — 「무중단」의 실제 값.
  H4  팔 B 진행 중 DML 이 느려짐   → 팔 B 의 p50_ms vs 팔 A 의 p50_ms(차단 전 구간)
  H5  팔 B 디스크 피크 ≈ 원본 2배  → disk_peak_mb 비교

AWS 승격 판단 (설계 §9 결정)
  팔 A 3판의 ddl_s 범위와 팔 B 3판의 ddl_s 범위가 **겹치면** 로컬 잡음이 팔 차이를
  삼킨 것이다 → EC2 로 올려 재측정. 겹치지 않으면 로컬 결론을 그대로 쓴다.
  (임의 임계값이 아니라 관측된 범위끼리의 비교다.)

인용 금지
  · 절대 소요 시간을 «운영에서 N분» 으로 (하드웨어 종속)
  · 이 수를 «현재 pose_data 의 값» 으로 (시더 정의가 실 테이블과 어긋남, 이슈 #153)
────────────────────────────────────────────────────────────────
EOF
