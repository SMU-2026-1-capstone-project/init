package com.shadowfit.global.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP 진입점의 correlation id 발급/수용 검증.
 *
 * 필터가 MDC에 값을 넣는 시점은 "체인 실행 중"뿐이라, 체인 안에서 값을 훔쳐보는 방식으로 검증한다.
 */
@DisplayName("CorrelationIdFilter 테스트")
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("헤더가 없으면 새 id를 발급하고 응답 헤더로 돌려준다")
    void generatesIdWhenHeaderAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(seenInsideChain));

        assertThat(seenInsideChain.get()).isNotBlank();
        assertThat(response.getHeader(CorrelationIds.HTTP_HEADER)).isEqualTo(seenInsideChain.get());
    }

    @Test
    @DisplayName("클라이언트가 보낸 X-Request-Id는 그대로 이어받는다")
    void adoptsInboundHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIds.HTTP_HEADER, "trace-from-client-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(seenInsideChain));

        assertThat(seenInsideChain.get()).isEqualTo("trace-from-client-1");
    }

    @Test
    @DisplayName("개행이 섞인 헤더는 로그 인젝션 위험이라 버리고 새로 발급한다")
    void rejectsUnsafeInboundHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIds.HTTP_HEADER, "evil\n2026-07-27 INFO 가짜 로그 줄");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(seenInsideChain));

        assertThat(seenInsideChain.get()).doesNotContain("evil").doesNotContain("\n");
    }

    @Test
    @DisplayName("요청이 끝나면 MDC를 비운다 — 스레드 재사용 시 다음 요청에 새어나가면 안 됨")
    void clearsMdcAfterRequest() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                capturingChain(new AtomicReference<>()));

        assertThat(CorrelationIds.current()).isNull();
    }

    private FilterChain capturingChain(AtomicReference<String> sink) {
        return (req, res) -> sink.set(CorrelationIds.current());
    }
}
