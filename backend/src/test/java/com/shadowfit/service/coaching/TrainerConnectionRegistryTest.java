package com.shadowfit.service.coaching;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * broadcast 의 "실패는 절대 밖으로 안 던진다" 계약과 죽은 연결 정리를 검증한다
 * (trainer-live-monitoring.md §8 세션4). PoseDataService.savePoseDataBatch 는 저장이 끝난
 * 뒤 커밋 후 훅에서 이 메서드를 부르므로, 트레이너 화면 전송이 실패해도 저장 경로에 영향을
 * 주면 안 된다는 게 이 클래스의 핵심 관심사다.
 */
@DisplayName("TrainerConnectionRegistry 테스트")
class TrainerConnectionRegistryTest {

    private static final Long USER_ID = 10L;
    private static final Long OTHER_USER_ID = 20L;

    @Mock private SseEmitter emitterA;
    @Mock private SseEmitter emitterB;

    private TrainerConnectionRegistry registry;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        registry = new TrainerConnectionRegistry();
    }

    @Test
    @DisplayName("담당 연결이 없는 userId 로 broadcast 하면 조용히 아무 일도 안 한다")
    void broadcast_noConnections_isNoop() {
        assertThatCode(() -> registry.broadcast(USER_ID, "rep", "payload"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("등록된 모든 연결에 이벤트를 보낸다")
    void broadcast_sendsToAllRegisteredConnections() throws IOException {
        registry.register(USER_ID, emitterA);
        registry.register(USER_ID, emitterB);

        registry.broadcast(USER_ID, "rep", "payload");

        verify(emitterA, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitterB, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("다른 userId 를 보고 있는 연결에는 안 보낸다")
    void broadcast_onlyTargetsGivenUserId() throws IOException {
        registry.register(USER_ID, emitterA);
        registry.register(OTHER_USER_ID, emitterB);

        registry.broadcast(USER_ID, "rep", "payload");

        verify(emitterA, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitterB, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("전송 실패한 연결은 예외를 던지지 않고 레지스트리에서 제거되며, 다른 연결은 계속 받는다")
    void broadcast_removesDeadConnectionOnSendFailure_andDoesNotPropagate() throws IOException {
        registry.register(USER_ID, emitterA);
        registry.register(USER_ID, emitterB);
        doThrow(new IOException("연결 끊김")).when(emitterA).send(any(SseEmitter.SseEventBuilder.class));

        assertThatCode(() -> registry.broadcast(USER_ID, "rep", "payload"))
                .doesNotThrowAnyException();

        verify(emitterA).completeWithError(any(IOException.class));
        verify(emitterB, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(registry.getConnections(USER_ID)).containsExactly(emitterB);
    }

    @Test
    @DisplayName("heartbeat 는 모든 사용자의 모든 연결에 보낸다")
    void heartbeat_sendsToEveryConnectionAcrossUsers() throws IOException {
        registry.register(USER_ID, emitterA);
        registry.register(OTHER_USER_ID, emitterB);

        registry.heartbeat();

        verify(emitterA, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitterB, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("연결이 하나도 없으면 heartbeat 는 조용히 아무 일도 안 한다")
    void heartbeat_noConnections_isNoop() {
        assertThatCode(() -> registry.heartbeat()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("heartbeat 전송이 실패한 연결도 broadcast 와 같은 방식으로 제거된다")
    void heartbeat_removesDeadConnectionOnSendFailure() throws IOException {
        registry.register(USER_ID, emitterA);
        doThrow(new IOException("연결 끊김")).when(emitterA).send(any(SseEmitter.SseEventBuilder.class));

        assertThatCode(() -> registry.heartbeat()).doesNotThrowAnyException();

        verify(emitterA).completeWithError(any(IOException.class));
        assertThat(registry.getConnections(USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("remove 로 특정 연결만 지우면 나머지는 남는다")
    void remove_removesOnlyGivenEmitter() {
        registry.register(USER_ID, emitterA);
        registry.register(USER_ID, emitterB);

        registry.remove(USER_ID, emitterA);

        assertThat(registry.getConnections(USER_ID)).containsExactly(emitterB);
    }

    @Test
    @DisplayName("마지막 연결까지 지워지면 getConnections 는 빈 리스트를 준다")
    void remove_lastConnection_leavesEmptyList() {
        registry.register(USER_ID, emitterA);

        registry.remove(USER_ID, emitterA);

        assertThat(registry.getConnections(USER_ID)).isEmpty();
    }
}
