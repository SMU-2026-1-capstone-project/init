package com.shadowfit.dto.login;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.shadowfit.model.member.Sex;
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
@JsonIgnoreProperties(ignoreUnknown = true)   // 구버전 앱이 보내는 role 을 거부하지 않고 버린다 (#138)
public class MemberRequestDto {
    @Schema(description = "사용자 아이디 (화면 표시용 고유 식별자)", example = "shadow_fit_01", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message="ID는 필수 입력 값입니다.")
    private String username;  // 화면 내 아이디 표시

    @Schema(description = "Email", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message="Email는 필수 입력 값입니다.")
    private String email;

    @Schema(description = "PASSWORD", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message="PASSWORD는 필수 입력 값입니다.")
    private String password;

    @Schema(description = "성별", example = "MALE",requiredMode = Schema.RequiredMode.REQUIRED)
    private Sex sex;

    // role 은 여기에 없다 — 있으면 안 된다 (이슈 #138).
    //
    // 이 엔드포인트는 permitAll(application.yml 의 security.whitelist)이라, 필드를 두는 것은
    // "권한을 요청자가 정한다"와 같다. 실제로 role:"ADMIN" 으로 가입하면 관리자가 됐다 —
    // CustomUserDetails:25 가 "ROLE_"+role 로 권한을 만들고 @PreAuthorize 가 그것을 믿기 때문에,
    // 가드 5곳이 정상 동작하면서도 통째로 무의미해지는 형태였다.
    //
    // 서버가 UserRole.USER 로 고정한다. 그 고정을 보장하는 것은 검증 로직이 아니라
    // "여기 필드가 없다"는 사실 하나다 — 누가 필드를 되살리면 그 순간 다시 뚫린다.
    // MemberServiceTest.signup_ignoresClientSuppliedRole 과
    // MemberControllerIntegrationTest.signup_withAdminRole_cannotAccessAdminApi 가 그것을 잡는다.
    //
    // 이 클래스의 @JsonIgnoreProperties(ignoreUnknown = true) 가 필요한 이유:
    // 프론트가 아직 role:"USER" 를 보내고 있어서(frontend/app/(auth)/login.tsx), 필드만 지우면
    // 기존 앱의 회원가입이 통째로 깨진다. 실제로 이 프로젝트에서는 알 수 없는 필드가 무시되지
    // 않고 HttpMessageNotReadableException 으로 거부됐다 — 테스트로 확인했다(500 응답).
    // 전역 설정에 기대지 않고 이 DTO 에 못박는 이유는, 다른 코드가 반대로 "엄격함"에 기대고
    // 있기 때문이다(ReportService:86 주석). 전역을 풀면 그쪽이 조용히 깨진다.
    //
    // 관리자가 되는 정식 경로는 별도 신청·심사 테이블로 만든다 (decisions/admin-role-provisioning.md
    // §6 확정 ②). 그때까지 관리자 생성은 수동 SQL 뿐이다.
}
