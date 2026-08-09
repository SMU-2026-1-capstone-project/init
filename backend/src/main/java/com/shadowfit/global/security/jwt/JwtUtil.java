package com.shadowfit.global.security.jwt;

import com.shadowfit.dto.login.CustomUserInfoDto;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.ZonedDateTime;
import java.util.Date;

@Slf4j
@Component
public class JwtUtil {
    private final SecretKey key;
    private final long accessTokenExpTime;
    private final long refreshTokenExpTime;

    public JwtUtil(
            @Value("${jwt.secret}") final String secretKey,
            @Value("${jwt.expiration_time}") final long accessTokenExpTime,
            @Value("${jwt.refresh_expiration_time}") final long refreshTokenExpTime)
    {
        byte[] keyBytes = secretKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.accessTokenExpTime = accessTokenExpTime;
        this.refreshTokenExpTime = refreshTokenExpTime;
    }

    // Access Token 생성
    public String createAccessToken(CustomUserInfoDto member){
        return createToken(member, accessTokenExpTime);
    }

    /**
     * refresh token 생성. {@code tokenVersion} 을 {@code ver} claim 으로 실어 보낸다 (이슈 #135).
     *
     * <p>⚠️ <b>탐지용이 아니다.</b> 폐기 판정은 {@code refresh_token.token} 과의 일치 하나로 끝난다 —
     * 서명이 유효한데 저장된 토큰과 다르면 그건 정의상 우리가 발급했던 구본이다. 이 claim 의 용도는
     * <b>직전 세대인지 가리는 것</b>이고, 그건 «응답을 못 받은 클라의 재시도» 를 «탈취» 와 구분하는
     * 유예 판정에만 쓰인다 ({@code decisions/token-lifecycle.md} §4-1).
     *
     * <p>claim 이 <b>서명 안에</b> 들어간다는 점이 핵심이다. 클라이언트가 ver 을 조작해 유예를
     * 얻어내려면 서명을 위조해야 한다.
     */
    public String createRefreshToken(CustomUserInfoDto member, long tokenVersion) {
        return createToken(member, refreshTokenExpTime, tokenVersion);
    }

    /**
     * refresh JWT 의 {@code ver} claim. <b>claim 이 없으면 0</b> 을 돌려준다.
     *
     * <p>0 이 기본값인 것은 마이그레이션 호환 때문이다. V3 이전에 발급된 refresh token 에는 이
     * claim 이 아예 없고, 같은 마이그레이션이 기존 행의 {@code token_version} 을 0 으로 채운다
     * ({@code V3__add_refresh_token_version.sql}). 그래서 0 == 0 으로 통과해 <b>배포가 기존 로그인
     * 세션을 끊지 않는다.</b> 여기서 -1 이나 예외를 돌려주면 배포 순간 전 사용자가 재로그인한다.
     */
    public long getTokenVersion(String token) {
        Object ver = parseClaims(token).get("ver");
        if (ver == null) {
            return 0L;
        }
        return ((Number) ver).longValue();
    }

    // 빌더 패턴으로 데이터를 직접 주입하여 누락 방지
    private String createToken(CustomUserInfoDto member, long expireTime){
        return createToken(member, expireTime, null);
    }

    private String createToken(CustomUserInfoDto member, long expireTime, Long tokenVersion){
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime tokenValidity = now.plusSeconds(expireTime);

        // 빌드 시점에 로그를 찍어 데이터가 들어오는지 확인
        log.info("@@@ Generating Token for User: {}", member.getEmail());

        JwtBuilder builder = Jwts.builder()
                .setSubject(member.getEmail()) // 필터에서 getSubject로 꺼낼 값
                .claim("userId", member.getEmail())
                .claim("role", member.getRole());

        // access token 에는 안 붙는다 — 회전 대상이 아니라서 비교할 상대가 없다.
        if (tokenVersion != null) {
            builder = builder.claim("ver", tokenVersion);
        }

        return builder
                .setIssuedAt(Date.from(now.toInstant()))
                .setExpiration(Date.from(tokenValidity.toInstant()))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    // 문자열 userId를 반환하도록 추출 로직 변경
    public String getUserEmail(String token){
        return parseClaims(token).getSubject();
    }

    // JWT 검증
    // ⚠️ 2026-07-24 수정: 위에서 io.jsonwebtoken.security.SecurityException을 명시 import하지
    // 않았을 땐 여기 SecurityException이 java.lang.SecurityException으로 잘못 resolve돼(io.jsonwebtoken
    // 패키지엔 이 이름의 클래스가 base package에 없음), 서명 변조 토큰(io.jsonwebtoken.security.
    // SignatureException)이 이 catch에 안 걸리고 그대로 던져지는 버그가 있었음 — JwtUtilTest로 발견.
    public boolean isValidToken(String token){
        try{
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (SecurityException | MalformedJwtException e) {
            log.info("Invalid JWT signature.", e);
        } catch (ExpiredJwtException e) {
            log.info("Expired JWT token.", e);
        } catch (UnsupportedJwtException e) {
            log.info("Unsupported JWT token.", e);
        } catch (IllegalArgumentException e) {
            log.info("JWT claims string is empty.", e);
        }
        return false;
    }

    public long getExpiration(String token){
        return parseClaims(token).getExpiration().getTime();
    }

    // Claims 추출
    public Claims parseClaims(String accessToken){
        try{
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(accessToken)
                    .getPayload();
        } catch(ExpiredJwtException e){
            return e.getClaims();
        }
    }
}