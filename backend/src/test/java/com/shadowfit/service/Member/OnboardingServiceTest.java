package com.shadowfit.service.Member;

import com.shadowfit.dto.onboarding.OnboardingDto;
import com.shadowfit.dto.onboarding.OnboardingRequestDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.model.member.WorkoutLevel;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@DisplayName("OnboardingService 테스트")
class OnboardingServiceTest {

    @Mock private MemberRepository memberRepository;
    private OnboardingService service;

    private static final String EMAIL = "test@test.com";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new OnboardingService(memberRepository);
    }

    private Member freshMember() {
        return Member.builder().id(1L).email(EMAIL).username("u").password("p").role(UserRole.USER).build();
    }

    @Test
    @DisplayName("필수 5개 필드가 모두 채워지면 onboardingCompleted가 true로 바뀜")
    void updateOnboarding_allFieldsPresent_completesOnboarding() {
        Member member = freshMember();
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member));
        OnboardingRequestDto dto = OnboardingRequestDto.builder()
                .selectedPersona(SelectedPersona.ADVANCED)
                .workoutLevel(WorkoutLevel.BEGINNER)
                .height(180.0).weight(75.0)
                .preferredUrl("https://youtu.be/dummy")
                .build();

        OnboardingDto result = service.updateOnboarding(EMAIL, dto);

        assertThat(member.isOnboardingCompleted()).isTrue();
        assertThat(result.getHeight()).isEqualTo(180.0);
    }

    @Test
    @DisplayName("일부 필드만 채워지면 onboardingCompleted는 그대로 false (부분 저장)")
    void updateOnboarding_partialFields_doesNotComplete() {
        Member member = freshMember();
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member));
        OnboardingRequestDto dto = OnboardingRequestDto.builder()
                .selectedPersona(SelectedPersona.ADVANCED)
                .build(); // 나머지 4개는 null

        service.updateOnboarding(EMAIL, dto);

        assertThat(member.isOnboardingCompleted()).isFalse();
        assertThat(member.getSelectedPersona()).isEqualTo(SelectedPersona.ADVANCED);
    }

    @Test
    @DisplayName("존재하지 않는 이메일이면 USER_NOT_FOUND")
    void updateOnboarding_userNotFound_throws() {
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateOnboarding(EMAIL, new OnboardingRequestDto()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("readOnboarding — 존재하면 현재 값 그대로 반환")
    void readOnboarding_success() {
        Member member = freshMember();
        member.updateOnboarding(OnboardingRequestDto.builder().height(170.0).build());
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member));

        OnboardingDto result = service.readOnboarding(EMAIL);

        assertThat(result.getHeight()).isEqualTo(170.0);
    }

    @Test
    @DisplayName("readOnboarding — 존재하지 않으면 USER_NOT_FOUND")
    void readOnboarding_userNotFound_throws() {
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.readOnboarding(EMAIL))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }
}
