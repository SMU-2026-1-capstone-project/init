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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-07 패턴 분석 3 endpoint가 쓰는 리포지토리 메서드의 실제 SQL 동작 검증
 * (pattern-analysis-implementation.md §3 세션8).
 *
 * <p>{@code PatternAnalysisServiceTest}는 이 메서드들을 전부 mock으로 대체하기 때문에, 서비스가
 * 넘긴 start/end를 리포지토리가 <b>실제로 어떻게 거르는지</b>(BETWEEN 포함 경계, avgSyncRate
 * IS NOT NULL, status 필터)는 그쪽 테스트로는 안 잡힌다 — 이 클래스가 그 빈틈이다.
 */
@SpringBootTest
@Transactional
@DisplayName("SessionRepository 패턴 분석 쿼리 3종 테스트")
class SessionRepositoryPatternAnalysisTest {

    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private CategoryRepository categoryRepository;

    private Member member;
    private Member otherMember;
    private Exercise squat;

    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 1, 0, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 8, 28, 23, 59, 59);

    @BeforeEach
    void setUp() {
        sessionRepository.deleteAll();
        memberRepository.deleteAll();
        exercisesRepository.deleteAll();
        sessionRepository.flush();

        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        squat = exercisesRepository.save(Exercise.builder().name("스쿼트").category(category).build());
        member = saveMember("hong", "hong@t.com");
        otherMember = saveMember("kim", "kim@t.com");
    }

    @Nested
    @DisplayName("findStartTimesByMemberAndRange — periodicity 전용")
    class FindStartTimes {

        @Test
        @DisplayName("0건 — 세션이 아예 없으면 빈 리스트(null 아님)")
        void noSessions_returnsEmptyList() {
            List<LocalDateTime> result = sessionRepository.findStartTimesByMemberAndRange(
                    member.getId(), START, END);

            assertThat(result).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("경계 포함 — start·end 정각의 세션도 결과에 들어간다(BETWEEN inclusive)")
        void boundaryTimestamps_areIncluded() {
            saveSession(member, START, Status.COMPLETED, "70.00");
            saveSession(member, END, Status.COMPLETED, "70.00");

            List<LocalDateTime> result = sessionRepository.findStartTimesByMemberAndRange(
                    member.getId(), START, END);

            assertThat(result).containsExactlyInAnyOrder(START, END);
        }

        @Test
        @DisplayName("경계 밖 — start 1초 전, end 1초 후는 제외된다")
        void justOutsideBoundary_isExcluded() {
            saveSession(member, START.minusSeconds(1), Status.COMPLETED, "70.00");
            saveSession(member, END.plusSeconds(1), Status.COMPLETED, "70.00");

            List<LocalDateTime> result = sessionRepository.findStartTimesByMemberAndRange(
                    member.getId(), START, END);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("status 조건이 없다 — IN_PROGRESS·CANCELLED·FAILED도 전부 잡힌다")
        void allStatuses_areCounted() {
            saveSession(member, START.plusDays(1), Status.IN_PROGRESS, null);
            saveSession(member, START.plusDays(2), Status.CANCELLED, null);
            saveSession(member, START.plusDays(3), Status.FAILED, null);
            saveSession(member, START.plusDays(4), Status.COMPLETED, "80.00");

            List<LocalDateTime> result = sessionRepository.findStartTimesByMemberAndRange(
                    member.getId(), START, END);

            assertThat(result).hasSize(4);
        }

        @Test
        @DisplayName("다른 회원 세션은 안 섞인다")
        void otherMembersSessions_areExcluded() {
            saveSession(otherMember, START.plusDays(1), Status.COMPLETED, "90.00");

            List<LocalDateTime> result = sessionRepository.findStartTimesByMemberAndRange(
                    member.getId(), START, END);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findIntensitySamplesByMemberAndRange — intensity-trend 전용")
    class FindIntensitySamples {

        @Test
        @DisplayName("0건 — 세션이 없으면 빈 리스트")
        void noSessions_returnsEmptyList() {
            List<SessionRepository.IntensitySample> result =
                    sessionRepository.findIntensitySamplesByMemberAndRange(member.getId(), START, END);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("avgSyncRate가 null인 세션은 창 안에 있어도 제외된다")
        void nullAvgSyncRate_isExcluded() {
            saveSession(member, START.plusDays(1), Status.IN_PROGRESS, null);
            saveSession(member, START.plusDays(2), Status.COMPLETED, "85.00");

            List<SessionRepository.IntensitySample> result =
                    sessionRepository.findIntensitySamplesByMemberAndRange(member.getId(), START, END);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getAvgSyncRate()).isEqualByComparingTo("85.00");
        }

        @Test
        @DisplayName("경계 포함 — start·end 정각의 세션도 포함된다")
        void boundaryTimestamps_areIncluded() {
            saveSession(member, START, Status.COMPLETED, "60.00");
            saveSession(member, END, Status.COMPLETED, "60.00");

            List<SessionRepository.IntensitySample> result =
                    sessionRepository.findIntensitySamplesByMemberAndRange(member.getId(), START, END);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("경계 밖은 제외된다")
        void justOutsideBoundary_isExcluded() {
            saveSession(member, START.minusSeconds(1), Status.COMPLETED, "60.00");
            saveSession(member, END.plusSeconds(1), Status.COMPLETED, "60.00");

            List<SessionRepository.IntensitySample> result =
                    sessionRepository.findIntensitySamplesByMemberAndRange(member.getId(), START, END);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findDistinctActiveDates — consistency 전용")
    class FindDistinctActiveDates {

        @Test
        @DisplayName("0건 — 활동일이 없으면 빈 리스트")
        void noSessions_returnsEmptyList() {
            List<java.sql.Date> result = sessionRepository.findDistinctActiveDates(
                    member.getId(), List.of(Status.COMPLETED), START, END);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("status 필터 — COMPLETED만 넘기면 FAILED만 있는 날은 안 잡힌다")
        void statusFilter_excludesOtherStatuses() {
            saveSession(member, START.plusDays(1), Status.FAILED, null);
            saveSession(member, START.plusDays(2), Status.COMPLETED, "80.00");

            List<java.sql.Date> result = sessionRepository.findDistinctActiveDates(
                    member.getId(), List.of(Status.COMPLETED), START, END);

            assertThat(result).containsExactly(java.sql.Date.valueOf(START.plusDays(2).toLocalDate()));
        }

        @Test
        @DisplayName("같은 날 여러 COMPLETED 세션 — DISTINCT로 날짜 1개만 나온다")
        void sameDayMultipleSessions_dedupToOneDate() {
            LocalDateTime day = START.plusDays(3);
            saveSession(member, day.withHour(7), Status.COMPLETED, "70.00");
            saveSession(member, day.withHour(19), Status.COMPLETED, "75.00");

            List<java.sql.Date> result = sessionRepository.findDistinctActiveDates(
                    member.getId(), List.of(Status.COMPLETED), START, END);

            assertThat(result).containsExactly(java.sql.Date.valueOf(day.toLocalDate()));
        }

        @Test
        @DisplayName("경계 포함/제외 — start·end 정각은 포함, 그 밖은 제외")
        void boundary_inclusiveAtEdgesExclusiveOutside() {
            saveSession(member, START, Status.COMPLETED, "70.00");
            saveSession(member, END, Status.COMPLETED, "70.00");
            saveSession(member, START.minusSeconds(1), Status.COMPLETED, "70.00");
            saveSession(member, END.plusSeconds(1), Status.COMPLETED, "70.00");

            List<java.sql.Date> result = sessionRepository.findDistinctActiveDates(
                    member.getId(), List.of(Status.COMPLETED), START, END);

            assertThat(result).containsExactlyInAnyOrder(
                    java.sql.Date.valueOf(START.toLocalDate()),
                    java.sql.Date.valueOf(END.toLocalDate()));
        }
    }

    private Member saveMember(String username, String email) {
        return memberRepository.save(Member.builder()
                .username(username)
                .email(email)
                .password("encoded-password")
                .selectedPersona(SelectedPersona.BEGINNER)
                .build());
    }

    private void saveSession(Member owner, LocalDateTime startTime, Status status, String avgSyncRate) {
        sessionRepository.save(Session.builder()
                .member(owner)
                .exercise(squat)
                .startTime(startTime)
                .status(status)
                .totalReps(20)
                .avgSyncRate(avgSyncRate == null ? null : new BigDecimal(avgSyncRate))
                .build());
    }
}
