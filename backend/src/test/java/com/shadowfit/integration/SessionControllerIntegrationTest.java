package com.shadowfit.integration;

import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SessionController 통합테스트 — 오늘 추가한 DELETE /sessions/{id}를 실제 HTTP 레벨(보안
 * 필터체인 포함)로 검증. 서비스 단위테스트(ExerciseSessionFlowIntegrationTest)는 있었지만
 * 컨트롤러/HTTP 매핑·인증은 무테스트였음.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("SessionController 통합테스트")
class SessionControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private SessionRepository sessionRepository;

    private Member member;
    private Exercise exercise;
    private String accessToken;

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(Member.builder()
                .email("session-http@test.com").username("u").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(ExerciseCategory.LOWER).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .build());

        CustomUserInfoDto info = CustomUserInfoDto.builder().email(member.getEmail()).role(member.getRole()).build();
        accessToken = jwtUtil.createAccessToken(info);
    }

    private Session session(Status status) {
        return sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(LocalDateTime.now().minusHours(1))
                .endTime(status == Status.IN_PROGRESS ? null : LocalDateTime.now())
                .status(status).totalReps(5).difficultyLevel(1)
                .avgSyncRate(new BigDecimal("70.0")).caloriesBurned(new BigDecimal("30.0"))
                .build());
    }

    @Test
    @DisplayName("완료된 세션 삭제 — 204")
    void deleteSession_completed_returns204() throws Exception {
        Session s = session(Status.COMPLETED);

        mockMvc.perform(delete("/sessions/" + s.getId())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("IN_PROGRESS 세션 삭제 시도 — 409")
    void deleteSession_inProgress_returns409() throws Exception {
        Session s = session(Status.IN_PROGRESS);

        mockMvc.perform(delete("/sessions/" + s.getId())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("존재하지 않는 세션 삭제 시도 — 404")
    void deleteSession_unknown_returns404() throws Exception {
        mockMvc.perform(delete("/sessions/999999")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("토큰 없이 세션 삭제 시도 — 401")
    void deleteSession_noToken_returns401() throws Exception {
        Session s = session(Status.COMPLETED);

        mockMvc.perform(delete("/sessions/" + s.getId()))
                .andExpect(status().isUnauthorized());
    }
}
