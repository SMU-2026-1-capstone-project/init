# #205 카드 A 후속 — change buffer 가 켜지는가 (로컬, 2026-08-22)

양 팔 모두 **커버링 인덱스 있음**. 팔은 **삽입 지점 모양** 하나다 — H=한 세션 집중, S=대역 8000 에 분산.
팔당 8000행 · 순서 `H S S H S H H S`(첫 판 버림).
🔴 팔 사이 **비용 비교는 하지 않는다**(페이로드가 다르다). 묻는 것은 «경로가 켜지는가» 하나다.

## 조건

```
pool_mb	cb
2048.00000000	all
cover_mb
81.5
```

| 팔 | 판 | ms | 행 | 🔑 ibuf_merges | 🔑 ibuf_ins | ibuf_size | bp_reads | bp_write_req | err |
|---|---|---|---|---|---|---|---|---|---|
| H | 0 | 47342 | 8000 | 0 | 0 | 1 | 2 | 42495 | 0 | ← 버림
| S | 1 | 29539 | 8000 | 0 | 0 | 1 | 134 | 43178 | 0 |
| S | 2 | 21006 | 8000 | 0 | 0 | 1 | 98 | 43056 | 0 |
| H | 3 | 15214 | 8000 | 0 | 0 | 1 | 0 | 42326 | 0 |
| S | 4 | 21054 | 8000 | 0 | 0 | 1 | 60 | 42995 | 0 |
| H | 5 | 15864 | 8000 | 0 | 0 | 1 | 56 | 42486 | 0 |
| H | 6 | 22567 | 8000 | 0 | 0 | 1 | 0 | 42382 | 0 |
| S | 7 | 18675 | 8000 | 0 | 0 | 1 | 0 | 42989 | 0 |

**팔별 합(첫 판 제외)** — merge 는 «몰아서» 나므로 판별은 중앙값이 아니라 **합**으로 본다

| 팔 | ibuf_merges 합 | ibuf_ins 합 | bp_reads 합 | bp_write_req 중앙값 |
|---|---|---|---|---|
| H | 0 | 0 | 56 | 42382 |
| S | 0 | 0 | 292 | 43026 |

## 교차 확인 — SHOW ENGINE INNODB STATUS (누적, 서버 기동 이후)

```
INSERT BUFFER AND ADAPTIVE HASH INDEX
-------------------------------------
Ibuf: size 1, free list len 3, seg size 5, 0 merges
merged operations:
 insert 0, delete mark 0, delete 0
discarded operations:
 insert 0, delete mark 0, delete 0
```
