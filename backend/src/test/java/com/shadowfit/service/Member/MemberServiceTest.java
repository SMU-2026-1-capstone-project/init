package com.shadowfit.service.Member;

import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.dto.login.LoginRequestDto;
import com.shadowfit.dto.login.LoginResponseDto;
import com.shadowfit.dto.login.LogOutRequestDto;
import com.shadowfit.dto.login.MemberRequestDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.security.jwt.JwtBlacklist;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.RefreshToken;
import com.shadowfit.model.member.Sex;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.member.RefreshTokenRepository;
import com.shadowfit.service.Exercise.PoseDataCleanupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MemberService 단위 테스트 — 회원가입/로그인/로그아웃/탈퇴. happy path뿐 아니라 실패 경로
 * (중복 이메일, 비밀번호 불일치, 존재하지 않는 회원)와 탈퇴 시 pose_data 비동기 정리 트리거
 * 조건(afterCommit 이전엔 호출 안 됨, 세션 없으면 등록 자체를 안 함)까지 검증한다.
 *
 * pose-data-partition-fk-tradeoff.md §5(B5) — 탈퇴는 회원이 쌓은 세션 규모가 커질 수 있어
 * pose_data 정리를 커밋 직후 비동기로 트리거하는 구조. 오늘 refresh_token·body_records에서
 * CASCADE 누락 버그를 두 번 발견한 그 경로라 이 서비스 자체의 자동 테스트가 중요.
 */
@DisplayName("MemberService 테스트")
class MemberServiceTest {

    @Mock private JwtUtil jwtUtil;
    @Mock private MemberRepository memberRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private PoseDataCleanupService poseDataCleanupService;
    @Mock private PasswordEncoder passwordEncoder;

    private JwtBlacklist jwtBlacklist; // 순수 인메모리 맵이라 모킹 없이 실사용
    private MemberService memberService;

    private static final String EMAIL = "test@test.com";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        jwtBlacklist = new JwtBlacklist();
        memberService = new MemberService(jwtUtil, memberRepository, refreshTokenRepository,
                sessionRepository, poseDataCleanupService, passwordEncoder, jwtBlacklist);
    }

    @Nested
    @DisplayName("회원가입")
    class Signup {

        @Test
        @DisplayName("정상 가입 — 비밀번호 인코딩 후 저장, username 반환")
        void signup_success() {
            MemberRequestDto dto = new MemberRequestDto("user1", EMAIL, "raw-pw", Sex.MALE, UserRole.USER);
            when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
            when(passwordEncoder.encode("raw-pw")).thenReturn("encoded-pw");

            String result = memberService.signup(dto);

            assertThat(result).isEqualTo("user1");
            ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
            verify(memberRepository).save(captor.capture());
            assertThat(captor.getValue().getPassword()).isEqualTo("encoded-pw");
            assertThat(captor.getValue().getEmail()).isEqualTo(EMAIL);
        }

        @Test
        @DisplayName("이미 가입된 이메일이면 USERID_DUPLICATION, 저장 안 함")
        void signup_duplicateEmail_throws() {
            MemberRequestDto dto = new MemberRequestDto("user1", EMAIL, "raw-pw", Sex.MALE, UserRole.USER);
            when(memberRepository.existsByEmail(EMAIL)).thenReturn(true);

            assertThatThrownBy(() -> memberService.signup(dto))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USERID_DUPLICATION);

            verify(memberRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("로그인")
    class Login {

        private Member existingMember() {
            return Member.builder().id(1L).email(EMAIL).username("user1")
                    .password("encoded-pw").role(UserRole.USER).build();
        }

        @Test
        @DisplayName("정상 로그인 — 토큰 발급 + refresh_token 저장")
        void login_success() {
            Member member = existingMember();
            LoginRequestDto dto = new LoginRequestDto(EMAIL, "raw-pw");
            when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member));
            when(passwordEncoder.matches("raw-pw", "encoded-pw")).thenReturn(true);
            when(jwtUtil.createAccessToken(any(CustomUserInfoDto.class))).thenReturn("access-token");
            when(jwtUtil.createRefreshToken(any(CustomUserInfoDto.class))).thenReturn("refresh-token");

            LoginResponseDto result = memberService.login(dto);

            assertThat(result.getAccessToken()).isEqualTo("access-token");
            assertThat(result.getRefreshToken()).isEqualTo("refresh-token");
            ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
            verify(refreshTokenRepository).save(captor.capture());
            assertThat(captor.getValue().getMemberId()).isEqualTo(1L);
            assertThat(captor.getValue().getToken()).isEqualTo("refresh-token");
        }

        @Test
        @DisplayName("존재하지 않는 이메일이면 USER_NOT_FOUND")
        void login_userNotFound_throws() {
            when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> memberService.login(new LoginRequestDto(EMAIL, "raw-pw")))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND);

            verify(jwtUtil, never()).createAccessToken(any());
        }

        @Test
        @DisplayName("비밀번호 불일치면 LOGIN_INPUT_INVALID, 토큰 발급 안 함")
        void login_wrongPassword_throws() {
            Member member = existingMember();
            when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member));
            when(passwordEncoder.matches("wrong-pw", "encoded-pw")).thenReturn(false);

            assertThatThrownBy(() -> memberService.login(new LoginRequestDto(EMAIL, "wrong-pw")))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.LOGIN_INPUT_INVALID);

            verify(jwtUtil, never()).createAccessToken(any());
            verify(refreshTokenRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("로그아웃")
    class Logout {

        @Test
        @DisplayName("refresh_token 삭제 + access token 블랙리스트 등록 (Bearer 접두어 제거)")
        void logout_bearerPrefix_stripped() {
            when(jwtUtil.getExpiration("raw-access-token")).thenReturn(123456L);
            LogOutRequestDto dto = LogOutRequestDto.builder()
                    .accessToken("Bearer raw-access-token")
                    .refreshToken("some-refresh-token")
                    .build();

            memberService.logout(dto);

            verify(refreshTokenRepository).deleteByToken("some-refresh-token");
            assertThat(jwtBlacklist.isBlacklisted("raw-access-token")).isTrue();
        }

        @Test
        @DisplayName("Bearer 접두어 없는 토큰도 그대로 블랙리스트 등록됨")
        void logout_withoutBearerPrefix_stillWorks() {
            when(jwtUtil.getExpiration("plain-token")).thenReturn(123456L);
            LogOutRequestDto dto = LogOutRequestDto.builder()
                    .accessToken("plain-token")
                    .refreshToken("some-refresh-token")
                    .build();

            memberService.logout(dto);

            assertThat(jwtBlacklist.isBlacklisted("plain-token")).isTrue();
        }
    }

    @Nested
    @DisplayName("회원 탈퇴 (pose-data-partition-fk-tradeoff.md §5, B5)")
    class DeleteAccount {

        @Test
        @DisplayName("존재하지 않는 이메일이면 USER_NOT_FOUND, delete 호출 안 함")
        void deleteAccount_memberNotFound_throws() {
            when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> memberService.deleteAccount(EMAIL))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND);

            verify(memberRepository, never()).delete(any());
        }

        @Test
        @DisplayName("세션이 있으면 삭제 후 커밋 시점에만 pose_data 비동기 정리가 트리거됨")
        void deleteAccount_withSessions_triggersCleanupOnlyAfterCommit() {
            Member member = Member.builder().id(1L).email(EMAIL).username("u").password("p").build();
            when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member));
            when(sessionRepository.findIdsByMemberId(1L)).thenReturn(List.of(101L, 102L));

            // 실제 트랜잭션 없이도 동기화 등록/발동만 검증하기 위해 동기화 컨텍스트를 직접 활성화
            TransactionSynchronizationManager.initSynchronization();
            try {
                memberService.deleteAccount(EMAIL);

                verify(memberRepository).delete(member);
                // 커밋 전이라 아직 호출되면 안 됨
                verify(poseDataCleanupService, never()).cleanupBySessionIds(any());

                List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
                assertThat(syncs).hasSize(1);
                syncs.forEach(TransactionSynchronization::afterCommit); // 커밋 시뮬레이션

                verify(poseDataCleanupService, times(1)).cleanupBySessionIds(List.of(101L, 102L));
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }

        @Test
        @DisplayName("세션이 없으면 커밋 동기화 자체를 등록하지 않음 (불필요한 비동기 트리거 방지)")
        void deleteAccount_withoutSessions_registersNoSynchronization() {
            Member member = Member.builder().id(1L).email(EMAIL).username("u").password("p").build();
            when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member));
            when(sessionRepository.findIdsByMemberId(1L)).thenReturn(List.of());

            TransactionSynchronizationManager.initSynchronization();
            try {
                memberService.deleteAccount(EMAIL);

                verify(memberRepository).delete(member);
                assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }
}
