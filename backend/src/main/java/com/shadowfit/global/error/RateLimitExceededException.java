package com.shadowfit.global.error;

import lombok.Getter;

/**
 * 시도 제한 초과 (이슈 #394). {@link BusinessException} 과 다른 점은 <b>{@code Retry-After} 에
 * 실을 초</b>를 같이 들고 온다는 것뿐이다 — 429 는 «언제 다시 오라»를 못 주면 반쪽이다.
 */
@Getter
public class RateLimitExceededException extends BusinessException {

    private final int retryAfterSeconds;

    public RateLimitExceededException(ErrorCode errorCode, int retryAfterSeconds) {
        super(errorCode);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
