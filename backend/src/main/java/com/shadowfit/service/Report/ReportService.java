package com.shadowfit.service.Report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.report.detailreport.ComparisonWithPreviousDto;
import com.shadowfit.dto.report.detailreport.ExerciseSyncRateDto;
import com.shadowfit.dto.report.detailreport.SessionReportResponseDto;
import com.shadowfit.dto.report.detailreport.WorstSectionDto;
import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.util.SetSummaryFormatter;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.report.Report;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.report.ReportRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportService {
    private final ReportRepository reportRepository;
    private final SessionRepository sessionRepository;
    private final PoseDataRepository poseDataRepository;
    private final WorstSectionCalculator worstSectionCalculator;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public SessionReportResponseDto getSessionReport(Long sessionId, Long currentMemberId) {
        log.info("세션 리포트 생성 시작 - 세션 ID: {}", sessionId);

        // 1. 기초 세션 정보 및 운동 조회 — 소유권을 WHERE절에 박아 조회 후 검증(fetch-then-check) 회피.
        // 존재하지 않는 세션·남의 세션 둘 다 동일하게 SESSION_NOT_FOUND(존재 여부 비공개).
        Session currentSession = sessionRepository.findSessionWithExerciseByIdAndMemberId(sessionId, currentMemberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        // 2. AI 분석 리포트 엔티티 조회
        Report report = reportRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));

        // 3. 이전 동일 운동 세션 조회 — 현재 세션 자체가 뽑히지 않도록 제외(자기비교 버그 방지)
        Optional<Session> lastSession = sessionRepository.findFirstByMemberIdAndExerciseIdAndStatusAndIdNotOrderByStartTimeDesc(
                currentSession.getMember().getId(),
                currentSession.getExercise().getId(),
                Status.COMPLETED,
                currentSession.getId()
        );

        return buildReportResponse(currentSession, report, lastSession);
    }


    private SessionReportResponseDto buildReportResponse(Session session, Report report,
                                                         Optional<Session> lastSession) {
        SessionReportResponseDto responseDto = SessionReportResponseDto.of(session, report);

        responseDto.setWorstSection(resolveWorstSection(session, report));
        responseDto.setSyncRateDetails(buildSyncRateDetails(session));
        lastSession.ifPresent(last ->
                responseDto.setComparisonWithPrevious(buildComparisonWithPrevious(session, last))
        );

        return responseDto;
    }

    // precompute-on-write(SessionService.applyComplete)가 세션 완료 시점에 이미 계산해 저장한
    // detailed_analysis가 있으면 그걸 읽기만 하고 pose_data는 스캔하지 않음(db-deep-dive.md §B-3).
    // precompute 이전에 생성된 리포트(시드 데이터 등)는 detailed_analysis가 비어 있으므로, 그 경우에만
    // 예전처럼 pose_data에서 즉석 계산 — 별도 백필 없이 하위호환(report-read-path.md §9-4).
    private WorstSectionDto resolveWorstSection(Session session, Report report) {
        String detailedAnalysis = report.getDetailedAnalysis();
        if (detailedAnalysis != null && !detailedAnalysis.isBlank()) {
            try {
                return objectMapper.readValue(detailedAnalysis, WorstSectionDto.class);
            } catch (Exception e) {
                log.warn("세션 {} detailed_analysis 파싱 실패 — pose_data 즉석 재계산으로 대체", session.getId(), e);
            }
        }
        List<PoseFrameProjection> poseFrames = poseDataRepository.findFramesBySessionId(session.getId());
        return worstSectionCalculator.calculate(session, poseFrames);
    }

    private List<ExerciseSyncRateDto> buildSyncRateDetails(Session session) {
        double avgSyncRate = session.getAvgSyncRate() == null ? 0.0 : session.getAvgSyncRate().doubleValue();
        ExerciseSyncRateDto detail = new ExerciseSyncRateDto(
                session.getExercise().getId(),
                session.getExercise().getName(),
                SetSummaryFormatter.format(session.getTotalReps()),
                avgSyncRate
        );
        return List.of(detail);
    }

    private ComparisonWithPreviousDto buildComparisonWithPrevious(Session current, Session last) {
        ComparisonWithPreviousDto dto = new ComparisonWithPreviousDto();
        dto.setSyncRateDiff(diffInt(current.getAvgSyncRate(), last.getAvgSyncRate()));
        dto.setWorkoutMinutesDiff(durationMinutes(current) - durationMinutes(last));
        dto.setCaloriesDiff(diffInt(current.getCaloriesBurned(), last.getCaloriesBurned()));
        return dto;
    }

    private int diffInt(java.math.BigDecimal current, java.math.BigDecimal previous) {
        double currentValue = current == null ? 0.0 : current.doubleValue();
        double previousValue = previous == null ? 0.0 : previous.doubleValue();
        return (int) Math.round(currentValue - previousValue);
    }

    private int durationMinutes(Session session) {
        if (session.getStartTime() == null || session.getEndTime() == null) return 0;
        return (int) java.time.Duration.between(session.getStartTime(), session.getEndTime()).toMinutes();
    }
}
