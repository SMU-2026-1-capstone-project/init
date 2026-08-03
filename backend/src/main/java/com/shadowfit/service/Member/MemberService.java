package com.shadowfit.service.Member;

import com.shadowfit.dto.login.*;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.security.jwt.JwtBlacklist;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.RefreshToken;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.member.RefreshTokenRepository;
import com.shadowfit.service.Exercise.PoseDataCleanupService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService{
    private final JwtUtil jwtUtil;
    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SessionRepository sessionRepository;
    private final PoseDataRepository poseDataRepository;
    private final PoseDataCleanupService poseDataCleanupService;
    private final PasswordEncoder passwordEncoder;
    private final JwtBlacklist jwtBlacklist;

    /**
     * 이 시간 동안 프레임 유입이 없으면 그 세션은 죽은 것으로 본다(탈퇴 가드 판정 기준).
     * 배치 간격(rep 단위 ~3~4초)보다 넉넉해야 세트 사이 휴식을 죽음으로 오판하지 않는다.
     * 60초는 초안이며 실측 배치 간격과 대조해 조정할 값이다
     * (docs/decisions/withdrawal-with-active-session.md §7).
     */
    @Value("${member.withdrawal.active-workout-idle-seconds:60}")
    private long activeWorkoutIdleSeconds;
    //로그인 로직
    @Transactional
    public LoginResponseDto login(LoginRequestDto dto){
        Member member = memberRepository.findByEmail(dto.getEmail()).
                orElseThrow(()-> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if(!passwordEncoder.matches(dto.getPassword(), member.getPassword())){
            throw new BusinessException(ErrorCode.LOGIN_INPUT_INVALID);
        }

        CustomUserInfoDto info = CustomUserInfoDto.builder()
                .email(member.getEmail())
                .role(member.getRole())
                .build();

        String accessToken=jwtUtil.createAccessToken(info);
        String refreshToken=jwtUtil.createRefreshToken(info);
        UserRole role = member.getRole();

        RefreshToken refreshTokenEntity= RefreshToken.builder()
                .memberId(member.getId())
                .token(refreshToken)
                .build();
        refreshTokenRepository.save(refreshTokenEntity);
        return new LoginResponseDto(accessToken,refreshToken,role);
    }

    //로그아웃 로직
    @Transactional
    public void logout(LogOutRequestDto dto){
        refreshTokenRepository.deleteByToken(dto.getRefreshToken());
        String token = dto.getAccessToken();
        if(token.startsWith("Bearer ")){
            token = token.substring(7);
        }
        long expiration = jwtUtil.getExpiration(token);
        jwtBlacklist.add(token,expiration);
    }

    //회원가입 로직
    @Transactional
    public String signup(MemberRequestDto dto) {
        if(memberRepository.existsByEmail((dto.getEmail()))) {
            throw new BusinessException(ErrorCode.USERID_DUPLICATION);
        }
        String encodedPassword = passwordEncoder.encode(dto.getPassword());
        Member member = Member.builder()
                .username(dto.getUsername())
                .email(dto.getEmail())
                .password(encodedPassword)
                .role(dto.getRole())
                .build();
        memberRepository.save(member);
        return member.getUsername();
    }

    //회원탈퇴 로직
    @Transactional
    public void deleteAccount(String email){
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(()-> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // createSession 과 같은 회원 행 락을 잡는다. 아래 "운동 중인가" 확인과 실제 삭제 사이에
        // 새 세션이 시작되면 그 세션의 pose_data 가 정리를 빠져나가므로(이슈 #87), 확인만으로는
        // 또 TOCTOU 다. createSession(SessionService:95)이 같은 락을 쓰므로 둘이 직렬화된다.
        memberRepository.findByIdForUpdate(member.getId())
                .orElseThrow(()-> new BusinessException(ErrorCode.USER_NOT_FOUND));

        requireNoActiveWorkout(member.getId());

        // pose_data는 파티셔닝을 위해 FK(ON DELETE CASCADE)를 제거했음 — 세션이 지워지기 전에
        // session_id 목록을 미리 확보해둬야 afterCommit 이후 정리할 수 있음
        // (docs/decisions/pose-data-partition-fk-tradeoff.md).
        List<Long> sessionIds = sessionRepository.findIdsByMemberId(member.getId());

        // refresh_token.member_id 는 users(id) ON DELETE CASCADE 라 DB가 자동으로 같이 지움 —
        // 수동으로도 지우면 이미 사라진 행을 또 지우려다 StaleStateException(0 row) 발생.
        // exercise_sessions.member_id 도 동일하게 CASCADE 유지(FK 그대로) — pose_data만 예외.
        memberRepository.delete(member);

        // afterCommit: 탈퇴 트랜잭션이 확정된 직후, 스케줄 대기 없이 즉시 비동기로 pose_data 정리
        // 트리거 (개인정보보호법 제21조 "지체없이" 파기 요건 대응, 탈퇴 API 응답은 기다리지 않음).
        if (!sessionIds.isEmpty()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            poseDataCleanupService.cleanupBySessionIds(sessionIds);
                        }
                    }
            );
        }
    }

    /**
     * 운동이 <b>실제로 진행 중</b>이면 탈퇴를 거절한다. 세션 1건 삭제가 이미 같은 방침을 쓰고 있는데
     * (SessionService.deleteSession, W006) 탈퇴 경로에만 빠져 있었다 — 그래서 "진행 중 세션 1건은
     * 못 지우는데 그 세션을 가진 회원 전체는 지울 수 있는" 상태였다
     * (docs/decisions/withdrawal-with-active-session.md, 이슈 #87).
     *
     * <p><b>판정을 상태값이 아니라 데이터 흐름으로 하는 이유.</b> {@code IN_PROGRESS} 만 보면 앱이
     * 죽어 남은 세션 때문에 <b>운동 중이 아닌 사용자가 최대 ~45분간 탈퇴하지 못한다</b>(타임아웃
     * 스케줄러가 걷어갈 때까지). 사용자에게는 원인이 안 보이는 형태라 안내 문구로 덮을 수 없다.
     * 살아있는 세션은 rep 단위로 3~4초마다 프레임을 보내므로, 유입이 끊긴 것을 죽었다는 증거로 쓴다.
     *
     * <p>임계값이 배치 간격(3~4초)보다 넉넉한 이유는 세트 사이 휴식 때문이다 — 짧게 잡으면 쉬는
     * 중인 사용자를 죽은 것으로 오판해 <b>정말 운동 중인데 탈퇴가 통과</b>한다. 이 방향의 오판이
     * 반대보다 나쁘므로(그게 막으려던 결함 그 자체다) 여유를 둔다.
     */
    private void requireNoActiveWorkout(Long memberId) {
        List<Long> inProgressIds =
                sessionRepository.findIdsByMemberIdAndStatus(memberId, Status.IN_PROGRESS);
        if (inProgressIds.isEmpty()) {
            return;
        }

        LocalDateTime since = LocalDateTime.now().minusSeconds(activeWorkoutIdleSeconds);
        if (poseDataRepository.countSince(inProgressIds, since) > 0) {
            throw new BusinessException(ErrorCode.WITHDRAWAL_BLOCKED_BY_ACTIVE_SESSION);
        }
        // 유입이 끊긴 IN_PROGRESS 세션(앱이 죽은 좀비)은 막지 않는다 — 탈퇴를 진행하고,
        // 세션은 users CASCADE 로, pose_data 는 아래 afterCommit 정리로 함께 사라진다.
    }
}
