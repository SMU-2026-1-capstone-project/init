package com.shadowfit.repository.goal;

import com.shadowfit.model.goal.Goal;
import com.shadowfit.model.goal.GoalType;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@DisplayName("GoalRepository 테스트")
class GoalRepositoryTest {

    @Autowired private GoalRepository goalRepository;
    @Autowired private MemberRepository memberRepository;

    private Member owner;
    private Member other;

    @BeforeEach
    void setUp() {
        goalRepository.deleteAll();
        memberRepository.deleteAll();
        goalRepository.flush();

        owner = saveMember("owner", "owner@t.com");
        other = saveMember("other", "other@t.com");
    }

    @Test
    @DisplayName("findByIdAndMemberId — 본인 목표만 조회되고, 남의 목표는 안 보인다(IDOR)")
    void findByIdAndMemberId_ownershipIsolation() {
        Goal goal = goalRepository.save(
                Goal.builder().member(owner).goalType(GoalType.WEEKLY_SESSIONS).targetValue(5).build());

        assertThat(goalRepository.findByIdAndMemberId(goal.getId(), owner.getId())).isPresent();
        assertThat(goalRepository.findByIdAndMemberId(goal.getId(), other.getId())).isEmpty();
    }

    @Test
    @DisplayName("findByMemberId — 다른 회원 목표는 안 섞인다")
    void findByMemberId_excludesOtherMembers() {
        goalRepository.save(Goal.builder().member(owner).goalType(GoalType.WEEKLY_SESSIONS).targetValue(5).build());
        goalRepository.save(Goal.builder().member(other).goalType(GoalType.WEEKLY_SESSIONS).targetValue(3).build());

        List<Goal> result = goalRepository.findByMemberId(owner.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMember().getId()).isEqualTo(owner.getId());
    }

    @Test
    @DisplayName("existsByMemberIdAndGoalType — 있으면 true, goalType이 다르면 false")
    void existsByMemberIdAndGoalType() {
        goalRepository.save(Goal.builder().member(owner).goalType(GoalType.WEEKLY_SESSIONS).targetValue(5).build());

        assertThat(goalRepository.existsByMemberIdAndGoalType(owner.getId(), GoalType.WEEKLY_SESSIONS)).isTrue();
        assertThat(goalRepository.existsByMemberIdAndGoalType(owner.getId(), GoalType.WEEKLY_MINUTES)).isFalse();
        assertThat(goalRepository.existsByMemberIdAndGoalType(other.getId(), GoalType.WEEKLY_SESSIONS)).isFalse();
    }

    @Test
    @DisplayName("회원당 goalType 하나 — DB 유니크 제약이 최종 방어선(애플리케이션 체크 우회해도 막힌다)")
    void uniqueConstraint_blocksDuplicateGoalTypePerMember() {
        goalRepository.saveAndFlush(
                Goal.builder().member(owner).goalType(GoalType.WEEKLY_SESSIONS).targetValue(5).build());

        assertThatThrownBy(() -> goalRepository.saveAndFlush(
                Goal.builder().member(owner).goalType(GoalType.WEEKLY_SESSIONS).targetValue(10).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 회원이어도 goalType이 다르면 둘 다 허용된다")
    void differentGoalTypes_bothAllowedForSameMember() {
        goalRepository.saveAndFlush(
                Goal.builder().member(owner).goalType(GoalType.WEEKLY_SESSIONS).targetValue(5).build());
        goalRepository.saveAndFlush(
                Goal.builder().member(owner).goalType(GoalType.WEEKLY_MINUTES).targetValue(120).build());

        assertThat(goalRepository.findByMemberId(owner.getId())).hasSize(2);
    }

    private Member saveMember(String username, String email) {
        return memberRepository.save(Member.builder()
                .username(username)
                .email(email)
                .password("encoded-password")
                .selectedPersona(SelectedPersona.BEGINNER)
                .build());
    }
}
