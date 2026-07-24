package com.shadowfit.service.Member;

import com.shadowfit.dto.preference.TtsPreferenceDto;
import com.shadowfit.dto.preference.TtsPreferenceUpdateDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@DisplayName("PreferenceService 테스트")
class PreferenceServiceTest {

    @Mock private MemberRepository memberRepository;
    private PreferenceService service;

    private static final String EMAIL = "test@test.com";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new PreferenceService(memberRepository);
    }

    private Member freshMember() {
        return Member.builder().id(1L).email(EMAIL).username("u").password("p").role(UserRole.USER).build();
    }

    @Test
    @DisplayName("조회 — 기본값(ttsEnabled=true, ttsSpeed=1.0) 반환")
    void getTtsPreferences_returnsDefaults() {
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(freshMember()));

        TtsPreferenceDto result = service.getTtsPreferences(EMAIL);

        assertThat(result.ttsEnabled()).isTrue();
        assertThat(result.ttsSpeed()).isEqualByComparingTo(new BigDecimal("1.0"));
    }

    @Test
    @DisplayName("조회 — 존재하지 않으면 USER_NOT_FOUND")
    void getTtsPreferences_userNotFound_throws() {
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTtsPreferences(EMAIL))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("수정 — ttsEnabled만 바꾸면 ttsSpeed는 그대로")
    void updateTtsPreferences_onlyEnabled_leavesSpeedUnchanged() {
        Member member = freshMember();
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member));

        TtsPreferenceDto result = service.updateTtsPreferences(EMAIL, new TtsPreferenceUpdateDto(false, null));

        assertThat(result.ttsEnabled()).isFalse();
        assertThat(result.ttsSpeed()).isEqualByComparingTo(new BigDecimal("1.0")); // 그대로
    }

    @Test
    @DisplayName("수정 — ttsSpeed만 바꾸면 ttsEnabled는 그대로")
    void updateTtsPreferences_onlySpeed_leavesEnabledUnchanged() {
        Member member = freshMember();
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member));

        TtsPreferenceDto result = service.updateTtsPreferences(EMAIL, new TtsPreferenceUpdateDto(null, new BigDecimal("1.5")));

        assertThat(result.ttsEnabled()).isTrue(); // 그대로
        assertThat(result.ttsSpeed()).isEqualByComparingTo(new BigDecimal("1.5"));
    }

    @Test
    @DisplayName("수정 — 존재하지 않으면 USER_NOT_FOUND")
    void updateTtsPreferences_userNotFound_throws() {
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateTtsPreferences(EMAIL, new TtsPreferenceUpdateDto(true, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }
}
