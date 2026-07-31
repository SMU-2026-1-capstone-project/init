package com.shadowfit.service.Exercise;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.observability.SessionMetrics;
import com.shadowfit.grpc.ExerciseServiceGrpc;
import com.shadowfit.grpc.ReattachRequest;
import com.shadowfit.grpc.ReattachResponse;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.repository.exercise.ExerciseReferenceRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
/**
 * 재부착 <b>실패 경로</b> 단위 테스트 (이슈 #59 2단계, CodeRabbit 지적으로 추가).
 *
 * <p>{@code SessionReattachTest} 는 허용 판정과 rep 복원을 보고, 통합 검증(§7-3)은 Docker 로
 * 실제 gRPC 를 몰았다. 그 사이에 <b>단위 테스트가 비어 있던 구간</b>이 여기다 — AI 가 죽었을 때
 * 무슨 일이 일어나는가.
 *
 * <p>핵심 회귀 대상은 <b>"재부착 실패로 세션을 FAILED 로 만들지 않는다"</b>이다. 시작 경로
 * ({@code sendAnalysisRequestToFastApi})는 정반대로 즉시 FAILED 처리하므로, 나중에 누군가
 * 두 경로를 "일관되게" 맞추려다 이 차이를 지울 수 있다. 그러면 되살릴 수 있었던 rep 을
 * 재부착 기능이 스스로 없애게 된다.
 */
@DisplayName("재부착 실패 경로 테스트")
class ReattachFailurePathTest {
    @Mock private WebClient webClient;
    @Mock private SessionRepository sessionRepository;
    @Mock private ExercisesRepository exercisesRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private SessionService sessionService;
    @Mock private ExerciseReferenceRepository referenceRepository;
    @Mock private PoseDataRepository poseDataRepository;
    private final SessionMetrics metrics = new SessionMetrics(new SimpleMeterRegistry());
    private CircuitBreakerRegistry circuitBreakerRegistry;
    private ExerciseServiceGrpc.ExerciseServiceBlockingStub blockingStub;
    private ExerciseAnalysisService service;
    private static final Long SESSION_ID = 42L;
    private static final Long MEMBER_ID = 7L;
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
        blockingStub = mock(ExerciseServiceGrpc.ExerciseServiceBlockingStub.class);
        when(blockingStub.withInterceptors(any())).thenReturn(blockingStub);
        when(blockingStub.withDeadlineAfter(anyLong(), any())).thenReturn(blockingStub);
        service = new ExerciseAnalysisService(webClient, sessionRepository, exercisesRepository,
                memberRepository, sessionService, referenceRepository, poseDataRepository,
                circuitBreakerRegistry, metrics);
        ReflectionTestUtils.setField(service, "internalToken", "test-token");
        ReflectionTestUtils.setField(service, "exerciseBlockingStub", blockingStub);
        when(sessionService.findReattachableSession(SESSION_ID, MEMBER_ID)).thenReturn(session());
        when(poseDataRepository.findMaxRepNumberBySessionId(SESSION_ID)).thenReturn(3);
        when(referenceRepository.findByExerciseId(anyLong())).thenReturn(List.of());
    }
    private Session session() {
        Member member = Member.builder().id(MEMBER_ID).selectedPersona(SelectedPersona.BEGINNER).build();
        Exercise exercise = Exercise.builder().id(1L).expectedDurationMinutes(15).build();
        return Session.builder()
                .id(SESSION_ID).member(member).exercise(exercise)
                .startTime(LocalDateTime.now()).build();
    }
    /**
     * 서킷을 OPEN 으로 만들어 tryAcquirePermission 이 거부하게 한다.
     *
     * <p>레지스트리에서 꺼낸 <b>바로 그 인스턴스</b>를 전이시켜야 한다 — 새로 만들어 replace 하면
     * 프로덕션 코드의 {@code circuitBreaker("aiServer")} 가 여전히 원래 인스턴스를 돌려받아
     * 서킷이 닫힌 채로 남는다(이 테스트를 처음 짤 때 실제로 그렇게 새어 NPE 로 드러났다).
     */
    private void openCircuit() {
        circuitBreakerRegistry.circuitBreaker("aiServer").transitionToOpenState();
    }
    @Test
    @DisplayName("gRPC 통신 장애 → 503, 세션은 FAILED 로 바뀌지 않는다")
    void gRPC장애_503_세션보존() {
        when(blockingStub.reattachAnalysis(any(ReattachRequest.class)))
                .thenThrow(new StatusRuntimeException(io.grpc.Status.UNAVAILABLE));
        assertThatThrownBy(() -> service.reattachSession(SESSION_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SESSION_REATTACH_UNAVAILABLE);
        // 시작 경로와 반대다 — 여기서 FAILED 로 만들면 pose_data 에 살아있는 rep 을 버리게 된다.
        verify(sessionService, never()).markAsFailedIfStillInProgress(anyLong(), any());
    }
    @Test
    @DisplayName("서킷 OPEN → gRPC 를 부르지도 않고 503, 세션 보존")
    void 서킷OPEN_503_세션보존() {
        openCircuit();
        assertThatThrownBy(() -> service.reattachSession(SESSION_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SESSION_REATTACH_UNAVAILABLE);
        verify(blockingStub, never()).reattachAnalysis(any(ReattachRequest.class));
        verify(sessionService, never()).markAsFailedIfStillInProgress(anyLong(), any());
    }
    @Test
    @DisplayName("AI 가 success=false 로 거절 → 503, 세션 보존")
    void AI거절_503_세션보존() {
        // 통신은 성공했고 AI 가 업무적으로 거절한 경우(기준 좌표 복원 실패 등).
        when(blockingStub.reattachAnalysis(any(ReattachRequest.class)))
                .thenReturn(ReattachResponse.newBuilder()
                        .setSuccess(false).setSessionId(SESSION_ID)
                        .setMessage("기준 좌표를 복원하지 못했습니다.").build());
        assertThatThrownBy(() -> service.reattachSession(SESSION_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SESSION_REATTACH_UNAVAILABLE);
        verify(sessionService, never()).markAsFailedIfStillInProgress(anyLong(), any());
    }
    @Test
    @DisplayName("서킷은 통신 실패만 실패로 센다 — AI 의 업무적 거절은 서킷을 열지 않는다")
    void AI거절은_서킷실패로_치지않는다() {
        when(blockingStub.reattachAnalysis(any(ReattachRequest.class)))
                .thenReturn(ReattachResponse.newBuilder().setSuccess(false).build());
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("aiServer");
        long before = cb.getMetrics().getNumberOfFailedCalls();
        assertThatThrownBy(() -> service.reattachSession(SESSION_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class);
        // AI 는 살아있다. 이걸 서킷 실패로 세면 멀쩡한 AI 를 향한 요청까지 차단하게 된다.
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(before);
    }
    @Test
    @DisplayName("DB 의 MAX(rep_number) 가 initial_rep_count 로 실려 나간다")
    void rep수가_요청에_실린다() {
        when(blockingStub.reattachAnalysis(any(ReattachRequest.class)))
                .thenReturn(ReattachResponse.newBuilder()
                        .setSuccess(true).setSessionId(SESSION_ID).setRepCount(3).build());
        var result = service.reattachSession(SESSION_ID, MEMBER_ID);
        org.mockito.ArgumentCaptor<ReattachRequest> captor =
                org.mockito.ArgumentCaptor.forClass(ReattachRequest.class);
        verify(blockingStub).reattachAnalysis(captor.capture());
        assertThat(captor.getValue().getInitialRepCount()).isEqualTo(3);
        assertThat(captor.getValue().getSessionId()).isEqualTo(SESSION_ID);
        assertThat(result.getRestoredRepCount()).isEqualTo(3);
        assertThat(result.getAnalyzerStateReset()).isTrue();
    }
}
