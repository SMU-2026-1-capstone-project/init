package com.shadowfit.dto.pattern;

import java.time.DayOfWeek;
import java.util.List;

/**
 * GET /patterns/periodicity — 요일·시간대별 세션 분포 (BE-07). 최근 4주 고정 윈도우.
 * 한 건도 없는 요일·시간대는 목록에서 빠진다(SessionRepository.countGroupedByStatus와 같은 관례
 * — 호출부/프론트가 0으로 채운다).
 *
 * @param sufficientData 가입일(Member.createdAt) 기준 4주 이상 지났는지(세션7, 2026-08-30 사용자
 *                        확인). false면 나머지 필드가 비어 있는 게 "활동 없음"이 아니라 "창 자체가
 *                        아직 안 채워짐"이라는 뜻 — 프론트가 그래프 대신 안내 문구를 고를 근거.
 */
public record PeriodicityResponseDto(
        boolean sufficientData,
        List<DayOfWeekCount> byDayOfWeek,
        List<TimeOfDayCount> byTimeOfDay
) {
    public record DayOfWeekCount(DayOfWeek dayOfWeek, long sessionCount) {}

    public record TimeOfDayCount(TimeBucket bucket, long sessionCount) {}
}
