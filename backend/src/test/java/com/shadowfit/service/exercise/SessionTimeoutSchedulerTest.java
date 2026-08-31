package com.shadowfit.service.exercise;

import com.shadowfit.global.observability.SessionMetrics;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.repository.exercise.SessionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SessionTimeoutScheduler 단위 테스트
 *
 * 네트워크 타임아웃 처리 로직 + FastAPI 완료 콜백과의 동시성(낙관적 락) 시나리오를 검증합니다.
 *
 * <p>🔄 2026-08-27(#207): 스케줄러가 {@code findByStatus}(엔티티 전체)가 아니라
 * {@code findTimeoutCandidatesByStatus}(프로젝션)를 쓰도록 바뀌어, 이 테스트도 {@code Session}
 * 엔티티 대신 {@link SessionRepository.TimeoutCandidate} 를 직접 만들어 목에 물린다.
 */
@DisplayName("세션 타임아웃 스케줄러 테스트")
class SessionTimeoutSchedulerTest {

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private SessionService sessionService;

    private SessionTimeoutScheduler scheduler;
    // 목이 아니라 진짜 레지스트리 — 지표가 실제로 올라가는지 값으로 검증하기 위해.
    private SimpleMeterRegistry meterRegistry;

    private static final int SQUAT_EXPECTED_MINUTES = 15;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new SessionTimeoutScheduler(sessionRepository, sessionService,
                new SessionMetrics(meterRegistry), 10, 30);
    }

    /** {@link SessionRepository.TimeoutCandidate} 목 — id·startTime·expectedDurationMinutes 만 지정하면 된다. */
    private static SessionRepository.TimeoutCandidate candidate(Long id, LocalDateTime startTime,
                                                                 int expectedDurationMinutes) {
        SessionRepository.TimeoutCandidate c = mock(SessionRepository.TimeoutCandidate.class);
        when(c.getId()).thenReturn(id);
        when(c.getStartTime()).thenReturn(startTime);
        when(c.getLastActiveAt()).thenReturn(null); // 이 테스트들은 전부 "활동 없음 폴백 식" 경로만 다룬다
        when(c.getMemberId()).thenReturn(1L);
        when(c.getExerciseName()).thenReturn("스쿼트");
        when(c.getExpectedDurationMinutes()).thenReturn(expectedDurationMinutes);
        return c;
    }

    @Test
    @DisplayName("타임아웃된 세션은 SessionService.markAsFailed 호출로 FAILED 처리되어야 함")
    void testTimeoutSessionMarkedFailed() {
        // mock()/when() 을 List.of(...) 인자 자리(다른 when().thenReturn() 호출 도중)에서 하면
        // Mockito 의 "진행 중 스터빙" 상태와 충돌해 UnfinishedStubbingException 이 난다 — 그래서
        // candidate() 호출은 항상 별도 문장으로 먼저 끝낸다.
        SessionRepository.TimeoutCandidate c1 = candidate(1L, LocalDateTime.now().minusMinutes(50), SQUAT_EXPECTED_MINUTES);
        when(sessionRepository.findTimeoutCandidatesByStatus(Status.IN_PROGRESS)).thenReturn(List.of(c1));
        when(sessionService.markAsFailedIfStillInProgress(eq(1L), any(LocalDateTime.class), eq(true)))
                .thenReturn(true);

        scheduler.checkAndTimeoutSessions();

        verify(sessionService, times(1))
                .markAsFailedIfStillInProgress(eq(1L), any(LocalDateTime.class), eq(true));
    }

    @Test
    @DisplayName("타임아웃되지 않은 세션은 markAsFailed 호출되지 않아야 함")
    void testNonTimeoutSessionNotCalled() {
        SessionRepository.TimeoutCandidate c2 = candidate(2L, LocalDateTime.now().minusMinutes(5), SQUAT_EXPECTED_MINUTES);
        when(sessionRepository.findTimeoutCandidatesByStatus(Status.IN_PROGRESS)).thenReturn(List.of(c2));

        scheduler.checkAndTimeoutSessions();

        // 오버로드 하나만 never() 로 보면 2-인자 쪽으로 회귀해도 통과한다 — 이 경로는 애초에
        // SessionService 를 건드리지 않아야 하므로 상호작용 자체를 없음으로 고정한다.
        verifyNoInteractions(sessionService);
    }

    @Test
    @DisplayName("IN_PROGRESS 세션이 없으면 SessionService를 호출하지 않아야 함")
    void testNoInProgressSessionsDoesNothing() {
        when(sessionRepository.findTimeoutCandidatesByStatus(Status.IN_PROGRESS))
                .thenReturn(new ArrayList<>());

        scheduler.checkAndTimeoutSessions();

        // 오버로드 하나만 never() 로 보면 2-인자 쪽으로 회귀해도 통과한다 — 이 경로는 애초에
        // SessionService 를 건드리지 않아야 하므로 상호작용 자체를 없음으로 고정한다.
        verifyNoInteractions(sessionService);
    }

    @Test
    @DisplayName("운동별 예상시간에 따라 타임아웃을 다르게 적용해야 함")
    void testTimeoutBasedOnExerciseDuration() {
        // 예상시간 30분 + 버퍼 30분 = 60분 임계, 50분 경과 → 타임아웃 아님
        SessionRepository.TimeoutCandidate c3 = candidate(3L, LocalDateTime.now().minusMinutes(50), 30);
        when(sessionRepository.findTimeoutCandidatesByStatus(Status.IN_PROGRESS)).thenReturn(List.of(c3));

        scheduler.checkAndTimeoutSessions();

        // 오버로드 하나만 never() 로 보면 2-인자 쪽으로 회귀해도 통과한다 — 이 경로는 애초에
        // SessionService 를 건드리지 않아야 하므로 상호작용 자체를 없음으로 고정한다.
        verifyNoInteractions(sessionService);
    }

    @Test
    @DisplayName("예상시간 10분인 운동은 41분 경과 시 타임아웃되어야 함")
    void testQuickExerciseTimeoutAfter40Minutes() {
        SessionRepository.TimeoutCandidate c4 = candidate(4L, LocalDateTime.now().minusMinutes(41), 10);
        when(sessionRepository.findTimeoutCandidatesByStatus(Status.IN_PROGRESS)).thenReturn(List.of(c4));
        when(sessionService.markAsFailedIfStillInProgress(eq(4L), any(LocalDateTime.class), eq(true)))
                .thenReturn(true);

        scheduler.checkAndTimeoutSessions();

        verify(sessionService, times(1))
                .markAsFailedIfStillInProgress(eq(4L), any(LocalDateTime.class), eq(true));
    }

    @Test
    @DisplayName("[동시성] FastAPI 완료와 충돌 시 OptimisticLockException을 양보하고 다른 세션 처리는 계속해야 함")
    void testYieldOnOptimisticLockConflict() {
        // 두 세션 모두 타임아웃 대상이지만 첫 번째는 충돌, 두 번째는 정상
        SessionRepository.TimeoutCandidate c10 = candidate(10L, LocalDateTime.now().minusMinutes(50), SQUAT_EXPECTED_MINUTES);
        SessionRepository.TimeoutCandidate c11 = candidate(11L, LocalDateTime.now().minusMinutes(50), SQUAT_EXPECTED_MINUTES);
        when(sessionRepository.findTimeoutCandidatesByStatus(Status.IN_PROGRESS)).thenReturn(List.of(c10, c11));

        when(sessionService.markAsFailedIfStillInProgress(eq(10L), any(LocalDateTime.class), eq(true)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Session.class, 10L));
        when(sessionService.markAsFailedIfStillInProgress(eq(11L), any(LocalDateTime.class), eq(true)))
                .thenReturn(true);

        // 충돌이 발생해도 예외가 외부로 전파되지 않아야 함
        assertDoesNotThrow(() -> scheduler.checkAndTimeoutSessions());

        // 두 세션 모두 호출되어야 함 (한 세션의 실패가 다른 세션 처리를 막으면 안 됨)
        verify(sessionService, times(1))
                .markAsFailedIfStillInProgress(eq(10L), any(LocalDateTime.class), eq(true));
        verify(sessionService, times(1))
                .markAsFailedIfStillInProgress(eq(11L), any(LocalDateTime.class), eq(true));

        // 양보한 충돌이 지표로 남아야 함 — 이 경쟁의 실제 발생 빈도를 운영 중 볼 수 있는 유일한 창구
        assertEquals(1.0, meterRegistry.counter("shadowfit.session.optimistic.lock.conflicts",
                "source", "timeout-scheduler", "outcome", "yield").count());
        // 성공적으로 FAILED 전환된 쪽은 상태 전이 지표로
        assertEquals(1.0, meterRegistry.counter("shadowfit.session.transitions",
                "status", "FAILED", "source", "timeout-scheduler").count());
    }

    @Test
    @DisplayName("[동시성] markAsFailed가 false를 리턴(이미 COMPLETED)하면 정상 진행해야 함")
    void testYieldWhenAlreadyCompleted() {
        SessionRepository.TimeoutCandidate c1 = candidate(1L, LocalDateTime.now().minusMinutes(50), SQUAT_EXPECTED_MINUTES);
        when(sessionRepository.findTimeoutCandidatesByStatus(Status.IN_PROGRESS)).thenReturn(List.of(c1));
        // FastAPI가 한 발 빨라 IN_PROGRESS가 아니게 된 경우
        when(sessionService.markAsFailedIfStillInProgress(eq(1L), any(LocalDateTime.class), eq(true)))
                .thenReturn(false);

        assertDoesNotThrow(() -> scheduler.checkAndTimeoutSessions());

        verify(sessionService, times(1))
                .markAsFailedIfStillInProgress(eq(1L), any(LocalDateTime.class), eq(true));
    }
}
