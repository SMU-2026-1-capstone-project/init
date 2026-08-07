package com.shadowfit.dto.admin;

import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.WorkoutLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 관리자 회원 목록 검색 조건 ({@code admin-page-scope.md} §3-A, 필터 5).
 *
 * <p>모든 필드가 nullable 이고, null 이면 그 조건을 걸지 않는다. 부분집합이 2⁵ = 32 가지라
 * 이것이 QueryDSL 채택 근거였다 ({@code querydsl-adoption.md} §1-1).
 */
@Schema(description = "관리자 회원 목록 검색 조건 (모든 항목 선택)")
public record AdminMemberSearchCondition(

        @Schema(description = "검색어 — username 또는 email 부분일치", example = "hong")
        String keyword,

        @Schema(description = "페르소나")
        SelectedPersona persona,

        @Schema(description = "운동 레벨. 온보딩 전 회원은 이 값이 없어 어떤 레벨로도 걸리지 않는다 "
                + "— 그런 회원은 onboardingCompleted=false 로 찾는다")
        WorkoutLevel workoutLevel,

        @Schema(description = "온보딩 완료 여부")
        Boolean onboardingCompleted,

        @Schema(description = "가입일 시작 (해당일 00:00:00 포함)", example = "2026-01-01")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate joinedFrom,

        @Schema(description = "가입일 종료 (해당일 23:59:59 까지 포함)", example = "2026-08-04")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate joinedTo
) {
}
