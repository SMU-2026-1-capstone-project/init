package com.shadowfit.repository.exercise;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.shadowfit.dto.admin.AdminSessionSearchCondition;
import com.shadowfit.dto.admin.AdminSessionSortKey;
import com.shadowfit.model.exercise.QExercise;
import com.shadowfit.model.exercise.QSession;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.QMember;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;

/**
 * 세션 목록 필터 조합별 실행 계획 측정용 <b>SQL 캡처 장치</b>
 * ({@code admin-page-scope.md} §4-4).
 *
 * <p>{@code AdminMemberExplainCaptureTest} 와 같은 장치이고 대상만 다르다 — 왜 SQL 을 손으로
 * 쓰지 않는지, 왜 H2 가 아니라 MySQL 인지, 왜 시스템 프로퍼티 없이는 실행되지 않는지는 그쪽
 * 주석에 있다.
 *
 * <p><b>여기서만 물어보는 것</b> — 회원 목록은 단일 테이블이라 옵티마이저가 고를 게 없었다.
 * 세션 목록은 검색어가 조인 너머({@code users.username})에 있어서, <b>세션부터 읽고 회원을
 * 붙일지 / 회원을 먼저 걸러 그 회원들의 세션을 찾을지</b>를 옵티마이저가 고른다. 조합 (d)
 * 상태+검색어가 그 선택이 갈리는 지점이라 이 캡처의 핵심이다.
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
        // 측정 대상 데이터를 지키는 스위치. 이게 없으면 SessionTimeoutScheduler 가 같이 떠서
        // 시딩된 IN_PROGRESS 세션을 FAILED 로 바꾼다 — 재는 동안 재는 대상이 변한다
        // (admin-page-scope.md §4-2 결함 #4).
        "scheduling.enabled=false"
})
@DisplayName("[측정 장치] 관리자 세션 목록 — 필터 조합별 실행 SQL 캡처")
class AdminSessionExplainCaptureTest {

    /** 시딩 데이터(1년치) 안쪽 구간. */
    private static final LocalDate FROM = LocalDate.of(2025, 9, 1);
    private static final LocalDate TO = LocalDate.of(2025, 12, 1);

    /** 시딩에서 100명당 1명의 username 에 심어둔 토큰. 회원 기준 선택도 1%. */
    private static final String KEYWORD = "kim";

    @Autowired private SessionQueryRepository sessionQueryRepository;
    @Autowired private EntityManager em;

    @Test
    @DisplayName("5개 조합을 순서대로 실행해 general log 에 남긴다")
    void captureAll() {
        capture("s_a_status_only", new AdminSessionSearchCondition(Status.FAILED, null, null, null, null));
        capture("s_b_status_period", new AdminSessionSearchCondition(Status.FAILED, null, FROM, TO, null));
        capture("s_c_keyword_only", new AdminSessionSearchCondition(null, null, null, null, KEYWORD));
        capture("s_d_status_keyword", new AdminSessionSearchCondition(Status.FAILED, null, null, null, KEYWORD));
        capture("s_e_no_filter", new AdminSessionSearchCondition(null, null, null, null, null));

        // 반사실 — 조건부 조인이 없었다면 총건수 쿼리가 어떤 모양이었을지 (§4-4 미측정 항목)
        captureAlwaysJoinCount("s_x_alwaysjoin_status", Status.FAILED, null);
        captureAlwaysJoinCount("s_y_alwaysjoin_status_keyword", Status.FAILED, KEYWORD);
    }

    /**
     * <b>반사실 캡처</b> — {@code countOf} 가 조인을 조건부로 붙이지 <b>않았다면</b> 나갔을 SQL.
     *
     * <p>왜 필요한가: 현행 구현은 총건수에서 {@code exercise} 를 항상 빼고 {@code member} 는
     * 검색어가 있을 때만 붙인다({@link SessionQueryRepositoryImpl#countOf}). 그 최적화가 실제로
     * 값을 하는지는 <b>재본 적이 없다</b> — 옵티마이저가 애초에 그 조인을 무시했다면 우리가 손으로
     * 뺀 것은 같은 계획을 낳는 헛수고다({@code admin-page-scope.md} §4-4).
     *
     * <p><b>왜 SQL 을 손으로 쓰지 않고 여기서 만드는가.</b> 이 rig 의 전제는 "재는 쿼리 = 앱이
     * 보내는 쿼리"다(§4-3 측정 장치). 반사실은 앱이 보내지 않는 쿼리라 캡처할 곳이 없지만, 손으로
     * 쓰면 <b>비교 대상 두 개 중 하나만 QueryDSL 산물</b>이 되어 조인 외의 차이(별칭·컬럼 표기)가
     * 섞인다. 같은 {@code JPAQueryFactory} 로 만들면 조인만 다른 짝이 된다.
     *
     * <p>⚠️ {@code whereOf} 가 private 이라 조건은 여기서 다시 짠다. 다만 이 반사실이 쓰는 조건은
     * 상태·검색어 둘뿐이고, 둘 다 아래 한 줄짜리라 본체와 어긋날 여지가 작다. 필터를 늘린다면
     * 이쪽도 같이 손봐야 한다.
     */
    private void captureAlwaysJoinCount(String label, Status status, String keyword) {
        QSession s = QSession.session;
        QMember m = QMember.member;
        QExercise e = QExercise.exercise;

        BooleanExpression statusEq = status == null ? null : s.status.eq(status);
        BooleanExpression keywordContains = keyword == null ? null
                : m.username.contains(keyword).or(m.email.contains(keyword));

        marker(label + "_BEGIN");
        new JPAQueryFactory(em)
                .select(s.count())
                .from(s)
                .join(s.member, m)        // 현행은 검색어가 있을 때만 붙인다
                .join(s.exercise, e)      // 현행은 절대 안 붙인다
                .where(statusEq, keywordContains)
                .fetchOne();
        marker(label + "_END");
    }

    /**
     * 조합 하나를 실행한다. 앞뒤 마커로 general log 에서 경계를 잡는다.
     *
     * <p>기본 정렬({@code startTime DESC})과 첫 페이지를 쓴다 — 관리자 화면의 기본 진입이고,
     * §4-1 의 {@code idx_session_status_starttime} 도 그 경로를 겨냥해 넣은 것이다.
     */
    private void capture(String label, AdminSessionSearchCondition condition) {
        marker(label + "_BEGIN");
        sessionQueryRepository.searchForAdmin(condition, AdminSessionSortKey.START_TIME, false, 0, 20);
        marker(label + "_END");
    }

    private void marker(String label) {
        em.createNativeQuery("SELECT '/*EXPLAIN_MARK*/" + label + "'").getSingleResult();
    }
}
