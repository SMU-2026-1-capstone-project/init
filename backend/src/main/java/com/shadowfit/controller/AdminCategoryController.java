package com.shadowfit.controller;

import com.shadowfit.dto.admin.CategoryCreateDto;
import com.shadowfit.dto.admin.CategoryResponseDto;
import com.shadowfit.dto.admin.CategoryUpdateDto;
import com.shadowfit.service.Exercise.AdminCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "관리자 - 카테고리", description = "운동 부위 카테고리 CRUD (BE-04)")
@RestController
@RequestMapping("/admin/categories")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminCategoryController {

    private final AdminCategoryService adminCategoryService;

    @Operation(summary = "카테고리 목록 조회", description = "필터·페이징 없음 — 전체를 반환한다")
    @GetMapping
    public ResponseEntity<List<CategoryResponseDto>> listCategories() {
        return ResponseEntity.ok(adminCategoryService.listCategories());
    }

    @Operation(summary = "카테고리 등록", description = "이름이 중복되면 409")
    @PostMapping
    public ResponseEntity<CategoryResponseDto> createCategory(@Valid @RequestBody CategoryCreateDto dto) {
        CategoryResponseDto response = adminCategoryService.createCategory(dto);
        return ResponseEntity.status(201).body(response);
    }

    @Operation(summary = "카테고리 이름 수정")
    @PatchMapping("/{categoryId}")
    public ResponseEntity<CategoryResponseDto> updateCategory(
            @PathVariable Long categoryId,
            @Valid @RequestBody CategoryUpdateDto dto
    ) {
        return ResponseEntity.ok(adminCategoryService.updateCategory(categoryId, dto));
    }

    @Operation(summary = "카테고리 삭제", description = "이 카테고리를 쓰는 운동 종목이 있으면 409")
    @DeleteMapping("/{categoryId}")
    public ResponseEntity<Void> deleteCategory(@PathVariable Long categoryId) {
        adminCategoryService.deleteCategory(categoryId);
        return ResponseEntity.noContent().build();
    }
}
