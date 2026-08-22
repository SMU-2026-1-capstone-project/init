package com.shadowfit.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code application-prod.yml} 의 계약 (docs/decisions/reverse-proxy-and-tls.md §8-2).
 *
 * <p><b>왜 설정 파일에 테스트가 붙는가.</b> 이 저장소가 반복해서 당한 부류가
 * <b>«조용히 무효가 되는 설정»</b> 이다 — 아무도 안 읽는 키가 남아 있거나(#285 gRPC 주소,
 * #286 죽은 HTTP 배선), 오타 하나로 값이 안 먹는데 앱은 정상 기동한다. 여기 키를
 * {@code forward-header-strategy} 로 한 글자 잘못 쓰면 <b>기동도 되고 로그도 조용한데
 * 프록시 뒤에서 스킴과 클라이언트 IP 만 틀린다.</b>
 *
 * <p>스프링 컨텍스트를 띄우지 않는다. prod 프로파일은 MySQL 을 물고, 여기서 재려는 것은
 * «이 파일이 무엇을 약속하는가» 뿐이다.
 */
class ProdProfileConfigTest {

    private static final String PATH = "application-prod.yml";

    private PropertySource<?> load() throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("prod", new ClassPathResource(PATH));
        assertThat(sources)
                .as("application-prod.yml 이 클래스패스에 있어야 한다 — 없으면 prod 프로파일이 "
                        + "빈 채로 도는 예전 상태로 되돌아간 것이다")
                .isNotEmpty();
        return sources.get(0);
    }

    @Test
    @DisplayName("forward-headers-strategy 키가 실제로 존재한다 — 오타면 조용히 무효가 된다")
    void forwardHeadersStrategyKeyExists() throws IOException {
        assertThat(load().getProperty("server.forward-headers-strategy"))
                .as("키 이름이 정확해야 한다. 틀리면 앱은 정상 기동하고 프록시 뒤에서만 틀린다")
                .isNotNull();
    }

    @Test
    @DisplayName("🔴 기본값이 none 이다 — 프록시 없이 켜면 아무나 IP·스킴을 위조한다")
    void forwardHeadersDefaultsToNone() throws IOException {
        Object value = load().getProperty("server.forward-headers-strategy");

        // 이 파일은 개발 compose·로컬 bootRun 에도 적용된다(둘 다 prod 프로파일이다).
        // 그 환경에는 프록시가 없으므로, framework 로 켠 채 두면 X-Forwarded-* 를 그대로
        // 믿어 클라이언트 IP·스킴 위조가 열린다. 켜는 것은 배포 환경의 환경변수 몫이다.
        assertThat(value.toString())
                .as("환경변수로 받되 기본값은 어디서 돌아도 안전한 쪽이어야 한다")
                .isEqualTo("${FORWARD_HEADERS_STRATEGY:none}");
    }
}
