package com.shadowfit.service.Member;

import com.shadowfit.dto.login.MemberRequestDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.Sex;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

/**
 * 이슈 #195 ②층 — 사전검사와 INSERT 사이에 같은 username 이 선점되는 경합.
 *
 * <p><b>이 테스트만이 확인할 수 있는 것.</b> {@code MemberService.duplicationOf} 는 어느 필드가
 * 걸렸는지를 {@code ConstraintViolationException#getConstraintName()} 으로 가른다. 그 값이
 * 실제로 무엇인지는 <b>MySQL 의 에러 문구를 Hibernate dialect 가 어떻게 뽑느냐</b>에 달려 있고
 * (관측된 형태는 {@code Duplicate entry 'e1runner' for key 'users.username'}), 그건 우리 코드가
 * 정하는 값이 아니다. 단위 테스트({@code MemberServiceTest})는 그 문자열을 <b>가정</b>하므로,
 * 가정이 맞는지는 진짜 MySQL 위에서만 확인된다. 드라이버·서버 버전이 문구를 바꾸면
 * 여기서 깨지고, 안 깨지면 그 매핑이 아직 유효하다는 뜻이다.
 *
 * <p><b>왜 H2 로는 안 되는가.</b> 제약명 형식이 벤더마다 다르다 — H2 는 자기 형식으로 주므로
 * 통과하든 실패하든 프로덕션(MySQL)에 대해 아무것도 말해주지 않는다.
 * {@code PoseDataOrphanRaceTest} 가 같은 이유로 {@code application-race.yml} 을 쓴다.
 *
 * <p><b>레이스를 타이밍에 맡기지 않는 이유.</b> 사전검사와 INSERT 사이는 비밀번호 인코딩뿐이라
 * 창이 좁다. 두 스레드를 경주시키면 대개 재현되지 않아 "결함 없음"으로 오독된다. 그래서
 * {@code existsByUsername} 을 스파이로 잡아 <b>이미 선점된 값에도 false 를 주게</b> 만든다 —
 * 사전검사가 통과한 직후 남이 끼어든 상태와 DB 가 보는 것이 정확히 같다. 창의 폭을 넓힐 뿐
 * 순서는 실제로 일어날 수 있는 그대로다.
 *
 * <p><b>실행법</b> — 시스템 프로퍼티가 없으면 통째로 건너뛰므로 CI 는 영향받지 않는다:
 * <pre>
 *   docker run -d --name shadowfit-race-mysql -e MYSQL_ROOT_PASSWORD=racetest \
 *     -e MYSQL_DATABASE=shadowfit -p 3307:3306 mysql:8.0 \
 *     --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
 *   docker exec -i shadowfit-race-mysql mysql -uroot -pracetest shadowfit \
 *     &lt; backend/src/main/resources/db/migration/V1__baseline.sql
 *   ./gradlew :backend:test --tests '*SignupUsernameRaceTest' -Drace.mysql=true
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("race")
@EnabledIfSystemProperty(named = "race.mysql", matches = "true",
        disabledReason = "실제 MySQL(3307)이 필요 — 클래스 주석의 docker 명령 참고")
@DisplayName("#195 가입 경합 — UNIQUE 위반이 4xx 로 옮겨지는가")
class SignupUsernameRaceTest {

    private static final String TAKEN_USERNAME = "race195user";

    @Autowired private MemberService memberService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    // MemberService 가 주입받는 그 빈을 스파이로 바꿔 «사전검사 통과» 상태를 만든다.
    @MockitoSpyBean private MemberRepository memberRepository;

    @AfterEach
    void tearDown() {
        // ddl-auto: none 프로파일이라 @Transactional 롤백에 기대지 않고 직접 지운다.
        jdbcTemplate.update("DELETE FROM users WHERE username = ? OR email IN (?, ?)",
                TAKEN_USERNAME, "race195-a@test.local", "race195-b@test.local");
    }

    @Test
    @DisplayName("사전검사를 통과해도 UNIQUE 에 걸리면 500 이 아니라 USERNAME_DUPLICATION")
    void usernameRace_isTranslatedTo4xx() {
        seedTakenUsername();

        // 사전검사가 «비었다» 고 답한 직후 남이 선점한 상태 — DB 만이 진실을 안다.
        doReturn(false).when(memberRepository).existsByUsername(TAKEN_USERNAME);

        MemberRequestDto dto = new MemberRequestDto(
                TAKEN_USERNAME, "race195-b@test.local", "E1passw0rd!", Sex.MALE);

        assertThatThrownBy(() -> memberService.signup(dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USERNAME_DUPLICATION);

        // 실패한 가입이 행을 남기지 않았는지 — 제약이 막았으니 당연하지만, 이 단언이 없으면
        // "예외는 맞게 나왔는데 반쪽 저장이 남는" 경우를 이 테스트가 못 본다.
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, "race195-b@test.local");
        assertThat(count).isZero();
    }

    private void seedTakenUsername() {
        memberRepository.saveAndFlush(Member.builder()
                .username(TAKEN_USERNAME)
                .email("race195-a@test.local")
                .password(passwordEncoder.encode("E1passw0rd!"))
                .role(UserRole.USER)
                .build());
    }
}
