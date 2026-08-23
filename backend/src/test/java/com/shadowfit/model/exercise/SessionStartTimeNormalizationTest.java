package com.shadowfit.model.exercise;

import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code Session.startTime} 의 «초 이하 없음» 불변식 (#446).
 *
 * <p>이 값은 {@code pose_data} 의 멱등 앵커이자 파티션 키이고, 리포트·재부착 조회가
 * <b>등호</b>로 찾는다(#392). 그래서 «메모리의 값» 과 «저장된 값» 이 어긋나면 조회가
 * <b>조용히 0행</b>이 된다 — 예외도 로그도 없이 «데이터가 없다» 로 보인다.
 *
 * <p>불변식을 지키던 곳이 {@code SessionService.createSession} 한 곳뿐이라 엔티티를 직접
 * 짓는 경로는 전부 빠져나갔다. 그 구멍을 엔티티의 {@code @PrePersist} 로 막았고, 이 테스트가
 * 그것을 고정한다 — <b>서비스를 안 거치고</b> 엔티티로 바로 저장하는 것이 핵심이다.
 */
@SpringBootTest
@Transactional
@DisplayName("Session.startTime 초 이하 정규화 (#446)")
class SessionStartTimeNormalizationTest {

    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private PoseDataRepository poseDataRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Member member;
    private Exercise exercise;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        member = memberRepository.saveAndFlush(Member.builder()
                .username("anchor" + unique)
                .email("anchor" + unique + "@test.com")
                .password("x")
                .selectedPersona(SelectedPersona.BEGINNER)
                .role(UserRole.USER)
                .build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트" + unique)
                .category(ExerciseCategory.LOWER)
                .expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00"))
                .analysisSupported(true)
                .build());
    }

    /** 서비스를 안 거치고 엔티티로 바로 저장한다 — 픽스처 24곳이 하던 그대로다. */
    private Session saveRaw(LocalDateTime startTime) {
        return sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(startTime)
                .status(Status.IN_PROGRESS)
                .totalReps(0).difficultyLevel(1)
                .build());
    }

    @Test
    @DisplayName("나노초를 넣어도 저장되면 0 이다 — 서비스를 안 거쳐도 지켜진다")
    void 엔티티로_바로_저장해도_초_이하가_잘린다() {
        LocalDateTime withNanos = LocalDateTime.now().withNano(123_456_789);

        Session saved = saveRaw(withNanos);

        assertThat(saved.getStartTime().getNano())
                .as("PrePersist 가 잘라야 «메모리의 값 == 저장된 값» 이 성립한다")
                .isZero();
        assertThat(saved.getStartTime())
                .isEqualTo(withNanos.withNano(0));
    }

    @Test
    @DisplayName("앵커 등호 조회가 성립한다 — 이 불변식이 실제로 무엇을 지키는지")
    void 앵커_등호_조회가_0행이_되지_않는다() {
        Session s = saveRaw(LocalDateTime.now().withNano(987_654_321));

        // 프로덕션에서 이 값을 넣는 것은 PoseDataService 의 JdbcTemplate 배치다(#188 앵커).
        // 엔티티는 created_at 을 insertable=false 로 막고 있어 JPA 로는 못 넣는다.
        poseDataRepository.saveAndFlush(PoseData.builder()
                .session(s).repNumber(1).timestampSec(0.0)
                .jointCoordinates("{}").syncRate(70.0).build());
        jdbcTemplate.update("UPDATE pose_data SET created_at = ? WHERE session_id = ?",
                s.getStartTime(), s.getId());

        assertThat(poseDataRepository.findFramesBySessionId(s.getId(), s.getStartTime()))
                .as("나노초가 남아 있으면 여기가 조용히 비고, 그게 #392 에서 세 번 물린 함정이다")
                .hasSize(1);
    }
}
