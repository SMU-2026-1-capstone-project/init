package com.shadowfit.repository.exercise;

import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SessionRepository extends JpaRepository<Session,Long> {
    // 소유권을 WHERE절에 박아 조회-후-검증(fetch-then-check) 대신 구조적으로 IDOR 차단.
    // 본인 세션이 아니거나 존재하지 않으면 똑같이 empty — "존재하지만 남의 것"이라는 정보를 노출 안 함.
    @Query("SELECT s FROM Session s JOIN FETCH s.exercise WHERE s.id = :sessionId AND s.member.id = :memberId")
    Optional<Session> findSessionWithExerciseByIdAndMemberId(@Param("sessionId") Long sessionId,
                                                              @Param("memberId") Long memberId);

    // 개별 세션 삭제(deleteSession) 전용 — exercise fetch join 불필요, 소유권만 WHERE절로 확인.
    Optional<Session> findByIdAndMemberId(Long sessionId, Long memberId);

    // 현재 조회 중인 세션 자체를 "이전 세션"으로 잘못 뽑지 않도록 excludeSessionId로 제외
    // (CodeRabbit 리뷰로 발견 — 현재 세션이 해당 운동의 가장 최근 완료 세션이면 자기 자신과
    // 비교하는 조용한 버그가 있었음, ReportService.getSessionReport §3).
    Optional<Session> findFirstByMemberIdAndExerciseIdAndStatusAndIdNotOrderByStartTimeDesc(
            Long memberId, Long exerciseId, Status status, Long excludeSessionId
    );

    List<Session> findByMemberIdAndStartTimeBetween(Long memberId, LocalDateTime start, LocalDateTime end);

    // exercise를 fetch join해서 한 방에 가져옴 — getWeeklyActivity N+1 방지
    @Query("SELECT s FROM Session s JOIN FETCH s.exercise " +
           "WHERE s.member.id = :memberId AND s.startTime BETWEEN :start AND :end")
    List<Session> findWeeklySessionsWithExercise(@Param("memberId") Long memberId,
                                                 @Param("start") LocalDateTime start,
                                                 @Param("end") LocalDateTime end);

    // 활동한 날짜 목록만 가져옴 — calculateConsecutiveDays 루프 N+1 방지.
    // 반환 타입은 java.sql.Date로 받는다 — List<LocalDate>로 바로 받으면 Spring Data의
    // ConversionService가 java.sql.Date -> LocalDate 컨버터를 못 찾아 ConverterNotFoundException.
    // 변환은 호출부(calculateConsecutiveDays)에서 Date.toLocalDate()로 처리.
    @Query("SELECT DISTINCT CAST(s.startTime AS date) FROM Session s " +
           "WHERE s.member.id = :memberId AND s.startTime BETWEEN :start AND :end")
    List<Date> findDistinctActiveDates(@Param("memberId") Long memberId,
                                       @Param("start") LocalDateTime start,
                                       @Param("end") LocalDateTime end);

    @Query("SELECT s FROM Session s JOIN FETCH s.exercise WHERE s.status = :status")
    List<Session> findByStatus(@Param("status") Status status);

    // 회원당 활성 세션 1개 제약 — MemberRepository.findByIdForUpdate로 잠근 뒤 체크해야
    // TOCTOU 없이 안전함(단독 호출 시엔 레이스 존재).
    boolean existsByMemberIdAndStatus(Long memberId, Status status);

    // 진행 중 세션 조회(GET /sessions/active) — 클라가 앱 재시작 후 sessionId를 복원하는 경로.
    //
    // findFirst...(= SQL LIMIT 1): 회원당 활성 세션은 1개여야 하지만 그 불변식은 DB 제약이 아니라
    // 애플리케이션 규약(createSession이 회원 row를 잠그고 체크)이다. 단건 시그니처로 받으면 규약이
    // 깨진 순간 NonUniqueResultException이 나서, 하필 "갇힘을 푸는 API"가 갇힘 때문에 터진다.
    // LIMIT 1이면 그 경우에도 가장 최근 세션을 돌려주고 조회는 계속 동작한다.
    //
    // @EntityGraph 로 exercise를 함께 로딩 — open-in-view: false 라 컨트롤러에서 lazy 접근이
    // 터진다. @Query + JOIN FETCH 로도 되지만 그러면 LIMIT을 SQL에 못 실어 전 행을 가져와야 한다.
    // exercise는 @ManyToOne(to-one)이라 join + LIMIT 조합이 안전하다(컬렉션 fetch + 페이징의
    // 인메모리 처리 문제는 해당 없음). idx_session_member_status (member_id, status) 를 탄다.
    @EntityGraph(attributePaths = "exercise")
    Optional<Session> findFirstByMemberIdAndStatusOrderByStartTimeDesc(Long memberId, Status status);

    // 회원 탈퇴 시 pose_data 비동기 정리용 — session_id 목록만 가볍게 조회.
    // pose_data의 FK(CASCADE)를 파티셔닝 때문에 제거해서, 탈퇴로 세션이 사라지기 전에
    // 미리 확보해둬야 함 (docs/decisions/pose-data-partition-fk-tradeoff.md).
    @Query("SELECT s.id FROM Session s WHERE s.member.id = :memberId")
    List<Long> findIdsByMemberId(@Param("memberId") Long memberId);

    // 탈퇴 가드용 — 특정 상태의 세션 id 만(회원당 활성 세션은 1개 규약이라 보통 0~1건).
    // 여기서 얻은 id 로 pose_data 유입 여부를 확인해 "실제로 운동 중인지"를 판정한다
    // (MemberService.deleteAccount, docs/decisions/withdrawal-with-active-session.md §3-2).
    // idx_session_member_status (member_id, status) 를 탄다.
    @Query("SELECT s.id FROM Session s WHERE s.member.id = :memberId AND s.status = :status")
    List<Long> findIdsByMemberIdAndStatus(@Param("memberId") Long memberId, @Param("status") Status status);

    // ─── 관리자 대시보드 집계 (admin-page-scope.md §3-D) ───────────────────────────
    //
    // 목록(A·B)과 성격이 다르다. 목록은 LIMIT 20 이라 인덱스만 타면 20건만 만지고 끝나지만,
    // 집계는 조건에 걸린 행을 전부 만져야 답이 나온다 — 인덱스로 범위를 줄여도 줄어든 범위
    // 전체를 읽는다. 그래서 QueryDSL 로 감싸지 않는다. 조건이 고정이라 동적 조립이 필요 없고,
    // 여기서 드는 비용은 쿼리 빌더가 아니라 집계 자체다 (§3-D "전부 조건 고정 집계").

    /**
     * 기간 내 시작된 세션 수.
     *
     * <p>⚠️ {@code idx_session_status_starttime (status, start_time)} 은 <b>탈 수 없다</b> —
     * 선두 컬럼이 {@code status} 인데 여기엔 상태 조건이 없다. 예측은 전수 스캔이고, 실제
     * 실행 계획은 {@code AdminStatsExplainCaptureTest} 로 확인한다.
     */
    @Query("SELECT COUNT(s) FROM Session s WHERE s.startTime >= :from AND s.startTime < :to")
    long countStartedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 상태별 세션 분포 (전체 기간).
     *
     * <p>결과에는 <b>한 건이라도 있는 상태만</b> 나온다. 0 건인 상태는 행 자체가 없으므로
     * 화면에서 빠지지 않게 호출부가 채워야 한다({@code AdminStatsService}).
     *
     * <p>반환 형태가 {@code Object[]} 인 이유 — {@code (Status, Long)} 두 칸짜리 결과를 받을
     * 전용 타입을 만들 만큼 쓰이는 곳이 많지 않다. 호출부에서 즉시 맵으로 접는다.
     */
    @Query("SELECT s.status, COUNT(s) FROM Session s GROUP BY s.status")
    List<Object[]> countGroupedByStatus();

    /**
     * 기간 내 <b>완료된</b> 세션의 평균 싱크로율.
     *
     * <p>완료 세션만 세는 이유 — 진행 중이거나 실패한 세션의 {@code avgSyncRate} 는 아직
     * 확정값이 아니다. 해당 세션이 없으면 {@code null} 이 나온다(0.0 이 아니다) — "0%" 와
     * "잰 적 없음"은 다르므로 호출부까지 null 로 올린다.
     */
    @Query("SELECT AVG(s.avgSyncRate) FROM Session s "
            + "WHERE s.status = com.shadowfit.model.exercise.Status.COMPLETED "
            + "AND s.startTime >= :from AND s.startTime < :to")
    Double averageSyncRateOfCompletedBetween(@Param("from") LocalDateTime from,
                                             @Param("to") LocalDateTime to);

    /**
     * 기간 내 세션을 <b>시작한</b> 서로 다른 회원 수 = 활성 회원.
     *
     * <p>⚠️ 다섯 위젯 중 가장 비싼 후보다. {@code COUNT(DISTINCT ...)} 는 전수 스캔에 더해
     * 중복 제거를 해야 한다. 이 예측도 {@code AdminStatsExplainCaptureTest} 에서 확인한다.
     */
    @Query("SELECT COUNT(DISTINCT s.member.id) FROM Session s "
            + "WHERE s.startTime >= :from AND s.startTime < :to")
    long countDistinctActiveMembersBetween(@Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to);
}
