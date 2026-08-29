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
 * <p>세션4(AI 콜백 중계)가 붙기 전까지는 연결이 열리고 유지되는 것만 확인할 수 있다 —
 * 연결 직후 {@code connected} 이벤트 하나만 보낸다. 타임아웃을 0(무제한)으로 둔 것과
 * 백프레셔·정리 정책은 세션5에서 다듬는다(§8 세션5).
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
