# decisions/ 인덱스

이 폴더는 파일을 이동/재배치하지 않고 그대로 둔 채, 카테고리별 탐색만 회복하기 위한 인덱스다. 새 decision 문서를 추가하면 이 README에도 한 줄을 같이 추가할 것.

**상태 표기 기준** — 파일 안에 실제로 적힌 결론에 근거해 판정했다. 근거를 못 찾은 파일은 추측하지 않고 **미상**으로 남겼다.
- **확정**: 결정되어 적용됨(구현·머지·사용자 confirm 완료)
- **반증**: 가설이 실측으로 뒤집혔거나 revert됨
- **진행중**: 일부 확정·일부 미결, 다음 라운드로 이어짐
- **보류**: 설계만 하고 착수/실행 미결정
- **미상**: 파일 tail만으로 결론을 판단할 근거가 부족함(재분류 시 본문 확인 필요)

## AI 성능·동시성

- [ai-channel-pool-hardening.md](./ai-channel-pool-hardening.md) — AI 채널 풀 장애 재배치·강화 (보류 — 부하 중 장애 빈도 실측 2회 모두 답 못 냄, 인프라 이슈로 이관)
- [ai-coresidency-capacity.md](./ai-coresidency-capacity.md) — 동거 용량 실측("156세션은 혼자 살 때 값") (진행중 — 다음 라운드 등록, 채택은 미결)
- [ai-cross-vm-affinity-probe.md](./ai-cross-vm-affinity-probe.md) — VM간 세션 어피니티 축소 검증 (보류 — 설계만, AWS 실행 미착수)
- [ai-horizontal-scaling.md](./ai-horizontal-scaling.md) — AI 수평 확장 — 세션 상태를 누가 갖는가 (보류 — 2대 실측 없음, §8 미측정 다수)
- [ai-load-budget.md](./ai-load-budget.md) — AI 서버 부하 버짓/베이스라인 측정 (미상)
- [ai-multiprocess-deployment-review.md](./ai-multiprocess-deployment-review.md) — AI 다중 프로세스 배포 코드 리뷰 (확정 — fork+COW 착수 안 함으로 결론)
- [ai-multiprocess-frame-routing.md](./ai-multiprocess-frame-routing.md) — 프로세스 분리 후 프레임 라우팅 버그 3종 (진행중 — 버그는 고쳤으나 N=3+동거 실측 남음)
- [ai-process-ceiling-cause.md](./ai-process-ceiling-cause.md) — 프로세스당 천장 원인 — GIL·이벤트루프·스레드풀 (보류 — 문서 tail 기준 "판은 미착수")
- [ai-receive-path-scaling.md](./ai-receive-path-scaling.md) — 16코어 중 9.5만 쓰는 마지막 후보 확인 (보류 — 격자가 "어디"만 답하고 "왜"는 다음 판)
- [ai-sticky-routing.md](./ai-sticky-routing.md) — AI 수평확장 — 스티키 라우팅 채택 여부 (진행중 — 추천 갱신, 선결 이슈·미측정 남음)
- [ai-sticky-routing-probe.md](./ai-sticky-routing-probe.md) — 스티키 라우팅 축소 측정 (확정 — 재실행 완료, "이미 갈라진 부하" 캐비엇 완전히 닫힘)
- [ai-worker-load-soak-experiment.md](./ai-worker-load-soak-experiment.md) — AI 워커 soak — 부하 중 자발적 장애 빈도 (보류 — 원 질문 미답인 채 스레드 종결, 원인 후보 3개 다 미확정)
- [detector-pool-ceiling-formula.md](./detector-pool-ceiling-formula.md) — 검출기 풀 상한 공식 실측 (보류 — 절편 708 정체 미상, 한 기계·한 워크로드 값)
- [per-process-ceiling-cause.md](./per-process-ceiling-cause.md) — 프로세스당 천장 «원인» 판별 설계 (확정 — 제목에 "§8·§9 실행 완료 2026-08-24")
- [per-process-ceiling-variance.md](./per-process-ceiling-variance.md) — 천장이 11% 흔들리는 이유(응답모드×GIL) (보류 — 설계 초안, 착수 미결정)

## DB·스토리지

- [covering-index-write-throughput.md](./covering-index-write-throughput.md) — 커버링 인덱스 쓰기 처리량(ms) 실측 설계 (보류 — 설계 초안, 착수 미결정)
- [durability-relaxation-inversion.md](./durability-relaxation-inversion.md) — 내구성 완화했는데 더 느려지는 이유 (반증 — 그룹커밋 붕괴 가설(H1) 재반증, 이 조건에선 완화 이득 없음)
- [mysql-vs-postgresql.md](./mysql-vs-postgresql.md) — MySQL vs PostgreSQL (확정 — MySQL 유지 권고, 근거를 실측+운영여력으로 재정리)
- [online-ddl-vs-blocking-alter.md](./online-ddl-vs-blocking-alter.md) — 무중단 스키마 변경(pt-osc) 96분 차단 ALTER 재측정 (확정 — 실측 완료, 수치 오류 정정·전파처 5곳 교정 완료)
- [partition-hole-drop-significance-retest.md](./partition-hole-drop-significance-retest.md) — 파티션 구멍→DROP +13% 유의성 재측정 설계 (보류 — 설계 초안, 착수 미결정)
- [pool-cliff-vs-concurrency.md](./pool-cliff-vs-concurrency.md) — 풀 사이징 cliff가 동시성에 따라 움직이는가 (진행중 — 1순위 용의자 반증됨, 커밋 내구성·rep 간격 등 미결 다수)
- [pose-batch-idempotency-implementation.md](./pose-batch-idempotency-implementation.md) — pose_data 멱등 구현(세션 단위) (확정 — 세션 단위 채택, 실측으로 원인 재확인)
- [pose-batch-idempotency-vs-partition.md](./pose-batch-idempotency-vs-partition.md) — pose_data 적재 멱등성과 파티션 충돌(#188) (진행중 — 참조무결성·멱등성 목록화라는 다음 질문을 엶)
- [pose-data-partition-fk-tradeoff.md](./pose-data-partition-fk-tradeoff.md) — pose_data 파티셔닝과 FK 제거 트레이드오프 (확정 — A2/B5 확정, 실구현 완료)
- [pose-ingest-downsampling.md](./pose-ingest-downsampling.md) — pose_data 적재 다운샘플링("1초 평균") (확정 — PR #53 실착수 완료, 이후 여러 차례 재측정도 완료)
- [projection-end-to-end-remeasure.md](./projection-end-to-end-remeasure.md) — projection "−98.7%"가 API 레벨에서 얼마인지 재측정 설계 (보류 — 실행 미착수, 미결정 5건)
- [querydsl-adoption.md](./querydsl-adoption.md) — 동적 쿼리 계층(QueryDSL/Criteria) 도입 여부 (확정 — 여러 항목 확정, Criteria 직접사용·Specification 병용 모두 기각)
- [report-read-path.md](./report-read-path.md) — 리포트 기능 읽기 경로 감사 — 병목·갭·로드맵 (미상)
- [resolution-tiers.md](./resolution-tiers.md) — 해상도 티어 다중 그레인 롤업 설계 (미상 — 설계 방향만 tail에 보임, 실행 여부 불명)
- [row-shape-partition-interaction.md](./row-shape-partition-interaction.md) — pose_data 행 모양×파티션 상호작용 (보류 — 제목에 "미실행" 명시)
- [schema-migration-tracking.md](./schema-migration-tracking.md) — 스키마 마이그레이션 적용 이력 추적 방법 (진행중 — #115 관측 피해는 해소 확인, 일부 미검증 남음)
- [session-index-composition.md](./session-index-composition.md) — exercise_sessions 인덱스 구성(떼기 아닌 합치기) (확정 — 2026-08-07 confirm, 마이그레이션 반영)

## 부하테스트 방법론·실험 설계

- [async-pool-backpressure-experiment.md](./async-pool-backpressure-experiment.md) — `@Async` 풀·서킷브레이커 백프레셔("AI 멈추면 Spring은 무엇을 쌓는가") (보류 — 큐 길이 측정 방법 자체가 막혀 H1 여전히 미답)
- [async-pool-queue-instrumentation.md](./async-pool-queue-instrumentation.md) — `applicationTaskExecutor` 큐 길이 계측 채널 설계 (보류 — 채널 선택·재실험 착수 미결정)
- [commit-count-and-mysql-metrics.md](./commit-count-and-mysql-metrics.md) — 커밋 횟수 레버와 MySQL 지표 4차 실측 설계 (보류 — 설계만, 실행 전)
- [e2e-regression-detector-seam.md](./e2e-regression-detector-seam.md) — E1 회귀를 저장소 안에서 돌리기 위한 이음매 설계 (확정 — 방향 B·세부 셋 확정, 사용자 confirm)
- [experiment-inventory.md](./experiment-inventory.md) — 실험 재고 — Spring·MySQL·AI 세 파트 남은 측정 (진행중 — 계속 갱신되는 살아있는 재고표)
- [four-axes-depth-experiments.md](./four-axes-depth-experiments.md) — DB 외 네 축 실험 설계 (보류 — 실행 미착수, 종료조건 기준 잉여로 명시)
- [h3-neighbor-starvation-conditions.md](./h3-neighbor-starvation-conditions.md) — H3(옆이 굶는 조건)는 이 박스에서 시험 불가 (반증 — H3가 "반증 아니라 미시험"이었고, 이 박스에선 조건 자체가 성립 안 함)
- [load-test-glossary.md](./load-test-glossary.md) — 부하테스트 용어집 (확정 — 참고자료, 용어 정의 자체는 안정)
- [load-test-strategy.md](./load-test-strategy.md) — 3-tier(프론트·AI·백엔드) 부하테스트 전략 (확정 — 다른 실험 문서들의 기준점)
- [loadtest-payload-uniqueness.md](./loadtest-payload-uniqueness.md) — 부하 페이로드를 매번 다르게 만드는 법(멱등키 충돌) (확정 — 마이그레이션 버전 재정렬로 해소)
- [observability-overhead-under-ai-load.md](./observability-overhead-under-ai-load.md) — 관측 스택 동거 비용(Q5) (보류 — 설계만, 단독 라운드보다 다음 從 라운드에 얹는 걸 추천)
- [outbox-batch-throughput-ceiling.md](./outbox-batch-throughput-ceiling.md) — outbox 완결 처리량 천장(#573 3차) (보류 — rig은 있으나 실행한 적 없음)
- [pool-sizing-10-20-experiment-design.md](./pool-sizing-10-20-experiment-design.md) — 커넥션 풀 사이징 재실험 설계 — 10~20 사이 좁히기 (보류 — EC2 라운드 착수 승인 등 실행 전 확인 필요, 미착수)
- [r10-loadgen-topology.md](./r10-loadgen-topology.md) — R10 무대 — 부하기를 대상 박스에 둘 것인가 (진행중 — 분기 확정, EC2 실행은 0판)
- [read-path-spike-test.md](./read-path-spike-test.md) — 읽기 경로 스파이크 테스트 (확정 — 사용자 confirm 후 EC2 2대 무인 라운드 실행 완료)
- [round-to-round-nonreproducibility.md](./round-to-round-nonreproducibility.md) — 라운드를 건너면 절대값이 안 맞는 이유(+17.7%) 설계 (진행중 — 방향 제시, 축 0 실험 필요)
- [session-spread-sweep.md](./session-spread-sweep.md) — 세션 분산도 스윕("100세션은 잰 값이 아니다") (보류 — 실행 미착수, 從 스윕만 결정됨)
- [slo-baseline.md](./slo-baseline.md) — 판정선과 SLO — "얼마면 나쁜 건가" (확정 — baseline/threshold 원칙 확정, 개별 값은 대부분 의도적으로 비움)

## 세션·도메인 로직

- [2026-05-27-channel-and-youtube-review.md](./2026-05-27-channel-and-youtube-review.md) — 실시간 채널 심층분석 + 유튜브 좌표 활용 가능성 검토 기록 (미상)
- [feedback-238-review-triage.md](./feedback-238-review-triage.md) — PR #238 리뷰 지적 11건 분류·처리 (진행중 — 일부 반영, 수단·상한·지터 실측 등 열린 질문 다수)
- [feedback-argmax-rules.md](./feedback-argmax-rules.md) — argmax 귀속 계산 규칙(#228) (진행중 — 결정은 났으나 정답지 기울기 실측 등 미검증 다수)
- [feedback-batch-retransmission.md](./feedback-batch-retransmission.md) — 피드백 배치 재전송 설계(#193) (확정 — 버퍼·flush 규칙 구현 세부 확정)
- [feedback-type-detector.md](./feedback-type-detector.md) — 자세 문제 유형 감지기 — 결함 판단 근거(#193 2단계) (진행중 — 정답지 지터 측정 가능해졌으나 실값 미기록)
- [pattern-analysis-implementation.md](./pattern-analysis-implementation.md) — 패턴 분석 API 구현계획(BE-07) (확정 — 9개 항목 구현 완료, 포폴 카드 연결)
- [pose-frame-base64-cost.md](./pose-frame-base64-cost.md) — POST /pose 프레임 base64 비용 실측 (보류 — 해상도 캡 도입 여부 미결정, 실기기 촬영본 검증 필요)
- [recommendation-algorithm.md](./recommendation-algorithm.md) — 운동 추천 알고리즘(BE-08) (미상)
- [reference-freeze.md](./reference-freeze.md) — 정답지를 한 번 뽑고 고정한다(추출 비결정성) (확정 — 원인 규명 완료, 대응 방향 확정, 이슈 닫힘)
- [reference-score-min-knee-variance.md](./reference-score-min-knee-variance.md) — 정답지 min_knee 판별 변동("지터 3.5°"의 진위, #256) (진행중 — 측정 방법은 이미 구현돼 있음, 입력 자산 확보가 남은 과제)
- [reference-style-and-caching.md](./reference-style-and-caching.md) — 선택형 스타일 기준 추출·전송·캐싱 설계 (보류 — 스타일 식별자 부재로 막힌 지점 확인, 미구현)
- [reference-style-identity.md](./reference-style-identity.md) — 스타일 식별자 — 고른 게 채점에 안 닿는 문제 (보류 — 설계 초안, 실행 미착수, 미결정 6건)
- [rep-timing-fps-contract.md](./rep-timing-fps-contract.md) — rep 판정의 시간 축을 무엇으로 둘 것인가(#143) (진행중 — 비교표는 있으나 가시성·동시세션 비용 미검증)
- [report-aggregation.md](./report-aggregation.md) — 리포트 집계 로직(BE-02, worst구간·syncRateDetails 등) (확정 — 6개 결정 일괄 채택)
- [report-generation-llm.md](./report-generation-llm.md) — 리포트 문장 생성 — LLM을 어디에 붙일 것인가 (보류 — 스키마 초안만, "지금 적용 대상 아님"으로 명시)
- [session-detector-ownership.md](./session-detector-ownership.md) — 검출기를 세션에 붙일지 스레드에 붙일지(#164) (확정 — ㄴ안 채택 확정, 구현은 별건)
- [session-end-trigger.md](./session-end-trigger.md) — 세션 종료 신호 트리거 재검토(ET-A vs ET-B) (확정 — ET-H로 정정, 관련 문서 갱신 완료)
- [session-lifecycle-checklist.md](./session-lifecycle-checklist.md) — 세션 생명주기(상태 관리) 설계 체크리스트 (미상)
- [session-liveness-vs-elapsed-time.md](./session-liveness-vs-elapsed-time.md) — 세션 생존 판정 — 경과시간인가 마지막활동인가 (확정 — 결정 유지, 남은 항목은 우선순위 근거일 뿐 전제가 아님)
- [session-resume-and-ai-state.md](./session-resume-and-ai-state.md) — 세션 재개(resume)와 AI 분석 상태 내구성 (확정 — 구현+통합검증 완료, PR #62)
- [tts-design.md](./tts-design.md) — TTS 음성 안내 설계 (미상)
- [withdrawal-with-active-session.md](./withdrawal-with-active-session.md) — 진행 중 세션이 있는 회원의 탈퇴 처리 (확정 — 완화책 구현·배포 완료, 판정 컬럼 교체 완료)
- [worst-section-rep-resolution.md](./worst-section-rep-resolution.md) — worst 구간을 어느 해상도로 계산할 것인가(#78) (진행중 — 영향 규모는 실측했으나 실데이터 재현은 안 됨)
- [youtube-coordinate-harvest.md](./youtube-coordinate-harvest.md) — 유튜브 영상→좌표 확보(스쿼트 폼 평가 튜닝용) (보류 — 미해결 확인 다수, 착수 여부 불명)

## 아키텍처·서비스 통합

- [ai-auth-token-flow.md](./ai-auth-token-flow.md) — AI 인증 토큰 흐름(분기 I 재개) (진행중 — 정적 판독 위주, 공격 재현·지연 비용 미측정)
- [ai-backend-coupling.md](./ai-backend-coupling.md) — AI↔Backend 결합 방식(분기 A~I) (확정 — H2 프론트 직결 등 다수 분기 채택 확정)
- [ai-session-ownership-verification.md](./ai-session-ownership-verification.md) — 세션 소유권 검증 — 신원은 채널①에서만(#187) (진행중 — 정적 사실은 확정, 동적 재현은 미착수)
- [api-improvement-opportunities.md](./api-improvement-opportunities.md) — 지금까지 만든 API 표면 감사 (확정 — 9개 항목 전부 커밋 반영 완료)
- [architecture-review-2026-08-11.md](./architecture-review-2026-08-11.md) — 아키텍처 회고 — 결함·재조립·점수 (확정 — 새 결함 발견 즉시 수정, 결함 ⑤는 철회)
- [circuit-breaker-worker-aggregation.md](./circuit-breaker-worker-aggregation.md) — 서킷브레이커 워커 3개 실패율 합산(#556) (진행중 — 워킹트리에 반영, 커밋은 별도 요청 시)
- [grpc-integration-checklist.md](./grpc-integration-checklist.md) — gRPC 좌표 송수신(AI↔Backend) 설계 체크리스트 (진행중 — 일부 완료, 관련 이슈 열려있음)
- [grpc-vs-webclient.md](./grpc-vs-webclient.md) — gRPC vs WebClient 통신 방식 (보류 — 결정 로그가 "사용자 confirm 대기"로 비어있음)
- [latency-perception.md](./latency-perception.md) — Latency 단위(ms·s) 의미와 컴포넌트 매핑 (진행중 — 계속 갱신, 분기 7 격상 검토 트리거 남음)
- [observability-correlation-id.md](./observability-correlation-id.md) — 관측성 1차 — correlation id 전파 + 커스텀 메트릭 (확정 — PR #54 머지 완료)
- [outbox-reliable-messaging.md](./outbox-reliable-messaging.md) — 신뢰성 있는 비동기 통보(Outbox, 세션종료 통보 유실 E1) (확정 — 설계·구현·측정·리뷰반영 전부 완료)
- [performance-tactics-availability-tradeoff.md](./performance-tactics-availability-tradeoff.md) — 아키텍처 품질속성 — 성능 택틱과 가용성 트레이드오프 (진행중 — 택틱 A 착수 확정(2026-09-04), B·C는 보류)
- [pose-batch-midflight-cancellation.md](./pose-batch-midflight-cancellation.md) — SavePoseDataBatch mid-flight 취소(#206 B-2) 가치 판단 (보류 — 재측정 완료했으나 채택 여부는 미결)
- [production-signal-checklist.md](./production-signal-checklist.md) — 백엔드 프로덕션 시그널 체크리스트(8개 기법) (진행중 — 관찰 기록 위주, mid-flight 취소는 미결)
- [r276-retry-followup.md](./r276-retry-followup.md) — #276 재시도 후속 — 재고표보다 남은 게 적음(문서 드리프트) (진행중 — 일부 이미 닫힌 것 확인, 남은 설계는 착수 미결정)
- [redis-adoption.md](./redis-adoption.md) — Redis 도입 — 넣을까, 넣는다면 어디에 (반증 — 1순위 추천(JWT 블랙리스트)의 전제 자체가 소멸 확인)
- [redis-introduction.md](./redis-introduction.md) — Redis 도입 — MySQL로 부족한지 엄격한 증명 (확정 — 결론은 "안 쓴다", 면접 자료로 활용하기로 함)

## 인증·계정·보안

- [admin-page-scope.md](./admin-page-scope.md) — 관리자 페이지 범위·필터 항목 정의 (확정 — 별도 웹+페이지번호 등 다수 항목 확정)
- [admin-role-provisioning.md](./admin-role-provisioning.md) — 관리자 권한을 어떻게 주나(가입 시 자기부여 대체) (확정 — 서버 심사·별도 신청 테이블로 확정)
- [external-integration-candidates.md](./external-integration-candidates.md) — 외부 통합 후보 비교(OAuth2/S3/푸시/결제/검색) (보류 — 추천만, 확정 아님)
- [hash-function-selection.md](./hash-function-selection.md) — 해시·체크섬 함수 — 목적별 분류와 고르는 법 (확정 — 참고용 선택 가이드)
- [oauth-implementation-considerations.md](./oauth-implementation-considerations.md) — OAuth2 소셜 로그인 — 붙이려면 무엇을 먼저 정해야 하나 (보류 — D1 포함 11건 전부 열려있음, "아무것도 확정 안 함")
- [token-lifecycle.md](./token-lifecycle.md) — 토큰 수명 설계(#135·#136·#137) (확정 — 블랙리스트 제거·access 30분 등 확정, 실구현 완료)

## 인프라·운영

- [backup-restore-rto-rpo.md](./backup-restore-rto-rpo.md) — 백업·복구 — "되돌릴 수 있다"를 시간으로 (진행중 — #202 실측 완료, 병렬 덤프 등은 범위 밖으로 남음)
- [major-version-upgrade-policy.md](./major-version-upgrade-policy.md) — 메이저 버전업 2건(springdoc 3.x·Gradle 9) (보류 — §8에 "재지 않은 것" 다수, 테스트·실측 안 됨)
- [replication-lag-and-semisync.md](./replication-lag-and-semisync.md) — 복제 — "한 대가 죽어도 된다"를 지연과 대가로 (진행중 — 조건부 결론, 다른 AZ는 안 잼)
- [reverse-proxy-and-tls.md](./reverse-proxy-and-tls.md) — 리버스 프록시·TLS 종료 — nginx를 넣어야 하나 (진행중 — 이슈 일부 등록, CORS PATCH 누락은 여전히 미등록)

## 포트폴리오·전략·서사

- [db-portfolio-roadmap.md](./db-portfolio-roadmap.md) — 이 프로젝트를 백엔드 DB 포폴로 만들기 — 로드맵 (진행중 — 미결정 대부분 해소, "1초 평균 집계 위치" 1건만 남음)
- [portfolio-benchmark.md](./portfolio-benchmark.md) — 유사 포폴 벤치마크 — 채울 키워드 vs 밀 차별점 (확정 — 서사 정정 반영된 참고자료)
- [professor-vision-backend-impact.md](./professor-vision-backend-impact.md) — 교수님 피드백 3갈래 — 백엔드 영향 분석 (보류 — 채택 여부 전부 미결)
- [project-destination-and-exit-criteria.md](./project-destination-and-exit-criteria.md) — 프로젝트 목적지와 종료 조건 (확정 — 종료조건 E1~E4 확정, 일부 세부만 미결)

---
총 105개 파일. 작성 시점: 2026-09-04.
