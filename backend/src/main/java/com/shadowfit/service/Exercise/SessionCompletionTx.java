package com.shadowfit.service.Exercise;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.dto.report.detailreport.RepSyncRateDto;
import com.shadowfit.dto.report.detailreport.SessionDetailedAnalysis;
import com.shadowfit.dto.report.detailreport.WorstSectionDto;
import com.shadowfit.global.observability.SessionMetrics;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.exercise.SyncStats;
import com.shadowfit.model.report.Report;
import com.shadowfit.model.report.ReportType;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.report.ReportRepository;
import com.shadowfit.service.Report.SessionAnalysisCalculator;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.grpc.SessionCompleteRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code SessionService.completeSession} 이 위임하는 실제 완료 처리 — 별도 빈이라
 * {@code @Transactional}이 Spring 프록시를 정상적으로 타고, self 주입이 필요 없다
 * (이슈 #175: 자기호출은 AOP 프록시를 우회해 {@code @Transactional}이 조용히 무시된다).
 *
 * <p>낙관적 락 재시도 루프(트랜잭션 없음)는 {@code SessionService.completeSession} 에 남아있고,
 * 실제 반영(트랜잭션 하나)만 여기로 옮겼다 — "재시도는 트랜잭션 밖, 반영은 안"이라는 의도가
 * 이제 클래스 경계로 드러난다.
 */
@Component
@RequiredArgsConstructor
public class SessionCompletionTx {

    private final SessionRepository sessionRepository;
    private final PoseDataRepository poseDataRepository;
    private final com.shadowfit.service.Report.DailyLogService dailyLogService;
    private final SessionAnalysisCalculator sessionAnalysisCalculator;
    private final ReportRepository reportRepository;
    private final ObjectMapper objectMapper;
    private final SessionMetrics sessionMetrics;

    @Transactional
    public void applyComplete(SessionCompleteRequest request) {
        Session session = sessionRepository.findById(request.getSessionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        // 싱크 통계는 AI 가 보낸 값을 쓰지 않고 pose_data 에서 직접 집계한다 (이슈 #75).
        SyncStats sync = resolveSyncStats(session, request);

        // 멱등성: FastAPI가 응답 유실로 같은 결과를 재전송한 경우(2-1, 2-2) 첫 완료 시각/기록을 보존하고
        // 즉시 종료한다. 판정은 Session.complete 안에만 있다 — 여기서 미리 한 번 더 보면 위 집계
        // 쿼리를 아낄 수 있지만, 그러면 가드가 두 곳이 되고 엔티티 쪽 분기는 도달 불가능해진다.
        // 재전송은 드물고 그 쿼리는 인덱스 집계 하나라, 가드가 한 곳인 쪽을 택했다.
        boolean transitioned = session.complete(
                request.getTotalReps(),
                sync,
                java.math.BigDecimal.valueOf(request.getCaloriesBurned()),
                LocalDateTime.now());
        if (!transitioned) {
            return;
        }

        sessionRepository.saveAndFlush(session);

        int exerciseMinutes = (int) java.time.Duration.between(session.getStartTime(), session.getEndTime()).toMinutes();
        dailyLogService.accumulateStats(session.getMember().getId(), session.getStartTime().toLocalDate(),
                exerciseMinutes, java.math.BigDecimal.valueOf(request.getCaloriesBurned()));

        precomputeReport(session);

        sessionMetrics.sessionTransition(Status.COMPLETED, "ai-callback");
    }

    /**
     * [싱크 통계 집계] 세션의 avg/max/min sync 를 {@code pose_data} 에서 직접 계산한다 (이슈 #75).
     *
     * <p>집계는 여기(조회가 필요하다), 대입은 {@link Session#complete} 안(상태 전이의 일부다).
     * 그 경계를 {@link SyncStats} 가 넘는다 — 셋을 묶어서 넘기므로 "avg 만 쓰고 max/min 은
     * 빠뜨리는" 아래 ②번 결함이 타입 수준에서 다시 일어날 수 없다.
     *
     * <p><b>왜 AI 가 보낸 값을 안 쓰는가</b> — 세 가지가 한꺼번에 틀려 있었다.
     * <ol>
     *   <li><b>재부착 세션은 후반 구간만 반영됐다.</b> AI 의 통계는 메모리의 {@code completed_reps}
     *       기준인데, 재부착하면 그게 비어서 시작한다. 재부착 이전 rep 의 sync_rate 는 AI 에 없고
     *       {@code pose_data} 에만 있다 — 그래서 <b>가진 쪽이 계산해야 한다.</b></li>
     *   <li><b>max/min 은 아예 저장되지 않았다.</b> AI 가 proto 로 보내는데 여기서 {@code set} 을
     *       안 해 컬럼이 항상 NULL 이었다.</li>
     *   <li><b>rep 이 있는데 통계가 비면 0.0 이 실제 값으로 저장됐다.</b> 아래 참고.</li>
     * </ol>
     *
     * <p><b>측정된 rep 이 없으면 0 이 아니라 {@code null} 이다.</b> 0 으로 두면 "측정 안 됨"이
     * "싱크로율 0%"로 둔갑해 월 평균을 끌어내린다 — 커밋 {@code 0914082} 가 고쳤던 바로 그 증상이고,
     * 그 방어({@code filter(Objects::nonNull)}, {@code SessionService.getCalendarMain} 의 월 평균)는
     * <b>null 만</b> 걸러내므로 저장된 0.0 은 못 막는다. 그래서 애초에 0 을 쓰지 않는다.
     *
     * <p>읽는 쪽 5곳 중 4곳은 이미 null 을 처리하고 있었고, {@code SessionReportResponseDto.of}
     * 하나만 {@code .intValue()} 로 바로 까서 NPE 가 났다 — 거기서 함께 막았다.
     *
     * <p>rep 가중 평균을 유지하는 이유는 {@code findRepAverageSyncRates} 주석 참고 — 다운샘플 때문에
     * 프레임 단위로 평균 내면 값이 달라진다.
     */
    private SyncStats resolveSyncStats(Session session, SessionCompleteRequest request) {
        List<Double> repAverages = poseDataRepository.findRepAverageSyncRates(session.getId(), session.getStartTime());

        if (repAverages.isEmpty()) {
            // rep 단위로 셀 수 있는 프레임이 없다. 두 경우가 섞여 있고 처리가 다르다.
            if (request.getTotalReps() > 0 && request.getAvgSyncRate() > 0) {
                // (1) AI 는 rep 을 셌는데 rep_number 가 안 남았다 = rep_number 를 안 보내는 구버전 AI.
                // proto3 기본값 0 으로 들어와 위 쿼리(repNumber > 0)에 안 걸린다. 배포 순서를 안 맞춰도
                // 깨지지 않게 하려던 설계인데(PoseDataService 주석), 여기서 null 로 덮으면 그 구간에
                // 통계가 통째로 사라진다. 우리가 더 잘 계산할 수 없으므로 AI 가 보낸 값을 쓴다.
                return SyncStats.of(request.getAvgSyncRate(), request.getMaxSyncRate(), request.getMinSyncRate());
            }
            // (2) 측정된 rep 이 정말 없다(0회 세션 등). 이때 AI 는 0.0 을 보내는데 그걸 저장하면 안 된다.
            return SyncStats.none();
        }

        return SyncStats.from(repAverages.stream()
                .mapToDouble(Double::doubleValue)
                .summaryStatistics());
    }

    /**
     * precompute-on-write (report-read-path.md §9) — 세션 완료 시점에 worst 회차와 회차별 추이를
     * 1회 계산해 reports에 저장. GET /reports/sessions/{id} 조회 때마다 pose_data를 재계산하던 것을
     * 제거하는 게 목적(db-deep-dive.md §B-3). applyComplete와 같은 트랜잭션(§9-2)이라 여기서 예외가
     * 나면 세션 완료 자체가 롤백된다(§9-3) — completeSession의 낙관적 락 재시도, AI 콜백 재전송이
     * 그대로 재시도 경로가 됨. pose_data가 아직 없는 경우는 계산기가 null·빈 리스트를 돌려주는
     * 정상 케이스라 예외가 아니다(§9-3에서 실패로 분류하지 않기로 함).
     *
     * <p><b>추이를 여기서 같이 계산하는 이유</b>: worst 와 재료(rep 그룹핑)가 같아 이미 읽어 온
     * 프레임으로 바로 나온다. 조회 시점에 계산하면 precompute 가 없애려던 pose_data 스캔이
     * 추이 때문에 되살아난다.
     */
    private void precomputeReport(Session session) {
        List<PoseFrameProjection> poseFrames = poseDataRepository.findFramesBySessionId(session.getId(), session.getStartTime());
        WorstSectionDto worstSection = sessionAnalysisCalculator.calculate(session, poseFrames);
        List<RepSyncRateDto> repTrend = sessionAnalysisCalculator.calculateRepTrend(poseFrames);

        Report report = new Report();
        report.setMember(session.getMember());
        report.setSession(session);
        report.setReportType(ReportType.SESSION);
        // 둘 다 비면 컬럼을 비워 둔다 — 읽기 경로가 "저장된 게 없다"로 보고 재계산을 시도하는데,
        // 프레임이 없어서 비었던 것이라면 재계산도 같은 결과라 손해가 없다.
        if (worstSection != null || !repTrend.isEmpty()) {
            try {
                report.setDetailedAnalysis(objectMapper.writeValueAsString(
                        new SessionDetailedAnalysis(worstSection, repTrend)));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("리포트 분석 직렬화 실패 - 세션 " + session.getId(), e);
            }
        }
        reportRepository.save(report);
    }
}
