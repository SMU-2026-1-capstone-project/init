package com.shadowfit.service.Exercise;

import com.shadowfit.dto.exercises.feedback.FeedbackBatchRequestDto;
import com.shadowfit.dto.exercises.feedback.FeedbackEventDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.repository.exercise.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackLogService {
    private final SessionRepository sessionRepository;
    private final JdbcTemplate jdbcTemplate;

    private static final String INSERT_IGNORE_SQL =
            "INSERT IGNORE INTO session_feedback_logs " +
            "(session_id, feedback_type, sync_rate_at_trigger, occurred_at, created_at) " +
            "VALUES (?, ?, ?, ?, ?)";

    /**
     * AI BT-SET retry 멱등성 보장 (BE-13-G).
     * uniqueKey (session_id, occurred_at, feedback_type) 충돌 시 MySQL INSERT IGNORE 가 흡수.
     *
     * @return batchUpdate 결과 합계 (insert 된 row 수, skip 된 것은 0 으로 카운트됨)
     */
    @Transactional
    public int saveBatch(FeedbackBatchRequestDto request) {
        if (!sessionRepository.existsById(request.sessionId())) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }

        Long sessionId = request.sessionId();
        List<FeedbackEventDto> events = request.events();
        LocalDateTime now = LocalDateTime.now();

        int[] results = jdbcTemplate.batchUpdate(INSERT_IGNORE_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                FeedbackEventDto event = events.get(i);
                ps.setLong(1, sessionId);
                ps.setString(2, event.feedbackType().name());
                BigDecimal sync = event.syncRateAtTrigger();
                if (sync == null) ps.setNull(3, Types.DECIMAL);
                else ps.setBigDecimal(3, sync);
                ps.setTimestamp(4, Timestamp.valueOf(event.occurredAt()));
                ps.setTimestamp(5, Timestamp.valueOf(now));
            }

            @Override
            public int getBatchSize() {
                return events.size();
            }
        });

        int inserted = 0;
        for (int r : results) if (r > 0) inserted++;
        int skipped = events.size() - inserted;

        log.info("세션 {} 피드백 batch (set_no={}, is_final={}): inserted={}, skipped={}",
                sessionId, request.setNo(), request.isFinal(), inserted, skipped);
        return inserted;
    }
}