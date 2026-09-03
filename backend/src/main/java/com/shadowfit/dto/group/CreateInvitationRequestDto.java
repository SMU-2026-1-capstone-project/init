package com.shadowfit.dto.group;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "그룹 초대 req dto")
public class CreateInvitationRequestDto {
    @NotNull
    @Schema(description = "초대할 회원 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long inviteeId;
}