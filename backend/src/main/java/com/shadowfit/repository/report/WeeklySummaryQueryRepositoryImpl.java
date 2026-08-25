package com.shadowfit.repository.report;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.shadowfit.dto.report.weekly.RepCurvePointDto;
import com.shadowfit.dto.report.weekly.WeeklyTotalsDto;
import com.shadowfit.dto.report.weekly.WorstRepFrequencyDto;
import com.shadowfit.model.exercise.QSession;
import com.shadowfit.model.exercise.Status;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 주간 요약 집계 구현 — A층·B층 두 갈래다.
 *
 * <p><b>A층</b>({@link #totalsBetween}) — {@code exercise_sessions} 한 표 안에서 끝난다.
 * QueryDSL 로 짠다.
 *
 * <p><b>왜 쿼리 한 번인가</b> — 다섯 값이 전부 같은 행 집합의 집계라 갈라 놓을 이유가 없다.
 * 갈라 놓으면 같은 범위를 다섯 번 스캔한다.
 *
 * <p><b>왜 평균을 DB 에서 나누지 않고 분자·분모를 따로 받나</b> — {@code SUM(a*b)/SUM(b)} 를
 * SQL 안에서 나누면 분모가 0 인 경우를 DB 방언마다 다르게 다루고(0 나누기 · NULL), 반올림
 * 자릿수도 방언에 끌려간다. 분자와 분모를 그대로 받아 <b>자바에서 한 번만</b> 나눈다 —
 * 경계 처리(회차 0 · 완료 세션 0)가 한 곳에 모인다.
 *
 * <p>🔴 <b>{@code status = COMPLETED} 만 센다.</b> {@code IN_PROGRESS}·{@code FAILED}·
 * {@code CANCELLED} 는 {@code avg_sync_rate} 가 없거나(측정 전) 신뢰할 수 없다. 특히
 * 타임아웃으로 {@code FAILED} 된 세션은 «운동을 안 한 것» 이 아니라 «끝맺음이 안 된 것» 인데,
 * 그 구분을 여기서 하지 않는다 — 세면 주간 평균이 조용히 내려간다.
 *
 * <p><b>B층</b>({@link #repCurveBetween}, {@link #worstRepDistributionBetween}) —
 * {@code reports.detailed_analysis}(JSON) 를 {@code JSON_TABLE} 로 펼친다. QueryDSL 은
 * {@code JSON_TABLE} 을 표현하지 못해 네이티브 쿼리로 내린다. {@code reports} 는 세션 완료 시점에만
 * 만들어지므로({@code SessionService.precomputeReport}) 세션 상태를 따로 거르지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class WeeklySummaryQueryRepositoryImpl implements WeeklySummaryQueryRepository {

    private static final QSession session = QSession.session;

    private final JPAQueryFactory queryFactory;
    private final EntityManager entityManager;

    @Override
    public WeeklyTotalsDto totalsBetween(Long memberId, LocalDateTime from, LocalDateTime to) {
        // 회차 가중의 분자 SUM(avg_sync_rate * total_reps).
        // 🔴 avg_sync_rate 가 null 인 세션(= 측정된 회차가 하나도 없는 세션)은 곱이 null 이 되고
        //    SQL 의 SUM 은 null 을 건너뛴다. 그래서 분자에서는 «저절로» 빠지는데, 분모를 그냥
        //    SUM(total_reps) 로 두면 그 세션의 회차가 분모에만 남아 평균이 조용히 아래로 끌린다.
        //    분모도 같은 행 집합이어야 짝이 맞는다 — 아래 CaseBuilder 가 그 일을 한다.
        NumberExpression<BigDecimal> weightedNumerator =
                session.avgSyncRate.multiply(session.totalReps).sum();

        // 분모 — «평균값이 있는» 세션의 회차만 센다. 위 분자와 같은 행 집합이어야 한다.
        NumberExpression<Integer> weightedDenominator =
                new com.querydsl.core.types.dsl.CaseBuilder()
                        .when(session.avgSyncRate.isNotNull()).then(session.totalReps)
                        .otherwise(0)
                        .sum();

        // COUNT(DISTINCT 날짜) — 「운동한 날 수」. 같은 날 두 세션은 하루다.
        // QueryDSL 에 DATE() 가 없어 템플릿으로 내린다. 🔴 MySQL 의 date() 가 아니라
        // 표준 cast 를 쓴다 — 통합 테스트가 H2(MySQL 모드)에서 돌기 때문이다. 둘 다에서 같은
        // 뜻이고, 어차피 함수를 씌운 컬럼이라 인덱스를 타지 못하는 것도 동일하다.
        NumberExpression<Long> activeDays = Expressions
                .dateTemplate(LocalDate.class, "cast({0} as date)", session.startTime)
                .countDistinct();

        Tuple row = queryFactory
                .select(session.count(),
                        session.totalReps.sum(),
                        weightedNumerator,
                        weightedDenominator,
                        session.avgSyncRate.avg(),
                        activeDays)
                .from(session)
                .where(session.member.id.eq(memberId),
                        session.status.eq(Status.COMPLETED),
                        session.startTime.goe(from),
                        session.startTime.lt(to))
                .fetchOne();

        if (row == null) {
            return WeeklyTotalsDto.empty();
        }

        long sessions = orZero(row.get(session.count()));
        if (sessions == 0) {
            // 집계 쿼리는 행이 없어도 한 줄(0, null, ...)을 돌려준다. 그 줄을 그대로 매핑하면
            // 「세션 0인데 평균 0.00」 이라는 거짓이 만들어진다 — 비어 있음은 비어 있음으로 낸다.
            return WeeklyTotalsDto.empty();
        }

        long totalReps = orZero(row.get(session.totalReps.sum()));
        BigDecimal repWeighted = divide(row.get(weightedNumerator), row.get(weightedDenominator));
        BigDecimal sessionWeighted = scale(row.get(session.avgSyncRate.avg()));

        return new WeeklyTotalsDto(sessions, totalReps, repWeighted, sessionWeighted,
                orZero(row.get(activeDays)));
    }

    /** 분모가 0·null 이면 «측정된 회차가 없다» 는 뜻이라 평균이 존재하지 않는다. */
    private BigDecimal divide(BigDecimal numerator, Integer denominator) {
        if (numerator == null || denominator == null || denominator == 0) return null;
        return numerator.divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal scale(Double value) {
        if (value == null) return null;
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private long orZero(Number value) {
        return value == null ? 0L : value.longValue();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<RepCurvePointDto> repCurveBetween(Long memberId, LocalDateTime from, LocalDateTime to) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT jt.rep_number, AVG(jt.sync_rate), COUNT(*)
                  FROM reports r
                  JOIN exercise_sessions s ON s.id = r.session_id
                 CROSS JOIN JSON_TABLE(r.detailed_analysis, '$.repTrend[*]'
                        COLUMNS (rep_number INT PATH '$.repNumber',
                                 sync_rate DOUBLE PATH '$.syncRate')) jt
                 WHERE r.member_id = :memberId
                   AND s.start_time >= :from AND s.start_time < :to
                 GROUP BY jt.rep_number
                 ORDER BY jt.rep_number
                """)
                .setParameter("memberId", memberId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        return rows.stream()
                .map(row -> new RepCurvePointDto(
                        ((Number) row[0]).intValue(),
                        BigDecimal.valueOf(((Number) row[1]).doubleValue()).setScale(2, RoundingMode.HALF_UP),
                        ((Number) row[2]).longValue()))
                .toList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<WorstRepFrequencyDto> worstRepDistributionBetween(Long memberId, LocalDateTime from, LocalDateTime to) {
        // «국면» 이 아니라 «회차» 다 — WorstSectionDto 에 국면 이름표가 없다(#80).
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT jt.worst_rep, COUNT(*)
                  FROM reports r
                  JOIN exercise_sessions s ON s.id = r.session_id
                 CROSS JOIN JSON_TABLE(r.detailed_analysis, '$'
                        COLUMNS (worst_rep INT PATH '$.worstSection.repNumber')) jt
                 WHERE r.member_id = :memberId
                   AND s.start_time >= :from AND s.start_time < :to
                   AND jt.worst_rep IS NOT NULL
                 GROUP BY jt.worst_rep
                 ORDER BY COUNT(*) DESC, jt.worst_rep ASC
                """)
                .setParameter("memberId", memberId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        return rows.stream()
                .map(row -> new WorstRepFrequencyDto(
                        ((Number) row[0]).intValue(),
                        ((Number) row[1]).longValue()))
                .toList();
    }
}
