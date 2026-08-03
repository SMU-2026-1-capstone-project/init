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

    private Boolean isCorrect; // 자세 정답 여부

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