package com.shadowfit.model.exercise;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "exercises")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString // 연관관계가 없으므로 exclude 없이 사용 가능
public class Exercise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExerciseCategory category;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "preferred_url", length = 500)
    private String preferredUrl;

    /**
     * SQL의 JSON 타입을 매핑합니다.
     * 가장 간단하게는 String으로 처리한 뒤,
     * 필요할 때 Jackson ObjectMapper로 파싱하여 사용합니다.
     */
    @Column(columnDefinition = "json")
    private String targetJoints;

    @Builder.Default
    @Column(precision = 5, scale = 2)
    private BigDecimal syncThresholdBeginner = new BigDecimal("60.00");

    @Builder.Default
    @Column(precision = 5, scale = 2)
    private BigDecimal syncThresholdAdvanced = new BigDecimal("85.00");

    @Builder.Default
    @Column(precision = 5, scale = 2)
    private BigDecimal syncThresholdDiet = new BigDecimal("70.00");

    @Builder.Default
    @Column(precision = 5, scale = 2)
    private BigDecimal syncThresholdRehab = new BigDecimal("50.00");

    @Builder.Default
    @Column(nullable = false)
    private Integer expectedDurationMinutes = 15; // 예상 운동시간 (기본값: 15분)

    /**
     * AI 서버가 이 종목의 자세 분석을 실제로 지원하는지. 기본값 false — 종목 행이 먼저 생기고
     * 분석기(ai-server)가 나중에 붙는 순서라, 기본을 true로 두면 준비 전에 세션이 열린다.
     * 현재 true인 건 스쿼트뿐(squat-first 방침). SessionService.createSession 이 이 값을 검사한다.
     */
    @Builder.Default
    @Column(name = "analysis_supported", nullable = false)
    private Boolean analysisSupported = false;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}