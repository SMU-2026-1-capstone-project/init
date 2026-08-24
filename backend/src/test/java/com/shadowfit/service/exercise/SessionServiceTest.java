package com.shadowfit.service.exercise;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import com.shadowfit.global.observability.CorrelationIds;
import com.shadowfit.model.outbox.OutboxEvent;
import com.shadowfit.model.outbox.OutboxEventType;
import com.shadowfit.model.outbox.OutboxStatus;
import com.shadowfit.repository.outbox.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * SessionService 통합테스트 — 오늘 다룬 deleteSession/applyComplete(precompute) 외에 지금까지
 * 무테스트였던 나머지 메서드들: createSession(활성세션 락), getWeeklyActivity/getCalendarMain/
 * getDailyActivity(집계 조회), endSession(자체 단위 검증 + afterCommit AI 통보 트리거).
 */
@SpringBootTest
@Transactional
@DisplayName("SessionService 테스트")
class SessionServiceTest {

    @Autowired private SessionService sessionService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private com.shadowfit.repository.exercise.CategoryRepository categoryRepository;
    @Autowired private OutboxEventRepository outboxRepository;
    // endSession 이 더 이상 이걸 부르지 않는다는 것 자체를 검증한다(요청 경로에 외부 호출 없음).
    @MockitoBean private ExerciseAnalysisService analysisService;
    // 발행기가 테스트 도중 돌면서 아웃박스 행을 집어가면 검증이 흔들린다 — 스케줄 실행을 막는다.
    @MockitoBean private OutboxPublisher outboxPublisher;

    private Member member;
    private Exercise exercise;
    private Category category;

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(Member.builder()
                .email("sessionsvc@test.com").username("u").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        category = categoryRepository.save(Category.builder().name("LOWER").build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .analysisSupported(true)  // 기본값이 false라 명시 필요 — 없으면 createSession이 W007로 막힘
                .build());
    }

    @Nested
    @DisplayName("createSession")
    class CreateSession {

        @Test
        @DisplayName("정상 생성 — IN_PROGRESS 세션 저장됨")
        void createSession_success() {
            VideoRequestDto dto = VideoRequestDto.builder().exerciseId(exercise.getId()).build();

            Session result = sessionService.createSession(dto, member.getId(), "https://youtu.be/dummy");

            assertThat(result.getId()).isNotNull();
            assertThat(result.getStatus()).isEqualTo(Status.IN_PROGRESS);
            assertThat(result.getMember().getId()).isEqualTo(member.getId());
        }

        @Test
        @DisplayName("AI 분석기가 없는 종목이면 EXERCISE_NOT_SUPPORTED — 런지·플랭크가 조용히 빈 결과를 내던 것 차단")
        void createSession_analysisNotSupported_throws() {
            Exercise unsupported = exercisesRepository.saveAndFlush(Exercise.builder()
                    .name("런지").category(category).expectedDurationMinutes(15)
                    .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                    .build());  // analysisSupported 기본값 false
            VideoRequestDto dto = VideoRequestDto.builder().exerciseId(unsupported.getId()).build();

            assertThatThrownBy(() -> sessionService.createSession(dto, member.getId(), "https://youtu.be/dummy"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXERCISE_NOT_SUPPORTED);
        }

        @Test
        @DisplayName("진행 중 세션 조회 — 시작 전 비어있고, 시작 후 조회되며, 종료 요청 뒤엔 endTime이 채워진 채 남는다 (#59 1단계)")
        void getActiveSession_reflectsLifecycle() {
            assertThat(sessionService.getActiveSession(member.getId())).isEmpty();

            VideoRequestDto dto = VideoRequestDto.builder().exerciseId(exercise.getId()).build();
            Session created = sessionService.createSession(dto, member.getId(), "https://youtu.be/dummy");

            // 앱이 죽어 sessionId를 잃은 클라가 이 API로 복원하는 시나리오
            assertThat(sessionService.getActiveSession(member.getId()))
                    .get()
                    .satisfies(active -> {
                        assertThat(active.getSessionId()).isEqualTo(created.getId());
                        assertThat(active.getExerciseId()).isEqualTo(exercise.getId());
                        // open-in-view: false 라 lazy 였다면 여기서 터진다 — @EntityGraph 동작 확인
                        assertThat(active.getExerciseName()).isEqualTo("스쿼트");
                        assertThat(active.getStatus()).isEqualTo(Status.IN_PROGRESS);
                    });

            // endSession 은 endTime 만 기록하고 status 는 IN_PROGRESS 로 둔다(COMPLETED 전환은 AI
            // 콜백 몫). 이 구간에도 createSession 은 409로 막히므로 조회에서 빼면 안 되고, 대신
            // endTime 으로 "이어하기 가능"과 "결과 처리 대기"를 구분한다.
            sessionService.endSession(created.getId(), member.getId());

            assertThat(sessionService.getActiveSession(member.getId()))
                    .get()
                    .satisfies(pending -> {
                        assertThat(pending.getSessionId()).isEqualTo(created.getId());
                        assertThat(pending.getStatus()).isEqualTo(Status.IN_PROGRESS);
                        assertThat(pending.getEndTime()).isNotNull();
                    });
        }

        @Test
        @DisplayName("진행 중 세션 조회 — 남의 세션은 보이지 않는다")
        void getActiveSession_otherMemberSession_notVisible() {
            Member other = memberRepository.saveAndFlush(Member.builder()
                    .email("other@test.com").username("other").password("dummy")
                    .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
            VideoRequestDto dto = VideoRequestDto.builder().exerciseId(exercise.getId()).build();
            sessionService.createSession(dto, other.getId(), "https://youtu.be/dummy");

            assertThat(sessionService.getActiveSession(member.getId())).isEmpty();
            assertThat(sessionService.getActiveSession(other.getId())).isPresent();
        }

        @Test
        @DisplayName("이미 진행 중인 세션이 있으면 SESSION_ALREADY_IN_PROGRESS")
        void createSession_activeSessionExists_throws() {
            VideoRequestDto dto = VideoRequestDto.builder().exerciseId(exercise.getId()).build();
            sessionService.createSession(dto, member.getId(), "https://youtu.be/dummy");

            assertThatThrownBy(() -> sessionService.createSession(dto, member.getId(), "https://youtu.be/dummy"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.SESSION_ALREADY_IN_PROGRESS);
        }

        @Test
        @DisplayName("존재하지 않는 회원이면 USER_NOT_FOUND")
        void createSession_unknownMember_throws() {
            VideoRequestDto dto = VideoRequestDto.builder().exerciseId(exercise.getId()).build();

            assertThatThrownBy(() -> sessionService.createSession(dto, 999999L, "https://youtu.be/dummy"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("존재하지 않는 운동이면 EXERCISE_NOT_FOUND")
        void createSession_unknownExercise_throws() {
            VideoRequestDto dto = VideoRequestDto.builder().exerciseId(999999L).build();

            assertThatThrownBy(() -> sessionService.createSession(dto, member.getId(), "https://youtu.be/dummy"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXERCISE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("endSession")
    class EndSession {

        private Session inProgressSession() {
            return sessionRepository.saveAndFlush(Session.builder()
                    .member(member).exercise(exercise).startTime(LocalDateTime.now().minusMinutes(10))
                    .status(Status.IN_PROGRESS).totalReps(0).difficultyLevel(1).build());
        }

        @Test
        @DisplayName("본인 세션 종료 — endTime 기록됨")
        void endSession_self_setsEndTime() {
            Session session = inProgressSession();

            sessionService.endSession(session.getId(), member.getId());

            assertThat(sessionRepository.findById(session.getId()).orElseThrow().getEndTime()).isNotNull();
        }

        @Test
        @DisplayName("본인 세션이 아니면 ACCESS_DENIED")
        void endSession_notOwner_throws() {
            Session session = inProgressSession();

            assertThatThrownBy(() -> sessionService.endSession(session.getId(), 999999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ACCESS_DENIED);
        }

        @Test
        @DisplayName("존재하지 않는 세션이면 SESSION_NOT_FOUND")
        void endSession_unknownSession_throws() {
            assertThatThrownBy(() -> sessionService.endSession(999999L, member.getId()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.SESSION_NOT_FOUND);
        }

        @Test
        @DisplayName("이미 종료된 세션 재호출은 멱등 — endTime 안 바뀜, 아웃박스 행도 정확히 1건만")
        void endSession_alreadyEnded_isIdempotent() {
            Session session = inProgressSession();
            sessionService.endSession(session.getId(), member.getId());
            LocalDateTime firstEndTime = sessionRepository.findById(session.getId()).orElseThrow().getEndTime();

            sessionService.endSession(session.getId(), member.getId()); // 멱등 경로

            assertThat(sessionRepository.findById(session.getId()).orElseThrow().getEndTime()).isEqualTo(firstEndTime);

            // 멱등성이 깨지면 같은 세션에 통보가 두 번 쌓여 AI 에 중복 송신된다. 수신측 멱등성이
            // 흡수해주긴 하지만, 애초에 안 만드는 게 맞다.
            assertThat(stopEventsFor(session.getId())).hasSize(1);
        }

        @Test
        @DisplayName("요청 경로에서 gRPC 를 부르지 않고, 통보를 아웃박스 행으로 남긴다")
        void endSession_writesOutboxRowInsteadOfCallingAi() {
            Session session = inProgressSession();

            sessionService.endSession(session.getId(), member.getId());

            // 이전 설계는 afterCommit 에서 stopAnalysis 를 직접 불렀다. 이제 요청 경로엔 외부 호출이
            // 아예 없다 — 사용자는 AI 응답을 기다리지 않고, 송신 실패가 요청에 영향을 주지도 않는다.
            verify(analysisService, never()).stopAnalysis(eq(session.getId()), anyBoolean());

            List<OutboxEvent> events = stopEventsFor(session.getId());
            assertThat(events).hasSize(1);
            OutboxEvent event = events.get(0);
            // 아직 아무도 안 보냈다 — 전달은 OutboxPublisher 의 몫이고, 그때까지 행이 증거로 남는다.
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(event.getPayload()).contains(String.valueOf(session.getId()));
            assertThat(event.getRetryCount()).isZero();
        }

        @Test
        @DisplayName("아웃박스 행에 원 요청의 cid 가 실린다 — 발행기는 MDC 로 이을 수 없으므로")
        void endSession_carriesCorrelationIdOnRow() {
            Session session = inProgressSession();

            try (CorrelationIds.Scope ignored = CorrelationIds.withCorrelationId("test-cid-1234")) {
                sessionService.endSession(session.getId(), member.getId());
            }

            assertThat(stopEventsFor(session.getId()))
                    .singleElement()
                    .extracting(OutboxEvent::getCorrelationId)
                    .isEqualTo("test-cid-1234");
        }

        private List<OutboxEvent> stopEventsFor(Long sessionId) {
            return outboxRepository.findAll().stream()
                    .filter(e -> e.getEventType() == OutboxEventType.STOP_ANALYSIS)
                    .filter(e -> sessionId.equals(e.getAggregateId()))
                    .toList();
        }
    }

}
