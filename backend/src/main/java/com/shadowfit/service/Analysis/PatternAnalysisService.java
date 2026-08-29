package com.shadowfit.service.Analysis;

import com.shadowfit.dto.pattern.ConsistencyResponseDto;
import com.shadowfit.dto.pattern.IntensityTrendResponseDto;
import com.shadowfit.dto.pattern.PeriodicityResponseDto;
import com.shadowfit.repository.exercise.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PatternAnalysisService {

    private final SessionRepository sessionRepository;

    // 세션 1 — 골격만. 요일·시간대 집계는 세션 2(pattern-analysis-implementation.md §3)에서 구현.
    public PeriodicityResponseDto getPeriodicity(Long memberId) {
        return new PeriodicityResponseDto(List.of(), List.of());
    }

    // 세션 1 — 골격만. 4주 윈도우 주 단위 집계는 세션 3에서 구현.
    public IntensityTrendResponseDto getIntensityTrend(Long memberId) {
        return new IntensityTrendResponseDto(List.of());
    }

    // 세션 1 — 골격만. 연속일수·결측일 계산은 세션 4에서 구현.
    public ConsistencyResponseDto getConsistency(Long memberId) {
        return new ConsistencyResponseDto(0, 0);
    }
}
