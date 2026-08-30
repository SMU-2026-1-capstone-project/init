package com.shadowfit.service.recommendation;

import com.shadowfit.dto.recommendation.NextSessionRecommendationResponseDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.WorkoutLevel;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 다음 스쿼트 세션 강도·볼륨 추천 (BE-08, recommendation-algorithm.md).
 *
 * <p>순수 함수다 — {@code Session.difficultyLevel}을 읽지도 쓰지도 않는다. 그 컬럼은 세션 생성
 * 흐름 어디서도 실제로 채워지지 않아(항상 기본값 1) "직전 난이도에서 +1"이라는 원 규칙(§6)의
 * "직전 난이도"를 DB에서 가져올 수가 없다. 그래서 매 호출마다 <b>프로필 기반 시작 level +
 * 최근 N세션 평균으로 딱 한 단계 조정</b>을 새로 계산한다 — 상태를 어디에도 안 쌓으므로
 * 세션 생성 흐름을 건드릴 필요가 없고, GET이 정말로 멱등하다(2026-08-30 사용자 confirm,
 * "1번: 추천 API만, 세션 생성 안 건드림").
 *
 * <p>🔴 원 규칙의 "하락 추세" 조건(§6 "avg &lt; 60% OR 하락 추세")은 구현하지 않는다 — N=3개
 * 샘플로는 추세 판정이 통계적으로 얇고, 평균 임계값만으로도 규칙의 방향성은 그대로 유지된다.
 * 마찬가지로 "모두 현재 난이도 완수" 조건도 별도 확인이 없다 — COMPLETED 상태로 이미 걸러졌고
 * difficultyLevel이 죽어있어 "같은 난이도에서 완수"를 구분할 수 없기 때문.
 */
@Service
@RequiredArgsConstructor
public class RecommendationService {

    // squat-first([[project_squat_first]]) — 분석 가능한 운동이 스쿼트(id=1)뿐이라 하드코딩.
    // 운동이 늘어나면(2학기) 이 상수부터 goalType처럼 파라미터화해야 한다.
    private static final Long SQUAT_EXERCISE_ID = 1L;

    // 규칙 임계값 — 🔴 미검증 잠정치(recommendation-algorithm.md §6·§9, 2026-08-30 사용자
    // confirm: 실사용자 데이터가 없어 다른 값으로 바꿀 근거도 없다는 점을 인정하고 그대로 채택).
    // 실사용자 데이터 확보 후 재조정 대상.
    private static final int RECENT_SESSION_WINDOW = 3;
    private static final double UPGRADE_AVG_SYNC_RATE_THRESHOLD = 85.0;
    private static final double DOWNGRADE_AVG_SYNC_RATE_THRESHOLD = 60.0;

    private static final int MIN_LEVEL = 1;
    private static final int MAX_LEVEL = 10;

    private final SessionRepository sessionRepository;
    private final MemberRepository memberRepository;

    @Transactional(readOnly = true)
    public NextSessionRecommendationResponseDto getNextSessionRecommendation(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        List<Session> recentCompleted = sessionRepository.findByMemberIdAndExerciseIdAndStatusOrderByStartTimeDesc(
                memberId, SQUAT_EXERCISE_ID, Status.COMPLETED, Limit.of(RECENT_SESSION_WINDOW));

        int coldStartLevel = coldStartLevel(member.getWorkoutLevel());
        int level;
        String reason;

        if (recentCompleted.isEmpty()) {
            level = coldStartLevel;
            reason = "아직 완료한 스쿼트 세션이 없어 프로필 기준 시작 난이도로 추천합니다.";
        } else {
            BigDecimal avgSyncRate = averageSyncRate(recentCompleted);
            double avg = avgSyncRate.doubleValue();
            int sampleSize = recentCompleted.size();

            if (avg >= UPGRADE_AVG_SYNC_RATE_THRESHOLD) {
                level = clamp(coldStartLevel + 1);
                reason = "최근 %d세션 평균 싱크로율 %.1f%%로 안정적 → 난이도를 상향합니다."
                        .formatted(sampleSize, avg);
            } else if (avg < DOWNGRADE_AVG_SYNC_RATE_THRESHOLD) {
                level = clamp(coldStartLevel - 1);
                reason = "최근 %d세션 평균 싱크로율 %.1f%%로 폼 안정이 우선 → 난이도를 유지·하향합니다."
                        .formatted(sampleSize, avg);
            } else {
                level = clamp(coldStartLevel);
                reason = "최근 %d세션 평균 싱크로율 %.1f%%로 꾸준히 수행 중 → 현재 난이도를 유지합니다."
                        .formatted(sampleSize, avg);
            }
        }

        return buildRecommendation(level, member.getSelectedPersona(), reason);
    }

    // workoutLevel(자기신고 숙련도 5단계) → 시작 level(1~10) 매핑. 🔴 근거 없는 잠정치 —
    // 5단계를 1~10 범위에 고르게 편 것뿐(recommendation-algorithm.md §4·§9, 2026-08-30 사용자
    // confirm). 온보딩 전이라 workoutLevel이 null이면 가장 낮은 단계로(안전 우선).
    private static int coldStartLevel(WorkoutLevel workoutLevel) {
        if (workoutLevel == null) {
            return MIN_LEVEL;
        }
        return switch (workoutLevel) {
            case STARTER -> 1;
            case BEGINNER -> 3;
            case INTERMEDIATE -> 5;
            case ADVANCED -> 7;
            case EXPERT -> 9;
        };
    }

    private static BigDecimal averageSyncRate(List<Session> sessions) {
        BigDecimal sum = sessions.stream()
                .map(Session::getAvgSyncRate)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(sessions.size()), 2, RoundingMode.HALF_UP);
    }

    private static int clamp(int level) {
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
    }

    // docs/12-persona-difficulty.md의 getDifficultyConfig를 그대로 Java로 옮긴 것 — 이 공식이
    // 백엔드에 실제로 들어가는 건 이번이 처음이다(그전엔 TS 설계 문서로만 존재).
    private static NextSessionRecommendationResponseDto buildRecommendation(
            int level, SelectedPersona persona, String reason) {
        int baseReps = persona == SelectedPersona.REHAB ? 5 : 10;
        double baseSyncRate = switch (persona) {
            case BEGINNER -> 60.0;
            case ADVANCED -> 85.0;
            case DIET -> 70.0;
            case REHAB -> 50.0;
        };

        int targetReps = baseReps + (level - 1) * 2;
        BigDecimal targetSyncRate = BigDecimal.valueOf(Math.min(baseSyncRate + (level - 1) * 2, 95.0))
                .setScale(1, RoundingMode.HALF_UP);
        int restTimeSec = Math.max(90 - (level - 1) * 5, 30);

        return new NextSessionRecommendationResponseDto(level, targetReps, targetSyncRate, restTimeSec, reason);
    }
}
