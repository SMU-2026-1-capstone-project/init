package com.shadowfit.service.Exercise;

import com.google.protobuf.Timestamp;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.grpc.FeedbackBatchRequest;
import com.shadowfit.grpc.FeedbackEvent;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionFeedbackLogRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FeedbackLogService.saveBatch 통합테스트 — AI가 BT-SET으로 세트 경계마다 batch 송신하는
 * 피드백 이벤트 로그의 멱등성(uniqueKey (session_id, occurred_at, feedback_type) + INSERT
 * IGNORE)이 실제로 재전송에 안전한지 검증한다(db-deep-dive.md §C). 실제 JdbcTemplate
 * batchUpdate 경로를 타야 하는 로직(잘못된 feedback_type이 배치 내부에서 던지는 예외 등)이라
 * 모킹이 아니라 real H2로 검증.
 */
@SpringBootTest
@Transactional
@DisplayName("FeedbackLogService 테스트")
class FeedbackLogServiceTest {

    @Autowired private FeedbackLogService feedbackLogService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionFeedbackLogRepository feedbackLogRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;

    private Session session;

    @BeforeEach
    void setUp() {
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email("feedback@test.com").username("피드백유저").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        Exercise exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(ExerciseCategory.LOWER).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .build());
        session = sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(LocalDateTime.now()).status(Status.IN_PROGRESS)
                .totalReps(0).difficultyLevel(1).build());
    }

    private Timestamp ts(long epochSecond) {
        return Timestamp.newBuilder().setSeconds(epochSecond).build();
    }

    private FeedbackEvent event(String feedbackType, long epochSecond, double syncRate) {
        return FeedbackEvent.newBuilder()
                .setFeedbackType(feedbackType)
                .setSyncRateAtTrigger(syncRate)
                .setOccurredAt(ts(epochSecond))
                .build();
    }

    private FeedbackBatchRequest batch(FeedbackEvent... events) {
        FeedbackBatchRequest.Builder b = FeedbackBatchRequest.newBuilder()
                .setSessionId(session.getId()).setSetNo(1).setIsFinal(false);
        for (FeedbackEvent e : events) b.addEvents(e);
        return b.build();
    }

    @Test
    @DisplayName("정상 batch — 전부 신규 삽입, 삽입 개수 그대로 반환")
    void saveBatch_allNew_insertsAll() {
        int inserted = feedbackLogService.saveBatch(batch(
                event("KNEE_OUT", 1000, 50.0),
                event("HIP_HIGH", 1001, 60.0)
        ));

        assertThat(inserted).isEqualTo(2);
        assertThat(feedbackLogRepository.findBySessionIdOrderByOccurredAtAsc(session.getId())).hasSize(2);
    }

    @Test
    @DisplayName("완전히 같은 batch를 재전송하면(at-least-once 재시도) 전부 흡수 — 중복 없음")
    void saveBatch_exactRetry_isIdempotent() {
        FeedbackBatchRequest request = batch(
                event("KNEE_OUT", 1000, 50.0),
                event("HIP_HIGH", 1001, 60.0)
        );

        int firstInserted = feedbackLogService.saveBatch(request);
        int secondInserted = feedbackLogService.saveBatch(request); // 완전히 동일한 재전송

        assertThat(firstInserted).isEqualTo(2);
        assertThat(secondInserted).isZero(); // 전부 중복으로 흡수됨
        assertThat(feedbackLogRepository.findBySessionIdOrderByOccurredAtAsc(session.getId())).hasSize(2); // 그대로 2건
    }

    @Test
    @DisplayName("일부만 겹치는 batch는 겹치는 것만 흡수하고 새 것만 삽입")
    void saveBatch_partialOverlap_insertsOnlyNewOnes() {
        feedbackLogService.saveBatch(batch(
                event("KNEE_OUT", 1000, 50.0),
                event("HIP_HIGH", 1001, 60.0)
        ));

        // 두 번째 batch: 1000(KNEE_OUT)은 중복, 1002(BACK_BENT)는 신규
        int secondInserted = feedbackLogService.saveBatch(batch(
                event("KNEE_OUT", 1000, 50.0),
                event("BACK_BENT", 1002, 70.0)
        ));

        assertThat(secondInserted).isEqualTo(1);
        assertThat(feedbackLogRepository.findBySessionIdOrderByOccurredAtAsc(session.getId())).hasSize(3);
    }

    @Test
    @DisplayName("존재하지 않는 세션이면 SESSION_NOT_FOUND, 아무 것도 삽입 안 함")
    void saveBatch_unknownSession_throwsAndInsertsNothing() {
        FeedbackBatchRequest request = FeedbackBatchRequest.newBuilder()
                .setSessionId(999999L).setSetNo(1)
                .addEvents(event("KNEE_OUT", 1000, 50.0))
                .build();

        assertThatThrownBy(() -> feedbackLogService.saveBatch(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_NOT_FOUND);

        assertThat(feedbackLogRepository.findBySessionIdOrderByOccurredAtAsc(999999L)).isEmpty();
    }

    @Test
    @DisplayName("이벤트가 비어있으면 0 반환, 아무 것도 삽입 안 함")
    void saveBatch_emptyEvents_returnsZero() {
        int inserted = feedbackLogService.saveBatch(batch());

        assertThat(inserted).isZero();
        assertThat(feedbackLogRepository.findBySessionIdOrderByOccurredAtAsc(session.getId())).isEmpty();
    }

    @Test
    @DisplayName("배치 안에 잘못된 feedback_type 문자열이 있으면 INVALID_INPUT_VALUE, 같은 배치의 다른 행도 삽입 안 됨")
    void saveBatch_invalidFeedbackType_throwsAndInsertsNothing() {
        FeedbackBatchRequest request = batch(
                event("KNEE_OUT", 1000, 50.0),        // 유효 — 먼저 옴
                event("NOT_A_REAL_TYPE", 1001, 60.0)   // 무효 — 배치 파라미터 바인딩 중 예외
        );

        assertThatThrownBy(() -> feedbackLogService.saveBatch(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);

        // setValues() 단계에서 던져지면 executeBatch() 자체가 호출 안 되므로 앞선 유효 행도 삽입 안 됨
        assertThat(feedbackLogRepository.findBySessionIdOrderByOccurredAtAsc(session.getId())).isEmpty();
    }
}
