package com.shadowfit.controller;

import com.shadowfit.dto.report.detailreport.SessionReportResponseDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.service.report.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@Tag(name = "운동 활동 관리", description = "메인페이지 운동 활동 관리")
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ExerciseReportController {
    private final ReportService reportService;

    @Operation(summary="일 별 운동 보고서",description = "일 별 운동 보고서 열람 가능")
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<SessionReportResponseDto> getSessionReport(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PathVariable Long sessionId) {

        SessionReportResponseDto response = reportService.getSessionReport(sessionId, customUserDetails.getMember().getId());
        return ResponseEntity.ok(response);
    }

    // 🔵 2026-08-23: 여기 있던 GET /reports/weekly 를 **없앴다** (#352).
    //
    // 부르는 곳이 저장소에 없었다(프론트·테스트·postman 0건). 원인은 같은 base 에 «주간» 이
    // 둘이었다는 것이다 — /reports/weekly-summary 가 이미 있어서 프론트에서 새것이 안 보였다.
    // 이름이 한 마디 차이인데 응답 DTO 도 의미도 달랐다.
    //
    // A층 요약은 ExerciseRecordController 의 /reports/weekly-summary 응답에 `summary` 필드로
    // 합쳤다. 프론트가 이미 부르던 경로라 배선이 안 깨진다.
}
