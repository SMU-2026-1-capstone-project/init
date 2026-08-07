package com.shadowfit.repository.exercise;

import com.shadowfit.dto.admin.AdminSessionListItemDto;
import com.shadowfit.dto.admin.AdminSessionSearchCondition;
import com.shadowfit.dto.admin.AdminSessionSortKey;
import com.shadowfit.dto.common.PageResponse;

/**
 * 세션 동적 조회 — QueryDSL 전용 계층.
 *
 * <p>{@link SessionRepository}(Spring Data 파생 쿼리)와 분리한 이유는 회원 쪽과 같다 —
 * 파생 쿼리는 조건이 메서드 이름에 박혀 있어 "요청마다 WHERE 절이 늘었다 줄었다" 하는 형태를
 * 표현할 수 없다. 관리자 세션 목록은 필터 4개의 부분집합 16가지가 모두 유효하다
 * ({@code admin-page-scope.md} §3-B).
 *
 * <p>기존 {@code SessionRepository} 의 조회는 전부 {@code member_id} 가 선두에 고정돼 있다
 * (내 세션, 내 활성 세션, 내 직전 동일 운동). 관리자 목록은 <b>그 조건 없이</b> 상태·기간으로
 * 훑는다 — 같은 테이블에 접근 패턴이 정반대인 읽기 주체가 생긴 것이고, 인덱스가 갈린 것도
 * 같은 뿌리다 ({@code admin-page-scope.md} §4).
 */
public interface SessionQueryRepository {

    /**
     * 관리자 세션 목록을 조건·정렬·페이징으로 조회한다.
     *
     * <p>총건수는 목록과 <b>같은 조건 메서드</b>로 세므로 건수와 내용이 어긋나지 않는다.
     */
    PageResponse<AdminSessionListItemDto> searchForAdmin(
            AdminSessionSearchCondition condition,
            AdminSessionSortKey sortKey,
            boolean ascending,
            int page,
            int size
    );
}
