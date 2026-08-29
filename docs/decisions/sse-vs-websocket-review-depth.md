# SSE vs WebSocket — 기술 리뷰(면접) 소재로 뭐가 나은가

작성일: 2026-08-30
상태: **분석 완료 · 참고용(하나는 확정됨)** — 이 문서는 비교 판단이지 결정이 아니었다. 이후 [`multiuser-realtime-sync.md`](./multiuser-realtime-sync.md)이 사용자 confirm으로 ✅ 채택 확정되어(2026-08-30), 이 문서 §3의 "SSE 추천"은 실제 방향과 다르게 됐다. 아래 화제 풍부함·리스크 비교 자체는 참고 자료로 유효하다. ([[결정은 사용자가, Claude 는 추천만]])
배경: "SSE가 기술적으로 리뷰하기 좋은가, WebSocket이 리뷰하기 좋은가"라는 질문에 답한 것을 박제.
연관: [`trainer-live-monitoring.md`](./trainer-live-monitoring.md), [`multiuser-realtime-sync.md`](./multiuser-realtime-sync.md), [`sse-capacity-deep-dive.md`](./sse-capacity-deep-dive.md), [`portfolio-benchmark.md`](./portfolio-benchmark.md)(같은 "채울 키워드 vs 밀 차별점" 프레임), [[feedback_tps_over_dau_justification]]

---

## 0. "리뷰하기 좋다"는 말이 가리키는 두 가지가 다르다

- **화제 풍부함** — 면접관이 꺼낼 수 있는 후속 질문의 가짓수. 표면적으로 "이 기술을 썼다"만으로 생기는 화제.
- **방어 가능한 깊이** — 그 화제 중 실제로 파고들어 "왜"까지 답할 수 있는 것의 개수.

이 둘은 반비례 관계에 놓일 수 있다 — 화제가 많은데 규모가 커서 다 못 파면, 오히려 면접에서 **"설계상 그렇게 될 겁니다"**로 답하는 최악의 상황이 나온다. 이 프로젝트가 지금까지 경계해온 것이 정확히 이거다([[feedback_tps_over_dau_justification]] — "DAU 대비 안 아프다"로 닫지 말고 원인 규명으로 닫을 것).

---

## 1. 화제 풍부함 비교

| | SSE (`trainer-live-monitoring.md`) | WebSocket (`multiuser-realtime-sync.md`) |
|---|---|---|
| 관계 형태 | 1:1 | N:N |
| 방향 | 단방향 | 양방향 |
| 화제 | async dispatch·스레드 모델, 캐파시티 천장 원인, 백프레셔 | 위 셋 + Redis Pub/Sub 순서·유실, 인스턴스 간 연결-그룹 매핑, 장애 시 그룹 전체 끊김, 재연결 백필 |
| 화제 개수 | 3개([`sse-capacity-deep-dive.md`](./sse-capacity-deep-dive.md) §1) | 6개 이상 — SSE의 상위 집합 + 분산 조율 문제 |
| 겹치는 선례 | 없음(새 종류) | `ai-sticky-routing.md`("세션이 어느 인스턴스에 사는가")와 같은 부류, 단 N:N이라 더 복잡 |

**표면적으로는 WebSocket이 압도적으로 화제가 많다.** 시스템 설계 면접에서 실제로 자주 나오는 "분산 시스템" 질문군(순서 보장, 장애 복구, presence)과 겹치는 게 많아서다.

---

## 2. 왜 화제 풍부함이 그대로 "리뷰하기 좋음"이 아닌가

### 2-1. 규모 차이가 그대로 "완주 확률" 차이다

- SSE: ≈17~19h ([`trainer-live-monitoring.md`](./trainer-live-monitoring.md) §8)
- WebSocket: ≈27~35h ([`multiuser-realtime-sync.md`](./multiuser-realtime-sync.md) §7), **SSE의 약 1.5~2배**

화제가 2배인데 시간도 2배 필요하다 — **화제당 파고들 수 있는 밀도는 같거나 WebSocket이 더 낮다.** 게다가 WebSocket은 §6에서 이미 채택 비추천 판정을 받은 상태라, 실제로 이 시간을 다 확보할 가능성 자체가 SSE보다 낮다.

### 2-2. 채택 비추천 사유가 리뷰 리스크로도 그대로 이어진다

`multiuser-realtime-sync.md` §6의 비추천 사유(리스크 최대·포지셔닝 불일치·기회비용)는 "만들지 말아야 할 이유"이자 동시에 "만들어도 얕게 남을 이유"다. 특히 §4(포지셔�다)의 "DB 깊이 서사와 접점이 거의 없음"은, 설령 WebSocket을 깊게 파도 **이 프로젝트의 메인 서사(DB 포폴)와 연결되는 질문으로 못 돌린다**는 뜻이다 — 화제는 풍부해도 서사에서 고립된다.

### 2-3. 얕게 남았을 때의 실패 모드가 다르다

- SSE가 얕게 남으면: "async dispatch를 확인은 안 했다" 정도 — 화제 3개 중 1~2개 미답.
- WebSocket이 얕게 남으면: Redis pub/sub 순서·유실, 인스턴스 장애 복구 같은 **면접관이 시스템 설계에서 제일 먼저 찌르는 지점**이 뚫려 있는 채로 남는다 — 화제가 많은 만큼 뚫린 구멍도 눈에 띈다.

---

## 3. 결론 (추천, 미확정)

**SSE.** 화제 개수가 아니라 **"방어 가능한 깊이까지 실제로 도달할 확률"** 기준이다.

- SSE: 화제 3개, 규모 17~19h, 이미 §7에서 2순위 추천, DB 포폴 서사와 완전히 배치되진 않음([`trainer-live-monitoring.md`](./trainer-live-monitoring.md) §5) → 3개 다 깊이 도달할 확률 높음
- WebSocket: 화제 6개 이상, 규모 27~35h, 이미 채택 비추천, DB 포폴 서사와 접점 거의 없음 → 얕게 남을 확률 높고, 남으면 서사에서도 고립됨

**얕은 화제 5~6개보다 깊은 화제 3개가 면접에서 낫다** — projection·실재/잠재 분류로 이미 증명된 이 프로젝트의 패턴("문제를 가진 게 아니라 측정한 것", [`db-deep-dive.md`](../portfolio/db-deep-dive.md) §0.3)과 같은 논리다.

> 🔴 이 결론은 추천이며 결정이 아니다. SSE·WebSocket 둘 다 기능 채택 자체가 미확정이므로, 이 비교는 "만약 하나를 판다면"의 우선순위다.

---

## 4. 참고 — 만약 WebSocket을 굳이 판다면 최소 방어선

이 절은 §3 결론(SSE 추천)을 바꾸지 않는다. 다만 나중에 WebSocket 쪽으로 방향이 바뀔 경우를 대비해, 화제 6개 중 **면접에서 가장 먼저 찔릴 가능성이 높은 것** 우선순위만 남겨둔다:

1. Redis Pub/Sub 순서·유실 — 분산 시스템 질문의 표준 진입점
2. 인스턴스 장애 시 그룹 전체 끊김 — N:N이라 SSE(1:1)보다 파급 범위가 큼
3. 재연결 백필 — "그동안 놓친 이벤트" 처리, 없으면 바로 드러남

나머지(연결-그룹 매핑, 메시지 중복 처리)는 위 셋을 파는 과정에서 부산물로 드러날 가능성이 높다.
