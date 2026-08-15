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
 * 피드백 이벤트 로그의 멱등성(uniqueKey (session_id, rep_number, feedback_type) +
 * ON DUPLICATE KEY UPDATE)이 실제로 재전송에 안전한지 검증한다(db-deep-dive.md §C). 실제 JdbcTemplate
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

    /** 멱등키는 (session, rep, type) 이다 — epochSecond 는 표시·정렬용이라 판정에 안 쓰인다. */
    private FeedbackEvent event(String feedbackType, int repNumber, long epochSecond, double syncRate) {
        return FeedbackEvent.newBuilder()
                .setFeedbackType(feedbackType)
                .setRepNumber(repNumber)
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
                event("KNEE_OUT", 1, 1000, 50.0),
                event("HIP_HIGH", 2, 1001, 60.0)
        ));

        assertThat(inserted).isEqualTo(2);
        assertThat(feedbackLogRepository.findBySessionIdOrderByOccurredAtAsc(session.getId())).hasSize(2);
    }

    @Test
    @DisplayName("완전히 같은 batch를 재전송하면(at-least-once 재시도) 전부 흡수 — 중복 없음")
    void saveBatch_exactRetry_isIdempotent() {
        FeedbackBatchRequest request = batch(
                event("KNEE_OUT", 1, 1000, 50.0),
                event("HIP_HIGH", 2, 1001, 60.0)
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
                event("KNEE_OUT", 1, 1000, 50.0),
                event("HIP_HIGH", 2, 1001, 60.0)
        ));

        // 두 번째 batch: rep1 KNEE_OUT 은 중복, rep3 BACK_BENT 는 신규
        int secondInserted = feedbackLogService.saveBatch(batch(
                event("KNEE_OUT", 1, 1000, 50.0),
                event("BACK_BENT", 3, 1002, 70.0)
        ));

        assertThat(secondInserted).isEqualTo(1);
        assertThat(feedbackLogRepository.findBySessionIdOrderByOccurredAtAsc(session.getId())).hasSize(3);
    }

    @Test
    @DisplayName("존재하지 않는 세션이면 SESSION_NOT_FOUND, 아무 것도 삽입 안 함")
    void saveBatch_unknownSession_throwsAndInsertsNothing() {
        FeedbackBatchRequest request = FeedbackBatchRequest.newBuilder()
                .setSessionId(999999L).setSetNo(1)
                .addEvents(event("KNEE_OUT", 1, 1000, 50.0))
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
                event("KNEE_OUT", 1, 1000, 50.0),        // 유효 — 먼저 옴
                event("NOT_A_REAL_TYPE", 2, 1001, 60.0)   // 무효 — 배치 파라미터 바인딩 중 예외
        );

        assertThatThrownBy(() -> feedbackLogService.saveBatch(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);

        // setValues() 단계에서 던져지면 executeBatch() 자체가 호출 안 되므로 앞선 유효 행도 삽입 안 됨
        assertThat(feedbackLogRepository.findBySessionIdOrderByOccurredAtAsc(session.getId())).isEmpty();
    }

    /**
     * #193 ② — 키가 시각에서 rep 으로 옮겨진 것의 <b>절반</b>. 재전송이 시각을 다시 찍어도
     * 같은 사건이면 행이 늘지 않는다.
     *
     * <p>옛 키(session, occurred_at, type)에서는 이 배치가 <b>새 행</b>이 됐다 — 전송 시각으로
     * 다시 찍는 재전송이 «없는 사건» 을 만들어내던 자리다.
     */
    @Test
    @DisplayName("#193 같은 rep·같은 유형이면 시각이 달라도 중복이다 (재전송이 시각을 다시 찍어도 안전)")
    void saveBatch_sameRepDifferentTimestamp_isDuplicate() {
        feedbackLogService.saveBatch(batch(event("KNEE_OUT", 3, 1000, 50.0)));

        int second = feedbackLogService.saveBatch(batch(event("KNEE_OUT", 3, 9999, 50.0)));

        assertThat(second).isZero();
        assertThat(feedbackLogRepository.findBySessionIdOrderByOccurredAtAsc(session.getId())).hasSize(1);
    }

    /**
     * #193 ② — 나머지 절반. 시각이 같아도 rep 이 다르면 서로 다른 사건이다.
     *
     * <p>옛 키에서는 이 둘이 <b>하나로 뭉개졌다</b>. occurred_at 이 DATETIME(초 단위)이라
     * 1초 안의 두 판정이 같은 값이 되기 때문이고, 그건 rep 이 1초 미만인 사용자 — 즉 자세가
     * 무너지도록 급하게 하는 사용자 — 에게 먼저 일어났다.
     *
     * <p>⚠️ 이 테스트는 H2 에서 돌고 H2 는 엔티티에서 스키마를 만들어 소수점 초가 살아있다.
     * 즉 <b>옛 키였다면 이 테스트는 H2 에서 통과하고 운영(MySQL DATETIME)에서만 깨졌을 것이다.</b>
     * 지금은 키에 시각이 없으므로 그 환경 차이 자체가 판정에서 빠졌다.
     */
    @Test
    @DisplayName("#193 rep 이 다르면 같은 시각·같은 유형이라도 별개 사건이다 (1초 뭉갬 제거)")
    void saveBatch_differentRepSameTimestamp_isNotDuplicate() {
        int inserted = feedbackLogService.saveBatch(batch(
                event("KNEE_OUT", 3, 1000, 50.0),
                event("KNEE_OUT", 4, 1000, 48.0)
        ));

        assertThat(inserted).isEqualTo(2);
        assertThat(feedbackLogRepository.findBySessionIdOrderByOccurredAtAsc(session.getId())).hasSize(2);
    }

    /**
     * 🔴 데이터 유실 방어. proto3 스칼라는 «미설정» 과 0 을 구분하지 못해, 보내는 쪽이
     * rep_number 를 안 채우면 0 이 도착한다. 그대로 저장하면 그 배치의 모든 이벤트가 «rep 0» 에서
     * 서로를 중복으로 지우고, 그 유실이 «멱등성이 동작했다» 로 보인다.
     */
    @Test
    @DisplayName("#193 rep_number 가 비면(=0) 저장하지 않고 INVALID_INPUT_VALUE — rep 0 에서 서로를 지우지 않도록")
    void saveBatch_missingRepNumber_isRejected() {
        FeedbackBatchRequest request = batch(
                FeedbackEvent.newBuilder()          // rep_number 를 안 채운다 = proto3 기본값 0
                        .setFeedbackType("KNEE_OUT")
                        .setSyncRateAtTrigger(50.0)
                        .setOccurredAt(ts(1000))
                        .build());

        assertThatThrownBy(() -> feedbackLogService.saveBatch(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);

        assertThat(feedbackLogRepository.findBySessionIdOrderByOccurredAtAsc(session.getId())).isEmpty();
    }
}
