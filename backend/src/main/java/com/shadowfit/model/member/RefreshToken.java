package com.shadowfit.model.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access= AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class RefreshToken {
    // PK 가 member_id 라 회원당 1행이다 — 이건 사고가 아니라 **정책**이다.
    // «1인 1세션: 새 로그인이 기존 세션을 만료시킨다» (이슈 #136, decisions/token-lifecycle.md §2 확정).
    // 다기기 동시 로그인을 허용하려면 PK 를 토큰 id 로 옮겨야 하고, 그건 마이그레이션이 따라온다.
    @Id
    @Column(name = "member_id", nullable = false)
    private Long memberId;

    // ⚠️ 평문이다. DB 덤프가 유출되면 그 자체로 쓸 수 있는 자격증명이다
    // (decisions/token-lifecycle.md §1-1-ㄱ — 해싱 여부는 #137 저장소 결정과 같이 정하기로 남겼다).
    @Column(name = "token", nullable = false, length = 512)
    private String token;

    // 회전 세대 번호 (이슈 #135). 같은 값이 refresh JWT 의 ver claim 에 실린다.
    //
    // ⚠️ **이 값은 폐기 판정에 쓰지 않는다.** 폐기 판정은 token 일치 하나로 끝난다 — 서명이
    // 유효한데 row.token 과 다른 refresh token 은 정의상 우리가 발급했던 구본이기 때문이다.
    // 이 값의 용도는 **직전 세대인지 가리는 것**뿐이고, 그건 유예(§ rotatedAt)에만 쓰인다.
    // 처음에는 이걸 탐지용으로 설계했다가 중복이라 용도를 좁혔다 (decisions/token-lifecycle.md §4-1).
    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private Long tokenVersion = 0L;

    // 마지막 회전 시각. 직전 토큰을 얼마나 더 인정할지의 기준이다.
    // null 이면 «회전한 적 없음» 이고, 그 경우 유예는 성립하지 않는다.
    @Column(name = "rotated_at")
    private LocalDateTime rotatedAt;

    /**
     * 세대를 올리고 새 토큰으로 교체한다. 로그인·재발급 양쪽에서 쓴다.
     *
     * <p>엔티티가 셋을 한 번에 움직이게 둔 이유: 따로 두면 호출부에서 {@code token} 만 바꾸고
     * {@code tokenVersion} 이나 {@code rotatedAt} 을 빠뜨릴 수 있다. 그러면 유예 판정이
     * <b>조용히</b> 어긋난다 — 검사는 돌지만 «직전 세대» 를 못 알아보게 되고, 정상 재시도가
     * 탈취로 판정되어 사용자가 강제 로그아웃된다.
     */
    public void rotate(String newToken, LocalDateTime now) {
        this.token = newToken;
        this.tokenVersion += 1;
        this.rotatedAt = now;
    }

    /**
     * 새 로그인이 기존 세션을 대체한다 (이슈 #136 — 「1인 1세션」).
     *
     * <p><b>{@code rotatedAt} 을 비우는 것이 이 메서드의 존재 이유다.</b> 로그인에도
     * {@link #rotate} 를 쓰면 앞 기기가 들고 있던 토큰이 정확히 «직전 세대 + 유예 안» 조건에
     * 걸려서, 재발급을 시도한 <b>앞 기기에게 새 세션의 refresh token 이 넘어간다.</b> 1인 1세션을
     * 세워놓고 그 반대 동작을 하게 되는 셈이다.
     *
     * <p>유예는 «재발급 응답을 못 받은 클라의 재시도» 를 위한 것이고, 로그인에는 그런 재시도가
     * 없다 — 로그인한 기기는 응답을 받았거나 못 받았으면 다시 로그인하면 된다.
     */
    public void replaceForNewLogin(String newToken) {
        this.token = newToken;
        this.tokenVersion += 1;
        this.rotatedAt = null;
    }

    /**
     * 제시된 토큰이 <b>직전 세대이면서 유예 안</b>인가 — 즉 «탈취» 가 아니라 «응답을 못 받은
     * 클라의 재시도» 로 볼 것인가.
     *
     * @param presentedVersion 제시된 refresh JWT 의 ver claim
     * @param now              현재 시각
     * @param graceSeconds     유예 길이. 근거는 클라 HTTP timeout (MemberService 참조)
     */
    public boolean isWithinRetryGrace(long presentedVersion, LocalDateTime now, long graceSeconds) {
        if (rotatedAt == null) {
            return false;   // 회전한 적이 없으면 «직전» 이라는 것도 없다
        }
        if (presentedVersion != tokenVersion - 1) {
            return false;   // 두 세대 이상 전 — 재시도로 설명되지 않는다
        }
        return !now.isAfter(rotatedAt.plusSeconds(graceSeconds));
    }
}
