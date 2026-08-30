package com.shadowfit.service.coaching;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.repository.coaching.TrainerAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 세션3의 SSE 컨트롤러가 붙기 전까지, "미배정 트레이너가 막힌다"를 서비스 단위로 검증한다
 * — 실제 HTTP 403 통합 검증은 컨트롤러가 생기는 세션3에서 이어진다.
 */
@DisplayName("TrainerAuthorizationService 테스트")
class TrainerAuthorizationServiceTest {

    private static final Long TRAINER_ID = 1L;
    private static final Long OTHER_TRAINER_ID = 2L;
    private static final Long USER_ID = 10L;

    @Mock private TrainerAssignmentRepository trainerAssignmentRepository;
    private TrainerAuthorizationService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new TrainerAuthorizationService(trainerAssignmentRepository);
    }

    @Test
    @DisplayName("담당 트레이너면 예외 없이 통과한다")
    void assignedTrainer_passes() {
        when(trainerAssignmentRepository.existsByTrainerIdAndUserId(TRAINER_ID, USER_ID)).thenReturn(true);

        assertThatCode(() -> service.assertAssignedTrainer(TRAINER_ID, USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("담당하지 않는 사용자면 NOT_ASSIGNED_TRAINER(403)를 던진다")
    void unassignedTrainer_throwsForbidden() {
        when(trainerAssignmentRepository.existsByTrainerIdAndUserId(OTHER_TRAINER_ID, USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.assertAssignedTrainer(OTHER_TRAINER_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_ASSIGNED_TRAINER);
    }
}
