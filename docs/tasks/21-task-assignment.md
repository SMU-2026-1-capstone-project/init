# 작업 분배표

마지막 업데이트: **2026-08-07** (표 본문 코드 대조 갱신 — §0 정정 블록·§3 참조). 최초 작성 2026-05-23
근거: ~~[`20-feature-roadmap.md`](./20-feature-roadmap.md) 의 스택별 남은 작업을 사람 분배 가능한 단위로 쪼갠 결과.~~
　　　→ ⚠️ **2026-08-08**: 원 근거 문서(`20-feature-roadmap.md`)는 2026-05-23 에서 멈춘 채 **프론트 칸이 전부 뒤집혔다.** 이 문서는 2026-08-07 에 코드로 직접 대조해 갱신했으므로 **원 근거보다 이 문서가 더 정확하다** — 상속 관계가 역전됐다.
　　　백엔드 잔여의 정본은 [`28-remaining-work-plan.md`](./28-remaining-work-plan.md), AI 잔여는 [`30-ai-remaining-work.md`](./30-ai-remaining-work.md).

> 📌 **헤더 날짜가 2.5개월 뒤처져 있었다** (2026-08-08 정정). 본문은 2026-08-07 에 갱신됐는데 헤더는 `2026-05-23` 이라, **문서를 열자마자 낡았다고 판단해 안 읽고 지나칠 수 있는 상태**였다. 같은 종류의 드리프트가 이 저장소에서 반복된다 — 갱신할 때 헤더를 같이 만지는 습관이 필요하다.

읽는 법:
- **상태**: 📋 대기 / 🚧 진행 중 / ✅ 완료 / 🟦 보류 / 🟥 차단
- **우선**: 🔴 즉시 (시연 핵심) / 🟡 단기 (시연 풍성) / 🟢 장기 / ⚪ 보류
- **추정**: 코드 변경 분량 기준 *대략* (실제 디버깅 시간은 별도)
- **의존성**: 이 작업이 시작되려면 먼저 완료되어야 하는 다른 작업

---

## 1. Frontend (React Native)

> 🔴 **2026-08-07 정정.** 이 표는 2026-05-23 기준이라 **전 항목이 📋(대기)로 남아 있었는데, 실제로는 절반 이상이 구현돼 있다.** 코드로 대조해 상태를 갱신한다. 근거는 각 행에 파일·심볼로 적었다.

| ID | 작업 | 우선 | 의존성 | 추정 | 담당 | 상태 |
|----|------|-----|--------|------|------|------|
| ~~FE-01~~ | ~~`exerciseService` 신설 (startSession / stopSession)~~ | — | — | — | | ✅ **완료** — `services/` 에 `exercisesService`·`aiService`·`reportService`·`memberService`·`preferenceService`·`authService` 7종 |
| ~~FE-02~~ | ~~녹화 버튼 핸들러 — startSession / 종료 호출~~ | — | — | — | | ✅ **완료** — `exercise.tsx:89` `startSession`, `exercisesService` 의 세션 종료(`/end`) |
| **FE-03** | `exercise.tsx` 의 DEV 패널(수동 syncRate) 제거 | 🟡 | — | 30m | | 📋 **남음** — `exercise.tsx:355` 에 `__DEV__ && isRecording` 조건으로 아직 있다. `__DEV__` 가드가 있어 배포본엔 안 나오므로 우선순위는 🔴→🟡 로 낮춘다 |
| ~~FE-04~~ | ~~카메라 프레임 캡처·인코딩~~ | — | — | — | | ✅ **완료** — `takePictureAsync`(`exercise.tsx:80`), ~3fps(330ms) |
| ~~FE-05~~ | ~~프레임 송신 → AI `POST /pose` 직결~~ | — | — | — | | ✅ **완료** — `aiService`, `EXPO_PUBLIC_INTERNAL_API_TOKEN` 헤더 |
| **FE-06** | 운동 결과 화면 — 종료 후 rep 수·sync_rate·feedback 표시 | 🔴 | — | 4h | | 🔶 **확인 필요** — `report/[id].tsx` 가 있으나 세션 종료 직후 결과 화면과 같은 것인지 미확인 |
| **FE-07** | TTS 재생 — `expo-speech` + `/preferences/tts` + 피드백 템플릿 매핑 | 🟡 | **AI-04** | 3h | | 📋 **남음** — `expo-speech` 사용처가 없다. ⚠️ **AI 가 결함을 안 보내므로**([`30-ai-remaining-work.md`](./30-ai-remaining-work.md) §1) 지금 만들어도 울릴 내용이 없다 |
| **FE-08** | 관절 점 오버레이 시각화 | 🟡 | — | 4h | | 📋 |
| ~~FE-09~~ | ~~캘린더 화면 데이터 연동~~ | — | — | — | | ✅ **완료** — `(tabs)/index.tsx` 가 `calendar` 호출 |
| ~~FE-10~~ | ~~주간 통계 화면 연동~~ | — | — | — | | ✅ **완료** — `(tabs)/activity.tsx` 가 `weekly-summary` 호출 |
| **FE-11** | 리포트 상세 — worst 구간·이전 기록 비교·자세 분석 차트 | 🟡 | BE-02 | 5~6h | | 🔶 **부분** — `report/[id].tsx` 는 있으나 worst 구간·비교·차트 여부 미확인 |
| **FE-12** | 운동 타이머 UI | 🟡 | — | 1h | | 📋 |
| **FE-14** | **세션 재부착 연동** — 앱 복귀 시 `GET /sessions/active` → `POST /sessions/{id}/reattach` | 🔴 | — | **추정 없음** | | 📋 **목록에 없던 것.** 백엔드([#73](https://github.com/Shadowfit/init/pull/73)·[#74](https://github.com/Shadowfit/init/pull/74))·AI 는 끝났는데 **프론트가 안 불러서 사용자 체감 효과가 0 이다** |
| **FE-15** | **휴식 중 프레임 전송 중단** ([#92](https://github.com/Shadowfit/init/issues/92)) | 🟡 | **AI-03**(세트 경계) | **추정 없음** | | 📋 **목록에 없던 것.** 휴식 개념이 프론트에 아예 없다. 세션당 MediaPipe 추론 800~1,350회 낭비 |
| **FE-13** | 관리자 화면 — 대시보드·회원 목록·세션 목록 | 🟢 | ~~BE-03~05~~ **완료** | 🔴 **추정 없음** | | 📋 **범위가 바뀌었다** — 아래 |

소계: 🔴 3개, 🟡 5개, 🟢 1개 / ✅ 완료 6개

### ⚠️ FE-13 은 이제 이 표의 8h+ 로 못 잡는다

관리자 화면을 **만든다**가 확정됐고([`../decisions/admin-page-scope.md`](../decisions/admin-page-scope.md) §8-6), **별도 웹으로 페이지 번호 방식**까지 정해졌다. 그런데:

- 이 행의 8h+ 는 **화면 3종(대시보드·카테고리·영상)** 기준인데, 확정된 범위는 **A 회원 목록 · B 세션 목록 · D 대시보드**다 — **겹치지 않는다**
- **별도 웹**이면 React Native 앱이 아니라 **프로젝트 세팅이 통째로 추가**된다

→ **현재 추정 없음.** [`28-remaining-work-plan.md`](./28-remaining-work-plan.md) §3 도 같은 단서를 달아뒀다. 백엔드는 A·B·D 가 다 끝나 **프론트만 기다리는 상태**다.

---

## 2. Backend (Spring Boot)

> **각 작업의 구체 풀이(현재 코드 상태·만질 파일·완료 기준·리스크)**: [`22-backend-tasks-detail.md`](./22-backend-tasks-detail.md)

| ID | 작업 | 우선 | 의존성 | 추정 | 담당 | 상태 |
|----|------|-----|--------|------|------|------|
| ~~BE-01~~ | ~~(H1 채택 시) `POST /exercises/sessions/{id}/frame` 프록시 endpoint~~ | ⚪ | — | — | | 🗑️ 폐기 (H2 채택, 2026-05-24) |
| BE-02 | worst 구간 선정 서비스 로직 보강 — `WorstSectionDto`·`SessionReportResponseDto` 채우는 메서드 | 🟡 | — | 3h | | 📋 |
| BE-03 | GPT/Claude 리포트 자동 생성 — `GptFeedbackService` 신설 (이미 env 에 `OPENAI_API_KEY`) | 🟡 | — | 6h | | 📋 |
| BE-04 | 카테고리 관리 CRUD API — `AdminCategoryController` | 🟢 | — | 3h | | 📋 |
| BE-05 | 관리자 대시보드 통계 API — 사용자/세션 집계 | 🟢 | — | 4h | | 📋 |
| BE-06 | 운동 목표 엔티티·CRUD API — `Goal` 도메인 신설 | 🟢 | — | 5h | | 📋 |
| BE-07 | 사용자 운동 패턴 분석 API — 주기성·강도 추세 | 🟢 | 데이터 축적 후 | 8h+ | | 📋 |
| BE-08 | 개인화 루틴 추천 API — 알고리즘 설계 필요 | 🟢 | BE-07 | 10h+ | | 📋 |
| BE-09 | 운동 세트 개념 도입 — DB 컬럼·DTO·gRPC 메시지 추가 ([[project_squat_first]] 와 협의) | ⚪ | 새 운동 추가 시점 | 5h | | 🟦 |
| **BE-10** | AI gRPC 헬스체크 + Resilience4j Circuit Breaker (H2 채택 부속) | ✅ **완료(2026-07-11)** | H2 확정 | 4h | | ✅ |
| **BE-11** | 콜백 PoseData 검증 게이트 (H2 채택 부속) | 🔴 | H2 확정 | 3h | | 📋 |
| **BE-12** | 콜백 처리 Outbox 패턴 (H2 채택 부속, 운영 신뢰성) | 🟡 | BE-11 | 5h | | 📋 |
| **BE-30** | TTS 피드백 효과 분석 (포폴 어필용, BE-07 와 묶기) | 🟢 | BE-07, 데이터 4주 축적 | 4h | | 📋 |

소계: 🔴 2개, 🟡 3개, 🟢 6개, ⚪ 2개 (BE-01 폐기 + BE-09 보류)

---

## 3. AI Server (FastAPI) — ~~거의 없음~~ **적지 않다** (2026-08-07 정정)

> 🔴 **이 절의 제목과 전제가 틀렸다.** *"거의 없음 / 현재 시연용 동작에는 추가 작업 없음"* 은
> 2026-05-23 시점 판단인데, 그 뒤로 두 가지가 달라졌다:
>
> 1. **2학기 계획이 종목 확장을 Week 3~4 로 못박았다**([`24-semester2-plan.md`](./24-semester2-plan.md)) — AI-02 는 "새 운동 추가 시점"이라는 막연한 의존이 아니라 **일정에 걸린 항목**이고, 백엔드를 막는 유일한 AI 작업이다
> 2. **아래 3건이 AI 잔여의 전부가 아니다** — 목록에 없던 큰 항목이 더 있다(아래 표 두 번째 블록)
>
> 전수 조사와 근거는 [`30-ai-remaining-work.md`](./30-ai-remaining-work.md) (2026-08-07 신설).
>
> ⚠️ [[feedback_minimize_python_changes]] 정책은 **완화됐다** — ai-server 변경은 이제 가능하고, 변경 사실을 알리고 면적을 최소화하면 된다. *"정책으로 손대지 않음"* 은 더 이상 이 표를 보류로 둘 근거가 못 된다.

| ID | 작업 | 우선 | 의존성 | 추정 | 담당 | 상태 |
|----|------|-----|--------|------|------|------|
| AI-01 | `ExtractReferenceData` 실제 구현 — YouTube 다운로드 + MediaPipe 추출 | 🟡 | AI-02 의 선결 | 6h | (원작자) | 📋 |
| AI-02 | 런지·플랭크 분석기 추가 | 🟡 | AI-01 · **2학기 Week 3~4** | 운동당 4h+ | (원작자) | 📋 |
| AI-03 | 운동 세트 자동 구분 분석 | 🟡 | BE-09 와 협의 · AI-04 의 선결 | 4h | (원작자) | 📋 |

**목록에 없던 것** — [`30-ai-remaining-work.md`](./30-ai-remaining-work.md) 에서 나왔다:

| ID | 작업 | 우선 | 의존성 | 추정 | 상태 |
|----|------|-----|--------|------|------|
| **AI-04** | **결함 분류 + `ReportFeedbackBatch` 송신** — proto·Spring 수신부·시드가 다 있는데 AI 가 그 RPC 를 한 번도 안 부른다. TTS 피드백 기능 전체가 시연용 더미로만 존재한다 | 🔴 | AI-03(세트 경계) | **추정 없음** | 📋 |
| **AI-05** | **부하 측정 1회** — [`../decisions/ai-load-budget.md`](../decisions/ai-load-budget.md) §5 에 계획이 이미 있고 실행만 남았다. "MediaPipe 가 AI CPU 95%+" 명제의 유일한 근거가 될 측정 | 🟡 | — | **추정 없음** | 📋 |
| **AI-06** | 선택형 스타일 기준(reference) — [`../decisions/reference-style-and-caching.md`](../decisions/reference-style-and-caching.md) | 🟢 | AI-01 | **추정 없음** | 📋 |

소계: 🔴 1개, 🟡 4개, 🟢 1개 — **추정된 것만 18h+**(AI-01 6h + AI-02 8h+ + AI-03 4h), 추정 없는 것 3개.

> 📌 **"AI 는 할 게 적다"는 인상은 측정 밀도의 착시였다.** 백엔드는 [`28-remaining-work-plan.md`](./28-remaining-work-plan.md) 300여 줄로 하위 작업까지 쪼개져 있고 AI 는 이 표 세 줄이었다. 게다가 AI 미구현은 **돌아가는 것처럼 보인다** — `ExtractReferenceData` 는 `success=True` 를 돌려주고, 세션 801 더미가 TTS 피드백을 시연해준다. 실제 작업량은 백엔드 잔여(17~28h)와 비슷하다.

---

## 4. Infra / Ops — 배포 시점

| ID | 작업 | 우선 | 의존성 | 추정 | 담당 | 상태 |
|----|------|-----|--------|------|------|------|
| OP-01 | A6 운영 알람 — Slack 웹훅 + Spring `AlertService` 헬퍼 + 감지 지점 3곳 | 🟢 | 배포 직전 | 3h | | 📋 |
| OP-02 | HTTPS 종료 + 도메인 (`api.shadowfit.com`?) | 🟢 | 도메인 발급 | 2h+ | | 📋 |
| OP-03 | MySQL 호스트 노출 차단 (운영용 `docker-compose` 분리) | 🟢 | — | 1h | | 📋 |
| OP-04 | DB 마이그레이션 도구 (Flyway) 도입 | 🟢 | — | 4h | | 📋 |
| OP-05 | dependabot — frontend npm `audit fix` (axios·@xmldom/xmldom 등) | 🟡 | — | 30m | | 📋 |
| OP-06 | dependabot — ai-server pip 신중 업그레이드 (`python-multipart`, `protobuf`) | 🟡 | proto 호환성 확인 | 1~2h | | 📋 |
| OP-07 | CI 도입 — GitHub Actions 로 PR 마다 `./gradlew test` | 🟢 | — | 2h | | 📋 |
| OP-08 | 모니터링 스택 — Spring Actuator + Prometheus + Grafana | 🟢 | — | 6h+ | | 📋 |

소계: 🟡 2개, 🟢 6개

---

## 5. 미결 결정 (작업 시작 전 답 필요)

각각 [`decisions/ai-backend-coupling.md`](../decisions/ai-backend-coupling.md) §해당 절에 트레이드오프 정리됨.

| 결정 | 막는 작업 | 상태 |
|------|---------|------|
| ~~**분기 H** 카메라 프레임 송신 경로~~ | ~~BE-01, FE-04, FE-05~~ | ✅ H2 채택 (2026-05-24) |
| **분기 I** 인증 토큰 흐름 (H2 부속) | FE-05, AI 측 인증 미들웨어, BE-10/11 | ✅ I1 정적 공유 잠정 (2026-05-24), 운영 단계에서 I2 검토 |
| **분기 B** proto 단일 소스 (루트 `proto/`) | (없음, 별도 정리 작업) | B3 또는 B1 유지 |
| **분기 D** AI 세션 in-memory vs 외부화 | (멀티 인스턴스 필요 시) | D1 (in-memory) |
| TTS 알람 채널 (Slack/이메일/SaaS) | OP-01 | A6-CH-1 (Slack 웹훅) |

---

## 6. 의존성 그래프 — 시연까지의 Critical Path

분기 H = H2 채택 결과 그래프 (백엔드 프록시 없음):

```
              [AI 측 인증 미들웨어 추가] ◀── AI 담당자 (H2 부속)
                          │
                          ▼
   [FE-01]────►[FE-02]──►[FE-04]──►[FE-05]──┐
   service     녹화 핸들러   프레임 캡처    AI 직접 송신  │
                                              │
                  ┌───────────────────────►[FE-06]
                  │ (콜백 → DB → 조회)        결과 화면
                  │                              │
              [백엔드 콜백 처리]                  ▼
              (BE-10·11 동시 진행)        ━━━━━━━━━━━━━━
                                         🎯 시연 가능 시점
                                         ━━━━━━━━━━━━━━
```

Critical path = 약 6개 작업 (AI 인증 미들웨어 + FE-01·02·04·05·06) + BE-10·11 병렬.
**작업 총량 약 15~17시간**. 두 명이 병렬로 가면 1주일, 한 명이면 2주.

---

## 7. 병렬화 가능한 그룹

같은 시점에 여러 사람이 동시 작업 가능한 묶음:

| 그룹 | 작업 | 누구 |
|------|------|------|
| A (즉시 시작) | FE-01, FE-02, FE-03 | 프론트 1명 |
| A (병렬) | BE-02 (worst 구간) | 백엔드 1명 |
| A (병렬) | OP-05 (npm audit fix) | DevOps 1명 |
| A (병렬) | **AI 측 인증 미들웨어 (H2 부속, ~2h)** | AI 담당자 |
| B (FE-01~03 완료 후) | **BE-10 (헬스체크+CB), BE-11 (콜백 검증)**, FE-04 (프레임 캡처) | 분담 |
| B (병렬) | FE-09, FE-10 (캘린더·통계 화면) | 프론트 2명째 |
| C (B 완료 후) | FE-05 (AI 직접 송신 연결), FE-06 (결과 화면) | 프론트 |
| C (병렬) | BE-03 (GPT 리포트), FE-07 (TTS) | 백엔드 + 프론트 |
| D (시연 직후) | OP-01 (알람), OP-02 (HTTPS), OP-03 (MySQL 차단), **BE-12 (Outbox)** | 인프라 + 백엔드 |

---

## 8. 권장 다음 액션 (1주차)

1. ~~**분기 H 결정**~~ ✅ H2 확정 (`decisions §11` 결정 로그)
2. **AI 담당자에게 H2 결정 + 인증 미들웨어 작업 알림** (분기 I1 잠정)
3. **FE-01·02·03 + BE-10·11 동시 진행**
4. **FE-04·FE-05·FE-06 순서대로 마무리**
4. **수동 e2e 1회** ([`18-testing-guide.md`](../18-testing-guide.md) §8)
5. 시연 가능 시점 도달 후 🟡 작업들 분배

---

## 관련 문서
- [`20-feature-roadmap.md`](./20-feature-roadmap.md) — PPT 요구사항 ↔ 코드 매핑
- [`decisions/ai-backend-coupling.md`](../decisions/ai-backend-coupling.md) — 미결 분기 트레이드오프
- [`18-testing-guide.md`](../18-testing-guide.md) §8 — 수동 e2e 절차
- [`architecture/ai-backend-integration.md`](../architecture/ai-backend-integration.md) — 결합 현황
