package com.shadowfit.service.Exercise;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.grpc.FeedbackBatchRequest;
import com.shadowfit.grpc.FeedbackEvent;
import com.shadowfit.model.exercise.FeedbackType;
import com.shadowfit.repository.exercise.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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

    /**
     * 🔴 {@code INSERT IGNORE} 가 아니다 (이슈 #219). {@code IGNORE} 는 중복만 삼키지 않는다 —
     * MySQL 8.0 실측에서 FK 위반은 행을 조용히 버리고 NOT NULL 위반은 빈 값을 저장했다.
     * {@code ON DUPLICATE KEY UPDATE id = id} 는 <b>UNIQUE 충돌에만</b> 반응하는 no-op 이라
     * 멱등성은 같고 나머지 위반은 정상적으로 예외가 된다.
     */
    private static final String INSERT_ON_DUPLICATE_SQL =
            "INSERT INTO session_feedback_logs " +
            "(session_id, rep_number, feedback_type, sync_rate_at_trigger, occurred_at, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE id = id";

    private static final String COUNT_BY_SESSION_SQL =
            "SELECT COUNT(*) FROM session_feedback_logs WHERE session_id = ?";

    /**
     * AI BT-SET retry 멱등성 보장 (BE-13-G). {@code uk_session_rep (session_id, rep_number,
     * feedback_type)} 충돌을 {@code ON DUPLICATE KEY UPDATE} 가 흡수한다.
     *
     * <p>proto 직접 수신 (D-2). REST endpoint 폐기 후 gRPC ReportFeedbackBatch 단일 채널.
     *
     * <p><b>삽입 건수를 반환값으로 세지 않는다</b>(#219 실측, #193 결정 ③). 운영 URL 의
     * {@code rewriteBatchedStatements=true} 때문에 드라이버가 batch 를 multi-row SQL 로 재작성하고
     * 행별 결과를 {@code SUCCESS_NO_INFO(-2)} 로 답한다 — 어떤 SQL 을 써도 {@code r > 0} 집계는
     * 항상 0 이 된다. 그래서 <b>배치 전후로 행 수를 세서</b> 차이를 쓴다.
     *
     * <p>동시성: 이 메서드가 한 트랜잭션이고 InnoDB 기본 격리수준(REPEATABLE READ)이라 두 COUNT
     * 사이에 남이 커밋한 행은 스냅샷에 안 들어온다. 즉 차이값은 <b>내가 넣은 건수</b>다.
     *
     * @return 실제로 새로 저장된 row 수 (중복 흡수된 것은 제외)
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

        // rep_number 는 1-based 다. 0 을 거절하는 것은 형식 검사가 아니라 «데이터 유실 방어» 다 —
        // proto3 스칼라는 «미설정» 과 0 을 구분하지 못해, 보내는 쪽이 이 필드를 안 채우면 0 이 온다.
        // 그대로 저장하면 그 배치의 모든 이벤트가 uk_session_rep 의 «rep 0» 에서 서로를 중복으로
        // 지우고, 그 유실이 «멱등성이 동작했다» 로 보인다. 입구에서 막는다.
        for (FeedbackEvent event : events) {
            if (event.getRepNumber() <= 0) {
                log.warn("세션 {} 피드백 batch 거부 — rep_number 가 비었다(={}). 보내는 쪽이 필드를 "
                        + "안 채웠을 가능성이 크다", sessionId, event.getRepNumber());
                throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
            }
        }

        LocalDateTime now = LocalDateTime.now(SEOUL);

        // 배치 전후로 행 수를 센다. batchUpdate 의 반환값으로는 셀 수 없다 — 운영 URL 의
        // rewriteBatchedStatements=true 때문에 드라이버가 행별 결과를 SUCCESS_NO_INFO(-2) 로
        // 답한다(#219 실측, 재현: BatchUpdateReturnValueProbe). session_id 가 uk_session_rep 의
        // 선두 컬럼이라 이 COUNT 는 인덱스만 읽는다.
        int before = countBySession(sessionId);

        try {
            jdbcTemplate.batchUpdate(INSERT_ON_DUPLICATE_SQL, new BatchPreparedStatementSetter() {
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
                    ps.setInt(2, event.getRepNumber());
                    ps.setString(3, type.name());
                    ps.setDouble(4, event.getSyncRateAtTrigger());

                    // proto Timestamp → java.sql.Timestamp (Asia/Seoul 로컬)
                    long millis = com.google.protobuf.util.Timestamps.toMillis(event.getOccurredAt());
                    LocalDateTime occurredAt = Instant.ofEpochMilli(millis).atZone(SEOUL).toLocalDateTime();
                    ps.setTimestamp(5, Timestamp.valueOf(occurredAt));
                    ps.setTimestamp(6, Timestamp.valueOf(now));
                }

                @Override
                public int getBatchSize() {
                    return events.size();
                }
            });
        } catch (DataIntegrityViolationException e) {
            // 위 존재검사(:67)와 이 INSERT 사이에 세션이 사라진 경우다 — 회원 탈퇴가 users →
            // exercise_sessions 를 CASCADE 로 지운다. 배치의 모든 행이 같은 session_id 라
            // 이 배치는 통째로 무효이고, 부분 성공을 만들 여지가 없다.
            //
            // 예전에는 INSERT IGNORE 가 이걸 무음으로 만들어 «행은 사라지고 로그는 중복이라고
            // 말하는» 상태였다(#219). 사전검사와 같은 코드를 던지므로 AI 입장에서 답이 하나로
            // 통일된다 — 재시도해도 소용없음을 알 수 있다.
            log.warn("세션 {} 피드백 batch 실패 — 검사 후 세션이 사라졌다(events={})",
                    sessionId, events.size(), e);
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }

        int inserted = countBySession(sessionId) - before;
        int skipped = events.size() - inserted;

        log.info("세션 {} 피드백 batch (set_no={}, is_final={}): inserted={}, skipped={}",
                sessionId, request.getSetNo(), request.getIsFinal(), inserted, skipped);
        return inserted;
    }

    private int countBySession(long sessionId) {
        Integer count = jdbcTemplate.queryForObject(COUNT_BY_SESSION_SQL, Integer.class, sessionId);
        return count == null ? 0 : count;
    }
}
