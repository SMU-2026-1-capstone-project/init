package com.shadowfit.service.coaching;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.repository.coaching.TrainerAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "이 트레이너가 이 사용자를 담당하는가" — role만으론 못 잡는 자원 소유권 검증
 * ({@code trainer-live-monitoring.md} §8 세션2).
 *
 * <p>{@code role=TRAINER} 여부는 여기서 검사하지 않는다 — 그건 기존 ADMIN 컨트롤러들과 같은
 * {@code @PreAuthorize("hasRole('TRAINER')")}로 세션3 컨트롤러에서 선언적으로 처리한다. 이
 * 서비스는 role 체크를 통과한 뒤에도 남는 질문, 즉 "TRAINER이긴 한데 하필 이 사용자를 담당하는
 * TRAINER인가"만 담당한다.
 */
@Service
@RequiredArgsConstructor
public class TrainerAuthorizationService {

    private final TrainerAssignmentRepository trainerAssignmentRepository;

    @Transactional(readOnly = true)
    public void assertAssignedTrainer(Long trainerId, Long userId) {
        if (!trainerAssignmentRepository.existsByTrainerIdAndUserId(trainerId, userId)) {
            throw new BusinessException(ErrorCode.NOT_ASSIGNED_TRAINER);
        }
    }
}
