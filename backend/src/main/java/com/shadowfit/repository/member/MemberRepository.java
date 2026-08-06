package com.shadowfit.repository.member;

import com.shadowfit.model.member.Member;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface MemberRepository extends JpaRepository<Member,Long> {
    Optional<Member> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);

    // 세션 생성 시 같은 회원에 대한 동시 요청을 직렬화 — existsByMemberIdAndStatus 체크와
    // save() 사이의 TOCTOU 레이스(둘 다 커밋 전이라 서로의 상태를 못 봄)를 막기 위함
    // (2026-07-16, SessionService.createSession).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Member m WHERE m.id = :id")
    Optional<Member> findByIdForUpdate(@Param("id") Long id);

    /**
     * 기간 내 신규 가입자 수 (관리자 대시보드, {@code admin-page-scope.md} §3-D).
     *
     * <p>다섯 위젯 중 <b>인덱스를 탈 수 있는 유일한 후보</b>다 —
     * {@code idx_users_created_at (created_at)} 이 단일 컬럼이고 조건이 그 컬럼의 범위라
     * 인덱스만 읽고 셀 수 있을 것으로 예측한다. 세션 쪽 집계들이 전수 스캔인 것과 대비된다.
     * 확인은 {@code AdminStatsExplainCaptureTest}.
     */
    @Query("SELECT COUNT(m) FROM Member m WHERE m.createdAt >= :from AND m.createdAt < :to")
    long countJoinedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
