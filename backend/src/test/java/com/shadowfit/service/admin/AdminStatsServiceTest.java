package com.shadowfit.service.admin;

import com.shadowfit.dto.admin.AdminStatsOverviewDto;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 관리자 대시보드 집계 검증 ({@code admin-page-scope.md} §3-D).
 *
 * <p>[목록 테스트와 무엇이 다른가] 목록(A·B)은 <b>어떤 행이 나오는가</b>를 봤다면 여기는
 * <b>몇 개로 접히는가</b>를 본다. 접고 나면 원본이 안 보이므로, 틀려도 숫자는 그럴듯하게
 * 나온다. 이 클래스가 겨냥하는 실패는 셋이다.
 *
 * <ul>
 *   <li><b>날짜 경계</b> — "오늘"이 어제·내일을 삼키는가. 목록의 기간 필터와 같은 함정인데
 *       집계는 <b>틀려도 숫자 하나만 달라져서</b> 눈으로는 절대 안 잡힌다</li>
 *   <li><b>0 건인 상태가 사라지는 것</b> — {@code GROUP BY} 는 없는 상태의 행을 안 만든다.
 *       그대로 내보내면 "실패 0건"과 "실패 항목이 화면에서 빠진 것"이 구분되지 않는다</li>
 *   <li><b>평균의 null</b> — 완료 세션이 없을 때 0.0 으로 접히면 "싱크로율 0%"라는
 *       <b>없는 사실</b>이 만들어진다. null 그대로 올라와야 한다</li>
 * </ul>
 *
 * <p>시각은 {@code LocalDate.now()} 기준으로 만든다 — 서비스가 실행 시점의 오늘을 쓰므로
 * 고정 날짜를 쓰면 테스트가 성립하지 않는다. 대신 <b>당일 00:00 에서 상대적으로</b> 잡아
 * (예: {@code todayStart.plusHours(1)}) 자정 근처에 돌려도 날짜가 밀리지 않게 했다.
 */
@SpringBootTest
@Transactional
@DisplayName("관리자 대시보드 집계 테스트")
class AdminStatsServiceTest {

    @Autowired private AdminStatsService adminStatsService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private EntityManager em;

    private LocalDateTime todayStart;
    private Exercise squat;

    @BeforeEach
    void setUp() {
        sessionRepository.deleteAll();
        memberRepository.deleteAll();
        exercisesRepository.deleteAll();
        sessionRepository.flush();

        todayStart = LocalDate.now().atStartOfDay();
        squat = exercisesRepository.save(Exercise.builder()
                .name("스쿼트")
                .category(ExerciseCategory.LOWER)
                .build());
    }

    @Nested
    @DisplayName("오늘 경계")
    class TodayBoundary {

        @Test
        @DisplayName("어제 세션은 오늘 집계에 들어가지 않는다")
        void yesterdaySession_excluded() {
            Member m = saveMember("hong", "hong@t.com");
            saveSession(m, todayStart.minusSeconds(1), Status.COMPLETED, "80.00");
            saveSession(m, todayStart, Status.COMPLETED, "70.00");

            assertThat(adminStatsService.getOverview().todaySessionCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("오늘 23:59:59 세션도 오늘로 센다 — 종료 경계가 다음 날 00:00 미만이므로")
        void lastSecondOfToday_included() {
            Member m = saveMember("hong", "hong@t.com");
            saveSession(m, todayStart.plusDays(1).minusSeconds(1), Status.COMPLETED, "80.00");

            assertThat(adminStatsService.getOverview().todaySessionCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("내일 세션은 오늘 집계에서 빠진다")
        void tomorrowSession_excluded() {
            Member m = saveMember("hong", "hong@t.com");
            saveSession(m, todayStart.plusDays(1), Status.COMPLETED, "80.00");

            assertThat(adminStatsService.getOverview().todaySessionCount()).isZero();
        }

        @Test
        @DisplayName("baseDate 는 오늘이다 — 집계 결과의 기준을 응답이 밝힌다")
        void baseDate_isToday() {
            assertThat(adminStatsService.getOverview().baseDate()).isEqualTo(LocalDate.now());
        }
    }

    @Nested
    @DisplayName("상태별 분포")
    class StatusDistribution {

        @Test
        @DisplayName("0 건인 상태도 0 으로 나온다 — GROUP BY 가 안 만든 행을 채운다")
        void zeroCountStatuses_areFilledIn() {
            Member m = saveMember("hong", "hong@t.com");
            saveSession(m, todayStart.plusHours(1), Status.COMPLETED, "80.00");

            AdminStatsOverviewDto overview = adminStatsService.getOverview();

            assertThat(overview.sessionCountByStatus())
                    .containsOnlyKeys(Status.values())
                    .containsEntry(Status.COMPLETED, 1L)
                    .containsEntry(Status.IN_PROGRESS, 0L)
                    .containsEntry(Status.CANCELLED, 0L)
                    .containsEntry(Status.FAILED, 0L);
        }

        @Test
        @DisplayName("분포는 전체 기간이다 — 어제 세션도 포함된다")
        void distribution_coversAllTime() {
            Member m = saveMember("hong", "hong@t.com");
            saveSession(m, todayStart.minusDays(30), Status.FAILED, null);
            saveSession(m, todayStart.plusHours(1), Status.COMPLETED, "80.00");

            AdminStatsOverviewDto overview = adminStatsService.getOverview();

            assertThat(overview.sessionCountByStatus())
                    .containsEntry(Status.FAILED, 1L)
                    .containsEntry(Status.COMPLETED, 1L);
            // 오늘 세션 수와는 다른 값이어야 한다 — 둘이 같은 기간을 보면 위젯 하나가 무의미해진다
            assertThat(overview.todaySessionCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("평균 싱크로율")
    class AverageSyncRate {

        @Test
        @DisplayName("완료 세션이 없으면 null 이다 — 0.0 이면 '싱크로율 0%'라는 없는 사실이 생긴다")
        void noCompletedSession_returnsNull() {
            Member m = saveMember("hong", "hong@t.com");
            saveSession(m, todayStart.plusHours(1), Status.IN_PROGRESS, null);

            assertThat(adminStatsService.getOverview().todayAverageSyncRate()).isNull();
        }

        @Test
        @DisplayName("완료 세션만 평균에 들어간다")
        void onlyCompletedSessions_areAveraged() {
            Member m = saveMember("hong", "hong@t.com");
            saveSession(m, todayStart.plusHours(1), Status.COMPLETED, "80.00");
            saveSession(m, todayStart.plusHours(2), Status.COMPLETED, "60.00");
            // 실패 세션의 값은 확정값이 아니므로 평균을 끌어내리면 안 된다
            saveSession(m, todayStart.plusHours(3), Status.FAILED, "10.00");

            assertThat(adminStatsService.getOverview().todayAverageSyncRate())
                    .isCloseTo(70.0, within(0.01));
        }

        @Test
        @DisplayName("어제 완료 세션은 오늘 평균에 안 들어간다")
        void yesterdayCompleted_excluded() {
            Member m = saveMember("hong", "hong@t.com");
            saveSession(m, todayStart.minusHours(1), Status.COMPLETED, "10.00");
            saveSession(m, todayStart.plusHours(1), Status.COMPLETED, "90.00");

            assertThat(adminStatsService.getOverview().todayAverageSyncRate())
                    .isCloseTo(90.0, within(0.01));
        }
    }

    @Nested
    @DisplayName("활성 회원")
    class ActiveMembers {

        @Test
        @DisplayName("한 회원이 여러 번 운동해도 1 명으로 센다")
        void sameMemberMultipleSessions_countedOnce() {
            Member m = saveMember("hong", "hong@t.com");
            saveSession(m, todayStart.plusHours(1), Status.COMPLETED, "80.00");
            saveSession(m, todayStart.plusHours(2), Status.COMPLETED, "80.00");

            assertThat(adminStatsService.getOverview().activeMemberCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("판정 기간보다 오래된 세션만 있는 회원은 비활성이다")
        void memberOutsideWindow_notActive() {
            Member stale = saveMember("stale", "stale@t.com");
            Member recent = saveMember("recent", "recent@t.com");
            saveSession(stale, todayStart.minusDays(AdminStatsService.ACTIVE_MEMBER_WINDOW_DAYS + 1),
                    Status.COMPLETED, "80.00");
            saveSession(recent, todayStart.plusHours(1), Status.COMPLETED, "80.00");

            AdminStatsOverviewDto overview = adminStatsService.getOverview();

            assertThat(overview.activeMemberCount()).isEqualTo(1);
            // 응답이 판정 기간을 같이 밝혀야 "활성 1명"이 "최근 7일 1명"으로 읽힌다
            assertThat(overview.activeMemberWindowDays())
                    .isEqualTo(AdminStatsService.ACTIVE_MEMBER_WINDOW_DAYS);
        }

        @Test
        @DisplayName("세션이 하나도 없는 회원은 가입만으로는 활성이 아니다")
        void memberWithoutSession_notActive() {
            saveMember("lurker", "lurker@t.com");

            assertThat(adminStatsService.getOverview().activeMemberCount()).isZero();
        }
    }

    @Nested
    @DisplayName("신규 가입자")
    class NewMembers {

        @Test
        @DisplayName("오늘 가입자만 센다")
        void onlyTodayJoins_areCounted() {
            saveMember("today", "today@t.com");
            saveMember("old", "old@t.com");
            setCreatedAt("old", todayStart.minusSeconds(1));

            assertThat(adminStatsService.getOverview().todayNewMemberCount()).isEqualTo(1);
        }
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private Member saveMember(String username, String email) {
        return memberRepository.save(Member.builder()
                .username(username)
                .email(email)
                .password("encoded-password")
                .selectedPersona(SelectedPersona.BEGINNER)
                .build());
    }

    private void saveSession(Member member, LocalDateTime startTime, Status status, String avgSyncRate) {
        sessionRepository.save(Session.builder()
                .member(member)
                .exercise(squat)
                .startTime(startTime)
                .status(status)
                .avgSyncRate(avgSyncRate == null ? null : new BigDecimal(avgSyncRate))
                .build());
    }

    /** {@code @CreationTimestamp} 가 덮어쓴 가입일을 시나리오 값으로 되돌린다. */
    private void setCreatedAt(String username, LocalDateTime createdAt) {
        memberRepository.flush();
        em.createQuery("UPDATE Member m SET m.createdAt = :createdAt WHERE m.username = :username")
                .setParameter("createdAt", createdAt)
                .setParameter("username", username)
                .executeUpdate();
        em.clear();
    }
}
