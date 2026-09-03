package com.shadowfit.dto.group;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.shadowfit.model.group.GroupMember;
import com.shadowfit.model.group.GroupMemberStatus;
import com.shadowfit.model.group.GroupRole;
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
@Schema(description = "그룹 멤버 res dto")
public class GroupMemberResponseDto {
    @Schema(description = "회원 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long memberId;

    @Schema(description = "닉네임", requiredMode = Schema.RequiredMode.REQUIRED)
    private String username;

    @Schema(description = "그룹 내 역할", requiredMode = Schema.RequiredMode.REQUIRED)
    private GroupRole role;

    @Schema(description = "가입 상태", requiredMode = Schema.RequiredMode.REQUIRED)
    private GroupMemberStatus status;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(description = "가입 시각", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime joinedAt;

    public static GroupMemberResponseDto from(GroupMember groupMember) {
        return GroupMemberResponseDto.builder()
                .memberId(groupMember.getMember().getId())
                .username(groupMember.getMember().getUsername())
                .role(groupMember.getRole())
                .status(groupMember.getStatus())
                .joinedAt(groupMember.getJoinedAt())
                .build();
    }
}