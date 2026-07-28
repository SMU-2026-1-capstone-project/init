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
}
