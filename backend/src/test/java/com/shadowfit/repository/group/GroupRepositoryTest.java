package com.shadowfit.repository.group;

import com.shadowfit.model.group.Group;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code findByIdForUpdate} — {@code GroupEventService.publish()}의 시퀀스 채번이 기대는
 * 락 조회 쿼리(Group.allocateNextSeq() 참고). 여기서는 "행이 조회되는가"만 확인한다 — 락이
 * 실제로 동시성을 막는지는 real MySQL이 필요한 별도 레이스 테스트의 몫이다
 * ({@code SignupUsernameRaceTest} 관행 참고).
 */
@SpringBootTest
@Transactional
@DisplayName("GroupRepository 테스트")
class GroupRepositoryTest {

    @Autowired private GroupRepository groupRepository;
    @Autowired private MemberRepository memberRepository;

    private Member creator;

    @BeforeEach
    void setUp() {
        creator = memberRepository.saveAndFlush(Member.builder()
                .email("creator@test.com").username("creator")
                .password("encoded-password").role(UserRole.USER).build());
    }

    @Test
    @DisplayName("findByIdForUpdate — 존재하는 그룹을 반환한다")
    void findByIdForUpdate_returnsGroup() {
        Group group = groupRepository.saveAndFlush(Group.builder().name("그룹1").createdBy(creator).build());

        assertThat(groupRepository.findByIdForUpdate(group.getId()))
                .isPresent()
                .get()
                .extracting(Group::getId)
                .isEqualTo(group.getId());
    }

    @Test
    @DisplayName("findByIdForUpdate — 존재하지 않는 id면 비어있다")
    void findByIdForUpdate_missingId_returnsEmpty() {
        assertThat(groupRepository.findByIdForUpdate(-1L)).isEmpty();
    }

    @Test
    @DisplayName("Group 생성 시 nextSeq 초기값은 0")
    void newGroup_nextSeqStartsAtZero() {
        Group group = groupRepository.saveAndFlush(Group.builder().name("그룹2").createdBy(creator).build());

        assertThat(group.getNextSeq()).isZero();
    }

    @Test
    @DisplayName("allocateNextSeq — 호출할 때마다 1씩 증가한 값을 반환한다")
    void allocateNextSeq_incrementsSequentially() {
        Group group = groupRepository.saveAndFlush(Group.builder().name("그룹3").createdBy(creator).build());

        assertThat(group.allocateNextSeq()).isEqualTo(1L);
        assertThat(group.allocateNextSeq()).isEqualTo(2L);
        assertThat(group.allocateNextSeq()).isEqualTo(3L);
    }
}
