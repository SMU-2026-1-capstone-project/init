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
import org.springframework.transaction.annotation.Transactional;
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
        // reattachSession 은 DB 작업을 self.loadReattachRequest 로 분리해 트랜잭션을 gRPC 앞에서
        // 닫는다(이슈 #76). 여기선 프록시가 없으니 자기 자신을 넣는다 — 트랜잭션 경계는 단위
        // 테스트의 관심사가 아니고, 리포지토리가 전부 mock 이라 동작은 같다.
        ReflectionTestUtils.setField(service, "self", service);
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

    /**
     * 시간 축도 rep 축처럼 이어붙여 보내는가 (이슈 #156).
     *
     * <p>AI 는 프레임 시각을 «첫 프레임 도착부터의 경과» 로 만든다. 재부착으로 AI 상태를 새로
     * 만들면 그 기준이 재부착 시점이 되어 이후 프레임의 {@code timestamp_sec} 이 0 부터 다시
     * 시작하고, 리포트의 «최악 구간 시각» 이 세션 앞부분과 겹치는 값으로 표시된다. 그래서
     * Spring 이 이미 흐른 시간을 실어 보낸다 — {@code initial_rep_count} 가 rep 축에서 하는 일과 같다.
     *
     * <p><b>값의 출처가 이 검사의 핵심이다.</b> 초판은 {@code session.start_time} 으로부터의 경과를
     * 보냈는데, 그 원점에는 StartAnalysis 와 첫 프레임 사이의 «자세 잡는 시간» 이 들어 있다. AI 의
     * 원점에는 그게 빠져 있으므로 두 원점을 섞으면 재부착 이후 시각이 준비 시간만큼 통째로 앞선다.
     * 지금은 {@code MAX(pose_data.timestamp_sec)} 를 되읽는다 — 저장된 값 자체가 AI 원점으로 만들어진
     * 것이라 원점이 하나로 유지되고, rep 축과 <b>같은 데이터원</b>이 된다.
     *
     * <p>이 검사가 없으면 «rep 은 이어지는데 시각만 리셋되는» 상태가 조용히 성립한다.
     */
    @Test
    @DisplayName("★ elapsed_sec 는 MAX(timestamp_sec) 다 — 세션 시작 기준이면 준비 시간이 새어든다 (#156)")
    void 경과시간이_요청에_실린다() {
        // 3분 전에 시작했지만, 그중 20초는 자세를 잡느라 프레임이 없었다.
        // 저장된 마지막 프레임 시각은 그 준비 시간이 빠진 값이다.
        double lastRecorded = 160.0;
        when(sessionService.findReattachableSession(SESSION_ID, MEMBER_ID))
                .thenReturn(sessionStartedMinutesAgo(3));
        when(poseDataRepository.findMaxTimestampSecBySessionId(SESSION_ID)).thenReturn(lastRecorded);
        when(blockingStub.reattachAnalysis(any(ReattachRequest.class)))
                .thenReturn(ReattachResponse.newBuilder()
                        .setSuccess(true).setSessionId(SESSION_ID).setRepCount(3).build());

        service.reattachSession(SESSION_ID, MEMBER_ID);

        org.mockito.ArgumentCaptor<ReattachRequest> captor =
                org.mockito.ArgumentCaptor.forClass(ReattachRequest.class);
        verify(blockingStub).reattachAnalysis(captor.capture());

        assertThat(captor.getValue().getElapsedSec())
                .as("elapsed_sec 가 저장된 마지막 프레임 시각과 다르면 AI 와 원점이 갈린다 "
                        + "— session.start_time 기준으로 되돌아가면 여기서 180 근처가 나온다")
                .isEqualTo(lastRecorded);
    }

    /**
     * 프레임이 한 건도 없는 세션(시작 직후 재부착)에서도 요청이 성립하는가.
     *
     * <p>{@code COALESCE} 가 0.0 을 주므로 AI 의 원점이 그대로 «재부착 후 첫 프레임» 이 된다 —
     * 아직 아무것도 기록되지 않았으니 그게 맞는 값이다.
     */
    @Test
    @DisplayName("프레임이 없는 세션은 elapsed_sec 가 0 이다 (#156)")
    void 프레임없는세션은_경과가0() {
        when(sessionService.findReattachableSession(SESSION_ID, MEMBER_ID))
                .thenReturn(sessionStartedMinutesAgo(3));
        when(poseDataRepository.findMaxTimestampSecBySessionId(SESSION_ID)).thenReturn(0.0);
        when(blockingStub.reattachAnalysis(any(ReattachRequest.class)))
                .thenReturn(ReattachResponse.newBuilder()
                        .setSuccess(true).setSessionId(SESSION_ID).setRepCount(0).build());

        service.reattachSession(SESSION_ID, MEMBER_ID);

        org.mockito.ArgumentCaptor<ReattachRequest> captor =
                org.mockito.ArgumentCaptor.forClass(ReattachRequest.class);
        verify(blockingStub).reattachAnalysis(captor.capture());

        assertThat(captor.getValue().getElapsedSec()).isZero();
    }

    private Session sessionStartedMinutesAgo(int minutes) {
        Member member = Member.builder().id(MEMBER_ID).selectedPersona(SelectedPersona.BEGINNER).build();
        Exercise exercise = Exercise.builder().id(1L).expectedDurationMinutes(15).build();
        return Session.builder()
                .id(SESSION_ID).member(member).exercise(exercise)
                .startTime(LocalDateTime.now().minusMinutes(minutes)).build();
    }

    /**
     * 이슈 #76 회귀 방지.
     *
     * <p>여기서 지키는 것은 "gRPC 를 트랜잭션 밖에서 한다"는 <b>경계 자체</b>다. 트랜잭션 안에서
     * 부르면 커넥션을 쥔 채 최대 5초(gRPC deadline)를 기다리게 되고, AI 가 느려지는 순간 풀(15)이
     * 마르면서 재부착과 무관한 요청까지 막힌다. 재부착은 드물지만 AI 재시작 직후에 <b>몰려서</b>
     * 들어오므로 정확히 그때 터진다.
     *
     * <p>실제 커넥션 점유는 단위 테스트로 볼 수 없어 <b>애노테이션 배치</b>로 대신 고정한다.
     * 누군가 두 메서드를 "정리"하며 {@code reattachSession} 에 {@code @Transactional} 을 도로
     * 붙이면 여기서 깨진다.
     */
    @Test
    @DisplayName("gRPC 는 트랜잭션 밖에서 호출한다 — 커넥션을 쥔 채 AI 를 기다리지 않는다 (#76)")
    void gRPC는_트랜잭션_밖에서() throws NoSuchMethodException {
        var reattach = ExerciseAnalysisService.class.getMethod("reattachSession", Long.class, Long.class);
        var load = ExerciseAnalysisService.class.getMethod("loadReattachRequest", Long.class, Long.class);

        assertThat(reattach.getAnnotation(Transactional.class))
                .as("reattachSession 에 @Transactional 이 붙으면 gRPC 왕복 내내 커넥션을 점유한다 (#76)")
                .isNull();
        assertThat(load.getAnnotation(Transactional.class))
                .as("DB 작업은 loadReattachRequest 안에서 끝나야 한다")
                .isNotNull();
        assertThat(load.getAnnotation(Transactional.class).readOnly())
                .as("재부착 준비는 읽기 전용이다")
                .isTrue();
    }
}
