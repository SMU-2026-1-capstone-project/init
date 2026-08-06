package com.shadowfit.repository.member;

import com.shadowfit.dto.admin.AdminMemberSearchCondition;
import com.shadowfit.dto.admin.AdminMemberSortKey;
import com.shadowfit.model.member.SelectedPersona;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;

/**
 * 필터 조합별 실행 계획 측정용 <b>SQL 캡처 장치</b> ({@code admin-page-scope.md} §4-3).
 *
 * <p><b>이건 테스트가 아니다.</b> 아무것도 단언하지 않는다. 하는 일은 하나 —
 * {@link MemberQueryRepositoryImpl} 을 조합별로 한 번씩 실행시켜 <b>MySQL 서버에 실제로 도착한
 * SQL</b> 을 general log 에 남기는 것이다. 판정은 그 SQL 에 {@code EXPLAIN} 을 거는
 * {@code loadtest/measure_admin_filter_explain.sh} 가 한다.
 *
 * <p>[왜 SQL 을 손으로 안 쓰는가] §4-3 이 남긴 절차의 1번은 {@code show-sql} 이 값을 {@code ?}
 * 로 찍어 손으로 채워야 한다는 것이었다. 손으로 채우면 <b>측정한 쿼리가 앱이 실제로 보내는
 * 쿼리와 같다는 보증이 사라진다</b> — 그 보증이 이 측정의 전부다. Connector/J 는 기본값
 * ({@code useServerPrepStmts=false})에서 클라이언트 측에서 값을 채워 보내므로, 서버 general log
 * 에는 값이 박힌 완성된 SQL 이 남는다. 그걸 그대로 {@code EXPLAIN} 에 건다.
 *
 * <p>[왜 H2 가 아닌 MySQL 인가] 나머지 테스트는 H2(MySQL 모드)를 쓴다. 그런데 Hibernate 의
 * SQL 생성은 dialect 에 달려 있어 — 페이징 문법, {@code lower()} 처리 등 — H2 에서 뽑은 SQL 은
 * MySQL 이 받는 SQL 이 아니다. 실행 계획을 재는 마당에 다른 SQL 을 재면 의미가 없으므로
 * 데이터소스를 스크래치 DB {@code shadowfit_explain} 으로 갈아끼운다.
 *
 * <p>[안전장치] {@code -Dexplain.capture=true} 없이는 <b>실행되지 않는다.</b> Docker MySQL 과
 * 20만 행 시딩을 전제하므로 CI 와 일반 {@code ./gradlew test} 에서는 조용히 건너뛴다.
 * {@code ddl-auto=none} 도 같은 이유다 — 테스트 기본값인 {@code create-drop} 이 그대로 걸리면
 * 시딩한 20만 행을 스키마째 날린다.
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
        // 스케줄러가 뜨면 측정 대상 데이터를 UPDATE 한다 (admin-page-scope.md §4-2 결함 #4).
        // 회원 목록 측정에는 세션이 안 걸리지만, 같은 스크래치 DB 를 B·D 와 공유하므로 여기서도 끈다.
        "scheduling.enabled=false"
})
@DisplayName("[측정 장치] 관리자 회원 목록 — 필터 조합별 실행 SQL 캡처")
class AdminMemberExplainCaptureTest {

    /** 시딩 데이터가 깔린 구간 안쪽. 전체(365일)의 대략 1/4 을 무는 범위로 잡는다. */
    private static final LocalDate FROM = LocalDate.of(2025, 9, 1);
    private static final LocalDate TO = LocalDate.of(2025, 12, 1);

    /** 시딩에서 100명당 1명의 username 에 심어둔 토큰. 선택도 1%. */
    private static final String KEYWORD = "kim";

    @Autowired private MemberQueryRepository memberQueryRepository;
    @Autowired private EntityManager em;

    @Test
    @DisplayName("6개 조합을 순서대로 실행해 general log 에 남긴다")
    void captureAll() {
        // (a)~(d) 는 §4-3 이 지정한 필수 4종, (e)·(f) 는 enum 필터가 실제로 어떤 계획을 타는지
        // 보려고 덧붙였다 — "선택도가 낮아 인덱스에서 뺐다"는 판단이 계획에 어떻게 드러나는지가
        // 예측이 아니라 관측으로 남아야 한다.
        capture("a_joined_only", new AdminMemberSearchCondition(null, null, null, null, FROM, TO));
        capture("b_keyword_only", new AdminMemberSearchCondition(KEYWORD, null, null, null, null, null));
        capture("c_joined_keyword", new AdminMemberSearchCondition(KEYWORD, null, null, null, FROM, TO));
        capture("d_no_filter", new AdminMemberSearchCondition(null, null, null, null, null, null));
        capture("e_persona_only", new AdminMemberSearchCondition(null, SelectedPersona.BEGINNER, null, null, null, null));
        capture("f_joined_persona", new AdminMemberSearchCondition(null, SelectedPersona.BEGINNER, null, null, FROM, TO));
    }

    /**
     * 조합 하나를 실행한다. 앞뒤로 마커를 흘려 general log 에서 어느 SQL 이 어느 조합의 것인지
     * 구분되게 한다 — Hibernate 가 그 사이에 다른 쿼리를 끼워도 경계가 흐려지지 않는다.
     *
     * <p>기본 정렬({@code createdAt DESC})과 첫 페이지를 쓴다. 관리자 화면의 기본 진입이 그것이고,
     * §4-1 의 인덱스도 그 경로를 겨냥해 넣은 것이라 비교 대상이 맞아떨어진다.
     */
    private void capture(String label, AdminMemberSearchCondition condition) {
        marker(label + "_BEGIN");
        memberQueryRepository.searchForAdmin(condition, AdminMemberSortKey.CREATED_AT, false, 0, 20);
        marker(label + "_END");
    }

    private void marker(String label) {
        em.createNativeQuery("SELECT '/*EXPLAIN_MARK*/" + label + "'").getSingleResult();
    }
}
