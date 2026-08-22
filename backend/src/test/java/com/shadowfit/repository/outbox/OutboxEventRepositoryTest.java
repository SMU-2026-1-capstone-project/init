package com.shadowfit.repository.outbox;

import com.shadowfit.model.outbox.OutboxEvent;
import com.shadowfit.model.outbox.OutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 아웃박스 상태 전이의 <b>소유권 조건</b> 검증.
 *
 * <p>[왜 이 테스트가 필요한가] 발행기는 행을 선점한 뒤 트랜잭션 밖에서 gRPC 를 보낸다. 그 사이
 * lease 가 만료되면 다른 발행기가 같은 행을 회수해 처리 중일 수 있는데, 원래 발행기가 뒤늦게
 * 깨어나 결과를 쓰면 <b>새 소유자의 진행을 덮어쓴다</b>. 상태 전이 쿼리에 {@code lockedBy} 조건을
 * 걸어 조건부 갱신(CAS)으로 막는데, 그 조건이 실제로 동작하는지는 쿼리를 돌려봐야만 알 수 있다.
 *
 * <p>[동시성 없이 검증하는 법] 두 발행기를 실제로 띄울 필요는 없다 — "다른 발행기가 회수해 간
 * 상태"는 곧 {@code lockedBy} 가 다른 값이라는 뜻이므로, 그 상태를 만들어놓고 옛 소유자 이름으로
 * 갱신을 시도하면 된다.
 *
 * <p>🔴 <b>정정(2026-08-20)</b>: 여기엔 «{@code FOR UPDATE SKIP LOCKED} 를 H2 가 지원하지 않아
 * 검증하지 못한다» 고 적혀 있었는데, <b>H2 는 그 문법을 받는다</b>(실제로 돌려 확인). 그 전제 때문에
 * 발행기 경로 전체가 테스트 없이 남아 있었다 — 지금은
 * {@code service.Exercise.OutboxPublisherFailureInjectionTest} 가 {@code dispatchPending()} 을 통째로
 * 돌린다. 다만 <b>문법을 받는 것</b>과 <b>MySQL 과 같은 잠금 의미를 갖는 것</b>은 다르므로, 발행기를
 * 둘 이상 띄웠을 때 서로 다른 행을 집는지는 여전히 실 MySQL 의 몫이다(PR #63 에서 수동 확인).
 */
@SpringBootTest
@Transactional
@DisplayName("아웃박스 소유권 조건 테스트")
class OutboxEventRepositoryTest {

    private static final String OWNER = "pub-original";
    private static final String OTHER = "pub-reclaimer";

    @Autowired private OutboxEventRepository outboxRepository;

    private OutboxEvent claimed;

    @BeforeEach
    void setUp() {
        OutboxEvent saved = outboxRepository.saveAndFlush(OutboxEvent.stopAnalysis(42L, "cid-test"));
        // 선점 상태를 만든다 — OWNER 가 송신 중인 행
        outboxRepository.markProcessing(List.of(saved.getId()), OWNER, LocalDateTime.now().plusMinutes(1));
        claimed = outboxRepository.findById(saved.getId()).orElseThrow();
    }

    @Test
    @DisplayName("소유자가 기록하면 반영된다")
    void owner_canRecord() {
        int updated = outboxRepository.markSent(claimed.getId(), OWNER, LocalDateTime.now());

        assertThat(updated).isEqualTo(1);
        assertThat(outboxRepository.findById(claimed.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("lease 를 잃은 뒤 기록하려 하면 0 행 — 새 소유자의 상태를 덮지 않는다")
    void staleOwner_cannotRecord() {
        // lease 만료 → 다른 발행기가 회수해 간 상황
        outboxRepository.markProcessing(List.of(claimed.getId()), OTHER, LocalDateTime.now().plusMinutes(1));

        // 원래 발행기가 뒤늦게 깨어나 자기 결과를 쓰려 한다
        int updated = outboxRepository.markSent(claimed.getId(), OWNER, LocalDateTime.now());

        assertThat(updated).isZero();
        OutboxEvent after = outboxRepository.findById(claimed.getId()).orElseThrow();
        // 새 소유자가 아직 송신 중이므로 PROCESSING 이 유지돼야 한다
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        assertThat(after.getLockedBy()).isEqualTo(OTHER);
    }

    @Test
    @DisplayName("재시도 예약도 소유권을 확인한다 — 남의 행을 PENDING 으로 되돌리면 중복 처리가 번진다")
    void staleOwner_cannotScheduleRetry() {
        outboxRepository.markProcessing(List.of(claimed.getId()), OTHER, LocalDateTime.now().plusMinutes(1));

        int updated = outboxRepository.markForRetry(claimed.getId(), OWNER, LocalDateTime.now().plusSeconds(4));

        assertThat(updated).isZero();
        OutboxEvent after = outboxRepository.findById(claimed.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        assertThat(after.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("이미 종결된 행은 소유자여도 다시 못 쓴다 — 상태 조건이 함께 걸려 있다")
    void terminalRow_cannotBeRewritten() {
        outboxRepository.markSent(claimed.getId(), OWNER, LocalDateTime.now());

        // SENT 로 끝난 행에 재시도를 걸면 이미 전달된 통보가 다시 나간다
        int updated = outboxRepository.markForRetry(claimed.getId(), OWNER, LocalDateTime.now());

        assertThat(updated).isZero();
        assertThat(outboxRepository.findById(claimed.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.SENT);
    }
}
