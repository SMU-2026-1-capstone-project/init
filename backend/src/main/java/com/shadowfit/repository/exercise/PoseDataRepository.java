package com.shadowfit.repository.exercise;

import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.model.exercise.PoseData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PoseDataRepository extends JpaRepository<PoseData, Long> {
    // 리포트 worst 구간 계산에 필요한 3컬럼만 조회. joint_coordinates(2.3KB JSON) 제외 → off-page I/O 회피.
    @Query("SELECT new com.shadowfit.dto.report.PoseFrameProjection(" +
           "p.timestampSec, p.syncRate, p.feedbackMessage) " +
           "FROM PoseData p WHERE p.session.id = :sessionId ORDER BY p.timestampSec ASC")
    List<PoseFrameProjection> findFramesBySessionId(@Param("sessionId") Long sessionId);

    // 재부착 시 AI 에 주입할 rep 카운트. AI 메모리가 증발해도 완료된 rep 은 진행 중에 이미
    // pose_data 로 넘어와 있다(docs/decisions/session-resume-and-ai-state.md §3-2).
    // COALESCE 로 0 을 주는 이유: 프레임이 한 건도 없는 세션(시작 직후 재부착)이면 MAX 가 null 이라
    // 호출부가 null 분기를 하나 더 지게 된다. rep_number 의 "미상"도 0 이라 의미가 어긋나지 않는다.
    @Query("SELECT COALESCE(MAX(p.repNumber), 0) FROM PoseData p WHERE p.session.id = :sessionId")
    int findMaxRepNumberBySessionId(@Param("sessionId") Long sessionId);

    // 회원 탈퇴 시 pose_data 참조무결성 대체(FK CASCADE 제거로 인한 애플리케이션 정리).
    // PoseDataCleanupService에서 afterCommit 이후 비동기로 호출됨.
    // docs/decisions/pose-data-partition-fk-tradeoff.md 분기 B(B5) 참조.
    @Modifying
    @Query("DELETE FROM PoseData p WHERE p.session.id IN :sessionIds")
    void deleteBySessionIdIn(@Param("sessionIds") List<Long> sessionIds);
}