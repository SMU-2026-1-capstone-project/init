package com.shadowfit.dto.admin;

import com.shadowfit.model.exercise.Status;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.Map;

/**
 * 관리자 대시보드 통계 위젯 5종 ({@code admin-page-scope.md} §3-D).
 *
 * <p><b>한 응답으로 묶은 이유</b> — 필터가 0 이고 화면 하나가 다섯 값을 동시에 그린다.
 * 위젯마다 엔드포인트를 나누면 왕복이 5배가 되는데, 나눠서 얻을 것(위젯별 캐시 TTL 을
 * 따로 주는 것)은 아직 필요하지 않다. 갈라야 할 근거가 생기면 그때 나누는 편이 싸다.
 *
 * <p><b>기준 시각을 응답에 실은 이유</b> — 집계 결과는 "언제 기준인가"가 없으면 해석할 수
 * 없다. 지금은 매 요청 실시간 집계라 곧 요청 시각이지만, 사전집계나 캐시를 도입하면 이 값이
 * 응답 시각과 벌어진다. <b>그때 필드를 새로 만들면 기존 화면이 조용히 옛 뜻으로 읽는다</b> —
 * 처음부터 실어 둔다.
 */
@Schema(description = "관리자 대시보드 통계")
public record AdminStatsOverviewDto(

        @Schema(description = "집계 기준 날짜 ('오늘' 위젯들의 기준)", example = "2026-08-06")
        LocalDate baseDate,

        @Schema(description = "오늘 시작된 세션 수", example = "42")
        long todaySessionCount,

        @Schema(description = "상태별 세션 분포 (전체 기간). 0 건인 상태도 0 으로 포함된다")
        Map<Status, Long> sessionCountByStatus,

        @Schema(description = "오늘 완료된 세션의 평균 싱크로율(%). "
                + "완료 세션이 없으면 null — '0%' 와 '잰 적 없음'은 다르다", example = "78.35")
        Double todayAverageSyncRate,

        @Schema(description = "오늘 신규 가입자 수", example = "7")
        long todayNewMemberCount,

        @Schema(description = "활성 회원 수 — 최근 N일 내 세션을 시작한 서로 다른 회원", example = "153")
        long activeMemberCount,

        @Schema(description = "활성 회원 판정 기간(일). activeMemberCount 의 N", example = "7")
        int activeMemberWindowDays
) {
}
