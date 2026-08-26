# outbox·FastAPI·Spring·nginx·gRPC — 지금 열려 있는 것 정리

작성일: 2026-08-26
상태: **인덱스 — 새 분석 아님.** 각 항목의 실제 분석·트레이드오프는 링크된 원문서에 있다. 여기는 흩어진 열린 항목을 다섯 컴포넌트 기준으로 한 곳에 모은 것
대상: 2026-08-26 대화에서 outbox·FastAPI·Spring·nginx·gRPC 각각 "더 고려할 부분"을 물어 나온 항목 정리
연관: [`../decisions/architecture-review-2026-08-11.md`](../decisions/architecture-review-2026-08-11.md), [`../decisions/outbox-reliable-messaging.md`](../decisions/outbox-reliable-messaging.md), [`../decisions/reverse-proxy-and-tls.md`](../decisions/reverse-proxy-and-tls.md), [`../decisions/ai-sticky-routing.md`](../decisions/ai-sticky-routing.md), [`../decisions/ai-channel-pool-hardening.md`](../decisions/ai-channel-pool-hardening.md), [`./32-deferred-items.md`](./32-deferred-items.md), [`./31-production-readiness-plan.md`](./31-production-readiness-plan.md)

---

## 0. 이 문서가 하는 일

**할 일 목록이 아니라 «지금 열려 있는 것의 지도»다.** 다섯 컴포넌트를 따로 물었더니 항목 대부분이
결국 하나의 뿌리로 수렴했다 — **AI(FastAPI)가 세션 상태를 프로세스 메모리에 들고 있는데, 그 소유권 모델이
아직 정해지지 않았다**([`architecture-review-2026-08-11.md §1 최대 결함`](../decisions/architecture-review-2026-08-11.md)).
outbox 의 한계, Spring 의 단일 대상 전제, gRPC 의 채널 고아 문제, nginx 가 세션 고정 라우팅을 떠맡게 된
이유가 전부 이 하나의 증상이다.

각 항목은 이미 어딘가에 분석돼 있다 — **여기서 새로 판단하지 않는다.** 우선순위·착수 여부는 명시하지
않는다([[feedback_no_arbitrary_threshold_values]] — 근거 없는 순위를 매기지 않는다, [[feedback_user_decides_not_claude]] — 결정은 사용자 몫).

---

## 1. outbox

| 항목 | 상태 | 근거 |
|---|---|---|
| 지연 p99 분포 | 미측정 | 단건 측정만 했고 분포는 안 냄 |
| 중복 흡수 실측 | 미측정 | 코드상 멱등 수신이 보장하나 의도적 2회 송신 → 1회만 반영되는지 직접 재보진 않음 |
| 다건 동시 적체·다중 발행기 거동 | 미측정 | 지금까지 전부 단일 세션 기준 |
| T3 — 스케줄러 3개(`SessionTimeoutScheduler`·`PoseDataPartitionScheduler`·`JwtBlacklist`) 다중 인스턴스 중복 tick | 별도 카드로 분리, 미착수 | ShedLock 의존성 추가가 필요해 outbox 의 SKIP LOCKED 와 성격이 다름 |

세부: [`outbox-reliable-messaging.md §6-5`](../decisions/outbox-reliable-messaging.md), §4-3.

---

## 2. FastAPI (ai-server)

| 항목 | 상태 | 근거 |
|---|---|---|
| 세션 상태가 프로세스 메모리 소유 (재시작=결과유실·무중단배포불가·스케일아웃불가·검출기가 세션 아닌 스레드에 붙음) | **최대 결함, 미해결** | `session_state.py`(무-TTL 무-영속). 네 증상이 한 뿌리 |
| base64 프레임 HTTP POST 비용 | 미측정 | `exercise.tsx:160` — 스트리밍이 자연스러운 자리지만 잰 적 없음 |
| CORS `allow_credentials`+와일드카드 오리진 조합 | ✅ 2026-08-26 즉시 수정 완료 | 재점검 불필요 |
| `/docs`·`/redoc`·`/openapi.json` 환경 구분 없이 노출 | ✅ 2026-08-26 즉시 수정 완료 (`DEBUG` 플래그 연결) | 재점검 불필요 |
| `POST /pose` `image` 필드 크기 무제한 | ✅ 2026-08-26 즉시 수정 완료 (`max_length=20_000_000`, 안전판) | 재점검 불필요 |

세부: [`architecture-review-2026-08-11.md §1`](../decisions/architecture-review-2026-08-11.md) 결함 ①·⑦·⑩·⑪·⑫.

---

## 3. Spring

| 항목 | 상태 | 근거 |
|---|---|---|
| outbox 가 단일 gRPC 대상을 전제 → AI 를 여러 인스턴스로 늘리면 통보 대상을 못 정함 | 미확정 | [`ai-sticky-routing.md §8`](../decisions/ai-sticky-routing.md)(㉮·㉯·㉰ 전부 미결) |
| `application-prod.yml` 없음 — dev/prod 가 사실상 같은 설정 | 🔶 **이 표가 낡았었다** — 파일은 2026-08-22 에 이미 생겼다(`forward-headers-strategy` 포함, [`reverse-proxy-and-tls.md §8-2`](../decisions/reverse-proxy-and-tls.md)). 다만 근본(dev 전용 프로파일 분리)은 그 문서도 **여전히 미착수**라고 적어둠 — compose·CD·검증 절차를 같이 건드리는 별개 작업 | `backend/src/main/resources/application-prod.yml` |
| P3 입장 제한(admission control) — AI 포화 시 세션 시작 거절 장치 없음 | **막던 것(임의 임계값 근거 부재)은 풀림.** 안전계수 설계는 남음 | [`32-deferred-items.md P3`](./32-deferred-items.md) — 물리 코어당 16.4세션(상한)은 나왔으나 HTTP·디코딩·GIL 손실 감안한 안전계수, 거절 방식(즉시거절/대기열/저품질모드)은 미설계 |
| 6565(Spring gRPC) 포트가 왜 호스트에 노출돼 있는지 근거 미확인 | ✅ 2026-08-26 확인·수정 완료 | `loadtest/ghz/*` 부하테스트 rig가 호스트에서 직접 때리는 용도(dev 전용). `docker-compose.prod.yml` 은 근거가 없어 매핑 제거, dev 는 유지 — [`reverse-proxy-and-tls.md §1-1`](../decisions/reverse-proxy-and-tls.md) |

---

## 4. nginx

| 항목 | 상태 | 근거 |
|---|---|---|
| 공개 트래픽 앞단 리버스 프록시·TLS 종료 | **미결정** — 5개 옵션(nginx+certbot / Caddy / ALB / Cloudflare Tunnel / 앱 자체 TLS) 비교만 돼 있음 | [`reverse-proxy-and-tls.md §2`](../decisions/reverse-proxy-and-tls.md) |
| 프론트 AI 직결 경로(8000)가 릴리스 빌드에서 HTTPS 분기 자체가 없음 | 🔴 **TLS 선택보다 먼저 걸리는 문제** | `aiService.ts:17` — http 고정, `app.json` cleartext 예외 없음. iOS ATS·Android(API 28+) 가 평문 HTTP 를 막음 |
| 배포 호스트 0대 — `docker-compose.prod.yml` 실호스트 검증 0회 | 미착수 | 상시 EC2 인스턴스 없음. HTTPS·CORS·rate limit 체크리스트가 전부 TODO |
| 현재 존재하는 nginx 는 `nginx-ai`(AI 워커 세션 고정 라우팅) 뿐 | 이미 동작 중, 별개 축 | `nginx-ai/default.conf` — `X-AI-Worker` 헤더 기반 정적 map. 이건 리버스 프록시/TLS 논의와 무관 |

---

## 5. gRPC

| 항목 | 상태 | 근거 |
|---|---|---|
| 채널 풀에서 채널이 가리키는 AI 프로세스가 죽으면 그 채널을 쓰던 세션이 갇힘 | **미결정** | [`ai-channel-pool-hardening.md`](../decisions/ai-channel-pool-hardening.md)(작성 2026-08-26, `ReattachAnalysis` 재사용 패턴으로 분석 중) |
| 여러 **박스**로 확장 시 스티키 라우팅·세션-인스턴스 매핑·장애 재배치 셋이 묶여 개별 결정 불가 | **미결정** | [`ai-sticky-routing.md §8`](../decisions/ai-sticky-routing.md) — ㉮ 엔드포인트 전달 방식, ㉯ 매핑 저장 위치, ㉰ 장애 재배치, 셋 다 체크박스만 있고 확정 없음 |
| 현재 필요성 자체가 미확정 | 참고 | DAU 1,000·피크 67세션 가정에서는 "1박스 세로확장(1.30배)으로 지금 구조가 버틴다"는 계산이 나와 가로확장 착수 사유가 아직 없음 — [`architecture-review-2026-08-11.md §5`](../decisions/architecture-review-2026-08-11.md) |

---

## 6. 의존 관계 — 순서가 있는 것만

```
FastAPI 세션 상태 소유권 모델 (미정)
  ├─▶ outbox 가 "단일 대상"을 벗어나려면 이게 먼저 정해져야 함 (Spring §3-1행)
  ├─▶ gRPC 채널 고아 문제의 근본 해법도 여기 묶임 (§5-1행)
  └─▶ ai-sticky-routing §8 전체가 이 모델의 하위 결정임

nginx TLS 선택
  ├─▶ 배포 호스트 확보가 선행 조건 (§4-3행)
  └─▶ 프론트 AI 직결 HTTPS 분기 부재가 TLS 선택보다 먼저 걸림 (§4-2행)

Spring P3 입장 제한
  └─▶ 안전계수만 남음. P1(재측정) 은 이미 끝났고 임의값 근거 문제는 풀림 (§3-2행)
```

---

## 결정 로그

- 2026-08-26: 작성. 2026-08-26 대화(outbox·FastAPI·Spring·nginx·gRPC 개별 질의)에서 나온 열린 항목을
  다섯 컴포넌트 기준으로 재편해 인덱스화. 각 항목의 실제 분석·트레이드오프는 새로 만들지 않고 원문서를
  그대로 링크. 새 결정 없음 — 착수 순서·채택은 미정으로 남긴다.
