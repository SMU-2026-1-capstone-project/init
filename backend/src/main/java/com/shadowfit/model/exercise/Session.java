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
     * 마지막으로 활동이 관측된 시각 — rep 이 완성돼 {@code SavePoseDataBatch} 가 들어올 때 갱신된다
     * ({@code PoseDataService.savePoseDataBatch}). {@code null} 이면 아직 rep 이 하나도 없다는 뜻.
     *
     * <p><b>왜 rep 단위인가</b> — Spring 은 개별 프레임을 받지 않는다. 프레임은 클라 → {@code ai-server}
     * 로만 흐르고, AI → Spring 방향 RPC 는 {@code SavePoseDataBatch}(rep 완성 시) ·
     * {@code ReportFeedbackBatch} · {@code CompleteAnalysis} 셋뿐이다. 이것이 Spring 이 얻을 수 있는
     * 가장 촘촘한 활동 신호이고, 더 촘촘하게 하려면 하트비트 RPC 를 새로 만들어야 한다.
     *
     * <p><b>왜 JPA 가 아니라 JdbcTemplate 로 쓰는가</b> — 이 필드를 엔티티로 갱신하면 {@code @Version}
     * 이 따라 올라간다. 그 낙관적 락은 AI 완료 콜백과 타임아웃 스케줄러의 경쟁을 조율하는 장치라,
     * 운동 중 내내 version 이 바뀌면 그 경쟁이 상시화된다. 그래서 쓰기는
     * {@code PoseDataService} 의 JdbcTemplate 경로에서 이 컬럼만 직접 갱신한다.
     */
    private LocalDateTime lastActiveAt;

    /**
     * 이 세션이 타임아웃으로 걷히는 시각.
     *
     * <p><b>앵커는 마지막 활동이다</b>(docs/decisions/session-liveness-vs-elapsed-time.md, ㄷ안).
     * 이전에는 {@code start_time + 예상 운동시간 + 버퍼} 였는데, 그 식에는 활동 항이 없어 세 가지가
     * 한꺼번에 틀렸다: 그만둔 세션을 45분간 붙들고(새 운동이 409로 막힌다), 45분 넘게 운동 중인
     * 세션을 프레임이 들어오는 중에 걷어가고, 그렇게 찍힌 {@code end_time} 이 주간 통계에 운동
     * 시간으로 합산됐다. 고정된 시간창은 필연적으로 양방향으로 틀린다 — 늘리면 방치가, 줄이면
     * 조기 종료가 심해진다.
     *
     * <p><b>활동이 없으면 기존 식으로 폴백한다.</b> rep 이 아직 하나도 없는 구간(자세 잡기·준비)이
     * 있고, 거기에 짧은 유휴 임계를 적용하면 시작하자마자 걷어가게 된다. 그래서 첫 rep 전까지는
     * 종전과 완전히 같은 기준을 쓰고, 첫 rep 이후부터 유휴 판정으로 넘어간다.
     *
     * <p>{@code SessionTimeoutScheduler}(걷어가는 쪽)와 재부착 허용 판정(이어붙일 수 있는지 보는 쪽)이
     * <b>같은 식</b>을 써야 한다. 값만 공유하고 식을 각자 쓰면 두 기준이 어긋나, "재부착은 성공했는데
     * 곧 스케줄러가 FAILED 로 바꾸는" 창이 생긴다. (이슈 #59 2단계, 2026-07-31 확정)
     *
     * <p>{@code exercise} 는 lazy 라 폴백 경로에서는 호출부가 JOIN FETCH 로 가져왔거나 트랜잭션
     * 안이어야 한다 — open-in-view: false.
     *
     * @param idleMinutes   마지막 활동 이후 이만큼 지나면 걷어간다
     * @param bufferMinutes 활동이 아직 없을 때 쓰는 기존 식의 버퍼
     */
    public LocalDateTime timeoutThreshold(int idleMinutes, int bufferMinutes) {
        if (lastActiveAt != null) {
            return lastActiveAt.plusMinutes(idleMinutes);
        }
        return startTime
                .plusMinutes(exercise.getExpectedDurationMinutes())
                .plusMinutes(bufferMinutes);
    }

    /** {@code now} 기준으로 이미 타임아웃 기준을 지났는지. 스케줄러가 아직 안 돌았어도 true 일 수 있다. */
    public boolean isTimedOutAt(LocalDateTime now, int idleMinutes, int bufferMinutes) {
        return now.isAfter(timeoutThreshold(idleMinutes, bufferMinutes));
    }
}
