package com.shadowfit.integration;

import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.model.coaching.TrainerAssignment;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.coaching.TrainerAssignmentRepository;
import com.shadowfit.repository.exercise.CategoryRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.service.exercise.PoseDataService;
import com.shadowfit.service.coaching.TrainerConnectionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code TrainerStreamAuthorizationIntegrationTest}(세션3)가 "연결·권한 실패"를 이미 덮었으니,
 * 이 클래스는 §8 세션6이 추가로 요구하는 두 가지를 채운다({@code trainer-live-monitoring.md}
 * §8 세션6) — ① 세션4(릴레이)·세션5(하트비트·정리)가 컨트롤러 HTTP 경계까지 실제로 이어지는지
 * 종단 검증(그 전까지는 {@code PoseDataServiceTrainerRelayTest}가 "broadcast가 불렸는가"만
 * mock으로 봤지, 실제 emitter가 응답 스트림에 썼는지는 아무도 안 봤다), ② 끊김 케이스
 * (컨트롤러가 등록한 {@code onCompletion}/{@code onError}가 실제로 레지스트리를 비우는가) —
 * 이건 {@code CoachingStreamController} 자체엔 단위 테스트가 없어 이번이 처음 보는 경로다.
 *
 * <p>rep 이벤트 종단 테스트는 {@link PoseDataService#savePoseDataBatch}의 커밋 후 훅에 의존하므로
 * {@code PoseDataServiceTrainerRelayTest}와 같은 이유로 {@link TestTransaction}을 쓴다 — 이
 * 클래스 전체를 감싸는 {@code @Transactional}은 기본이 롤백이라 그대로 두면 {@code afterCommit()}
 * 이 안 불린다. 커밋시키는 테스트가 만든 행은 이 트랜잭션 밖에 실제로 남으므로 {@link #tearDown}
 * 에서 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("트레이너 SSE 스트림 생명주기 통합테스트")
class TrainerStreamLifecycleIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;
    @Autowired private TrainerAssignmentRepository trainerAssignmentRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private PoseDataService poseDataService;
    @Autowired private TrainerConnectionRegistry trainerConnectionRegistry;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long categoryId;
    private Long exerciseId;
    private Long exerciseSessionId;
    private String assignedTrainerToken;

    @BeforeEach
    void setUp() {
        Member user = memberRepository.saveAndFlush(Member.builder()
                .email("lifecycle-user@test.com").username("u").password("dummy")
                .role(UserRole.USER).build());
        userId = user.getId();
        Member trainer = memberRepository.saveAndFlush(Member.builder()
                .email("lifecycle-trainer@test.com").username("t").password("dummy")
                .role(UserRole.TRAINER).build());
        trainerAssignmentRepository.saveAndFlush(TrainerAssignment.builder().trainer(trainer).user(user).build());
        assignedTrainerToken = jwtUtil.createAccessToken(
                CustomUserInfoDto.builder().email(trainer.getEmail()).role(trainer.getRole()).build());

        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        categoryId = category.getId();
        Exercise exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .build());
        exerciseId = exercise.getId();
        Session session = sessionRepository.saveAndFlush(Session.builder()
                .member(user).exercise(exercise).startTime(LocalDateTime.now())
                .status(Status.IN_PROGRESS).totalReps(0).difficultyLevel(1).build());
        exerciseSessionId = session.getId();
    }

    // TestTransaction.end() 로 커밋시킨 테스트만 실제로 행을 남긴다 — 롤백된 테스트는 이미 없는
    // 행이라 같은 DELETE 가 0건으로 조용히 끝난다. FK 순서:
    // pose_data → exercise_sessions → trainer_assignments → exercises → categories → users.
    @AfterEach
    void tearDown() {
        if (exerciseSessionId != null) {
            jdbcTemplate.update("DELETE FROM pose_data WHERE session_id = ?", exerciseSessionId);
            jdbcTemplate.update("DELETE FROM exercise_sessions WHERE id = ?", exerciseSessionId);
        }
        if (exerciseId != null) {
            jdbcTemplate.update("DELETE FROM exercises WHERE id = ?", exerciseId);
        }
        if (categoryId != null) {
            jdbcTemplate.update("DELETE FROM categories WHERE id = ?", categoryId);
        }
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM trainer_assignments WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ? OR email = 'lifecycle-trainer@test.com'", userId);
        }
    }

    @Test
    @DisplayName("연결 정상 종료(complete)하면 레지스트리에서 즉시 제거된다")
    void stream_complete_removesFromRegistry() throws Exception {
        MvcResult result = mockMvc.perform(get("/coaching/trainer/" + userId + "/stream")
                        .header("Authorization", "Bearer " + assignedTrainerToken))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andReturn();

        List<SseEmitter> connections = trainerConnectionRegistry.getConnections(userId);
        assertThat(connections).hasSize(1);

        // emitter.complete() 자체는 컨트롤러가 등록한 onCompletion 콜백(연결 제거)을 바로 안
        // 부른다 — Spring 은 그 콜백을 실제 서블릿 비동기 디스패치가 끝날 때 부르므로, MockMvc
        // 에서도 asyncDispatch 로 그 디스패치를 직접 재현해야 한다.
        connections.get(0).complete();
        mockMvc.perform(asyncDispatch(result));

        assertThat(trainerConnectionRegistry.getConnections(userId)).isEmpty();
    }

    // completeWithError 케이스(onError 콜백으로도 제거되는가)는 여기서 MockMvc 로 못 잡는다 —
    // 이 컨트롤러는 connected 이벤트를 리턴 전에 이미 응답에 써버려서(§8 세션3 설계) 그 시점부터
    // 응답이 커밋된 상태다. completeWithError 를 흉내내려고 asyncDispatch 를 재현하면 Spring 이
    // 이미 커밋된 스트림 위에 GlobalExceptionHandler 의 에러 응답을 다시 쓰려다 실패해
    // ServletException 으로 전체가 죽고, 그 실패 지점이 우리 onError 콜백보다 앞이라 정리
    // 자체를 못 본다 — MockMvc 의 비동기 에러 재디스패치 시뮬레이션 한계지 컨트롤러 결함이
    // 아니다(실제 컨테이너라면 그냥 소켓을 닫지 응답 본문을 다시 쓰려 하지 않는다). onError 가
    // 부르는 코드는 onCompletion 과 똑같은 한 줄(connectionRegistry.remove)이고, 그 removal
    // 자체는 broadcast/heartbeat 전송 실패 시 이미 도는 실제 프로덕션 경로라
    // TrainerConnectionRegistryTest 에서 충분히 검증됐다 — 여기서 굳이 다시 흉내내지 않는다.

    @Test
    @DisplayName("연결된 트레이너는 커밋된 rep 배치의 실제 SSE 프레임을 응답 스트림에서 받는다")
    void stream_receivesRepEventAfterCommittedBatch() throws Exception {
        MvcResult result = mockMvc.perform(get("/coaching/trainer/" + userId + "/stream")
                        .header("Authorization", "Bearer " + assignedTrainerToken))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("connected");

        poseDataService.savePoseDataBatch(exerciseSessionId, List.of(frame(3, "무릎이 안쪽으로 모입니다")));
        TestTransaction.flagForCommit();
        TestTransaction.end();

        // 기본 getContentAsString() 은 응답의 characterEncoding 을 따르는데, 이 비동기 스트리밍
        // 응답은 그게 UTF-8 로 안 잡혀 한글이 깨진다(MockHttpServletResponse 특성) — 명시적으로
        // UTF-8 로 읽는다. SseEmitter 가 실제로 UTF-8 로 쓰는 것과는 별개 문제(디코딩 쪽 이슈).
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(content).contains("event:rep");
        assertThat(content).contains("\"repNumber\":3");
        assertThat(content).contains("무릎이 안쪽으로 모입니다");
    }

    private PoseDataRequest frame(int repNumber, String feedbackMessage) {
        return PoseDataRequest.newBuilder()
                .setTimestampSec(0.0)
                .setJointCoordinates("{}")
                .setSyncRate(72.5)
                .setRepNumber(repNumber)
                .setSmoothedKneeAngle(90.0)
                .setFeedbackMessage(feedbackMessage)
                .build();
    }
}
