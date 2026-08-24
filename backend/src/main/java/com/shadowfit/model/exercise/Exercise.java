package com.shadowfit.model.exercise;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "exercises")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString // BE-04 — category 는 연관관계라 exclude(LAZY 를 트랜잭션 밖에서 찍으면 예외)
public class Exercise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    // BE-04 — 카테고리가 고정 enum 에서 관리 가능한 테이블로 승격됐다(V11 마이그레이션).
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

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
     *
     * <p><b>캐시 주의</b>: 이 엔티티는 {@code ExercisesRepository.findByIdCached} 를 통해 Caffeine에
     * 캐시된다(expireAfterWrite=1h). 값을 SQL로 직접 바꾸면 최대 1시간 동안 반영되지 않으므로 즉시
     * 반영하려면 애플리케이션 재시작 또는 캐시 비우기가 필요하다. 향후 이 플래그를 바꾸는 관리자 API를
     * 만든다면 {@code AdminExerciseService.updateThresholds} 처럼
     * {@code @CacheEvict(cacheNames = "exercises", key = "#exerciseId")} 를 함께 걸어야 한다.
     */
    @Builder.Default
    @Column(name = "analysis_supported", nullable = false)
    private Boolean analysisSupported = false;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ─── 전이 ────────────────────────────────────────────────────────────────
    // 이슈 #174 — setter 를 전면 개방하는 대신 "무엇을 바꾸는가"에 이름을 준다.

    /** 등록 시 예상 시간만 조건부로 덮어쓴다 — null 이면 {@code @Builder.Default}(15분)가 그대로 남는다. */
    public void applyExpectedDuration(Integer minutes) {
        if (minutes != null) this.expectedDurationMinutes = minutes;
    }

    /** 관리자 수정(PATCH) — 보낸 필드만 갱신한다. null 검증(공백 등)은 호출자(서비스) 몫이다. */
    public void applyUpdate(String name, Category category, String description,
                             String preferredUrl, String targetJoints, Integer expectedDurationMinutes) {
        if (name != null) this.name = name;
        if (category != null) this.category = category;
        if (description != null) this.description = description;
        if (preferredUrl != null) this.preferredUrl = preferredUrl;
        if (targetJoints != null) this.targetJoints = targetJoints;
        if (expectedDurationMinutes != null) this.expectedDurationMinutes = expectedDurationMinutes;
    }

    /** 싱크로율 임계값 4종을 한 번에 바꾼다. beginner&lt;advanced 검증은 호출자(DTO)가 이미 했다. */
    public void updateThresholds(BigDecimal beginner, BigDecimal advanced, BigDecimal diet, BigDecimal rehab) {
        this.syncThresholdBeginner = beginner;
        this.syncThresholdAdvanced = advanced;
        this.syncThresholdDiet = diet;
        this.syncThresholdRehab = rehab;
    }

    /**
     * 분석 지원 여부를 바꾼다.
     *
     * @return 바뀌기 <b>전</b> 값 — 호출자가 "꺼져 있다가 켜졌는지"(WARN 로그 분기)와
     *         "이전 -> 이후"(INFO 로그) 를 둘 다 구성해야 해서, 판정을 미리 접지 않고 원값을 준다.
     */
    public boolean changeAnalysisSupport(boolean supported) {
        boolean before = Boolean.TRUE.equals(this.analysisSupported);
        this.analysisSupported = supported;
        return before;
    }
}