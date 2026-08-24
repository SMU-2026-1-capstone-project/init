package com.shadowfit.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자 운동 목록 검색 조건 ({@code admin-page-scope.md} §3-C, 필터 2).
 *
 * <p>모든 필드가 nullable 이고, null 이면 그 조건을 걸지 않는다 — A(회원)·B(세션)와 같은 규약이다.
 *
 * <p><b>필터가 2개뿐인데 QueryDSL 을 쓰는 이유</b>는 성능이 아니라 일관성이다. 이 화면이 거는
 * {@code exercises} 는 현재 3행(스쿼트·런지·플랭크)뿐이라 어떤 방식을 써도 비용 차이가 0 이다.
 * 그래서 판단축이 "동적 조회 도구를 둘로 늘리지 않는다"만 남았고, {@code Specification} 후보는
 * 폐기됐다 ({@code querydsl-adoption.md} §6-1, 2026-07-31 확정).
 */
@Schema(description = "관리자 운동 목록 검색 조건 (모든 항목 선택)")
public record AdminExerciseSearchCondition(

        @Schema(description = "검색어 — 운동명 부분일치", example = "스쿼")
        String keyword,

        @Schema(description = "운동 부위 카테고리 ID")
        Long categoryId
) {
}