package com.shadowfit.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 세션 소유권 비밀값의 성질 (#187 안 (d)).
 *
 * <p>이 값의 방어력은 전부 «추측할 수 없다» 에서 온다 — {@code session_id} 가 순차 정수라
 * 추측되는 것과 대비되는 것이 존재 이유다. 그래서 여기서 재는 것은 «잘 만들어지나» 가 아니라
 * <b>«추측 가능한 성질이 섞이지 않았나»</b> 다.
 */
class SessionNonceGeneratorTest {

    private final SessionNonceGenerator generator = new SessionNonceGenerator();

    @Test
    @DisplayName("매번 다른 값이 나온다 — 같은 값이 두 번 나오면 그 순간 두 세션이 서로를 통과한다")
    void generatesDistinctValues() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            seen.add(generator.generate());
        }
        // 128비트에서 1만 개 뽑아 충돌하면 난수원이 고장난 것이다(생일 문제로도 사실상 0).
        assertThat(seen).hasSize(10_000);
    }

    @Test
    @DisplayName("URL-safe Base64 22자 — 이 값이 지나는 경로 어디서도 이스케이프가 필요 없어야 한다")
    void isUrlSafeBase64WithoutPadding() {
        // REST 응답 → 클라 → POST /pose 본문 → gRPC 문자열 필드를 지난다.
        // 패딩(=)이 있으면 쿼리스트링에 실릴 때 인코딩이 갈리는 자리가 생긴다.
        Pattern urlSafe = Pattern.compile("^[A-Za-z0-9_-]{22}$");
        for (int i = 0; i < 1_000; i++) {
            assertThat(generator.generate()).matches(urlSafe);
        }
    }

    @Test
    @DisplayName("128비트를 실제로 담는다 — 자릿수만 맞고 엔트로피가 비면 방어가 사라진다")
    void carriesFullEntropy() {
        // 22자 URL-safe Base64 = 132비트 표현 공간이고 그중 128비트가 실제 값이다.
        // 마지막 글자가 항상 같다면 인코딩이 잘린 것이고, 그건 자릿수로는 안 보인다.
        Set<Character> lastChars = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String nonce = generator.generate();
            lastChars.add(nonce.charAt(nonce.length() - 1));
        }
        assertThat(lastChars).hasSizeGreaterThan(1);
    }
}
