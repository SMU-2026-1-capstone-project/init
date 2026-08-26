# outbox 중복 흡수 · 지연 분포 — 2026-08-26

`docs/decisions/outbox-reliable-messaging.md` §6-5 "아직 안 한 것" 셋 중 둘(지연 p99 분포·중복
흡수)을 채운다. §6(2026-07-29)가 로컬 Docker 스택(mysql+backend+ai)으로 실제 HTTP·gRPC 세션을
굴려 잰 것과 같은 방법론이다.

rig: [`loadtest/measure_outbox_duplicate_and_latency.sh`](../../measure_outbox_duplicate_and_latency.sh) ·
로컬 원자료: [`raw.txt`](raw.txt) · AWS 원자료: [`aws-raw.txt`](aws-raw.txt)

## 1. 중복 흡수 — ✅ 안전 확인 (로컬 + AWS 재현)

정상 완료된 세션의 outbox 행(`status=SENT`)을 SQL로 강제 `PENDING`으로 되돌려(크래시 후
재전송을 흉내낸다 — §4-3-1의 at-least-once 조건) 발행기가 다시 집게 만들었다.

| 관측 | 로컬 | AWS(c7i.xlarge) |
|---|---|---|
| 2차 발행 도달 | 1s (1초 폴링 해상도) | 0.74s |
| reports 행 수 | 1 → 1 | 1 → 1 |
| session.status | COMPLETED 유지 | COMPLETED 유지 |

**메커니즘 — AI가 재전송을 명시적으로 식별한다.** 로컬 판 백엔드 로그:

```
AI 서버 분석 중단 요청 전송 - sessionId: 3
AI 서버 응답: 이미 중단 처리된 세션입니다(재송신).
```

두 번째 `stopAnalysis` 호출에 AI가 "이미 처리됨"으로 응답했고, Spring은 이를 받아 리포트를
중복 생성하지 않고 세션 상태도 그대로 뒀다. `docs/decisions/outbox-reliable-messaging.md` §1-3가
"이미 가진 절반"이라 적어둔 수신측 멱등성이 실제 E2E 경로에서 확인됐다.

🔴 **범위 밖**: 세션 시작 직후(0초 지연) 바로 종료하면 AI 측 세션 등록과 경합해 **1차 시도
자체가 스스로 실패**하는 경로를 리허설 중 우연히 밟았다(FAILED·report 0, 그 뒤 강제 재전송이
성공). 이건 별개의 레이스이지 duplicate-absorption 질문이 아니다 — rig은 `START_SETTLE_S`(기본
4초)로 이 레이스를 피해서 정상 케이스를 만든다. 이 레이스 자체가 실제 운영에서 문제가 되는지는
미검증(세션 시작 직후 프레임 없이 바로 종료하는 것이 정상 사용 패턴은 아니다).

## 2. 지연 분포

| | 로컬(2물리코어, 참고치) | **AWS c7i.xlarge (신뢰 가능)** |
|---|---|---|
| N | 12 | **40** |
| min | 0.08s | 0.65s |
| median | 0.87s | 0.70s |
| p95 | 1.29s | 1.03s |
| p99/max | 1.43s | 1.05s |

**AWS 값을 인용한다.** 로컬은 [[project_loadtest_env_constraint]]대로 절대값을 신뢰하지 않는다
(실제로 로컬은 0.08s처럼 AWS엔 없는 저지연 표본이 섞여 산포가 더 넓다 — 박스 잡음).

🔑 **분포가 뚜렷한 이봉(bimodal)이다** — 표본이 ~0.65~0.74s 군(다수)과 ~0.96~1.05s 군(소수)
둘로 갈린다, 그 간격이 약 0.3초. `OutboxPublisher`가 **1초 주기 폴링**이라는 것과 정확히 맞는
그림이다: outbox 행이 폴 tick 직후 생기면 거의 즉시 집히고(짧은 쪽 군), tick 직전에 생기면
다음 tick까지 거의 한 틱을 통째로 기다린다(긴 쪽 군). 즉 **p99(1.05s)의 지배 요인은 AI 처리
시간이 아니라 폴링 주기다** — 처리 자체의 바닥은 ~0.65s로 보이고, 나머지는 폴 주기 대기다.

🔴 **N=40, 단일 세션 순차 실행 기준이다.** §6-5가 열어둔 세 번째 항목(다건 동시 적체·다중
발행기)은 여전히 미측정 — 동시 부하 아래서 발행기가 밀리기 시작하면 이 그림이 달라질 수 있다.

## 무대

- 로컬: Docker Desktop, mysql+backend+ai (동시에 다른 세션 셋이 같은 저장소에서 작업 중이었음)
- AWS: `c7i.xlarge` 1대, `ap-northeast-2`, `ROLE=p6-target`(단, `AI_MEM_LIMIT=3000m`로 축소 —
  단일 세션 순차 측정이라 P6의 동시성 캡 용량이 필요 없음), 커밋 `0e7ffeb`
- 🔴 **부트스트랩이 스스로는 "준비됨"까지 못 갔다** — AI 헬스체크가 호스트 `localhost:8000`을
  찌르는데, 오늘 낮 커밋(`d73e99d`, N=3 워커+`ai-nginx` 전환)이 그 포트의 호스트 매핑을
  없앴다(`ai-nginx`를 거쳐야 한다). `p6-target`의 `docker compose up` 목록엔 `ai-nginx`가
  없어 그 계층이 안 뜬다. **이 판엔 영향 없음** — Spring→AI 통신은 gRPC(도커 내부망, 호스트
  포트 무관)라 컨테이너 자체는 정상이었다. 별도 이슈로 등록: **[#567](https://github.com/Shadowfit/init/issues/567)**
- 인스턴스는 측정 종료 직후 수동 종료·터미네이트 확인 완료(이 rig은 `run_all.sh`를 안 거치는
  standalone 호출이라 자동 종료 보호가 없다 — [[project_unattended_aws_round_recipe]])
