package com.shadowfit.service.exercise;

import com.shadowfit.dto.coaching.TrainerRepEventDto;
import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.CategoryRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.service.coaching.TrainerConnectionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * registerTrainerRelay 가 recordOrphanWindow 와 같은 "커밋 후에만 실행" 게이팅을 실제로
 * 지키는지 검증한다 ({@code trainer-live-monitoring.md} §8 세션4).
 *
 * <p>기존 {@link PoseDataServiceTest} 는 클래스 전체가 {@code @Transactional} 롤백이라
 * {@code afterCommit()} 이 아예 안 불린다 — 그 스타일로는 릴레이가 실제로 발동하는지 검증할
 * 수 없다. 이 클래스만 {@link TestTransaction} 으로 테스트 메서드 안에서 커밋/롤백을 직접
 * 갈라 그 경계 자체를 확인한다({@code PoseDataOrphanWindowTest} 처럼 실제 Docker MySQL 을
 * 띄워 재는 것과 달리, 여기서는 "재는 것"이 아니라 "불렸는가"만 보면 되므로 기존 테스트 DB로
 * 충분하다).
 *
 * <p>{@link TrainerConnectionRegistry} 는 {@code @MockitoBean} 으로 교체해 실제 SSE 연결 없이
 * broadcast 호출 자체만 검증한다 — 페이로드 내부 로직(대표 프레임 선택·danger 판정)은
 * {@link TrainerConnectionRegistryTest} 가 아니라 이 클래스의 관심사가 아니다.
 *
 * <p>커밋시키는 테스트는 데이터가 이 트랜잭션 밖에 실제로 남으므로 {@link #tearDown} 에서
 * 직접 지운다 — 롤백된 테스트는 이미 없는 행이라 같은 DELETE 가 0건으로 조용히 끝난다.
 */
@SpringBootTest
@Transactional
@DisplayName("PoseDataService 트레이너 SSE 릴레이 커밋 게이팅 테스트")
class PoseDataServiceTrainerRelayTest {

    @Autowired private PoseDataService poseDataService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private TrainerConnectionRegistry trainerConnectionRegistry;

    private Long memberId;
    private Long categoryId;
    private Long exerciseId;
    private Long sessionId;

    // 테스트 DB 는 Flyway 를 끄고 JPA(create-drop)로만 스키마를 만들므로(application.yml
    // 주석 참고) V2 시드(스쿼트 id=1)가 없다 — PoseDataServiceTest 처럼 직접 만든다.
    @BeforeEach
    void setUp() {
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email("trainer-relay@test.com").username("u").password("dummy")
                .role(UserRole.USER).build());
        memberId = member.getId();

        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        categoryId = category.getId();
        Exercise exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .build());
        exerciseId = exercise.getId();

        Session session = sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise).startTime(LocalDateTime.now())
                .status(Status.IN_PROGRESS).totalReps(0).difficultyLevel(1).build());
        sessionId = session.getId();
    }

    // 커밋시키는 테스트는 위 setUp 이 만든 행이 이 트랜잭션 밖에 실제로 남으므로 여기서
    // 직접 지운다 — 롤백된 테스트는 이미 없는 행이라 같은 DELETE 가 0건으로 조용히 끝난다.
    // FK 순서: pose_data → exercise_sessions → exercises → categories → users.
    @AfterEach
    void tearDown() {
        if (sessionId != null) {
            jdbcTemplate.update("DELETE FROM pose_data WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM exercise_sessions WHERE id = ?", sessionId);
        }
        if (exerciseId != null) {
            jdbcTemplate.update("DELETE FROM exercises WHERE id = ?", exerciseId);
        }
        if (categoryId != null) {
            jdbcTemplate.update("DELETE FROM categories WHERE id = ?", categoryId);
        }
        if (memberId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", memberId);
        }
    }

    @Test
    @DisplayName("트랜잭션이 커밋되면 담당 트레이너에게 rep 이벤트가 중계된다")
    void relayFiresAfterCommit() {
        poseDataService.savePoseDataBatch(sessionId, List.of(frame(1)));

        TestTransaction.flagForCommit();
        TestTransaction.end();

        verify(trainerConnectionRegistry).broadcast(eq(memberId), eq("rep"), any(TrainerRepEventDto.class));
    }

    @Test
    @DisplayName("트랜잭션이 롤백되면 중계되지 않는다")
    void relayDoesNotFireOnRollback() {
        poseDataService.savePoseDataBatch(sessionId, List.of(frame(1)));

        TestTransaction.flagForRollback(); // 기본값과 같지만 의도를 명시
        TestTransaction.end();

        verifyNoInteractions(trainerConnectionRegistry);
    }

    private PoseDataRequest frame(int repNumber) {
        return PoseDataRequest.newBuilder()
                .setTimestampSec(0.0)
                .setJointCoordinates("{}")
                .setSyncRate(72.5)
                .setRepNumber(repNumber)
                .setSmoothedKneeAngle(90.0)
                .setFeedbackMessage("무릎이 안쪽으로 모입니다")
                .build();
    }
}
