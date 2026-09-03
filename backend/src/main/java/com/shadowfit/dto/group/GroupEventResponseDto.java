package com.shadowfit.dto.group;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.shadowfit.model.group.GroupEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * WebSocket 브로드캐스트 봉투와 REST 백필 응답이 공유하는 형태.
 * {@code GroupSocketHandler}가 이 그대로를 JSON 직렬화해 세션에 보낸다.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "그룹 이벤트 res dto")
public class GroupEventResponseDto {
    @Schema(description = "그룹 내 시퀀스 번호", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long seq;

    @Schema(description = "그룹 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long groupId;

    @Schema(description = "이벤트 타입", requiredMode = Schema.RequiredMode.REQUIRED)
    private String type;

    @Schema(description = "발신자 회원 id (시스템 이벤트는 null)")
    private Long senderId;

    @Schema(description = "이벤트 페이로드(JSON 문자열)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String payload;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(description = "발생 시각", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime occurredAt;

    public static GroupEventResponseDto from(GroupEvent event) {
        return GroupEventResponseDto.builder()
                .seq(event.getSeq())
                .groupId(event.getGroup().getId())
                .type(event.getEventType())
                .senderId(event.getSender() != null ? event.getSender().getId() : null)
                .payload(event.getPayload())
                .occurredAt(event.getCreatedAt())
                .build();
    }
}