package com.shadowfit.dto.pattern;

import java.time.LocalTime;

/**
 * periodicity 시간대 4구간 (BE-07). 통계로 도출한 경계가 아니라 생활권 구분(출근·점심·퇴근·취침 기준)
 * 관례값이다 — 2026-08-30 사용자 확인. 경계는 [from, to) 반개구간.
 *
 * <p>아침 05~11시 · 오후 11~17시 · 저녁 17~22시 · 밤 22~05시(자정 넘어감).
 */
public enum TimeBucket {
    MORNING, AFTERNOON, EVENING, NIGHT;

    public static TimeBucket of(LocalTime time) {
        int hour = time.getHour();
        if (hour >= 5 && hour < 11) {
            return MORNING;
        }
        if (hour >= 11 && hour < 17) {
            return AFTERNOON;
        }
        if (hour >= 17 && hour < 22) {
            return EVENING;
        }
        return NIGHT;
    }
}
