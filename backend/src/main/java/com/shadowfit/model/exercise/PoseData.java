package com.shadowfit.model.exercise;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "pose_data")
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
     * DB가 채우는 적재 시각({@code DEFAULT CURRENT_TIMESTAMP}). 월별 RANGE 파티션의 키이자,
     * "이 세션이 실제로 살아있는가"의 판정 근거다(MemberService.deleteAccount — 유입이 끊기면
     * 죽은 세션으로 본다, docs/decisions/withdrawal-with-active-session.md §3-2).
     *
     * <p>{@code insertable=false, updatable=false} — 실제 쓰기는 {@code PoseDataService} 의
     * JdbcTemplate 배치가 담당하고 값은 DB DEFAULT 가 채운다. JPA 가 쓰지 못하게 막아 두 경로가
     * 어긋나지 않게 한다. 이 매핑은 <b>읽기 전용</b>이다.
     */
    @Column(name = "created_at", insertable = false, updatable = false)
    private java.time.LocalDateTime createdAt;
}