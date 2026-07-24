package com.shadowfit.service.Exercise;

import com.shadowfit.dto.admin.ExerciseThresholdResponseDto;
import com.shadowfit.dto.admin.ThresholdUpdateDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
import com.shadowfit.repository.exercise.ExercisesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@DisplayName("AdminExerciseService 테스트")
class AdminExerciseServiceTest {

    @Mock private ExercisesRepository exercisesRepository;
    private AdminExerciseService service;

    private static final Long EXERCISE_ID = 1L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new AdminExerciseService(exercisesRepository);
    }

    private Exercise exercise() {
        return Exercise.builder().id(EXERCISE_ID).name("스쿼트").category(ExerciseCategory.LOWER)
                .expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .syncThresholdDiet(new BigDecimal("70.00")).syncThresholdRehab(new BigDecimal("50.00"))
                .build();
    }

    @Test
    @DisplayName("정상 변경 — 4개 임계값 전부 갱신")
    void updateThresholds_success() {
        Exercise exercise = exercise();
        when(exercisesRepository.findById(EXERCISE_ID)).thenReturn(Optional.of(exercise));
        ThresholdUpdateDto dto = new ThresholdUpdateDto(
                new BigDecimal("55"), new BigDecimal("90"), new BigDecimal("65"), new BigDecimal("45"));

        ExerciseThresholdResponseDto result = service.updateThresholds(EXERCISE_ID, dto);

        assertThat(result.syncThresholdBeginner()).isEqualByComparingTo(new BigDecimal("55"));
        assertThat(result.syncThresholdAdvanced()).isEqualByComparingTo(new BigDecimal("90"));
        assertThat(exercise.getSyncThresholdDiet()).isEqualByComparingTo(new BigDecimal("65"));
    }

    @Test
    @DisplayName("beginner >= advanced 이면 INVALID_INPUT_VALUE, 저장 시도 자체를 안 함")
    void updateThresholds_beginnerNotLessThanAdvanced_throws() {
        ThresholdUpdateDto dto = new ThresholdUpdateDto(
                new BigDecimal("90"), new BigDecimal("90"), new BigDecimal("65"), new BigDecimal("45"));

        assertThatThrownBy(() -> service.updateThresholds(EXERCISE_ID, dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);

        // beginner==advanced 케이스라 findById까지 안 가고 검증에서 바로 걸려야 함
        org.mockito.Mockito.verifyNoInteractions(exercisesRepository);
    }

    @Test
    @DisplayName("존재하지 않는 운동이면 EXERCISE_NOT_FOUND")
    void updateThresholds_exerciseNotFound_throws() {
        when(exercisesRepository.findById(EXERCISE_ID)).thenReturn(Optional.empty());
        ThresholdUpdateDto dto = new ThresholdUpdateDto(
                new BigDecimal("55"), new BigDecimal("90"), new BigDecimal("65"), new BigDecimal("45"));

        assertThatThrownBy(() -> service.updateThresholds(EXERCISE_ID, dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXERCISE_NOT_FOUND);
    }
}
