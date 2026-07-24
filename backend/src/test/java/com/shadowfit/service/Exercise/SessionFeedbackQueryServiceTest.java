package com.shadowfit.service.Exercise;

import com.shadowfit.dto.exercises.feedback.SessionFeedbackEventDto;
import com.shadowfit.dto.exercises.feedback.SessionFeedbackSummaryDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
import com.shadowfit.model.exercise.FeedbackType;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.SessionFeedbackLog;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.SessionFeedbackLogRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SessionFeedbackQueryService 테스트")
class SessionFeedbackQueryServiceTest {

    @Mock private SessionFeedbackLogRepository feedbackLogRepository;
    @Mock private SessionRepository sessionRepository;
    private SessionFeedbackQueryService service;

    private static final Long OWNER_ID = 1L;
    private static final Long SESSION_ID = 10L;
    private Session session;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new SessionFeedbackQueryService(feedbackLogRepository, sessionRepository);

        Member owner = Member.builder().id(OWNER_ID).email("t@t.com").username("u").password("p").role(UserRole.USER).build();
        Exercise exercise = Exercise.builder().id(1L).name("스쿼트").category(ExerciseCategory.LOWER)
                .expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .build();
        session = Session.builder().id(SESSION_ID).member(owner).exercise(exercise)
                .startTime(LocalDateTime.now()).status(Status.COMPLETED).build();
    }

    @Test
    @DisplayName("getEvents — 본인 세션이면 발생시각 순 이벤트 목록 반환")
    void getEvents_success() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        SessionFeedbackLog log = SessionFeedbackLog.builder()
                .id(1L).session(session).feedbackType(FeedbackType.KNEE_OUT)
                .occurredAt(LocalDateTime.now()).build();
        when(feedbackLogRepository.findBySessionIdOrderByOccurredAtAsc(SESSION_ID)).thenReturn(List.of(log));

        List<SessionFeedbackEventDto> result = service.getEvents(SESSION_ID, OWNER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).feedbackType()).isEqualTo(FeedbackType.KNEE_OUT);
    }

    @Test
    @DisplayName("getEvents — 세션이 없으면 SESSION_NOT_FOUND")
    void getEvents_sessionNotFound_throws() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getEvents(SESSION_ID, OWNER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    @DisplayName("getEvents — 본인 세션이 아니면 ACCESS_DENIED")
    void getEvents_notOwner_throws() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.getEvents(SESSION_ID, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCESS_DENIED);
    }

    @Test
    @DisplayName("getSummary — 타입별 카운트·통계를 총합과 함께 반환")
    void getSummary_success() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        SessionFeedbackLogRepository.TypeStats stat = mock(SessionFeedbackLogRepository.TypeStats.class);
        when(stat.getFeedbackType()).thenReturn(FeedbackType.KNEE_OUT);
        when(stat.getCount()).thenReturn(3L);
        when(stat.getAvgSyncRate()).thenReturn(new BigDecimal("55.5"));
        when(stat.getMinSyncRate()).thenReturn(new BigDecimal("40.0"));
        when(stat.getMaxSyncRate()).thenReturn(new BigDecimal("70.0"));
        when(feedbackLogRepository.aggregateBySession(SESSION_ID)).thenReturn(List.of(stat));

        SessionFeedbackSummaryDto result = service.getSummary(SESSION_ID, OWNER_ID);

        assertThat(result.totalCount()).isEqualTo(3L);
        assertThat(result.byType()).hasSize(1);
        assertThat(result.byType().get(0).count()).isEqualTo(3L);
    }

    @Test
    @DisplayName("getSummary — 본인 세션이 아니면 ACCESS_DENIED (getEvents와 동일 소유권 검증 공유)")
    void getSummary_notOwner_throws() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.getSummary(SESSION_ID, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCESS_DENIED);
    }
}
