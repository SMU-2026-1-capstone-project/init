package com.shadowfit.service.Member;

import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.dto.login.LoginRequestDto;
import com.shadowfit.dto.login.LoginResponseDto;
import com.shadowfit.dto.login.MemberRequestDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.RefreshToken;
import com.shadowfit.model.member.Sex;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.member.RefreshTokenRepository;
import com.shadowfit.service.Exercise.PoseDataCleanupService;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock private PoseDataRepository poseDataRepository;
    @Mock private PoseDataCleanupService poseDataCleanupService;
    @Mock private PasswordEncoder passwordEncoder;

    private MemberService memberService;

    // Login·Logout 두 중첩 클래스가 함께 쓴다. 원래 Login 안에 private 으로 있었는데,
    // 로그아웃이 요청자 기준으로 바뀌며(#135 §1-1-ㄴ) 거기서도 회원 조회가 필요해졌다.
    private Member existingMember() {
        return Member.builder().id(1L).email(EMAIL).username("user1")
                .password("encoded-pw").role(UserRole.USER).build();
    }

    private static final String EMAIL = "test@test.com";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        memberService = new MemberService(jwtUtil, memberRepository, refreshTokenRepository,
                sessionRepository, poseDataRepository, poseDataCleanupService, passwordEncoder);
    }

    @Nested
    @DisplayName("회원가입")
    class Signup {

        @Test
        @DisplayName("정상 가입 — 비밀번호 인코딩 후 저장, username 반환")
        void signup_success() {
            MemberRequestDto dto = new MemberRequestDto("user1", EMAIL, "raw-pw", Sex.MALE);
            when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
            when(passwordEncoder.encode("raw-pw")).thenReturn("encoded-pw");

            String result = memberService.signup(dto);

            assertThat(result).isEqualTo("user1");
            ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
            verify(memberRepository).save(captor.capture());
            assertThat(captor.getValue().getPassword()).isEqualTo("encoded-pw");
            assertThat(captor.getValue().getEmail()).isEqualTo(EMAIL);
        }

        /**
         * 이슈 #138 — 가입은 권한을 요청자에게서 받지 않는다.
         *
         * <p>이 테스트가 지키는 것은 «서버가 USER 로 고정한다»는 계약이고, 그 고정을 보장하는 것은
         * 검증 로직이 아니라 <b>{@code MemberRequestDto} 에 role 필드가 없다는 사실</b> 하나다.
         * 그래서 이 테스트는 «필드를 되살리는 변경» 을 잡는 자리다 — 누군가 DTO 에 role 을 다시
         * 넣고 {@code .role(dto.getRole())} 을 복원하면 여기서 깨진다.
         *
         * <p>DTO 에 필드가 없으니 «ADMIN 을 보내는» 것을 이 계층에서는 표현할 수 없다. HTTP 레벨의
         * 실제 공격 재현은 {@code MemberControllerIntegrationTest.signup_withAdminRole_isIgnored}
         * 가 raw JSON 으로 담당한다.
         */
        @Test
        @DisplayName("#138 가입 권한은 서버가 USER 로 고정한다 — 클라이언트가 정할 수 없다")
        void signup_ignoresClientSuppliedRole() {
            MemberRequestDto dto = new MemberRequestDto("user1", EMAIL, "raw-pw", Sex.MALE);
            when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
            when(passwordEncoder.encode("raw-pw")).thenReturn("encoded-pw");

            memberService.signup(dto);

            ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
            verify(memberRepository).save(captor.capture());
            assertThat(captor.getValue().getRole()).isEqualTo(UserRole.USER);
        }

        @Test
        @DisplayName("이미 가입된 이메일이면 USERID_DUPLICATION, 저장 안 함")
        void signup_duplicateEmail_throws() {
            MemberRequestDto dto = new MemberRequestDto("user1", EMAIL, "raw-pw", Sex.MALE);
            when(memberRepository.existsByEmail(EMAIL)).thenReturn(true);

            assertThatThrownBy(() -> memberService.signup(dto))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USERID_DUPLICATION);

            verify(memberRepository, never()).save(any());
        }

        /**
         * 이슈 #195 ①층 — 순차 재현(같은 닉네임으로 두 번 가입)을 닫는 자리.
         *
         * <p>이 검사가 없을 때 나가던 것은 4xx 가 아니라 <b>500</b> 이었다. UNIQUE 는 DDL 에
         * 있었으므로({@code V1__baseline.sql:28}) 데이터가 더럽혀진 적은 없고, 정상적으로 쓰는
         * 사용자가 서버 결함처럼 보이는 응답을 받는 것이 결함이었다.
         */
        @Test
        @DisplayName("#195 이미 쓰는 닉네임이면 USERNAME_DUPLICATION, 저장 안 함")
        void signup_duplicateUsername_throws() {
            MemberRequestDto dto = new MemberRequestDto("user1", EMAIL, "raw-pw", Sex.MALE);
            when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
            when(memberRepository.existsByUsername("user1")).thenReturn(true);

            assertThatThrownBy(() -> memberService.signup(dto))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USERNAME_DUPLICATION);

            verify(memberRepository, never()).save(any());
        }

        /**
         * 이슈 #195 ②층 — 사전검사를 통과한 뒤 INSERT 가 제약에 걸리는 경우(동시 가입).
         *
         * <p>여기서 고정하는 것은 <b>제약명으로 필드를 가른다</b>는 판단이다. email·username 이
         * 둘 다 UNIQUE 라 예외 타입만으로는 구분되지 않고, 제약 위반 직후에 재조회로 확인하는
         * 길은 영속성 컨텍스트 상태가 정의되지 않아 못 쓴다({@code MemberService.signup} 주석).
         *
         * <p>제약명 문자열은 MySQL 이 {@code for key 'users.username'} 으로 주는 것을 Hibernate
         * dialect 가 뽑아온 값이다 — 실제 값은 {@code SignupUsernameRaceTest} 가 진짜 MySQL 로
         * 확인한다. 이 단위 테스트는 그 값을 <b>가정</b>하므로, 둘이 짝이다.
         */
        @Test
        @DisplayName("#195 경합 — username 제약에 걸리면 USERNAME_DUPLICATION 으로 옮긴다")
        void signup_usernameConstraintRace_mapsTo4xx() {
            MemberRequestDto dto = new MemberRequestDto("user1", EMAIL, "raw-pw", Sex.MALE);
            when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
            when(memberRepository.existsByUsername("user1")).thenReturn(false);
            when(passwordEncoder.encode("raw-pw")).thenReturn("encoded-pw");
            doThrow(constraintViolation("users.username")).when(memberRepository).save(any());

            assertThatThrownBy(() -> memberService.signup(dto))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USERNAME_DUPLICATION);
        }

        @Test
        @DisplayName("#195 경합 — email 제약에 걸리면 USERID_DUPLICATION 으로 옮긴다")
        void signup_emailConstraintRace_mapsTo4xx() {
            MemberRequestDto dto = new MemberRequestDto("user1", EMAIL, "raw-pw", Sex.MALE);
            when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
            when(memberRepository.existsByUsername("user1")).thenReturn(false);
            when(passwordEncoder.encode("raw-pw")).thenReturn("encoded-pw");
            doThrow(constraintViolation("users.email")).when(memberRepository).save(any());

            assertThatThrownBy(() -> memberService.signup(dto))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USERID_DUPLICATION);
        }

        /**
         * 🔴 이 테스트가 지키는 것은 «고치는 범위» 다.
         *
         * <p>{@code DataIntegrityViolationException} 은 UNIQUE 전용이 아니라 FK·NOT NULL 위반에서도
         * 나오고 그쪽은 대개 서버 결함이다. 싸잡아 4xx 로 바꾸면 진짜 버그가 "사용자가 중복을 냈다"로
         * 위장되고 {@code log.error} 도 사라진다. 그래서 <b>이름을 확인한 두 제약만</b> 옮기고
         * 나머지는 원래 예외 그대로 500 으로 내보낸다. 누가 나중에 이 처리를 전역으로 넓히면
         * 여기서 깨진다.
         */
        @Test
        @DisplayName("#195 모르는 제약 위반은 4xx 로 감추지 않고 그대로 올려보낸다")
        void signup_unknownConstraint_isNotSwallowed() {
            MemberRequestDto dto = new MemberRequestDto("user1", EMAIL, "raw-pw", Sex.MALE);
            when(memberRepository.existsByEmail(EMAIL)).thenReturn(false);
            when(memberRepository.existsByUsername("user1")).thenReturn(false);
            when(passwordEncoder.encode("raw-pw")).thenReturn("encoded-pw");
            doThrow(constraintViolation("fk_something_else")).when(memberRepository).save(any());

            assertThatThrownBy(() -> memberService.signup(dto))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        private DataIntegrityViolationException constraintViolation(String constraintName) {
            return new DataIntegrityViolationException(
                    "could not execute statement",
                    new ConstraintViolationException(
                            "Duplicate entry for key '" + constraintName + "'",
                            new SQLException("duplicate", "23000", 1062),
                            constraintName));
        }
    }

    @Nested
    @DisplayName("로그인")
    class Login {

        @Test
        @DisplayName("정상 로그인 — 토큰 발급 + refresh_token 저장")
        void login_success() {
            Member member = existingMember();
            LoginRequestDto dto = new LoginRequestDto(EMAIL, "raw-pw");
            when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member));
            when(passwordEncoder.matches("raw-pw", "encoded-pw")).thenReturn(true);
            when(jwtUtil.createAccessToken(any(CustomUserInfoDto.class))).thenReturn("access-token");
            when(jwtUtil.createRefreshToken(any(CustomUserInfoDto.class), anyLong())).thenReturn("refresh-token");

            LoginResponseDto result = memberService.login(dto);

            assertThat(result.getAccessToken()).isEqualTo("access-token");
            assertThat(result.getRefreshToken()).isEqualTo("refresh-token");
            ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
            verify(refreshTokenRepository).save(captor.capture());
            assertThat(captor.getValue().getMemberId()).isEqualTo(1L);
            assertThat(captor.getValue().getToken()).isEqualTo("refresh-token");
            // 로그인은 유예를 열지 않는다 (이슈 #136). 열면 앞 기기가 재발급으로 새 세션의
            // refresh token 을 받아가서 「1인 1세션」의 정반대가 된다.
            assertThat(captor.getValue().getRotatedAt()).isNull();
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
        @DisplayName("요청자의 refresh_token 행만 지운다 — 토큰 값은 보지 않는다")
        void logout_deletesByRequester() {
            when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existingMember()));

            memberService.logout(EMAIL);

            // 본문의 refresh token 이 아니라 **요청자** 기준으로 지운다 (§1-1-ㄴ).
            // 본문 기준이면 ① 남의 토큰 값을 알면 남의 행을 지울 수 있고 ② 그 행이 이미 새
            // 로그인으로 교체된 뒤면 0행을 지우고 «로그아웃 성공» 을 응답한다.
            verify(refreshTokenRepository).deleteByMemberId(1L);
            verify(refreshTokenRepository, never()).deleteByToken(anyString());
        }

        @Test
        @DisplayName("access token 은 건드리지 않는다 — 블랙리스트가 없다 (#137 ㄴ-4)")
        void logout_doesNotTouchAccessToken() {
            when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existingMember()));

            memberService.logout(EMAIL);

            // 예전에는 여기서 access token 을 파싱해 만료시각을 뽑고 블랙리스트에 넣었다.
            // 이 단언이 처음엔 `verify(jwtUtil, never()).getExpiration(...)` 이었는데,
            // **그 메서드 자체를 지우면서 컴파일이 안 되는 단언**이 됐다 — 되살리려면 메서드부터
            // 되살려야 하므로 보장은 오히려 강해졌다(#138 을 «필드 제거» 로 닫은 것과 같은 형태).
            // 여기서는 로그아웃이 jwtUtil 을 아예 안 쓴다는 사실만 남긴다.
            verifyNoInteractions(jwtUtil);
        }

        @Test
        @DisplayName("없는 회원이면 USER_NOT_FOUND — 삭제는 시도하지 않는다")
        void logout_memberNotFound_throws() {
            when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> memberService.logout(EMAIL))
                    .isInstanceOf(BusinessException.class);

            verify(refreshTokenRepository, never()).deleteByMemberId(anyLong());
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
            // 탈퇴 가드가 createSession 과 같은 회원 행 락을 잡는다(이슈 #87). 진행 중 세션 조회는
            // 스텁하지 않아 빈 목록이 오므로 가드는 통과한다 — 가드 자체의 검증은
            // MemberWithdrawalGuardTest(실제 DB) 몫이고, 여기 관심사는 정리 트리거 조건이다.
            when(memberRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(member));
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
            when(memberRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(member));
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
