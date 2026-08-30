package com.shadowfit.controller;

import com.shadowfit.dto.group.CreateInvitationRequestDto;
import com.shadowfit.dto.group.InvitationResponseDto;
import com.shadowfit.global.security.auth.CustomUserDetails;
import com.shadowfit.service.group.GroupInvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "그룹 초대", description = "그룹 초대 발송·수락·거절")
@RestController
@RequiredArgsConstructor
public class GroupInvitationController {

    private final GroupInvitationService groupInvitationService;

    @Operation(summary = "그룹 초대 발송", description = "ACTIVE 멤버 누구나 초대할 수 있다.")
    @PostMapping("/groups/{groupId}/invitations")
    public ResponseEntity<InvitationResponseDto> invite(
            @PathVariable Long groupId,
            @Valid @RequestBody CreateInvitationRequestDto request,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        InvitationResponseDto response = groupInvitationService.invite(groupId, userDetails.getMember().getId(), request);
        return ResponseEntity.status(201).body(response);
    }

    @Operation(summary = "내게 온 대기중인 초대 목록")
    @GetMapping("/invitations/mine")
    public ResponseEntity<List<InvitationResponseDto>> listMyInvitations(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(groupInvitationService.listMyInvitations(userDetails.getMember().getId()));
    }

    @Operation(summary = "초대 수락", description = "수락 시 그룹 멤버로 가입되고 MEMBER_JOINED 이벤트가 발행된다.")
    @PostMapping("/invitations/{invitationId}/accept")
    public ResponseEntity<Void> accept(
            @PathVariable Long invitationId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        groupInvitationService.accept(invitationId, userDetails.getMember().getId());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "초대 거절")
    @PostMapping("/invitations/{invitationId}/decline")
    public ResponseEntity<Void> decline(
            @PathVariable Long invitationId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        groupInvitationService.decline(invitationId, userDetails.getMember().getId());
        return ResponseEntity.ok().build();
    }
}