# #205 카드 B — 두 평균 · 곡선 비용 · 생성 표 (로컬, 2026-08-20)

## 전제 — rep 당 프레임 수 모양

```
shape	sessions	min_frames_per_rep	max_frames_per_rep
uniform	2002	3	30
nonuniform	200	4	30
```

## 두 평균의 차이

```
shape	sessions	sessions_differing	max_abs_diff	avg_abs_diff
uniform	2002	0	0.000000	0.000000
nonuniform	200	200	0.082569	0.082569
```

## 가장 크게 갈린 세션

```
session_id	min_frames	max_frames	frame_weighted	rep_weighted	diff
68531	4	30	73.917431	74.000000	-0.082569
68534	4	30	73.917431	74.000000	-0.082569
68533	4	30	73.917431	74.000000	-0.082569
68532	4	30	73.917431	74.000000	-0.082569
68535	4	30	73.917431	74.000000	-0.082569
```

## rep 순서별 곡선 쿼리 비용 (첫 판 버림)

| 팔 | 판 | ms | 핸들러 | tmp |
|---|---|---|---|---|
| with_cover | 0 | 6376.3 | 3175688 | 2 | ← 버림
| with_cover | 1 | 6906.2 | 3175688 | 2 |
| with_cover | 2 | 7392.9 | 3175688 | 2 |
| with_cover | 3 | 6696.1 | 3175688 | 2 |
| no_cover | 0 | 84406.4 | 3175688 | 2 | ← 버림
| no_cover | 1 | 43248.2 | 3175688 | 2 |
| no_cover | 2 | 45992.1 | 3175688 | 2 |
| no_cover | 3 | 73653.7 | 3175688 | 2 |

**팔별 중앙값(첫 판 제외)**

| 팔 | ms 중앙값 | 핸들러 |
|---|---|---|
| with_cover | 6906.2 | 3175688 |
| no_cover | 45992.1 | 3175688 |

## 실행계획

```
*************************** 1. row ***************************
EXPLAIN: -> Sort: pose_data.rep_number
    -> Table scan on <temporary>
        -> Aggregate using temporary table
            -> Filter: (pose_data.rep_number > 0)  (cost=442485 rows=409409)
                -> Table scan on pose_data  (cost=442485 rows=1.23e+6)

```
