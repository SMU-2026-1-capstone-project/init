package com.shadowfit.integration;

import com.shadowfit.dto.login.CustomUserInfoDto;
import com.shadowfit.dto.pattern.TimeBucket;
import com.shadowfit.global.security.jwt.JwtUtil;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.CategoryRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PatternAnalysisController 통합테스트 — BE-07 세1~4가 만든 로직은 PatternAnalysisServiceTest
 * (310줄, 순수 유닛)로 이미 촘촘히 덮여 있지만, 실제 HTTP 경계(JWT→memberId 인증, DTO 직렬화,
 * 라우팅)를 지나는 컨트롤러 레벨 테스트가 하나도 없었다 — 이 클래스가 그 첫 종단 검증이다.
 *
 * <p>ProtectedEndpointSmokeTest 는 "토큰 없으면 401" 만 넓게 훑는 스모크 테스트라 여기 세
 * endpoint 를 별도로 채웠다(같은 파일에 추가). 이 클래스는 그 반대쪽 — 인증된 요청이 실제로
 * 올바른 값을 돌려주는지를 본다.
 *
 * <p>sufficientData=true 경로를 재현하려면 회원가입 4주(28일) 이상 지난 계정이 필요한데,
 * {@code Member.createdAt} 는 {@code @CreationTimestamp}(updatable=false) 라 JPA 로는 과거
 * 시각을 못 심는다 — {@link JdbcTemplate} 로 저장 직후 직접 UPDATE 해 우회한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("PatternAnalysisController 통합테스트")
class PatternAnalysisControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    private Exercise exercise;

    @BeforeEach
    void setUp() {
        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00")).syncThresholdAdvanced(new BigDecimal("85.00"))
                .build());
    }

    private String tokenFor(Member member) {
        CustomUserInfoDto info = CustomUserInfoDto.builder().email(member.getEmail()).role(member.getRole()).build();
        return jwtUtil.createAccessToken(info);
    }

    private Member freshMember(String email) {
        return memberRepository.saveAndFlush(Member.builder()
                .email(email).username(email.replace("@test.com", "")).password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
    }

    /**
     * JPA 로는 못 심는 "가입 30일 지남" 을 저장 직후 직접 UPDATE 로 만든다. {@code entityManager.clear()}
     * 가 필요한 이유: 이 테스트 메서드 전체(설정 + MockMvc 호출)가 같은 트랜잭션·같은 영속성
     * 컨텍스트를 공유해서, JDBC 로 DB 행을 바꿔도 1차 캐시에 남은 {@code member} 인스턴스는 옛
     * createdAt 을 그대로 들고 있다 — 이후 JWT 인증이 {@code CustomUserDetailsService} 로 다시
     * 조회할 때 그 캐시를 맞게 된다(MemberDeletionCascadeIntegrationTest 와 같은 패턴).
     */
    private void backdateSignup(Member member, int daysAgo) {
        jdbcTemplate.update("UPDATE users SET created_at = ? WHERE id = ?",
                LocalDateTime.now().minusDays(daysAgo), member.getId());
        entityManager.clear();
    }

    @Test
    @DisplayName("가입 4주 미만이면 세 endpoint 모두 sufficientData=false, 빈 결과")
    void freshMember_allThreeEndpoints_returnInsufficientData() throws Exception {
        Member member = freshMember("pattern-fresh@test.com");
        String token = tokenFor(member);

        mockMvc.perform(get("/patterns/periodicity").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sufficientData").value(false))
                .andExpect(jsonPath("$.byDayOfWeek").isEmpty())
                .andExpect(jsonPath("$.byTimeOfDay").isEmpty());

        mockMvc.perform(get("/patterns/intensity-trend").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sufficientData").value(false))
                // 데이터가 없어도 주간 배열 자체는 4칸 고정이다(세션3 설계) — sufficientData 로만 구분.
                .andExpect(jsonPath("$.weeklyTrend.length()").value(4))
                .andExpect(jsonPath("$.weeklyTrend[0].avgSyncRate").doesNotExist());

        mockMvc.perform(get("/patterns/consistency").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sufficientData").value(false))
                .andExpect(jsonPath("$.currentStreakDays").value(0));
    }

    @Test
    @DisplayName("가입 4주 이상 + 오늘 완료 세션 하나 — 세 endpoint 모두 실데이터 반영")
    void seasonedMember_withTodaySession_reflectsRealData() throws Exception {
        Member member = freshMember("pattern-seasoned@test.com");
        String token = tokenFor(member);

        // 고정 시각(예: 19시)을 박으면 실제 실행 시각이 그보다 이르면 "미래" 세션이 돼
        // periodicity/intensity-trend 의 end=now() 범위에서 빠진다 — 항상 "지금보다 조금
        // 전"으로 잡고, 기대 시간대는 그 실제 시각에서 역산한다.
        LocalDateTime start = LocalDateTime.now().minusMinutes(5);
        TimeBucket expectedBucket = TimeBucket.of(start.toLocalTime());
        sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(start).endTime(start.plusMinutes(30))
                .status(Status.COMPLETED).totalReps(20).difficultyLevel(1)
                .avgSyncRate(new BigDecimal("77.5")).caloriesBurned(new BigDecimal("120.0"))
                .build());

        // 모든 엔티티 생성이 끝난 뒤에 마지막으로 backdate — clear() 가 member/exercise 를
        // detach 시키므로, 그 전에 하면 위 Session 저장이 detached 참조로 깨질 수 있다.
        backdateSignup(member, 30);

        mockMvc.perform(get("/patterns/periodicity").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sufficientData").value(true))
                .andExpect(jsonPath("$.byDayOfWeek.length()").value(1))
                .andExpect(jsonPath("$.byDayOfWeek[0].sessionCount").value(1))
                .andExpect(jsonPath("$.byTimeOfDay[0].bucket").value(expectedBucket.name()))
                .andExpect(jsonPath("$.byTimeOfDay[0].sessionCount").value(1));

        mockMvc.perform(get("/patterns/intensity-trend").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sufficientData").value(true))
                .andExpect(jsonPath("$.weeklyTrend.length()").value(4))
                // 이번 주(마지막 버킷)만 값이 채워지고 나머지 세 주는 세션이 없어 null/0 이어야 한다.
                .andExpect(jsonPath("$.weeklyTrend[3].avgSyncRate").value(77.5))
                .andExpect(jsonPath("$.weeklyTrend[3].totalMinutes").value(30))
                .andExpect(jsonPath("$.weeklyTrend[0].avgSyncRate").doesNotExist());

        mockMvc.perform(get("/patterns/consistency").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sufficientData").value(true))
                .andExpect(jsonPath("$.currentStreakDays").value(1))
                .andExpect(jsonPath("$.missedDaysInLast4Weeks").value(27));
    }
}
