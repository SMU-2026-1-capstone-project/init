package com.shadowfit.model.exercise;

import com.shadowfit.model.member.Member;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "exercise_sessions")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
// 무한 참조 방지(member·exercise) + 비밀값 유출 방지(sessionNonce, #187 d — 엔티티를 통째로
// 로깅하는 자리가 하나라도 생기면 로그를 읽는 사람이 그 세션의 소유자가 된다)
@ToString(exclude = {"member", "exercise", "sessionNonce"})
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

    /**
     * 세션 소유권 검증용 비밀값 (#187 안 (d)). 생성은 {@code SessionNonceGenerator} 가 한다.
     *
     * <p>세 곳으로 흐른다 — ① 세션 생성·재부착·진행중조회 REST 응답으로 <b>그 클라에게만</b>,
     * ② {@code AnalyzeRequest}/{@code ReattachRequest} 로 AI 에게, 그리고 ③ 여기 DB 에.
     * AI 가 보관값과 {@code POST /pose} 동봉값을 대조한다. {@code session_id} 는 순차 정수라
     * 추측되지만 이 값은 안 된다는 것이 방어의 전부다.
     *
     * <p>{@code null} 이면 <b>이 기능 배포 전에 시작된 세션</b>이다(V8 이 컬럼을 NULL 허용으로
     * 만든 이유). 1단계는 그런 세션을 그대로 통과시킨다 — 값을 지어내면 클라도 AI 도 모르는
     * 값이라 검증을 켜는 순간 끊긴다.
     *
     * <p>🔴 로그에 찍지 말 것. 이 클래스의 {@code @ToString} 에서 이미 제외했다 — 대조 실패를
     * 기록할 때도 값이 아니라 «불일치» 라는 사실만 남긴다.
     */
    @Column(length = 64)
    private String sessionNonce;

    // 낙관적 락: FastAPI 완료 콜백과 스케줄러 타임아웃이 동시에 같은 세션을 갱신할 때 충돌 감지용.
    // Hibernate 가 관리하는 필드라 외부에서 쓰면 안 된다 — 이전에는 @Setter(AccessLevel.NONE) 으로
    // 이 필드만 막았지만, 지금은 클래스 전체에 setter 가 없어 그 방어가 기본값이 됐다.
    @Version
    @Column(nullable = false)
    @Builder.Default
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

    /**
     * 이 세션을 완료로 확정한다 — <b>"완료란 무엇인가" 의 정의는 여기 하나뿐이어야 한다.</b>
     *
     * <p>이전에는 이 전이가 호출자 쪽 setter 네 번으로 표현됐고, 그래서 두 번째 사본이 생겼을 때
     * 아무도 못 막았다(이슈 #174·#179 — 그 사본은 한 달간 낡은 채로 남아 있었다). 전이에 이름이
     * 있으면 사본을 만들려는 사람이 최소한 이 메서드를 지나가게 된다.
     *
     * <p><b>멱등</b>: 이미 COMPLETED 면 아무것도 바꾸지 않고 {@code false} 를 돌려준다. AI 가 응답
     * 유실로 같은 결과를 재전송해도 첫 완료 시각·기록이 보존된다.
     *
     * <p><b>왜 {@code void} 가 아닌가</b> — 호출자는 "내가 실제로 전이시켰는가" 를 알아야 한다.
     * 완료 지표·일일 통계 누적·리포트 선계산이 그 판정에 달려 있어서, 재전송에도 그것들이 또
     * 돌면 통계가 두 번 더해진다.
     *
     * @return 이번 호출이 실제로 전이시켰으면 {@code true}, 이미 완료였으면 {@code false}
     */
    public boolean complete(int totalReps, SyncStats sync, BigDecimal caloriesBurned, LocalDateTime at) {
        if (this.status == Status.COMPLETED) {
            return false;
        }
        this.status = Status.COMPLETED;
        this.endTime = at;
        this.totalReps = totalReps;
        this.avgSyncRate = sync.avg();
        this.maxSyncRate = sync.max();
        this.minSyncRate = sync.min();
        this.caloriesBurned = caloriesBurned;
        return true;
    }

    /**
     * 사용자가 종료를 눌렀다 — <b>종료 시각만 찍고 {@code status} 는 건드리지 않는다.</b>
     *
     * <p>상태를 안 바꾸는 것이 계약이다. {@code COMPLETED} 로의 전이는 AI 완료 콜백 몫이라, 여기서
     * 같이 바꾸면 "사용자는 끝냈지만 분석은 진행 중" 인 구간이 표현 불가능해진다. 타임아웃
     * 스케줄러가 그 구간을 safety net 으로 걷어갈 수 있는 것도 status 가 IN_PROGRESS 로 남기
     * 때문이다. 메서드 이름에 status 가 안 들어가는 이유이기도 하다.
     *
     * @return 이번 호출이 실제로 종료 시각을 남겼으면 {@code true}, 이미 종료된 세션이면 {@code false}
     */
    public boolean markEnded(LocalDateTime at) {
        if (this.endTime != null) {
            return false;
        }
        this.endTime = at;
        return true;
    }

    /**
     * 타임아웃·전송 실패로 세션을 걷어낸다. <b>{@code IN_PROGRESS} 일 때만 성립한다</b> — 이미
     * 완료된 세션을 FAILED 로 덮으면 사용자가 실제로 운동한 기록이 사라진다. 이 가드가 낙관적
     * 락과 함께 "콜백 결과 우선" 정책을 이룬다.
     *
     * @return 이번 호출이 실제로 걷어냈으면 {@code true}, 이미 끝난 세션이면 {@code false}
     */
    public boolean fail(LocalDateTime at) {
        if (this.status != Status.IN_PROGRESS) {
            return false;
        }
        this.status = Status.FAILED;
        this.endTime = at;
        return true;
    }
}
