# ARM B — #204 EXPLAIN 스윕

- 실행: 5f8f776 · 표본 세션 2813 2716 2619 2522 2425
- pose_data 인덱스:
```
Key_name	Seq_in_index	Column_name
PRIMARY	1	id
PRIMARY	2	created_at
uk_pose_event	1	session_id
uk_pose_event	2	rep_number
uk_pose_event	3	timestamp_sec
uk_pose_event	4	created_at
idx_session_timestamp	1	session_id
idx_session_timestamp	2	timestamp_sec
```
```
rows_total	sessions
1588399	2203
PARTITION_NAME	TABLE_ROWS
p2026_01	97031
p2026_02	88946
p2026_03	98186
p2026_04	95299
p2026_05	98186
p2026_06	161873
p2026_07	98178
p2026_08	104259
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
possible_keys: uk_pose_event,idx_session_timestamp
          key: uk_pose_event
      key_len: 8
         rows: 750
     filtered: 100.00
        Extra: NULL
```
**EXPLAIN FORMAT=TREE**
```
EXPLAIN: -> Index lookup on pose_data using uk_pose_event (session_id=2813)  (cost=825 rows=750)

```
**핸들러 카운터** (FLUSH STATUS 직후 1회 실행)
```
Handler_read_key	14
Handler_read_next	750
```
**EXPLAIN ANALYZE 실제시간** (세션 5 개 · 첫 판은 워밍업으로 버린다)
```
  s=2813  actual time=0.176..5.52 rows=750 loops=1   ← 워밍업(버림)
  s=2716  actual time=1.1..266 rows=750 loops=1
  s=2619  actual time=0.202..5.1 rows=750 loops=1
  s=2522  actual time=1.09..5.2 rows=750 loops=1
  s=2425  actual time=0.0798..7.37 rows=750 loops=1
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
possible_keys: uk_pose_event,idx_session_timestamp
          key: uk_pose_event
      key_len: 8
         rows: 750
     filtered: 11.11
        Extra: Using index condition
```
**EXPLAIN FORMAT=TREE**
```
EXPLAIN: -> Index lookup on pose_data using uk_pose_event (session_id=2813), with index condition: ((pose_data.created_at >= TIMESTAMP'2026-01-03 01:45:00') and (pose_data.created_at < <cache>(('2026-01-03 01:45:00' + interval 1 day))))  (cost=196 rows=750)

```
**핸들러 카운터** (FLUSH STATUS 직후 1회 실행)
```
Handler_read_key	1
Handler_read_next	750
```
**EXPLAIN ANALYZE 실제시간** (세션 5 개 · 첫 판은 워밍업으로 버린다)
```
  s=2813  actual time=0.0295..2.48 rows=750 loops=1   ← 워밍업(버림)
  s=2716  actual time=0.0265..0.0265 rows=0 loops=1
  s=2619  actual time=0.0177..0.0177 rows=0 loops=1
  s=2522  actual time=0.0214..0.0214 rows=0 loops=1
  s=2425  actual time=0.0569..0.0569 rows=0 loops=1
```

### R2 🟡 findMaxRepNumberBySessionId
```sql
SELECT COALESCE(MAX(rep_number),0) FROM pose_data WHERE session_id = 2813
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
  s=2716  actual time=0..0 rows=1 loops=1
  s=2619  
  s=2522  
  s=2425  
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
  s=2522  actual time=0..0 rows=1 loops=1
  s=2425  
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
         type: range
possible_keys: uk_pose_event,idx_session_timestamp
          key: uk_pose_event
      key_len: 12
         rows: 750
     filtered: 100.00
        Extra: Using index condition
```
**EXPLAIN FORMAT=TREE**
```
EXPLAIN: -> Group aggregate: avg(pose_data.sync_rate)  (cost=2550 rows=750)
    -> Index range scan on pose_data using uk_pose_event over (session_id = 2813 AND 0 < rep_number), with index condition: ((pose_data.session_id = 2813) and (pose_data.rep_number > 0))  (cost=2475 rows=750)

```
**핸들러 카운터** (FLUSH STATUS 직후 1회 실행)
```
Handler_read_key	14
Handler_read_next	750
```
**EXPLAIN ANALYZE 실제시간** (세션 5 개 · 첫 판은 워밍업으로 버린다)
```
  s=2813  actual time=0.295..3.61 rows=25 loops=1   ← 워밍업(버림)
  s=2716  actual time=0.281..3.65 rows=25 loops=1
  s=2619  actual time=0.429..3.98 rows=25 loops=1
  s=2522  actual time=0.292..2.3 rows=25 loops=1
  s=2425  actual time=0.355..3.84 rows=25 loops=1
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
possible_keys: uk_pose_event,idx_session_timestamp
          key: uk_pose_event
      key_len: 8
         rows: 1
     filtered: 33.33
        Extra: Using where; Using index
```
**EXPLAIN FORMAT=TREE**
```
EXPLAIN: -> Aggregate: count(0)  (cost=0.317 rows=1)
    -> Filter: (pose_data.created_at > TIMESTAMP'2026-12-01 00:00:00')  (cost=0.283 rows=0.333)
        -> Covering index lookup on pose_data using uk_pose_event (session_id=2813)  (cost=0.283 rows=1)

```
**핸들러 카운터** (FLUSH STATUS 직후 1회 실행)
```
Handler_read_key	3
```
**EXPLAIN ANALYZE 실제시간** (세션 5 개 · 첫 판은 워밍업으로 버린다)
```
  s=2813  actual time=0.027..0.0272 rows=1 loops=1   ← 워밍업(버림)
  s=2716  actual time=0.0161..0.0162 rows=1 loops=1
  s=2619  actual time=0.0168..0.0169 rows=1 loops=1
  s=2522  actual time=0.022..0.0221 rows=1 loops=1
  s=2425  actual time=0.0499..0.05 rows=1 loops=1
```

### R6 🔴 deleteBySessionIdIn — EXPLAIN 만 (실행 안 함)
```
*************************** 1. row ***************************
           id: 1
  select_type: DELETE
        table: pose_data
   partitions: p2026_01,p2026_02,p2026_03,p2026_04,p2026_05,p2026_06,p2026_07,p2026_08,p2026_09,p2026_10,p2026_11,p2026_12,p2027_01,pfuture
         type: range
possible_keys: uk_pose_event,idx_session_timestamp
          key: uk_pose_event
      key_len: 8
          ref: const
         rows: 750
     filtered: 100.00
        Extra: Using where
```
