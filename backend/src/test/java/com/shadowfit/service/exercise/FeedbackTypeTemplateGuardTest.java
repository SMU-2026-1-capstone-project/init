package com.shadowfit.service.exercise;

import com.google.protobuf.Timestamp;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.grpc.FeedbackBatchRequest;
import com.shadowfit.grpc.FeedbackEvent;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.ExerciseFeedbackTemplate;
import com.shadowfit.model.exercise.FeedbackType;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExerciseFeedbackTemplateRepository;
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
 * #297 — <b>그 운동에 멘트가 없는 유형을 입구에서 막는가.</b>
 *
 * <p>{@code FeedbackType} enum 은 8종인데 멘트({@code exercise_feedback_templates})는 운동마다
 * 다르다. 막지 않으면 실패에 신호가 없다 — 저장은 성공하고 로그도 정상이고 행도 남는데,
 * 읽기 경로가 템플릿과 조인하지 않아서 매핑은 클라이언트가 하고, 거기서 매칭이 안 되면
 * <b>사용자 화면에서만 조용히 사라진다.</b>
 *
 * <p>기존 {@code FeedbackLogServiceTest} 의 픽스처는 템플릿을 하나도 안 만든다. 그래서 이
 * 클래스가 따로 있다 — 여기서만 «템플릿이 있는 운동» 을 세운다.
 */
@SpringBootTest
@Transactional
@DisplayName("#297 멘트 없는 유형 거절")
class FeedbackTypeTemplateGuardTest {

    @Autowired private FeedbackLogService feedbackLogService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionFeedbackLogRepository feedbackLogRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private com.shadowfit.repository.exercise.CategoryRepository categoryRepository;
    @Autowired private ExerciseFeedbackTemplateRepository templateRepository;

    private Member member;

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(Member.builder()
                .email("tmplguard@test.com").username("멘트가드").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
    }

    /** 운동 하나를 만들고, 주어진 유형들에만 멘트를 시드한다. */
    private Session sessionFor(String exerciseName, FeedbackType... seeded) {
        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        Exercise exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name(exerciseName).category(category).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00")).build());
        for (FeedbackType type : seeded) {
            templateRepository.saveAndFlush(ExerciseFeedbackTemplate.builder()
                    .exercise(exercise).feedbackType(type)
                    .persona(SelectedPersona.BEGINNER)
                    .message(type.name() + " 멘트").priority(10).build());
        }
        return sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(LocalDateTime.now()).status(Status.IN_PROGRESS)
                .totalReps(0).difficultyLevel(1).build());
    }

    private FeedbackBatchRequest batch(Session session, FeedbackType... types) {
        FeedbackBatchRequest.Builder b = FeedbackBatchRequest.newBuilder()
                .setSessionId(session.getId()).setSetNo(1).setIsFinal(false);
        int rep = 1;
        for (FeedbackType type : types) {
            b.addEvents(FeedbackEvent.newBuilder()
                    .setFeedbackType(type.name())
                    .setRepNumber(rep++)
                    .setSyncRateAtTrigger(55.0)
                    .setOccurredAt(Timestamp.newBuilder().setSeconds(1_760_000_000L).build())
                    .build());
        }
        return b.build();
    }

    @Test
    @DisplayName("그 운동에 멘트가 있는 유형이면 저장된다")
    void supportedTypeIsSaved() {
        Session session = sessionFor("스쿼트", FeedbackType.BACK_BENT, FeedbackType.HIP_HIGH);

        int saved = feedbackLogService.saveBatch(batch(session, FeedbackType.BACK_BENT, FeedbackType.HIP_HIGH));

        assertThat(saved).isEqualTo(2);
        assertThat(feedbackLogRepository.findBySessionIdOrderByOccurredAtAsc(session.getId())).hasSize(2);
    }

    @Test
    @DisplayName("멘트 없는 유형이 섞이면 INVALID_INPUT_VALUE — 같은 배치의 정상 이벤트도 안 들어간다")
    void unsupportedTypeRejectsWholeBatch() {
        // 스쿼트에는 HEAD_DOWN 멘트가 없다(V2 시드에서 exercise 3 에만 있다). enum 에는 있으므로
        // valueOf 는 통과한다 — 그 틈이 이 이슈다.
        Session session = sessionFor("스쿼트", FeedbackType.BACK_BENT, FeedbackType.HIP_HIGH);

        assertThatThrownBy(() ->
                feedbackLogService.saveBatch(batch(session, FeedbackType.BACK_BENT, FeedbackType.HEAD_DOWN)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);

        // 배치 전체가 죽는다. 정상이던 BACK_BENT 도 안 남아야 «보내는 쪽을 고쳐라» 가 된다 —
        // 절반만 들어가면 AI 가 재전송할 때 무엇이 이미 있는지 알 수 없다.
        assertThat(feedbackLogRepository.findBySessionIdOrderByOccurredAtAsc(session.getId())).isEmpty();
    }

    @Test
    @DisplayName("운동에 멘트가 하나도 없으면 검증을 건너뛴다 — 시딩 누락으로 사용자 데이터를 버리지 않는다")
    void exerciseWithoutAnyTemplatePassesThrough() {
        // 템플릿을 하나도 안 시드한다. 이건 보내는 쪽 잘못이 아니라 우리 시딩 누락이라,
        // INVALID_ARGUMENT 를 주면 AI 가 「재시도해도 소용없음」으로 읽고 버퍼를 통째로 버린다.
        // 화면은 어차피 조용하지만 행은 남겨야 나중에 멘트를 넣었을 때 지난 기록이 살아난다.
        Session session = sessionFor("템플릿없는운동");

        int saved = feedbackLogService.saveBatch(batch(session, FeedbackType.HEAD_DOWN));

        assertThat(saved).isEqualTo(1);
        assertThat(feedbackLogRepository.findBySessionIdOrderByOccurredAtAsc(session.getId())).hasSize(1);
    }

    @Test
    @DisplayName("enum 에 없는 문자열은 여전히 거절된다 — 기존 계약이 안 바뀌었는지")
    void unknownStringStillRejected() {
        Session session = sessionFor("스쿼트", FeedbackType.BACK_BENT);

        FeedbackBatchRequest request = FeedbackBatchRequest.newBuilder()
                .setSessionId(session.getId()).setSetNo(1)
                .addEvents(FeedbackEvent.newBuilder()
                        .setFeedbackType("존재하지_않는_유형").setRepNumber(1)
                        .setSyncRateAtTrigger(55.0)
                        .setOccurredAt(Timestamp.newBuilder().setSeconds(1_760_000_000L).build())
                        .build())
                .build();

        assertThatThrownBy(() -> feedbackLogService.saveBatch(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
    }
}
