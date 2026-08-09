package com.shadowfit.controller;

import com.shadowfit.dto.login.LogOutRequestDto;
import com.shadowfit.dto.login.LoginRequestDto;
import com.shadowfit.dto.login.LoginResponseDto;
import com.shadowfit.dto.login.MemberRequestDto;
import com.shadowfit.dto.login.ReissueRequestDto;
import com.shadowfit.dto.onboarding.OnboardingDto;
import com.shadowfit.dto.onboarding.OnboardingRequestDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.service.Member.MemberService;
import com.shadowfit.service.Member.OnboardingService;
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

    @Operation(summary="로그아웃",description = "로그아웃을 할 수 있음")
    @PostMapping("/logout")
    public ResponseEntity<Void> Logout(@Valid @RequestBody LogOutRequestDto dto,
                                       @AuthenticationPrincipal CustomUserDetails userDetails){
        // 지울 대상을 **본문의 refresh token 이 아니라 인증된 본인**으로 정한다
        // (decisions/token-lifecycle.md §1-1-ㄴ). 재발급(§4-2)과는 사정이 반대다 — 이쪽은
        // 인증을 통과해서 요청자가 이미 있는데도 본문을 믿고 있었다.
        memberService.logout(dto, userDetails.getMember().getEmail());
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
