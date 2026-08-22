# ARM C — #204 EXPLAIN 스윕

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
idx_report_cover	1	session_id
idx_report_cover	2	rep_number
idx_report_cover	3	timestamp_sec
idx_report_cover	4	sync_rate
idx_report_cover	5	smoothed_knee_angle
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
possible_keys: uk_pose_event,idx_session_timestamp,idx_report_cover
          key: idx_report_cover
      key_len: 8
         rows: 750
     filtered: 100.00
        Extra: Using index
```
**EXPLAIN FORMAT=TREE**
```
EXPLAIN: -> Covering index lookup on pose_data using idx_report_cover (session_id=2813)  (cost=76.1 rows=750)

```
**핸들러 카운터** (FLUSH STATUS 직후 1회 실행)
```
Handler_read_key	14
Handler_read_next	750
```
**EXPLAIN ANALYZE 실제시간** (세션 5 개 · 첫 판은 워밍업으로 버린다)
```
  s=2813  actual time=0.0983..1.21 rows=750 loops=1   ← 워밍업(버림)
  s=2716  actual time=0.345..1.67 rows=750 loops=1
  s=2619  actual time=0.248..1.54 rows=750 loops=1
  s=2522  actual time=0.136..12.5 rows=750 loops=1
  s=2425  actual time=0.121..1.22 rows=750 loops=1
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
possible_keys: uk_pose_event,idx_session_timestamp,idx_report_cover
          key: idx_report_cover
      key_len: 8
         rows: 750
     filtered: 11.11
        Extra: Using where; Using index
```
**EXPLAIN FORMAT=TREE**
```
EXPLAIN: -> Filter: ((pose_data.created_at >= TIMESTAMP'2026-01-03 01:45:00') and (pose_data.created_at < <cache>(('2026-01-03 01:45:00' + interval 1 day))))  (cost=9.45 rows=83.3)
    -> Covering index lookup on pose_data using idx_report_cover (session_id=2813)  (cost=9.45 rows=750)

```
**핸들러 카운터** (FLUSH STATUS 직후 1회 실행)
```
Handler_read_key	1
Handler_read_next	750
```
**EXPLAIN ANALYZE 실제시간** (세션 5 개 · 첫 판은 워밍업으로 버린다)
```
  s=2813  actual time=0.0396..2.67 rows=750 loops=1   ← 워밍업(버림)
  s=2716  actual time=0.0261..0.0261 rows=0 loops=1
  s=2619  actual time=0.0417..0.0417 rows=0 loops=1
  s=2522  actual time=0.03..0.03 rows=0 loops=1
  s=2425  actual time=0.0193..0.0193 rows=0 loops=1
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
  s=2716  
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
possible_keys: uk_pose_event,idx_session_timestamp,idx_report_cover
          key: idx_report_cover
      key_len: 12
         rows: 750
     filtered: 100.00
        Extra: Using where; Using index
```
**EXPLAIN FORMAT=TREE**
```
EXPLAIN: -> Group aggregate: avg(pose_data.sync_rate)  (cost=226 rows=750)
    -> Filter: ((pose_data.session_id = 2813) and (pose_data.rep_number > 0))  (cost=151 rows=750)
        -> Covering index range scan on pose_data using idx_report_cover over (session_id = 2813 AND 0 < rep_number)  (cost=151 rows=750)

```
**핸들러 카운터** (FLUSH STATUS 직후 1회 실행)
```
Handler_read_key	14
Handler_read_next	750
```
**EXPLAIN ANALYZE 실제시간** (세션 5 개 · 첫 판은 워밍업으로 버린다)
```
  s=2813  actual time=0.24..1.61 rows=25 loops=1   ← 워밍업(버림)
  s=2716  actual time=0.119..2.29 rows=25 loops=1
  s=2619  actual time=0.121..1.72 rows=25 loops=1
  s=2522  actual time=0.093..0.764 rows=25 loops=1
  s=2425  actual time=0.0949..0.809 rows=25 loops=1
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
possible_keys: uk_pose_event,idx_session_timestamp,idx_report_cover
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
  s=2813  actual time=0.0234..0.0235 rows=1 loops=1   ← 워밍업(버림)
  s=2716  actual time=0.0229..0.023 rows=1 loops=1
  s=2619  actual time=0.0224..0.0225 rows=1 loops=1
  s=2522  actual time=0.0232..0.0233 rows=1 loops=1
  s=2425  actual time=0.0226..0.0227 rows=1 loops=1
```

### R6 🔴 deleteBySessionIdIn — EXPLAIN 만 (실행 안 함)
```
*************************** 1. row ***************************
           id: 1
  select_type: DELETE
        table: pose_data
   partitions: p2026_01,p2026_02,p2026_03,p2026_04,p2026_05,p2026_06,p2026_07,p2026_08,p2026_09,p2026_10,p2026_11,p2026_12,p2027_01,pfuture
         type: range
possible_keys: uk_pose_event,idx_session_timestamp,idx_report_cover
          key: uk_pose_event
      key_len: 8
          ref: const
         rows: 750
     filtered: 100.00
        Extra: Using where
```
