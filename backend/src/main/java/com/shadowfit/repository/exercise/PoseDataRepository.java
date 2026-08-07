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
    /**
     * 리포트 worst rep 계산용 프레임 조회. joint_coordinates(2.3KB JSON) 제외 → off-page I/O 회피.
     *
     * <p><b>rep_number 를 싣는 이유</b>(이슈 #78): {@code sync_rate} 는 rep 안에서 상수라
     * (ai-server 가 rep 단위로 채점해 프레임마다 복제 전송) rep 을 모르면 계산기가 경계를 넘는
     * 것을 막을 수 없다. 컬럼은 #74 에서 생겼지만 이 프로젝션은 그 전에 만들어져 갱신되지 않았다.
     *
     * <p><b>정렬을 rep 우선으로</b>: 계산기가 rep 별로 묶으므로 같은 rep 의 프레임이 인접해야
     * 그룹핑이 자연스럽고, 그룹 안의 순서(대표 프레임 선택)도 시간순으로 확정된다. 예전 정렬
     * ({@code timestampSec} 단독)은 rep 을 안 볼 때만 의미가 있었다.
     *
     * <p>{@code repNumber = 0}(미상) 행도 그대로 가져온다 — 필터링은 계산기가 한다. 그래야
     * "프레임이 아예 없다"와 "rep 을 알 수 없는 프레임뿐이다"를 계산기가 구분해 다룰 수 있다.
     */
    @Query("SELECT new com.shadowfit.dto.report.PoseFrameProjection(" +
           "p.timestampSec, p.syncRate, p.repNumber, p.smoothedKneeAngle) " +
           "FROM PoseData p WHERE p.session.id = :sessionId " +
           "ORDER BY p.repNumber ASC, p.timestampSec ASC")
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

    /**
     * 이 세션들에 {@code since} 이후 들어온 프레임이 있는지 — <b>"실제로 운동 중인가"</b>의 판정
     * (MemberService.deleteAccount, docs/decisions/withdrawal-with-active-session.md §3-2).
     *
     * <p><b>왜 세션 상태가 아니라 이걸 보나.</b> {@code IN_PROGRESS} 는 앱이 죽으면 갱신되지 않아
     * 최대 "예상 운동시간 + 버퍼"(스쿼트 ~45분) 동안 남는다. 그 상태값으로 탈퇴를 막으면 운동 중이
     * 아닌 사용자가 이유도 모른 채 막힌다. 반면 살아있는 세션은 rep 단위로 3~4초마다 프레임을
     * 보내므로, <b>유입이 끊긴 것은 세션이 죽었다는 직접 증거</b>다.
     *
     * <p><b>비용</b>: {@code created_at} 하한이 있어 파티션 pruning 이 걸려 최근 파티션만 본다.
     * 대상 세션도 회원당 0~1건이라 가볍다. 탈퇴는 드문 경로이므로 이 쿼리 1회는 감수 가능하다
     * — 반대로 세션에 {@code last_activity_at} 을 두는 방식은 배치 저장마다 UPDATE 가 붙어
     * <b>핫패스</b>에 비용을 얹으므로 택하지 않았다(같은 문서 §3-2).
     */
    @Query("SELECT COUNT(p) FROM PoseData p " +
           "WHERE p.session.id IN :sessionIds AND p.createdAt > :since")
    long countSince(@Param("sessionIds") List<Long> sessionIds,
                    @Param("since") java.time.LocalDateTime since);
}