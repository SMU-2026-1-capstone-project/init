package com.shadowfit.service.Report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.dto.report.detailreport.RepSyncRateDto;
import com.shadowfit.dto.report.detailreport.SessionDetailedAnalysis;
import com.shadowfit.dto.report.detailreport.SessionReportResponseDto;
import com.shadowfit.dto.report.detailreport.WorstSectionDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.report.Report;
import com.shadowfit.model.report.ReportType;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.report.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReportService 단위 테스트 — precompute-on-write 도입(2026-07-24) 후 읽기 경로의 핵심 분기:
 * detailed_analysis 존재 시 재계산 없이 읽기만 하는지(§9 목적), 없을 때만 pose_data로 하위호환
 * fallback하는지(§9-4, 백필 없이 안전).
 */
@DisplayName("ReportService 테스트")
class ReportServiceTest {

    @Mock private ReportRepository reportRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private PoseDataRepository poseDataRepository;
    @Mock private WorstSectionCalculator worstSectionCalculator;

    private ReportService reportService;

    private Session session;
    private Report report;
    private static final Long MEMBER_ID = 1L;
    private static final Long SESSION_ID = 10L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // 실제 JSON 직렬화/역직렬화 동작이 검증 대상이라 ObjectMapper는 모킹하지 않고 실사용
        reportService = new ReportService(reportRepository, sessionRepository, poseDataRepository,
                worstSectionCalculator, new ObjectMapper());

        Member member = Member.builder().id(MEMBER_ID).email("t@t.com").username("u").password("p").build();
        Exercise exercise = Exercise.builder().id(1L).name("스쿼트").category(ExerciseCategory.LOWER)
                .expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .build();
        session = Session.builder()
                .id(SESSION_ID).member(member).exercise(exercise)
                .startTime(LocalDateTime.now().minusMinutes(20))
                .endTime(LocalDateTime.now())
                .status(Status.COMPLETED)
                .totalReps(10)
                .avgSyncRate(new BigDecimal("77.5"))
                .caloriesBurned(new BigDecimal("50.0"))
                .build();

        report = new Report();
        report.setMember(member);
        report.setSession(session);
        report.setReportType(ReportType.SESSION);

        when(sessionRepository.findSessionWithExerciseByIdAndMemberId(SESSION_ID, MEMBER_ID))
                .thenReturn(Optional.of(session));
        when(sessionRepository.findFirstByMemberIdAndExerciseIdAndStatusAndIdNotOrderByStartTimeDesc(
                anyLong(), anyLong(), any(Status.class), anyLong())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("세션이 없으면(본인 것 아니거나 존재 안 함) SESSION_NOT_FOUND")
    void sessionNotFound() {
        when(sessionRepository.findSessionWithExerciseByIdAndMemberId(SESSION_ID, MEMBER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportService.getSessionReport(SESSION_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_NOT_FOUND);

        verify(reportRepository, never()).findBySessionId(anyLong());
    }

    @Test
    @DisplayName("리포트가 없으면 REPORT_NOT_FOUND")
    void reportNotFound() {
        when(reportRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportService.getSessionReport(SESSION_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPORT_NOT_FOUND);
    }

    @Test
    @DisplayName("precompute된 detailed_analysis가 있으면 그 값을 읽기만 하고 pose_data는 재조회 안 함")
    void precomputedAnalysis_skipsPoseDataRecompute() throws Exception {
        WorstSectionDto precomputed = new WorstSectionDto();
        precomputed.setRepNumber(2);
        precomputed.setExerciseName("스쿼트");
        precomputed.setTimeStamp("01:15");
        precomputed.setReason("2회차 · 싱크로율 75%");
        SessionDetailedAnalysis stored = new SessionDetailedAnalysis(precomputed, List.of(
                new RepSyncRateDto(1, 80.0, "00:12"),
                new RepSyncRateDto(2, 75.0, "01:15")
        ));
        report.setDetailedAnalysis(new ObjectMapper().writeValueAsString(stored));
        when(reportRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(report));

        SessionReportResponseDto result = reportService.getSessionReport(SESSION_ID, MEMBER_ID);

        assertThat(result.getWorstSection()).isNotNull();
        assertThat(result.getWorstSection().getRepNumber()).isEqualTo(2);
        assertThat(result.getRepTrend())
                .extracting(RepSyncRateDto::getRepNumber).containsExactly(1, 2);
        // 추이를 응답에 넣으면서 pose_data 스캔이 되살아나지 않았는지가 이 테스트의 핵심이다
        verify(poseDataRepository, never()).findFramesBySessionId(anyLong());
        verify(worstSectionCalculator, never()).calculate(any(), any());
        verify(worstSectionCalculator, never()).calculateRepTrend(any());
    }

    @Test
    @DisplayName("구버전 형식(WorstSectionDto 단독)으로 저장된 detailed_analysis는 재계산으로 흘린다")
    void legacyAnalysisFormat_fallsBackToRecompute() throws Exception {
        // 예전에는 이 컬럼에 WorstSectionDto 를 그대로 직렬화했다. 그 행을 SessionDetailedAnalysis 로
        // 읽으면 파싱이 실패하거나(엄격 모드) 전부 null 인 객체가 나온다(관대 모드). 둘 다 재계산이어야
        // 한다 — 후자만 놓치면 나중에 FAIL_ON_UNKNOWN_PROPERTIES 를 끄는 순간 worst 가 조용히 사라진다.
        WorstSectionDto legacy = new WorstSectionDto();
        legacy.setExerciseName("스쿼트");
        legacy.setTimeStamp("01:15");
        legacy.setReason("싱크로율 40% · KNEE_OUT");
        report.setDetailedAnalysis(new ObjectMapper().writeValueAsString(legacy));
        when(reportRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(report));

        List<PoseFrameProjection> frames = List.of(new PoseFrameProjection(0.0, 50.0, 1));
        when(poseDataRepository.findFramesBySessionId(SESSION_ID)).thenReturn(frames);
        WorstSectionDto recomputed = new WorstSectionDto();
        recomputed.setReason("재계산됨");
        when(worstSectionCalculator.calculate(session, frames)).thenReturn(recomputed);
        when(worstSectionCalculator.calculateRepTrend(frames))
                .thenReturn(List.of(new RepSyncRateDto(1, 50.0, "00:00")));

        SessionReportResponseDto result = reportService.getSessionReport(SESSION_ID, MEMBER_ID);

        assertThat(result.getWorstSection().getReason()).isEqualTo("재계산됨");
        assertThat(result.getRepTrend()).hasSize(1);
        verify(poseDataRepository, times(1)).findFramesBySessionId(SESSION_ID);
    }

    @Test
    @DisplayName("detailed_analysis가 비어있으면(precompute 이전 리포트) pose_data로 하위호환 재계산")
    void blankAnalysis_fallsBackToPoseDataRecompute() {
        report.setDetailedAnalysis(null); // 시드 데이터처럼 precompute 이전 상태
        when(reportRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(report));
        List<PoseFrameProjection> frames = List.of(new PoseFrameProjection(0.0, 50.0, 1));
        when(poseDataRepository.findFramesBySessionId(SESSION_ID)).thenReturn(frames);
        WorstSectionDto recomputed = new WorstSectionDto();
        recomputed.setReason("재계산됨");
        when(worstSectionCalculator.calculate(session, frames)).thenReturn(recomputed);

        SessionReportResponseDto result = reportService.getSessionReport(SESSION_ID, MEMBER_ID);

        assertThat(result.getWorstSection().getReason()).isEqualTo("재계산됨");
        verify(poseDataRepository, times(1)).findFramesBySessionId(SESSION_ID);
    }

    @Test
    @DisplayName("detailed_analysis가 깨진 JSON이면 예외 대신 pose_data 재계산으로 안전하게 대체")
    void malformedAnalysis_fallsBackGracefully() {
        report.setDetailedAnalysis("{이건 JSON이 아님");
        when(reportRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(report));
        List<PoseFrameProjection> frames = List.of(new PoseFrameProjection(0.0, 50.0, 1));
        when(poseDataRepository.findFramesBySessionId(SESSION_ID)).thenReturn(frames);
        WorstSectionDto recomputed = new WorstSectionDto();
        recomputed.setReason("재계산됨");
        when(worstSectionCalculator.calculate(session, frames)).thenReturn(recomputed);

        SessionReportResponseDto result = reportService.getSessionReport(SESSION_ID, MEMBER_ID);

        // 파싱 실패 시 예외를 삼키고 끝나는 게 아니라, 실제로 pose_data 재계산까지 수행됐는지 검증
        // (CodeRabbit 지적 — 예전엔 calculate()가 null을 반환해도 통과하는 부실한 테스트였음)
        assertThat(result.getWorstSection().getReason()).isEqualTo("재계산됨");
        verify(poseDataRepository, times(1)).findFramesBySessionId(SESSION_ID);
        verify(worstSectionCalculator).calculate(session, frames);
    }

    @Test
    @DisplayName("이전 동일 운동 세션이 있으면 comparisonWithPrevious 채워짐")
    void previousSessionPresent_fillsComparison() {
        report.setDetailedAnalysis(null);
        when(reportRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(report));
        when(poseDataRepository.findFramesBySessionId(SESSION_ID)).thenReturn(List.of());

        Session lastSession = Session.builder()
                .id(9L).member(session.getMember()).exercise(session.getExercise())
                .startTime(LocalDateTime.now().minusDays(1).minusMinutes(30))
                .endTime(LocalDateTime.now().minusDays(1))
                .status(Status.COMPLETED)
                .avgSyncRate(new BigDecimal("70.0"))
                .caloriesBurned(new BigDecimal("40.0"))
                .build();
        when(sessionRepository.findFirstByMemberIdAndExerciseIdAndStatusAndIdNotOrderByStartTimeDesc(
                MEMBER_ID, session.getExercise().getId(), Status.COMPLETED, SESSION_ID))
                .thenReturn(Optional.of(lastSession));

        SessionReportResponseDto result = reportService.getSessionReport(SESSION_ID, MEMBER_ID);

        assertThat(result.getComparisonWithPrevious()).isNotNull();
        assertThat(result.getComparisonWithPrevious().getSyncRateDiff()).isEqualTo(8); // 77.5 - 70.0 반올림
    }
}
