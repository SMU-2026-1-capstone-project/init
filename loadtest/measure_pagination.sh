#!/usr/bin/env bash
# ②(c) 전체 테이블 페이지네이션 실측 — offset vs keyset(cursor)
# RealMySQL 실험 (docs/portfolio/realmysql-experiments.md §4 ②(c))
#
# 데이터: pose_data_scale 9,750만 행, PK(id, created_at), 버퍼풀 2GB.
# 가설: LIMIT n OFFSET m 은 m(깊이)에 선형 비례 저하(앞 m행 스캔 후 폐기),
#       keyset(WHERE id > last)은 PK 범위 점프라 평탄(≈O(log n)).
# 측정: EXPLAIN ANALYZE 최상위(Limit) 노드 actual time 끝값(ms), warm(3회째).
#
# 📌 이 rig 이 낸 «최대 489,868배» 를 인용할 때의 조건:
#    docs/portfolio/realmysql-experiments.md #keyset-489868x
#    - 깊이 5,000만 한 점의 값이다. 10만 깊이면 798배, 0 이면 1배다
#    - EXPLAIN ANALYZE(DB 내부 타이밍)라 SSH 왕복 오염이 없다 — AWS 재검증판(2026-07-16)의
#      keyset 54~56ms 에 붙는 「~50ms 바닥」 캐비엇은 이 판에는 해당하지 않는다
set -u
PW=1234
DB(){ docker exec shadowfit-mysql mysql -uroot -p$PW shadowfit "$@" 2>/dev/null; }
DEPTHS="0 10000 100000 1000000 10000000 50000000"

# EXPLAIN ANALYZE 첫 노드(Limit) actual time=A..B 의 B(완료시각) 추출
measure(){ DB -e "EXPLAIN ANALYZE $1\G" | grep -oP 'actual time=[0-9.]+\.\.\K[0-9.]+' | head -1; }

echo "## ②(c) offset vs keyset — pose_data_scale 9,750만 행, warm"
printf "%-12s %-14s %-14s %-10s\n" depth offset_ms keyset_ms speedup
for N in $DEPTHS; do
  # keyset 기준 id = 해당 깊이의 시작 id
  bid=$(DB -N -e "SELECT id FROM pose_data_scale ORDER BY id LIMIT 1 OFFSET $N")
  o=""; k=""
  for r in 1 2 3; do o=$(measure "SELECT id,session_id,timestamp_sec FROM pose_data_scale ORDER BY id LIMIT 20 OFFSET $N"); done
  for r in 1 2 3; do k=$(measure "SELECT id,session_id,timestamp_sec FROM pose_data_scale WHERE id >= $bid ORDER BY id LIMIT 20"); done
  sp=$(awk -v a="$o" -v b="$k" 'BEGIN{ if(b>0) printf "%.0fx", a/b; else print "-" }')
  printf "%-12s %-14s %-14s %-10s\n" "$N" "$o" "$k" "$sp"
done
echo "DONE"
