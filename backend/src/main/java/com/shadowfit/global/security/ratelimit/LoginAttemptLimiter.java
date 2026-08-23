package com.shadowfit.global.security.ratelimit;

import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.error.RateLimitExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * <b>계정 단위</b> 로그인 시도 제한 (이슈 #394).
 *
 * <p>[왜 IP 제한만으로 안 되나] {@link AuthRateLimitFilter} 의 IP 버킷은 «한 곳에서 여러 계정을
 * 훑는» 것을 막는다. 반대로 «여러 IP 에서 한 계정을 두드리는» 것은 못 막는다 — 각 IP 는
 * 한도의 발끝에도 안 닿기 때문이다. 로그인 브루트포스의 실제 모양이 후자라, 계정 키가 없으면
 * 이 축은 반쪽이다.
 *
 * <p>[왜 필터가 아니라 서비스인가] 계정 키는 <b>요청 본문 안</b>에 있다. 필터에서 꺼내려면
 * 본문을 미리 읽어 버퍼링하는 래퍼가 필요하고, JSON 을 필터에서 파싱하게 된다.
 * {@code MemberService.login} 은 이미 검증된 {@code dto.getEmail()} 을 들고 있다.
 *
 * <p>🔴 <b>«실패» 만 센다.</b> 성공하면 {@link #recordSuccess} 가 창을 비운다. 모든 시도를 세면
 * 정상 사용자가 기기를 바꿔가며 로그인하는 것까지 걸리는데, 이 장치가 막으려는 것은
 * 그게 아니라 <b>비밀번호 추측</b>이다. 추측은 정의상 실패의 연속이다.
 *
 * <p>🔴 <b>«없는 계정» 도 실패로 센다.</b> 있는 계정과 없는 계정의 한도가 다르면 그 차이가
 * 곧 <b>계정 존재 여부 오라클</b>이 된다.
 */
@Slf4j
@Component
public class LoginAttemptLimiter {

    private final FixedWindowCounter counter;
    private final AuthRateLimitProperties properties;

    public LoginAttemptLimiter(AuthRateLimitProperties properties) {
        this.properties = properties;
        this.counter = new FixedWindowCounter(properties.getWindowSeconds(), properties.getMaxKeys());
    }

    /**
     * 한 칸을 <b>원자적으로 잡고</b> 들어간다. 여유가 없으면 429 로 끊는다.
     * 로그인 처리에 들어가기 <b>전에</b> 부른다.
     *
     * <p>🔴 <b>«보고 나서 나중에 센다» 가 아니다.</b> 검사(현재값 읽기)와 기록(증가)이 갈라져
     * 있으면 그 사이에 동시 요청이 전부 통과한다 — 한도 3에 현재 2여도 동시에 온 열 건이
     * 다 들어가고, 각자 BCrypt 를 태운 뒤 실패를 기록한다. 브루트포스가 정확히 그 형태라
     * 여기서만은 <b>잡고 들어가야</b> 한다. (CodeRabbit 지적, PR #423)
     *
     * <p>잡은 칸은 <b>기본이 «유지»</b> 다 — 로그인은 실패가 정상 결과이기 때문이다.
     * 되돌리는 것은 {@link #releaseReservation} 을 명시적으로 부를 때뿐이고,
     * 성공하면 {@link #recordSuccess} 가 창을 통째로 비운다.
     */
    public void acquireOrThrow(String email) {
        if (!properties.isEnabled()) {
            return;
        }
        String key = normalize(email);
        if (!counter.tryAcquire(key, properties.getAccountFailuresPerWindow())) {
            // 🔴 이메일을 로그에 찍지 않는다 (이슈 #411 과 같은 이유 — 로그는 유출 표면이다).
            //    누구였는지는 이 요청이 인증 전이라 actor 로도 안 남는데, 그건 감수한다.
            //    여기 이메일을 찍으면 «로그인 실패한 계정 목록» 이 로그에 쌓인다.
            log.warn("로그인 시도 제한 초과 — 창 {}초, 한도 {}회",
                    properties.getWindowSeconds(), properties.getAccountFailuresPerWindow());
            throw new RateLimitExceededException(
                    ErrorCode.TOO_MANY_LOGIN_ATTEMPTS, counter.retryAfterSeconds());
        }
    }

    /**
     * 잡아둔 칸을 되돌린다 — <b>인증과 무관한 실패</b>에만 쓴다(DB 장애 등).
     *
     * <p>비밀번호 불일치·계정 없음에는 <b>부르지 않는다.</b> 그게 이 장치가 세려는 바로
     * 그 사건이다. 반대로 인프라가 흔들려 실패한 것까지 세면 <b>장애가 사용자 한도를
     * 갉아먹는다</b> — 로그인이 안 되는 와중에 429 까지 받게 된다.
     */
    public void releaseReservation(String email) {
        if (!properties.isEnabled()) {
            return;
        }
        counter.release(normalize(email));
    }

    /** 로그인이 성사됐다 = 이 계정을 두드리던 것이 아니었다. 창을 비운다. */
    public void recordSuccess(String email) {
        counter.reset(normalize(email));
    }

    /**
     * 키를 소문자로 맞춘다. 이걸 안 하면 {@code A@b.com} 과 {@code a@b.com} 이 서로 다른 버킷을
     * 받아 <b>대소문자만 바꿔가며 한도를 배로 쓸 수 있다.</b>
     *
     * <p>⚠️ 이 정규화는 «제한 키» 전용이다. 조회는 여전히 {@code memberRepository.findByEmail}
     * 이 원문으로 한다 — 두 곳의 규칙이 다르다는 것은 알고 두는 것이고, 제한 쪽만 넓게 잡는
     * 방향이라 안전하다.
     */
    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
