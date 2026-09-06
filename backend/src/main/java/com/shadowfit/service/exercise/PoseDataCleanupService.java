package com.shadowfit.service.exercise;

import com.shadowfit.repository.exercise.PoseDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * pose_data는 파티셔닝(TTL 만료 시 DROP PARTITION) 적용을 위해 FK(ON DELETE CASCADE)를
 * 제거했다 — MySQL/InnoDB가 FK 걸린 테이블의 파티셔닝을 지원하지 않기 때문
 * (docs/decisions/pose-data-partition-fk-tradeoff.md).
 *
 * 그래서 회원 탈퇴로 세션이 사라져도 pose_data는 더 이상 DB가 자동으로 지워주지 않는다.
 * 이 서비스가 그 대체 — 회원 탈퇴 트랜잭션이 커밋된 직후(afterCommit), 스케줄 대기 없이
 * 즉시 비동기로 트리거된다(개인정보보호법 제21조 "지체없이" 파기 요건 대응, 단 탈퇴 API
 * 응답 자체는 기다리지 않음).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PoseDataCleanupService {

    private final PoseDataRepository poseDataRepository;

    @Async("applicationTaskExecutor")
    @Transactional
    public void cleanupBySessionIds(List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return;
        }
        poseDataRepository.deleteBySessionIdIn(sessionIds);
        log.info("회원 탈퇴에 따른 pose_data 비동기 정리 완료 - 세션 {}개", sessionIds.size());
    }
}
