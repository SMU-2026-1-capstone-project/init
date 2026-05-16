package com.shadowfit.dto.preference;

import com.shadowfit.model.member.Member;

import java.math.BigDecimal;

public record TtsPreferenceDto(
        Boolean ttsEnabled,
        BigDecimal ttsSpeed
) {
    public static TtsPreferenceDto fromEntity(Member member) {
        return new TtsPreferenceDto(
                member.getTtsEnabled(),
                member.getTtsSpeed()
        );
    }
}