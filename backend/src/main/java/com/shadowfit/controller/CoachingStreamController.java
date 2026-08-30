package com.shadowfit.controller;

import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.service.coaching.TrainerAuthorizationService;
import com.shadowfit.service.coaching.TrainerConnectionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 트레이너 실시간 모니터링 SSE 스트림 ({@code trainer-live-monitoring.md} §8 세션3).
 *
 * <p>role=TRAINER 여부는 {@code @PreAuthorize}로 선언적으로 막고, "이 트레이너가 하필 이
 * {@code userId}를 담당하는가"는 role만으론 못 잡아 {@link TrainerAuthorizationService}가
 * 별도로 검증한다(세션2).
 *
 * <p>연결 직후 {@code connected} 이벤트 하나를 보낸다. 이후 rep 결과 중계는 세션4
 * ({@code PoseDataService.registerTrainerRelay}), 좀비 연결 정리(하트비트)와 백프레셔(실패 시
 * 드롭)는 {@link TrainerConnectionRegistry} 쪽에서 처리한다(§8 세션5). 타임아웃은 0(무제한)을
 * 그대로 유지 — 유한값으로 바꾸려면 프론트에 재연결 로직이 새로 필요해 이번 세션 범위 밖이다
 * (2026-08-30 사용자 확인).
 */
@Slf4j
@RestController
@RequestMapping("/coaching")
@RequiredArgsConstructor
public class CoachingStreamController {

    private final TrainerAuthorizationService trainerAuthorizationService;
    private final TrainerConnectionRegistry connectionRegistry;

    @PreAuthorize("hasRole('TRAINER')")
    @GetMapping("/trainer/{userId}/stream")
    public SseEmitter stream(@PathVariable Long userId, @AuthenticationPrincipal CustomUserDetails userDetails) {
        Long trainerId = userDetails.getMember().getId();
        trainerAuthorizationService.assertAssignedTrainer(trainerId, userId);

        SseEmitter emitter = new SseEmitter(0L);
        connectionRegistry.register(userId, emitter);

        emitter.onCompletion(() -> connectionRegistry.remove(userId, emitter));
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> {
            log.warn("트레이너 SSE 연결 오류: trainerId={}, userId={}", trainerId, userId, e);
            connectionRegistry.remove(userId, emitter);
        });

        try {
            emitter.send(SseEmitter.event().name("connected").data("connected"));
        } catch (IOException e) {
            connectionRegistry.remove(userId, emitter);
            emitter.completeWithError(e);
        }

        return emitter;
    }
}
