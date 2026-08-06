package com.shadowfit.repository.member;

import com.shadowfit.dto.admin.AdminMemberListItemDto;
import com.shadowfit.dto.admin.AdminMemberSearchCondition;
import com.shadowfit.dto.admin.AdminMemberSortKey;
import com.shadowfit.dto.common.PageResponse;

/**
 * 회원 동적 조회 — QueryDSL 전용 계층.
 *
 * <p>{@code MemberRepository}(Spring Data 파생 쿼리)와 분리한 이유는, 파생 쿼리는 조건이
 * 메서드 이름에 박혀 있어 "요청마다 WHERE 절이 늘었다 줄었다" 하는 형태를 표현할 수 없기
 * 때문이다. 관리자 회원 목록은 필터 5개의 부분집합 32가지가 모두 유효하다
 * ({@code querydsl-adoption.md} §1-1).
 */
public interface MemberQueryRepository {

    /**
     * 관리자 회원 목록을 조건·정렬·페이징으로 조회한다.
     *
     * <p>총건수는 목록과 <b>같은 조건 메서드</b>로 세므로 건수와 내용이 어긋나지 않는다.
     */
    PageResponse<AdminMemberListItemDto> searchForAdmin(
            AdminMemberSearchCondition condition,
            AdminMemberSortKey sortKey,
            boolean ascending,
            int page,
            int size
    );
}
