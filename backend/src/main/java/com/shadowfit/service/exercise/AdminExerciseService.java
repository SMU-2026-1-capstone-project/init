package com.shadowfit.service.exercise;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.admin.AdminExerciseDetailDto;
import com.shadowfit.dto.admin.AdminExerciseListItemDto;
import com.shadowfit.dto.admin.AdminExerciseSearchCondition;
import com.shadowfit.dto.admin.AdminExerciseSortKey;
import com.shadowfit.dto.admin.ExerciseCreateDto;
import com.shadowfit.dto.admin.ExerciseThresholdResponseDto;
import com.shadowfit.dto.admin.ExerciseUpdateDto;
import com.shadowfit.dto.admin.ThresholdUpdateDto;
import com.shadowfit.dto.common.PageResponse;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.repository.exercise.CategoryRepository;
import com.shadowfit.repository.exercise.ExerciseQueryRepository;
import com.shadowfit.repository.exercise.ExerciseReferenceRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminExerciseService {

    /** 페이지 크기 상한. 없으면 size=1000000 한 방으로 전체를 긁어갈 수 있다 (AdminMemberService 와 동일). */
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    private final ExercisesRepository exercisesRepository;
    private final ExerciseQueryRepository exerciseQueryRepository;
    private final SessionRepository sessionRepository;
    private final ExerciseReferenceRepository exerciseReferenceRepository;
    private final CategoryRepository categoryRepository;
    private final ObjectMapper objectMapper;

    // ─── 조회 ──────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<AdminExerciseListItemDto> searchExercises(
            AdminExerciseSearchCondition condition,
            AdminExerciseSortKey sortKey,
            boolean ascending,
            int page,
            int size
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = normalizeSize(size);
        return exerciseQueryRepository.searchForAdmin(condition, sortKey, ascending, safePage, safeSize);
    }

    /**
     * 운동 상세.
     *
     * <p>캐시된 {@code findByIdCached} 가 아니라 {@code findById} 를 쓴다. 관리자 상세는 방금
     * 수정한 값을 확인하는 자리라 최대 1시간 낡은 값을 보여주면 "저장이 안 됐다"로 읽힌다.
     */
    @Transactional(readOnly = true)
    public AdminExerciseDetailDto getExercise(Long exerciseId) {
        return AdminExerciseDetailDto.fromEntity(findOrThrow(exerciseId));
    }

    // ─── 등록 ──────────────────────────────────────────────────────────────────────

    /**
     * 운동 종목 등록.
     *
     * <p><b>{@code analysisSupported} 는 서버가 {@code false} 로 고정한다</b> — 엔티티 기본값을
     * 그대로 쓰고 DTO 에 받는 자리를 두지 않는다({@link ExerciseCreateDto} 주석). 임계값 4종도
     * 같은 이유로 기본값(60/85/70/50)에서 시작한다.
     *
     * <p>캐시 무효화가 없는 것은 의도다 — 새 id 는 {@code exercises} 캐시에 있을 수 없다.
     */
    @Transactional
    public AdminExerciseDetailDto createExercise(ExerciseCreateDto dto) {
        validateJsonOrThrow(dto.targetJoints());

        Category category = findCategoryOrThrow(dto.categoryId());

        Exercise exercise = Exercise.builder()
                .name(dto.name())
                .category(category)
                .description(dto.description())
                .preferredUrl(dto.preferredUrl())
                .targetJoints(dto.targetJoints())
                .build();

        // 생략되면 엔티티의 @Builder.Default(15)가 그대로 남는다. 빌더에 null 을 넘기면 기본값을
        // 덮어써 NOT NULL 위반이 되므로, 값이 있을 때만 설정한다.
        exercise.applyExpectedDuration(dto.expectedDurationMinutes());

        Exercise saved = exercisesRepository.save(exercise);
        log.info("운동 종목 등록: id={}, name={}, category={} (analysisSupported=false 고정)",
                saved.getId(), saved.getName(), category.getName());

        return AdminExerciseDetailDto.fromEntity(saved);
    }

    // ─── 수정 ──────────────────────────────────────────────────────────────────────

    /**
     * 운동 종목 수정 — 보낸 필드만 갱신한다({@link ExerciseUpdateDto} 주석).
     *
     * <p>{@code findById}(캐시 미적용) 유지 — 캐시된 {@code findByIdCached} 는 detached 엔티티를
     * 반환해 아래 setter 가 dirty-checking 에 안 잡히고 <b>조용히 무시된다</b>.
     * {@code updateThresholds} 와 같은 이유이고, 같은 이유로 {@code @CacheEvict} 도 함께 건다.
     */
    @Transactional
    @CacheEvict(cacheNames = "exercises", key = "#exerciseId")
    public AdminExerciseDetailDto updateExercise(Long exerciseId, ExerciseUpdateDto dto) {
        validateJsonOrThrow(dto.targetJoints());

        Exercise exercise = findOrThrow(exerciseId);

        // name·category 는 DB NOT NULL 이라 "안 보내는 것"은 허용하고 "보낸 값이 빈 것"만 막는다.
        // 이 검증은 엔티티가 아니라 서비스 몫이다(applyUpdate 의 null 체크와 성격이 다르다).
        if (dto.name() != null && !StringUtils.hasText(dto.name())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        // categoryId 가 안 왔으면(null) 카테고리를 안 바꾼다 — applyUpdate 의 null=유지 규약과
        // 같은 자리라, 여기서 조회를 건너뛴다(조회 자체가 categoryId 필수인 findCategoryOrThrow
        // 를 안 타야 "생략 = 유지"가 성립한다).
        Category category = dto.categoryId() == null ? null : findCategoryOrThrow(dto.categoryId());

        exercise.applyUpdate(dto.name(), category, dto.description(),
                dto.preferredUrl(), dto.targetJoints(), dto.expectedDurationMinutes());

        log.info("운동 종목 수정: id={}, name={}", exerciseId, exercise.getName());
        return AdminExerciseDetailDto.fromEntity(exercise);
    }

    // findById(캐시 미적용) 유지 — 캐시된 findByIdCached는 detached 엔티티를 반환해
    // 아래 setter가 dirty-checking에 안 잡히고 조용히 무시됨. evict만 캐시에 반영.
    @Transactional
    @CacheEvict(cacheNames = "exercises", key = "#exerciseId")
    public ExerciseThresholdResponseDto updateThresholds(Long exerciseId, ThresholdUpdateDto dto) {
        // beginner < advanced만 검증. diet/rehab은 숙련도 축이 아니라 목적(체중감량/안전)이 달라
        // beginner·advanced와 순서 관계를 강제할 이유가 없음 — 개별 범위(0~100)만 DTO에서 검증.
        if (dto.beginner().compareTo(dto.advanced()) >= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        Exercise exercise = exercisesRepository.findById(exerciseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXERCISE_NOT_FOUND));

        log.info("운동 {} 임계값 변경: beginner {} -> {}, advanced {} -> {}, diet {} -> {}, rehab {} -> {}",
                exerciseId,
                exercise.getSyncThresholdBeginner(), dto.beginner(),
                exercise.getSyncThresholdAdvanced(), dto.advanced(),
                exercise.getSyncThresholdDiet(), dto.diet(),
                exercise.getSyncThresholdRehab(), dto.rehab());

        exercise.updateThresholds(dto.beginner(), dto.advanced(), dto.diet(), dto.rehab());

        return ExerciseThresholdResponseDto.fromEntity(exercise);
    }

    // ─── 분석 활성화 ────────────────────────────────────────────────────────────────

    /**
     * AI 분석 활성화 여부 변경 — {@code exercises.analysis_supported}.
     *
     * <p>이 값이 {@code true} 가 되면 {@code SessionService.createSession} 의 W007 가드가 열려
     * <b>세션이 실제로 시작된다</b>. 그래서 등록·수정 DTO 에서 빼고 전용 경로로 분리했다
     * ({@link com.shadowfit.dto.admin.AnalysisSupportUpdateDto} 주석).
     *
     * <p><b>@CacheEvict 가 여기서는 선택이 아니다.</b> 이 플래그를 읽는 것이 바로
     * {@code SessionService.createSession} 의 {@code findByIdCached}
     * ({@code SessionService.java:107})다 — Caffeine {@code expireAfterWrite=1h} 라 evict 가
     * 없으면 <b>켜도 최대 1시간 동안 W007 로 계속 막히고, 꺼도 1시간 동안 계속 열린다.</b>
     * 뒤쪽이 특히 나쁘다.
     *
     * <h4>켤 때만 가드를 건다</h4>
     * 기준 좌표({@code exercise_references})가 0건이면 거부한다(W012). 그것이 분석의 실제
     * 입력이고, 비어 있으면 ai-server 가 <b>경고만 하고 진행해</b> {@code sync_rate} 가 전부 0 이
     * 되기 때문이다({@code exercise_servicer.py:78-82}). 끄는 방향은 안전하므로 가드가 없다.
     *
     * <h4>⚠️ 이 가드는 필요조건이지 충분조건이 아니다</h4>
     * <b>ai-server 는 {@code exercise_id} 를 받아놓고 무시한다</b> —
     * {@code exercise_servicer.py:73}·{@code :126} 이 {@code exercise_type = "squat"} 로
     * 하드코딩돼 있고 분석기도 {@code squat_analyzer.py} 하나뿐이다(squat-first). 따라서 런지에
     * 이 플래그를 켜면 세션은 열리고 <b>런지 동작이 스쿼트 기준으로 채점된다</b> — 에러가 아니라
     * 조용히 틀린 점수가 나온다. Spring 에서는 이것을 검증할 수단이 없어(AI 의 지원 종목을 묻는
     * RPC 가 없다) 막지 않고 경고 로그로 남긴다. 이슈로 등록해 뒀다.
     */
    @Transactional
    @CacheEvict(cacheNames = "exercises", key = "#exerciseId")
    public AdminExerciseDetailDto updateAnalysisSupport(Long exerciseId, boolean supported) {
        Exercise exercise = findOrThrow(exerciseId);

        if (supported && !exerciseReferenceRepository.existsByExerciseId(exerciseId)) {
            log.warn("분석 활성화 거부 — 기준 좌표 0건: id={}, name={}", exerciseId, exercise.getName());
            throw new BusinessException(ErrorCode.EXERCISE_ANALYSIS_ENABLE_BLOCKED);
        }

        boolean before = exercise.changeAnalysisSupport(supported);

        if (supported && !before) {
            // 켜는 것만 WARN 이다. ai-server 가 종목과 무관하게 스쿼트로 분석하므로, 스쿼트가
            // 아닌 종목을 켠 사실은 나중에 "점수가 이상하다"를 추적할 때 필요한 단서가 된다.
            log.warn("분석 활성화: id={}, name={} — ai-server 는 exercise_id 를 무시하고 squat 으로 "
                    + "분석한다(exercise_servicer.py:73). 스쿼트가 아니면 결과가 조용히 틀린다.",
                    exerciseId, exercise.getName());
        } else {
            log.info("분석 활성화 여부 변경: id={}, name={}, {} -> {}",
                    exerciseId, exercise.getName(), before, supported);
        }

        return AdminExerciseDetailDto.fromEntity(exercise);
    }

    // ─── 삭제 ──────────────────────────────────────────────────────────────────────

    /**
     * 운동 종목 삭제 — <b>하드 삭제</b>. 세션 이력이 있으면 거부한다(W011).
     *
     * <p>딸린 데이터의 운명이 FK 정의에 따라 갈린다({@code V1__baseline.sql}):
     * <ul>
     *   <li>{@code exercise_references}(:83)·{@code exercise_feedback_templates}(:278) —
     *       {@code ON DELETE CASCADE} 라 <b>같이 지워진다</b>. 기준 좌표와 피드백 멘트는 종목에
     *       종속된 부속물이라 종목이 사라지면 의미가 없다</li>
     *   <li>{@code exercise_sessions}(:110) — CASCADE 가 <b>없다</b>. 회원의 운동 이력이라
     *       종목을 지운다고 없애도 되는 데이터가 아니다</li>
     * </ul>
     * 즉 "이력이 있으면 못 지운다"는 이 메서드가 새로 만든 규칙이 아니라 <b>스키마가 이미 내린
     * 결정</b>이고, 아래 가드는 그걸 500 대신 409 로 옮긴다.
     *
     * <p>⚠️ <b>검사와 삭제 사이 레이스가 남는다.</b> {@code exists} 가 false 를 준 직후 누군가
     * 그 종목으로 세션을 시작하면 DELETE 가 FK 제약에 걸린다. 그걸 막는 것이 아니라 <b>받아서
     * 같은 409 로 바꾼다</b> — 잠금으로 막으려면 종목 행을 잠근 채 세션 생성 경로 전체와
     * 직렬화해야 하는데, 그건 3행짜리 마스터 테이블 삭제가 살 비용이 아니다. 결과적으로
     * 사용자에게 보이는 답은 두 경로가 같다.
     *
     * <p>{@code DataIntegrityViolationException} 을 안 받으면 {@code GlobalExceptionHandler} 의
     * {@code Exception} 핸들러로 떨어져 <b>409 대신 500</b> 이 나간다 — 그 클래스의 버그가 이
     * 코드베이스에서 이미 세 번 있었다(403→500, 400→500, 404→500. 같은 파일 주석 참고).
     */
    @Transactional
    @CacheEvict(cacheNames = "exercises", key = "#exerciseId")
    public void deleteExercise(Long exerciseId) {
        Exercise exercise = findOrThrow(exerciseId);

        if (sessionRepository.existsByExerciseId(exerciseId)) {
            log.warn("운동 종목 삭제 거부 — 세션 이력 존재: id={}, name={}", exerciseId, exercise.getName());
            throw new BusinessException(ErrorCode.EXERCISE_DELETE_NOT_ALLOWED);
        }

        try {
            exercisesRepository.delete(exercise);
            // 제약 위반은 flush 시점에 터진다. 트랜잭션이 끝난 뒤 나면 이 try 를 빠져나간 뒤라
            // 여기서 잡을 수 없으므로 명시적으로 flush 해서 경계를 이 안으로 당긴다.
            exercisesRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 위 exists 검사와 여기 사이에 세션이 생긴 경우 (클래스 주석의 레이스).
            log.warn("운동 종목 삭제 실패 — 검사 후 참조가 생김: id={}", exerciseId, e);
            throw new BusinessException(ErrorCode.EXERCISE_DELETE_NOT_ALLOWED);
        }

        log.info("운동 종목 삭제: id={}, name={}", exerciseId, exercise.getName());
    }

    // ─── 공통 ──────────────────────────────────────────────────────────────────────

    private Exercise findOrThrow(Long exerciseId) {
        return exercisesRepository.findById(exerciseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXERCISE_NOT_FOUND));
    }

    private Category findCategoryOrThrow(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND));
    }

    /**
     * {@code target_joints} 는 MySQL {@code json} 컬럼이라 형식이 깨진 문자열은 DB 가 거부한다
     * (에러 3140). 그대로 두면 {@code DataIntegrityViolationException} → <b>500</b> 이 나가므로,
     * 저장 전에 걸러 400 으로 답한다.
     *
     * <p>null 은 통과시킨다 — 컬럼이 nullable 이고, PATCH 에서 null 은 "안 바꿈"이다.
     */
    private void validateJsonOrThrow(String json) {
        if (json == null) {
            return;
        }
        try {
            objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("targetJoints 가 유효한 JSON 이 아님: {}", e.getOriginalMessage());
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}