package com.shadowfit.repository.exercise;

import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code findByMemberIdAndExerciseIdAndStatusOrderByStartTimeDesc}(BE-08 추천 입력) 검증 —
 * {@code Limit} 파생 쿼리가 실제로 개수를 제한하고 내림차순을 지키는지, status·exercise
 * 필터가 맞는지 확인한다. {@code RecommendationServiceTest}는 이 메서드를 mock하므로
 * 여기서 실제 쿼리로 확인한다.
 */
@SpringBootTest
@Transactional
@DisplayName("SessionRepository.findByMemberIdAndExerciseIdAndStatusOrderByStartTimeDesc 테스트 (BE-08)")
class SessionRepositoryRecommendationTest {

    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private CategoryRepository categoryRepository;

    private Member member;
    private Exercise squat;
    private Exercise lunge;

    @BeforeEach
    void setUp() {
        sessionRepository.deleteAll();
        memberRepository.deleteAll();
        exercisesRepository.deleteAll();
        sessionRepository.flush();

        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        squat = exercisesRepository.save(Exercise.builder().name("스쿼트").category(category).build());
        lunge = exercisesRepository.save(Exercise.builder().name("런지").category(category).build());
        member = memberRepository.save(Member.builder()
                .username("hong").email("hong@t.com").password("pw")
                .selectedPersona(SelectedPersona.BEGINNER).build());
    }

    @Test
    @DisplayName("Limit — 요청한 개수만큼만, 최신순으로 온다")
    void limitsToRequestedCountInDescOrder() {
        saveSession(squat, Status.COMPLETED, LocalDateTime.of(2026, 8, 1, 10, 0));
        saveSession(squat, Status.COMPLETED, LocalDateTime.of(2026, 8, 2, 10, 0));
        saveSession(squat, Status.COMPLETED, LocalDateTime.of(2026, 8, 3, 10, 0));
        saveSession(squat, Status.COMPLETED, LocalDateTime.of(2026, 8, 4, 10, 0));
        saveSession(squat, Status.COMPLETED, LocalDateTime.of(2026, 8, 5, 10, 0));

        List<Session> result = sessionRepository.findByMemberIdAndExerciseIdAndStatusOrderByStartTimeDesc(
                member.getId(), squat.getId(), Status.COMPLETED, Limit.of(3));

        assertThat(result).hasSize(3);
        assertThat(result).extracting(Session::getStartTime).containsExactly(
                LocalDateTime.of(2026, 8, 5, 10, 0),
                LocalDateTime.of(2026, 8, 4, 10, 0),
                LocalDateTime.of(2026, 8, 3, 10, 0));
    }

    @Test
    @DisplayName("exercise_id 필터 — 다른 운동 세션은 안 섞인다")
    void excludesOtherExercises() {
        saveSession(squat, Status.COMPLETED, LocalDateTime.of(2026, 8, 1, 10, 0));
        saveSession(lunge, Status.COMPLETED, LocalDateTime.of(2026, 8, 2, 10, 0));

        List<Session> result = sessionRepository.findByMemberIdAndExerciseIdAndStatusOrderByStartTimeDesc(
                member.getId(), squat.getId(), Status.COMPLETED, Limit.of(3));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getExercise().getId()).isEqualTo(squat.getId());
    }

    @Test
    @DisplayName("status 필터 — IN_PROGRESS·FAILED는 안 섞인다")
    void excludesNonCompletedStatuses() {
        saveSession(squat, Status.IN_PROGRESS, LocalDateTime.of(2026, 8, 1, 10, 0));
        saveSession(squat, Status.FAILED, LocalDateTime.of(2026, 8, 2, 10, 0));
        saveSession(squat, Status.COMPLETED, LocalDateTime.of(2026, 8, 3, 10, 0));

        List<Session> result = sessionRepository.findByMemberIdAndExerciseIdAndStatusOrderByStartTimeDesc(
                member.getId(), squat.getId(), Status.COMPLETED, Limit.of(10));

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("0건 — 완료 세션이 없으면 빈 리스트")
    void noCompletedSessions_returnsEmptyList() {
        List<Session> result = sessionRepository.findByMemberIdAndExerciseIdAndStatusOrderByStartTimeDesc(
                member.getId(), squat.getId(), Status.COMPLETED, Limit.of(3));

        assertThat(result).isEmpty();
    }

    private void saveSession(Exercise exercise, Status status, LocalDateTime startTime) {
        sessionRepository.save(Session.builder()
                .member(member)
                .exercise(exercise)
                .startTime(startTime)
                .endTime(startTime.plusMinutes(20))
                .status(status)
                .totalReps(20)
                .avgSyncRate(new BigDecimal("75.00"))
                .build());
    }
}
