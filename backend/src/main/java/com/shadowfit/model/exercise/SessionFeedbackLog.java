package com.shadowfit.model.exercise;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 세션 진행 중 AI 가 판정한 피드백 이벤트 로그 (분기 2-A 의미 재정의).
 * AI 가 BT-SET 으로 세트 경계마다 batch 송신 (분기 2.A.BT). 휴식 시간 retry 가능.
 *
 * 멱등성 (BE-13-G):
 *   uniqueKey (session_id, occurred_at, feedback_type) 로 중복 row 방지.
 *   FeedbackLogService 가 INSERT IGNORE 로 흡수 (FeedbackLogService:33, INSERT_IGNORE_SQL).
 *
 *   🔴 이 SQL 은 중복만 삼키지 않는다 (이슈 #219). IGNORE 는 "중복을 무시" 가 아니라
 *   "무시 가능한 에러를 전부 경고로 낮춘다" 이고, MySQL 8.0 실측(#219 ①)에서 이렇게 나왔다:
 *
 *       FK 위반      → 에러 없음, ROW_COUNT()=0 · 행이 조용히 사라진다
 *       NOT NULL 위반 → 에러 없음, ROW_COUNT()=1 · 빈 값('')이 저장된다
 *       중복          → 에러 없음, ROW_COUNT()=0 · (이것만이 의도한 동작)
 *
 *   이 테이블에서 실제로 걸리는 것은 FK 쪽이다. session_id 는 users → exercise_sessions
 *   ON DELETE CASCADE 로 사라질 수 있어(회원 탈퇴), FeedbackLogService 의 세션 확인(:49)과
 *   batch INSERT(:62) 사이에 탈퇴가 끼면 AI 가 보낸 피드백이 통째로 없어지는데
 *   skipped 카운터(:94)는 그걸 "중복 흡수" 로 센다. #87(pose_data 고아 행)과 같은 창의
 *   반대편이다 — 저긴 FK 가 없어 행이 남고, 여긴 FK 가 있어 행이 사라진다.
 *
 *   수정 방향은 #219 코멘트에 있다(ON DUPLICATE KEY UPDATE id = id 로 좁히고 FK 위반은
 *   SESSION_NOT_FOUND 로 올린다). 아직 안 고쳤다.
 *
 *   ⚠️ 이 줄은 원래 "per-row try/catch 로 DataIntegrityViolationException 흡수" 라고 적혀 있었고
 *   실제 코드는 그때도 INSERT IGNORE 였다(이슈 #215 ④). 그 문구가 가리키던 방식은 이 코드베이스가
 *   실측으로 밟았다가 물러난 쪽이라(DailyLogRepository:16-20 — "Hibernate 세션이 flush 실패로
 *   손상돼 후속 쿼리가 깨짐") 근거까지 남겨둔다. 다만 그때 이 자리를 "그래서 INSERT IGNORE 가
 *   맞다" 로 정리한 것은 성급했다 — 중복만 보고 통과 판정을 냈고, 위의 나머지 둘은 안 봤다.
 */
@Entity
@Table(name = "session_feedback_logs",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_session_event",
               columnNames = {"session_id", "occurred_at", "feedback_type"}))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessionFeedbackLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE) // 실 schema.sql의 ON DELETE CASCADE와 일치 — 세션 삭제 시 함께 정리
    private Session session;

    @Enumerated(EnumType.STRING)
    @Column(name = "feedback_type", nullable = false, length = 30)
    private FeedbackType feedbackType;

    /** 트리거 순간의 싱크로율 (0.0 ~ 100.0). FastAPI가 측정한 값. */
    @Column(name = "sync_rate_at_trigger", precision = 5, scale = 2)
    private BigDecimal syncRateAtTrigger;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}