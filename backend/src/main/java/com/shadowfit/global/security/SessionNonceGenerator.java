package com.shadowfit.global.security;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 세션 소유권 검증용 비밀값을 만든다 (이슈 #187 안 (d)).
 *
 * <p><b>무엇을 막는가.</b> {@code /pose} 는 앱 번들에 든 공유 토큰 하나로만 인증하고
 * {@code session_id} 는 클라가 보낸 정수를 그대로 믿는다. 그 id 는 {@code AUTO_INCREMENT}
 * 순차값이라 <b>추측된다</b> — 토큰을 뽑아낸 사람이 번호만 바꿔 가며 남의 세션에 프레임을
 * 꽂을 수 있었다. 이 값은 세션마다 다르고 추측되지 않으므로, 「그 세션을 만든 클라」와
 * 「그 id 를 아는 사람」을 가른다.
 *
 * <p><b>왜 128비트인가.</b> 임의로 고른 자릿수가 아니라 <b>추측 불가</b>의 통상 하한이다 —
 * 이 값의 방어력은 전적으로 «맞힐 수 없다» 에서 오고, 세션 수명이 짧아 온라인 추측만
 * 가능하므로 128비트면 그 축에서 더 늘릴 이유가 없다. 여기서 재는 것은 «몇 년 버티나» 가
 * 아니라 «순차 정수와 달리 맞힐 수 없나» 다.
 *
 * <p><b>왜 URL-safe Base64 인가.</b> 이 값은 REST 응답 → 클라 → {@code POST /pose} 본문 →
 * gRPC 문자열 필드를 지난다. 그 경로 어디서도 이스케이프가 필요 없어야 하고, hex(32자)보다
 * 짧다(22자). 패딩({@code =})은 뺀다 — 길이가 고정이라 복원에 필요 없고, 쿼리스트링에
 * 실릴 때 인코딩이 갈리는 자리를 없앤다.
 *
 * <p>🔴 <b>로그에 찍지 말 것.</b> 이 값이 로그에 남으면 로그를 읽을 수 있는 사람이 그 세션의
 * 소유자가 된다. 대조 실패를 기록할 때도 값이 아니라 «불일치» 라는 사실만 남긴다.
 */
@Component
public class SessionNonceGenerator {

    /** 128비트. 위 클래스 주석의 «추측 불가» 근거가 이 자릿수의 전부다. */
    private static final int NONCE_BYTES = 16;

    // SecureRandom 은 thread-safe 하고, 재시드 비용 때문에 인스턴스를 재사용하는 편이 낫다.
    private final SecureRandom random = new SecureRandom();

    /** @return URL-safe Base64, 패딩 없음 (22자). */
    public String generate() {
        byte[] bytes = new byte[NONCE_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
