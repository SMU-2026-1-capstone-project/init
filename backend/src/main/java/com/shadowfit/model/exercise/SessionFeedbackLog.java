package com.shadowfit.model.exercise;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 세션 진행 중 device TTS로 발화된 피드백 이벤트 로그.
 * 운동 종료 시 FastAPI가 배치로 일괄 전송 (실시간 호출 X).
 */
@Entity
@Table(name = "session_feedback_logs",
       indexes = @Index(name = "idx_session_feedback", columnList = "session_id, occurred_at"))
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