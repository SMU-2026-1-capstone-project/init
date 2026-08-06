package com.shadowfit.controller;

import com.shadowfit.dto.admin.AdminStatsOverviewDto;
import com.shadowfit.service.admin.AdminStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 - 통계", description = "대시보드 통계 (운영자 전용)")
@RestController
@RequestMapping("/admin/stats")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminStatsController {

    private final AdminStatsService adminStatsService;

    @Operation(summary = "대시보드 통계 조회",
               description = "위젯 5종(오늘 세션 수·상태별 분포·오늘 평균 싱크로율·오늘 신규 가입자·활성 회원)을 "
                       + "한 번에 반환한다. 필터는 없다. "
                       + "현재는 매 요청 실시간 집계이며, 사전집계·캐시 도입 여부는 실측 후 결정한다.")
    @GetMapping("/overview")
    public ResponseEntity<AdminStatsOverviewDto> getOverview() {
        return ResponseEntity.ok(adminStatsService.getOverview());
    }
}
