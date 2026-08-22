package com.shadowfit.global.security.jwt;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * refresh token 을 DB 에 저장하기 전에 한 방향 해시로 바꾼다 (이슈 #185).
 *
 * <p><b>왜 SHA-256 이고 BCrypt 가 아닌가.</b> 해시 함수 선택은 «무엇을 지키느냐» 로 갈린다
 * (docs/decisions/hash-function-selection.md). BCrypt 의 느림은 <b>사람이 고른 저엔트로피
 * 비밀번호</b>를 무차별 대입에서 지키기 위한 것이다. refresh token 은 그 반대다 — 서명까지 붙은
 * 고엔트로피 JWT 라 무차별 대입이 성립하지 않는다. 그래서 여기 필요한 것은 ④(비밀번호)가 아니라
 * <b>③(무결성·신원): «제시된 값이 저장했던 그 값과 같은가»를 증명</b>하는 것이고, 그 축의 표준이
 * SHA-256 이다. 그리고 그 «같은가» 를 값으로 조회·대조해야 하므로 <b>salt 를 쓰지 않는다</b>
 * (salt 를 넣으면 결정적이지 않아 저장값과 못 맞춘다. 고엔트로피라 salt 의 이득인 «같은 입력이
 * 같은 해시가 되는 것을 막기» 자체가 필요 없다).
 *
 * <p><b>이 값은 저장·대조에만 쓴다.</b> 되돌릴 수 없으므로, 재발급 유예 경로는 저장값을 되돌려주는
 * 대신 새 토큰을 회전 발급한다 (#185 ㄱ, MemberService 재발급 참조).
 */
@Component
public class RefreshTokenHasher {

    private static final String ALGORITHM = "SHA-256";

    /**
     * @param rawToken 원문 refresh token (클라에 나가는 JWT)
     * @return 소문자 hex 로 인코딩한 SHA-256 해시 (64자). DB {@code refresh_token.token} 에 담긴다
     */
    public String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance(ALGORITHM)
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 JLS 가 모든 JVM 에 요구하는 알고리즘이라 여기 도달하지 않는다.
            // 그래도 삼키지 않는다 — 도달하면 인증 저장이 통째로 깨진 것이므로 명시적으로 터뜨린다.
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
