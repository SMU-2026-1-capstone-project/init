package com.shadowfit.repository.report;

import com.shadowfit.dto.report.weekly.WeeklyTotalsDto;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주간 «A층» 집계 쿼리 테스트.
 *
 * <p><b>왜 이 테스트가 따로 필요한가</b> — 서비스 테스트는 리포지토리를 목으로 막아서 «주 경계»만
 * 본다. 정작 이 판의 알맹이인 <b>가중 평균이 실제 SQL 에서 맞게 나오는가</b>는 거기서 안 걸린다.
 *
 * <p>🔴 <b>그리고 실 데이터로는 이 검증이 안 된다.</b> 로컬 시드는 한 주의 여섯 세션이 전부
 * {@code total_reps=25 · avg_sync_rate=75.00} 로 <b>똑같다</b>. 두 평균의 차이가 0.000 으로
 * 나오는데 그건 「차이가 없다」가 아니라 <b>「이 데이터로는 못 잰다」</b>다
 * ([[project_synthetic_data_distribution_limit]]). 그래서 여기서는 회차 수와 점수가 <b>서로 다른</b>
 * 세션을 손으로 심는다 — 식이 갈리는지를 보려면 갈릴 수 있는 데이터가 있어야 한다.
 */
@SpringBootTest
@Transactional
@DisplayName("주간 A층 집계 쿼리")
class WeeklySummaryQueryRepositoryTest {

    @Autowired private WeeklySummaryQueryRepository repository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private com.shadowfit.repository.exercise.CategoryRepository categoryRepository;
    @Autowired private SessionRepository sessionRepository;

    /** 2026-08-17(월) 00:00 ~ 08-24(월) 00:00 — 반열린 구간 */
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 8, 17, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 8, 24, 0, 0);

    private Member member;
    private Member otherMember;
    private Exercise exercise;

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(Member.builder()
                .email("weekly@test.com").username("주간사용자").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        otherMember = memberRepository.saveAndFlush(Member.builder()
                .email("weekly-other@test.com").username("남").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00")).build());
    }

    private void seed(Member owner, LocalDateTime startTime, Status status, int reps, String syncRate) {
        sessionRepository.saveAndFlush(Session.builder()
                .member(owner)
                .exercise(exercise)
                .startTime(startTime)
                .status(status)
                .totalReps(reps)
                .avgSyncRate(syncRate == null ? null : new BigDecimal(syncRate))
                .build());
    }

    @Test
    @DisplayName("회차 가중과 세션 가중이 «다르게» 나온다 — 그 차이가 이 판의 측정 결과다")
    void 두_평균이_갈린다() {
        // 회차 수가 서로 다른 세 세션. 손으로 계산하면
        //   회차 가중 = (90×10 + 80×5 + 85×20) / 35 = 3000/35 = 85.714... → 85.71
        //   세션 가중 = (90 + 80 + 85) / 3          = 85.00
        seed(member, FROM.plusDays(0).withHour(20), Status.COMPLETED, 10, "90.00");
        seed(member, FROM.plusDays(2).withHour(20), Status.COMPLETED, 5, "80.00");
        seed(member, FROM.plusDays(4).withHour(20), Status.COMPLETED, 20, "85.00");

        WeeklyTotalsDto totals = repository.totalsBetween(member.getId(), FROM, TO);

        assertThat(totals.sessions()).isEqualTo(3);
        assertThat(totals.totalReps()).isEqualTo(35);
        assertThat(totals.repWeightedSyncRate()).isEqualByComparingTo("85.71");
        assertThat(totals.sessionWeightedSyncRate()).isEqualByComparingTo("85.00");
        assertThat(totals.weightingGap()).isEqualByComparingTo("0.71");
        assertThat(totals.activeDays()).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 날 두 번 운동해도 «운동한 날» 은 하루다")
    void 같은_날은_하루() {
        seed(member, FROM.withHour(9), Status.COMPLETED, 10, "80.00");
        seed(member, FROM.withHour(21), Status.COMPLETED, 10, "90.00");

        WeeklyTotalsDto totals = repository.totalsBetween(member.getId(), FROM, TO);

        assertThat(totals.sessions()).isEqualTo(2);
        assertThat(totals.activeDays()).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 측정된 회차가 없는 세션은 «분모에서도» 빠진다 — 안 그러면 평균이 조용히 내려간다")
    void 측정_안된_세션이_평균을_안_끌어내린다() {
        seed(member, FROM.withHour(20), Status.COMPLETED, 10, "90.00");
        // avg_sync_rate 가 null 인데 total_reps 는 20 인 세션. 분모에 남으면
        // 900/30 = 30.00 이라는 엉뚱한 평균이 나온다.
        seed(member, FROM.plusDays(1).withHour(20), Status.COMPLETED, 20, null);

        WeeklyTotalsDto totals = repository.totalsBetween(member.getId(), FROM, TO);

        assertThat(totals.sessions()).isEqualTo(2);
        assertThat(totals.totalReps()).isEqualTo(30);          // 총 회차에는 그대로 센다(사실이다)
        assertThat(totals.repWeightedSyncRate()).isEqualByComparingTo("90.00");  // 평균은 안 끌린다
    }

    @Test
    @DisplayName("완료되지 않은 세션은 세지 않는다")
    void 완료만_센다() {
        seed(member, FROM.withHour(20), Status.COMPLETED, 10, "90.00");
        seed(member, FROM.plusDays(1).withHour(20), Status.IN_PROGRESS, 5, null);
        seed(member, FROM.plusDays(2).withHour(20), Status.FAILED, 5, "50.00");
        seed(member, FROM.plusDays(3).withHour(20), Status.CANCELLED, 5, "50.00");

        WeeklyTotalsDto totals = repository.totalsBetween(member.getId(), FROM, TO);

        assertThat(totals.sessions()).isEqualTo(1);
        assertThat(totals.repWeightedSyncRate()).isEqualByComparingTo("90.00");
    }

    @Test
    @DisplayName("남의 세션은 안 센다")
    void 소유권() {
        seed(member, FROM.withHour(20), Status.COMPLETED, 10, "90.00");
        seed(otherMember, FROM.withHour(20), Status.COMPLETED, 10, "10.00");

        WeeklyTotalsDto totals = repository.totalsBetween(member.getId(), FROM, TO);

        assertThat(totals.sessions()).isEqualTo(1);
        assertThat(totals.repWeightedSyncRate()).isEqualByComparingTo("90.00");
    }

    @Test
    @DisplayName("구간은 시작 포함·끝 미포함 — 경계의 세션이 두 주에 겹치거나 새지 않는다")
    void 반열린_구간() {
        seed(member, FROM, Status.COMPLETED, 10, "90.00");                    // 포함
        seed(member, TO.minusSeconds(1), Status.COMPLETED, 10, "80.00");      // 포함(일요일 23:59:59)
        seed(member, TO, Status.COMPLETED, 10, "10.00");                      // 미포함(다음 주 월 00:00)
        seed(member, FROM.minusSeconds(1), Status.COMPLETED, 10, "10.00");    // 미포함(지난주 끝)

        WeeklyTotalsDto totals = repository.totalsBetween(member.getId(), FROM, TO);

        assertThat(totals.sessions()).isEqualTo(2);
        assertThat(totals.repWeightedSyncRate()).isEqualByComparingTo("85.00");
    }

    @Test
    @DisplayName("기록이 없으면 «0점» 이 아니라 «비어 있음» 으로 낸다")
    void 기록_없음() {
        WeeklyTotalsDto totals = repository.totalsBetween(member.getId(), FROM, TO);

        assertThat(totals.isEmpty()).isTrue();
        assertThat(totals.sessions()).isZero();
        // 🔴 0.00 이 아니라 null 이어야 한다 — 「측정 안 됨」이 「싱크로율 0점」으로 둔갑하면
        //    월 평균 같은 상위 집계가 조용히 내려간다(SessionService.resolveSyncStats 와 같은 이유).
        assertThat(totals.repWeightedSyncRate()).isNull();
        assertThat(totals.sessionWeightedSyncRate()).isNull();
        assertThat(totals.weightingGap()).isNull();
    }
}
