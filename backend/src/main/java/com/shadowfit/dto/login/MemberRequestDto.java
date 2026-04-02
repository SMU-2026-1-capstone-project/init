package com.shadowfit.dto.login;

import com.shadowfit.model.member.Sex;
import com.shadowfit.model.member.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

//회원가입용 Dto
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "회원가입 req dto")
public class MemberRequestDto {
    @Schema(description = "ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message="ID는 필수 입력 값입니다.")
    private String userId;  // 화면 내 아이디 표시

    @Schema(description = "Email", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message="Email는 필수 입력 값입니다.")
    private String email;

    @Schema(description = "PASSWORD", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message="PASSWORD는 필수 입력 값입니다.")
    private String password;

    @Schema(description = "성별", example = "MALE",requiredMode = Schema.RequiredMode.REQUIRED)
    private Sex sex;

    @Schema(description = "사용자 권한", example = "USER",requiredMode = Schema.RequiredMode.REQUIRED)
    private UserRole role;
}
