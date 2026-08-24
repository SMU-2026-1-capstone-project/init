package com.shadowfit.service.exercise;

import com.shadowfit.dto.exercises.VideoRequestDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * createSession() TOCTOU 레이스 실측 — 같은 회원이 두 세션을 정확히 동시에 생성 시도할 때,
 * MemberRepository.findByIdForUpdate(PESSIMISTIC_WRITE)가 직렬화해 딱 하나만 성공하고
 * 나머지는 SESSION_ALREADY_IN_PROGRESS로 거절되는지 검증.
 * (경합 방지책 없이 existsByMemberIdAndStatus만 썼다면 둘 다 통과했을 레이스.)
 */
@SpringBootTest
class SessionCreateConcurrencyTest {

    @Autowired private SessionService sessionService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private com.shadowfit.repository.exercise.CategoryRepository categoryRepository;

    @Test
    @DisplayName("같은 회원이 세션 두 개를 동시에 생성 시도하면 하나만 성공한다")
    void concurrentCreateSession_onlyOneSucceeds() throws InterruptedException {
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email("session-concurrency@test.com")
                .username("세션동시성테스트")
                .password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER)
                .role(UserRole.USER)
                .build());
        Long memberId = member.getId();

        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        Exercise exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트")
                .category(category)
                .expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00"))
                .analysisSupported(true)  // 기본값 false — createSession이 W007로 막히므로 명시 필요
                .build());
        Long exerciseId = exercise.getId();

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);
        AtomicReference<Throwable> errorA = new AtomicReference<>();
        AtomicReference<Throwable> errorB = new AtomicReference<>();

        VideoRequestDto dto = VideoRequestDto.builder().exerciseId(exerciseId).build();

        Thread threadA = new Thread(() -> {
            try {
                startGate.await();
                sessionService.createSession(dto, memberId, "https://youtu.be/a");
            } catch (Throwable t) {
                errorA.set(t);
            } finally {
                doneGate.countDown();
            }
        });
        Thread threadB = new Thread(() -> {
            try {
                startGate.await();
                sessionService.createSession(dto, memberId, "https://youtu.be/b");
            } catch (Throwable t) {
                errorB.set(t);
            } finally {
                doneGate.countDown();
            }
        });

        threadA.start();
        threadB.start();
        startGate.countDown(); // 두 스레드가 최대한 같은 순간에 createSession을 부르도록
        doneGate.await();

        List<Throwable> errors = java.util.Arrays.asList(errorA.get(), errorB.get());
        long successCount = errors.stream().filter(e -> e == null).count();
        long rejectedCount = errors.stream()
                .filter(e -> e instanceof BusinessException be && be.getErrorCode() == ErrorCode.SESSION_ALREADY_IN_PROGRESS)
                .count();

        assertThat(successCount).as("정확히 하나만 성공해야 함").isEqualTo(1);
        assertThat(rejectedCount).as("나머지 하나는 SESSION_ALREADY_IN_PROGRESS로 거절돼야 함").isEqualTo(1);

        List<com.shadowfit.model.exercise.Session> sessions = sessionRepository.findAll().stream()
                .filter(s -> s.getMember().getId().equals(memberId))
                .toList();
        assertThat(sessions).as("실제로 생성된 세션은 1개여야 함").hasSize(1);
    }
}
