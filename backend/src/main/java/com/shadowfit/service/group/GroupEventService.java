package com.shadowfit.service.group;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.group.GroupEventResponseDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.group.Group;
import com.shadowfit.model.group.GroupEvent;
import com.shadowfit.model.member.Member;
import com.shadowfit.repository.group.GroupEventRepository;
import com.shadowfit.repository.group.GroupRepository;
import com.shadowfit.repository.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 그룹 이벤트를 시퀀스 채번과 함께 영속화하고, 트랜잭션 커밋 후에만 로컬 WebSocket
 * 세션으로 브로드캐스트한다(롤백된 이벤트가 클라이언트에 나가는 것을 막기 위함).
 *
 * <p>단일 인스턴스 전제 — {@link GroupSocketRegistry}는 이 프로세스가 들고 있는 세션만
 * 안다. 인스턴스를 늘리면 이 브로드캐스트 자리를 Redis pub/sub 발행으로 바꿔야 한다
 * ({@code docs/decisions/multiuser-realtime-sync.md} §7 세션3 이후).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupEventService {

    private final GroupRepository groupRepository;
    private final GroupEventRepository groupEventRepository;
    private final MemberRepository memberRepository;
    private final GroupSocketRegistry groupSocketRegistry;
    private final ObjectMapper objectMapper;

    @Transactional
    public GroupEvent publish(Long groupId, Long senderId, String eventType, String payload) {
        // Group.allocateNextSeq()가 원자적이려면 이 조회가 행을 잠가야 한다 — 동시에 여러
        // 스레드가 같은 그룹에 publish()해도 seq가 겹치지 않는 이유.
        Group group = groupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_NOT_FOUND));

        Member sender = null;
        if (senderId != null) {
            sender = memberRepository.findById(senderId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        }

        long seq = group.allocateNextSeq();

        GroupEvent event = groupEventRepository.save(GroupEvent.builder()
                .group(group)
                .seq(seq)
                .eventType(eventType)
                .sender(sender)
                .payload(payload)
                .build());

        registerPostCommitBroadcast(event);
        return event;
    }

    private void registerPostCommitBroadcast(GroupEvent event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            broadcast(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                broadcast(event);
            }
        });
    }

    private void broadcast(GroupEvent event) {
        try {
            String json = objectMapper.writeValueAsString(GroupEventResponseDto.from(event));
            groupSocketRegistry.broadcast(event.getGroup().getId(), json);
        } catch (JsonProcessingException e) {
            log.warn("그룹 이벤트 직렬화 실패 (groupId={}, seq={})", event.getGroup().getId(), event.getSeq(), e);
        }
    }
}