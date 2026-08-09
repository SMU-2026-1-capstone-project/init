package com.shadowfit.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.admin.ExerciseCreateDto;
import com.shadowfit.dto.admin.ExerciseUpdateDto;
import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 운동 종목 CRUD 의 <b>HTTP 계약</b> 검증 ({@code admin-page-scope.md} §3-C).
 *
 * <p>[왜 서비스 단위테스트로 부족한가] 서비스 테스트는 "{@code BusinessException(W011)} 을
 * 던진다"까지만 본다. 그게 실제로 <b>409 로 나가는지</b>는 {@code GlobalExceptionHandler} 까지
 * 가봐야 알 수 있고, 바로 그 구간에서 이 코드베이스가 세 번 틀렸다 — 403→500, 400→500, 404→500
 * (같은 파일 주석). 여기서는 상태코드 자체를 고정한다.
 *
 * <p>권한 경계({@code @PreAuthorize}) 는 {@link AdminAuthorizationIntegrationTest} 가 기존 두
 * 엔드포인트에 대해 검증하는데, 이번에 늘어난 5개는 <b>클래스 레벨 애노테이션에 얹혀 있어</b>
 * 메서드마다 따로 붙지 않는다. 그 상속이 실제로 걸리는지를 쓰기 경로 하나(DELETE)로 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("관리자 운동 종목 CRUD 통합테스트")
class AdminExerciseCrudIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private SessionRepository sessionRepository;

    private Member user;
    private Exercise exercise;
    private String userToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        user = memberRepository.saveAndFlush(Member.builder()
                .email("user@test.com").username("u").password("dummy").role(UserRole.USER).build());
        Member admin = memberRepository.saveAndFlush(Member.builder()
                .email("admin@test.com").username("a").password("dummy").role(UserRole.ADMIN).build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(ExerciseCategory.LOWER).build());

        userToken = jwtUtil.createAccessToken(
                CustomUserInfoDto.builder().email(user.getEmail()).role(user.getRole()).build());
        adminToken = jwtUtil.createAccessToken(
                CustomUserInfoDto.builder().email(admin.getEmail()).role(admin.getRole()).build());
    }

    @Nested
    @DisplayName("조회")
    class Read {

        @Test
        @DisplayName("목록 — 200 이고 총건수가 실린다")
        void list_returns200WithTotal() throws Exception {
            mockMvc.perform(get("/admin/exercises").header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").isNumber())
                    .andExpect(jsonPath("$.content").isArray());
        }

        @Test
        @DisplayName("정렬 키가 화이트리스트에 없으면 500 이 아니라 400")
        void list_unknownSortKey_returns400() throws Exception {
            mockMvc.perform(get("/admin/exercises")
                            .param("sort", "PASSWORD")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("상세 — 없는 id 면 404")
        void detail_notFound_returns404() throws Exception {
            mockMvc.perform(get("/admin/exercises/999999")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("등록")
    class Create {

        @Test
        @DisplayName("201 + Location, analysisSupported 는 false 로 내려온다")
        void create_returns201WithLocation() throws Exception {
            ExerciseCreateDto dto = new ExerciseCreateDto(
                    "데드리프트", ExerciseCategory.BACK, "설명", null, "[\"hip\"]", 20);

            mockMvc.perform(post("/admin/exercises")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists("Location"))
                    .andExpect(jsonPath("$.analysisSupported").value(false))
                    .andExpect(jsonPath("$.expectedDurationMinutes").value(20));
        }

        @Test
        @DisplayName("이름이 비면 400")
        void create_blankName_returns400() throws Exception {
            ExerciseCreateDto dto = new ExerciseCreateDto(
                    "  ", ExerciseCategory.BACK, null, null, null, null);

            mockMvc.perform(post("/admin/exercises")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("targetJoints 가 깨진 JSON 이면 500 이 아니라 400")
        void create_invalidJson_returns400() throws Exception {
            ExerciseCreateDto dto = new ExerciseCreateDto(
                    "데드리프트", ExerciseCategory.BACK, null, null, "{깨진", null);

            mockMvc.perform(post("/admin/exercises")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("수정")
    class Update {

        @Test
        @DisplayName("보낸 필드만 바뀐다 — 생략한 카테고리는 유지")
        void update_partial_keepsOmitted() throws Exception {
            ExerciseUpdateDto dto = new ExerciseUpdateDto(
                    "스쿼트(개선)", null, null, null, null, null);

            mockMvc.perform(patch("/admin/exercises/" + exercise.getId())
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("스쿼트(개선)"))
                    .andExpect(jsonPath("$.category").value("LOWER"));
        }
    }

    @Nested
    @DisplayName("삭제")
    class Delete {

        @Test
        @DisplayName("세션 이력이 없으면 204")
        void delete_noSessions_returns204() throws Exception {
            mockMvc.perform(delete("/admin/exercises/" + exercise.getId())
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNoContent());
        }

        /**
         * 이 테스트가 이 클래스의 핵심이다 — {@code exercise_sessions} 의 FK 에는
         * {@code ON DELETE CASCADE} 가 없어서(V1__baseline.sql:110), 가드가 없으면 DB 제약 위반이
         * 그대로 올라와 <b>409 여야 할 것이 500</b> 이 된다.
         */
        @Test
        @DisplayName("세션 이력이 있으면 500 이 아니라 409")
        void delete_withSessions_returns409() throws Exception {
            sessionRepository.saveAndFlush(Session.builder()
                    .member(user).exercise(exercise).startTime(LocalDateTime.now()).build());

            mockMvc.perform(delete("/admin/exercises/" + exercise.getId())
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("USER 역할이면 403 — 클래스 레벨 @PreAuthorize 가 새 메서드에도 걸린다")
        void delete_userRole_returns403() throws Exception {
            mockMvc.perform(delete("/admin/exercises/" + exercise.getId())
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isForbidden());
        }
    }
}