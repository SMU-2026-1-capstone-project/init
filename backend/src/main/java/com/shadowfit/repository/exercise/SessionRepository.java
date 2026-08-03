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
}
