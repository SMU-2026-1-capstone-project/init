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

    /** AI 분석 중단(StopAnalysis) 응답의 업무 결과. tags: outcome(ok/session-missing) */
    private static final String AI_STOP_RESULT = "shadowfit.ai.stop.result";

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

    /** 수신 프레임 수와 다운샘플 후 저장 행수를 함께 기록 — 실측 R값이 의도대로 나오는지 관측. */
    public void poseBatch(int receivedFrames, int storedRows) {
        frames("received").record(receivedFrames);
        frames("stored").record(storedRows);
    }

    private DistributionSummary frames(String stage) {
        return DistributionSummary.builder(POSE_BATCH_FRAMES)
                .tag("stage", stage)
                .register(registry);
    }
}
