package com.shadowfit.dto.admin;

import com.shadowfit.model.exercise.Status;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 관리자 세션 목록 검색 조건 ({@code admin-page-scope.md} §3-B, 필터 4).
 *
 * <p>회원 목록({@link AdminMemberSearchCondition})과 같은 규칙이다 — 모든 필드가 nullable 이고
 * null 이면 그 조건을 걸지 않는다. 부분집합이 2⁴ = 16 가지다.
 *
 * <p><b>회원 목록과 다른 점</b> — {@code keyword} 가 <b>조인 너머의 컬럼</b>을 가리킨다.
 * 세션을 읽으면서 {@code users.username} 으로 거르는 형태라, 옵티마이저가 어느 테이블부터
 * 읽을지(드라이빙 테이블)를 고르게 된다. 회원 목록에는 없던 변수이고 코드만 봐서는 알 수 없다
 * ({@code admin-page-scope.md} §4-4 에서 측정).
 *
 * <p>🔶 싱크로율 구간 필터는 넣지 않았다 — §3-B 에서 "필수 아님"으로 열어둔 항목이다.
 */
@Schema(description = "관리자 세션 목록 검색 조건 (모든 항목 선택)")
public record AdminSessionSearchCondition(

        @Schema(description = "세션 상태. CANCELLED 는 대입하는 코드가 없어 항상 0건이다 "
                + "— 사용자 취소가 API 표면에 없다 (죽은 상태, 존치 여부 미결)")
        Status status,

        @Schema(description = "운동 종목 ID", example = "1")
        Long exerciseId,

        @Schema(description = "시작일 (해당일 00:00:00 포함)", example = "2026-01-01")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate startedFrom,

        @Schema(description = "종료일 (해당일 23:59:59 까지 포함)", example = "2026-08-04")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate startedTo,

        @Schema(description = "회원 검색어 — username 또는 email 부분일치", example = "hong")
        String keyword
) {
}
