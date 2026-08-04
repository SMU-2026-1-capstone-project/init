package com.shadowfit.repository.exercise;

import com.shadowfit.dto.admin.AdminSessionSearchCondition;
import com.shadowfit.dto.admin.AdminSessionSortKey;
import com.shadowfit.model.exercise.Status;
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
        "spring.sql.init.mode=never"
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
