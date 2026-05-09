package com.shadowfit.controller;

import com.shadowfit.dto.preference.TtsPreferenceDto;
import com.shadowfit.dto.preference.TtsPreferenceUpdateDto;
import com.shadowfit.service.Member.PreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.userdetails.UserDetails;

@Tag(name = "사용자 환경설정", description = "TTS 등 사용자별 환경설정")
@RestController
@RequestMapping("/preferences")
@RequiredArgsConstructor
public class PreferenceController {
    private final PreferenceService preferenceService;

    @Operation(summary = "TTS 설정 조회", description = "현재 로그인 사용자의 TTS 음성 피드백 설정")
    @GetMapping("/tts")
    public ResponseEntity<TtsPreferenceDto> getTtsPreferences(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(preferenceService.getTtsPreferences(userDetails.getUsername()));
    }

    @Operation(summary = "TTS 설정 변경", description = "TTS 활성화 여부, 음성 속도(0.5 ~ 2.0)")
    @PatchMapping("/tts")
    public ResponseEntity<TtsPreferenceDto> updateTtsPreferences(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody TtsPreferenceUpdateDto dto) {
        return ResponseEntity.ok(preferenceService.updateTtsPreferences(userDetails.getUsername(), dto));
    }
}