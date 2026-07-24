package com.shadowfit.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.admin.ThresholdUpdateDto;
import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.service.Exercise.ExerciseAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @PreAuthorize("hasRole('ADMIN')") 경계 통합테스트 — AdminExerciseController,
 * ExercisesController.extractReference 둘 다 지금까지 이 권한 경계가 실제로 작동하는지
 * 검증한 적이 한 번도 없었음. USER 역할로 호출 시 진짜로 막히는지가 핵심.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("관리자 권한(@PreAuthorize) 통합테스트")
class AdminAuthorizationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @MockitoBean private ExerciseAnalysisService analysisService; // 실제 gRPC/추출 로직 우회, 권한만 검증

    private Exercise exercise;
    private String userToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        Member user = memberRepository.saveAndFlush(Member.builder()
                .email("user@test.com").username("u").password("dummy").role(UserRole.USER).build());
        Member admin = memberRepository.saveAndFlush(Member.builder()
                .email("admin@test.com").username("a").password("dummy").role(UserRole.ADMIN).build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(ExerciseCategory.LOWER).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .build());

        userToken = jwtUtil.createAccessToken(CustomUserInfoDto.builder().email(user.getEmail()).role(user.getRole()).build());
        adminToken = jwtUtil.createAccessToken(CustomUserInfoDto.builder().email(admin.getEmail()).role(admin.getRole()).build());
    }

    @Test
    @DisplayName("임계값 변경 — USER 역할이면 403")
    void updateThresholds_userRole_returns403() throws Exception {
        ThresholdUpdateDto dto = new ThresholdUpdateDto(
                new BigDecimal("60"), new BigDecimal("85"), new BigDecimal("70"), new BigDecimal("50"));

        mockMvc.perform(patch("/admin/exercises/" + exercise.getId() + "/thresholds")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("임계값 변경 — ADMIN 역할이면 200")
    void updateThresholds_adminRole_returns200() throws Exception {
        ThresholdUpdateDto dto = new ThresholdUpdateDto(
                new BigDecimal("60"), new BigDecimal("85"), new BigDecimal("70"), new BigDecimal("50"));

        mockMvc.perform(patch("/admin/exercises/" + exercise.getId() + "/thresholds")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("기준 좌표 추출 — USER 역할이면 403")
    void extractReference_userRole_returns403() throws Exception {
        mockMvc.perform(post("/exercises/" + exercise.getId() + "/reference")
                        .header("Authorization", "Bearer " + userToken)
                        .param("youtubeUrl", "https://youtu.be/dummy"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("기준 좌표 추출 — ADMIN 역할이면 통과(202)")
    void extractReference_adminRole_returns202() throws Exception {
        doNothing().when(analysisService).extractReferencePoses(anyLong(), anyString());

        mockMvc.perform(post("/exercises/" + exercise.getId() + "/reference")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("youtubeUrl", "https://youtu.be/dummy"))
                .andExpect(status().isAccepted());
    }
}
