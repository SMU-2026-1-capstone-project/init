package com.shadowfit.service.Exercise;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.observability.SessionMetrics;
import com.shadowfit.grpc.ExerciseServiceGrpc;
import com.shadowfit.grpc.ReattachRequest;
import com.shadowfit.grpc.ReattachResponse;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.outbox.DispatchOutcome;
import com.shadowfit.model.outbox.OutboxEvent;
import com.shadowfit.model.outbox.OutboxEventType;
import com.shadowfit.repository.exercise.ExerciseReferenceRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.outbox.OutboxEventRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 자동 재부착(#581, ai-channel-pool-hardening.md §3-1 ㄴ) 단위 테스트.
 *
 * <p>{@code ReattachFailurePathTest} 가 <b>사용자 요청</b> 재부착({@code reattachSession})의 실패
 * 경로를 고정한다면, 여기는 그 시스템 트리거 짝인 {@code reattachFromOutbox}(발행기가 부른다)와
 * {@code enqueueReattachForWorker}(서킷브레이커 OPEN 리스너가 부른다)를 고정한다. 이 둘은 구현될
 * 때 같이 들어왔지만 테스트 없이 워킹트리에만 있었다 — 신뢰성 기능이 회귀 감시 없이 남는 것을
 * 막으려고 추가한다.
 */
@DisplayName("자동 재부착(#581) 테스트")
class AutoReattachTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private ExercisesRepository exercisesRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private SessionService sessionService;
    @Mock private ExerciseReferenceRepository referenceRepository;
    @Mock private PoseDataRepository poseDataRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SessionMetrics metrics = new SessionMetrics(registry);
    private CircuitBreakerRegistry circuitBreakerRegistry;
    private ExerciseServiceGrpc.ExerciseServiceBlockingStub blockingStub;
    private ExerciseAnalysisService service;
    private static final Long SESSION_ID = 42L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
        blockingStub = mock(ExerciseServiceGrpc.ExerciseServiceBlockingStub.class);
        when(blockingStub.withInterceptors(any(io.grpc.ClientInterceptor[].class))).thenReturn(blockingStub);
        when(blockingStub.withDeadlineAfter(anyLong(), any())).thenReturn(blockingStub);
        service = new ExerciseAnalysisService(sessionRepository, exercisesRepository,
                memberRepository, sessionService, referenceRepository, poseDataRepository,
                circuitBreakerRegistry, metrics, outboxEventRepository);
        ReflectionTestUtils.setField(service, "internalToken", "test-token");
        ReflectionTestUtils.setField(service, "aiChannelPoolSize", 1);
        ReflectionTestUtils.setField(service, "aiBlockingStubPool", List.of(blockingStub));
        // reattachFromOutbox → self.loadReattachRequestById 를 부른다. 프록시가 없는 단위
        // 테스트라 ReattachFailurePathTest 와 같은 이유로 자기 자신을 넣는다.
        ReflectionTestUtils.setField(service, "self", service);
    }

    private Session session() {
        Member member = Member.builder().id(7L).selectedPersona(SelectedPersona.BEGINNER).build();
        Exercise exercise = Exercise.builder().id(1L).expectedDurationMinutes(15).build();
        return Session.builder()
                .id(SESSION_ID).member(member).exercise(exercise)
                .startTime(LocalDateTime.now()).build();
    }

    /** 재부착 준비(DB 조회) 단계가 성공하도록 공통 목을 세운다 — gRPC 응답만 각 테스트가 정한다. */
    private void stubReattachRequestAssembly() {
        when(sessionService.findReattachableSessionById(SESSION_ID)).thenReturn(session());
        when(poseDataRepository.findMaxRepNumberBySessionId(eq(SESSION_ID), any())).thenReturn(3);
        when(poseDataRepository.findMaxTimestampSecBySessionId(eq(SESSION_ID), any())).thenReturn(10.0);
        when(referenceRepository.findByExerciseId(anyLong())).thenReturn(List.of());
    }

    private double counter(String outcome) {
        return registry.get("shadowfit.ai.reattach.result").tag("outcome", outcome).counter().count();
    }

    @Nested
    @DisplayName("reattachFromOutbox — 발행기가 부르는 실행부")
    class ReattachFromOutbox {

        @Test
        @DisplayName("이어붙일 대상이 아니면(SESSION_NOT_FOUND 등) TERMINAL_FAILED, gRPC 는 안 부른다")
        void 대상아님_TERMINAL_FAILED() {
            when(sessionService.findReattachableSessionById(SESSION_ID))
                    .thenThrow(new BusinessException(ErrorCode.SESSION_NOT_FOUND));

            DispatchOutcome outcome = service.reattachFromOutbox(SESSION_ID);

            assertThat(outcome).isEqualTo(DispatchOutcome.TERMINAL_FAILED);
            assertThat(counter("not-reattachable")).isEqualTo(1.0);
            verify(blockingStub, never()).reattachAnalysis(any());
        }

        @Test
        @DisplayName("서킷 OPEN → gRPC 를 부르지도 않고 RETRY")
        void 서킷OPEN_RETRY() {
            stubReattachRequestAssembly();
            circuitBreakerRegistry.circuitBreaker("aiServer-0").transitionToOpenState();

            DispatchOutcome outcome = service.reattachFromOutbox(SESSION_ID);

            assertThat(outcome).isEqualTo(DispatchOutcome.RETRY);
            assertThat(counter("circuit-open")).isEqualTo(1.0);
            verify(blockingStub, never()).reattachAnalysis(any());
        }

        @Test
        @DisplayName("gRPC 통신 실패 → RETRY, 서킷 실패로 센다(컨테이너가 계속 안 살아나면 OPEN 유지)")
        void gRPC실패_RETRY() {
            stubReattachRequestAssembly();
            when(blockingStub.reattachAnalysis(any(ReattachRequest.class)))
                    .thenThrow(new StatusRuntimeException(io.grpc.Status.UNAVAILABLE));
            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("aiServer-0");
            long before = cb.getMetrics().getNumberOfFailedCalls();

            DispatchOutcome outcome = service.reattachFromOutbox(SESSION_ID);

            assertThat(outcome).isEqualTo(DispatchOutcome.RETRY);
            assertThat(counter("grpc-error")).isEqualTo(1.0);
            assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(before + 1);
        }

        @Test
        @DisplayName("AI 가 success=false 로 거절 → TERMINAL_FAILED (재시도해도 같은 결과)")
        void AI거절_TERMINAL_FAILED() {
            stubReattachRequestAssembly();
            when(blockingStub.reattachAnalysis(any(ReattachRequest.class)))
                    .thenReturn(ReattachResponse.newBuilder()
                            .setSuccess(false).setSessionId(SESSION_ID)
                            .setMessage("기준 좌표를 복원하지 못했습니다.").build());

            DispatchOutcome outcome = service.reattachFromOutbox(SESSION_ID);

            assertThat(outcome).isEqualTo(DispatchOutcome.TERMINAL_FAILED);
            assertThat(counter("ai-rejected")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("성공(신규) → SENT, outcome=ok")
        void 성공_SENT_ok() {
            stubReattachRequestAssembly();
            when(blockingStub.reattachAnalysis(any(ReattachRequest.class)))
                    .thenReturn(ReattachResponse.newBuilder()
                            .setSuccess(true).setSessionId(SESSION_ID).setRepCount(3)
                            .setAlreadyActive(false).build());

            DispatchOutcome outcome = service.reattachFromOutbox(SESSION_ID);

            assertThat(outcome).isEqualTo(DispatchOutcome.SENT);
            assertThat(counter("ok")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("성공(이미 활성) → SENT, outcome=already-active — 서킷 플래핑으로 중복 큐잉된 경우")
        void 성공_SENT_alreadyActive() {
            stubReattachRequestAssembly();
            when(blockingStub.reattachAnalysis(any(ReattachRequest.class)))
                    .thenReturn(ReattachResponse.newBuilder()
                            .setSuccess(true).setSessionId(SESSION_ID).setRepCount(3)
                            .setAlreadyActive(true).build());

            DispatchOutcome outcome = service.reattachFromOutbox(SESSION_ID);

            assertThat(outcome).isEqualTo(DispatchOutcome.SENT);
            assertThat(counter("already-active")).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("enqueueReattachForWorker — 서킷 OPEN 리스너가 부르는 큐잉부")
    class EnqueueReattachForWorker {

        @BeforeEach
        void poolOf3() {
            // floorMod 필터링을 보려면 워커가 둘 이상이어야 한다.
            ReflectionTestUtils.setField(service, "aiChannelPoolSize", 3);
        }

        @Test
        @DisplayName("이 워커로 라우팅되던(floorMod 일치) IN_PROGRESS 세션만 큐잉한다")
        void 워커에_해당하는_세션만_큐잉() {
            // floorMod(1,3)=1, (2,3)=2, (4,3)=1, (7,3)=1 → workerIndex=1 대상은 1·4·7
            when(sessionRepository.findIdsByStatus(Status.IN_PROGRESS))
                    .thenReturn(List.of(1L, 2L, 4L, 7L));
            when(outboxEventRepository.existsByAggregateIdAndEventTypeAndStatusIn(
                    any(), eq(OutboxEventType.REATTACH_ANALYSIS), any()))
                    .thenReturn(false);

            service.enqueueReattachForWorker(1);

            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository, times(3)).save(captor.capture());
            assertThat(captor.getAllValues().stream().map(OutboxEvent::getAggregateId))
                    .containsExactlyInAnyOrder(1L, 4L, 7L);
            assertThat(captor.getAllValues())
                    .allMatch(e -> e.getEventType() == OutboxEventType.REATTACH_ANALYSIS);
        }

        @Test
        @DisplayName("이미 PENDING/PROCESSING 인 재부착 이벤트가 있으면 건너뛴다 — 서킷 플래핑 중복 방지")
        void 이미_대기중이면_건너뛴다() {
            when(sessionRepository.findIdsByStatus(Status.IN_PROGRESS)).thenReturn(List.of(1L, 4L));
            when(outboxEventRepository.existsByAggregateIdAndEventTypeAndStatusIn(
                    eq(1L), eq(OutboxEventType.REATTACH_ANALYSIS), any()))
                    .thenReturn(true);
            when(outboxEventRepository.existsByAggregateIdAndEventTypeAndStatusIn(
                    eq(4L), eq(OutboxEventType.REATTACH_ANALYSIS), any()))
                    .thenReturn(false);

            service.enqueueReattachForWorker(1);

            ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxEventRepository, times(1)).save(captor.capture());
            assertThat(captor.getValue().getAggregateId()).isEqualTo(4L);
        }

        @Test
        @DisplayName("이 워커로 라우팅되는 IN_PROGRESS 세션이 없으면 저장을 호출하지 않는다")
        void 대상없으면_저장안함() {
            // floorMod(2,3)=2, floorMod(5,3)=2 — 둘 다 workerIndex=1 이 아니다.
            when(sessionRepository.findIdsByStatus(Status.IN_PROGRESS)).thenReturn(List.of(2L, 5L));

            service.enqueueReattachForWorker(1);

            verify(outboxEventRepository, never()).save(any());
        }
    }
}
