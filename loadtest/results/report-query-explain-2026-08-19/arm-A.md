# ARM A — #204 EXPLAIN 스윕

- 실행: 5f8f776 · 표본 세션 2813 2716 2619 2522 2425
- pose_data 인덱스:
```
Key_name	Seq_in_index	Column_name
PRIMARY	1	id
PRIMARY	2	created_at
idx_session_timestamp	1	session_id
idx_session_timestamp	2	timestamp_sec
```
```
rows_total	sessions
1590434	2203
PARTITION_NAME	TABLE_ROWS
p2026_01	97031
p2026_02	88946
p2026_03	98186
p2026_04	95299
p2026_05	98186
p2026_06	168180
p2026_07	98178
p2026_08	105907
p2026_09	95299
p2026_10	98186
p2026_11	95299
p2026_12	97609
```

### R1 🔴 findFramesBySessionId — 리포트 핵심 경로 (§2)
```sql
SELECT timestamp_sec, sync_rate, rep_number, smoothed_knee_angle FROM pose_data WHERE session_id = 2813 ORDER BY rep_number ASC, timestamp_sec ASC
```

**EXPLAIN**
```
  select_type: SIMPLE
        table: pose_data
   partitions: p2026_01,p2026_02,p2026_03,p2026_04,p2026_05,p2026_06,p2026_07,p2026_08,p2026_09,p2026_10,p2026_11,p2026_12,p2027_01,pfuture
         type: ref
possible_keys: idx_session_timestamp
          key: idx_session_timestamp
      key_len: 8
         rows: 750
     filtered: 100.00
        Extra: Using filesort
```
**EXPLAIN FORMAT=TREE**
```
EXPLAIN: -> Sort: pose_data.rep_number, pose_data.timestamp_sec  (cost=825 rows=750)
    -> Index lookup on pose_data using idx_session_timestamp (session_id=2813)  (cost=825 rows=750)

```
**핸들러 카운터** (FLUSH STATUS 직후 1회 실행)
```
Handler_read_key	14
Handler_read_next	750
Sort_rows	750
Sort_scan	1
```
**EXPLAIN ANALYZE 실제시간** (세션 5 개 · 첫 판은 워밍업으로 버린다)
```
  s=2813  actual time=4.02..4.06 rows=750 loops=1   ← 워밍업(버림)
  s=2716  actual time=97.5..97.5 rows=750 loops=1
  s=2619  actual time=2629..2629 rows=750 loops=1
  s=2522  actual time=1122..1122 rows=750 loops=1
  s=2425  actual time=2250..2250 rows=750 loops=1
```

### R1p 후보수정 ㄱ — created_at 범위를 같이 넘긴 판
```sql
SELECT timestamp_sec, sync_rate, rep_number, smoothed_knee_angle FROM pose_data WHERE session_id = 2813 AND created_at >= '2026-01-03 01:45:00' AND created_at < '2026-01-03 01:45:00' + INTERVAL 1 DAY ORDER BY rep_number ASC, timestamp_sec ASC
```

**EXPLAIN**
```
  select_type: SIMPLE
        table: pose_data
   partitions: p2026_01
         type: ref
possible_keys: idx_session_timestamp
          key: idx_session_timestamp
      key_len: 8
         rows: 750
     filtered: 11.11
        Extra: Using index condition; Using filesort
```
**EXPLAIN FORMAT=TREE**
```
EXPLAIN: -> Sort: pose_data.rep_number, pose_data.timestamp_sec  (cost=196 rows=750)
    -> Index lookup on pose_data using idx_session_timestamp (session_id=2813), with index condition: ((pose_data.created_at >= TIMESTAMP'2026-01-03 01:45:00') and (pose_data.created_at < <cache>(('2026-01-03 01:45:00' + interval 1 day))))  (cost=196 rows=750)

```
**핸들러 카운터** (FLUSH STATUS 직후 1회 실행)
```
Handler_read_key	1
Handler_read_next	750
Sort_rows	750
Sort_scan	1
```
**EXPLAIN ANALYZE 실제시간** (세션 5 개 · 첫 판은 워밍업으로 버린다)
```
  s=2813  actual time=11.1..11.2 rows=750 loops=1   ← 워밍업(버림)
  s=2716  actual time=0.028..0.028 rows=0 loops=1
  s=2619  actual time=0.122..0.122 rows=0 loops=1
  s=2522  actual time=0.0281..0.0281 rows=0 loops=1
  s=2425  actual time=0.0327..0.0327 rows=0 loops=1
```

### R2 🟡 findMaxRepNumberBySessionId
```sql
SELECT COALESCE(MAX(rep_number),0) FROM pose_data WHERE session_id = 2813
```

**EXPLAIN**
```
  select_type: SIMPLE
        table: pose_data
   partitions: p2026_01,p2026_02,p2026_03,p2026_04,p2026_05,p2026_06,p2026_07,p2026_08,p2026_09,p2026_10,p2026_11,p2026_12,p2027_01,pfuture
         type: ref
possible_keys: idx_session_timestamp
          key: idx_session_timestamp
      key_len: 8
         rows: 750
     filtered: 100.00
        Extra: NULL
```
**EXPLAIN FORMAT=TREE**
```
EXPLAIN: -> Aggregate: max(pose_data.rep_number)  (cost=900 rows=1)
    -> Index lookup on pose_data using idx_session_timestamp (session_id=2813)  (cost=825 rows=750)

```
**핸들러 카운터** (FLUSH STATUS 직후 1회 실행)
```
Handler_read_key	14
Handler_read_next	750
```
**EXPLAIN ANALYZE 실제시간** (세션 5 개 · 첫 판은 워밍업으로 버린다)
```
  s=2813  actual time=2.79..2.79 rows=1 loops=1   ← 워밍업(버림)
  s=2716  actual time=4.98..4.98 rows=1 loops=1
  s=2619  actual time=14..14 rows=1 loops=1
  s=2522  actual time=5.45..5.45 rows=1 loops=1
  s=2425  actual time=6.34..6.34 rows=1 loops=1
```

### R3 🟢 findMaxTimestampSecBySessionId
```sql
SELECT COALESCE(MAX(timestamp_sec),0.0) FROM pose_data WHERE session_id = 2813
```

**EXPLAIN**
```
  select_type: SIMPLE
        table: NULL
   partitions: NULL
         type: NULL
possible_keys: NULL
          key: NULL
      key_len: NULL
         rows: NULL
     filtered: NULL
        Extra: Select tables optimized away
```
**EXPLAIN FORMAT=TREE**
```
EXPLAIN: -> Rows fetched before execution  (cost=0..0 rows=1)

```
**핸들러 카운터** (FLUSH STATUS 직후 1회 실행)
```
Handler_read_key	14
```
**EXPLAIN ANALYZE 실제시간** (세션 5 개 · 첫 판은 워밍업으로 버린다)
```
  s=2813     ← 워밍업(버림)
  s=2716  
  s=2619  
  s=2522  
  s=2425  actual time=0..0 rows=1 loops=1
```

### R4 🟡 findRepAverageSyncRates
```sql
SELECT AVG(sync_rate) FROM pose_data WHERE session_id = 2813 AND rep_number > 0 GROUP BY rep_number ORDER BY rep_number
```

**EXPLAIN**
```
  select_type: SIMPLE
        table: pose_data
   partitions: p2026_01,p2026_02,p2026_03,p2026_04,p2026_05,p2026_06,p2026_07,p2026_08,p2026_09,p2026_10,p2026_11,p2026_12,p2027_01,pfuture
         type: ref
possible_keys: idx_session_timestamp
          key: idx_session_timestamp
      key_len: 8
         rows: 750
     filtered: 33.33
        Extra: Using where; Using temporary; Using filesort
```
**EXPLAIN FORMAT=TREE**
```
EXPLAIN: -> Sort: pose_data.rep_number
    -> Table scan on <temporary>
        -> Aggregate using temporary table
            -> Filter: (pose_data.rep_number > 0)  (cost=775 rows=250)
                -> Index lookup on pose_data using idx_session_timestamp (session_id=2813)  (cost=775 rows=750)

```
**핸들러 카운터** (FLUSH STATUS 직후 1회 실행)
```
Created_tmp_tables	1
Handler_read_key	764
Handler_read_next	750
Handler_read_rnd_next	26
Sort_rows	25
Sort_scan	1
```
**EXPLAIN ANALYZE 실제시간** (세션 5 개 · 첫 판은 워밍업으로 버린다)
```
  s=2813  actual time=4.41..4.42 rows=25 loops=1   ← 워밍업(버림)
  s=2716  actual time=4.33..4.34 rows=25 loops=1
  s=2619  actual time=5.06..5.06 rows=25 loops=1
  s=2522  actual time=4.03..4.03 rows=25 loops=1
  s=2425  actual time=5.81..5.81 rows=25 loops=1
```

### R5 🟢 countSince — 프루닝이 걸린다고 적힌 대조군
```sql
SELECT COUNT(*) FROM pose_data WHERE session_id IN (2813) AND created_at > '2026-12-01 00:00:00'
```

**EXPLAIN**
```
  select_type: SIMPLE
        table: pose_data
   partitions: p2026_12,p2027_01,pfuture
         type: ref
possible_keys: idx_session_timestamp
          key: idx_session_timestamp
      key_len: 8
         rows: 1
     filtered: 33.33
        Extra: Using where; Using index
```
**EXPLAIN FORMAT=TREE**
```
EXPLAIN: -> Aggregate: count(0)  (cost=0.317 rows=1)
    -> Filter: (pose_data.created_at > TIMESTAMP'2026-12-01 00:00:00')  (cost=0.283 rows=0.333)
        -> Covering index lookup on pose_data using idx_session_timestamp (session_id=2813)  (cost=0.283 rows=1)

```
**핸들러 카운터** (FLUSH STATUS 직후 1회 실행)
```
Handler_read_key	3
```
**EXPLAIN ANALYZE 실제시간** (세션 5 개 · 첫 판은 워밍업으로 버린다)
```
  s=2813  actual time=0.0496..0.0498 rows=1 loops=1   ← 워밍업(버림)
  s=2716  actual time=0.0323..0.0325 rows=1 loops=1
  s=2619  actual time=0.0157..0.0158 rows=1 loops=1
  s=2522  actual time=0.718..0.718 rows=1 loops=1
  s=2425  actual time=0.0161..0.0162 rows=1 loops=1
```

### R6 🔴 deleteBySessionIdIn — EXPLAIN 만 (실행 안 함)
```
*************************** 1. row ***************************
           id: 1
  select_type: DELETE
        table: pose_data
   partitions: p2026_01,p2026_02,p2026_03,p2026_04,p2026_05,p2026_06,p2026_07,p2026_08,p2026_09,p2026_10,p2026_11,p2026_12,p2027_01,pfuture
         type: range
possible_keys: idx_session_timestamp
          key: idx_session_timestamp
      key_len: 8
          ref: const
         rows: 750
     filtered: 100.00
        Extra: Using where
```
