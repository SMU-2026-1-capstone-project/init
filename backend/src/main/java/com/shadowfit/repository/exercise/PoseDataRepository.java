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

    /**
     * 세션의 <b>rep 단위</b> 평균 sync_rate 목록 (이슈 #75).
     *
     * <p><b>왜 rep 단위로 묶는가</b>: 한 rep 의 모든 프레임은 같은 sync_rate 를 공유하지만
     * (ai-server {@code pose.py} 가 rep 단위로 채워 보낸다), 저장 시 다운샘플(R≈5)이 걸려
     * <b>rep 마다 살아남은 행 수가 다르다.</b> 그냥 {@code AVG(sync_rate)} 를 내면 프레임이 많이
     * 남은 rep 이 더 무거워지는 <b>프레임 가중 평균</b>이 되어, AI 가 계산하던 <b>rep 가중 평균</b>과
     * 값이 달라진다. GROUP BY 로 rep 을 먼저 접어야 같은 의미가 된다.
     *
     * <p>{@code repNumber > 0} 인 이유: 0 은 "미상"이다 — 컬럼이 생기기 전에 저장된 행과, rep_number
     * 를 안 보내는 구버전 AI 의 행이 여기 해당한다(마이그레이션 {@code 2026-07-31-add-pose-data-rep-number.sql}).
     * 이 행들을 섞으면 서로 다른 rep 이 하나로 뭉뚱그려진다.
     *
     * <p>반환 행 수는 세션의 rep 수(수십 규모)라 호출부에서 avg/max/min 을 계산해도 부담이 없다.
     * DB 에서 한 번에 접으려면 파생 테이블(native)이 필요한데, 그 대가로 얻는 게 없다.
     */
    @Query("SELECT AVG(p.syncRate) FROM PoseData p " +
           "WHERE p.session.id = :sessionId AND p.repNumber > 0 " +
           "GROUP BY p.repNumber ORDER BY p.repNumber")
    List<Double> findRepAverageSyncRates(@Param("sessionId") Long sessionId);

    // 회원 탈퇴 시 pose_data 참조무결성 대체(FK CASCADE 제거로 인한 애플리케이션 정리).
    // PoseDataCleanupService에서 afterCommit 이후 비동기로 호출됨.
    // docs/decisions/pose-data-partition-fk-tradeoff.md 분기 B(B5) 참조.
    @Modifying
    @Query("DELETE FROM PoseData p WHERE p.session.id IN :sessionIds")
    void deleteBySessionIdIn(@Param("sessionIds") List<Long> sessionIds);
}