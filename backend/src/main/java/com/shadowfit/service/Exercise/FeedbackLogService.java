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
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackLogService {
    private final SessionRepository sessionRepository;
    private final JdbcTemplate jdbcTemplate;

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** MySQL ER_NO_REFERENCED_ROW_2 — 부모 행이 없어 FK 를 못 건다. 세션 소멸의 신호다. */
    private static final int MYSQL_NO_REFERENCED_ROW = 1452;

    /**
     * 참조 무결성 위반 SQLState — H2({@code 23503}/{@code 23506})·PostgreSQL({@code 23503}).
     *
     * <p>MySQL 은 무결성 위반 전부를 {@code 23000} 하나로 답해서 FK 와 NOT NULL 이 SQLState 로는
     * 안 갈린다 — 그래서 MySQL 은 위 벤더 코드로 본다. 반대로 <b>테스트는 H2 로 돈다</b>(이
     * 클래스의 테스트 주석). 둘 다 안 보면 운영과 테스트 중 한쪽에서 판정이 조용히 뒤집힌다.
     */
    private static final Set<String> REFERENTIAL_INTEGRITY_SQL_STATES = Set.of("23503", "23506");

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
            // 🔴 FK 위반일 때만 «세션 소멸» 로 번역한다 (#238 리뷰 A-1).
            //
            // 이 catch 가 상정하는 것은 위 존재검사(:67)와 이 INSERT 사이에 세션이 사라진
            // 경우다 — 회원 탈퇴가 users → exercise_sessions 를 CASCADE 로 지운다. 배치의 모든
            // 행이 같은 session_id 라 이 배치는 통째로 무효이고, 부분 성공을 만들 여지가 없다.
            //
            // 그런데 catch 는 상위 타입을 받으므로 NOT NULL·값 범위·데이터 잘림 위반도 함께
            // 걸린다. 그것까지 SESSION_NOT_FOUND 로 답하면 AI 는 「세션이 없어졌다」고 믿고
            // 재전송 설계의 축 C 에 따라 <b>버퍼를 통째로 버린다</b> — 우리 쪽 값 오류 한 건이
            // 정상 이벤트까지 없애는 셈이다(feedback-batch-retransmission.md 축 C).
            //
            // 예전에는 INSERT IGNORE 가 이걸 무음으로 만들어 «행은 사라지고 로그는 중복이라고
            // 말하는» 상태였다(#219). 사전검사와 같은 코드를 던지므로 AI 입장에서 답이 하나로
            // 통일된다 — 재시도해도 소용없음을 알 수 있다.
            if (!isForeignKeyViolation(e)) {
                // 세션 소멸이 아니다. 삼키지 않고 그대로 올린다 — gRPC 는 INTERNAL 로 답하고,
                // 축 C 의 「그 외」 행이 받는다(버퍼 유지).
                log.error("세션 {} 피드백 batch 실패 — FK 아닌 제약 위반(events={})",
                        sessionId, events.size(), e);
                throw e;
            }
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

    /**
     * 이 무결성 위반이 <b>FK 위반</b>인가 — 즉 부모 행(세션)이 없어서 난 것인가.
     *
     * <p>MySQL {@code 1452}(ER_NO_REFERENCED_ROW_2)는 «자식 행을 넣으려는데 부모가 없다» 다.
     * NOT NULL({@code 1048})·값 범위({@code 1264})·데이터 잘림({@code 1406})은 같은 Spring
     * 예외로 올라오지만 세션 소멸과 무관하므로 여기서 갈라낸다.
     *
     * <p>Spring 예외는 드라이버 예외를 감싸고 있어 원인 체인을 따라 내려가야 {@code SQLException}
     * 의 벤더 코드가 나온다.
     */
    private boolean isForeignKeyViolation(Throwable e) {
        for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof SQLException sqlException
                    && (sqlException.getErrorCode() == MYSQL_NO_REFERENCED_ROW
                        || REFERENTIAL_INTEGRITY_SQL_STATES.contains(sqlException.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    private int countBySession(long sessionId) {
        Integer count = jdbcTemplate.queryForObject(COUNT_BY_SESSION_SQL, Integer.class, sessionId);
        return count == null ? 0 : count;
    }
}
