#!/usr/bin/env bash
# 관리자 대시보드 b(상태별 분포) 집계 — 볼륨별 비용 곡선
# (docs/decisions/admin-page-scope.md §4-5 ②-1, 2026-08-06)
#
# 질문: b 는 전체 기간 GROUP BY 라 데이터가 쌓이면 반드시 느려진다 — 고 §4-5 ② 가 적었는데,
#       그건 "커버링 인덱스 전체 스캔은 O(n)" 이라는 **논증**이지 관측이 아니었다.
#       실제로 행 수에 선형인가.
#
# 전제: measure_admin_filter_explain.sh 가 만든 스크래치 DB(shadowfit_explain, 세션 100만)가
#       이미 있어야 한다. 여기서는 그 테이블을 크기별로 복제해 쓴다.
#
# ── 왜 DELETE 로 줄이지 않는가 ────────────────────────────────────────────────
#   줄여가며 재면 재는 것이 "행 수 효과"인지 "파편화 효과"인지 갈라낼 수 없다.
#   (작성 당시 파편화는 미검증 항목이었다. 2026-08-09 에 실측했고 — results/delete-fragmentation-2026-08-09/ —
#    구멍 뚫기 삭제에서 **+24% 계단**이 관측됐다. 즉 이 회피는 결과적으로도 옳았다:
#    줄여가며 쟀다면 그 24% 가 행 수 효과에 섞여 들어왔을 것이다.)
#   그래서 크기별로 테이블을 새로 만들어 갓 만든 인덱스 위에서 각각 잰다.
#
# ── 왜 7회 중앙값인가, 그리고 왜 최소값도 같이 내는가 ─────────────────────────
#   1차 측정(3회)에서 250k 지점이 125~394ms 로 3배 튀어 곡선이 비단조로 보였다. 2코어에
#   컨테이너 4개가 동거하는 환경이라 단발 측정은 이웃 프로세스에 그대로 흔들린다.
#
#   ⚠️ 그런데 7회 중앙값으로도 250k(220ms)와 500k(224ms)가 거의 같게 나온다. **최소값**으로
#      보면 행당 0.37~0.47µs 로 선형이 선명하다. 이유는 동거 노이즈가 **시간을 늘리기만 하지
#      줄이지 않는다**는 것 — 최소값이 "방해 없이 돌았을 때"에 가장 가깝고, 중앙값은 이웃
#      컨테이너의 상태를 같이 재고 있다. 이 환경에서는 최소값을 신호로 읽을 것.
set -euo pipefail
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

PW=1234
DB=shadowfit_explain
C=shadowfit-mysql
SIZES="50000 100000 250000 500000 1000000"
REPS=7

Q(){ docker exec -i "$C" mysql -uroot -p$PW "$DB" "$@" 2>/dev/null; }

# ⚠️ 실패해도 만든 표를 남기지 않는다. set -euo pipefail 이라 EXPLAIN ANALYZE 한 번만
#    실패해도 마지막 정리 루프에 도달하지 못하고, 그러면 es_50000~es_1000000 이 스크래치
#    DB 에 그대로 남는다(1M 짜리까지 있어 디스크도 먹는다). 다음 실행이 이전 잔여물 위에서
#    도는 것은 §4-2 결함 #3 이 지적한 "이전 실행의 잔여 파일로 그럴듯한 요약" 과 같은 계열이다.
cleanup(){
  local rc=$?
  for n in $SIZES; do Q -e "DROP TABLE IF EXISTS es_$n;" >/dev/null 2>&1 || true; done
  [[ $rc -ne 0 ]] && echo "!! 비정상 종료(exit $rc) — 볼륨별 임시 표를 정리했다." >&2
  return 0
}
trap cleanup EXIT

echo "### 볼륨별 테이블 생성"
for n in $SIZES; do
  Q -e "
    DROP TABLE IF EXISTS es_$n;
    CREATE TABLE es_$n LIKE exercise_sessions;
    INSERT INTO es_$n SELECT * FROM exercise_sessions LIMIT $n;
    ANALYZE TABLE es_$n;" >/dev/null
done
echo "  완료"
echo

echo "### b 집계 actual time — ${REPS}회 중 중앙값 (ms)"
printf "%10s  %9s  %9s  %9s  %14s\n" "행수" "중앙값" "최소" "최대" "행당(us)"
for n in $SIZES; do
  Q -e "SELECT status,count(id) FROM es_$n GROUP BY status;" >/dev/null   # 워밍업
  vals=""
  for i in $(seq $REPS); do
    t=$(Q --vertical -e "EXPLAIN ANALYZE SELECT status,count(id) FROM es_$n GROUP BY status;" \
        | grep -o 'actual time=[0-9.]*\.\.[0-9.]*' | head -1 | sed 's/.*\.\.//')
    vals="$vals$t\n"
  done
  sorted=$(printf "$vals" | sort -n)
  med=$(printf "%s\n" "$sorted" | awk 'NR==4')
  min=$(printf "%s\n" "$sorted" | head -1)
  max=$(printf "%s\n" "$sorted" | tail -1)
  per=$(awk -v m="$med" -v n="$n" 'BEGIN{printf "%.2f", m*1000/n}')
  printf "%10s  %9s  %9s  %9s  %14s\n" "$n" "$med" "$min" "$max" "$per"
done
echo

for n in $SIZES; do Q -e "DROP TABLE IF EXISTS es_$n;" >/dev/null; done
echo "### 정리 완료"
