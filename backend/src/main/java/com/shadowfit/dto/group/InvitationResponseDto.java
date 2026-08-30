package com.shadowfit.dto.group;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.shadowfit.model.group.GroupInvitation;
import com.shadowfit.model.group.InvitationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "그룹 초대 res dto")
public class InvitationResponseDto {
    @Schema(description = "초대 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "그룹 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long groupId;

    @Schema(description = "그룹 이름", requiredMode = Schema.RequiredMode.REQUIRED)
    private String groupName;

    @Schema(description = "초대자 회원 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long inviterId;

    @Schema(description = "초대 상태", requiredMode = Schema.RequiredMode.REQUIRED)
    private InvitationStatus status;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(description = "초대 생성 시각", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createdAt;

    public static InvitationResponseDto from(GroupInvitation invitation) {
        return InvitationResponseDto.builder()
                .id(invitation.getId())
                .groupId(invitation.getGroup().getId())
                .groupName(invitation.getGroup().getName())
                .inviterId(invitation.getInviter().getId())
                .status(invitation.getStatus())
                .createdAt(invitation.getCreatedAt())
                .build();
    }
}