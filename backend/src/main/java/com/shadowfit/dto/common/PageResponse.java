package com.shadowfit.dto.common;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 관리자 목록 공통 페이징 응답.
 *
 * <p><b>offset 페이징을 쓰는 이유</b> — 관리자 화면은 "5페이지로 점프", "전체 1,234건 중"
 * 같은 총건수·임의 페이지 이동을 기대한다. keyset 페이징은 이걸 구조적으로 못 한다
 * ({@code admin-page-scope.md} §5).
 *
 * <p>✅ <b>2026-08-07 확정</b> — 관리자 프론트가 <b>별도 웹 + 페이지 번호</b>로 정해져
 * 이 설계의 전제가 참이 됐다({@code admin-page-scope.md} §5-1). 그 전까지 이 클래스는
 * "무한 스크롤로 뒤집히면 keyset 을 얹는다"는 잠정 상태였고, 그 갈래는 사라졌다.
 * 따라서 {@code totalElements}·{@code totalPages} 는 <b>성능상 감수한 비용이 아니라
 * UI 요구사항</b>이다 — 페이지 번호를 그리려면 있어야 한다.
 *
 * <p>keyset 은 관리자가 아니라 <b>리포트 히스토리·캘린더</b>(모바일 무한 스크롤)에서 다룬다.
 * 그쪽은 {@code (member_id, status, start_time)} 통합 인덱스를 그대로 타는 자리다
 * ({@code session-index-composition.md}).
 *
 * <p><b>총건수를 목록과 같은 조건으로 세는 이유</b> — 조건을 따로 짜면 건수와 목록이
 * 어긋난다. QueryDSL 로 조건 메서드를 만들어 목록 쿼리와 count 쿼리가 같은 것을 재사용한다
 * ({@code admin-page-scope.md} §7).
 */
@Schema(description = "페이징 목록 응답")
public record PageResponse<T>(
        @Schema(description = "현재 페이지 내용") List<T> content,
        @Schema(description = "현재 페이지 번호 (0부터)", example = "0") int page,
        @Schema(description = "페이지 크기", example = "20") int size,
        @Schema(description = "조건에 맞는 전체 건수", example = "1234") long totalElements,
        @Schema(description = "전체 페이지 수", example = "62") int totalPages
) {
    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        // size 가 0 이면 나눗셈이 터진다. 컨트롤러에서 검증하지만 여기서도 막는다.
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }
}
