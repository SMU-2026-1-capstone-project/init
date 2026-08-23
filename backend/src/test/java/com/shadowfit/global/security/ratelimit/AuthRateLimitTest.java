package com.shadowfit.global.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.error.RateLimitExceededException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 인증 경로 시도 제한 (이슈 #394) — 카운터·IP 필터·계정 제한 세 층을 한 파일에서 고정한다.
 *
 * <p><b>여기서 운영 한도 숫자를 검증하지 않는다.</b> 그 값은 가정에서 유도된 것이라
 * ({@code application.yml} 의 {@code security.rate-limit} 블록) 가정이 바뀌면 같이 바뀐다 —
 * 테스트가 숫자를 박아두면 값을 고칠 때마다 테스트가 «틀렸다»고 운다. 고정하는 것은
 * <b>「한도를 넘으면 막힌다」·「창이 지나면 풀린다」·「성공하면 초기화된다」</b> 같은
 * <b>계약</b>이고, 숫자는 각 테스트가 자기 것을 만들어 쓴다.
 */
@DisplayName("인증 경로 시도 제한 (#394)")
class AuthRateLimitTest {

    private static AuthRateLimitProperties props(int ipPerWindow, int accountFailures, int windowSeconds) {
        AuthRateLimitProperties p = new AuthRateLimitProperties();
        p.setIpPerWindow(ipPerWindow);
        p.setAccountFailuresPerWindow(accountFailures);
        p.setWindowSeconds(windowSeconds);
        return p;
    }

    private static ObjectMapper mapper() {
        // 운영에서는 Boot 가 구성한 ObjectMapper 가 주입된다. 여기서는 LocalDateTime 을
        // 직렬화해야 하므로 JSR310 을 직접 붙인다 — 안 붙이면 본문 쓰기에서 터진다.
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Nested
    @DisplayName("FixedWindowCounter")
    class Counter {

        @Test
        @DisplayName("같은 키는 누적되고 다른 키는 서로를 안 건드린다")
        void countsPerKey() {
            FixedWindowCounter counter = new FixedWindowCounter(60, 1000);

            assertThat(counter.increment("a")).isEqualTo(1);
            assertThat(counter.increment("a")).isEqualTo(2);
            assertThat(counter.increment("b")).isEqualTo(1);
            assertThat(counter.current("a")).isEqualTo(2);
        }

        @Test
        @DisplayName("reset 하면 창이 즉시 비고, 없던 키의 current 는 0")
        void resetEmptiesWindow() {
            FixedWindowCounter counter = new FixedWindowCounter(60, 1000);
            counter.increment("a");
            counter.reset("a");

            assertThat(counter.current("a")).isZero();
            assertThat(counter.current("없던-키")).isZero();
        }

        @Test
        @DisplayName("창이 지나면 스스로 풀린다 — 잠금이 아니라 제한이다")
        void windowExpires() throws Exception {
            FixedWindowCounter counter = new FixedWindowCounter(1, 1000);
            counter.increment("a");
            assertThat(counter.current("a")).isEqualTo(1);

            // expireAfterWrite 는 시간 기반이라 실제로 기다린다. 1초라 감당할 만하고,
            // 이 계약(「기다리면 풀린다」)은 이 장치가 «영구 차단» 이 아니라는 근거라
            // 고정할 값어치가 있다.
            Thread.sleep(1_200);
            // Caffeine 은 만료를 지연 정리하므로 다른 키를 건드려 한 번 깨워준다.
            counter.increment("깨우기");

            assertThat(counter.current("a")).isZero();
        }
    }

    @Nested
    @DisplayName("AuthRateLimitFilter — IP 키")
    class IpFilter {

        private MockHttpServletRequest post(String path, String ip) {
            MockHttpServletRequest r = new MockHttpServletRequest("POST", path);
            r.setRemoteAddr(ip);
            return r;
        }

        @Test
        @DisplayName("한도까지는 통과하고 넘으면 429 + Retry-After 로 끊는다")
        void blocksOverLimit() throws Exception {
            AuthRateLimitFilter filter = new AuthRateLimitFilter(props(2, 3, 60), mapper());
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(post("/member/login", "1.1.1.1"), new MockHttpServletResponse(), chain);
            filter.doFilter(post("/member/login", "1.1.1.1"), new MockHttpServletResponse(), chain);
            verify(chain, times(2)).doFilter(ArgumentMatchers.any(), ArgumentMatchers.any());

            MockHttpServletResponse blocked = new MockHttpServletResponse();
            FilterChain shouldNotRun = mock(FilterChain.class);
            filter.doFilter(post("/member/login", "1.1.1.1"), blocked, shouldNotRun);

            assertThat(blocked.getStatus()).isEqualTo(429);
            assertThat(blocked.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
            // 본문 형식이 GlobalExceptionHandler 와 같아야 한다 — 이 필터는 MVC 밖이라
            // 그 핸들러가 안 도는데, 클라 입장에서 «어떤 429 는 모양이 다르다» 가 되면 안 된다.
            assertThat(blocked.getContentAsString()).contains("429").contains("message").contains("timestamp");
            verify(shouldNotRun, never()).doFilter(ArgumentMatchers.any(), ArgumentMatchers.any());
        }

        @Test
        @DisplayName("IP 가 다르면 서로의 한도를 안 먹는다")
        void perIpBuckets() throws Exception {
            AuthRateLimitFilter filter = new AuthRateLimitFilter(props(1, 3, 60), mapper());

            filter.doFilter(post("/member/login", "1.1.1.1"), new MockHttpServletResponse(), mock(FilterChain.class));

            MockHttpServletResponse other = new MockHttpServletResponse();
            filter.doFilter(post("/member/login", "2.2.2.2"), other, mock(FilterChain.class));

            assertThat(other.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("경로별 버킷이라 가입 폭주가 로그인 예산을 안 뺏는다")
        void perPathBuckets() throws Exception {
            AuthRateLimitFilter filter = new AuthRateLimitFilter(props(1, 3, 60), mapper());

            filter.doFilter(post("/member/signup", "1.1.1.1"), new MockHttpServletResponse(), mock(FilterChain.class));

            MockHttpServletResponse loginResponse = new MockHttpServletResponse();
            filter.doFilter(post("/member/login", "1.1.1.1"), loginResponse, mock(FilterChain.class));

            assertThat(loginResponse.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("보호 대상이 아닌 경로·메서드는 세지 않는다 — preflight 가 한도를 먹으면 안 된다")
        void ignoresOtherRequests() throws Exception {
            AuthRateLimitFilter filter = new AuthRateLimitFilter(props(1, 3, 60), mapper());

            for (int i = 0; i < 5; i++) {
                MockHttpServletRequest other = new MockHttpServletRequest("GET", "/sessions/1");
                other.setRemoteAddr("1.1.1.1");
                MockHttpServletResponse res = new MockHttpServletResponse();
                filter.doFilter(other, res, mock(FilterChain.class));
                assertThat(res.getStatus()).isEqualTo(200);
            }

            for (int i = 0; i < 5; i++) {
                MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/member/login");
                preflight.setRemoteAddr("1.1.1.1");
                MockHttpServletResponse res = new MockHttpServletResponse();
                filter.doFilter(preflight, res, mock(FilterChain.class));
                assertThat(res.getStatus()).isEqualTo(200);
            }
        }

        @Test
        @DisplayName("enabled=false 면 아무것도 안 막는다 — 재배포 없이 끄는 통로")
        void disabledLetsEverythingThrough() throws Exception {
            AuthRateLimitProperties p = props(1, 1, 60);
            p.setEnabled(false);
            AuthRateLimitFilter filter = new AuthRateLimitFilter(p, mapper());

            for (int i = 0; i < 10; i++) {
                MockHttpServletResponse res = new MockHttpServletResponse();
                filter.doFilter(post("/member/login", "1.1.1.1"), res, mock(FilterChain.class));
                assertThat(res.getStatus()).isEqualTo(200);
            }
        }
    }

    @Nested
    @DisplayName("LoginAttemptLimiter — 계정 키")
    class Account {

        @Test
        @DisplayName("실패가 한도에 닿으면 다음 시도를 429 로 끊는다")
        void blocksAfterFailures() {
            LoginAttemptLimiter limiter = new LoginAttemptLimiter(props(60, 2, 60));

            limiter.recordFailure("a@b.com");
            assertThatCode(() -> limiter.checkOrThrow("a@b.com")).doesNotThrowAnyException();

            limiter.recordFailure("a@b.com");
            assertThatThrownBy(() -> limiter.checkOrThrow("a@b.com"))
                    .isInstanceOf(RateLimitExceededException.class)
                    .satisfies(e -> {
                        RateLimitExceededException ex = (RateLimitExceededException) e;
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS);
                        assertThat(ex.getRetryAfterSeconds()).isEqualTo(60);
                    });
        }

        @Test
        @DisplayName("성공하면 창이 비어, 기기를 여러 대 쓰는 사용자가 자기 실패 이력에 안 걸린다")
        void successResetsWindow() {
            LoginAttemptLimiter limiter = new LoginAttemptLimiter(props(60, 2, 60));

            limiter.recordFailure("a@b.com");
            limiter.recordFailure("a@b.com");
            limiter.recordSuccess("a@b.com");

            assertThatCode(() -> limiter.checkOrThrow("a@b.com")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("대소문자·공백만 바꿔서 한도를 배로 쓸 수 없다")
        void keyIsNormalized() {
            LoginAttemptLimiter limiter = new LoginAttemptLimiter(props(60, 2, 60));

            limiter.recordFailure("a@b.com");
            limiter.recordFailure("A@B.COM");

            // 정규화가 없으면 이 둘이 다른 버킷이라 여기서 안 걸린다.
            assertThatThrownBy(() -> limiter.checkOrThrow("  A@b.Com  "))
                    .isInstanceOf(RateLimitExceededException.class);
        }

        @Test
        @DisplayName("계정이 다르면 서로의 한도를 안 먹는다")
        void perAccountBuckets() {
            LoginAttemptLimiter limiter = new LoginAttemptLimiter(props(60, 1, 60));

            limiter.recordFailure("a@b.com");

            assertThatCode(() -> limiter.checkOrThrow("c@d.com")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("enabled=false 면 검사 자체를 안 한다")
        void disabledSkipsCheck() {
            AuthRateLimitProperties p = props(60, 1, 60);
            p.setEnabled(false);
            LoginAttemptLimiter limiter = new LoginAttemptLimiter(p);

            limiter.recordFailure("a@b.com");
            limiter.recordFailure("a@b.com");

            assertThatCode(() -> limiter.checkOrThrow("a@b.com")).doesNotThrowAnyException();
        }
    }
}
