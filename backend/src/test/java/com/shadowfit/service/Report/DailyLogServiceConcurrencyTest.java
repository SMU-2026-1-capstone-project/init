package com.shadowfit.service.Report;

import com.shadowfit.dto.report.record.DailyLogRequestDto;
import com.shadowfit.dto.report.record.Mood;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.model.report.DailyLog;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.report.DailyLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DailyLog 첫 기록 INSERT 경합 실측 — 같은 사용자의 그날 첫 두 세션이 정확히 동시에
 * accumulateStats를 호출할 때, catch 블록의 재시도(incrementStats)가 같은 트랜잭션 안에서
 * 실제로 성공하는지(Hibernate 세션이 save() 실패로 손상돼 후속 쿼리가 깨지지 않는지) 검증.
 * 이론(원자 UPDATE라 안전할 것)이 아니라 진짜 두 스레드로 강제 실측.
 */
@SpringBootTest
class DailyLogServiceConcurrencyTest {

    @Autowired private DailyLogService dailyLogService;
    @Autowired private DailyLogRepository dailyLogRepository;
    @Autowired private MemberRepository memberRepository;

    @Test
    @DisplayName("같은 사용자의 그날 첫 두 세션이 동시에 종료돼도 lost-update 없이 합산된다")
    void concurrentFirstAccumulate_bothSucceed_noDataLoss() throws InterruptedException {
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email("concurrency-test@test.com")
                .username("동시성테스트")
                .password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER)
                .role(UserRole.USER)
                .build());
        Long memberId = member.getId();
        LocalDate logDate = LocalDate.of(2026, 7, 15);

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);
        AtomicReference<Throwable> errorA = new AtomicReference<>();
        AtomicReference<Throwable> errorB = new AtomicReference<>();

        Thread threadA = new Thread(() -> {
            try {
                startGate.await();
                dailyLogService.accumulateStats(memberId, logDate, 10, BigDecimal.valueOf(100));
            } catch (Throwable t) {
                errorA.set(t);
            } finally {
                doneGate.countDown();
            }
        });
        Thread threadB = new Thread(() -> {
            try {
                startGate.await();
                dailyLogService.accumulateStats(memberId, logDate, 20, BigDecimal.valueOf(200));
            } catch (Throwable t) {
                errorB.set(t);
            } finally {
                doneGate.countDown();
            }
        });

        threadA.start();
        threadB.start();
        startGate.countDown(); // 두 스레드가 최대한 같은 순간에 accumulateStats를 부르도록
        doneGate.await();

        assertThat(errorA.get()).as("thread A는 예외 없이 끝나야 함").isNull();
        assertThat(errorB.get()).as("thread B는 예외 없이 끝나야 함").isNull();

        List<DailyLog> logs = dailyLogRepository.findAll().stream()
                .filter(l -> l.getMember().getId().equals(memberId) && l.getLogDate().equals(logDate))
                .toList();
        assertThat(logs).as("row가 중복 생성되지 않고 딱 1개여야 함").hasSize(1);

        DailyLog result = logs.get(0);
        assertThat(result.getTotalExerciseTime()).as("10+20 유실 없이 합산돼야 함").isEqualTo(30);
        assertThat(result.getTotalCalories()).as("100+200 유실 없이 합산돼야 함")
                .isEqualByComparingTo(BigDecimal.valueOf(300));
    }

    /**
     * 이슈 #215 ① — 메모 저장이 check-then-act 라 같은 날 첫 기록을 동시에 쓰면 500 이 나갔다.
     *
     * <p>위 {@code accumulateStats} 경합과 <b>상대가 다르다</b>. 저건 «같은 날 두 세션» 이지만
     * 이건 <b>같은 사용자의 같은 요청 두 번</b>이다 — 더블탭·재전송이면 그대로 재현된다.
     * 그래서 «드문 조건이라 괜찮다» 로 넘길 자리가 아니었다.
     *
     * <p>고치기 전 여기서 나가던 것은 {@code uk_member_date} 위반 →
     * {@code DataIntegrityViolationException} → {@code GlobalExceptionHandler} 의
     * {@code Exception} 핸들러 → <b>500</b> 이다.
     */
    @Test
    @DisplayName("#215 같은 날 메모를 동시에 저장해도 500 이 아니라 둘 다 성공하고 row 는 1개")
    void concurrentFirstMemoWrite_bothSucceed_singleRow() throws InterruptedException {
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email("memo-race@test.com")
                .username("메모경합")
                .password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER)
                .role(UserRole.USER)
                .build());
        Long memberId = member.getId();
        LocalDate logDate = LocalDate.of(2026, 8, 15);

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);
        AtomicReference<Throwable> errorA = new AtomicReference<>();
        AtomicReference<Throwable> errorB = new AtomicReference<>();

        Thread threadA = writer(startGate, doneGate, errorA, memberId, logDate, "A 가 쓴 메모", Mood.GOOD);
        Thread threadB = writer(startGate, doneGate, errorB, memberId, logDate, "B 가 쓴 메모", Mood.GREAT);

        threadA.start();
        threadB.start();
        startGate.countDown();
        doneGate.await();

        assertThat(errorA.get()).as("thread A는 예외 없이 끝나야 함 (고치기 전엔 한쪽이 500)").isNull();
        assertThat(errorB.get()).as("thread B는 예외 없이 끝나야 함 (고치기 전엔 한쪽이 500)").isNull();

        List<DailyLog> logs = dailyLogRepository.findAll().stream()
                .filter(l -> l.getMember().getId().equals(memberId) && l.getLogDate().equals(logDate))
                .toList();
        assertThat(logs).as("row가 중복 생성되지 않고 딱 1개여야 함").hasSize(1);

        // 어느 쪽이 이겼는지는 단언하지 않는다 — 덮어쓰기 의미상 «마지막 입력이 이긴다» 이고,
        // 두 스레드 중 누가 마지막인지는 정의되지 않는다. 여기서 지키는 것은 «둘 중 하나가
        // 온전히 남는다» 이지 순서가 아니다.
        DailyLog result = logs.get(0);
        assertThat(result.getMemo()).isIn("A 가 쓴 메모", "B 가 쓴 메모");
        assertThat(result.getMood()).isIn(Mood.GOOD, Mood.GREAT);
    }

    /**
     * 네이티브 upsert 로 바꾸면서 «있으면 덮어쓴다» 가 유지되는지 — 기존 dirty-checking 경로가
     * 하던 일과 같아야 한다. mood 를 enum 이 아니라 String 으로 넘기게 됐으므로
     * ({@code @Enumerated} 변환이 네이티브 쿼리엔 안 걸린다) 되읽었을 때 enum 으로 돌아오는지도
     * 여기서 확인된다.
     */
    @Test
    @DisplayName("#215 두 번째 저장은 새 row 가 아니라 덮어쓰기 (memo·mood 모두)")
    void secondWrite_overwritesInPlace() {
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email("memo-overwrite@test.com")
                .username("메모덮어쓰기")
                .password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER)
                .role(UserRole.USER)
                .build());
        LocalDate logDate = LocalDate.of(2026, 8, 15);

        dailyLogService.saveOrUpdateLog(member.getId(),
                new DailyLogRequestDto(logDate, "처음 쓴 메모", Mood.NORMAL));
        dailyLogService.saveOrUpdateLog(member.getId(),
                new DailyLogRequestDto(logDate, "고쳐 쓴 메모", Mood.GREAT));

        List<DailyLog> logs = dailyLogRepository.findAll().stream()
                .filter(l -> l.getMember().getId().equals(member.getId()) && l.getLogDate().equals(logDate))
                .toList();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getMemo()).isEqualTo("고쳐 쓴 메모");
        assertThat(logs.get(0).getMood()).isEqualTo(Mood.GREAT);
    }

    private Thread writer(CountDownLatch startGate, CountDownLatch doneGate,
                          AtomicReference<Throwable> error, Long memberId, LocalDate logDate,
                          String memo, Mood mood) {
        return new Thread(() -> {
            try {
                startGate.await();
                dailyLogService.saveOrUpdateLog(memberId, new DailyLogRequestDto(logDate, memo, mood));
            } catch (Throwable t) {
                error.set(t);
            } finally {
                doneGate.countDown();
            }
        });
    }
}
