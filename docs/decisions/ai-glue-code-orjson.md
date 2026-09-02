# 글루 코드 경량화 — orjson 스왑이 프로세스 1개 처리량을 올리나 (2026-09-02)

작성일: 2026-09-02
상태: 🟢 **완료 — 오른다.** rps +7.86%(226.2 → 244.0), p50 −6.88%(695.9ms → 648.1ms).
16판(양쪽 8판씩, 독립된 2라운드) 전부에서 방향 일관·산포 안 안 겹침.

브랜치: `worktree-ai-glue-code-orjson`(아직 PR 안 올림) · 커밋 [`81d0bbe8`](https://github.com/Shadowfit/init/commit/81d0bbe88405db5a7f5161c045f877a11bae2f6e)(orjson 스왑) ·
[`5638a915`](https://github.com/Shadowfit/init/commit/5638a91549c81b730fd225b1e7eacd7fa60df299)(run_arms.py 회귀 수정)
연관: [`per-process-ceiling-cause.md`](./per-process-ceiling-cause.md) §7 「① GIL — 같은 처방(프로세스) — 다만 **파이썬 구간을 줄이는 길**이 별도로 열린다」가 이 판의 발단이다.

---

## 0. 한 줄

프로세스 1개가 9.5~9.8 vCPU에서 막히는 원인(GIL)은 프로세스 분리로만 우회했고, **파이썬 구간
자체를 줄이는 길은 안 가봤다** — 여기서 가봤다. `json.dumps/loads` + 기본 응답 직렬화를
`orjson`(Rust 코어)으로 바꿨더니 **같은 프로세스 1개가 8% 더 처리한다.**

---

## 1. 왜 — 손 안 대던 자리

`pose.py`의 요청 경로는 파싱 → 검증 → (MediaPipe 추론) → 직렬화 순이다. MediaPipe는 C++이라
GIL을 놓지만, 앞뒤의 순수 파이썬 글루 코드(JSON 파싱·Pydantic 검증·base64 디코드·응답 직렬화)는
GIL에 그대로 걸린다. `per-process-ceiling-cause.md`는 이 글루 코드를 "줄이는 길이 별도로
열린다"고만 적어두고 실행하지 않았다 — `pose.py`가 팀원 코드라 면적을 최소화하려던 판단이었다.

이번엔 실제로 줄여서, 프로세스 1개의 처리량 천장(9.5~9.8 vCPU) 자체가 움직이는지 쟀다.

---

## 2. 뭘 바꿨나 — 면적 2파일

- `ai-server/requirements.txt`: `orjson==3.10.18` 추가
- `ai-server/app/api/endpoints/pose.py`:
  - `router`에 `default_response_class=ORJSONResponse` — 기본 응답 직렬화 경로
  - `_landmarks_to_json`: `json.dumps` → `orjson.dumps(...).decode()`
  - BACK_BENT 판정부: `json.loads`/`json.JSONDecodeError` → `orjson.loads`/`orjson.JSONDecodeError`
  - 측정용 raw 응답 분기: `JSONResponse` → `ORJSONResponse`

**판정 로직은 한 줄도 안 건드렸다.** 160 pytest 전부 통과(52 subtests 포함).

---

## 3. 무대

| | |
|---|---|
| 박스 | `c7i.4xlarge` 1대(16 vCPU) — 대상+부하기 동거, R6·proc-count-sweep과 같은 조건 |
| 부하 | 160세션 · 3.0fps · 판당 90초 · 풀 201 · 프로세스 **1개**(N=3 최적 배치는 이 판의 질문이 아니다) |
| 팔 | **json**(main, 커밋 `4f009007`) vs **orjson**(이 브랜치) — 같은 박스, 같은 날, 판만 바꿔 비교 |
| 배열 | 라운드 2개, 라운드마다 버림 1 + 4판. 시간 편향을 줄이려고 2라운드째는 **orjson을 먼저** 돌렸다 |
| 도구 | `loadtest/results/frame-path-overhead-2026-08-23/run_arms.py`(기존 rig 재사용) |

---

## 4. 결과

| | json (main) | orjson |
|---|--:|--:|
| n | 8 | 8 |
| 평균 rps | 226.20 | **243.99** |
| rps 범위 | 222.81 ~ 234.53 | 237.50 ~ 256.58 |
| rps 산포(cv) | 1.78% | 2.50% |
| 평균 p50 | 695.9 ms | **648.1 ms** |
| p50 산포(cv) | 1.31% | 2.03% |

```
rps    +7.86%   (산포 합 4.28% 의 약 1.8배 — 산포 안이 아니다)
p50    -6.88%   (산포 합 3.34% 의 약 2.1배)
Welch t   rps  t=6.88 · p50  t=-8.46   (df≈14, 우연으로 보기 어려운 크기)
```

🔑 **json 8판의 최댓값(234.53)이 orjson 8판의 최솟값(237.50)보다 작다** — 16판 전체에서
두 팔이 한 번도 안 겹친다. 라운드를 둘로 쪼갠 것도 우연 편향이 아님을 보여준다:

```
        json      orjson
1라운드   228.5     247.0
2라운드   223.9     241.0
```

---

## 5. 이 판이 확정한 것과 안 한 것

- ✅ **글루 코드 경량화가 처리량을 올린다** — 프로세스 1개 기준 +7.86%, 재현됨(2라운드)
- ✅ **판정 로직 무변경** — 면적 2파일, pytest 160판 통과
- 🔴 **N=3 배치에서도 같은 비율로 오르는지는 안 쟀다** — 이 판은 N=1만 봤다. 여러 프로세스가
  동시에 박스를 나눠 쓸 때(§9 "요청당 GIL이 N과 함께 늘어난다" 효과와 겹칠 수 있다)는 별도 판이 필요하다
- 🔴 **9.5~9.8 vCPU 천장 자체가 움직였는지는 이 판만으론 모른다** — CPU 사용률(`cpu.ai`)을
  이번 rig가 안 걷었다(주 관측값은 rps·p50만). rps가 올랐다는 것과 "같은 CPU로 더 많이
  처리했다"는 것은 다른 주장이다 — 후자를 말하려면 CPU 샘플러를 붙여 다시 재야 한다
- 🔴 **더 큰 글루 코드 경량화(Pydantic 검증 스킵, 배치 등)는 안 건드렸다** — orjson은 그중
  가장 싸고 안전한 한 수였다

---

## 6. 덤 — 측정 중 잡은 버그

`run_arms.py`가 `--remote-target` 없이(이 판처럼 동거 라운드로) 돌면 `AI` 전역변수가 끝까지
`None`으로 남아 `wait_health()`가 매판 예외를 삼키고 "기동 실패"만 찍는다 — 서버는 실제로
떠서 `/health`가 200을 주는데도 그렇다. R10-b(`--remote-target` 추가, #649)가 non-remote
분기의 `AI` 할당을 빠뜨린 회귀. 커밋 [`5638a915`](https://github.com/Shadowfit/init/commit/5638a91549c81b730fd225b1e7eacd7fa60df299)로 고쳐서 이 판에 반영했다 — main에는 아직 안 올라갔다.

---

## 7. 다음

- N=3(또는 N=3→4 재검토) 배치에서 orjson 효과 재확인
- CPU 샘플러를 붙여 "같은 vCPU로 더 처리하는가" vs "vCPU 자체를 더 쓰는가" 구분
- `run_arms.py`의 `AI` 회귀 수정을 main에 별도 PR로 올리기
