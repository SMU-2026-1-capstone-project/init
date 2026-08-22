package com.shadowfit.controller;

import com.shadowfit.dto.report.detailreport.SessionReportResponseDto;
import com.shadowfit.dto.report.weekly.WeeklySummaryResponseDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.service.Report.ReportService;
import com.shadowfit.service.Report.WeeklySummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Tag(name = "운동 활동 관리", description = "메인페이지 운동 활동 관리")
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ExerciseReportController {
    private final ReportService reportService;
    private final WeeklySummaryService weeklySummaryService;

    @Operation(summary="일 별 운동 보고서",description = "일 별 운동 보고서 열람 가능")
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<SessionReportResponseDto> getSessionReport(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PathVariable Long sessionId) {

        SessionReportResponseDto response = reportService.getSessionReport(sessionId, customUserDetails.getMember().getId());
        return ResponseEntity.ok(response);
    }

    /**
     * 주간 요약 — 한 주의 집계와 한국어 문장.
     *
     * <p>세션 리포트와 달리 <b>기록이 없어도 404 가 아니다.</b> 특정 세션을 지목한 요청은 그 세션이
     * 없으면 잘못된 요청이지만, 「이번 주에 운동을 안 했다」는 정상적인 상태다. 빈 집계와 그 사실을
     * 말하는 문장을 200 으로 돌려준다.
     *
     * <p>설계: {@code docs/decisions/report-generation-llm.md} §13.
     */
    @Operation(summary = "주간 운동 요약",
            description = "기준일이 속한 주(월요일 시작)의 집계와 요약 문장. 기록이 없으면 빈 집계를 돌려준다")
    @GetMapping("/weekly")
    public ResponseEntity<WeeklySummaryResponseDto> getWeeklySummary(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @Parameter(description = "기준일(yyyy-MM-dd). 그 날이 속한 주를 잡는다. 없으면 오늘")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        WeeklySummaryResponseDto response =
                weeklySummaryService.getWeeklySummary(customUserDetails.getMember().getId(), date);
        return ResponseEntity.ok(response);
    }
}
