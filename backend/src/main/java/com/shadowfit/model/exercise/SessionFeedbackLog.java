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
 *   FeedbackLogService 가 INSERT IGNORE 로 흡수 (FeedbackLogService:40, INSERT_IGNORE_SQL).
 *
 *   ⚠️ 이 줄은 원래 "per-row try/catch 로 DataIntegrityViolationException 흡수" 라고 적혀 있었다.
 *   실제 코드는 그때도 INSERT IGNORE 였다(이슈 #215 ④). 결과는 같지만 근거가 다르고, 하필
 *   그 문구가 가리키는 방식은 이 코드베이스가 실측으로 밟았다가 물러난 쪽이다
 *   (DailyLogRepository:16-20 — "Hibernate 세션이 flush 실패로 손상돼 후속 쿼리가 깨짐").
 *   주석을 보고 따라 하면 손해를 보는 자리라 근거까지 남긴다.
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