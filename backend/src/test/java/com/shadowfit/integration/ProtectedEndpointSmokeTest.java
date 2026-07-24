package com.shadowfit.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 나머지 컨트롤러들(ExerciseRecordController·ExerciseReportController·ExercisesController·
 * FeedbackTemplateController·PreferenceController·SessionFeedbackController)의 보호된
 * 엔드포인트가 실제로 인증 없이는 401을 반환하는지 확인하는 저비용 스모크 테스트.
 * 비즈니스 로직 자체는 이미 서비스 단위테스트로 커버돼 있어, 여기서는 라우팅·보안설정
 * 자체가 살아있는지만 넓게 확인한다(라우팅 오타·화이트리스트 실수 등을 잡는 안전망).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("보호된 엔드포인트 인증 스모크 테스트")
class ProtectedEndpointSmokeTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("GET /reports/weekly-summary — 토큰 없으면 401")
    void weeklySummary_noToken_401() throws Exception {
        mockMvc.perform(get("/reports/weekly-summary")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /reports/calendar — 토큰 없으면 401")
    void calendar_noToken_401() throws Exception {
        mockMvc.perform(get("/reports/calendar").param("year", "2026").param("month", "7"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /reports/daily — 토큰 없으면 401")
    void daily_noToken_401() throws Exception {
        mockMvc.perform(get("/reports/daily").param("date", "2026-07-24"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /reports/daily-logs — 토큰 없으면 401")
    void saveDailyLog_noToken_401() throws Exception {
        mockMvc.perform(post("/reports/daily-logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /reports/session/{id} — 토큰 없으면 401")
    void sessionReport_noToken_401() throws Exception {
        mockMvc.perform(get("/reports/session/1")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /exercises/sessions — 토큰 없으면 401")
    void startAnalysis_noToken_401() throws Exception {
        mockMvc.perform(post("/exercises/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /exercises/{id}/feedback-templates — 토큰 없으면 401")
    void feedbackTemplates_noToken_401() throws Exception {
        mockMvc.perform(get("/exercises/1/feedback-templates")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /preferences/tts — 토큰 없으면 401")
    void getPreferences_noToken_401() throws Exception {
        mockMvc.perform(get("/preferences/tts")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PATCH /preferences/tts — 토큰 없으면 401")
    void updatePreferences_noToken_401() throws Exception {
        mockMvc.perform(patch("/preferences/tts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /sessions/{id}/feedbacks — 토큰 없으면 401")
    void sessionFeedbacks_noToken_401() throws Exception {
        mockMvc.perform(get("/sessions/1/feedbacks")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /sessions/{id}/feedback-summary — 토큰 없으면 401")
    void sessionFeedbackSummary_noToken_401() throws Exception {
        mockMvc.perform(get("/sessions/1/feedback-summary")).andExpect(status().isUnauthorized());
    }
}
