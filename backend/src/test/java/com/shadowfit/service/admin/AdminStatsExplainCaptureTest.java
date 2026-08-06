package com.shadowfit.service.admin;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 대시보드 집계 5종의 실행 SQL 캡처 장치 ({@code admin-page-scope.md} §3-D).
 *
 * <p>{@code AdminMemberExplainCaptureTest} 와 같은 방식이다 — 아무것도 단언하지 않고, 실제
 * 리포지토리를 MySQL 상대로 실행시켜 서버 general log 에 <b>앱이 진짜 보내는 SQL</b> 을
 * 남긴다. 판정은 그 SQL 에 {@code EXPLAIN} 을 거는 쪽이 한다.
 *
 * <p><b>여기서 확인하려는 예측</b> — 목록(A·B)과 달리 집계는 인덱스로 범위를 줄여도 줄어든
 * 범위를 전부 읽어야 한다. 그래서 다섯 중 넷은 전수 스캔일 것으로 본다.
 *
 * <ul>
 *   <li>{@code countStartedBetween} — {@code idx_session_status_starttime} 은 선두가
 *       {@code status} 인데 상태 조건이 없다 → <b>못 탄다</b>고 예측</li>
 *   <li>{@code countGroupedByStatus} — 전체 기간 {@code GROUP BY} → 전수. 다만 위 인덱스의
 *       선두 컬럼이 {@code status} 라 <b>인덱스만 읽고</b> 셀 여지가 있다(커버링). 예측이
 *       갈리는 유일한 항목이라 가장 볼 만하다</li>
 *   <li>{@code averageSyncRateOfCompletedBetween} — 상태 + 기간이 둘 다 걸려 인덱스 선두부터
 *       맞는다 → <b>range 를 탄다</b>고 예측. 다만 {@code avg_sync_rate} 는 인덱스에 없어
 *       행 접근이 따라온다</li>
 *   <li>{@code countDistinctActiveMembersBetween} — 전수 + 중복 제거 → <b>가장 비싸다</b>고 예측</li>
 *   <li>{@code countJoinedBetween} — {@code idx_users_created_at} 단일 컬럼의 범위 조건 →
 *       <b>인덱스만 읽고 센다</b>고 예측. 다섯 중 유일하게 싼 항목일 것</li>
 * </ul>
 *
 * <p>⚠️ <b>예측은 예측이다.</b> A·B 측정에서 예측 4건이 반증됐다(§4-3·§4-4). 위 목록은
 * 관측을 대신하지 않는다 — 반증되면 그 사실을 문서에 남기는 것이 이 장치의 목적이다.
 *
 * <p><b>✅ 2026-08-06 측정 결과 — 위 예측 중 첫 항목이 반증됐다</b>(§4-5). {@code countStartedBetween}
 * 은 "선두가 {@code status} 인데 상태 조건이 없으니 못 탄다"고 봤는데, MySQL 8.0 의
 * <b>skip scan</b>({@code Using index for skip scan})이 걸려 100만 → 11만으로 접혔다. 선두
 * 컬럼의 distinct 값이 적으면 옵티마이저가 값마다 뒤 컬럼 범위 탐색을 반복한다.
 * {@code countDistinctActiveMembersBetween} 은 비싼 것은 맞았으나 고른 인덱스가 달랐다 —
 * {@code member_id} 선두를 정렬 순서로 읽어 <b>중복 제거를 공짜로</b> 만드는 쪽을 택했다.
 * 나머지 셋은 예측대로다. 시간까지 포함한 표는 §4-5.
 *
 * <p>[안전장치] {@code -Dexplain.capture=true} 없이는 실행되지 않는다. Docker MySQL 과 시딩을
 * 전제하므로 CI 와 일반 {@code ./gradlew test} 에서는 조용히 건너뛴다.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "explain.capture", matches = "true")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/shadowfit_explain?serverTimezone=Asia/Seoul",
        "spring.datasource.username=root",
        "spring.datasource.password=1234",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=never",
        // 스케줄러가 뜨면 IN_PROGRESS 세션을 FAILED 로 바꾼다 — 하필 이 장치의 집계 b(상태별
        // 분포)가 재는 대상이 그 값이다 (admin-page-scope.md §4-2 결함 #4).
        "scheduling.enabled=false"
})
@DisplayName("[측정 장치] 관리자 대시보드 집계 — 실행 SQL 캡처")
class AdminStatsExplainCaptureTest {

    /**
     * 시딩 데이터가 깔린 구간 안쪽의 하루.
     *
     * <p>실행일의 "오늘"을 쓰면 안 된다 — 시딩은 과거 365일에 뿌려져 있어 오늘 자 행이 0 건이고,
     * <b>0 건 조회는 실행 계획이 달라진다</b>(옵티마이저가 범위를 좁게 보고 다른 선택을 한다).
     * 재는 것은 "데이터가 있는 하루를 집계할 때의 계획"이므로 시딩 구간 안에서 고른다.
     */
    private static final LocalDate SEEDED_DAY = LocalDate.of(2025, 12, 1);

    private static final int ACTIVE_WINDOW_DAYS = AdminStatsService.ACTIVE_MEMBER_WINDOW_DAYS;

    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private EntityManager em;

    @Test
    @DisplayName("집계 5종을 순서대로 실행해 general log 에 남긴다")
    void captureAll() {
        LocalDateTime dayStart = SEEDED_DAY.atStartOfDay();
        LocalDateTime dayEnd = SEEDED_DAY.plusDays(1).atStartOfDay();

        capture("a_today_session_count", () -> sessionRepository.countStartedBetween(dayStart, dayEnd));
        capture("b_status_distribution", sessionRepository::countGroupedByStatus);
        capture("c_avg_sync_rate", () -> sessionRepository.averageSyncRateOfCompletedBetween(dayStart, dayEnd));
        capture("d_new_members", () -> memberRepository.countJoinedBetween(dayStart, dayEnd));
        capture("e_active_members", () -> sessionRepository.countDistinctActiveMembersBetween(
                dayEnd.minusDays(ACTIVE_WINDOW_DAYS), dayEnd));
    }

    /** 앞뒤로 마커를 흘려 general log 에서 어느 SQL 이 어느 집계의 것인지 구분되게 한다. */
    private void capture(String label, Runnable query) {
        marker(label + "_BEGIN");
        query.run();
        marker(label + "_END");
    }

    private void marker(String label) {
        em.createNativeQuery("SELECT '/*EXPLAIN_MARK*/" + label + "'").getSingleResult();
    }
}
