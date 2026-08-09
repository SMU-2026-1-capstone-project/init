package com.shadowfit.repository.exercise;

import com.shadowfit.dto.admin.AdminExerciseListItemDto;
import com.shadowfit.dto.admin.AdminExerciseSearchCondition;
import com.shadowfit.dto.admin.AdminExerciseSortKey;
import com.shadowfit.dto.common.PageResponse;

/**
 * 운동 종목 동적 조회 — QueryDSL 전용 계층.
 *
 * <p>{@link ExercisesRepository}(Spring Data)와 분리한 이유는 {@code MemberQueryRepository} 와
 * 같다: 파생 쿼리는 조건이 메서드 이름에 박혀 있어 "요청마다 WHERE 절이 늘었다 줄었다" 하는
 * 형태를 표현할 수 없다.
 *
 * <p>다만 <b>근거의 무게는 회원 목록과 다르다.</b> 저쪽은 필터 5개의 부분집합 32가지가 근거였고
 * 여기는 2개(4가지)뿐이다. 이 자리의 판단축은 성능이 아니라 "동적 조회 도구를 둘로 늘리지
 * 않는다"이며, 그래서 {@code Specification} 후보가 폐기됐다
 * ({@code querydsl-adoption.md} §6-1, {@code admin-page-scope.md} §3-C).
 */
public interface ExerciseQueryRepository {

    /**
     * 관리자 운동 목록을 조건·정렬·페이징으로 조회한다.
     *
     * <p>총건수는 목록과 <b>같은 조건 메서드</b>로 세므로 건수와 내용이 어긋나지 않는다
     * ({@code admin-page-scope.md} §7).
     */
    PageResponse<AdminExerciseListItemDto> searchForAdmin(
            AdminExerciseSearchCondition condition,
            AdminExerciseSortKey sortKey,
            boolean ascending,
            int page,
            int size
    );
}