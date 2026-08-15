package com.shadowfit.service.Exercise;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.grpc.FeedbackBatchRequest;
import com.shadowfit.grpc.FeedbackEvent;
import com.shadowfit.model.exercise.FeedbackType;
import com.shadowfit.repository.exercise.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackLogService {
    private final SessionRepository sessionRepository;
    private final JdbcTemplate jdbcTemplate;

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final String INSERT_IGNORE_SQL =
            "INSERT IGNORE INTO session_feedback_logs " +
            "(session_id, feedback_type, sync_rate_at_trigger, occurred_at, created_at) " +
            "VALUES (?, ?, ?, ?, ?)";

    /**
     * AI BT-SET retry 멱등성 보장 (BE-13-G).
     * uniqueKey (session_id, occurred_at, feedback_type) 충돌 시 MySQL INSERT IGNORE 가 흡수.
     *
     * proto 직접 수신 (D-2). REST endpoint 폐기 후 gRPC ReportFeedbackBatch 단일 채널.
     *
     * @return INSERT 된 row 수 (중복 흡수된 것은 카운트 제외)
     */
    @Transactional
    public int saveBatch(FeedbackBatchRequest request) {
        long sessionId = request.getSessionId();
        if (!sessionRepository.existsById(sessionId)) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }

        List<FeedbackEvent> events = request.getEventsList();
        if (events.isEmpty()) {
            log.info("세션 {} 피드백 batch (set_no={}, is_final={}): 빈 events — 스킵",
                    sessionId, request.getSetNo(), request.getIsFinal());
            return 0;
        }

        LocalDateTime now = LocalDateTime.now(SEOUL);

        int[] results = jdbcTemplate.batchUpdate(INSERT_IGNORE_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                FeedbackEvent event = events.get(i);

                // proto string → FeedbackType enum. invalid 시 명시적 BusinessException.
                FeedbackType type;
                try {
                    type = FeedbackType.valueOf(event.getFeedbackType());
                } catch (IllegalArgumentException e) {
                    throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
                }

                ps.setLong(1, sessionId);
                ps.setString(2, type.name());
                ps.setDouble(3, event.getSyncRateAtTrigger());

                // proto Timestamp → java.sql.Timestamp (Asia/Seoul 로컬)
                long millis = com.google.protobuf.util.Timestamps.toMillis(event.getOccurredAt());
                LocalDateTime occurredAt = Instant.ofEpochMilli(millis).atZone(SEOUL).toLocalDateTime();
                ps.setTimestamp(4, Timestamp.valueOf(occurredAt));
                ps.setTimestamp(5, Timestamp.valueOf(now));
            }

            @Override
            public int getBatchSize() {
                return events.size();
            }
        });

        // 🔴 이 계산은 운영 설정에서 성립하지 않는다 (이슈 #219, 2026-08-16 실측).
        //
        // 운영 URL 에 rewriteBatchedStatements=true 가 있어(application.yml:17) 드라이버가 batch 를
        // multi-row SQL 로 재작성하고 행별 결과를 SUCCESS_NO_INFO(-2) 로 답한다. r > 0 은 -2 에
        // 대해 언제나 거짓이므로 inserted 는 항상 0, skipped 는 항상 전체가 된다:
        //
        //   rewrite=false + INSERT IGNORE : [0, 0, 1]   → inserted=1 (맞음)
        //   rewrite=true  + INSERT IGNORE : [-2, -2, -2] → inserted=0 (틀림, 실제로는 3행 들어감)
        //
        // 그리고 이 값은 로그로만 가지 않는다 — ExerciseGrpcService:126 이 gRPC 응답 savedCount
        // 로 싣는다. 즉 이 경로가 켜지면 AI 는 매번 "0건 저장됨" 을 받는다.
        //
        // 아직 사고가 아닌 이유는 이 경로의 호출이 0건이기 때문이다(#193 — AI 쪽에
        // ReportFeedbackBatch 호출자가 없다). 고칠 시점도 거기다. 확정된 방향은 배치 전후
        // COUNT(*) 차이로 세는 것(#193 착수 전 결정 ③). 반환값으로 세는 길은 없다 —
        // ON DUPLICATE KEY UPDATE 로 바꿔도 -2 는 그대로이고, rewrite=false 에서도 그 문법은
        // 중복에 1 을 돌려준다(Connector/J 가 affected 가 아니라 found rows 를 보고).
        // 재현: BatchUpdateReturnValueProbe.
        int inserted = 0;
        for (int r : results) if (r > 0) inserted++;
        int skipped = events.size() - inserted;

        log.info("세션 {} 피드백 batch (set_no={}, is_final={}): inserted={}, skipped={}",
                sessionId, request.getSetNo(), request.getIsFinal(), inserted, skipped);
        return inserted;
    }
}
