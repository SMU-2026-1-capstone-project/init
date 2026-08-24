package com.shadowfit.repository.exercise;

import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.model.exercise.PoseData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * <b>조회 넷이 전부 {@code sessionAnchor} 를 받는 이유</b> (#392): {@code pose_data} 는
 * {@code created_at} 으로 월별 RANGE 파티셔닝돼 있는데({@code V1__baseline.sql}), {@code WHERE} 에
 * 그 컬럼이 없으면 <b>파티션 프루닝이 안 걸린다.</b> #204 EXPLAIN 스윕 실측: 인덱스를 셋 다
 * 바꿔봐도 <b>14개 파티션 전탐색</b>이고 {@code Handler_read_key} 가 14 — 파티션마다 인덱스
 * 다이브를 한 번씩 하고 있었다. {@code created_at} 을 같이 넘긴 판만 1파티션 · 다이브 1회로 준다.
 *
 * <p>넘길 값이 이미 손에 있다는 것이 핵심이다 — {@code created_at} 은 V6({@link
 * com.shadowfit.service.exercise.PoseDataService} 멱등 앵커, #188)부터 «적재 시각» 이 아니라
 * <b>세션 시작 시각</b>이라, 한 세션의 모든 행이 값 하나를 공유한다. 즉 호출부는
 * {@code session.getStartTime()} 하나로 파티션을 특정할 수 있고 <b>추가 조회가 필요 없다.</b>
 *
 * <p>🔴 <b>등호라서 앵커가 어긋난 행은 안 잡힌다.</b> V6 이전에 적재된 행은 컬럼
 * DEFAULT(CURRENT_TIMESTAMP)를 받아 적재 시각이 들어 있었고, 그 전제를
 * {@code V9__normalize_pose_created_at.sql} 이 없앴다(접고 보정). 즉 <b>이 술어는 V9 에 의존한다</b> —
 * 앵커를 안 맞춘 채로 이 조회를 쓰면 그 행들이 조용히 빠진다.
 */
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
           "p.id, p.timestampSec, p.syncRate, p.repNumber, p.smoothedKneeAngle) " +
           "FROM PoseData p WHERE p.session.id = :sessionId AND p.createdAt = :sessionAnchor " +
           "ORDER BY p.repNumber ASC, p.timestampSec ASC")
    List<PoseFrameProjection> findFramesBySessionId(@Param("sessionId") Long sessionId,
                                                    @Param("sessionAnchor") LocalDateTime sessionAnchor);

    /**
     * worst rep 대표 프레임의 자세 좌표 하나만 (P5 Tier 0, 32-deferred-items.md).
     *
     * <p><b>{@code createdAt} 을 같이 받는 이유는 다른 조회들과 같다</b>(클래스 주석) — {@code id} 만
     * 조건이면 PK가 {@code (id, created_at)} 복합키라 파티션 프루닝이 안 걸려 14개 파티션을 전부
     * 뒤진다. 둘 다 주면 복합 PK 그대로 단일 파티션 · 단일 행 다이브가 된다.
     *
     * <p>단일 행만 읽으므로 {@code findFramesBySessionId} 가 피하던 off-page I/O 비용은 딱 이
     * 한 번만 낸다 — 세션 전체 프레임(수백~수천)이 아니라 대표로 뽑힌 딱 하나이기 때문에 값이 있다.
     */
    @Query("SELECT p.jointCoordinates FROM PoseData p " +
           "WHERE p.id = :id AND p.createdAt = :sessionAnchor")
    Optional<String> findJointCoordinatesById(@Param("id") Long id,
                                              @Param("sessionAnchor") LocalDateTime sessionAnchor);

    // 재부착 시 AI 에 주입할 rep 카운트. AI 메모리가 증발해도 완료된 rep 은 진행 중에 이미
    // pose_data 로 넘어와 있다(docs/decisions/session-resume-and-ai-state.md §3-2).
    // COALESCE 로 0 을 주는 이유: 프레임이 한 건도 없는 세션(시작 직후 재부착)이면 MAX 가 null 이라
    // 호출부가 null 분기를 하나 더 지게 된다. rep_number 의 "미상"도 0 이라 의미가 어긋나지 않는다.
    @Query("SELECT COALESCE(MAX(p.repNumber), 0) FROM PoseData p " +
           "WHERE p.session.id = :sessionId AND p.createdAt = :sessionAnchor")
    int findMaxRepNumberBySessionId(@Param("sessionId") Long sessionId,
                                    @Param("sessionAnchor") LocalDateTime sessionAnchor);

    // 재부착 시 AI 에 주입할 «이미 흐른 초» (이슈 #156). 바로 위 rep 축 복원과 **같은 데이터원**을
    // 쓰는 것이 핵심이다 — timestamp_sec 은 AI 가 «첫 프레임 도착» 을 0 으로 잡아 만든 값이라,
    // 여기서 그대로 되읽으면 재부착 후에도 원점이 하나로 유지된다.
    //
    // session.start_time 으로부터의 경과를 쓰면 안 된다. 그건 원점이 «세션 생성» 이라, AI 가
    // 의도적으로 뺀 «자세 잡는 시간» 이 다시 들어가 재부착 이후 시각이 그만큼 부풀려진다.
    //
    // ⚠️ 대가: 마지막 프레임 이후 재부착까지의 공백(AI 장애 구간)은 시간 축에 안 잡힌다. 그 구간엔
    //    분석된 프레임이 없어 «운동 시각» 을 붙일 근거도 없고, 무엇보다 원점을 섞지 않는 쪽을 택했다.
    @Query("SELECT COALESCE(MAX(p.timestampSec), 0.0) FROM PoseData p " +
           "WHERE p.session.id = :sessionId AND p.createdAt = :sessionAnchor")
    double findMaxTimestampSecBySessionId(@Param("sessionId") Long sessionId,
                                          @Param("sessionAnchor") LocalDateTime sessionAnchor);

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
           "WHERE p.session.id = :sessionId AND p.createdAt = :sessionAnchor AND p.repNumber > 0 " +
           "GROUP BY p.repNumber ORDER BY p.repNumber")
    List<Double> findRepAverageSyncRates(@Param("sessionId") Long sessionId,
                                         @Param("sessionAnchor") LocalDateTime sessionAnchor);

    // 회원 탈퇴 시 pose_data 참조무결성 대체(FK CASCADE 제거로 인한 애플리케이션 정리).
    // PoseDataCleanupService에서 afterCommit 이후 비동기로 호출됨.
    // docs/decisions/pose-data-partition-fk-tradeoff.md 분기 B(B5) 참조.
    @Modifying
    @Query("DELETE FROM PoseData p WHERE p.session.id IN :sessionIds")
    void deleteBySessionIdIn(@Param("sessionIds") List<Long> sessionIds);

    // 🔴 countSince(sessionIds, since) 가 여기 있었다 — 탈퇴 가드의 "실제로 운동 중인가" 판정이다.
    //    지웠다 (#317). created_at 은 #188 멱등 때문에 **세션 시작 시각으로 고정**되므로, 그 컬럼의
    //    하한으로는 "최근에 프레임이 들어왔나"를 물을 수 없다 — 한 세션의 모든 행이 같은 값이다.
    //    판정은 SessionRepository.countActiveSince(last_active_at) 로 옮겼다.
    //
    //    되살리지 말 것. 옛 javadoc 은 "세션에 last_activity_at 을 두면 배치마다 UPDATE 가 붙어
    //    핫패스에 비용이 얹힌다"를 근거로 이 방식을 골랐는데, 그 UPDATE 는 이미 다른 이유로
    //    존재한다(session-liveness-vs-elapsed-time.md ㄷ안 — 타임아웃·재부착 판정의 앵커).
    //    즉 피하려던 비용은 이미 내고 있고, 근거가 사라졌다.
}