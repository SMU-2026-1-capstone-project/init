#!/usr/bin/env bash
# #204 §2-ㄴ 전용 — 「커버링이 아니라서 생기는 행 본체 랜덤 접근」의 크기
#
# 왜 따로 재나: A→B→C 스윕은 **팔 순서와 캐시 상태가 교락**한다(A 가 제일 차가운 버퍼풀에서
# 돌았다). 계획 모양과 핸들러 카운터는 순서와 무관하지만 **시간과 페이지 접근은 아니다.**
#
# 그래서 여기서는 팔을 바꾸지 않는다 — 인덱스 둘이 **동시에 존재하는** 상태에서
# FORCE INDEX 로만 경로를 가르고, 두 경로를 **번갈아** 돌린다(버림판 1 + 교대 N판).
#   · uk_pose_event     … 정렬은 맞지만 sync_rate·smoothed_knee_angle 이 없다 → 행 본체로 간다
#   · idx_report_cover  … 네 컬럼이 다 들어 있다 → 인덱스만 읽고 끝난다
#
# 지표: Innodb_buffer_pool_read_requests 델타 = **논리 페이지 접근 수**.
#   행 본체 접근이 실제로 붙는지 아닌지가 여기서 갈린다. (박스가 놀고 있어야 유효 —
#   전역 카운터다. 로컬 2코어 동거라 시간은 여전히 참고값이다.)
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a

N=${N:-6}
TAG=${TAG:-seed204}
OUT=${OUT:-loadtest/results/report-query-explain-2026-08-19}
DB(){ docker exec -i shadowfit-mysql mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit "$@" 2>/dev/null; }

for idx in uk_pose_event idx_report_cover; do
  c=$(DB -e "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='shadowfit' AND table_name='pose_data' AND index_name='$idx'")
  [ "${c:-0}" -gt 0 ] || { echo "!! $idx 가 없다 — measure_report_query_explain.sh C 를 먼저 돌릴 것"; exit 1; }
done

mapfile -t SIDS < <(DB -e "SELECT id FROM exercise_sessions WHERE reference_source='$TAG' AND id % 89 = 0 ORDER BY id LIMIT $((N+1))")

Q(){ # $1=index  $2=session
  echo "SELECT timestamp_sec, sync_rate, rep_number, smoothed_knee_angle
          FROM pose_data FORCE INDEX ($1) WHERE session_id = $2
         ORDER BY rep_number ASC, timestamp_sec ASC"
}

run(){ # $1=index $2=session → "pages<TAB>ms"
  docker exec -i shadowfit-mysql mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit -e "
    SET @p0 := (SELECT VARIABLE_VALUE FROM performance_schema.global_status
                 WHERE VARIABLE_NAME='Innodb_buffer_pool_read_requests');
    SET @t0 := NOW(6);
    $(Q "$1" "$2");
    SET @t1 := NOW(6);
    SELECT '===', (SELECT VARIABLE_VALUE FROM performance_schema.global_status
                    WHERE VARIABLE_NAME='Innodb_buffer_pool_read_requests') - @p0 AS pages,
           ROUND(TIMESTAMPDIFF(MICROSECOND, @t0, @t1)/1000, 2) AS ms;" 2>/dev/null \
  | awk -F'\t' '/^===/{print $2"\t"$3}'
}

{
echo "# §2-ㄴ 커버링 — 행 본체 랜덤 접근의 크기"
echo
echo "같은 팔(C, 인덱스 둘 다 존재) 안에서 FORCE INDEX 로만 경로를 가르고 **교대**로 돌린다."
echo "지표는 \`Innodb_buffer_pool_read_requests\` 델타 = 논리 페이지 접근 수. 세션당 750행."
echo
echo "| 판 | 세션 | uk_pose_event (비커버링) 페이지 | ms | idx_report_cover (커버링) 페이지 | ms |"
echo "|---|---|---|---|---|---|"
i=0
uk_pages=(); cv_pages=(); uk_ms=(); cv_ms=()
for s in "${SIDS[@]}"; do
  i=$((i+1))
  a=$(run uk_pose_event "$s");    ap=${a%%$'\t'*}; am=${a##*$'\t'}
  b=$(run idx_report_cover "$s"); bp=${b%%$'\t'*}; bm=${b##*$'\t'}
  if [ "$i" = 1 ]; then
    echo "| 0 | $s | $ap | $am | $bp | $bm | ← 워밍업(버림) |"
  else
    echo "| $((i-1)) | $s | $ap | $am | $bp | $bm |"
    uk_pages+=("$ap"); cv_pages+=("$bp"); uk_ms+=("$am"); cv_ms+=("$bm")
  fi
done
echo
med(){ printf '%s\n' "$@" | sort -n | awk '{v[NR]=$1} END{print (NR%2)? v[(NR+1)/2] : (v[NR/2]+v[NR/2+1])/2}'; }
echo "**중앙값** — 페이지: 비커버링 $(med "${uk_pages[@]}") ↔ 커버링 $(med "${cv_pages[@]}")"
echo "· 시간(참고값, 로컬 2코어): 비커버링 $(med "${uk_ms[@]}")ms ↔ 커버링 $(med "${cv_ms[@]}")ms"
} | tee "$OUT/covering-cost.md"
