package com.shadowfit.repository.coaching;

import com.shadowfit.model.coaching.TrainerAssignment;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 트레이너-사용자 배정의 소유 검증 쿼리와 "사용자당 담당 트레이너는 한 명" 제약 검증
 * ({@code trainer-live-monitoring.md} §1·§4 — 권한 체크가 이 관계 위에 서므로, 관계 자체가
 * 의도대로 유일한지가 먼저 확인돼야 한다).
 */
@SpringBootTest
@Transactional
@DisplayName("트레이너 배정 레포지토리 테스트")
class TrainerAssignmentRepositoryTest {

    @Autowired private TrainerAssignmentRepository trainerAssignmentRepository;
    @Autowired private MemberRepository memberRepository;

    private Member trainer;
    private Member user;
    private Member otherTrainer;

    @BeforeEach
    void setUp() {
        trainer = memberRepository.save(newMember("trainer@test.com", "trainer1", UserRole.TRAINER));
        otherTrainer = memberRepository.save(newMember("other-trainer@test.com", "trainer2", UserRole.TRAINER));
        user = memberRepository.save(newMember("user@test.com", "user1", UserRole.USER));
    }

    @Test
    @DisplayName("배정된 트레이너-사용자 쌍은 existsByTrainerIdAndUserId 가 true")
    void existsByTrainerIdAndUserId_returnsTrueForAssignedPair() {
        trainerAssignmentRepository.save(TrainerAssignment.builder().trainer(trainer).user(user).build());

        assertThat(trainerAssignmentRepository.existsByTrainerIdAndUserId(trainer.getId(), user.getId())).isTrue();
    }

    @Test
    @DisplayName("배정되지 않은 트레이너로 조회하면 existsByTrainerIdAndUserId 가 false — 권한 체크의 핵심")
    void existsByTrainerIdAndUserId_returnsFalseForUnassignedTrainer() {
        trainerAssignmentRepository.save(TrainerAssignment.builder().trainer(trainer).user(user).build());

        assertThat(trainerAssignmentRepository.existsByTrainerIdAndUserId(otherTrainer.getId(), user.getId())).isFalse();
    }

    @Test
    @DisplayName("findByUserId 로 담당 트레이너를 조회한다")
    void findByUserId_returnsAssignment() {
        trainerAssignmentRepository.save(TrainerAssignment.builder().trainer(trainer).user(user).build());

        assertThat(trainerAssignmentRepository.findByUserId(user.getId()))
                .isPresent()
                .get()
                .extracting(a -> a.getTrainer().getId())
                .isEqualTo(trainer.getId());
    }

    @Test
    @DisplayName("한 사용자에게 담당 트레이너를 두 번 배정하면 유니크 제약 위반 — 1:1 관계가 DB 레벨에서 강제된다")
    void secondAssignmentForSameUser_violatesUniqueConstraint() {
        trainerAssignmentRepository.saveAndFlush(TrainerAssignment.builder().trainer(trainer).user(user).build());

        assertThatThrownBy(() ->
                trainerAssignmentRepository.saveAndFlush(
                        TrainerAssignment.builder().trainer(otherTrainer).user(user).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Member newMember(String email, String username, UserRole role) {
        return Member.builder()
                .email(email)
                .username(username)
                .password("encoded-password")
                .role(role)
                .build();
    }
}
