# 스티키 라우팅 축소 측정 — 재실행 (2026-08-29, 버그 수정)

설계: [`../../../../docs/decisions/ai-sticky-routing-probe.md`](../../../../docs/decisions/ai-sticky-routing-probe.md) §0-2
원판(버그 있음): [`../README.md`](../README.md)
박스: `c7i.4xlarge`(16 vCPU) 1대 `i-0c3b140e4ac11c3d4` · 커밋
[`3ebc9d9`](https://github.com/Shadowfit/init/commit/3ebc9d96bb934764de4016ae42bda778d8823cc3)
배열: 원판과 동일 — 버림(C) · C E C C E C E E C = 9판(유효 8판)

---

## 왜 다시 돌렸나

원판(2026-08-26)의 C팔이 `sessions_total // N`(정수 나눗셈)을 그대로 옮겨 쓰면서
160세션 중 1개를 버렸다(3개 rig × 53 = 159). E팔은 `session_id % N`이라 160을 다 썼다.
정규화해도 잔차 0.10%가 산포선(0.042%)을 살짝 넘어 "완전히 닫혔다"로 못 박지 못했다.

## 무엇을 고쳤나

`run_arm_C()`에서 나머지(`sessions_total % N`)를 앞쪽 rig부터 하나씩 배분하도록 고쳤다 —
`s_each = 53`, `remainder = 1`이면 rig0이 54, rig1·rig2가 53을 받아 합계 160.
(커밋 `3ebc9d9`, `E:\init\loadtest\results\sticky-routing-probe-2026-08-26\run_sticky_probe.py`)

## 결과 — 정규화 없이 닫힘

| 팔 | 유효 판 rps | 평균 | 판 간 산포(CV) | 세션 수(per_rig) |
|---|---|--:|--:|--:|
| C | 371.34 · 377.00 · 377.77 · 375.15 | **375.31** | 0.76% | [54, 53, 53] = **160** |
| E | 364.00 · 375.14 · 375.39 · 377.28 | **372.95** | 1.62% | (해시, 항상 160) |

```
차:        (E − C) / C = −0.63%
산포 합:   0.76% + 1.62% = 2.39%
```

**−0.63% ≪ 2.39% — 이번엔 원값 그대로도 판정선 ㄴ을 통과한다.** 세션 수 정규화가
더 이상 필요 없다(둘 다 160).

판정선 나머지 셋도 재확인:

| # | 물음 | 결과 |
|---|---|:--:|
| ㄱ 정확성 | `nolease` 0인가 | 🟢 8판 전부 0 |
| ㄷ 배정 균형 | E팔 `assigned_dist` | 🟢 4판 전부 `{"0":53,"1":54,"2":53}` |
| ㄹ 교란 배제 | E팔 `rig` CPU | 🟢 0.36~0.37 vCPU (1 vCPU 근처 아님, C팔 rig 합 0.36과 같은 자릿수) |

## 결론

**"이미 갈라진 부하" 캐비엇이 완전히 닫혔다.** 정적 사전분할(N 스윕 방식)이든 클라이언트
해시 라우팅(`session_id % N`)이든 처리량 차이가 없다 — N 스윕의 절대 처리량(451.2 rps)과
`ai-sticky-routing.md` §5-1의 추천(㉮=ㄱ, Spring이 세션 생성 시 AI 주소를 실어준다)에 이
결과를 그대로 얹을 수 있다.

📌 `ai-sticky-routing.md` §8의 결정 셋(엔드포인트 계약·매핑 위치·장애 재배치)은 이 결과와
무관하게 그대로 미결이다.

## 인프라

`c7i.4xlarge` 1대, 무인 실행(부트스트랩 ROLE=ai-venv → 라운드 → S3 업로드 → 자동
terminate). 소요 약 17분(부트스트랩 포함), 종료 후 인스턴스·볼륨 확인됨(`terminated`).

## 파일

| 파일 | 내용 |
|---|---|
| `sticky_rerun.json` | 오케스트레이터 전체 결과(9판, CPU·outcomes·assigned_dist·per_rig sessions 포함) |
| `raw_C_r*_rig{0,1,2}.tsv` | C팔 각 판·각 rig의 요청별 원시 로그 |
| `raw_E_r*.tsv` | E팔 각 판의 요청별 원시 로그(단일 rig) |
| `server_rerun_ai{0,1,2}.log` | AI 프로세스 3개의 stdout/stderr |
| `user_data.log` | EC2 부트스트랩·전제 확인·업로드·워치독 로그 |
| `ai_venv_conditions.txt` | 박스 보정값 |
