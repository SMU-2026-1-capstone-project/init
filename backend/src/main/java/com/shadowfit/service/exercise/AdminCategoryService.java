package com.shadowfit.service.exercise;

import com.shadowfit.dto.admin.CategoryCreateDto;
import com.shadowfit.dto.admin.CategoryResponseDto;
import com.shadowfit.dto.admin.CategoryUpdateDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.repository.exercise.CategoryRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCategoryService {

    private final CategoryRepository categoryRepository;
    private final ExercisesRepository exercisesRepository;

    /** 필터가 없다(admin-page-scope.md §3-D 와 같은 결 — 조건 0개는 QueryDSL로 안 감싼다). */
    @Transactional(readOnly = true)
    public List<CategoryResponseDto> listCategories() {
        return categoryRepository.findAll().stream()
                .map(CategoryResponseDto::fromEntity)
                .toList();
    }

    @Transactional
    public CategoryResponseDto createCategory(CategoryCreateDto dto) {
        if (categoryRepository.existsByName(dto.name())) {
            throw new BusinessException(ErrorCode.CATEGORY_NAME_DUPLICATION);
        }

        Category saved = categoryRepository.save(Category.builder().name(dto.name()).build());
        log.info("카테고리 등록: id={}, name={}", saved.getId(), saved.getName());
        return CategoryResponseDto.fromEntity(saved);
    }

    @Transactional
    public CategoryResponseDto updateCategory(Long categoryId, CategoryUpdateDto dto) {
        Category category = findOrThrow(categoryId);

        // 자기 자신과 같은 이름으로 "수정"하는 것은 중복이 아니다 — existsByName 이 그 케이스까지
        // 걸면 이름을 안 바꾸는 PATCH 조차 409 가 난다.
        if (!category.getName().equals(dto.name()) && categoryRepository.existsByName(dto.name())) {
            throw new BusinessException(ErrorCode.CATEGORY_NAME_DUPLICATION);
        }

        category.rename(dto.name());
        log.info("카테고리 수정: id={}, name={}", categoryId, category.getName());
        return CategoryResponseDto.fromEntity(category);
    }

    @Transactional
    public void deleteCategory(Long categoryId) {
        Category category = findOrThrow(categoryId);

        // FK(RESTRICT)가 어차피 막지만, 그러면 DataIntegrityViolationException → 500 이 나간다.
        // EXERCISE_DELETE_NOT_ALLOWED(W011)와 같은 이유로 여기서 먼저 걸러 409 로 답한다.
        if (exercisesRepository.existsByCategoryId(categoryId)) {
            throw new BusinessException(ErrorCode.CATEGORY_IN_USE);
        }

        categoryRepository.delete(category);
        log.info("카테고리 삭제: id={}, name={}", categoryId, category.getName());
    }

    private Category findOrThrow(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND));
    }
}
