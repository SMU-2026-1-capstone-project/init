package com.shadowfit.model.goal;

import com.shadowfit.model.member.Member;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 운동 목표 (BE-06). {@code currentValue}·{@code periodStart}·{@code periodEnd}·{@code status}는
 * 컬럼으로 안 둔다 — rolling window(최근 7일) 채택(goal-domain-design.md §4 ✅, 2026-08-30
 * 사용자 confirm) + currentValue는 조회 시점 직접 계산(같은 날 재확인) 방식이라, 이 엔티티가
 * 아는 건 "무엇을 얼마나"뿐이고 "지금 얼마나 했는지"는 GoalService가 SessionRepository를
 * 그때그때 읽어서 답한다. status도 그 값과 targetValue 비교로 응답 시점에 계산된다.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
// V13__add_goals_table.sql의 uk_goals_member_type과 짝 — 테스트(H2, ddl-auto: create-drop)는
// 마이그레이션이 아니라 이 애노테이션으로 스키마를 만들기 때문에, 여기 없으면 테스트 DB만
// 제약이 빠진 채로 초록불이 뜬다(schema-migration-tracking.md §1의 그 함정).
@Table(name = "goals", uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "goal_type"}))
public class Goal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(name = "goal_type", nullable = false)
    private GoalType goalType;

    @Column(name = "target_value", nullable = false)
    private int targetValue;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void updateTargetValue(int targetValue) {
        this.targetValue = targetValue;
    }
}
