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

    public String createRefreshToken(CustomUserInfoDto member) {
        return createToken(member, refreshTokenExpTime);
    }

    // 빌더 패턴으로 데이터를 직접 주입하여 누락 방지
    private String createToken(CustomUserInfoDto member, long expireTime){
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime tokenValidity = now.plusSeconds(expireTime);

        // 빌드 시점에 로그를 찍어 데이터가 들어오는지 확인
        log.info("@@@ Generating Token for User: {}", member.getEmail());

        return Jwts.builder()
                .setSubject(member.getEmail()) // 필터에서 getSubject로 꺼낼 값
                .claim("userId", member.getEmail())
                .claim("role", member.getRole())
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