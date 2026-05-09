package com.shadowfit.model.exercise;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 운동별 자세 문제에 대해 사용자에게 안내할 피드백 메시지 템플릿.
 * 운영자가 운동/문제유형마다 멘트를 DB에서 직접 관리한다.
 */
@Entity
@Table(name = "exercise_feedback_templates",
       uniqueConstraints = @UniqueConstraint(columnNames = {"exercise_id", "feedback_type"}))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExerciseFeedbackTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exercise_id", nullable = false)
    private Exercise exercise;

    @Enumerated(EnumType.STRING)
    @Column(name = "feedback_type", nullable = false, length = 30)
    private FeedbackType feedbackType;

    @Column(nullable = false, length = 200)
    private String message;

    /** 동시에 여러 문제가 감지될 때 우선순위 (낮을수록 우선). */
    @Builder.Default
    @Column(nullable = false)
    private Integer priority = 100;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}