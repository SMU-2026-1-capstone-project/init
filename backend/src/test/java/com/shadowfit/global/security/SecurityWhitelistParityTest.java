package com.shadowfit.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * {@code security.whitelist} 두 사본이 <b>같은지</b> 단언한다 (이슈 #350).
 *
 * <p><b>왜 필요한가.</b> 이 목록은 {@code src/main/resources/application.yml} 과
 * {@code src/test/resources/application.yml} 에 <b>별개 사본</b>으로 있다. 테스트 yml 이 같은
 * 이름으로 main 것을 덮으므로 병합되지 않는다 — 한쪽만 고치면 <b>테스트와 운영의 인증 경계가 갈린다.</b>
 *
 * <p>테스트 yml 은 그 위험을 스스로 적어 뒀고, 「테스트가 먼저 깨지니 조용히 지나가지는 않는다」고
 * 했다. <b>그 안전장치가 다섯 항목에는 안 걸렸다</b> — {@code /swagger-ui.html} · {@code /webjars/**} ·
 * {@code /actuator/*} 를 <b>부르는 테스트가 하나도 없어서</b>다. 「테스트가 먼저 깨진다」는
 * 그 경로를 치는 테스트가 있을 때만 성립한다(2026-08-23 실측: 운영 13 · 테스트 8).
 *
 * <p>그래서 이 테스트는 <b>경로를 치지 않는다.</b> 두 YAML 을 직접 읽어 목록이 같은지만 본다 —
 * 그래야 «부르는 테스트가 없는 경로» 도 그물에 걸린다.
 *
 * <p>⚠️ 이 테스트가 지키는 것은 <b>목록의 동일성</b>이지 목록의 <b>내용이 옳은가</b>가 아니다.
 * 잘못된 경로를 양쪽에 똑같이 넣으면 여기서는 통과한다.
 */
class SecurityWhitelistParityTest {

    private static final String MAIN_YML = "/application.yml";

    @Test
    @DisplayName("security.whitelist 는 main 과 test 사본이 완전히 같아야 한다 (#350)")
    void whitelist_copies_are_identical() {
        List<String> mainList = readWhitelist("src/main/resources/application.yml");
        List<String> testList = readWhitelist("src/test/resources/application.yml");

        assertThat(testList)
                .describedAs(
                        "두 사본이 갈렸다. 한쪽에만 넣으면 테스트와 운영의 인증 경계가 달라진다 (#350).%n"
                                + "  main 에만: %s%n  test 에만: %s",
                        subtract(mainList, testList), subtract(testList, mainList))
                .containsExactlyInAnyOrderElementsOf(mainList);
    }

    @Test
    @DisplayName("이 테스트가 읽는 자리가 실제로 존재한다 — 빈 목록을 «같다» 로 통과시키지 않는다")
    void both_copies_are_non_empty() {
        // 🔴 경로가 바뀌거나 키가 사라지면 위 테스트는 «빈 목록 == 빈 목록» 으로 초록이 된다.
        //    그물이 조용히 사라지는 그 모양을 여기서 막는다.
        assertThat(readWhitelist("src/main/resources/application.yml")).isNotEmpty();
        assertThat(readWhitelist("src/test/resources/application.yml")).isNotEmpty();
    }

    @SuppressWarnings("unchecked")
    private static List<String> readWhitelist(String path) {
        // 파일에서 직접 읽는다 — 스프링 컨텍스트로 읽으면 «지금 활성인 한쪽» 만 보이고,
        // 이 테스트가 물어야 하는 것은 **두 파일이 같은가** 다.
        java.nio.file.Path p = java.nio.file.Path.of(path);
        assertThat(java.nio.file.Files.exists(p))
                .describedAs("설정 파일을 못 찾았다: %s (작업 디렉터리 = %s)",
                        p.toAbsolutePath(), System.getProperty("user.dir"))
                .isTrue();
        try (InputStream in = java.nio.file.Files.newInputStream(p)) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> security = (Map<String, Object>) root.get("security");
            if (security == null) {
                return List.of();
            }
            Object list = security.get("whitelist");
            return list == null ? List.of() : (List<String>) list;
        } catch (Exception e) {
            throw new IllegalStateException(path + " 를 읽지 못했다", e);
        }
    }

    private static List<String> subtract(List<String> a, List<String> b) {
        return a.stream().filter(x -> !b.contains(x)).toList();
    }
}
