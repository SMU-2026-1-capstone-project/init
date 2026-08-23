package com.shadowfit.global.security.ratelimit;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 인증 경로 시도 제한 설정 (이슈 #394).
 *
 * <p>🔴 <b>여기 있는 숫자는 임의값이 아니다.</b> 값의 유도는 {@code application.yml} 의
 * {@code security.rate-limit} 블록에 가정 넷과 함께 적혀 있고, 이 클래스는 그 값을 받기만 한다.
 * 값을 바꾸려면 <b>그 가정부터</b> 볼 것 — 가정이 안 바뀌었는데 숫자만 바뀌면 그 순간
 * 근거 없는 임계값이 된다.
 */
@Component
@ConfigurationProperties(prefix = "security.rate-limit")
@Getter
@Setter
public class AuthRateLimitProperties {

    /**
     * 끄는 스위치. 기본 {@code true}.
     *
     * <p>테스트에서 끄기 위한 것이 아니다(테스트는 자기 인스턴스를 직접 만든다) —
     * 운영에서 <b>이 장치가 오히려 사고를 낼 때</b> 재배포 없이 끌 수 있어야 해서다.
     * 가장 그럴 법한 시나리오가 {@link AuthRateLimitFilter} 의 프록시 경고다.
     */
    private boolean enabled = true;

    /** 창 길이(초). 카운트는 이 길이의 고정 창(fixed window)으로 센다. */
    private int windowSeconds = 60;

    /** IP 하나가 창 하나에서 인증 경로를 부를 수 있는 횟수. */
    private int ipPerWindow = 60;

    /** 계정 하나가 창 하나에서 <b>로그인에 실패</b>할 수 있는 횟수. 성공하면 초기화된다. */
    private int accountFailuresPerWindow = 3;

    /**
     * 카운터가 들고 있을 최대 키 수. 이 장치 자체가 메모리 고갈 통로가 되지 않게 하는 상한이다 —
     * 공격자가 IP·이메일을 계속 바꾸면 키가 무한히 늘 수 있고, 그러면 «막으려던 것»이
     * «수단»이 된다. 넘으면 Caffeine 이 오래된 키부터 버린다(= 그 키는 제한이 풀린다).
     */
    private long maxKeys = 100_000;
}
