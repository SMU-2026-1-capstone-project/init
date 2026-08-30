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

    // 서킷브레이커 OPEN 자동 재부착(REATTACH_ANALYSIS) 전용 — 위 findSessionWithExerciseByIdAndMemberId
    // 와 달리 소유권(memberId) 조건이 없다. 호출 경로가 사용자 요청이 아니라 시스템(서킷브레이커
    // 이벤트 → 아웃박스 발행기)이라 "요청자"가 없다 — 애초에 대조할 currentMemberId 가 없다.
    // IDOR 우려가 없는 이유: 이 경로로 얻은 sessionId는 findIdsByStatus(IN_PROGRESS)로 이미 서버가
    // 직접 뽑은 값이지, 클라이언트가 실어 보낸 값이 아니다.
    @Query("SELECT s FROM Session s JOIN FETCH s.exercise WHERE s.id = :sessionId")
    Optional<Session> findSessionWithExerciseById(@Param("sessionId") Long sessionId);

    // 서킷브레이커 OPEN 시 "이 워커가 물고 있던 세션"을 찾는 선행 조회 — 결과를 Math.floorMod로
    // 걸러 워커별로 좁힌다(ExerciseAnalysisService.enqueueReattachForWorker). IN_PROGRESS 세션
    // 수는 동시 운동 인원 규모라 작다(ai-sticky-routing.md §1-1: DAU 1,000 가정에서 최대 116) —
    // findTimeoutCandidatesByStatus 와 같은 전제로 전체 스캔을 감수한다.
    List<Long> findIdsByStatus(Status status);

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
    //
    // status IN :statuses 는 이 쿼리의 결과를 좁히려는 것이 아니다 — 호출부가 Status.values()
    // 전부를 넘겨 조건상 항상 참이다(#541). idx_session_member_status_start(member_id, status,
    // start_time)가 status 등치 없이는 start_time을 seek 못 해 회원 전체 이력(EXPLAIN 실측
    // 1,040행)을 읽은 뒤 걸렀다 — status를 명시하면 MySQL이 상태값별 range scan 4개로 쪼개
    // start_time을 seek해 실제 해당 행(277)만 읽는다(loadtest/results/report-query-explain-r14-2026-08-24).
    @Query("SELECT DISTINCT CAST(s.startTime AS date) FROM Session s " +
           "WHERE s.member.id = :memberId AND s.status IN :statuses AND s.startTime BETWEEN :start AND :end")
    List<Date> findDistinctActiveDates(@Param("memberId") Long memberId,
                                       @Param("statuses") List<Status> statuses,
                                       @Param("start") LocalDateTime start,
                                       @Param("end") LocalDateTime end);

    /**
     * {@code SessionTimeoutScheduler} 의 타임아웃 판정에 필요한 컬럼만 싣는 프로젝션 (#207).
     *
     * <p>예전엔 {@code findByStatus} 가 {@code IN_PROGRESS} 세션 전부를 {@code JOIN FETCH exercise}
     * 로 엔티티째 물었다 — 적재량이 「타임아웃된 세션 수」가 아니라 「진행 중 세션 전체」에 비례해
     * 세션이 안 끝날수록(=장애 시) 스윕이 무거워지는 방향이었다. 스윕이 물린 엔티티는 판정과 로그
     * 한 줄에만 쓰이고 실제 FAILED 전환은 {@code SessionService.markAsFailedIfStillInProgress} 가
     * id 로 다시 읽어서 하므로(자기 트랜잭션), 엔티티 그래프를 통째로 들고 있을 이유가 없었다.
     */
    interface TimeoutCandidate {
        Long getId();
        LocalDateTime getStartTime();
        LocalDateTime getLastActiveAt();
        Long getMemberId();
        String getExerciseName();
        Integer getExpectedDurationMinutes();
    }

    // member·exercise 는 스칼라 컬럼만 select 하므로(id·name·expectedDurationMinutes) 그 엔티티들이
    // 영속성 컨텍스트에 안 올라간다 — Session.isTimedOutAt(정적 버전)에 필요한 값만 실은 것.
    @Query("SELECT s.id AS id, s.startTime AS startTime, s.lastActiveAt AS lastActiveAt, "
         + "s.member.id AS memberId, s.exercise.name AS exerciseName, "
         + "s.exercise.expectedDurationMinutes AS expectedDurationMinutes "
         + "FROM Session s WHERE s.status = :status")
    List<TimeoutCandidate> findTimeoutCandidatesByStatus(@Param("status") Status status);

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
    // 인메모리 처리 문제는 해당 없음).
    //
    // 🔀 2026-08-07 — idx_session_member_status_start (member_id, status, start_time) 을 탄다.
    // 그 전까지 이 쿼리가 (member_id, status) 를 탄다고 적어뒀는데 **틀렸다.** 등치 둘에
    // ORDER BY start_time LIMIT 1 인데 (member_id, status) 는 정렬을 못 받쳐, 옵티마이저가
    // 정렬 비용을 보고 idx_session_member_exercise_status_start 로 도망가 회원의 전 세션을
    // 읽고 정렬했다 — 회원당 세션 2000건이면 2001행이다. 통합 인덱스는 앞 둘이 등치로 고정된
    // 뒤 남은 구간이 이미 start_time 순이라 LIMIT 1 이 **진짜 1행**이 된다. 팬아웃과 무관한
    // 상수다 (이슈 #110, docs/decisions/session-index-composition.md §4).
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
    // idx_session_member_status_start (member_id, status, start_time) 의 앞 두 컬럼을 탄다
    // (2026-08-07 통합 전에는 idx_session_member_status 였다 — 이 쿼리에는 등치 둘뿐이라
    // 통합으로 달라지는 것이 없다. 세 번째 컬럼이 붙어도 seek 범위는 같다).
    //
    // 컬럼 순서가 뒤집힌 (member_id, start_time, status) 였다면 status 로 좁히지 못해, member_id
    // 로 찾은 뒤 그 회원의 세션을 훑으며 status 를 필터로 확인해야 한다 — 읽는 행이 찾는 status 의
    // 선택도만큼 늘어난다. 이 쿼리가 통합 인덱스의 컬럼 순서를 정한 근거 중 하나다
    // (session-index-composition.md §4-3).
    //
    // ⚠️ 그 문서의 실측치(팬아웃 2000 에서 1000 → 2001행, 약 2배)는 **rig 의 status 분포가
    //    COMPLETED 50% 일 때의 값**이다. 배수는 선택도에 따라 달라지므로 일반 수치로 쓰지 말 것.
    @Query("SELECT s.id FROM Session s WHERE s.member.id = :memberId AND s.status = :status")
    List<Long> findIdsByMemberIdAndStatus(@Param("memberId") Long memberId, @Param("status") Status status);

    /**
     * 이 세션들 중 {@code since} 이후 <b>활동이 있었던</b> 것의 수 — "실제로 운동 중인가"의 판정
     * (MemberService.deleteAccount, docs/decisions/withdrawal-with-active-session.md §3-2).
     *
     * <p><b>왜 세션 상태가 아니라 이걸 보나.</b> {@code IN_PROGRESS} 는 앱이 죽으면 갱신되지 않아
     * 최대 "예상 운동시간 + 버퍼"(스쿼트 ~45분) 동안 남는다. 그 상태값으로 탈퇴를 막으면 운동 중이
     * 아닌 사용자가 이유도 모른 채 막힌다. 반면 살아있는 세션은 rep 단위로 3~4초마다 프레임을
     * 보내고 배치마다 {@code last_active_at} 이 갱신되므로(PoseDataService), <b>갱신이 끊긴 것은
     * 세션이 죽었다는 직접 증거</b>다.
     *
     * <p>🔴 <b>{@code pose_data.created_at} 으로 재면 안 된다</b> (#317). 그 컬럼은 "프레임이 들어온
     * 시각"이 아니라 <b>세션 시작 시각</b>이다 — #188 멱등 때문에 한 세션의 모든 행이 같은 앵커를
     * 공유한다({@code sessionAnchor = session.getStartTime()}). 그래서 그걸로 재면 질문이 "최근에
     * 프레임이 들어왔나"에서 <b>"세션이 최근에 시작됐나"</b>로 바뀌고, 4분째 운동 중인 사용자가
     * 가드를 그냥 통과한다.
     *
     * <p>{@code last_active_at} 이 null 인 세션(첫 배치 전에 앱이 죽은 경우)은 비교에서 빠져 안
     * 세어진다 — "유입 없음"과 같은 뜻이라 의도한 대로다.
     */
    @Query("SELECT COUNT(s) FROM Session s WHERE s.id IN :sessionIds AND s.lastActiveAt > :since")
    long countActiveSince(@Param("sessionIds") List<Long> sessionIds,
                          @Param("since") LocalDateTime since);

    /**
     * 이 종목으로 만들어진 세션이 <b>한 건이라도</b> 있는가 — 관리자 종목 삭제 가드.
     *
     * <p><b>{@code count} 가 아니라 {@code exists} 인 이유.</b> 판정에 필요한 건 0 이냐 아니냐뿐인데
     * {@code COUNT} 는 조건에 걸린 행을 전부 세야 답이 나온다. 스쿼트({@code id=1})는 사실상 모든
     * 세션이 달려 있어서, 세면 그 종목의 전 이력을 훑고 결과는 "0 이 아니다" 한 줄로 버려진다.
     * {@code exists} 는 첫 행에서 멈춘다.
     *
     * <p>그 대가로 <b>거부 메시지에 건수를 실을 수 없다</b>("세션 3건이 참조 중"). 관리자가 알아야
     * 하는 건 "지울 수 있나"이고 몇 건인지는 세션 목록(B)에서 종목으로 걸러 보면 되므로, 여기서
     * 전수 스캔을 살 이유가 없다고 봤다.
     *
     * <p>인덱스는 {@code exercise_sessions.exercise_id} 의 FK 인덱스를 탄다
     * ({@code V1__baseline.sql:110} — MySQL 이 FK 에 자동 생성).
     */
    boolean existsByExerciseId(Long exerciseId);

    // ─── 관리자 대시보드 집계 (admin-page-scope.md §3-D) ───────────────────────────
    //
    // 목록(A·B)과 성격이 다르다. 목록은 LIMIT 20 이라 인덱스만 타면 20건만 만지고 끝나지만,
    // 집계는 조건에 걸린 행을 전부 만져야 답이 나온다 — 인덱스로 범위를 줄여도 줄어든 범위
    // 전체를 읽는다. 그래서 QueryDSL 로 감싸지 않는다. 조건이 고정이라 동적 조립이 필요 없고,
    // 여기서 드는 비용은 쿼리 빌더가 아니라 집계 자체다 (§3-D "전부 조건 고정 집계").

    /**
     * 기간 내 시작된 세션 수.
     *
     * <p>✅ <b>실측(2026-08-06): 예측이 틀렸다.</b> "선두가 {@code status} 인데 상태 조건이
     * 없으니 {@code idx_session_status_starttime} 을 못 탄다"고 봤는데, MySQL 8.0 의
     * <b>skip scan</b> 이 걸려 {@code range} 로 돈다 — 선두 컬럼의 값이 넷뿐이라 옵티마이저가
     * 상태값마다 {@code start_time} 범위 탐색을 반복한다. 100만 → 11만 ({@code admin-page-scope.md} §4-5).
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
     * <p>✅ <b>실측(2026-08-06): 비싼 것은 맞았고, 고르는 인덱스가 뜻밖이었다.</b> 기간 조건이
     * 있는데도 {@code idx_session_status_starttime} 이 아니라
     * {@code idx_session_member_starttime (member_id, start_time)} 을 탔다 — {@code member_id}
     * 선두를 정렬된 순서로 읽으면 <b>중복 제거가 공짜</b>가 되기 때문이다. 옵티마이저가 범위를
     * 좁히는 것보다 그쪽을 택했다. 100만 행 인덱스 전체 스캔, <b>0.63초</b> — 상태별 분포와
     * 둘이 대시보드 전체 비용의 대부분이었다 ({@code admin-page-scope.md} §4-5).
     *
     * <p>🔀 <b>2026-08-07 해소</b> — 위 인덱스는 이제 없다(#110 통합으로 삭제). 대신 이 쿼리
     * 전용으로 {@code idx_session_starttime_member (start_time, member_id)} 를 얹었다:
     * {@code start_time} 이 선두라 기간으로 <b>seek</b> 하고, {@code member_id} 가 인덱스에
     * 실려 있어 <b>커버링</b>이다. 100만 행 스캔이 사라지고 <b>355ms → 13.6ms (26배)</b>
     * ({@code admin-page-scope.md} §4-5-1).
     *
     * <p>⚠️ <b>중복 제거는 공짜가 아니다.</b> {@code (start_time, member_id)} 에서
     * {@code member_id} 는 <b>같은 {@code start_time} 안에서만</b> 정렬돼 있다. 기간 범위
     * 전체를 놓고 보면 전역 정렬이 아니므로 {@code DISTINCT} 는 여전히 별도 처리가 필요하다.
     * 이득의 출처는 <b>seek 으로 범위를 좁힌 것 + 커버링</b>이지 중복 제거가 아니다.
     *
     * <p>즉 이 인덱스는 <b>맞바꾼 것</b>이다. 옛 {@code (member_id, start_time)} 은 선두가
     * {@code member_id} 라 정렬된 순서로 읽으면 중복 제거가 <b>진짜로</b> 공짜였지만, 기간으로
     * seek 를 못 해 100만 행을 읽고 98%를 버렸다. 공짜 중복 제거를 포기하고 seek 을 산 결과가
     * 26배다.
     *
     * <p>🔶 <b>미검증</b>: MySQL 이 남은 {@code DISTINCT} 를 임시 테이블로 처리하는지 다른
     * 방식인지는 확인하지 않았다. 확인하려면 {@code EXPLAIN ANALYZE} 의 실제 연산자를 봐야 한다.
     *
     * <p>순서를 뒤집으면 안 되는 이유가 위 실측 그 자체다 — 범위 조건이 뒤에 실리면 seek 를
     * 못 해 인덱스 전체를 읽고 98%를 버린다.
     */
    @Query("SELECT COUNT(DISTINCT s.member.id) FROM Session s "
            + "WHERE s.startTime >= :from AND s.startTime < :to")
    long countDistinctActiveMembersBetween(@Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to);

    // ─── 패턴 분석 (BE-07, pattern-analysis-implementation.md §3) ──────────────────

    /**
     * periodicity 집계 전용 — 요일·시간대 그룹핑에는 {@code startTime} 스칼라 하나만 필요해
     * {@code findDistinctActiveDates}와 같은 이유로 엔티티 전체를 안 문다. status 필터는 없다 —
     * "활동"의 정의를 {@code findDistinctActiveDates}(호출부가 {@code Status.values()} 전부를 넘김)와
     * 동일하게 세션 시작 여부로 본다.
     */
    @Query("SELECT s.startTime FROM Session s "
         + "WHERE s.member.id = :memberId AND s.startTime BETWEEN :start AND :end")
    List<LocalDateTime> findStartTimesByMemberAndRange(@Param("memberId") Long memberId,
                                                        @Param("start") LocalDateTime start,
                                                        @Param("end") LocalDateTime end);

    /**
     * intensity-trend(세션3) 전용 — 주 단위 평균 syncRate·총 시간 집계에 필요한 값만 뽑는다.
     * {@code avgSyncRate IS NOT NULL}로 미완료·rep 미측정 세션을 걸러 애초에 집계 모수에서 뺀다
     * (2026-08-30 사용자 확인 — totalMinutes도 같은 필터를 공유해 한 행이 "실제로 측정된 운동"만
     * 대표하게 한다). {@link com.shadowfit.model.exercise.Session#complete} 가 avgSyncRate·endTime을
     * 같은 호출에서 함께 채우므로 avgSyncRate가 non-null이면 endTime도 항상 non-null이다.
     */
    interface IntensitySample {
        LocalDateTime getStartTime();
        LocalDateTime getEndTime();
        java.math.BigDecimal getAvgSyncRate();
    }

    @Query("SELECT s.startTime AS startTime, s.endTime AS endTime, s.avgSyncRate AS avgSyncRate FROM Session s "
         + "WHERE s.member.id = :memberId AND s.avgSyncRate IS NOT NULL AND s.startTime BETWEEN :start AND :end")
    List<IntensitySample> findIntensitySamplesByMemberAndRange(@Param("memberId") Long memberId,
                                                                @Param("start") LocalDateTime start,
                                                                @Param("end") LocalDateTime end);

    // ─── 목표 진척 (BE-06, goal-domain-design.md §4·§7) ──────────────────────────

    /**
     * 목표 진척 조회 전용 — 최근 N일간(rolling window) COMPLETED 세션의 시작·종료 시각.
     * WEEKLY_SESSIONS(개수)·WEEKLY_MINUTES(분) 두 goalType을 이 한 번의 조회 결과로
     * {@code GoalService}가 같이 계산한다(list.size() / Duration 합산 — 별도 컬럼·저장 없이
     * 조회 시점 직접 계산, 2026-08-30 사용자 confirm). exercise 종목은 안 가린다 — squat-first라
     * 사실상 스쿼트뿐이지만, 목표는 "총 운동량"이라 종목 무관이 맞다(goal-domain-design.md §3).
     */
    interface CompletedSessionWindow {
        LocalDateTime getStartTime();
        LocalDateTime getEndTime();
    }

    @Query("SELECT s.startTime AS startTime, s.endTime AS endTime FROM Session s "
         + "WHERE s.member.id = :memberId AND s.status = com.shadowfit.model.exercise.Status.COMPLETED "
         + "AND s.startTime >= :since")
    List<CompletedSessionWindow> findCompletedSessionWindowsSince(@Param("memberId") Long memberId,
                                                                   @Param("since") LocalDateTime since);
}
