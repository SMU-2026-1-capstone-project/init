#!/usr/bin/env bash
# ②(d) 파티션 실측 — Range 파티셔닝 + pruning + DROP PARTITION vs DELETE WHERE
# RealMySQL 실험 (docs/portfolio/realmysql-experiments.md §4 ②(d))
#
# 데이터: pose_data_scale 1억 행, PK(id, created_at), created_at 2026-01~2027-01 월별 ~8M 행.
# 가설: ① created_at Range 파티션 → 날짜범위 쿼리는 EXPLAIN partitions 컬럼에 단일 파티션만(pruning).
#       ② TTL 만료 시 DROP PARTITION 은 O(1) 메타데이터 연산(락 거의 없음, 디스크 즉시 회수),
#          DELETE WHERE 는 같은 ~8M 행을 행단위 삭제(undo·binlog·락 폭발) → 수 초~수십 초.
# 핵심 reframe: 파티션의 가치는 쿼리 pruning 이 아니라(쿼리는 이미 인덱스로 빠름) "값싼 TTL".
#
# 📌 이 rig 이 낸 «625배»·«96분» 을 인용할 때의 조건:
#    docs/portfolio/realmysql-experiments.md #drop-partition-625x
#    - 두 팔의 행수가 다르다(DELETE 8,301,450 ↔ DROP 7,560,000) → 행당 정규화하면 570배
#    - DROP 1.8초의 대부분도 O(1) 이 아니라 ~910MB .ibd 삭제 I/O 다
#    - 절대 시간(18.6분·1.8초·96분)은 하드웨어 종속이라 「운영에서 N분」으로 인용 금지
set -u
PW=1234
DB(){ docker exec shadowfit-mysql mysql -uroot -p$PW shadowfit "$@" 2>/dev/null; }

# created_at TIMESTAMP 은 RANGE(UNIX_TIMESTAMP(...)) 로 파티셔닝 (RANGE COLUMNS 는 TIMESTAMP 미지원).
# 파티션 표현식 컬럼(created_at)은 모든 UNIQUE 키에 포함돼야 함 → PK(id, created_at) 충족.
PARTITION_DDL="
ALTER TABLE pose_data_scale
PARTITION BY RANGE (UNIX_TIMESTAMP(created_at)) (
  PARTITION p2026_01 VALUES LESS THAN (UNIX_TIMESTAMP('2026-02-01 00:00:00')),
  PARTITION p2026_02 VALUES LESS THAN (UNIX_TIMESTAMP('2026-03-01 00:00:00')),
  PARTITION p2026_03 VALUES LESS THAN (UNIX_TIMESTAMP('2026-04-01 00:00:00')),
  PARTITION p2026_04 VALUES LESS THAN (UNIX_TIMESTAMP('2026-05-01 00:00:00')),
  PARTITION p2026_05 VALUES LESS THAN (UNIX_TIMESTAMP('2026-06-01 00:00:00')),
  PARTITION p2026_06 VALUES LESS THAN (UNIX_TIMESTAMP('2026-07-01 00:00:00')),
  PARTITION p2026_07 VALUES LESS THAN (UNIX_TIMESTAMP('2026-08-01 00:00:00')),
  PARTITION p2026_08 VALUES LESS THAN (UNIX_TIMESTAMP('2026-09-01 00:00:00')),
  PARTITION p2026_09 VALUES LESS THAN (UNIX_TIMESTAMP('2026-10-01 00:00:00')),
  PARTITION p2026_10 VALUES LESS THAN (UNIX_TIMESTAMP('2026-11-01 00:00:00')),
  PARTITION p2026_11 VALUES LESS THAN (UNIX_TIMESTAMP('2026-12-01 00:00:00')),
  PARTITION p2026_12 VALUES LESS THAN (UNIX_TIMESTAMP('2027-01-01 00:00:00')),
  PARTITION p2027_01 VALUES LESS THAN (UNIX_TIMESTAMP('2027-02-01 00:00:00')),
  PARTITION pfuture  VALUES LESS THAN MAXVALUE
);"

echo "## [1] 파티션 변환 (1억 행 풀 리빌드 — 시간 측정)"
t0=$(date +%s); DB -e "$PARTITION_DDL"; t1=$(date +%s)
echo "ALTER PARTITION BY: $((t1-t0)) s"

echo
echo "## [2] 파티션별 행수 (information_schema.partitions)"
DB -t -e "SELECT partition_name, table_rows
          FROM information_schema.partitions
          WHERE table_name='pose_data_scale' AND partition_name IS NOT NULL
          ORDER BY partition_ordinal_position;"

echo
echo "## [3] pruning — 단일 월 범위 쿼리는 partitions 컬럼에 1개만"
echo "### (a) 날짜범위 WHERE → pruning 기대"
DB -e "EXPLAIN SELECT COUNT(*) FROM pose_data_scale
       WHERE created_at >= '2026-06-01' AND created_at < '2026-07-01'\G" | grep -E 'partitions|table:'
echo "### (b) 범위 없는 집계 → 전 파티션 스캔(pruning 없음, 대조)"
DB -e "EXPLAIN SELECT COUNT(*) FROM pose_data_scale\G" | grep -E 'partitions|table:'

echo
echo "## [4] ⭐ DROP PARTITION vs DELETE WHERE — 같은 ~8M 행 만료"
# ⚠️ information_schema.tables 의 data_length 는 캐시된 추정치라 회수 즉시 반영 안 됨.
#    실제 공간 회수 증거는 디스크의 파티션 .ibd 파일(아래 IBD 함수)로 확인.
IBD(){ docker exec shadowfit-mysql bash -c \
  "ls -la /var/lib/mysql/shadowfit/pose_data_scale#p#$1.ibd 2>/dev/null | awk '{printf \"%.0f MB\\n\", \$5/1024/1024}' || echo '파일 없음(삭제됨)'"; }

echo "### (a) DELETE WHERE created_at < '2026-02-01'  (p2026_01 ≈ 8.3M 행, 행단위)"
n_del=$(DB -N -e "SELECT COUNT(*) FROM pose_data_scale WHERE created_at < '2026-02-01'")
t0=$(date +%s%3N); DB -e "DELETE FROM pose_data_scale WHERE created_at < '2026-02-01';"; t1=$(date +%s%3N)
echo "DELETE ${n_del} 행: $((t1-t0)) ms"
echo "  ↳ DELETE 후: p2026_01 파티션 비었되 잔존, .ibd 파일은 그대로(공간 미회수):"
DB -t -e "SELECT partition_name, table_rows FROM information_schema.partitions
          WHERE table_name='pose_data_scale' AND partition_name='p2026_01';"
echo "     p2026_01.ibd 디스크: $(IBD p2026_01)   ← 0행인데 파일 안 줄어듦"

echo "### (b) DROP PARTITION p2026_02  (≈ 7.56M 행, O(1) 메타데이터)"
n_drop=$(DB -N -e "SELECT COUNT(*) FROM pose_data_scale WHERE created_at >= '2026-02-01' AND created_at < '2026-03-01'")
t0=$(date +%s%3N); DB -e "ALTER TABLE pose_data_scale DROP PARTITION p2026_02;"; t1=$(date +%s%3N)
echo "DROP PARTITION ${n_drop} 행: $((t1-t0)) ms"
echo "     p2026_02.ibd 디스크: $(IBD p2026_02)   ← 파일째 삭제, 공간 즉시 회수"
echo
echo "# 실측(2026-06-03, 로컬): ALTER PARTITION BY 5,767s / DELETE 1,118,936ms(18.6분) / DROP 1,790ms(1.8초) ≈ 625x"
echo "#   ↑ 두 팔의 행수가 다르다(8,301,450 ↔ 7,560,000) — 행당 정규화하면 570x."
echo "#   인용 조건: docs/portfolio/realmysql-experiments.md #drop-partition-625x"
echo "DONE"
