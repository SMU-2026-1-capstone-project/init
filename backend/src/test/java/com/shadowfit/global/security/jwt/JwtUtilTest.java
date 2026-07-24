package com.shadowfit.global.security.jwt;

import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.model.member.UserRole;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtUtil 단위테스트 — 토큰 생성/검증/만료/파싱. 보안 메커니즘 자체가 지금까지 무테스트였던
 * 영역이라 happy path뿐 아니라 변조·만료·형식오류 케이스까지 검증.
 */
@DisplayName("JwtUtil 테스트")
class JwtUtilTest {

    private static final String SECRET = "test-secret-key-must-be-long-enough-for-hmac-sha256-1234567890";

    private JwtUtil jwtUtil;
    private CustomUserInfoDto userInfo;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, 3600L, 604800L); // access 1시간, refresh 7일
        userInfo = CustomUserInfoDto.builder().email("test@test.com").role(UserRole.USER).build();
    }

    @Test
    @DisplayName("access token 생성 후 이메일 추출·검증 가능")
    void createAccessToken_andExtractEmail() {
        String token = jwtUtil.createAccessToken(userInfo);

        assertThat(token).isNotBlank();
        assertThat(jwtUtil.getUserEmail(token)).isEqualTo("test@test.com");
        assertThat(jwtUtil.isValidToken(token)).isTrue();
    }

    @Test
    @DisplayName("refresh token도 정상 생성·검증됨")
    void createRefreshToken_isValid() {
        String token = jwtUtil.createRefreshToken(userInfo);

        assertThat(jwtUtil.isValidToken(token)).isTrue();
        assertThat(jwtUtil.getUserEmail(token)).isEqualTo("test@test.com");
    }

    @Test
    @DisplayName("만료된 토큰은 isValidToken false — 단 getExpiration/parseClaims는 여전히 값 반환 (로그아웃 블랙리스트 계산용)")
    void expiredToken_isInvalid_butClaimsStillReadable() {
        JwtUtil expiredIssuer = new JwtUtil(SECRET, -10L, -10L); // 발급 즉시 만료
        String expiredToken = expiredIssuer.createAccessToken(userInfo);

        assertThat(jwtUtil.isValidToken(expiredToken)).isFalse();
        Claims claims = jwtUtil.parseClaims(expiredToken);
        assertThat(claims.getSubject()).isEqualTo("test@test.com");
    }

    @Test
    @DisplayName("서명이 변조된 토큰은 isValidToken false")
    void tamperedToken_isInvalid() {
        String token = jwtUtil.createAccessToken(userInfo);
        String tampered = token.substring(0, token.length() - 5) + "AAAAA";

        assertThat(jwtUtil.isValidToken(tampered)).isFalse();
    }

    @Test
    @DisplayName("형식이 아예 잘못된 문자열은 예외 전파 없이 isValidToken false")
    void malformedString_isInvalid() {
        assertThat(jwtUtil.isValidToken("not-a-jwt-at-all")).isFalse();
    }

    @Test
    @DisplayName("getExpiration은 발급 시점 + 설정된 만료시간(초)의 epoch millis를 반환")
    void getExpiration_reflectsConfiguredExpiry() {
        String token = jwtUtil.createAccessToken(userInfo);
        long expiration = jwtUtil.getExpiration(token);

        long expectedMin = System.currentTimeMillis() + 3600L * 1000 - 5000;
        long expectedMax = System.currentTimeMillis() + 3600L * 1000 + 5000;
        assertThat(expiration).isBetween(expectedMin, expectedMax);
    }
}
