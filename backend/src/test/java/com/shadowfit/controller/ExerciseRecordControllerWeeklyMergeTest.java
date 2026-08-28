package com.shadowfit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.shadowfit.dto.report.record.WeeklyActivityResponseDto;
import com.shadowfit.dto.report.weekly.WeeklySummaryResponseDto;
import com.shadowfit.dto.report.weekly.WeeklyTotalsDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.model.member.Member;
import com.shadowfit.service.Report.DailyLogService;
import com.shadowfit.service.Exercise.SessionActivityQueryService;
import com.shadowfit.service.Report.WeeklySummaryService;

/**
 * 주간이 <b>한 응답</b>으로 나가는지 지킨다 (이슈 #352).
 *
 * <p><b>왜 이 테스트인가.</b> A층 요약은 {@code GET /reports/weekly} 로 따로 나가 있었는데
 * <b>부르는 곳이 저장소에 없었다</b> — 같은 base 에 «주간» 이 둘이라(`/reports/weekly-summary` ·
 * `/reports/weekly`) 프론트에서 보면 이미 하나 있어서 새것이 안 보였다. 합친 뒤에 누가
 * {@code summary} 조립을 빼면 <b>증상이 똑같아진다</b>: 응답은 200 이고 기존 필드도 멀쩡해서
 * 화면이 안 깨지고, 새 값만 조용히 사라진다. 그 조용함을 여기서 막는다.
 *
 * <p>⚠️ 스프링 컨텍스트를 안 띄운다 — 이 테스트가 묻는 것은 «컨트롤러가 두 서비스를 다 부르고
 * 응답에 얹는가» 이고, 라우팅·보안은 {@code ProtectedEndpointSmokeTest} 의 몫이다.
 */
class ExerciseRecordControllerWeeklyMergeTest {

    private final SessionActivityQueryService sessionActivityQueryService = mock(SessionActivityQueryService.class);
    private final DailyLogService dailyLogService = mock(DailyLogService.class);
    private final WeeklySummaryService weeklySummaryService = mock(WeeklySummaryService.class);

    private final ExerciseRecordController controller =
            new ExerciseRecordController(sessionActivityQueryService, dailyLogService, weeklySummaryService);

    @Test
    @DisplayName("#352 /reports/weekly-summary 는 활동 집계와 A층 요약을 «한 응답» 으로 돌려준다")
    void weeklySummary_carriesBothHalves() {
        Member member = mock(Member.class);
        when(member.getId()).thenReturn(7L);
        CustomUserDetails principal = mock(CustomUserDetails.class);
        when(principal.getMember()).thenReturn(member);

        WeeklyActivityResponseDto activity = WeeklyActivityResponseDto.builder()
                .dateRange("8월 17일 - 23일")
                .totalWorkouts(4)
                .build();
        when(sessionActivityQueryService.getWeeklyActivity(7L)).thenReturn(activity);

        WeeklySummaryResponseDto summary = new WeeklySummaryResponseDto(
                LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 24),
                WeeklyTotalsDto.empty(), WeeklyTotalsDto.empty(), List.of("이번 주는 4번 운동했어요"));
        when(weeklySummaryService.getWeeklySummary(eq(7L), any())).thenReturn(summary);

        ResponseEntity<WeeklyActivityResponseDto> response = controller.getWeeklySummary(principal);

        assertThat(response.getBody()).isNotNull();
        // 기존 절반 — 프론트가 이미 읽던 필드다. 합치면서 깨지지 않아야 한다
        assertThat(response.getBody().getTotalWorkouts()).isEqualTo(4);
        // 🔴 새 절반 — 이게 빠지면 «조용히 사라지는» 그 상태로 되돌아간다
        assertThat(response.getBody().getSummary()).isSameAs(summary);
    }

    @Test
    @DisplayName("#352 A층 요약은 기준일 없이(=오늘이 속한 주) 부른다 — 두 절반이 다른 주를 보면 안 된다")
    void weeklySummary_usesCurrentWeekForBothHalves() {
        Member member = mock(Member.class);
        when(member.getId()).thenReturn(7L);
        CustomUserDetails principal = mock(CustomUserDetails.class);
        when(principal.getMember()).thenReturn(member);
        when(sessionActivityQueryService.getWeeklyActivity(7L))
                .thenReturn(WeeklyActivityResponseDto.builder().build());
        when(weeklySummaryService.getWeeklySummary(eq(7L), any()))
                .thenReturn(new WeeklySummaryResponseDto(null, null,
                        WeeklyTotalsDto.empty(), WeeklyTotalsDto.empty(), List.of()));

        controller.getWeeklySummary(principal);

        // 활동 집계(getWeeklyActivity)는 «이번 주» 고정이다. A층에만 기준일을 주면 같은 응답 안에서
        // 두 절반이 다른 주를 가리킨다 — #352 가 잡은 것과 같은 종류의 조용한 어긋남이다.
        verify(weeklySummaryService).getWeeklySummary(7L, null);
    }
}
