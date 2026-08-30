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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code findCompletedSessionWindowsSince}(BE-06 목표 진척 조회 전용) 검증.
 * {@code GoalServiceTest}는 이 메서드를 mock하므로, status 필터·경계·다른 회원 격리는
 * 여기서 실제 쿼리로 확인한다.
 */
@SpringBootTest
@Transactional
@DisplayName("SessionRepository.findCompletedSessionWindowsSince 테스트 (BE-06)")
class SessionRepositoryGoalTest {

    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private CategoryRepository categoryRepository;

    private Member member;
    private Exercise squat;
    private static final LocalDateTime SINCE = LocalDateTime.of(2026, 8, 24, 0, 0, 0);

    @BeforeEach
    void setUp() {
        sessionRepository.deleteAll();
        memberRepository.deleteAll();
        exercisesRepository.deleteAll();
        sessionRepository.flush();

        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        squat = exercisesRepository.save(Exercise.builder().name("스쿼트").category(category).build());
        member = memberRepository.save(Member.builder()
                .username("hong").email("hong@t.com").password("pw")
                .selectedPersona(SelectedPersona.BEGINNER).build());
    }

    @Test
    @DisplayName("status 필터 — COMPLETED만 잡히고 IN_PROGRESS·FAILED는 제외된다")
    void onlyCompletedStatusIsIncluded() {
        saveSession(SINCE.plusDays(1), Status.COMPLETED);
        saveSession(SINCE.plusDays(2), Status.IN_PROGRESS);
        saveSession(SINCE.plusDays(3), Status.FAILED);
        saveSession(SINCE.plusDays(4), Status.CANCELLED);

        List<SessionRepository.CompletedSessionWindow> result =
                sessionRepository.findCompletedSessionWindowsSince(member.getId(), SINCE);

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("경계 — since 정각은 포함, 1초 전은 제외")
    void sinceBoundary_inclusive() {
        saveSession(SINCE, Status.COMPLETED);
        saveSession(SINCE.minusSeconds(1), Status.COMPLETED);

        List<SessionRepository.CompletedSessionWindow> result =
                sessionRepository.findCompletedSessionWindowsSince(member.getId(), SINCE);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStartTime()).isEqualTo(SINCE);
    }

    @Test
    @DisplayName("상한이 없다 — 미래(오늘 이후) 세션도 잡힌다")
    void noUpperBound_futureSessionsIncluded() {
        saveSession(SINCE.plusDays(100), Status.COMPLETED);

        List<SessionRepository.CompletedSessionWindow> result =
                sessionRepository.findCompletedSessionWindowsSince(member.getId(), SINCE);

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("0건 — 세션이 없으면 빈 리스트")
    void noSessions_returnsEmptyList() {
        List<SessionRepository.CompletedSessionWindow> result =
                sessionRepository.findCompletedSessionWindowsSince(member.getId(), SINCE);

        assertThat(result).isEmpty();
    }

    private void saveSession(LocalDateTime startTime, Status status) {
        sessionRepository.save(Session.builder()
                .member(member)
                .exercise(squat)
                .startTime(startTime)
                .endTime(startTime.plusMinutes(20))
                .status(status)
                .totalReps(20)
                .build());
    }
}
