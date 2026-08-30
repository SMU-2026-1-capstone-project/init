package com.shadowfit.service.group;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupEvent;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.group.GroupEventRepository;
import com.shadowfit.repository.group.GroupRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 트랜잭션 동기화가 걸려 있지 않은 순수 단위 테스트에서는
 * {@code TransactionSynchronizationManager.isSynchronizationActive()}가 항상 false라
 * {@code GroupEventService.publish()}가 즉시(커밋 대기 없이) 브로드캐스트한다 — 이게 바로
 * "동기화가 없으면 즉시 방송" 폴백 분기이므로, 이 테스트 환경 자체가 그 분기를 검증한다.
 */
@DisplayName("GroupEventService 테스트")
class GroupEventServiceTest {

    private static final Long GROUP_ID = 1L;
    private static final Long SENDER_ID = 10L;

    @Mock private GroupRepository groupRepository;
    @Mock private GroupEventRepository groupEventRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private GroupSocketRegistry groupSocketRegistry;

    private Group group;
    private Member sender;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        Member creator = Member.builder().id(99L).email("creator@test.com").username("creator")
                .password("encoded-password").role(UserRole.USER).build();
        group = Group.builder().id(GROUP_ID).name("그룹").createdBy(creator).build();
        sender = Member.builder().id(SENDER_ID).email("sender@test.com").username("sender")
                .password("encoded-password").role(UserRole.USER).build();
    }

    @Test
    @DisplayName("publish — 그룹이 없으면 GROUP_NOT_FOUND, 저장·방송 모두 일어나지 않는다")
    void publish_groupNotFound_throwsAndSkipsSideEffects() {
        GroupEventService service = newService(new ObjectMapper());
        when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.publish(GROUP_ID, SENDER_ID, "REP_COMPLETED", "{}"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.GROUP_NOT_FOUND);

        verify(groupEventRepository, never()).save(any());
        verify(groupSocketRegistry, never()).broadcast(anyLong(), anyString());
    }

    @Test
    @DisplayName("publish — senderId가 존재하지 않는 사용자면 USER_NOT_FOUND")
    void publish_unknownSender_throws() {
        GroupEventService service = newService(new ObjectMapper());
        when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
        when(memberRepository.findById(SENDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.publish(GROUP_ID, SENDER_ID, "REP_COMPLETED", "{}"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("publish — senderId가 null이면(시스템 이벤트) 사용자 조회를 건너뛴다")
    void publish_nullSender_skipsMemberLookup() {
        GroupEventService service = newService(new ObjectMapper());
        when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
        when(groupEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> service.publish(GROUP_ID, null, "MEMBER_JOINED", "{}"))
                .doesNotThrowAnyException();

        verify(memberRepository, never()).findById(any());
    }

    @Test
    @DisplayName("publish — group.allocateNextSeq()로 채번한 seq로 이벤트를 저장하고, 직렬화해 그룹에 방송한다")
    void publish_success_savesAndBroadcasts() {
        GroupEventService service = newService(new ObjectMapper());
        when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
        when(memberRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
        when(groupEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GroupEvent event = service.publish(GROUP_ID, SENDER_ID, "REP_COMPLETED", "{\"rep\":1}");

        assertThat(event.getSeq()).isEqualTo(1L);
        assertThat(event.getEventType()).isEqualTo("REP_COMPLETED");
        assertThat(event.getSender()).isEqualTo(sender);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(groupSocketRegistry).broadcast(eq(GROUP_ID), jsonCaptor.capture());
        assertThat(jsonCaptor.getValue())
                .contains("\"seq\":1")
                .contains("\"type\":\"REP_COMPLETED\"")
                .contains("\"senderId\":10");
    }

    @Test
    @DisplayName("publish — 연속 호출 시 group의 next_seq가 순차 증가한다")
    void publish_consecutiveCalls_incrementSeq() {
        GroupEventService service = newService(new ObjectMapper());
        when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
        when(memberRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
        when(groupEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GroupEvent first = service.publish(GROUP_ID, SENDER_ID, "REP_COMPLETED", "{}");
        GroupEvent second = service.publish(GROUP_ID, SENDER_ID, "REP_COMPLETED", "{}");

        assertThat(first.getSeq()).isEqualTo(1L);
        assertThat(second.getSeq()).isEqualTo(2L);
    }

    @Test
    @DisplayName("publish — 직렬화에 실패하면 방송만 조용히 건너뛴다(예외를 던지지 않는다)")
    void publish_serializationFailure_swallowsAndSkipsBroadcast() throws JsonProcessingException {
        ObjectMapper failingMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("boom") {});
        GroupEventService service = newService(failingMapper);
        when(groupRepository.findByIdForUpdate(GROUP_ID)).thenReturn(Optional.of(group));
        when(memberRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
        when(groupEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> service.publish(GROUP_ID, SENDER_ID, "REP_COMPLETED", "{}"))
                .doesNotThrowAnyException();

        verify(groupSocketRegistry, never()).broadcast(anyLong(), anyString());
    }

    private GroupEventService newService(ObjectMapper objectMapper) {
        return new GroupEventService(groupRepository, groupEventRepository, memberRepository,
                groupSocketRegistry, objectMapper);
    }
}
