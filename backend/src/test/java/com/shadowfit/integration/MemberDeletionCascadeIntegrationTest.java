package com.shadowfit.integration;

import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
import com.shadowfit.model.exercise.FeedbackType;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.SessionFeedbackLog;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.model.report.DailyLog;
import com.shadowfit.model.report.Report;
import com.shadowfit.model.report.ReportType;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionFeedbackLogRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.report.DailyLogRepository;
import com.shadowfit.repository.report.ReportRepository;
import com.shadowfit.service.Member.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회원 탈퇴 시 실제 CASCADE 체인이 (H2 테스트 스키마 기준으로도) 끝까지 도는지 검증하는
 * 통합테스트. MemberServiceTest(모킹 기반)는 로직 분기는 검증하지만 실제 JPA cascade 동작은
 * 못 잡는다 — 오늘 Report/SessionFeedbackLog에서 @OnDelete 누락을 발견한 게 바로 이 경로라
 * 회원 삭제도 실제 DB로 한 번 더 확인한다 (pose-data-partition-fk-tradeoff.md §5).
 *
 * pose_data 정리(afterCommit + @Async)는 테스트 트랜잭션이 실제로 커밋되지 않아 여기서
 * 검증 불가 — 그 부분은 MemberServiceTest의 동기화 등록/발동 단위테스트가 커버한다.
 */
@SpringBootTest
@Transactional
@DisplayName("회원 탈퇴 CASCADE 통합테스트")
class MemberDeletionCascadeIntegrationTest {

    @Autowired private MemberService memberService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private ReportRepository reportRepository;
    @Autowired private SessionFeedbackLogRepository feedbackLogRepository;
    @Autowired private DailyLogRepository dailyLogRepository;
    @Autowired private EntityManager entityManager;

    private Member member;
    private Session session;

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(Member.builder()
                .email("cascade@test.com")
                .username("cascade유저")
                .password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER)
                .role(UserRole.USER)
                .build());

        Exercise exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(ExerciseCategory.LOWER)
                .expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00"))
                .build());

        session = sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(LocalDateTime.now().minusHours(1))
                .endTime(LocalDateTime.now())
                .status(Status.COMPLETED)
                .totalReps(10)
                .avgSyncRate(new BigDecimal("77.5"))
                .caloriesBurned(new BigDecimal("50.0"))
                .build());

        Report report = new Report();
        report.setMember(member);
        report.setSession(session);
        report.setReportType(ReportType.SESSION);
        reportRepository.saveAndFlush(report);

        feedbackLogRepository.saveAndFlush(SessionFeedbackLog.builder()
                .session(session).feedbackType(FeedbackType.KNEE_OUT)
                .occurredAt(LocalDateTime.now())
                .build());

        dailyLogRepository.saveAndFlush(DailyLog.builder()
                .member(member).logDate(LocalDate.now())
                .totalExerciseTime(20).totalCalories(new BigDecimal("100.0"))
                .build());

        // refresh_token/body_records는 이 통합테스트로 검증 불가:
        // - refresh_token: RefreshToken 엔티티가 Member로 가는 @ManyToOne 매핑 자체가 없어서
        //   (memberId가 단순 Long 컬럼) H2 테스트 스키마엔 FK조차 안 생김 — 별도 구조 개선 필요.
        // - body_records: JPA 엔티티 자체가 없는 미구현 테이블(production-signal-checklist.md)이라
        //   Hibernate ddl-auto로 생성되는 H2 테스트 스키마엔 테이블 자체가 없음.
        // 둘 다 오늘 추가/확인한 ON DELETE CASCADE는 라이브 MySQL 컨테이너에서 직접 확인 완료.
    }

    @Test
    @DisplayName("탈퇴 시 세션·리포트·피드백로그·일일로그 전부 정리됨 (실제 JPA cascade로 검증 가능한 범위)")
    void deleteAccount_cascadesToAllOwnedData() {
        Long memberId = member.getId();
        Long sessionId = session.getId();
        LocalDate logDate = LocalDate.now();

        memberService.deleteAccount(member.getEmail());

        // deleteAccount 내부의 memberRepository.delete()는 즉시 flush되지 않고 트랜잭션 커밋
        // 시점까지 지연될 수 있음 — 테스트는 커밋 없이 검증해야 하므로 명시적으로 flush해서
        // 실제 DELETE(및 DB 레벨 CASCADE)가 지금 실행되게 한 뒤, 1차 캐시의 stale 엔티티를
        // clear()로 비워서 이후 조회가 DB를 다시 타게 함(그래야 CASCADE 결과가 보임).
        entityManager.flush();
        entityManager.clear();

        assertThat(memberRepository.findById(memberId)).isEmpty();
        assertThat(sessionRepository.findById(sessionId)).isEmpty();
        assertThat(reportRepository.findBySessionId(sessionId)).isEmpty();
        assertThat(feedbackLogRepository.findBySessionIdOrderByOccurredAtAsc(sessionId)).isEmpty();
        // CodeRabbit 지적 반영(2026-07-24): DailyLog.member도 오늘 @OnDelete를 추가한 대상인데
        // 이 테스트가 빠뜨리고 있었음 — 실제 cascade까지 확인.
        assertThat(dailyLogRepository.findByMemberIdAndLogDate(memberId, logDate)).isEmpty();
    }
}
