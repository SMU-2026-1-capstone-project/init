package com.shadowfit.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.error.GlobalExceptionHandler;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 매핑되지 않은 경로가 404 로 나가고 ERROR 로그를 남기지 않는지 검증한다 (이슈 #129).
 *
 * <p>배경 — {@code GlobalExceptionHandler} 에 {@code NoResourceFoundException} 핸들러가 없어
 * {@code @ExceptionHandler(Exception.class)} 가 받았고, 그 결과 <b>404 대신 500</b> + 매 요청
 * {@code log.error} 스택트레이스였다. 같은 클래스의 {@code AccessDeniedException}(403→500) ·
 * {@code MethodArgumentTypeMismatchException}(400→500, #124) 과 <b>형태가 같은 세 번째 사례</b>다.
 *
 * <p>⚠️ 이 테스트는 상태코드뿐 아니라 <b>로그 레벨도 함께 고정한다.</b> 이슈에서 실질적인 피해로
 * 지목된 것이 상태코드가 아니라 <i>"외부 uptime 모니터가 8080 을 찌를 때마다 ERROR 로그"</i> —
 * 즉 로그가 오탐 채널이 되는 쪽이기 때문이다. 상태코드만 검증하면 나중에 누가 핸들러 안에서
 * {@code log.error} 를 다시 써도 초록불이 유지된다.
 *
 * <p>⚠️ 실제 신고 경로였던 8080 {@code /actuator/health} 자체를 재현하지는 <b>못한다.</b>
 * 테스트 컨텍스트는 {@code src/test/resources/application.yml} 을 쓰므로 {@code management.server.port}
 * 분리가 없고, 그래서 액추에이터가 같은 포트에 <b>매핑돼 있다</b>(404 가 아니라 200 이 난다).
 * 여기서 고정하는 것은 그 경로가 아니라 <b>"핸들러 없는 경로 → 404"</b> 라는 동일한 메커니즘이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("매핑 없는 경로 — 404 이고 ERROR 로그를 남기지 않는다")
class UnmappedPathNotFoundTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;

    private String token;
    private Logger handlerLogger;
    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void setUp() {
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email("unmapped-path@test.com").username("u").password("dummy")
                .role(UserRole.USER).build());
        token = jwtUtil.createAccessToken(CustomUserInfoDto.builder()
                .email(member.getEmail()).role(member.getRole()).build());

        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logCapture = new ListAppender<>();
        logCapture.start();
        handlerLogger.addAppender(logCapture);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(logCapture);
        logCapture.stop();
    }

    @Test
    @DisplayName("인증된 요청이라도 매핑 없는 경로면 404 + 공통 에러 형식")
    void unmappedPath_returns404() throws Exception {
        mockMvc.perform(get("/no-such-path").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").exists());
    }

    /** 존재하는 prefix 아래의 오타 경로도 같은 예외 경로를 탄다 — 라우팅 실수를 500 으로 오인하지 않게. */
    @Test
    @DisplayName("존재하는 prefix 아래 오타 경로도 404")
    void unmappedSubPath_returns404() throws Exception {
        mockMvc.perform(get("/reports/no-such-sub").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("404 는 ERROR 가 아니라 WARN 으로만 남는다 — 경로를 알아볼 수 있게")
    void unmappedPath_doesNotLogError() throws Exception {
        mockMvc.perform(get("/no-such-path").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        assertThat(logCapture.list)
                .as("매핑 없는 경로는 서버 결함이 아니므로 ERROR 로 올리지 않는다")
                .noneMatch(event -> event.getLevel() == Level.ERROR);
        assertThat(logCapture.list)
                .as("대신 어느 경로가 찔렸는지는 WARN 으로 남아야 한다 — 8080 을 찌르는 모니터를 알아채는 수단이다")
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("/no-such-path"));
    }
}