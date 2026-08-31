package com.shadowfit.controller;

import com.shadowfit.dto.report.record.CalendarMainResponseDto;
import com.shadowfit.dto.report.record.DailyActivityResponseDto;
import com.shadowfit.dto.report.record.DailyLogRequestDto;
import com.shadowfit.dto.report.record.WeeklyActivityResponseDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.service.exercise.SessionActivityQueryService;
import com.shadowfit.service.report.DailyLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.shadowfit.service.report.WeeklySummaryService;

import java.time.LocalDate;

@Tag(name = "운동 활동 관리", description = "메인페이지 운동 활동 관리")
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
@Slf4j
public class ExerciseRecordController {
    private final SessionActivityQueryService sessionActivityQueryService;
    private final DailyLogService dailyLogService;
    private final WeeklySummaryService weeklySummaryService;

    /**
     * 주간 요약 — 활동 집계(총 운동시간·칼로리·일별 분)와 <b>A층 요약</b>(세션수·회차·싱크로율·
     * 운동일수 + 규칙 문장)을 <b>한 응답</b>으로 돌려준다.
     *
     * <p>🔵 <b>2026-08-23: 둘을 합쳤다</b> (#352). A층 요약은 {@code GET /reports/weekly} 로 따로
     * 나가 있었는데 <b>부르는 곳이 저장소에 없었다</b> — 같은 base 에 «주간» 이 둘이라 프론트에서
     * 보면 이미 하나 있어서 새것이 안 보였다. 이름이 한 마디 차이인데 응답 DTO 도 의미도 달랐다.
     *
     * <p>🔴 <b>기준일 파라미터는 안 받는다.</b> A층 서비스는 {@code date} 를 받을 수 있지만
     * 활동 집계({@code getWeeklyActivity})는 <b>이번 주 고정</b>이다. 한쪽만 기준일을 존중하면
     * 같은 응답 안에서 두 절반이 다른 주를 가리키게 된다 — 이 이슈가 잡은 것과 같은 종류의
     * 조용한 어긋남이다. 과거 주를 보려면 <b>두 절반을 같이</b> 기준일 기반으로 바꿔야 한다.
     */
    @Operation(summary="주간 운동 요약",
            description = "이번 주 활동 집계 + A층 요약(집계·문장). 기록이 없어도 200 이고 빈 집계를 돌려준다")
    @GetMapping("/weekly-summary")
    public ResponseEntity<WeeklyActivityResponseDto> getWeeklySummary(@AuthenticationPrincipal CustomUserDetails customUserDetails) {
        // 서비스 로직에서 주간 통계 및 오늘 운동 리스트를 계산해서 반환
        Long memberId = customUserDetails.getMember().getId();
        WeeklyActivityResponseDto response = sessionActivityQueryService.getWeeklyActivity(memberId);
        // A층 요약은 기준일 없이(=오늘이 속한 주) 부른다 — 위 집계와 같은 주를 보게 하려는 것이다.
        response.setSummary(weeklySummaryService.getWeeklySummary(memberId, null));
        return ResponseEntity.ok(response);
    }

    @Operation(summary="메인화면 달력 데이터 조회",description = "메인화면에 달력 api")
    @GetMapping("/calendar")
    public ResponseEntity<CalendarMainResponseDto> getCalendarRecords(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @RequestParam int year,
            @RequestParam int month) {
        Long memberId = customUserDetails.getMember().getId();
        CalendarMainResponseDto response = sessionActivityQueryService.getCalendarMain(memberId, year, month);
        return ResponseEntity.ok(response);
    }

    @Operation(summary="특정 날짜 운동 목록", description = "달력에서 날짜 클릭 시 그 날의 운동 세션 목록 조회. 빈 날은 sessions=[] 반환")
    @GetMapping("/daily")
    public ResponseEntity<DailyActivityResponseDto> getDailyActivity(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Long memberId = customUserDetails.getMember().getId();
        DailyActivityResponseDto response = sessionActivityQueryService.getDailyActivity(memberId, date);
        return ResponseEntity.ok(response);
    }

    @Operation(summary="운동 메모",description = "운동 메모를 적을 수 있다")
    @PostMapping("/daily-logs")
    public ResponseEntity<Void> saveDailyLog(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @Valid @RequestBody DailyLogRequestDto request) {
        Long memberId = customUserDetails.getMember().getId();
        dailyLogService.saveOrUpdateLog(memberId, request);
        return ResponseEntity.noContent().build();
    }




}
