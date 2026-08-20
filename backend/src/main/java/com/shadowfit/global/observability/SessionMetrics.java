package com.shadowfit.global.observability;

import com.shadowfit.model.exercise.Status;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 운동 세션 파이프라인의 커스텀 지표. Actuator {@code /actuator/metrics}로 노출된다.
 *
 * <p>로그(개별 사건)와 달리 "지난 한 시간 낙관락 충돌 몇 건?" 같은 <b>집계 질문</b>에 답하기 위한
 * 축. 특히 세션 정합성(스케줄러↔AI 콜백 경쟁)은 지금까지 코드와 재현 실험으로만 증명돼 있고
 * 운영 중 실제 발생 빈도를 볼 수단이 없었다.
 *
 * <p>지표 이름은 Micrometer 관례(소문자·점 구분)를 따른다.
 */
@Component
public class SessionMetrics {

    /** 세션 상태 전이 건수. tags: status(COMPLETED/FAILED), source(전이를 일으킨 흐름) */
    private static final String TRANSITIONS = "shadowfit.session.transitions";

    /** 낙관적 락 충돌 건수. tags: source(충돌을 만난 흐름), outcome(retry/yield) */
    private static final String LOCK_CONFLICTS = "shadowfit.session.optimistic.lock.conflicts";

    /** pose_data 배치 프레임 수 분포. tags: stage(received/stored) — 다운샘플 비율 관측용 */
    private static final String POSE_BATCH_FRAMES = "shadowfit.pose.batch.frames";

    /**
     * pose_data 배치 INSERT 가 만난 데드락과 그 재시도 결과. tags: outcome(retried/recovered/exhausted)
     *
     * <p>이 지표가 필요한 이유는 재시도 횟수를 «2회» 로 고정했기 때문이다 — 실측(#276)은
     * <b>워커 8 한 점</b>에서만 2회면 잔여 실패 0% 를 보였고, 같은 라운드가 <b>데드락 확률이
     * 동시성의 함수</b>임을(워커 2 에서 1.2%, 16 에서 59.5%) 같이 보였다. 즉 운영 동시성이
     * 실측 지점을 넘어서면 «2회» 가 모자랄 수 있고, 그때 그것을 알 수단이 이 지표뿐이다.
     */
    private static final String POSE_DEADLOCK_RETRIES = "shadowfit.pose.batch.deadlock.retries";

    /**
     * AI 분석 중단(StopAnalysis) 응답의 업무 결과.
     * tags: outcome(ok/session-missing/session-missing-redelivery/grpc-error/error/skipped-circuit-open)
     *
     * <p>{@code session-missing} 과 {@code session-missing-redelivery} 는 AI 응답이 같지만(둘 다
     * {@code success=false}) 뜻이 다르다 — 앞은 «결과 유실», 뒤는 «회수분 재송신이라 첫 송신이
     * 이미 처리됐을 수 있음»이다 (이슈 #152). 나누지 않으면 정상 중복 배달이 유실 지표를 부풀린다.
     */
    private static final String AI_STOP_RESULT = "shadowfit.ai.stop.result";

    /** 아웃박스 발행 결과. tags: outcome(sent/retry/failed) */
    private static final String OUTBOX_DISPATCH = "shadowfit.outbox.dispatch";

    /** 아웃박스 적체(PENDING 행 수) 게이지. */
    private static final String OUTBOX_PENDING = "shadowfit.outbox.pending";

    /** 통보 지연 — 행 생성(created_at)부터 전달 성공(sent_at)까지. */
    private static final String OUTBOX_LAG = "shadowfit.outbox.lag";

    /** 세션이 사라졌는데 남아 있는 pose_data 행 수 게이지 (이슈 #87). */
    private static final String POSE_ORPHANS = "shadowfit.pose.orphan.rows";

    /** 세션 검증 통과부터 커밋까지 — 고아가 생길 수 있는 창의 폭 (이슈 #87). */
    private static final String POSE_ORPHAN_WINDOW = "shadowfit.pose.orphan.window";

    private final MeterRegistry registry;

    public SessionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param source 어떤 흐름이 전이시켰는지 — ai-callback / timeout-scheduler / circuit-open 등.
     *               같은 FAILED라도 "AI가 죽어서"와 "타임아웃이 걷어내서"는 운영상 완전히 다른 사건이다.
     */
    public void sessionTransition(Status status, String source) {
        registry.counter(TRANSITIONS, "status", status.name(), "source", source).increment();
    }

    /**
     * @param outcome retry(재시도해서 덮어씀) / yield(양보하고 물러남).
     *                낙관락 설계상 어느 쪽이 이겼는지가 정책 그 자체라 태그로 분리한다.
     */
    public void optimisticLockConflict(String source, String outcome) {
        registry.counter(LOCK_CONFLICTS, "source", source, "outcome", outcome).increment();
    }

    /**
     * StopAnalysis 응답의 <b>업무 층</b> 결과. gRPC 전송 성공(onNext)과는 별개 축이다.
     *
     * <p>AI는 세션 상태를 못 찾아도 gRPC 에러가 아니라 {@code success=false}인 정상 응답을 준다
     * (AI 프로세스 재시작 시 in-memory 상태가 사라지므로). 그 경우 CompleteAnalysis가 영영 오지 않아
     * 결과가 유실되는데, 전송 층만 보면 "성공"으로 보여 사건 자체가 관측되지 않는다.
     *
     * @param outcome ok / session-missing
     */
    public void aiStopResult(String outcome) {
        registry.counter(AI_STOP_RESULT, "outcome", outcome).increment();
    }

    /**
     * 아웃박스 발행 1건의 결과.
     *
     * @param outcome sent / retry / failed. {@code failed} 는 <b>종료 상태의 유실</b>이므로
     *                {@code retry} 와 반드시 구분해야 한다 — 전자는 사람이 봐야 할 사건이고
     *                후자는 정상 운영 중에도 나온다.
     */
    public void outboxDispatch(String outcome) {
        registry.counter(OUTBOX_DISPATCH, "outcome", outcome).increment();
    }

    /**
     * 적체 감시 게이지. 발행기가 죽거나 독 메시지가 쌓이면 이 값만 계속 오른다 — 아웃박스의
     * 대표적 실패 양상이라, 이게 없으면 "조용히 안 나가는 것"을 아무도 모른다.
     *
     * <p>{@code supplier} 는 게이지가 스크레이프될 때마다 호출되므로 매 tick 등록하지 않는다.
     */
    public void registerOutboxPendingGauge(java.util.function.Supplier<Number> supplier) {
        io.micrometer.core.instrument.Gauge.builder(OUTBOX_PENDING, supplier)
                .description("전달 대기 중인 아웃박스 행 수")
                .register(registry);
    }

    /** 통보 지연(생성→전달). 폴링 간격이 실제 지연에 얼마나 반영되는지 본다. */
    public void outboxLag(java.time.Duration lag) {
        registry.timer(OUTBOX_LAG).record(lag);
    }

    /**
     * 고아 {@code pose_data} 행 수 게이지 (이슈 #87). 세션 검증과 INSERT 사이에 회원 탈퇴가
     * 끼어들면 정리 경로를 빠져나간 행이 남는데, <b>지금은 그 일이 실제로 나는지 볼 수단이 없다.</b>
     * 결함의 발생 빈도를 모르면 "핫패스에 상시 락을 얹을 만한가"(수정안 ㄱ)를 판단할 근거가 없어,
     * 고치기 전에 세는 것부터 한다.
     *
     * <p><b>{@link #registerOutboxPendingGauge} 와 달리 supplier 가 DB 를 직접 치지 않는다.</b>
     * 적체 게이지는 인덱스 조회라 스크레이프마다 물어봐도 되지만, 이쪽은 대용량 테이블
     * anti-join 이라 스크레이프 주기로 돌리면 그 자체가 부하다. 그래서 supplier 는 스케줄러가
     * 미리 채워둔 값을 읽기만 한다 — 게이지 값에 <b>최대 갱신 주기만큼의 지연</b>이 있다는 뜻이고,
     * 드물게 발생하는 사건을 세는 용도라 그 지연은 무해하다.
     */
    public void registerPoseOrphanGauge(java.util.function.Supplier<Number> supplier) {
        io.micrometer.core.instrument.Gauge.builder(POSE_ORPHANS, supplier)
                .description("세션이 없는데 남아 있는 pose_data 행 수 (최근 갱신값)")
                .register(registry);
    }

    /**
     * 고아가 생길 수 있는 <b>창의 폭</b> — 세션 검증 통과부터 커밋까지 (이슈 #87).
     *
     * <p>게이지({@link #registerPoseOrphanGauge})가 "실제로 몇 건 났나"를 센다면 이쪽은
     * <b>"날 수 있는 구간이 얼마나 넓나"</b>를 잰다. 둘을 곱해야 빈도 상한이 나오고, 그 상한이
     * 있어야 수정안(락은 비용이 확실한데 이득이 불확실)을 저울질할 수 있다.
     *
     * <p><b>끝점이 INSERT 가 아니라 커밋인 이유.</b> 탈퇴 쪽 정리가 우리 커밋 <i>뒤에</i> 돌면
     * 우리 행까지 같이 지워져 고아가 안 남는다. 위험한 구간은 검증 통과부터 커밋 직전까지다.
     *
     * <p>백분위를 함께 발행한다 — 평균은 이 용도에 쓸모없다. 창이 대부분 짧고 가끔 길다면
     * 확률을 지배하는 건 그 꼬리이지 평균이 아니기 때문이다.
     */
    public void poseOrphanWindow(java.time.Duration window) {
        io.micrometer.core.instrument.Timer.builder(POSE_ORPHAN_WINDOW)
                .description("세션 검증 통과 → 커밋. 이 구간에 탈퇴가 겹치면 고아 행이 남는다")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(registry)
                .record(window);
    }

    /** 수신 프레임 수와 다운샘플 후 저장 행수를 함께 기록 — 실측 R값이 의도대로 나오는지 관측. */
    public void poseBatch(int receivedFrames, int storedRows) {
        frames("received").record(receivedFrames);
        frames("stored").record(storedRows);
    }

    /**
     * @param outcome retried(데드락을 만나 다시 던짐) / recovered(재시도 끝에 성공) /
     *                exhausted(재시도를 다 쓰고도 실패 — AI 쪽 재전송으로 넘어간다).
     *                셋을 나누는 이유는 «데드락이 났다» 와 «데이터를 잃었다» 가 다른 사건이기
     *                때문이다. retried 가 늘어도 recovered 로 닫히면 유실은 0 이다.
     */
    public void poseBatchDeadlockRetry(String outcome) {
        registry.counter(POSE_DEADLOCK_RETRIES, "outcome", outcome).increment();
    }

    private DistributionSummary frames(String stage) {
        return DistributionSummary.builder(POSE_BATCH_FRAMES)
                .tag("stage", stage)
                .register(registry);
    }
}
