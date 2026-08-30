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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("RecommendationService 테스트")
class RecommendationServiceTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private MemberRepository memberRepository;
    private RecommendationService service;

    private static final Long MEMBER_ID = 1L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new RecommendationService(sessionRepository, memberRepository);
    }

    @Test
    @DisplayName("회원이 없으면 USER_NOT_FOUND")
    void memberNotFound_throws() {
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getNextSessionRecommendation(MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("콜드스타트 — 완료 세션이 없으면 workoutLevel 기반 시작 level을 그대로 쓴다")
    void coldStart_usesWorkoutLevelBaseline() {
        Member member = memberWith(WorkoutLevel.INTERMEDIATE, SelectedPersona.BEGINNER);
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(sessionRepository.findByMemberIdAndExerciseIdAndStatusOrderByStartTimeDesc(
                eq(MEMBER_ID), anyLong(), eq(Status.COMPLETED), any())).thenReturn(List.of());

        NextSessionRecommendationResponseDto result = service.getNextSessionRecommendation(MEMBER_ID);

        // INTERMEDIATE → level 5. persona=BEGINNER → baseReps 10, baseSyncRate 60.
        assertThat(result.difficultyLevel()).isEqualTo(5);
        assertThat(result.targetReps()).isEqualTo(10 + (5 - 1) * 2); // 18
        assertThat(result.targetSyncRate()).isEqualByComparingTo("68.0"); // 60 + (5-1)*2
        assertThat(result.restTimeSec()).isEqualTo(90 - (5 - 1) * 5); // 70
        assertThat(result.reason()).contains("아직 완료한 스쿼트 세션이 없어");
    }

    @Test
    @DisplayName("콜드스타트 — workoutLevel이 null(온보딩 전)이면 최저 level 1")
    void coldStart_nullWorkoutLevel_usesMinLevel() {
        Member member = memberWith(null, SelectedPersona.BEGINNER);
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(sessionRepository.findByMemberIdAndExerciseIdAndStatusOrderByStartTimeDesc(
                eq(MEMBER_ID), anyLong(), eq(Status.COMPLETED), any())).thenReturn(List.of());

        NextSessionRecommendationResponseDto result = service.getNextSessionRecommendation(MEMBER_ID);

        assertThat(result.difficultyLevel()).isEqualTo(1);
    }

    @Test
    @DisplayName("최근 평균 싱크로율 85% 이상 — 난이도 상향")
    void highAvgSyncRate_upgradesLevel() {
        Member member = memberWith(WorkoutLevel.STARTER, SelectedPersona.BEGINNER); // 시작 level 1
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        List<Session> recent = List.of(sessionWith("90.00"), sessionWith("88.00"), sessionWith("92.00"));
        when(sessionRepository.findByMemberIdAndExerciseIdAndStatusOrderByStartTimeDesc(
                eq(MEMBER_ID), anyLong(), eq(Status.COMPLETED), any())).thenReturn(recent);

        NextSessionRecommendationResponseDto result = service.getNextSessionRecommendation(MEMBER_ID);

        assertThat(result.difficultyLevel()).isEqualTo(2); // 1 + 1
        assertThat(result.reason()).contains("안정적").contains("상향");
    }

    @Test
    @DisplayName("최근 평균 싱크로율 60% 미만 — 난이도 유지/하향(하한 1 아래로는 안 내려감)")
    void lowAvgSyncRate_downgradesLevelButClampsAtMin() {
        Member member = memberWith(WorkoutLevel.STARTER, SelectedPersona.BEGINNER); // 시작 level 1
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        List<Session> recent = List.of(sessionWith("40.00"), sessionWith("35.00"), sessionWith("30.00"));
        when(sessionRepository.findByMemberIdAndExerciseIdAndStatusOrderByStartTimeDesc(
                eq(MEMBER_ID), anyLong(), eq(Status.COMPLETED), any())).thenReturn(recent);

        NextSessionRecommendationResponseDto result = service.getNextSessionRecommendation(MEMBER_ID);

        assertThat(result.difficultyLevel()).isEqualTo(1); // 1 - 1 = 0, clamp to MIN_LEVEL(1)
        assertThat(result.reason()).contains("폼 안정이 우선");
    }

    @Test
    @DisplayName("평균 싱크로율이 상향·하향 임계값 사이 — 난이도 유지")
    void midRangeAvgSyncRate_maintainsLevel() {
        Member member = memberWith(WorkoutLevel.INTERMEDIATE, SelectedPersona.BEGINNER); // 시작 level 5
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        List<Session> recent = List.of(sessionWith("70.00"), sessionWith("72.00"), sessionWith("75.00"));
        when(sessionRepository.findByMemberIdAndExerciseIdAndStatusOrderByStartTimeDesc(
                eq(MEMBER_ID), anyLong(), eq(Status.COMPLETED), any())).thenReturn(recent);

        NextSessionRecommendationResponseDto result = service.getNextSessionRecommendation(MEMBER_ID);

        assertThat(result.difficultyLevel()).isEqualTo(5);
        assertThat(result.reason()).contains("꾸준히 수행 중");
    }

    @Test
    @DisplayName("난이도 상한 10을 넘지 않는다")
    void level_clampsAtMax() {
        Member member = memberWith(WorkoutLevel.EXPERT, SelectedPersona.BEGINNER); // 시작 level 9
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        List<Session> recent = List.of(sessionWith("95.00"), sessionWith("96.00"), sessionWith("97.00"));
        when(sessionRepository.findByMemberIdAndExerciseIdAndStatusOrderByStartTimeDesc(
                eq(MEMBER_ID), anyLong(), eq(Status.COMPLETED), any())).thenReturn(recent);

        NextSessionRecommendationResponseDto result = service.getNextSessionRecommendation(MEMBER_ID);

        assertThat(result.difficultyLevel()).isEqualTo(10); // 9+1=10, clamp at MAX_LEVEL(10)
    }

    @Test
    @DisplayName("REHAB 페르소나는 baseReps가 5, baseSyncRate가 50")
    void rehabPersona_usesLowerBaseline() {
        Member member = memberWith(WorkoutLevel.STARTER, SelectedPersona.REHAB); // 시작 level 1
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(sessionRepository.findByMemberIdAndExerciseIdAndStatusOrderByStartTimeDesc(
                eq(MEMBER_ID), anyLong(), eq(Status.COMPLETED), any())).thenReturn(List.of());

        NextSessionRecommendationResponseDto result = service.getNextSessionRecommendation(MEMBER_ID);

        assertThat(result.targetReps()).isEqualTo(5); // baseReps(5) + (1-1)*2
        assertThat(result.targetSyncRate()).isEqualByComparingTo("50.0");
    }

    private static Member memberWith(WorkoutLevel workoutLevel, SelectedPersona persona) {
        Member member = mock(Member.class);
        when(member.getWorkoutLevel()).thenReturn(workoutLevel);
        when(member.getSelectedPersona()).thenReturn(persona);
        return member;
    }

    private static Session sessionWith(String avgSyncRate) {
        Session session = mock(Session.class);
        when(session.getAvgSyncRate()).thenReturn(new BigDecimal(avgSyncRate));
        return session;
    }
}
