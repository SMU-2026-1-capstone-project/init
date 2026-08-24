package com.shadowfit.controller;

import com.shadowfit.dto.admin.AdminMemberListItemDto;
import com.shadowfit.dto.admin.AdminMemberSearchCondition;
import com.shadowfit.dto.admin.AdminMemberSortKey;
import com.shadowfit.dto.common.PageResponse;
import com.shadowfit.service.member.AdminMemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 - 회원", description = "회원 목록 조회 (운영자 전용)")
@RestController
@RequestMapping("/admin/members")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminMemberController {

    private final AdminMemberService adminMemberService;

    @Operation(summary = "회원 목록 조회",
               description = "필터 5종(검색어·페르소나·운동레벨·온보딩여부·가입일범위)의 임의 조합으로 조회한다. "
                       + "기본 정렬은 가입일 최신순이며, 페이지 크기는 최대 100 으로 제한된다.")
    @GetMapping
    public ResponseEntity<PageResponse<AdminMemberListItemDto>> searchMembers(
            @ModelAttribute AdminMemberSearchCondition condition,

            @Parameter(description = "정렬 키 (기본 CREATED_AT)")
            @RequestParam(defaultValue = "CREATED_AT") AdminMemberSortKey sort,

            @Parameter(description = "오름차순 여부. 기본은 false(최신순)")
            @RequestParam(defaultValue = "false") boolean asc,

            @Parameter(description = "페이지 번호 (0부터)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "페이지 크기 (최대 100)")
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(
                adminMemberService.searchMembers(condition, sort, asc, page, size));
    }
}
