package com.shadowfit.service.Member;

import com.shadowfit.dto.preference.TtsPreferenceDto;
import com.shadowfit.dto.preference.TtsPreferenceUpdateDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.member.Member;
import com.shadowfit.repository.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PreferenceService {
    private final MemberRepository memberRepository;

    public TtsPreferenceDto getTtsPreferences(String email) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return TtsPreferenceDto.fromEntity(member);
    }

    @Transactional
    public TtsPreferenceDto updateTtsPreferences(String email, TtsPreferenceUpdateDto dto) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        member.updateTtsPreferences(dto);
        return TtsPreferenceDto.fromEntity(member);
    }
}