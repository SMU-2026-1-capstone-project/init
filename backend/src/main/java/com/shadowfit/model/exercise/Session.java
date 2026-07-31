package com.shadowfit.model.exercise;

import com.shadowfit.model.member.Member;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import jakarta.persistence.Version;
import lombok.AccessLevel;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "exercise_sessions")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"member", "exercise"}) // 무한 참조 방지
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 연관관계 설정 (N:1)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE) // 실 schema.sql의 ON DELETE CASCADE와 일치 — 회원 탈퇴 시 함께 정리
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exercise_id", nullable = false)
    private Exercise exercise;

    @Column(length = 500)
    private String referenceSource;

    @Column(nullable = false)
    private LocalDateTime startTime;

    private LocalDateTime endTime;

    @Builder.Default
    private Integer totalReps = 0;

    @Column(precision = 5, scale = 2)
    private BigDecimal avgSyncRate;

    @Column(precision = 5, scale = 2)
    private BigDecimal maxSyncRate;

    @Column(precision = 5, scale = 2)
    private BigDecimal minSyncRate;

    @Column(precision = 7, scale = 2)
    private BigDecimal caloriesBurned;

    @Builder.Default
    private Integer difficultyLevel = 1;

    @Enumerated(EnumType.STRING) // 숫자가 아닌 문자열 이름으로 저장
    @Builder.Default
    private Status status = Status.IN_PROGRESS;

    // 낙관적 락: FastAPI 완료 콜백과 스케줄러 타임아웃이 동시에 같은 세션을 갱신할 때 충돌 감지용
    @Version
    @Column(nullable = false)
    @Builder.Default
    @Setter(AccessLevel.NONE)  // Hibernate가 관리하는 필드 — 외부에서 setVersion() 호출 차단
    private Long version = 0L;

    @CreationTimestamp // INSERT 시 현재 시간 자동 입력
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 이 세션이 타임아웃으로 걷히는 시각 = 시작시간 + 예상 운동시간 + 버퍼.
     *
     * <p>{@code SessionTimeoutScheduler}(걷어가는 쪽)와 재부착 허용 판정(이어붙일 수 있는지 보는 쪽)이
     * <b>같은 식</b>을 써야 한다. 버퍼 값만 공유하고 식을 각자 쓰면 예상 운동시간이 긴 종목에서 두
     * 기준이 어긋나, "재부착은 성공했는데 곧 스케줄러가 FAILED 로 바꾸는" 창이 생긴다.
     * (이슈 #59 2단계, 2026-07-31 확정)
     *
     * <p>{@code exercise} 는 lazy 라 호출부가 JOIN FETCH 로 가져왔거나 트랜잭션 안이어야 한다 —
     * open-in-view: false.
     */
    public LocalDateTime timeoutThreshold(int bufferMinutes) {
        return startTime
                .plusMinutes(exercise.getExpectedDurationMinutes())
                .plusMinutes(bufferMinutes);
    }

    /** {@code now} 기준으로 이미 타임아웃 기준을 지났는지. 스케줄러가 아직 안 돌았어도 true 일 수 있다. */
    public boolean isTimedOutAt(LocalDateTime now, int bufferMinutes) {
        return now.isAfter(timeoutThreshold(bufferMinutes));
    }
}
