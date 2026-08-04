package com.shadowfit.controller;

import com.shadowfit.dto.admin.AdminSessionListItemDto;
import com.shadowfit.dto.admin.AdminSessionSearchCondition;
import com.shadowfit.dto.admin.AdminSessionSortKey;
import com.shadowfit.dto.common.PageResponse;
import com.shadowfit.service.Exercise.AdminSessionService;
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

@Tag(name = "관리자 - 세션", description = "운동 세션 목록 조회 (운영자 전용)")
@RestController
@RequestMapping("/admin/sessions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminSessionController {

    private final AdminSessionService adminSessionService;

    @Operation(summary = "세션 목록 조회",
               description = "필터 4종(상태·운동종목·기간·회원검색어)의 임의 조합으로 조회한다. "
                       + "기본 정렬은 시작시각 최신순이며, 페이지 크기는 최대 100 으로 제한된다.")
    @GetMapping
    public ResponseEntity<PageResponse<AdminSessionListItemDto>> searchSessions(
            @ModelAttribute AdminSessionSearchCondition condition,

            @Parameter(description = "정렬 키 (기본 START_TIME)")
            @RequestParam(defaultValue = "START_TIME") AdminSessionSortKey sort,

            @Parameter(description = "오름차순 여부. 기본은 false(최신순)")
            @RequestParam(defaultValue = "false") boolean asc,

            @Parameter(description = "페이지 번호 (0부터)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "페이지 크기 (최대 100)")
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(
                adminSessionService.searchSessions(condition, sort, asc, page, size));
    }
}
