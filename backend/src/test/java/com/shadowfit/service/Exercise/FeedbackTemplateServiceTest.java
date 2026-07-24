package com.shadowfit.service.Exercise;

import com.shadowfit.dto.exercises.feedback.FeedbackTemplateDto;
import com.shadowfit.model.exercise.ExerciseFeedbackTemplate;
import com.shadowfit.model.exercise.FeedbackType;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.repository.exercise.ExerciseFeedbackTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * FeedbackTemplateService 테스트 — persona row가 있으면 그것, 없으면 persona IS NULL
 * fallback을 쓰는 merge 로직(분기 4-A) 검증.
 */
@DisplayName("FeedbackTemplateService 테스트")
class FeedbackTemplateServiceTest {

    @Mock private ExerciseFeedbackTemplateRepository templateRepository;
    private FeedbackTemplateService service;

    private static final Long EXERCISE_ID = 1L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new FeedbackTemplateService(templateRepository);
    }

    private ExerciseFeedbackTemplate template(FeedbackType type, SelectedPersona persona, String message, int priority) {
        return ExerciseFeedbackTemplate.builder()
                .feedbackType(type).persona(persona).message(message).priority(priority).build();
    }

    @Test
    @DisplayName("persona가 null이면 fallback(공통) 템플릿만 반환")
    void nullPersona_returnsOnlyFallback() {
        when(templateRepository.findByExerciseIdAndPersonaIsNullOrderByPriorityAsc(EXERCISE_ID))
                .thenReturn(List.of(template(FeedbackType.KNEE_OUT, null, "무릎 조심", 1)));

        List<FeedbackTemplateDto> result = service.getTemplatesByExercise(EXERCISE_ID, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).message()).isEqualTo("무릎 조심");
    }

    @Test
    @DisplayName("페르소나 전용 row가 있으면 그게 같은 feedback_type의 fallback을 덮어씀")
    void personaTemplate_overridesFallbackForSameType() {
        when(templateRepository.findByExerciseIdAndPersonaIsNullOrderByPriorityAsc(EXERCISE_ID))
                .thenReturn(List.of(template(FeedbackType.KNEE_OUT, null, "공통 메시지", 1)));
        when(templateRepository.findByExerciseIdAndPersonaOrderByPriorityAsc(EXERCISE_ID, SelectedPersona.REHAB))
                .thenReturn(List.of(template(FeedbackType.KNEE_OUT, SelectedPersona.REHAB, "재활 전용 메시지", 1)));

        List<FeedbackTemplateDto> result = service.getTemplatesByExercise(EXERCISE_ID, SelectedPersona.REHAB);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).message()).isEqualTo("재활 전용 메시지"); // fallback 아니라 페르소나 전용이 이김
    }

    @Test
    @DisplayName("페르소나 row가 없는 feedback_type은 fallback으로 채워짐 — 페르소나·공통 합쳐진 결과")
    void mixedTypes_fallbackFillsGapsNotCoveredByPersona() {
        when(templateRepository.findByExerciseIdAndPersonaIsNullOrderByPriorityAsc(EXERCISE_ID))
                .thenReturn(List.of(
                        template(FeedbackType.KNEE_OUT, null, "공통-무릎", 1),
                        template(FeedbackType.HIP_HIGH, null, "공통-엉덩이", 2)
                ));
        when(templateRepository.findByExerciseIdAndPersonaOrderByPriorityAsc(EXERCISE_ID, SelectedPersona.REHAB))
                .thenReturn(List.of(template(FeedbackType.KNEE_OUT, SelectedPersona.REHAB, "재활-무릎", 1)));

        List<FeedbackTemplateDto> result = service.getTemplatesByExercise(EXERCISE_ID, SelectedPersona.REHAB);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(FeedbackTemplateDto::message)
                .containsExactlyInAnyOrder("재활-무릎", "공통-엉덩이");
    }

    @Test
    @DisplayName("결과는 priority 오름차순 정렬")
    void result_sortedByPriorityAscending() {
        when(templateRepository.findByExerciseIdAndPersonaIsNullOrderByPriorityAsc(EXERCISE_ID))
                .thenReturn(List.of(
                        template(FeedbackType.HIP_HIGH, null, "낮은 우선순위", 100),
                        template(FeedbackType.KNEE_OUT, null, "높은 우선순위", 1)
                ));

        List<FeedbackTemplateDto> result = service.getTemplatesByExercise(EXERCISE_ID, null);

        assertThat(result).extracting(FeedbackTemplateDto::message)
                .containsExactly("높은 우선순위", "낮은 우선순위");
    }
}
