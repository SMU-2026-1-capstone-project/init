package com.shadowfit.controller;

import com.shadowfit.dto.login.LoginRequestDto;
import com.shadowfit.dto.login.LoginResponseDto;
import com.shadowfit.dto.login.MemberRequestDto;
import com.shadowfit.dto.login.ReissueRequestDto;
import com.shadowfit.dto.onboarding.OnboardingDto;
import com.shadowfit.dto.onboarding.OnboardingRequestDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.service.member.MemberService;
import com.shadowfit.service.member.OnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name="인증/인가/온보딩", description="로그인/회원가입/회원정보수정")
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/member")
public class MemberController {
    private final MemberService memberService;
    private final OnboardingService onboardingService;
    @Operation(summary="로그인",description = "로그인을 할 수 있음")
    @PostMapping("/login")
    public ResponseEntity<LoginResponseDto> getMemberProfile(
            @Valid @RequestBody LoginRequestDto request){
        LoginResponseDto response = memberService.login(request);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @Operation(summary="토큰 재발급",
            description = "리프레시 토큰으로 액세스·리프레시 토큰을 재발급한다 (이슈 #135). "
                    + "액세스 토큰이 만료된 상태로 호출되는 것이 정상 경로라 인증을 요구하지 않는다 — "
                    + "신원은 리프레시 토큰 자신의 서명에서 나온다.")
    @PostMapping("/reissue")
    public ResponseEntity<LoginResponseDto> reissue(@Valid @RequestBody ReissueRequestDto dto){
        return ResponseEntity.ok(memberService.reissue(dto));
    }

    @Operation(summary="로그아웃",
            description = "요청자의 리프레시 토큰을 폐기한다. **요청 본문이 없다** — 지울 대상은 "
                    + "인증된 본인이고(decisions/token-lifecycle.md §1-1-ㄴ), 액세스 토큰은 "
                    + "블랙리스트를 두지 않으므로(#137 ㄴ-4) 남은 수명 동안 유효하다.")
    @PostMapping("/logout")
    public ResponseEntity<Void> Logout(@AuthenticationPrincipal CustomUserDetails userDetails){
        // 본문(accessToken·refreshToken)을 받던 것을 없앴다. 요청자 기준으로 바꾼 시점에
        // refreshToken 은 이미 안 쓰였고, accessToken 은 블랙리스트 등록에만 쓰이다가 #137 로
        // 그 자리마저 사라졌다 — 아무 데도 안 쓰는 값을 @NotBlank 로 요구하고 있던 셈이다.
        memberService.logout(userDetails.getMember().getEmail());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary="회원가입",description = "회원가입을 할 수 있음")
    @PostMapping("/signup")
    public ResponseEntity<String> signup(@Valid @RequestBody MemberRequestDto dto) {
        //Member entity = modelMapper.map(member, Member.class);
        String id = memberService.signup(dto);
        return ResponseEntity.status(HttpStatus.OK).body(id);
    }

    @Operation(summary="회원탈퇴", description = "회원탈퇴를 할 수 있음 (본인만)")
    @DeleteMapping("/{email}")
    public ResponseEntity<Void> deleteMember(@PathVariable("email") String email,
                                              @AuthenticationPrincipal CustomUserDetails userDetails){
        requireSelf(email, userDetails);
        memberService.deleteAccount(email);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary="회원정보조회", description = "온보딩정보를 열람 할 수 있음 (본인만)")
    @GetMapping("/onboarding/{email}")
    public ResponseEntity<OnboardingDto> getOnboarding(@PathVariable("email") String email,
                                                        @AuthenticationPrincipal CustomUserDetails userDetails){
        requireSelf(email, userDetails);
        OnboardingDto response = onboardingService.readOnboarding(email);
        return ResponseEntity.ok(response);
    }

    @Operation(summary="온보딩 단계별 저장", description = "온보딩을 수정할 수 있음 (본인만)")
    @PatchMapping("/onboarding/{email}")
    public ResponseEntity<OnboardingDto> updateOnboarding(@PathVariable("email") String email,
                                                          @Valid @RequestBody OnboardingRequestDto dto,
                                                          @AuthenticationPrincipal CustomUserDetails userDetails){
        requireSelf(email, userDetails);
        OnboardingDto response = onboardingService.updateOnboarding(email,dto);
        return ResponseEntity.ok(response);

    }

    // 경로의 email을 신뢰하지 않고 인증된 본인인지 확인 (IDOR 방지)
    private void requireSelf(String pathEmail, CustomUserDetails userDetails) {
        if (!userDetails.getMember().getEmail().equals(pathEmail)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

}
