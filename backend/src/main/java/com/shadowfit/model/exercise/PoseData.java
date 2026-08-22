package com.shadowfit.model.exercise;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "pose_data",
       // 멱등 키 (#188, decisions/pose-batch-idempotency-implementation.md).
       // created_at 이 키에 들어가는 것은 설계가 아니라 **파티션 제약**이다 — MySQL 은 파티션
       // 테이블의 모든 유니크 키가 파티션 표현식의 컬럼을 포함할 것을 요구한다. 그래서 PK 도
       // (id, created_at) 이다.
       //
       // 여기 선언이 마이그레이션(V6)과 **중복**인데, 그게 의도다: 테스트는 H2 + ddl-auto 라
       // Flyway 를 안 보므로(test/resources/application.yml), 엔티티에 없으면 멱등 테스트가
       // 제약 없는 스키마 위에서 초록불을 낸다.
       uniqueConstraints = @UniqueConstraint(
               name = "uk_pose_event",
               columnNames = {"session_id", "rep_number", "timestamp_sec", "created_at"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@ToString(exclude = "session") // 무한 참조 방지
public class PoseData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private Session session;

    // 이 프레임이 속한 rep 번호 (1-based, 0=미상). 재부착 시 MAX(rep_number) 로 rep 카운트를 복원한다.
    // 실제 쓰기는 JdbcTemplate 배치(PoseDataService.savePoseDataBatch)가 담당하지만, JPA 로 이 엔티티를
    // 직접 만드는 경로(테스트 픽스처 등)가 있어 기본값이 필요하다 — 없으면 NOT NULL 위반이 난다.
    // 0 은 DB 컬럼 DEFAULT 와 같은 값이고 의미도 같다("미상", MAX 를 취해도 카운트가 늘지 않음).
    @Column(name = "rep_number", nullable = false)
    @Builder.Default
    private Integer repNumber = 0;

    @Column(name = "timestamp_sec", nullable = false)
    private Double timestampSec; // 영상 내 시간대 (초)

    @Lob // 데이터가 길 수 있으므로 대용량 데이터 타입 지정
    @Column(columnDefinition = "TEXT", nullable = false)
    private String jointCoordinates; // 관절 좌표 (JSON 문자열)

    private Double syncRate; // 정답 영상과의 일치율

    /**
     * 좌우 무릎각 평균을 최근 3프레임으로 평활한 값(도, 0=미상). <b>작을수록 깊게 앉은 것이다.</b>
     *
     * <p>{@code syncRate} 는 ai-server 가 rep 단위로 채점해(DTW 가 시퀀스 한 쌍에서 숫자 하나를
     * 내놓는다) 그 rep 의 모든 프레임에 <b>복제</b>하는 값이라 rep 안에서 상수다 — 평균이 아니라
     * 애초에 프레임별 값이 존재한 적이 없다. 그래서 "이 rep 의 어느 순간이 바닥이었나"를
     * {@code syncRate} 로는 고를 수 없다. 그런데 {@code jointCoordinates} 는 프레임마다 다르므로
     * <b>어느 프레임을 남기느냐가 리포트에 그려질 자세를 결정한다.</b> 그 선택 기준이 이 값이다
     * (decisions/worst-section-rep-resolution.md §4-ㄹ).
     *
     * <p>정의를 ai-server 의 rep 경계 판정값과 일치시켰다 — 그래야 "이 rep 의 바닥"과 "가장 깊은
     * 프레임"이 서로 다른 근거를 갖지 않는다. 0 은 구버전 AI(proto3 미전송)와 컬럼 도입 이전
     * 행이며, 스쿼트 무릎각은 0 이 될 수 없으므로 유효값과 구분된다.
     *
     * <p>{@code isCorrect} 는 2026-08-01 삭제했다 — 읽는 곳이 없었고, {@code syncRate} 에서
     * 파생된 값인데 임계값(40)을 쓰기 시점에 굳혀 저장해 AI 의 persona 임계값(BEGINNER 60)과
     * 한 행 안에서 모순됐다. 판정은 임계값을 아는 쪽이 한 번만 한다.
     */
    @Column(name = "smoothed_knee_angle", nullable = false)
    @Builder.Default
    private Double smoothedKneeAngle = 0.0;

    @Column(length = 500)
    private String feedbackMessage; // AI가 주는 실시간 피드백

    /**
     * <b>세션 시작 시각</b>({@code exercise_sessions.start_time}). 한 세션의 모든 행이 같은 값을
     * 갖는다. 월별 RANGE 파티션의 키이자, "이 세션이 실제로 살아있는가"의 판정 근거다
     * (MemberService.deleteAccount — 유입이 끊기면 죽은 세션으로 본다,
     * docs/decisions/withdrawal-with-active-session.md §3-2).
     *
     * <p><b>2026-08-17 이전에는 적재 시각이었다</b>({@code DEFAULT CURRENT_TIMESTAMP}). 바꾼 이유는
     * 멱등이다 — 재전송은 나중에 도착하므로 적재 시각이면 값이 매번 달라지고, 그러면 위
     * {@code uk_pose_event} 가 통째로 무력해진다(#188,
     * docs/decisions/pose-batch-idempotency-implementation.md 분기 A).
     *
     * <p>세션 시작을 앵커로 고른 것은 <b>파티션을 세션 경계와 정렬</b>하기 위해서다. 프레임별
     * 시각을 쓰면 자정을 걸친 세션이 두 파티션으로 쪼개지는데, 사용자가 보는 단위는 세션이다
     * (리포트 1개 = 세션 1개). 프레임이 세션 안 어디인가는 {@code timestampSec} 이 답하므로 이
     * 컬럼이 그 일을 겸할 이유가 없다. 대가는 자정을 걸친 세션이 <b>시작한 날</b> 칸에 통째로
     * 들어가는 것이다.
     *
     * <p>{@code insertable=false, updatable=false} — 실제 쓰기는 {@code PoseDataService} 의
     * JdbcTemplate 배치가 담당한다. JPA 가 쓰지 못하게 막아 두 경로가 어긋나지 않게 한다.
     * <b>값을 갱신해서도 안 된다</b>: 파티션 키라 UPDATE 는 행의 물리적 이동이고, 멱등 키의
     * 구성요소라 사후 변경은 재전송 판정을 깨뜨린다.
     */
    @Column(name = "created_at", insertable = false, updatable = false)
    private java.time.LocalDateTime createdAt;
}