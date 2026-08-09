package com.shadowfit.controller;

import com.shadowfit.dto.admin.AdminExerciseDetailDto;
import com.shadowfit.dto.admin.AdminExerciseListItemDto;
import com.shadowfit.dto.admin.AdminExerciseSearchCondition;
import com.shadowfit.dto.admin.AdminExerciseSortKey;
import com.shadowfit.dto.admin.AnalysisSupportUpdateDto;
import com.shadowfit.dto.admin.ExerciseCreateDto;
import com.shadowfit.dto.admin.ExerciseThresholdResponseDto;
import com.shadowfit.dto.admin.ExerciseUpdateDto;
import com.shadowfit.dto.admin.ThresholdUpdateDto;
import com.shadowfit.dto.common.PageResponse;
import com.shadowfit.service.Exercise.AdminExerciseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

@Tag(name = "관리자 - 운동 종목", description = "운동 종목 CRUD · 임계값 등 운영자 전용")
@RestController
@RequestMapping("/admin/exercises")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminExerciseController {
    private final AdminExerciseService adminExerciseService;

    @Operation(summary = "운동 종목 목록 조회",
               description = "필터 2종(검색어·카테고리)의 임의 조합으로 조회한다. "
                       + "기본 정렬은 등록일 최신순이며, 페이지 크기는 최대 100 으로 제한된다.")
    @GetMapping
    public ResponseEntity<PageResponse<AdminExerciseListItemDto>> searchExercises(
            @ModelAttribute AdminExerciseSearchCondition condition,

            @Parameter(description = "정렬 키 (기본 CREATED_AT)")
            @RequestParam(defaultValue = "CREATED_AT") AdminExerciseSortKey sort,

            @Parameter(description = "오름차순 여부. 기본은 false(최신순)")
            @RequestParam(defaultValue = "false") boolean asc,

            @Parameter(description = "페이지 번호 (0부터)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "페이지 크기 (최대 100)")
            @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(
                adminExerciseService.searchExercises(condition, sort, asc, page, size));
    }

    @Operation(summary = "운동 종목 상세 조회",
               description = "수정 폼을 채우는 데 필요한 전 필드를 돌려준다. 임계값 4종도 포함 — "
                       + "값을 바꾸는 경로는 따로 있지만 보는 경로는 여기뿐이다.")
    @GetMapping("/{exerciseId}")
    public ResponseEntity<AdminExerciseDetailDto> getExercise(@PathVariable Long exerciseId) {
        return ResponseEntity.ok(adminExerciseService.getExercise(exerciseId));
    }

    @Operation(summary = "운동 종목 등록",
               description = "analysisSupported 는 서버가 false 로 고정한다 — 분석기(ai-server)가 "
                       + "붙기 전에 세션이 열리는 것을 막기 위해서다. 임계값 4종도 기본값(60/85/70/50)에서 "
                       + "시작하며, 바꾸려면 등록 후 PATCH /{id}/thresholds 를 쓴다.")
    @PostMapping
    public ResponseEntity<AdminExerciseDetailDto> createExercise(
            @Valid @RequestBody ExerciseCreateDto dto) {
        AdminExerciseDetailDto created = adminExerciseService.createExercise(dto);
        // 201 + Location. 생성된 자원의 위치를 헤더로 주는 편이 관리자 화면에서 바로 상세로
        // 이동하기 쉽다. fromPath 로 만들어 컨텍스트 경로·프록시 접두를 보존한다.
        return ResponseEntity
                .created(UriComponentsBuilder.fromPath("/admin/exercises/{id}")
                        .buildAndExpand(created.id())
                        .toUri())
                .body(created);
    }

    @Operation(summary = "운동 종목 수정",
               description = "보낸 필드만 갱신한다(부분 수정). 생략한 필드는 그대로 남는다. "
                       + "임계값과 analysisSupported 는 이 경로로 바꿀 수 없다.")
    @PatchMapping("/{exerciseId}")
    public ResponseEntity<AdminExerciseDetailDto> updateExercise(
            @PathVariable Long exerciseId,
            @Valid @RequestBody ExerciseUpdateDto dto) {
        return ResponseEntity.ok(adminExerciseService.updateExercise(exerciseId, dto));
    }

    @Operation(summary = "운동 싱크로율 임계값 변경",
               description = "4개 페르소나(초보자/고급자/다이어트/재활) 임계값을 즉시 갱신. 신규 세션부터 적용. beginner < advanced 필수.")
    @PatchMapping("/{exerciseId}/thresholds")
    public ResponseEntity<ExerciseThresholdResponseDto> updateThresholds(
            @PathVariable Long exerciseId,
            @Valid @RequestBody ThresholdUpdateDto dto) {
        return ResponseEntity.ok(adminExerciseService.updateThresholds(exerciseId, dto));
    }

    @Operation(summary = "AI 분석 활성화 여부 변경",
               description = "true 로 바꾸면 이 종목으로 세션을 시작할 수 있게 된다(W007 가드가 열린다). "
                       + "기준 좌표가 0건이면 400(W012) 으로 거부한다 — 비어 있으면 ai-server 가 "
                       + "경고만 하고 진행해 싱크로율이 전부 0 이 되기 때문이다.\n\n"
                       + "⚠️ **이 검사는 필요조건이지 충분조건이 아니다.** ai-server 는 현재 exercise_id 를 "
                       + "무시하고 무조건 squat 으로 분석한다(squat-first). 스쿼트가 아닌 종목을 켜면 "
                       + "세션은 열리지만 결과가 조용히 틀린다.")
    @PatchMapping("/{exerciseId}/analysis-support")
    public ResponseEntity<AdminExerciseDetailDto> updateAnalysisSupport(
            @PathVariable Long exerciseId,
            @Valid @RequestBody AnalysisSupportUpdateDto dto) {
        return ResponseEntity.ok(
                adminExerciseService.updateAnalysisSupport(exerciseId, dto.supported()));
    }

    @Operation(summary = "운동 종목 삭제",
               description = "기준 좌표·피드백 멘트는 FK CASCADE 로 함께 삭제된다. "
                       + "다만 이 종목으로 만들어진 세션이 한 건이라도 있으면 409(W011) 로 거부한다 — "
                       + "회원의 운동 이력은 종목을 지운다고 없앨 데이터가 아니다.")
    @DeleteMapping("/{exerciseId}")
    public ResponseEntity<Void> deleteExercise(@PathVariable Long exerciseId) {
        adminExerciseService.deleteExercise(exerciseId);
        return ResponseEntity.noContent().build();
    }
}