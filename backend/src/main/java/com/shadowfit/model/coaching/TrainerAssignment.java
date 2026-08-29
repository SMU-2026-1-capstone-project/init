package com.shadowfit.model.coaching;

import com.shadowfit.model.member.Member;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * 트레이너 1명 ↔ 담당 사용자 1명의 배정 관계 ({@code trainer-live-monitoring.md} §1 — "관계
 * 형태는 1:1"은 배정 관계 하나하나가 1:1이라는 뜻이다). 사용자 쪽에 유니크 제약을 걸어 "한
 * 사용자에게 담당 트레이너는 한 명"을 DB 레벨에서 강제한다 — 트레이너 한 명이 여러 사용자를
 * 담당하는 것(1인당 배정을 여러 번)은 허용된다. **확정(2026-08-30, 사용자 confirm)**: 물리치료사·
 * 코치가 여러 회원을 보는 현실적 모델을 반영한 방향이며, 순수 1:1(트레이너도 1명만)이 아니다.
 *
 * <p>권한 체크(어느 트레이너가 어느 세션을 볼 수 있는가)는 이 배정 관계로 결정된다 — 세션2
 * (SSE 컨트롤러 인가)에서 이 레포지토리를 조회해 소유 검증한다.
 */
@Entity
@Table(name = "trainer_assignments")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"trainer", "user"})
public class TrainerAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trainer_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Member trainer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Member user;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;
}
