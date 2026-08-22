# #205 카드 A 후속 — change buffer 가 켜지는가 (로컬, 2026-08-22)

양 팔 모두 **커버링 인덱스 있음**. 팔은 **삽입 지점 모양** 하나다 — H=한 세션 집중, S=대역 8000 에 분산.
팔당 8000행 · 순서 `E E E H`(첫 판 버림).
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
| E | 0 | 16747 | 8000 | 0 | 0 | 1 | 144 | 42998 | 0 | ← 버림
| E | 1 | 16060 | 8000 | 0 | 0 | 1 | 134 | 43089 | 0 |
| E | 2 | 16251 | 8000 | 0 | 0 | 1 | 87 | 42478 | 0 |
| H | 3 | 14773 | 8000 | 0 | 0 | 1 | 0 | 42320 | 0 |

**팔별 합(첫 판 제외)** — merge 는 «몰아서» 나므로 판별은 중앙값이 아니라 **합**으로 본다

| 팔 | ibuf_merges 합 | ibuf_ins 합 | bp_reads 합 | bp_write_req 중앙값 | 유효 판 | 🔴 버린 판(err) |
|---|---|---|---|---|---|---|
| H | 0 | 0 | 0 | 42320 | 1 | 0 |
| S | 🔴 **못 쟀다 — 유효 판 0** | — | — | — | 0 | 0 |
| E | 0 | 0 | 221 | 42784 | 2 | 0 |

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
