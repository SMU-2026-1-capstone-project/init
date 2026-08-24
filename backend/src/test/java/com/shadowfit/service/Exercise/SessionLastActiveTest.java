package com.shadowfit.service.Exercise;

import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 활동 시각 기록 경로 (docs/decisions/session-liveness-vs-elapsed-time.md, ㄷ안).
 *
 * <p>판정 식 자체는 {@code SessionReattachTest} 가 본다. 여기서 보는 것은 <b>쓰기 경로</b> —
 * rep 배치가 도착했을 때 {@code last_active_at} 이 실제로 갱신되는가, 그리고 그 갱신이
 * {@code @Version} 을 건드리지 않는가다.
 *
 * <p><b>version 을 함께 보는 이유</b>: 이 필드를 JPA 엔티티로 갱신하면 낙관적 락 버전이 따라
 * 올라간다. 그 락은 AI 완료 콜백과 타임아웃 스케줄러의 경쟁을 조율하는 장치라, 운동 중 내내
 * version 이 바뀌면 지금은 드물어서 지표로만 관측하던 경쟁이 상시화된다. 그래서 쓰기를
 * {@code JdbcTemplate} 으로 하는 것이고, 그 선택이 유지되는지를 여기서 고정한다.
 */
@SpringBootTest
@Transactional
@DisplayName("활동 시각 기록 (last_active_at)")
class SessionLastActiveTest {

    @Autowired private PoseDataService poseDataService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private com.shadowfit.repository.exercise.CategoryRepository categoryRepository;
    @Autowired private EntityManager entityManager;

    @Value("${exercise.session.timeout.idle-minutes:10}")
    private int idleMinutes;

    @Value("${exercise.session.timeout.default-buffer-minutes:30}")
    private int bufferMinutes;

    private static final int EXPECTED_DURATION_MINUTES = 15;

    private Member owner;
    private Exercise exercise;

    @BeforeEach
    void setUp() {
        owner = memberRepository.saveAndFlush(Member.builder()
                .email("last-active@test.com").username("owner").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category)
                .expectedDurationMinutes(EXPECTED_DURATION_MINUTES)
                .syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00"))
                .analysisSupported(true)
                .build());
    }

    private Session inProgressSession(LocalDateTime startTime) {
        return sessionRepository.saveAndFlush(Session.builder()
                .member(owner).exercise(exercise)
                .startTime(startTime).status(Status.IN_PROGRESS).endTime(null)
                .totalReps(0).difficultyLevel(1).build());
    }

    /** 다운샘플(R=5)을 통과해 최소 1행은 남도록 만든다. */
    private List<PoseDataRequest> batch() {
        return java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> PoseDataRequest.newBuilder()
                        .setRepNumber(1).setTimestampSec(i * 0.33)
                        .setJointCoordinates("{}").setSyncRate(70.0).setFeedbackMessage("ok")
                        .build())
                .toList();
    }

    /** JdbcTemplate 로 쓴 값은 영속성 컨텍스트에 없다 — 비우고 다시 읽어야 실제 DB 상태가 보인다. */
    private Session reload(Long id) {
        entityManager.flush();
        entityManager.clear();
        return sessionRepository.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("rep 배치가 도착하면 활동 시각이 찍힌다")
    void 배치가_활동시각을_갱신한다() {
        Session s = inProgressSession(LocalDateTime.now().minusMinutes(5));
        assertThat(s.getLastActiveAt()).as("아직 rep 이 없다").isNull();
        LocalDateTime before = LocalDateTime.now();

        poseDataService.savePoseDataBatch(s.getId(), batch());

        Session after = reload(s.getId());
        assertThat(after.getLastActiveAt())
                .as("배치 도착 자체가 '아직 운동 중'이라는 신호다")
                .isNotNull()
                .isAfterOrEqualTo(before.minusSeconds(1));
    }

    @Test
    @DisplayName("활동 시각 갱신이 낙관적 락 버전을 올리지 않는다")
    void 갱신이_version을_건드리지_않는다() {
        Session s = inProgressSession(LocalDateTime.now().minusMinutes(5));
        Long versionBefore = reload(s.getId()).getVersion();

        poseDataService.savePoseDataBatch(s.getId(), batch());

        Session after = reload(s.getId());
        assertThat(after.getLastActiveAt()).isNotNull();
        assertThat(after.getVersion())
                .as("JPA 로 갱신하면 version 이 올라 AI 콜백↔스케줄러 경쟁이 상시화된다 — "
                        + "그래서 JdbcTemplate 으로 이 컬럼만 직접 쓴다")
                .isEqualTo(versionBefore);
    }

    @Test
    @DisplayName("첫 배치가 도착하면 판정 기준이 준비 구간에서 유휴 구간으로 넘어간다")
    void 첫_배치가_판정기준을_전환한다() {
        // 시작 후 20분 — 종전 식(15+30=45분)으로는 아직 살아있는 세션이다.
        Session s = inProgressSession(LocalDateTime.now().minusMinutes(20));
        assertThat(s.isTimedOutAt(LocalDateTime.now(), idleMinutes, bufferMinutes))
                .as("준비 구간에서는 기존 식을 그대로 쓴다")
                .isFalse();

        poseDataService.savePoseDataBatch(s.getId(), batch());
        Session after = reload(s.getId());

        assertThat(after.timeoutThreshold(idleMinutes, bufferMinutes))
                .as("이제 앵커가 last_active_at 이다")
                .isEqualTo(after.getLastActiveAt().plusMinutes(idleMinutes));
        assertThat(after.isTimedOutAt(LocalDateTime.now(), idleMinutes, bufferMinutes))
                .as("방금 활동했으므로 살아있다")
                .isFalse();
    }
}
