package com.shadowfit.service.Exercise;

import com.shadowfit.model.outbox.DispatchOutcome;
import com.shadowfit.model.outbox.OutboxEvent;
import com.shadowfit.model.outbox.OutboxStatus;
import com.shadowfit.repository.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 아웃박스 발행기 <b>실패 주입</b> 테스트 — 송신이 실패하거나 발행기가 도중에 죽는 상황에서
 * 행이 어떤 상태로 남는지를 고정한다.
 *
 * <p>[왜 이 테스트가 필요한가] 아웃박스의 보장(유실 0 · 선점 만료 회수 · 중복 배달 시 상태
 * 오염 방지)은 지금까지 <b>{@code OutboxPublisher} 의 주석에 설계로만</b> 있었다. 기존
 * {@code OutboxEventRepositoryTest} 는 상태 전이 쿼리의 소유권 조건(CAS)만 검증하므로,
 * <b>그 쿼리를 언제 어떤 순서로 부르는지</b>는 아무도 고정하고 있지 않았다. 리팩터링이 이
 * 보장을 조용히 깨도 초록불이 뜬다({@code 28-remaining-work-plan.md} §2-7 «가»).
 *
 * <p>[어떻게 실패를 주입하나] 실패는 {@code stopAnalysis} 가 무엇을 돌려주느냐(또는 던지느냐)로
 * 표현된다. AI 를 실제로 멈출 필요가 없다 — 발행기 입장에서 «AI 가 죽었다» 는 곧
 * {@link DispatchOutcome#RETRY} 이고, «세션을 잃었다» 는 {@link DispatchOutcome#TERMINAL_FAILED}
 * 이며, «발행기가 송신 중 죽었다» 는 예외가 올라와 상태를 못 적고 빠지는 것이다.
 *
 * <p>[H2 로 되는 이유] 선점 쿼리의 {@code FOR UPDATE SKIP LOCKED} 를 H2 가 <b>받는다</b>(2026-08-20
 * 확인). 그래서 {@code dispatchPending()} 을 통째로 돌릴 수 있다. 다만 H2 가 <i>문법을 받는 것</i>과
 * <i>MySQL 과 같은 행 잠금 의미를 갖는 것</i>은 다른 문제라, <b>발행기를 둘 이상 띄웠을 때 정말
 * 서로 다른 행을 집는가</b>는 여기서 검증하지 않는다 — 그건 실 MySQL 이 필요하고 §2-7 «다» 로
 * 열려 있다. 이 테스트가 고정하는 것은 <b>단일 발행기의 실패 처리 로직</b>이다.
 *
 * <p>[스케줄러를 끄는 이유] {@code scheduling.enabled=false} 로 자동 tick 을 막는다. 안 그러면
 * 배경 tick 이 우리가 심어둔 행을 먼저 집어 가 단언이 흔들린다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 🔴 전용 DB 로 격리한다. 기본 테스트 URL(jdbc:h2:mem:shadowfit_test)은 DB_CLOSE_DELAY=-1 이라
        //    JVM 이 사는 동안 유지되고, 스프링 컨텍스트가 여럿이면 그 전부가 같은 DB 를 본다. 다른
        //    컨텍스트에는 scheduling 이 켜져 있어 **그쪽 OutboxPublisher 의 배경 tick 이 우리 행을
        //    집어 간다** — 실제로 그렇게 샜다(우리 컨텍스트 로그엔 아무 흔적이 없고 행만 PROCESSING
        //    으로 남는다). 아래 scheduling=false 는 우리 컨텍스트만 끄므로 그것만으로는 부족하다.
        "spring.datasource.url=jdbc:h2:mem:outbox_failure_injection;MODE=MySQL;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "scheduling.enabled=false",
        // 이 테스트는 gRPC 를 안 쓴다. 서버를 띄우면 컨텍스트가 무거워지고, 같은 JVM 에서
        // 도는 «타이밍으로 판정하는» 테스트(GrpcServerDeadlineProbeTest 는 클라이언트가
        // 포기한 뒤 저장이 완주하는지를 잰다)의 여유를 갉아먹는다. 실제로 그 판정이
        // 흔들렸다 — 필요 없는 것은 안 띄운다.
        "grpc.server.port=-1",
        "outbox.publisher.max-retry=3",
        "outbox.publisher.lock-timeout-seconds=60",
        "outbox.publisher.batch-size=20"
})
@DisplayName("아웃박스 발행기 실패 주입 테스트")
class OutboxPublisherFailureInjectionTest {

    private static final long SESSION_ID = 4242L;
    /** 이 발행기가 아닌 누군가 — 회수해 간 다른 인스턴스를 흉내낼 때 쓴다. */
    private static final String OTHER_PUBLISHER = "pub-other";

    @Autowired private OutboxPublisher publisher;
    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    /**
     * 발행기와 <b>같은 프로퍼티를 같은 기본값으로</b> 읽는다. 한도를 테스트에 상수로 박으면
     * 설정이 바뀔 때 테스트가 «한도 초과» 가 아닌 상황을 재게 된다 — 실제로 한 번 그렇게 샜다.
     */
    @Value("${outbox.publisher.max-retry:10}") private int maxRetry;

    /** 실패 주입 지점. 발행기가 보는 «바깥 세계» 는 이 한 메서드뿐이다. */
    @MockitoBean private ExerciseAnalysisService analysisService;

    @BeforeEach
    void clean() {
        // @Transactional 을 안 건다 — 발행기의 상태 전이가 REQUIRES_NEW 라 별도 트랜잭션에서
        // 커밋되고, 테스트 트랜잭션으로 감싸면 우리가 심은 행이 그쪽에서 안 보인다.
        outboxRepository.deleteAll();
    }

    // ---------------------------------------------------------------------
    // 행 심기 — 상태를 «만들어» 두고 발행기가 그것을 어떻게 다루는지 본다
    // ---------------------------------------------------------------------

    private OutboxEvent pending() {
        return outboxRepository.saveAndFlush(OutboxEvent.stopAnalysis(SESSION_ID, "cid-test"));
    }

    private OutboxEvent pendingWithRetryCount(int retryCount) {
        OutboxEvent e = OutboxEvent.stopAnalysis(SESSION_ID, "cid-test");
        e.setRetryCount(retryCount);
        return outboxRepository.saveAndFlush(e);
    }

    /** 선점된 채 lease 가 만료된 행 — 이전 발행기가 송신 도중 죽고 남긴 모양. */
    private OutboxEvent staleProcessing() {
        OutboxEvent e = OutboxEvent.stopAnalysis(SESSION_ID, "cid-test");
        e.setStatus(OutboxStatus.PROCESSING);
        e.setLockedBy("pub-dead");
        e.setLockExpiresAt(LocalDateTime.now().minusSeconds(1));
        return outboxRepository.saveAndFlush(e);
    }

    private OutboxEvent reload(OutboxEvent e) {
        return outboxRepository.findById(e.getId()).orElseThrow();
    }

    @Nested
    @DisplayName("이벤트 타입별 라우팅")
    class EventTypeRouting {

        /**
         * dispatchOne 의 event-type switch(#581) 가 REATTACH_ANALYSIS 를
         * {@code analysisService.reattachFromOutbox} 로 보내는지만 본다 — 백오프·회수 등
         * 나머지 발행기 동작은 이벤트 타입과 무관해 {@code WhenSendFails} 등 다른 Nested 가
         * STOP_ANALYSIS 로 이미 고정했다.
         */
        @Test
        @DisplayName("REATTACH_ANALYSIS 행은 reattachFromOutbox 로 라우팅된다")
        void reattachAnalysis_routesToReattachFromOutbox() {
            when(analysisService.reattachFromOutbox(SESSION_ID)).thenReturn(DispatchOutcome.SENT);
            OutboxEvent seeded = outboxRepository.saveAndFlush(
                    OutboxEvent.reattachAnalysis(SESSION_ID, "cid-test"));

            publisher.dispatchPending();

            verify(analysisService).reattachFromOutbox(SESSION_ID);
            assertThat(reload(seeded).getStatus()).isEqualTo(OutboxStatus.SENT);
        }
    }

    @Nested
    @DisplayName("송신이 실패했을 때")
    class WhenSendFails {

        @Test
        @DisplayName("RETRY 는 PENDING 으로 되돌리고 백오프를 걸어 다음 tick 이 즉시 다시 집지 않게 한다")
        void retry_reschedulesWithBackoff() {
            when(analysisService.stopAnalysis(eq(SESSION_ID), anyBoolean())).thenReturn(DispatchOutcome.RETRY);
            OutboxEvent seeded = pending();

            publisher.dispatchPending();

            OutboxEvent after = reload(seeded);
            assertThat(after.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(after.getRetryCount()).isEqualTo(1);
            assertThat(after.getNextRetryAt())
                    .as("백오프가 걸려야 다음 tick 이 즉시 다시 집지 않는다")
                    .isAfter(LocalDateTime.now());
            // 소유권 흔적이 지워져야 회수 대상으로도 안 잡힌다
            assertThat(after.getLockedBy()).isNull();
            assertThat(after.getLockExpiresAt()).isNull();
        }

        @Test
        @DisplayName("백오프가 걸린 행은 바로 다음 tick 에 안 집힌다 — 재시도 폭주 방지")
        void backoff_actuallyDelaysNextPickup() {
            when(analysisService.stopAnalysis(eq(SESSION_ID), anyBoolean())).thenReturn(DispatchOutcome.RETRY);
            pending();

            publisher.dispatchPending();   // 1회차 — 여기서 백오프가 걸린다
            publisher.dispatchPending();   // 2회차 — 백오프 안이라 건너뛰어야 한다

            verify(analysisService, org.mockito.Mockito.times(1)).stopAnalysis(eq(SESSION_ID), anyBoolean());
        }

        @Test
        @DisplayName("백오프는 시도 횟수에 따라 2배씩 는다 (1s → 2s → 4s)")
        void backoff_doubles() {
            when(analysisService.stopAnalysis(eq(SESSION_ID), anyBoolean())).thenReturn(DispatchOutcome.RETRY);

            // retryCount=0 이면 이번이 1번째 시도 → 1s, 1이면 2번째 → 2s, 2면 3번째 → 4s.
            // 절대 시각이 아니라 «심은 시각으로부터의 간격» 으로 본다 — 벽시계에 의존하지 않으려는 것.
            assertThat(backoffSecondsFor(0)).isEqualTo(1);
            assertThat(backoffSecondsFor(1)).isEqualTo(2);
            assertThat(backoffSecondsFor(2)).isEqualTo(4);
        }

        private long backoffSecondsFor(int retryCount) {
            outboxRepository.deleteAll();
            OutboxEvent seeded = pendingWithRetryCount(retryCount);
            LocalDateTime before = LocalDateTime.now();

            publisher.dispatchPending();

            LocalDateTime nextAt = reload(seeded).getNextRetryAt();
            // 초 단위로 반올림 — 실행에 걸린 밀리초가 경계를 흔들지 않게 한다
            return Math.round(Duration.between(before, nextAt).toMillis() / 1000.0);
        }

        @Test
        @DisplayName("재시도 한도를 넘기면 독 메시지로 보고 터미널 FAILED 로 종결한다")
        void retryCapExceeded_goesTerminal() {
            when(analysisService.stopAnalysis(eq(SESSION_ID), anyBoolean())).thenReturn(DispatchOutcome.RETRY);
            // 이번 시도가 «한도 + 1» 번째가 되도록 심는다 (attempts = retryCount + 1)
            OutboxEvent seeded = pendingWithRetryCount(maxRetry);

            publisher.dispatchPending();

            OutboxEvent after = reload(seeded);
            assertThat(after.getStatus())
                    .as("한도를 넘긴 행은 PENDING 으로 되돌아가면 안 된다 — 영원히 폴링을 먹는다")
                    .isEqualTo(OutboxStatus.FAILED);
            assertThat(after.getLockedBy()).isNull();
        }

        @Test
        @DisplayName("TERMINAL_FAILED 는 한도와 무관하게 즉시 종결한다 — 재시도가 원리상 무의미하다")
        void terminalFailure_skipsRetryEntirely() {
            when(analysisService.stopAnalysis(eq(SESSION_ID), anyBoolean()))
                    .thenReturn(DispatchOutcome.TERMINAL_FAILED);
            OutboxEvent seeded = pending();

            publisher.dispatchPending();

            OutboxEvent after = reload(seeded);
            assertThat(after.getStatus()).isEqualTo(OutboxStatus.FAILED);
            assertThat(after.getRetryCount())
                    .as("재시도를 한 번도 쓰지 않아야 한다")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("발행기가 송신 도중 죽었을 때")
    class WhenPublisherDies {

        @Test
        @DisplayName("예외가 나도 행은 유실되지 않고 PROCESSING 으로 남아 회수를 기다린다")
        void exception_leavesRowRecoverable() {
            when(analysisService.stopAnalysis(eq(SESSION_ID), anyBoolean()))
                    .thenThrow(new RuntimeException("송신 중 프로세스 이상"));
            OutboxEvent seeded = pending();

            publisher.dispatchPending();

            OutboxEvent after = reload(seeded);
            assertThat(after.getStatus())
                    .as("PENDING 으로 되돌리면 «송신됐는지 모르는 행» 을 즉시 다시 보내게 된다")
                    .isEqualTo(OutboxStatus.PROCESSING);
            assertThat(after.getLockExpiresAt())
                    .as("만료 시각이 있어야 회수될 수 있다")
                    .isNotNull();
        }

        @Test
        @DisplayName("한 행이 터져도 같은 배치의 나머지는 계속 나간다")
        void oneRowFailure_doesNotStopTheBatch() {
            OutboxEvent first = pending();
            OutboxEvent boom = outboxRepository.saveAndFlush(OutboxEvent.stopAnalysis(9999L, "cid-boom"));
            OutboxEvent last = pending();

            when(analysisService.stopAnalysis(eq(SESSION_ID), anyBoolean())).thenReturn(DispatchOutcome.SENT);
            when(analysisService.stopAnalysis(eq(9999L), anyBoolean()))
                    .thenThrow(new RuntimeException("이 행만 터진다"));

            publisher.dispatchPending();

            assertThat(reload(first).getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(reload(last).getStatus())
                    .as("터진 행보다 뒤에 있어도 처리돼야 한다")
                    .isEqualTo(OutboxStatus.SENT);
            assertThat(reload(boom).getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        }

        @Test
        @DisplayName("tick 하나가 통째로 죽어도 예외가 밖으로 새지 않는다 — 스케줄러가 작업을 영구 중단시킨다")
        void tickSwallowsExceptions() {
            when(analysisService.stopAnalysis(eq(SESSION_ID), anyBoolean()))
                    .thenThrow(new RuntimeException("무엇이든"));
            pending();

            // 예외가 새면 여기서 테스트가 실패한다
            publisher.dispatchPending();
        }
    }

    @Nested
    @DisplayName("선점이 만료된 행을 회수할 때")
    class WhenReclaimingStaleLease {

        @Test
        @DisplayName("만료된 PROCESSING 을 회수해 다시 보낸다")
        void staleLease_isReclaimed() {
            when(analysisService.stopAnalysis(eq(SESSION_ID), anyBoolean())).thenReturn(DispatchOutcome.SENT);
            OutboxEvent seeded = staleProcessing();

            publisher.dispatchPending();

            assertThat(reload(seeded).getStatus()).isEqualTo(OutboxStatus.SENT);
        }

        @Test
        @DisplayName("아직 살아 있는 lease 는 건드리지 않는다 — 남이 송신 중인 행을 가로채면 불필요한 중복이 난다")
        void liveLease_isLeftAlone() {
            OutboxEvent e = OutboxEvent.stopAnalysis(SESSION_ID, "cid-test");
            e.setStatus(OutboxStatus.PROCESSING);
            e.setLockedBy(OTHER_PUBLISHER);
            e.setLockExpiresAt(LocalDateTime.now().plusMinutes(5));
            OutboxEvent seeded = outboxRepository.saveAndFlush(e);

            publisher.dispatchPending();

            verify(analysisService, never()).stopAnalysis(eq(SESSION_ID), anyBoolean());
            OutboxEvent after = reload(seeded);
            assertThat(after.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
            assertThat(after.getLockedBy()).isEqualTo(OTHER_PUBLISHER);
        }

        /**
         * 이슈 #152 회귀 고정.
         *
         * <p>회수분은 «이미 한 번 나갔을 수 있는» 행이라, AI 가 {@code success=false} 로 답해도
         * 그것이 «세션을 잃었다» 가 아니라 «첫 송신이 이미 처리했다» 일 수 있다. 그 구분을
         * 수신부가 하려면 <b>발행기가 회수분임을 알려줘야</b> 한다. 이 플래그가 빠지면 진행
         * 중인 세션이 FAILED 로 걷어내진다 — 고쳐진 지점이라 여기서 못박는다.
         */
        @Test
        @DisplayName("회수분은 possiblyRedelivered=true 로 보낸다 (#152)")
        void reclaimed_isMarkedAsPossiblyRedelivered() {
            when(analysisService.stopAnalysis(eq(SESSION_ID), anyBoolean()))
                    .thenReturn(DispatchOutcome.TERMINAL_FAILED);
            staleProcessing();

            publisher.dispatchPending();

            verify(analysisService).stopAnalysis(SESSION_ID, true);
        }

        @Test
        @DisplayName("신규분은 possiblyRedelivered=false 로 보낸다 — 회수분과 섞이면 진짜 유실을 못 본다")
        void freshRow_isNotMarkedAsRedelivered() {
            when(analysisService.stopAnalysis(eq(SESSION_ID), anyBoolean())).thenReturn(DispatchOutcome.SENT);
            pending();

            publisher.dispatchPending();

            verify(analysisService).stopAnalysis(SESSION_ID, false);
        }
    }

    @Nested
    @DisplayName("송신 중에 소유권을 잃었을 때")
    class WhenLeaseLost {

        /**
         * 발행기는 트랜잭션 <b>밖</b>에서 송신하므로 그 사이 lease 가 만료되면 다른 발행기가
         * 같은 행을 회수해 간다. 그때 원래 발행기가 결과를 쓰면 새 소유자의 진행을 덮어쓴다.
         *
         * <p>송신 도중 다른 발행기가 가로채는 상황을 «송신 부수효과» 로 만든다 — 실제로 두
         * 발행기를 띄우지 않고도 같은 조건이 된다.
         */
        @Test
        @DisplayName("결과를 쓰지 않고 물러난다 — 새 소유자의 상태를 덮어쓰지 않는다")
        void doesNotOverwriteNewOwner() {
            OutboxEvent seeded = pending();

            doAnswer(invocation -> {
                // 송신하는 «동안» 다른 발행기가 회수해 갔다.
                // 트랜잭션으로 감싸는 이유: @Modifying 쿼리는 트랜잭션이 없으면 던진다. 여기서
                // 던지면 dispatchBatch 가 그것을 «행 처리 실패» 로 잡아버려, 정작 재려던
                // 소유권 상실 경로가 아니라 예외 경로를 테스트하게 된다.
                new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                        outboxRepository.markProcessing(
                                List.of(seeded.getId()), OTHER_PUBLISHER, LocalDateTime.now().plusMinutes(5)));
                return DispatchOutcome.SENT;
            }).when(analysisService).stopAnalysis(eq(SESSION_ID), anyBoolean());

            publisher.dispatchPending();

            OutboxEvent after = reload(seeded);
            assertThat(after.getStatus())
                    .as("새 소유자가 아직 송신 중인데 SENT 로 덮으면 그쪽 진행이 망가진다")
                    .isEqualTo(OutboxStatus.PROCESSING);
            assertThat(after.getLockedBy()).isEqualTo(OTHER_PUBLISHER);
        }
    }
}
