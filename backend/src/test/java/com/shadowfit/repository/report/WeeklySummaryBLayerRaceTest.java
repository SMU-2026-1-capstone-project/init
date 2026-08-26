package com.shadowfit.repository.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.report.detailreport.RepSyncRateDto;
import com.shadowfit.dto.report.detailreport.SessionDetailedAnalysis;
import com.shadowfit.dto.report.detailreport.WorstSectionDto;
import com.shadowfit.dto.report.weekly.RepCurvePointDto;
import com.shadowfit.dto.report.weekly.WorstRepFrequencyDto;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.model.report.Report;
import com.shadowfit.model.report.ReportType;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주간 요약 «B층» 집계 쿼리 테스트 — {@code JSON_TABLE} 이 실제 MySQL 에서 맞게 나오는가.
 *
 * <p><b>왜 H2(기본 테스트 프로파일)로는 안 되는가.</b> H2 2.3.232(이 프로젝트의 테스트 런타임)는
 * {@code JSON_TABLE} 함수를 구현하지 않는다 — {@code MODE=MySQL} 을 줘도 마찬가지다
 * (실측: {@code Function "JSON_TABLE" not found}). {@link WeeklySummaryQueryRepositoryImpl}
 * 이 실제로 짜는 SQL 은 로컬 {@code shadowfit-mysql}(8.0.46) 컨테이너에 {@code EXPLAIN} 으로
 * 직접 검증했다 — 두 조회 모두 스키마·조인·경로가 유효하고 {@code Table function: json_table}
 * 로 계획에 잡힌다. 이 테스트는 그 결과를 <b>값 단위</b>로 자동 검증한다.
 *
 * <p>{@code DailyLogRepository.upsertStats} 가 이미 같은 이유로 겪은 문제다 — «ON DUPLICATE KEY
 * UPDATE ... AS new» 문법도 H2 파서가 거부해서 그 메서드는 테스트가 아예 없다. 여기서는 대신
 * 이 프로젝트가 이미 갖고 있는 «race» 프로파일(실제 MySQL, {@code PoseDataOrphanRaceTest} 등)에
 * 얹는다 — CI(H2 전용)는 이 클래스를 건드리지 않고 조용히 건너뛴다.
 *
 * <p><b>실행법</b> — 시스템 프로퍼티가 없으면 통째로 건너뛰므로 CI 는 영향받지 않는다:
 * <pre>
 *   docker run -d --name shadowfit-race-mysql -e MYSQL_ROOT_PASSWORD=racetest \
 *     -e MYSQL_DATABASE=shadowfit -p 3307:3306 mysql:8.0 \
 *     --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
 *   for f in backend/src/main/resources/db/migration/V*.sql; do
 *     docker exec -i shadowfit-race-mysql mysql -uroot -pracetest shadowfit &lt; "$f"; done
 *   ./gradlew :backend:test --tests '*WeeklySummaryBLayerRaceTest' -Drace.mysql=true
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("race")
@EnabledIfSystemProperty(named = "race.mysql", matches = "true",
        disabledReason = "실제 MySQL(3307)이 필요 — 클래스 주석의 docker 명령 참고")
@DisplayName("주간 B층 집계 쿼리 (JSON_TABLE, 실 MySQL)")
class WeeklySummaryBLayerRaceTest {

    @Autowired private WeeklySummaryQueryRepository repository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private com.shadowfit.repository.exercise.CategoryRepository categoryRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private ReportRepository reportRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 2026-08-17(월) 00:00 ~ 08-24(월) 00:00 — 반열린 구간. A층 테스트와 같은 창을 쓴다. */
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 8, 17, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 8, 24, 0, 0);

    private Member member;
    private Member otherMember;
    private Exercise exercise;

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(Member.builder()
                .email("weekly-blayer@test.com").username("주간B층사용자").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        otherMember = memberRepository.saveAndFlush(Member.builder()
                .email("weekly-blayer-other@test.com").username("B층남").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00")).build());
    }

    @AfterEach
    void tearDown() {
        // ddl-auto: none 프로파일이라 @Transactional 롤백에 기대지 않고 직접 지운다
        // (SignupUsernameRaceTest 와 같은 이유). FK 순서대로 자식부터 지운다.
        jdbcTemplate.update("DELETE FROM reports WHERE member_id IN (?, ?)", member.getId(), otherMember.getId());
        jdbcTemplate.update("DELETE FROM exercise_sessions WHERE member_id IN (?, ?)",
                member.getId(), otherMember.getId());
        jdbcTemplate.update("DELETE FROM exercises WHERE id = ?", exercise.getId());
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", member.getId(), otherMember.getId());
    }

    /** A층(세션)과 B층({@code reports.detailed_analysis})을 같이 심는다 — B층 쿼리는 이 조합이 있어야 나온다. */
    private void seedWithReport(Member owner, LocalDateTime startTime, WorstSectionDto worstSection,
                                List<RepSyncRateDto> repTrend) {
        Session s = sessionRepository.saveAndFlush(Session.builder()
                .member(owner)
                .exercise(exercise)
                .startTime(startTime)
                .status(Status.COMPLETED)
                .totalReps(repTrend.size())
                .avgSyncRate(new BigDecimal("80.00"))
                .build());
        Report report = new Report();
        report.setMember(owner);
        report.setSession(s);
        report.setReportType(ReportType.SESSION);
        try {
            report.setDetailedAnalysis(
                    objectMapper.writeValueAsString(new SessionDetailedAnalysis(worstSection, repTrend)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        reportRepository.saveAndFlush(report);
    }

    private static RepSyncRateDto rep(int repNumber, double syncRate) {
        return new RepSyncRateDto(repNumber, syncRate, "00:%02d".formatted(repNumber));
    }

    private static WorstSectionDto worst(int repNumber) {
        WorstSectionDto dto = new WorstSectionDto();
        dto.setRepNumber(repNumber);
        dto.setExerciseName("스쿼트");
        dto.setTimeStamp("00:%02d".formatted(repNumber));
        dto.setReason("%d회차".formatted(repNumber));
        return dto;
    }

    @Test
    @DisplayName("회차별 평균이 세션을 넘어 접힌다")
    void 회차_곡선() {
        // 세션 A: 1회차 90, 2회차 70 / 세션 B: 1회차 80, 2회차 60
        // → 1회차 평균 85(표본 2), 2회차 평균 65(표본 2)
        seedWithReport(member, FROM.withHour(9), worst(2), List.of(rep(1, 90.0), rep(2, 70.0)));
        seedWithReport(member, FROM.plusDays(1).withHour(9), worst(2), List.of(rep(1, 80.0), rep(2, 60.0)));

        List<RepCurvePointDto> curve = repository.repCurveBetween(member.getId(), FROM, TO);

        assertThat(curve).extracting(RepCurvePointDto::repNumber).containsExactly(1, 2);
        assertThat(curve.get(0).avgSyncRate()).isEqualByComparingTo("85.00");
        assertThat(curve.get(0).sampleCount()).isEqualTo(2);
        assertThat(curve.get(1).avgSyncRate()).isEqualByComparingTo("65.00");
    }

    @Test
    @DisplayName("기간 밖 세션의 회차는 곡선에 안 섞인다")
    void 회차_곡선_기간_필터() {
        seedWithReport(member, FROM.withHour(9), worst(1), List.of(rep(1, 90.0)));
        seedWithReport(member, FROM.minusDays(1).withHour(9), worst(1), List.of(rep(1, 10.0))); // 지난주

        List<RepCurvePointDto> curve = repository.repCurveBetween(member.getId(), FROM, TO);

        assertThat(curve).hasSize(1);
        assertThat(curve.get(0).avgSyncRate()).isEqualByComparingTo("90.00");
    }

    @Test
    @DisplayName("worst 회차 분포가 빈도 내림차순으로 나온다")
    void worst_분포() {
        seedWithReport(member, FROM.withHour(9), worst(3), List.of(rep(1, 90.0), rep(3, 70.0)));
        seedWithReport(member, FROM.plusDays(1).withHour(9), worst(3), List.of(rep(1, 88.0), rep(3, 72.0)));
        seedWithReport(member, FROM.plusDays(2).withHour(9), worst(1), List.of(rep(1, 60.0), rep(3, 90.0)));

        List<WorstRepFrequencyDto> distribution = repository.worstRepDistributionBetween(member.getId(), FROM, TO);

        assertThat(distribution).hasSize(2);
        assertThat(distribution.get(0).repNumber()).isEqualTo(3);
        assertThat(distribution.get(0).count()).isEqualTo(2);
        assertThat(distribution.get(1).repNumber()).isEqualTo(1);
        assertThat(distribution.get(1).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("worst 가 없는 세션(측정된 회차 0건)은 분포에서 빠진다")
    void worst_없는_세션은_제외() {
        seedWithReport(member, FROM.withHour(9), null, List.of());

        List<WorstRepFrequencyDto> distribution = repository.worstRepDistributionBetween(member.getId(), FROM, TO);

        assertThat(distribution).isEmpty();
    }

    @Test
    @DisplayName("남의 세션은 곡선·분포 어느 쪽에도 안 섞인다")
    void 소유권() {
        seedWithReport(otherMember, FROM.withHour(9), worst(1), List.of(rep(1, 10.0)));

        assertThat(repository.repCurveBetween(member.getId(), FROM, TO)).isEmpty();
        assertThat(repository.worstRepDistributionBetween(member.getId(), FROM, TO)).isEmpty();
    }
}
