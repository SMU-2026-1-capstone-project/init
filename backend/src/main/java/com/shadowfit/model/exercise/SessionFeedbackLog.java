package com.shadowfit.model.exercise;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 세션 진행 중 AI 가 판정한 피드백 이벤트 로그 (분기 2-A 의미 재정의).
 * AI 가 BT-SET 으로 세트 경계마다 batch 송신 (분기 2.A.BT). 휴식 시간 retry 가능.
 *
 * <h3>멱등성 (BE-13-G)</h3>
 * {@code uk_session_rep (session_id, rep_number, feedback_type)} + {@code FeedbackLogService} 의
 * {@code ON DUPLICATE KEY UPDATE id = id}. AI 가 같은 배치를 다시 보내도 행이 늘지 않는다.
 *
 * <p><b>키가 시각이 아닌 이유</b>(#193 ②, V5 마이그레이션). 이 표는 원래 «device TTS 가 실제
 * 발화한 시점» 을 남기는 <b>발화 로그</b>였고(docs/05-database-design.md:195) 그 의미에서는 사건이
 * 곧 시각이라 {@code occurred_at} 키가 옳았다. 기록 대상이 «AI 판정 이벤트» 로 재정의되면서
 * 사건의 정체가 «어느 rep 에서 무엇이» 가 됐는데 키만 옛 의미에 남아 있었다. 시각 키는 재전송이
 * 시각을 다시 찍으면 없는 사건을 만들고, {@code DATETIME}(초 단위)이라 1초 안의 두 사건을 지웠다.
 *
 * <p><b>{@code INSERT IGNORE} 를 쓰지 않는 이유</b>(#219). {@code IGNORE} 는 «중복을 무시» 가
 * 아니라 «무시 가능한 에러를 전부 경고로 낮춘다» 이고, MySQL 8.0 실측에서 이렇게 나왔다:
 *
 * <pre>
 *   FK 위반       → 에러 없음, 행이 조용히 사라진다
 *   NOT NULL 위반 → 에러 없음, 빈 값('')이 저장된다
 *   중복          → 에러 없음  (이것만이 의도한 동작)
 * </pre>
 *
 * 이 표에서 걸리는 것은 FK 다 — {@code session_id} 는 회원 탈퇴 시 CASCADE 로 사라질 수 있어,
 * 세션 확인과 INSERT 사이에 탈퇴가 끼면 피드백이 통째로 없어진다. {@code IGNORE} 는 그걸 무음으로
 * 만들고 집계는 «중복 흡수» 로 센다. #87(pose_data 고아 행)과 같은 창의 반대편이다 — 저긴 FK 가
 * 없어 행이 남고, 여긴 FK 가 있어 행이 사라진다. 지금은 그 위반이 예외로 올라와
 * {@code SESSION_NOT_FOUND} 가 된다.
 *
 * <p>📌 이 경로는 아직 실행된 적이 없다 — AI 쪽에 {@code ReportFeedbackBatch} 호출자가 없다(#193).
 * 위 두 수정을 «켜기 전» 에 한 이유가 그것이다. 표가 비어 있을 때가 키를 바꾸기 가장 싼 시점이고,
 * 켠 뒤에 고치면 거짓말하는 계수기로 운영을 시작하게 된다.
 */
@Entity
@Table(name = "session_feedback_logs",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_session_rep",
               columnNames = {"session_id", "rep_number", "feedback_type"}))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessionFeedbackLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE) // 실 schema.sql의 ON DELETE CASCADE와 일치 — 세션 삭제 시 함께 정리
    private Session session;

    @Enumerated(EnumType.STRING)
    @Column(name = "feedback_type", nullable = false, length = 30)
    private FeedbackType feedbackType;

    /** 트리거 순간의 싱크로율 (0.0 ~ 100.0). FastAPI가 측정한 값. */
    @Column(name = "sync_rate_at_trigger", precision = 5, scale = 2)
    private BigDecimal syncRateAtTrigger;

    /**
     * 이 사건이 속한 rep 번호 (1-based). 멱등키의 두 번째 컬럼이다 (#193 ② · V5 마이그레이션).
     *
     * <p>🔴 <b>0 이 들어오면 안 된다.</b> proto3 스칼라는 «미설정» 과 0 을 구분하지 못해, 보내는
     * 쪽이 이 필드를 안 채우면 0 이 도착한다. 그대로 저장하면 그 세션의 모든 이벤트가 «rep 0» 에서
     * 서로를 중복으로 지운다. {@code FeedbackLogService} 가 입구에서 거절한다.
     */
    @Column(name = "rep_number", nullable = false)
    private Integer repNumber;

    /**
     * 판정 시각. <b>표시·정렬용이고 멱등키가 아니다</b>(V5 에서 키에서 뺐다).
     *
     * <p>컬럼 타입이 {@code DATETIME}(초 단위)이라 1초 안의 두 사건은 같은 값이 된다. 키였을 때는
     * 그게 «서로 다른 사건이 하나로 뭉개진다» 였지만, 지금은 정렬이 그만큼 거칠어질 뿐이다.
     */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}