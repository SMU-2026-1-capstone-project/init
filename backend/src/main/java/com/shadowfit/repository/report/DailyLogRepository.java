package com.shadowfit.repository.report;

import com.shadowfit.model.report.DailyLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

public interface DailyLogRepository extends JpaRepository<DailyLog,Long> {
    Optional<DailyLog> findByMemberIdAndLogDate(Long memberId, LocalDate logDate);

    // 같은 날 두 세션이 동시에 종료돼도 lost-update가 안 생김 — INSERT/UPDATE 판단과 누적을
    // DB 한 문장(원자 연산)으로 처리. JPA save()로 INSERT 실패를 catch해 재시도하는 방식은
    // Hibernate 세션이 flush 실패로 손상돼 같은 트랜잭션에서 후속 쿼리가 깨짐(실측으로 확인,
    // DailyLogServiceConcurrencyTest 최초 실패: "don't flush the Session after an exception occurs")
    // — 그래서 네이티브 upsert 한 문장으로 우회.
    // [알려진 기술부채] ON DUPLICATE KEY UPDATE 안의 VALUES(col) 함수는 MySQL 8.0.20에서
    // deprecated(향후 제거 예정)다. 대체 문법은 8.0.19+ 의 행 별칭:
    //     VALUES (...) AS new ON DUPLICATE KEY UPDATE col = col + new.col
    // 그런데 테스트가 H2 MySQL 모드에서 도는데 H2 파서가 `AS new` 를 거부한다(2026-07-28 실측:
    // "Syntax error ... [*]AS new"). 지금 바꾸면 운영(MySQL)은 되고 테스트만 깨지므로 보류.
    // 해소 조건: 테스트 DB를 Testcontainers MySQL 로 바꾸거나 H2 가 이 문법을 지원할 때.
    @Modifying
    @Query(value = "INSERT INTO daily_logs (member_id, log_date, total_exercise_time, total_calories) " +
                   "VALUES (:memberId, :logDate, :addTime, :addCalories) " +
                   "ON DUPLICATE KEY UPDATE " +
                   "total_exercise_time = total_exercise_time + VALUES(total_exercise_time), " +
                   "total_calories = total_calories + VALUES(total_calories)",
           nativeQuery = true)
    void upsertStats(@Param("memberId") Long memberId, @Param("logDate") LocalDate logDate,
                      @Param("addTime") int addTime, @Param("addCalories") BigDecimal addCalories);

    /**
     * 사용자가 쓴 메모·기분을 저장한다 (이슈 #215 ①).
     *
     * <p>원래는 서비스가 «찾아보고 없으면 save» 하는 check-then-act 였다. 같은 회원·같은 날짜라
     * 경합 상대가 남이 아니라 <b>자기 자신</b>이고(더블탭·재전송), 두 요청이 검사를 함께 통과하면
     * {@code uk_member_date} 위반이 <b>500</b> 으로 나갔다 — 이슈 #195(가입)와 같은 모양이고
     * 이쪽도 HTTP 경로다({@code ExerciseRecordController:67}).
     *
     * <p><b>#195 의 해법(제약 위반을 catch)을 여기 쓰지 않는 이유는 바로 위 주석에 있다</b> —
     * 이 테이블에서 그 방식은 이미 실측으로 실패했다. 그래서 같은 테이블이 이미 쓰고 있는
     * 네이티브 upsert 로 맞춘다.
     *
     * <p><b>누적이 아니라 덮어쓰기</b>인 것이 {@code upsertStats} 와 다른 점이다. 이건 사용자가
     * 그날 일지를 «다시 쓴» 것이라 마지막 입력이 이겨야 한다 — 기존 dirty-checking 경로도
     * memo·mood 를 그대로 대입하고 있었으므로 의미는 바뀌지 않는다.
     *
     * <p>⚠️ {@code mood} 를 enum 이 아니라 String 으로 받는다. 네이티브 쿼리라 JPA 의
     * {@code @Enumerated(STRING)} 변환이 걸리지 않는다 — 호출부가 {@code name()} 을 넘긴다.
     * (같은 이유로 {@code updated_at} 도 JPA 감사가 아니라 DDL 의
     * {@code ON UPDATE CURRENT_TIMESTAMP} 가 채운다. 이 값은 어떤 응답에도 실리지 않는다.)
     */
    @Modifying
    @Query(value = "INSERT INTO daily_logs (member_id, log_date, memo, mood) " +
                   "VALUES (:memberId, :logDate, :memo, :mood) " +
                   "ON DUPLICATE KEY UPDATE " +
                   "memo = VALUES(memo), mood = VALUES(mood)",
           nativeQuery = true)
    void upsertMemoAndMood(@Param("memberId") Long memberId, @Param("logDate") LocalDate logDate,
                           @Param("memo") String memo, @Param("mood") String mood);
}
