package com.shadowfit.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

/**
 * CORS preflight 응답이 실제로 쓰이는 HTTP 메서드를 전부 허용하는지 검증한다 (이슈 #149).
 *
 * <p>배경 — {@code WebConfig} 의 {@code allowedMethods} 에 PATCH 가 빠져 있었다. 브라우저는 PATCH 에
 * preflight({@code OPTIONS})를 보내고, 이 단계에서 막히면 <b>본 요청을 아예 보내지 않는다.</b>
 * 즉 세션 종료 · TTS 설정 · 온보딩 · 관리자 운동 수정이 통째로 막힌다.
 *
 * <p>실제로 고쳐 보고 되돌려 본 결과, 막히는 형태는 이슈에 적었던 <i>"200 인데 헤더에 PATCH 가 없다"</i>
 * 가 아니라 <b>preflight 자체가 403</b> 이었다 — Spring 의 CORS 처리는 허용되지 않은
 * {@code Access-Control-Request-Method} 를 만나면 본문 없이 거부한다. 그래서 아래 검사는
 * 헤더 내용뿐 아니라 <b>상태코드 200</b> 도 같이 못박는다.
 *
 * <p>지금까지 안 드러난 이유는 <b>현재 클라이언트가 React Native 네이티브 앱이라 CORS 를 타지 않기</b>
 * 때문이다. 관리자 프론트가 별도 웹으로 확정돼 있어(docs/decisions/admin-page-scope.md §5-1),
 * 그 화면이 브라우저에 뜨는 순간 드러날 결함이었다.
 *
 * <p>⚠️ 엔드포인트 목록을 <b>하드코딩하지 않는다.</b> 이 결함의 본질은 "PATCH 를 쓰는 컨트롤러가
 * 늘어나는데 CORS 설정이 안 따라온다" 는 드리프트이므로, 실제 {@code RequestMappingHandlerMapping}
 * 에 등록된 메서드 집합을 읽어서 검사한다. 나중에 PUT 만 쓰는 곳에 PATCH 가 새로 생겨도 자동으로 걸린다.
 *
 * <p>⚠️ 여기서 고정하는 것은 <b>허용 메서드</b>뿐이다. {@code allowedOriginPatterns("*")} +
 * {@code allowCredentials(true)} 조합(= 어떤 오리진이든 그대로 echo)은 이슈 #149 에서 성격이 다른
 * <i>하드닝</i> 항목으로 분리돼 있고, 아직 결정되지 않았다. 이 테스트는 그 부분을 고정하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("CORS preflight — 실제 쓰는 메서드를 전부 허용한다")
class CorsPreflightTest {

    private static final String ORIGIN = "http://localhost:3000";

    @Autowired private MockMvc mockMvc;

    // 액추에이터도 RequestMappingHandlerMapping 하위 타입 빈(controllerEndpointHandlerMapping)을 올린다.
    // 우리가 볼 것은 컨트롤러 매핑이므로 이름으로 못박는다.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    /**
     * 애플리케이션이 실제로 매핑한 HTTP 메서드를 전부 모은다.
     * 메서드를 명시하지 않은 매핑(= 전 메서드 허용)은 여기서 셀 수 없으므로 제외된다.
     */
    private List<String> mappedHttpMethods() {
        List<String> methods = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            entry.getKey().getMethodsCondition().getMethods().forEach(m -> {
                if (!methods.contains(m.name())) {
                    methods.add(m.name());
                }
            });
        }
        return methods;
    }

    private String allowedMethodsHeader() throws Exception {
        // preflight 는 인증 전에 처리돼야 한다 — 토큰 없이 보낸다.
        MvcResult result = mockMvc.perform(options("/admin/exercises/1")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("preflight 는 인증을 요구하지 않고 200 으로 끝나야 한다 (실제 status=%d)",
                        result.getResponse().getStatus())
                .isEqualTo(200);

        String header = result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS);
        assertThat(header)
                .as("preflight 응답에 Access-Control-Allow-Methods 가 없으면 브라우저는 본 요청을 보내지 않는다")
                .isNotNull();
        return header;
    }

    @Test
    @DisplayName("PATCH preflight 가 허용된다 — 없으면 관리자 웹의 임계값 수정이 전송조차 안 된다")
    void patchPreflight_isAllowed() throws Exception {
        assertThat(allowedMethodsHeader())
                .as("PATCH 를 쓰는 엔드포인트가 있는데 허용 목록에 없으면 preflight 에서 막힌다")
                .contains("PATCH");
    }

    @Test
    @DisplayName("컨트롤러가 실제로 쓰는 메서드는 모두 허용 목록에 있다 — 드리프트 방지")
    void allMappedMethods_areAllowed() throws Exception {
        String allowed = allowedMethodsHeader();

        // OPTIONS/HEAD 는 preflight·프레임워크가 처리하는 메서드라 허용 목록에 없어도 정상이다.
        List<String> required = mappedHttpMethods().stream()
                .filter(m -> !m.equals("OPTIONS") && !m.equals("HEAD"))
                .toList();

        assertThat(required)
                .as("매핑을 하나도 못 읽었다면 이 테스트가 아무것도 검증하지 못한 것이다")
                .isNotEmpty();

        assertThat(allowed.split("\\s*,\\s*"))
                .as("실제 매핑된 메서드 %s 가 CORS 허용 목록 [%s] 에 전부 들어 있어야 한다", required, allowed)
                .contains(required.toArray(new String[0]));
    }
}