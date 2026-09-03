package com.shadowfit.repository.group;

import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupEvent;
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
 * {@code findAllByGroupIdAndSeqGreaterThanOrderBySeqAsc} — 재연결 백필의 유일한 경로
 * ({@code GroupController.getEventsAfter}). afterSeq 경계와 그룹 간 격리, 순서 보장이 핵심이다.
 */
@SpringBootTest
@Transactional
@DisplayName("GroupEventRepository 테스트")
class GroupEventRepositoryTest {

    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupEventRepository groupEventRepository;
    @Autowired private MemberRepository memberRepository;

    private Group group;
    private Group otherGroup;
    private Member sender;

    @BeforeEach
    void setUp() {
        sender = memberRepository.saveAndFlush(newMember("sender@test.com", "sender"));
        group = groupRepository.saveAndFlush(Group.builder().name("그룹").createdBy(sender).build());
        otherGroup = groupRepository.saveAndFlush(Group.builder().name("다른 그룹").createdBy(sender).build());

        saveEvent(group, 1L);
        saveEvent(group, 2L);
        saveEvent(group, 3L);
        saveEvent(otherGroup, 1L);
    }

    @Test
    @DisplayName("afterSeq=0 — 그룹의 모든 이벤트를 seq 오름차순으로 반환한다")
    void findAllAfterZero_returnsAllEventsInOrder() {
        assertThat(groupEventRepository.findAllByGroupIdAndSeqGreaterThanOrderBySeqAsc(group.getId(), 0L))
                .extracting(GroupEvent::getSeq)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("afterSeq=1 — 1은 제외(경계는 초과, 이상 아님)하고 2·3만 반환한다")
    void findAllAfterOne_excludesTheGivenSeq() {
        assertThat(groupEventRepository.findAllByGroupIdAndSeqGreaterThanOrderBySeqAsc(group.getId(), 1L))
                .extracting(GroupEvent::getSeq)
                .containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("afterSeq=3(최신) — 놓친 이벤트가 없으면 빈 목록")
    void findAllAfterLatest_returnsEmpty() {
        assertThat(groupEventRepository.findAllByGroupIdAndSeqGreaterThanOrderBySeqAsc(group.getId(), 3L))
                .isEmpty();
    }

    @Test
    @DisplayName("다른 그룹의 이벤트는 섞이지 않는다")
    void findAll_isIsolatedPerGroup() {
        assertThat(groupEventRepository.findAllByGroupIdAndSeqGreaterThanOrderBySeqAsc(otherGroup.getId(), 0L))
                .extracting(GroupEvent::getSeq)
                .containsExactly(1L);
    }

    private void saveEvent(Group targetGroup, long seq) {
        groupEventRepository.saveAndFlush(GroupEvent.builder()
                .group(targetGroup).seq(seq).eventType("REP_COMPLETED").sender(sender).payload("{}").build());
    }

    private Member newMember(String email, String username) {
        return Member.builder().email(email).username(username)
                .password("encoded-password").role(UserRole.USER).build();
    }
}
