package com.shadowfit.service.Member;

import com.shadowfit.dto.login.*;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.global.security.jwt.RefreshTokenHasher;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.RefreshToken;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.member.RefreshTokenRepository;
import com.shadowfit.service.Exercise.PoseDataCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService{
    private final JwtUtil jwtUtil;
    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SessionRepository sessionRepository;
    private final PoseDataCleanupService poseDataCleanupService;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenHasher refreshTokenHasher;

    /**
     * 이 시간 동안 프레임 유입이 없으면 그 세션은 죽은 것으로 본다(탈퇴 가드 판정 기준).
     *
     * <p><b>기준은 배치 간격이 아니라 세트 간 휴식이다.</b> 휴식 중에는 rep 이 완성되지 않아 AI 가
     * 콜백을 보내지 않으므로, Spring 이 보기엔 죽은 세션과 구분되지 않는다. 이 프로젝트는 이미 같은
     * 이유로 ET-C(AI timeout 자동 종료)를 거부한 적이 있다(tts-design.md §2.A: "세트 사이 휴식
     * 시간(30~90초)도 frame 안 오는 구간이라 휴식과 종료 구분 불가").
     *
     * <p>{@code restTimeSec = max(90 - (level-1)*5, 30)} → 최대 90초(초급자,
     * 12-persona-difficulty.md:90). 2배 여유를 둬 180초.
     */
    @Value("${member.withdrawal.active-workout-idle-seconds:180}")
    private long activeWorkoutIdleSeconds;

    /**
     * 재발급 재시도를 «탈취» 가 아니라 «응답을 못 받은 클라» 로 봐주는 유예 (이슈 #135).
     *
     * <p><b>근거는 클라의 HTTP timeout 이다</b> — {@code frontend/services/api.ts:35} 의
     * {@code timeout: 10000}. 클라가 포기했지만 서버는 이미 회전을 끝냈을 수 있는 구간이 정확히
     * 그만큼이고, 그 뒤 클라가 구본으로 다시 오면 유예가 없을 때 강제 로그아웃이 된다.
     *
     * <p>⚠️ <b>그 이상은 근거가 없어서 안 늘렸다</b>([[feedback_no_arbitrary_threshold_values]]).
     * 지금 프론트에는 재시도 로직이 <b>아예 없다</b> — 401 을 받으면 곧바로 {@code forceLogout} 이다
     * ({@code api.ts:63-70}). 재발급 흐름을 붙이면서 재시도 간격·횟수가 정해지면 이 값을 다시
     * 유도해야 한다. 그때까지 이 10초는 «타임아웃 1회분» 이고 그 이상을 주장하지 않는다.
     */
    private static final long REISSUE_RETRY_GRACE_SECONDS = 10;
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

        UserRole role = member.getRole();

        // 「1인 1세션」 — 기존 행이 있으면 **교체**한다 (이슈 #136, decisions/token-lifecycle.md §2 확정).
        //
        // 예전에는 매번 새 빌더를 save() 했다. PK 가 member_id 이고 @GeneratedValue 가 없어
        // Spring Data JPA 가 persist 가 아니라 merge 로 보내므로, 중복 키 예외가 아니라
        // **조용한 UPDATE** 였다 — 앞 기기 토큰이 실패 없이 사라졌다. 동작은 같지만 그게
        // 사고가 아니라 정책이라는 것이 코드에 안 보였다.
        //
        // ⚠️ rotate() 가 아니라 replaceForNewLogin() 이다. 차이는 rotated_at 을 **비운다**는 것이고,
        // 그게 없으면 앞 기기 토큰이 «직전 세대 + 유예 안» 에 걸려 재발급 시 새 세션의 토큰을
        // 받아간다 — 1인 1세션을 세워놓고 정반대로 동작하게 된다.
        RefreshToken entity = refreshTokenRepository.findById(member.getId())
                .orElseGet(() -> RefreshToken.builder().memberId(member.getId()).build());

        // 토큰 안의 ver 과 행의 token_version 이 **같아야** 한다. 그래서 다음 세대 번호를 먼저
        // 계산해 토큰에 싣고, 엔티티가 행을 그 값으로 올린다.
        String refreshToken = jwtUtil.createRefreshToken(info, entity.getTokenVersion() + 1);
        // 원문이 아니라 해시를 저장한다 (#185). 원문은 이 응답으로만 나간다.
        entity.replaceForNewLogin(refreshTokenHasher.hash(refreshToken));
        refreshTokenRepository.save(entity);

        String accessToken = jwtUtil.createAccessToken(info);
        return new LoginResponseDto(accessToken, refreshToken, role);
    }

    /**
     * refresh token 으로 access·refresh 를 재발급한다 (이슈 #135).
     *
     * <p><b>신원 근거는 refresh JWT 의 서명이다.</b> 이 경로는 access 가 만료된 뒤에 불리는 것이
     * 정상이라 인증 필터를 통과하지 못하고 {@code SecurityContext} 가 비어 있다. 서명이 유효하고
     * subject 가 그 회원이면 그것이 곧 소유권 증명이다 — 위조가 불가능하기 때문이다
     * ({@code decisions/token-lifecycle.md} §4-2).
     *
     * <p><b>세 갈래로 갈린다:</b>
     * <ol>
     *   <li>저장된 해시와 같다 → 정상 회전</li>
     *   <li>직전 세대 + 유예 안 → <b>응답을 못 받은 클라의 재시도</b>로 본다. <b>새 토큰을 회전
     *       발급</b>한다 (#185 ㄱ — 해시 저장이라 저장값을 되돌려줄 수 없다.
     *       § {@code REISSUE_RETRY_GRACE_SECONDS})</li>
     *   <li>그 외 → 폐기된 구본이 왔다 → 세션을 끊는다</li>
     * </ol>
     *
     * <p>3번의 판정 근거가 «token 불일치» 하나인 것에 주의. 세대 번호는 여기 안 쓴다 — 서명이
     * 유효한데 저장된 것과 다른 토큰은 <b>정의상</b> 우리가 발급했던 구본이다.
     *
     * <p>⚠️ <b>3번은 «탈취» 와 «낡은 기기» 를 구분하지 못한다.</b> 1인 1세션이라 행이 하나뿐이고,
     * 둘 다 «옛 토큰이 도착했다» 로 똑같이 보인다. 보수적인 쪽(끊는다)을 택했다 — 사용자는 이미
     * «다른 기기에서 로그인하면 로그아웃된다» 를 전제로 하므로 재로그인이 정책 안에 있고, 반대로
     * 관대하게 두면 탐지를 넣은 의미가 사라진다. 뒤집으려면 이 분기 하나만 바꾸면 된다.
     */
    @Transactional
    public LoginResponseDto reissue(ReissueRequestDto dto) {
        String presented = dto.getRefreshToken();

        // 서명·만료 검증이 먼저다. 여기서 걸리면 DB 를 볼 이유가 없다.
        if (!jwtUtil.isValidToken(presented)) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        Member member = memberRepository.findByEmail(jwtUtil.getUserEmail(presented))
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        RefreshToken entity = refreshTokenRepository.findById(member.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN));

        CustomUserInfoDto info = CustomUserInfoDto.builder()
                .email(member.getEmail())
                .role(member.getRole())
                .build();
        LocalDateTime now = LocalDateTime.now();

        // 저장값은 이제 해시라(#185) 원문 대조 대신 해시 대조를 한다. presented 는 클라가 보낸
        // 원문 JWT 이므로 같은 함수로 해싱해 맞춘다.
        String presentedHash = refreshTokenHasher.hash(presented);

        // (2) 재시도 유예 — 응답을 못 받은 클라가 직전 토큰으로 다시 온 경우 (#135).
        //
        // 🔴 예전엔 «회전하지 않고 저장된 토큰을 그대로 돌려줬다». 해시 저장(#185)으로는 저장값을
        //    돌려줄 수 없어(원문이 없다), 여기서 **새 토큰을 회전 발급**한다(안 ㄱ). 그 대가로
        //    유예의 성질이 바뀐다 — 응답이 **한 번** 유실되면 흡수하지만, 그 재발급 응답까지
        //    **연달아** 유실되면 다음 재시도는 이미 두 세대 전이 되어 유예 밖(=아래 (3) revoke)이
        //    된다. 예전(원문 반환)은 반복 유실도 견뎠다. 실사용자·재시도 로직이 없는 현재 이 꼬리는
        //    체감 0 이고, 실사용자가 생기면 가역 암호화(안 ㄷ)로 승격을 재검토한다
        //    (docs/decisions/ai-session-ownership-verification.md 와 무관, 토큰 문서 참조).
        if (!entity.getToken().equals(presentedHash)
                && entity.isWithinRetryGrace(jwtUtil.getTokenVersion(presented), now, REISSUE_RETRY_GRACE_SECONDS)) {
            String reissued = jwtUtil.createRefreshToken(info, entity.getTokenVersion() + 1);
            entity.rotate(refreshTokenHasher.hash(reissued), now);
            refreshTokenRepository.save(entity);
            return new LoginResponseDto(jwtUtil.createAccessToken(info), reissued, member.getRole());
        }

        // (3) 폐기된 구본 — 세션을 끊는다.
        if (!entity.getToken().equals(presentedHash)) {
            refreshTokenRepository.deleteByMemberId(member.getId());
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_REUSED);
        }

        // (1) 정상 회전 — 해시를 저장한다.
        String rotated = jwtUtil.createRefreshToken(info, entity.getTokenVersion() + 1);
        entity.rotate(refreshTokenHasher.hash(rotated), now);
        refreshTokenRepository.save(entity);

        return new LoginResponseDto(jwtUtil.createAccessToken(info), rotated, member.getRole());
    }

    /**
     * 로그아웃 — <b>refresh token 행을 지우는 것이 전부다.</b>
     *
     * <p>지울 대상을 본문이 아니라 <b>요청자</b>로 정한다(decisions/token-lifecycle.md §1-1-ㄴ).
     * 예전 방식은 남의 토큰 값을 알면 남의 행을 지울 수 있었고, 반대로 그 행이 이미 새 로그인으로
     * 교체됐으면 <b>0행을 지우고 성공했다</b> — 로그아웃했다고 응답하는데 서버에는 세션이 남는
     * 쪽이 실제로 더 위험하다.
     *
     * <p><b>access token 은 즉시 죽지 않는다</b>(이슈 #137, 같은 문서 ㄴ-4). 이미 발급된 access 는
     * 남은 수명(30분 상한) 동안 유효하고, 그 대가로 블랙리스트라는 상태를 두지 않는다. 로그아웃의
     * 의미가 «서버가 즉시 끊는다» 가 아니라 <b>«갱신이 끊기고 곧 만료된다»</b> 로 바뀐 것이다.
     */
    @Transactional
    public void logout(String requesterEmail){
        Member requester = memberRepository.findByEmail(requesterEmail)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        refreshTokenRepository.deleteByMemberId(requester.getId());
    }

    /**
     * 회원가입.
     *
     * <p><b>두 층으로 막는다</b>(이슈 #195). {@code users} 에는 UNIQUE 가 <b>둘</b> 있는데
     * ({@code V1__baseline.sql:26}·{@code :28}) 사전검사가 email 에만 있어, 겹친 username 으로
     * 가입하면 {@code DataIntegrityViolationException} 이 {@code GlobalExceptionHandler} 의
     * {@code Exception} 핸들러로 떨어져 <b>400 이 아니라 500</b> 이 나갔다.
     *
     * <p>① <b>사전검사</b> — 순차 재현(같은 닉네임으로 두 번 가입)을 닫는다. 사람이 실제로 만나는
     * 경로가 이쪽이다. 이슈의 발견 경로도 E1 드라이버가 username 을 재사용한 것이었다.
     *
     * <p>② <b>제약 위반 수신</b> — ①과 INSERT 사이에 같은 값이 들어오는 레이스가 남으므로,
     * 막는 대신 <b>받아서 같은 4xx 로 바꾼다</b>. {@code AdminExerciseService.deleteExercise}
     * (:259)가 FK 제약에 대해 이미 같은 모양을 쓴다 — 잠금으로 직렬화하려면 가입 경로 전체를
     * 묶어야 하는데, 사용자당 한 번 일어나는 동작이 살 비용이 아니다.
     *
     * <p>🔴 <b>어느 제약이 걸렸는지는 재조회로 가르지 않는다.</b> email·username 둘 다 UNIQUE 라
     * 예외만으로는 구분이 안 되는데, 제약 위반 직후의 영속성 컨텍스트는 JPA 명세상 <b>상태가
     * 정의되지 않는다</b>(트랜잭션은 이미 rollback-only). 거기서 {@code existsByEmail} 을 한 번 더
     * 도는 것은 동작하더라도 보장이 없는 자리다. 그래서 예외가 <b>구조적으로</b> 들고 오는
     * 제약명({@code ConstraintViolationException#getConstraintName()})으로 가른다 — 조회 0회다.
     */
    @Transactional
    public String signup(MemberRequestDto dto) {
        if(memberRepository.existsByEmail((dto.getEmail()))) {
            throw new BusinessException(ErrorCode.USERID_DUPLICATION);
        }
        if (memberRepository.existsByUsername(dto.getUsername())) {
            throw new BusinessException(ErrorCode.USERNAME_DUPLICATION);
        }
        String encodedPassword = passwordEncoder.encode(dto.getPassword());
        // sex 는 지금까지 DTO 로 받기만 하고 엔티티에 옮기지 않아 조용히 버려졌다 —
        // Swagger 는 REQUIRED 라고 표기하는데 저장은 안 되던 상태였고, 그래서 members.sex 에
        // 값이 닿은 적이 없어 Sex enum 의 철자 오류(FEAMALE)도 여태 잠복해 있었다.
        Member member = Member.builder()
                .username(dto.getUsername())
                .email(dto.getEmail())
                .password(encodedPassword)
                // .role(...) 을 부르지 않는다. Member 의 @Builder.Default(UserRole.USER)가
                // 적용되며, 빌더에서 명시 호출하면 그 default 가 덮어써지므로 — 이 줄이 없는 것이
                // 곧 "서버가 권한을 고정한다"이다 (이슈 #138, decisions/admin-role-provisioning.md).
                .sex(dto.getSex())
                .build();
        try {
            memberRepository.save(member);
            // Member 는 IDENTITY 라 save() 가 곧 INSERT 지만, 그 사실에 기대지 않는다 —
            // 전략이 바뀌면(#211 이 batch insert 를 이유로 건드리는 자리다) 제약 위반이 커밋
            // 시점으로 밀려 이 try 를 빠져나간 뒤 터진다. deleteExercise(:273)와 같은 이유다.
            memberRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw duplicationOf(e);
        }
        return member.getUsername();
    }

    /**
     * 사전검사와 INSERT 사이에 낀 UNIQUE 위반을 해당 필드의 4xx 로 옮긴다(이슈 #195 ②층).
     *
     * <p>제약명은 MySQL 이 {@code Duplicate entry 'e1runner' for key 'users.username'} 으로 주고
     * Hibernate 의 dialect 가 그중 {@code users.username} 을 뽑아 온다. 테이블 접두사가 붙는지는
     * 버전에 따라 다르므로 <b>포함 관계로</b> 본다.
     *
     * <p><b>모르는 제약은 그대로 500 으로 보낸다.</b> {@code DataIntegrityViolationException} 은
     * UNIQUE 전용이 아니라 FK·NOT NULL·JSON 형식 위반({@code AdminExerciseService:292})에서도
     * 나오고, 그쪽은 대개 <b>클라 잘못이 아니라 서버 결함</b>이다. 싸잡아 4xx 로 바꾸면 진짜 결함이
     * "사용자가 중복을 냈다"로 위장되고 {@code log.error} 도 사라져 조용해진다. 여기서 4xx 로
     * 바꾸는 것은 <b>이름을 확인한 두 제약뿐</b>이다.
     */
    private RuntimeException duplicationOf(DataIntegrityViolationException e) {
        String constraint = (e.getCause() instanceof ConstraintViolationException cve)
                ? cve.getConstraintName()
                : null;
        if (constraint == null) {
            return e;
        }
        String name = constraint.toLowerCase(Locale.ROOT);
        if (name.contains("username")) {
            log.warn("가입 경합 — 사전검사 후 username 이 선점됨 (constraint={})", constraint);
            return new BusinessException(ErrorCode.USERNAME_DUPLICATION);
        }
        if (name.contains("email")) {
            log.warn("가입 경합 — 사전검사 후 email 이 선점됨 (constraint={})", constraint);
            return new BusinessException(ErrorCode.USERID_DUPLICATION);
        }
        return e;
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
     * <p>임계값의 기준은 배치 간격(~3~4초)이 아니라 <b>세트 간 휴식(최대 90초)</b>이다. 짧게 잡으면
     * 쉬는 중인 사용자를 죽은 것으로 오판해 <b>정말 운동 중인데 탈퇴가 통과</b>한다 — 그건 막으려던
     * 결함 그 자체라, 반대 방향 오판(죽은 세션 때문에 몇 분 더 기다림)보다 훨씬 나쁘다.
     *
     * <p>🔴 <b>"유입"을 세는 자리가 pose_data 가 아니다</b> (#317). 원안은 {@code pose_data.created_at}
     * 하한을 셌는데, #188 멱등이 들어오면서 그 컬럼이 <b>세션 시작 시각으로 고정</b>됐다 — 한 세션의
     * 모든 행이 같은 값을 갖는다. 그래서 가드가 묻던 질문이 "최근 180초 안에 프레임이 들어왔나"에서
     * <b>"세션이 최근 180초 안에 시작됐나"</b>로 바뀌었고, 4분째 운동 중인 사용자가 그냥 통과했다
     * (거짓 음성). 지금은 배치마다 갱신되는 {@code exercise_sessions.last_active_at} 을 본다.
     */
    private void requireNoActiveWorkout(Long memberId) {
        List<Long> inProgressIds =
                sessionRepository.findIdsByMemberIdAndStatus(memberId, Status.IN_PROGRESS);
        if (inProgressIds.isEmpty()) {
            return;
        }

        LocalDateTime since = LocalDateTime.now().minusSeconds(activeWorkoutIdleSeconds);
        if (sessionRepository.countActiveSince(inProgressIds, since) > 0) {
            throw new BusinessException(ErrorCode.WITHDRAWAL_BLOCKED_BY_ACTIVE_SESSION);
        }
        // 유입이 끊긴 IN_PROGRESS 세션(앱이 죽은 좀비)은 막지 않는다 — 탈퇴를 진행하고,
        // 세션은 users CASCADE 로, pose_data 는 아래 afterCommit 정리로 함께 사라진다.
    }
}
