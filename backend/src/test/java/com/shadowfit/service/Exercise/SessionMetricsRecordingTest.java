package com.shadowfit.service.Exercise;

import com.shadowfit.dto.exercises.VideoRequestDto;
import com.shadowfit.global.observability.SessionMetrics;
import com.shadowfit.grpc.AnalyzeRequest;
import com.shadowfit.grpc.AnalyzeResponse;
import com.shadowfit.grpc.ExerciseServiceGrpc;
import com.shadowfit.grpc.StopRequest;
import com.shadowfit.grpc.StopResponse;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.outbox.DispatchOutcome;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.repository.exercise.ExerciseReferenceRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 커스텀 지표가 <b>실제로 기록되는지</b> 검증한다 — 목이 아니라 진짜 {@link SimpleMeterRegistry} 로.
 *
 * <p>[왜 별도 클래스인가] 지표 기록은 세 서비스에 흩어져 있지만 하나의 관심사(관측성)라,
 * "이 사건이 나면 저 계수기가 오른다"를 한 곳에서 읽을 수 있게 모았다. 각 서비스의 기능 테스트는
 * 기존 `ExerciseAnalysisServiceTest` / `SessionServiceTest` / `PoseDataServiceTest` 에 그대로 있다.
 *
 * <p>[왜 목 레지스트리가 아닌가] {@code verify(metrics).sessionTransition(...)} 로는 지표 <b>이름·태그</b>
 * 오타를 못 잡는다. 계수기는 조용히 실패하는 종류의 코드다 — 호출이 빠지든 태그를 틀리든 예외가 안 나고,
 * 나중에 대시보드의 "충돌 0건"이 진짜 0건인지 계측 고장인지 구분할 수 없게 된다. 그래서 실제 레지스트리에
 * 그 이름·그 태그로 조회했을 때 값이 나오는지까지 확인한다. ({@code SessionTimeoutSchedulerTest} 와 동일 패턴)
 *
 * <p>[왜 단위 테스트인가] 낙관락 충돌·gRPC onError 는 통합 컨텍스트에서 결정적으로 재현하기 어렵다
 * (전자는 실제 동시 커밋, 후자는 죽은 AI 서버 + 테스트 트랜잭션 밖 콜백 스레드가 필요). 프록시를 거치지 않고
 * 원객체를 직접 호출하면 {@code @Async}/{@code @Transactional} 없이 그 분기만 정확히 때릴 수 있다.
 */
@DisplayName("세션 지표 기록 테스트")
class
SessionMetricsRecordingTest {

    private static final String TRANSITIONS = "shadowfit.session.transitions";
    private static final String CONFLICTS = "shadowfit.session.optimistic.lock.conflicts";
    private static final String POSE_FRAMES = "shadowfit.pose.batch.frames";
    private static final String AI_STOP_RESULT = "shadowfit.ai.stop.result";

    private SimpleMeterRegistry registry;
    private SessionMetrics metrics;

    @BeforeEach
    void setUpRegistry() {
        registry = new SimpleMeterRegistry();
        metrics = new SessionMetrics(registry);
    }

    private double transitions(Status status, String source) {
        return registry.counter(TRANSITIONS, "status", status.name(), "source", source).count();
    }

    private double conflicts(String source, String outcome) {
        return registry.counter(CONFLICTS, "source", source, "outcome", outcome).count();
    }

    private double stopResults(String outcome) {
        return registry.counter(AI_STOP_RESULT, "outcome", outcome).count();
    }

    @Nested
    @DisplayName("ExerciseAnalysisService")
    class AnalysisService {

        @Mock private SessionRepository sessionRepository;
        @Mock private ExercisesRepository exercisesRepository;
        @Mock private MemberRepository memberRepository;
        @Mock private SessionService sessionService;
        @Mock private ExerciseReferenceRepository referenceRepository;
        // 재부착 시 MAX(rep_number) 복원용 (이슈 #59 2단계). 이 테스트가 보는 경로는 안 쓴다.
        @Mock private PoseDataRepository poseDataRepository;

        private CircuitBreakerRegistry circuitBreakerRegistry;
        private ExerciseServiceGrpc.ExerciseServiceStub stub;
        // 중단 송신만 블로킹 스텁을 쓴다 — 발행기가 결과로 행 상태를 정하므로 반환값이 필요하다.
        private ExerciseServiceGrpc.ExerciseServiceBlockingStub blockingStub;
        private ExerciseAnalysisService service;

        @BeforeEach
        void setUp() {
            MockitoAnnotations.openMocks(this);
            circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
            stub = mock(ExerciseServiceGrpc.ExerciseServiceStub.class);
            // getAuthenticatedStub() 의 빌더 체인 — 어느 단계든 같은 목을 돌려주면 된다
            when(stub.withInterceptors(any())).thenReturn(stub);
            when(stub.withDeadlineAfter(anyLong(), any())).thenReturn(stub);
            blockingStub = mock(ExerciseServiceGrpc.ExerciseServiceBlockingStub.class);
            when(blockingStub.withInterceptors(any())).thenReturn(blockingStub);
            when(blockingStub.withDeadlineAfter(anyLong(), any())).thenReturn(blockingStub);

            service = new ExerciseAnalysisService(sessionRepository, exercisesRepository,
                    memberRepository, sessionService, referenceRepository, poseDataRepository,
                    circuitBreakerRegistry, metrics);
            ReflectionTestUtils.setField(service, "internalToken", "test-token");
            ReflectionTestUtils.setField(service, "exerciseAsyncStub", stub);
            ReflectionTestUtils.setField(service, "exerciseBlockingStub", blockingStub);

            when(referenceRepository.findByExerciseId(anyLong())).thenReturn(List.of());
        }

        private VideoRequestDto dto() {
            return VideoRequestDto.builder().exerciseId(1L).build();
        }

        @Test
        @DisplayName("서킷 OPEN으로 세션을 걷어내면 FAILED 전이가 source=circuit-open 으로 기록된다")
        void circuitOpen_recordsFailedTransition() {
            circuitBreakerRegistry.circuitBreaker("aiServer").transitionToOpenState();
            when(sessionService.markAsFailedIfStillInProgress(eq(1L), any(LocalDateTime.class))).thenReturn(true);

            service.sendAnalysisRequestToFastApi(1L, dto(), "https://youtu.be/dummy", "BEGINNER");

            assertThat(transitions(Status.FAILED, "circuit-open")).isEqualTo(1.0);
            // 원인이 다른 FAILED와 섞이면 안 된다 — source 태그가 존재 이유이므로
            assertThat(transitions(Status.FAILED, "grpc-error")).isZero();
        }

        @Test
        @DisplayName("서킷 OPEN이어도 세션이 이미 종료 상태면(false) 지표를 올리지 않는다")
        void circuitOpen_alreadyFinished_recordsNothing() {
            circuitBreakerRegistry.circuitBreaker("aiServer").transitionToOpenState();
            when(sessionService.markAsFailedIfStillInProgress(eq(1L), any(LocalDateTime.class))).thenReturn(false);

            service.sendAnalysisRequestToFastApi(1L, dto(), "https://youtu.be/dummy", "BEGINNER");

            assertThat(transitions(Status.FAILED, "circuit-open")).isZero();
        }

        @Test
        @DisplayName("gRPC onError로 세션을 걷어내면 FAILED 전이가 source=grpc-error 로 기록된다")
        void grpcError_recordsFailedTransition() {
            // 서킷은 CLOSED — 호출은 나가고 그 호출이 실패하는 경로.
            // notifyAi=true 로 스텁하는 이유: 이 경로는 StartAnalysis 를 실제로 보냈으므로 AI 에
            // 상태가 만들어졌을 수 있다("에러 = 모름"). 위 circuit-open 테스트가 2-인자 그대로인
            // 것과 대비되고, 그 차이가 이슈 #98 의 호출처 분기다.
            when(sessionService.markAsFailedIfStillInProgress(eq(2L), any(LocalDateTime.class), eq(true)))
                    .thenReturn(true);
            doAnswer(invocation -> {
                StreamObserver<AnalyzeResponse> observer = invocation.getArgument(1);
                observer.onError(new StatusRuntimeException(io.grpc.Status.fromCode(Code.UNAVAILABLE)));
                return null;
            }).when(stub).startAnalysis(any(AnalyzeRequest.class), any());

            service.sendAnalysisRequestToFastApi(2L, dto(), "https://youtu.be/dummy", "BEGINNER");

            assertThat(transitions(Status.FAILED, "grpc-error")).isEqualTo(1.0);
            assertThat(transitions(Status.FAILED, "circuit-open")).isZero();
        }

        @Test
        @DisplayName("중단 응답 success=false 면 session-missing 으로 기록하고 즉시 FAILED 로 걷어낸다")
        void stopAnalysis_sessionMissing_recordsAndFailsFast() {
            when(sessionService.markAsFailedIfStillInProgress(eq(7L), any(LocalDateTime.class))).thenReturn(true);
            stubStopResponse(false, "진행 중인 세션을 찾을 수 없습니다.");

            DispatchOutcome outcome = service.stopAnalysis(7L, false);

            // 재시도해도 AI 는 그 세션을 영영 모른다 — 발행기가 행을 FAILED 로 종결해야 한다.
            // SENT 로 보면 실제 결과 유실이 "전송 성공"으로 위장된다.
            assertThat(outcome).isEqualTo(DispatchOutcome.TERMINAL_FAILED);
            assertThat(stopResults("session-missing")).isEqualTo(1.0);
            assertThat(transitions(Status.FAILED, "ai-session-missing")).isEqualTo(1.0);

            // 전송 자체는 성공했으므로 서킷에는 "성공"으로 기록돼야 한다 — AI 는 새 분석을 받을 수
            // 있는 상태라, 여기서 실패로 집계하면 신규 startAnalysis 까지 막히게 된다.
            // getState()==CLOSED 만으로는 검증이 안 된다: 기본 minimumNumberOfCalls 가 100 이라
            // 아무것도 기록되지 않아도 CLOSED 라, 그 단언은 항상 통과한다.
            CircuitBreaker.Metrics cb = circuitBreakerRegistry.circuitBreaker("aiServer").getMetrics();
            assertThat(cb.getNumberOfSuccessfulCalls()).isEqualTo(1);
            assertThat(cb.getNumberOfFailedCalls()).isZero();
        }

        @Test
        @DisplayName("FAILED 처리 중 낙관락 충돌이 나면 양보하되 전달 결과는 그대로 돌려준다")
        void stopAnalysis_sessionMissing_lockConflict_yields() {
            when(sessionService.markAsFailedIfStillInProgress(eq(10L), any(LocalDateTime.class)))
                    .thenThrow(new ObjectOptimisticLockingFailureException(Session.class, 10L));
            stubStopResponse(false, "진행 중인 세션을 찾을 수 없습니다.");

            // 예외가 새어나가면 발행기가 행 상태를 못 정해 PROCESSING 으로 남고, lock 만료까지
            // 불필요하게 붙들린다 — 세션 전이 실패가 전달 결과 판정을 오염시키면 안 된다.
            DispatchOutcome outcome = service.stopAnalysis(10L, false);

            assertThat(outcome).isEqualTo(DispatchOutcome.TERMINAL_FAILED);
            assertThat(conflicts("ai-session-missing", "yield")).isEqualTo(1.0);
            // 양보했으므로 FAILED 전이는 없다 — 완료 콜백이 이긴 것
            assertThat(transitions(Status.FAILED, "ai-session-missing")).isZero();
        }

        @Test
        @DisplayName("중단 응답 success=true 면 ok 로만 기록하고 세션을 건드리지 않는다")
        void stopAnalysis_success_recordsOkOnly() {
            stubStopResponse(true, "분석 중단 및 결과 보고 예약 완료.");

            DispatchOutcome outcome = service.stopAnalysis(8L, false);

            assertThat(outcome).isEqualTo(DispatchOutcome.SENT);
            assertThat(stopResults("ok")).isEqualTo(1.0);
            assertThat(stopResults("session-missing")).isZero();
            assertThat(transitions(Status.FAILED, "ai-session-missing")).isZero();
        }

        @Test
        @DisplayName("회수분 재송신의 success=false 는 세션을 걷어내지 않는다 — 중복 배달이다 (#152)")
        void stopAnalysis_sessionMissing_onRedelivery_doesNotFailSession() {
            stubStopResponse(false, "진행 중인 세션을 찾을 수 없습니다.");

            // 아웃박스는 at-least-once — 발행기가 송신 직후·결과 기록 전에 죽으면 lease 만료 후
            // 같은 StopAnalysis 가 다시 나간다. AI 수신부는 멱등하지 않아(첫 호출이 상태를 제거)
            // 두 번째 호출은 «반드시» success=false 다. 그 값을 유실로 읽으면 정상 완료 중인
            // 세션을 FAILED 로 뒤집고 거짓 실패 지표를 남긴다.
            DispatchOutcome outcome = service.stopAnalysis(7L, true);

            // 재시도는 여전히 무의미하다 — 행은 종결한다.
            assertThat(outcome).isEqualTo(DispatchOutcome.TERMINAL_FAILED);

            // 핵심: 세션을 건드리지 않는다.
            verify(sessionService, never())
                    .markAsFailedIfStillInProgress(anyLong(), any(LocalDateTime.class));
            assertThat(transitions(Status.FAILED, "ai-session-missing")).isZero();

            // 사건 자체는 남긴다. 다만 «유실» 지표와 섞지 않는다 — 섞으면 정상 중복 배달이 유실
            // 카운터를 부풀려, 진짜 유실이 났을 때 구분할 수단이 사라진다.
            assertThat(stopResults("session-missing-redelivery")).isEqualTo(1.0);
            assertThat(stopResults("session-missing")).isZero();
        }

        @Test
        @DisplayName("첫 배달의 success=false 는 그대로 걷어낸다 — #152 수정이 원 동작을 지우지 않았다")
        void stopAnalysis_sessionMissing_onFirstDelivery_stillFailsFast() {
            when(sessionService.markAsFailedIfStillInProgress(eq(13L), any(LocalDateTime.class)))
                    .thenReturn(true);
            stubStopResponse(false, "진행 중인 세션을 찾을 수 없습니다.");

            assertThat(service.stopAnalysis(13L, false)).isEqualTo(DispatchOutcome.TERMINAL_FAILED);

            assertThat(stopResults("session-missing")).isEqualTo(1.0);
            assertThat(stopResults("session-missing-redelivery")).isZero();
            assertThat(transitions(Status.FAILED, "ai-session-missing")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("success=false 여도 세션이 이미 종료 상태면(false) 전이 지표는 올리지 않는다")
        void stopAnalysis_sessionMissing_alreadyFinished_recordsNoTransition() {
            when(sessionService.markAsFailedIfStillInProgress(eq(9L), any(LocalDateTime.class))).thenReturn(false);
            stubStopResponse(false, "진행 중인 세션을 찾을 수 없습니다.");

            assertThat(service.stopAnalysis(9L, false)).isEqualTo(DispatchOutcome.TERMINAL_FAILED);

            // 사건 자체는 기록돼야 한다 — 세션 전이가 없었다고 유실이 없었던 건 아니다
            assertThat(stopResults("session-missing")).isEqualTo(1.0);
            assertThat(transitions(Status.FAILED, "ai-session-missing")).isZero();
        }

        @Test
        @DisplayName("서킷 OPEN 이면 송신을 버리지 않고 RETRY 로 돌려준다 — 행이 남아 나중에 전달된다")
        void stopAnalysis_circuitOpen_retriesInsteadOfDropping() {
            circuitBreakerRegistry.circuitBreaker("aiServer").transitionToOpenState();

            DispatchOutcome outcome = service.stopAnalysis(11L, false);

            // 이전 설계는 여기서 그냥 return 해 통보를 통째로 버렸다(E1 의 두 번째 유실 경로).
            // 하필 AI 가 죽어 통보가 가장 많이 쌓이는 구간이라 피해가 컸다.
            assertThat(outcome).isEqualTo(DispatchOutcome.RETRY);
            assertThat(stopResults("skipped-circuit-open")).isEqualTo(1.0);
            // 서킷이 열려 있으니 호출 자체가 나가지 않아야 한다
            verify(blockingStub, never()).stopAnalysis(any(StopRequest.class));
        }

        @Test
        @DisplayName("gRPC 오류면 RETRY — 나중에 될 수 있는 실패라 종결하지 않는다")
        void stopAnalysis_grpcError_retries() {
            when(blockingStub.stopAnalysis(any(StopRequest.class)))
                    .thenThrow(new StatusRuntimeException(io.grpc.Status.fromCode(Code.UNAVAILABLE)));

            DispatchOutcome outcome = service.stopAnalysis(12L, false);

            assertThat(outcome).isEqualTo(DispatchOutcome.RETRY);
            assertThat(stopResults("grpc-error")).isEqualTo(1.0);
            // 전송 실패는 서킷에 실패로 기록돼야 한다(업무 실패인 session-missing 과 다른 축)
            assertThat(circuitBreakerRegistry.circuitBreaker("aiServer").getMetrics()
                    .getNumberOfFailedCalls()).isEqualTo(1);
            // 세션을 걷어내지 않는다 — 나중에 전달되면 정상 완료될 수 있다
            assertThat(transitions(Status.FAILED, "ai-session-missing")).isZero();
        }

        private void stubStopResponse(boolean success, String message) {
            when(blockingStub.stopAnalysis(any(StopRequest.class)))
                    .thenReturn(StopResponse.newBuilder().setSuccess(success).setMessage(message).build());
        }

    }

    @Nested
    @DisplayName("SessionService")
    class SessionServiceConflicts {

        private SessionService service;
        private SessionService self;

        @BeforeEach
        void setUp() {
            service = new SessionService(mock(SessionRepository.class), mock(ExercisesRepository.class),
                    mock(MemberRepository.class), mock(com.fasterxml.jackson.databind.ObjectMapper.class),
                    mock(com.shadowfit.service.Report.DailyLogService.class),
                    mock(com.shadowfit.repository.exercise.PoseDataRepository.class),
                    mock(com.shadowfit.service.Report.SessionAnalysisCalculator.class),
                    mock(com.shadowfit.repository.report.ReportRepository.class),
                    metrics,
                    mock(com.shadowfit.repository.outbox.OutboxEventRepository.class));
            self = mock(SessionService.class);
            ReflectionTestUtils.setField(service, "self", self);
        }

        @Test
        @DisplayName("AI 콜백 낙관락 충돌 — 첫 시도만 실패하면 retry 1건")
        void completeSession_conflictThenSuccess_recordsRetry() {
            doThrow(new ObjectOptimisticLockingFailureException(Session.class, 1L))
                    .doNothing()
                    .when(self).applyComplete(any());

            service.completeSession(com.shadowfit.grpc.SessionCompleteRequest.newBuilder().setSessionId(1L).build());

            assertThat(conflicts("ai-callback", "retry")).isEqualTo(1.0);
            assertThat(conflicts("ai-callback", "exhausted")).isZero();
        }

        @Test
        @DisplayName("AI 콜백 낙관락 충돌 — 3회 모두 실패하면 retry 2건 + exhausted 1건 후 예외 전파")
        void completeSession_alwaysConflict_recordsExhausted() {
            doThrow(new ObjectOptimisticLockingFailureException(Session.class, 2L))
                    .when(self).applyComplete(any());

            assertThatThrownBy(() -> service.completeSession(
                    com.shadowfit.grpc.SessionCompleteRequest.newBuilder().setSessionId(2L).build()))
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);

            assertThat(conflicts("ai-callback", "retry")).isEqualTo(2.0);
            assertThat(conflicts("ai-callback", "exhausted")).isEqualTo(1.0);
            // 스케줄러 쪽 충돌과 섞이면 안 된다 — 어느 흐름이 졌는지가 정책 그 자체이므로
            assertThat(conflicts("timeout-scheduler", "yield")).isZero();
        }
    }

    @Nested
    @DisplayName("SessionMetrics.poseBatch")
    class PoseBatch {

        @Test
        @DisplayName("수신/저장 행수가 stage 태그로 나뉘어 분포에 기록된다")
        void recordsReceivedAndStoredSeparately() {
            metrics.poseBatch(25, 5);

            assertThat(registry.summary(POSE_FRAMES, "stage", "received").totalAmount()).isEqualTo(25.0);
            assertThat(registry.summary(POSE_FRAMES, "stage", "stored").totalAmount()).isEqualTo(5.0);
        }

        @Test
        @DisplayName("배치가 누적되면 합계와 건수가 함께 쌓여 평균 다운샘플 비율을 낼 수 있다")
        void accumulatesAcrossBatches() {
            metrics.poseBatch(25, 5);
            metrics.poseBatch(15, 3);

            assertThat(registry.summary(POSE_FRAMES, "stage", "received").count()).isEqualTo(2);
            assertThat(registry.summary(POSE_FRAMES, "stage", "received").totalAmount()).isEqualTo(40.0);
            assertThat(registry.summary(POSE_FRAMES, "stage", "stored").totalAmount()).isEqualTo(8.0);
            // 이 비율이 곧 실측 R값(≈5) — 운영 중 다운샘플이 의도대로 도는지 보는 창구
            assertThat(40.0 / 8.0).isEqualTo(5.0);
        }
    }
}
