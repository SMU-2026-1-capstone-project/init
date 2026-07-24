package com.shadowfit.service.Exercise;

import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
import com.shadowfit.model.exercise.PoseData;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * PoseDataCleanupService 통합테스트 — 회원 탈퇴 시 pose_data 참조무결성 대체(B5,
 * pose-data-partition-fk-tradeoff.md). @Async라 실제 스레드풀에서 실행되므로 폴링으로 확인.
 *
 * ⚠️ 의도적으로 @Transactional 미사용(CodeRabbit 지적, 2026-07-24): 이 테스트가 @Transactional
 * 이었을 때는 setUp()의 fixture가 테스트 트랜잭션 안에서만 존재(커밋 안 됨)해서, cleanupService의
 * @Async 스레드나 Awaitility의 폴링 스레드가 애초에 그 row 자체를 볼 수 없었다 — 그래서
 * "isEmpty()" 단언이 실제 삭제 성공 때문이 아니라 "그 스레드에서 원래 안 보여서" 통과하는
 * 거짓 양성이었다(실제로 cleanupBySessionIds가 완전히 고장나도 이 테스트는 통과했을 것).
 * fixture를 진짜로 커밋시켜야 다른 스레드에서도 보이고, 삭제를 제대로 검증할 수 있어서
 * @Transactional을 빼고 @AfterEach에서 수동 정리한다.
 */
@SpringBootTest
@DisplayName("PoseDataCleanupService 테스트")
class PoseDataCleanupServiceTest {

    @Autowired private PoseDataCleanupService cleanupService;
    @Autowired private PoseDataRepository poseDataRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private Long memberId;
    private Long exerciseId;
    private Session targetSession;
    private Session otherSession;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email("cleanup-" + unique + "@test.com").username("u-" + unique).password("dummy")
                .role(UserRole.USER).build());
        memberId = member.getId();
        Exercise exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(ExerciseCategory.LOWER).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .build());
        exerciseId = exercise.getId();

        targetSession = sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise).startTime(LocalDateTime.now())
                .status(Status.COMPLETED).totalReps(5).build());
        otherSession = sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise).startTime(LocalDateTime.now())
                .status(Status.COMPLETED).totalReps(5).build());

        poseDataRepository.saveAndFlush(PoseData.builder()
                .session(targetSession).timestampSec(0.0).jointCoordinates("{}")
                .syncRate(70.0).isCorrect(true).build());
        poseDataRepository.saveAndFlush(PoseData.builder()
                .session(otherSession).timestampSec(0.0).jointCoordinates("{}")
                .syncRate(70.0).isCorrect(true).build());
    }

    @AfterEach
    void tearDown() {
        // @Transactional이 없어 자동 롤백이 안 되므로 직접 정리(생성 역순).
        //
        // ⚠️ 2026-07-24: 처음엔 이 메서드에 @Transactional을 직접 붙였는데도
        // deleteBySessionIdIn(@Modifying 벌크 쿼리라 트랜잭션 컨텍스트 필수)이
        // TransactionRequiredException을 던졌다 — Spring의 TransactionalTestExecutionListener는
        // @Test 메서드 실행만 트랜잭션으로 감싸고, JUnit이 직접 호출하는 @BeforeEach/@AfterEach
        // 콜백 자체는 TestContextManager를 안 거쳐서 @Transactional이 조용히 무시됐던 것.
        // TransactionTemplate으로 직접 트랜잭션을 열어서 우회한다.
        //
        // ⚠️ 2026-07-24 (CodeRabbit): setUp()이 중간에 실패하면 targetSession/otherSession/
        // exerciseId/memberId가 null인 채로 여기 진입할 수 있어서, null 체크 없이 그대로
        // deleteById 등을 부르면 NPE가 나면서 원래 setUp 실패 원인을 가려버린다 — null이 아닌
        // 것만 골라서 정리.
        List<Long> sessionIds = List.of(targetSession, otherSession).stream()
                .filter(java.util.Objects::nonNull)
                .map(Session::getId)
                .toList();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            if (!sessionIds.isEmpty()) {
                poseDataRepository.deleteBySessionIdIn(sessionIds);
                sessionRepository.deleteAllById(sessionIds);
            }
            if (exerciseId != null) {
                exercisesRepository.deleteById(exerciseId);
            }
            if (memberId != null) {
                memberRepository.deleteById(memberId);
            }
        });
    }

    @Test
    @DisplayName("대상 세션의 pose_data만 지워지고 다른 세션 것은 보존됨")
    void cleanupBySessionIds_removesOnlyTargetSessions() {
        cleanupService.cleanupBySessionIds(List.of(targetSession.getId()));

        await().atMost(java.time.Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(poseDataRepository.findFramesBySessionId(targetSession.getId())).isEmpty()
        );
        assertThat(poseDataRepository.findFramesBySessionId(otherSession.getId())).hasSize(1);
    }

    @Test
    @DisplayName("빈 리스트/null이면 아무 것도 지우지 않고 조용히 반환")
    void cleanupBySessionIds_emptyOrNull_noop() {
        // CodeRabbit 지적 반영(2026-07-24): null/빈 리스트는 repository를 아예 안 부르는
        // no-op 경로라 비동기 디스패치 완료를 기다릴 필요 자체가 없음 — 고정 sleep 제거.
        cleanupService.cleanupBySessionIds(List.of());
        cleanupService.cleanupBySessionIds(null);

        assertThat(poseDataRepository.findFramesBySessionId(targetSession.getId())).hasSize(1);
        assertThat(poseDataRepository.findFramesBySessionId(otherSession.getId())).hasSize(1);
    }
}
