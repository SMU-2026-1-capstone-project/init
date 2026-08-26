package com.shadowfit.service.Exercise;

import com.shadowfit.dto.exercises.VideoRequestDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ExerciseAnalysisService 통합테스트 — 실제 Spring 컨텍스트(자기주입 self, @GrpcClient 스텁 모두
 * 정상 구성)로 검증. AI 서버로 나가는 실제 gRPC 호출이 필요한 성공 경로는 서킷브레이커를 강제로
 * OPEN시켜 우회하고, 그 앞단의 검증 로직만 검증한다.
 */
@SpringBootTest
@Transactional
@DisplayName("ExerciseAnalysisService 테스트")
class ExerciseAnalysisServiceTest {

    @Autowired private ExerciseAnalysisService analysisService;
    @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private com.shadowfit.repository.exercise.CategoryRepository categoryRepository;
    @Autowired private SessionRepository sessionRepository;

    private Member member;
    private Exercise exercise;

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(Member.builder()
                .email("analysis@test.com").username("u").password("dummy")
                .preferredUrl("https://youtu.be/dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .analysisSupported(true)  // 기본값 false — createSession이 W007로 막히므로 명시 필요
                .build());
    }

    @AfterEach
    void resetCircuitBreaker() {
        // 다른 테스트에 영향 안 주도록 매번 CLOSED로 복구
        circuitBreakerRegistry.circuitBreaker("aiServer").transitionToClosedState();
    }

    // ---- extractReferencePoses ----

    @Test
    @DisplayName("기준 좌표 추출 — 존재하지 않는 운동이면 EXERCISE_NOT_FOUND")
    void extractReferencePoses_unknownExercise_throws() {
        assertThatThrownBy(() -> analysisService.extractReferencePoses(999999L, "https://youtu.be/dummy"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXERCISE_NOT_FOUND);
    }

    @Test
    @DisplayName("기준 좌표 추출 — youtubeUrl이 비어있으면 INVALID_INPUT_VALUE")
    void extractReferencePoses_blankUrl_throws() {
        assertThatThrownBy(() -> analysisService.extractReferencePoses(exercise.getId(), ""))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    @DisplayName("기준 좌표 추출 — 서킷브레이커 OPEN이면 예외 없이 조용히 스킵")
    void extractReferencePoses_circuitOpen_skipsSilently() {
        circuitBreakerRegistry.circuitBreaker("aiServer").transitionToOpenState();

        // AI 서버 호출을 아예 시도하지 않아야 하므로(스텁 실제 연결 없이도) 예외 없이 반환돼야 함
        analysisService.extractReferencePoses(exercise.getId(), "https://youtu.be/dummy");
    }

    // ---- startAnalysis ----

    @Test
    @DisplayName("세션 시작 — 존재하지 않는 회원이면 USER_NOT_FOUND")
    void startAnalysis_unknownMember_throws() {
        VideoRequestDto dto = VideoRequestDto.builder().exerciseId(exercise.getId()).build();

        assertThatThrownBy(() -> analysisService.startAnalysis(dto, 999999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("세션 시작 — preferredUrl이 없으면 INVALID_INPUT_VALUE")
    void startAnalysis_noPreferredUrl_throws() {
        Member noUrlMember = memberRepository.saveAndFlush(Member.builder()
                .email("nourl@test.com").username("u2").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        VideoRequestDto dto = VideoRequestDto.builder().exerciseId(exercise.getId()).build();

        assertThatThrownBy(() -> analysisService.startAnalysis(dto, noUrlMember.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    @DisplayName("세션 시작 — 정상 케이스면 세션이 즉시 IN_PROGRESS로 동기 생성·반환됨 " +
            "(self.sendAnalysisRequestToFastApi가 진짜 비동기로 나가 이 트랜잭션 안에서 영향 없어야 함)")
    void startAnalysis_success_createsSessionSynchronously() {
        VideoRequestDto dto = VideoRequestDto.builder().exerciseId(exercise.getId()).build();

        Long sessionId = analysisService.startAnalysis(dto, member.getId()).sessionId();

        // self.를 거쳐 실제로 @Async 프록시를 타면, 비동기 스레드는 이 테스트 트랜잭션이 커밋되기
        // 전이라 세션을 아예 못 봐서(findById 실패) markAsFailedIfStillInProgress가 조용히
        // no-op됨 — 그래서 동기 반환 직후 이 트랜잭션 안에서는 항상 IN_PROGRESS로 보여야 함.
        // (self. 대신 this.로 self-invocation하면 @Async가 무시돼 동기 실행되면서 이 값이
        // 깨질 수 있음 — 2026-07-24 발견·수정한 버그의 회귀 방지 성격도 겸함)
        //
        // CodeRabbit 지적(2026-07-24, sendAnalysisRequestToFastApi를 목 처리해서 백그라운드 gRPC
        // 호출 부수효과를 끊어내라) 검토 결과 skip: self.sendAnalysisRequestToFastApi()는
        // registerSynchronization(...).afterCommit()으로만 실행되는데, 이 테스트는 클래스
        // @Transactional 기본 rollback 정책상 실제 커밋이 한 번도 일어나지 않아 afterCommit
        // 콜백 자체가 트리거되지 않음(실측: 로그에 "비동기 분석 요청 시작"이 전혀 안 찍힘) —
        // 즉 이 테스트에서는 gRPC 호출도, 그로 인한 부수효과도 실제로 발생하지 않아 목 처리 대상이 없음.
        Session created = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(created.getStatus()).isEqualTo(Status.IN_PROGRESS);
        assertThat(created.getMember().getId()).isEqualTo(member.getId());
    }

    // ---- stopAnalysis ----


    @Test
    @DisplayName("세션 시작 — 소유권 비밀값이 DB 에 저장되고 같은 값이 응답으로 나간다 (#187 d)")
    void startAnalysis_issuesSessionNonce() {
        VideoRequestDto dto = VideoRequestDto.builder().exerciseId(exercise.getId()).build();

        var started = analysisService.startAnalysis(dto, member.getId());

        Session saved = sessionRepository.findById(started.sessionId()).orElseThrow();
        // 🔴 «응답으로 나간 값» 과 «DB 에 남은 값» 이 같아야 한다. 갈리면 클라가 받은 값으로는
        //    AI 의 보관값(이것도 DB 에서 나온다)을 통과하지 못해 세션이 통째로 막힌다.
        assertThat(started.sessionNonce())
                .as("세션 시작 응답의 nonce")
                .isNotBlank()
                .isEqualTo(saved.getSessionNonce());
    }

    @Test
    @DisplayName("세션 시작 — 응답의 startTime 이 «지금» 이 아니라 DB 에 저장된 값이다 (#467)")
    void startAnalysis_startTimeIsThePersistedValue() {
        VideoRequestDto dto = VideoRequestDto.builder().exerciseId(exercise.getId()).build();

        var started = analysisService.startAnalysis(dto, member.getId());

        Session saved = sessionRepository.findById(started.sessionId()).orElseThrow();
        // 🔴 예전에는 컨트롤러가 응답을 만들며 LocalDateTime.now() 를 **새로 읽어** 실었다.
        //    세션을 저장한 시각과 다른 now() 호출이라 초 경계를 넘으면 1초 갈렸고,
        //    실제로 관측됐다(2026-08-23: 응답 13:21:04 · DB 13:21:05).
        //
        //    표시용 값이 아니라서 아프다 — 이 값은 pose_data 의 멱등 앵커이자 파티션 키이고
        //    (#188 · #392), 리포트·재부착 조회가 등호로 찾는 바로 그 값이다.
        assertThat(started.startTime())
                .as("세션 시작 응답의 startTime 은 저장된 값과 같아야 한다")
                .isNotNull()
                .isEqualTo(saved.getStartTime());
        assertThat(started.startTime().getNano())
                .as("앵커라서 초 이하가 없다 (#446, Session 의 @PrePersist)")
                .isZero();
    }

    @Test
    @DisplayName("세션 시작 — 두 세션이 서로 다른 값을 받는다 (같으면 서로를 통과한다)")
    void startAnalysis_nonceDiffersPerSession() {
        VideoRequestDto dto = VideoRequestDto.builder().exerciseId(exercise.getId()).build();
        var first = analysisService.startAnalysis(dto, member.getId());

        // 1인 1세션이라 두 번째를 만들려면 앞 세션을 끝내야 한다(SESSION_ALREADY_IN_PROGRESS).
        Session firstSession = sessionRepository.findById(first.sessionId()).orElseThrow();
        firstSession.fail(java.time.LocalDateTime.now());
        sessionRepository.saveAndFlush(firstSession);

        var second = analysisService.startAnalysis(dto, member.getId());

        assertThat(second.sessionNonce()).isNotEqualTo(first.sessionNonce());
    }

    @Test
    @DisplayName("세션 시작 — 비밀값이 toString 으로 새지 않는다 (엔티티를 통째로 로깅하는 자리 방어)")
    void sessionNonce_isNotExposedInToString() {
        VideoRequestDto dto = VideoRequestDto.builder().exerciseId(exercise.getId()).build();
        var started = analysisService.startAnalysis(dto, member.getId());
        Session saved = sessionRepository.findById(started.sessionId()).orElseThrow();

        // 로그에 남으면 로그를 읽을 수 있는 사람이 그 세션의 소유자가 된다.
        assertThat(saved.toString()).doesNotContain(saved.getSessionNonce());
    }

    @Test
    @DisplayName("분석 중단 — 서킷브레이커 OPEN이면 예외 없이 조용히 스킵")
    void stopAnalysis_circuitOpen_skipsSilently() {
        circuitBreakerRegistry.circuitBreaker("aiServer").transitionToOpenState();

        analysisService.stopAnalysis(1L, false);
    }

}
