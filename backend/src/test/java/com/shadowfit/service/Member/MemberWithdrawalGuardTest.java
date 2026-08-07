package com.shadowfit.service.Member;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 회원 탈퇴 가드 — <b>운동이 실제로 진행 중일 때만</b> 막는지 검증
 * (docs/decisions/withdrawal-with-active-session.md, 이슈 #87).
 *
 * <p><b>이 테스트의 핵심 관심사는 "진행 중"의 정의</b>다. 세션 상태({@code IN_PROGRESS})만 보고
 * 막으면 앱이 죽어 남은 좀비 세션 때문에 <b>운동 중이 아닌 사용자가 최대 ~45분간 탈퇴하지
 * 못한다</b>(타임아웃 스케줄러가 걷어갈 때까지). 그래서 판정을 프레임 유입으로 하며, 아래 두
 * 시나리오가 정확히 그 차이를 가른다 — <b>둘 다 세션 상태는 IN_PROGRESS 로 같고 프레임의 나이만
 * 다르다.</b>
 *
 * <p>{@code created_at} 을 직접 넣는 이유: 이 컬럼은 DB DEFAULT 가 채우고 엔티티 매핑은 읽기
 * 전용이라, 테스트가 "10분 전에 들어온 프레임"을 만들려면 SQL 로 직접 쓰는 수밖에 없다.
 */
@SpringBootTest
@Transactional
@DisplayName("회원 탈퇴 가드 — 진행 중 운동")
class MemberWithdrawalGuardTest {

    @Autowired private MemberService memberService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * 경계 계산에 쓸 임계값. <b>서비스와 같은 표현식(기본값 포함)</b>을 쓴다 — 그래야 설정을
     * 바꾸든 안 바꾸든 테스트가 항상 서비스와 같은 값을 본다. 테스트 프로파일에는 이 설정이
     * 없으므로 양쪽 다 기본값으로 떨어진다.
     */
    @Value("${member.withdrawal.active-workout-idle-seconds:180}")
    private long idleSeconds;

    private Member member;
    private Exercise exercise;

    @BeforeEach
    void setUp() {
        member = memberRepository.saveAndFlush(Member.builder()
                .email("withdrawal-guard@test.com").username("탈퇴가드")
                .password("dummy").role(UserRole.USER).build());

        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(ExerciseCategory.LOWER).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00"))
                .build());
    }

    @Test
    @DisplayName("프레임이 계속 들어오는 세션이 있으면 탈퇴가 거절된다")
    void blocksWithdrawal_whenFramesStillArriving() {
        Long sessionId = startedSession();
        insertFrame(sessionId, LocalDateTime.now().minusSeconds(3)); // 방금 들어온 프레임

        assertThatThrownBy(() -> memberService.deleteAccount(member.getEmail()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.WITHDRAWAL_BLOCKED_BY_ACTIVE_SESSION);

        assertThat(memberRepository.findById(member.getId()))
                .as("거절됐으므로 회원이 남아 있어야 한다").isPresent();
    }

    /**
     * 임계값의 기준이 배치 간격이 아니라 <b>세트 간 휴식</b>임을 고정하는 회귀 테스트.
     *
     * <p>휴식 중에는 rep 이 완성되지 않아 AI 가 콜백을 보내지 않으므로, Spring 이 보기엔 죽은 세션과
     * 구분되지 않는다. {@code restTimeSec = max(90 - (level-1)*5, 30)} 이라 초급자는 90초까지 쉰다
     * (12-persona-difficulty.md:90). 임계값을 배치 간격(~3~4초) 기준으로 짧게 잡으면 <b>쉬는 중인
     * 사용자가 죽은 것으로 판정돼 정말 운동 중인데 탈퇴가 통과</b>한다 — 막으려던 결함 그 자체다.
     *
     * <p>이 프로젝트는 이미 같은 함정으로 ET-C(AI timeout 자동 종료)를 거부한 적이 있다
     * (tts-design.md §2.A).
     */
    @Test
    @DisplayName("세트 사이 휴식(90초) 중이면 여전히 운동 중이므로 탈퇴가 거절된다")
    void blocksWithdrawal_duringRestBetweenSets() {
        Long sessionId = startedSession();
        insertFrame(sessionId, LocalDateTime.now().minusSeconds(90)); // 초급자 최대 휴식

        assertThatThrownBy(() -> memberService.deleteAccount(member.getEmail()))
                .as("휴식은 운동의 일부다 — 죽은 세션으로 오판하면 안 된다")
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.WITHDRAWAL_BLOCKED_BY_ACTIVE_SESSION);
    }

    @Test
    @DisplayName("IN_PROGRESS 지만 프레임이 끊긴 좀비 세션은 탈퇴를 막지 않는다")
    void allowsWithdrawal_whenSessionIsZombie() {
        Long sessionId = startedSession();
        insertFrame(sessionId, LocalDateTime.now().minusMinutes(10)); // 앱이 죽어 유입이 끊긴 상태

        assertThatCode(() -> memberService.deleteAccount(member.getEmail()))
                .as("운동 중이 아닌데 상태값 때문에 막히면 안 된다").doesNotThrowAnyException();
    }

    /**
     * 임계값 자체를 고정하는 경계 테스트. 위 두 케이스(3초 / 10분)는 간격이 너무 넓어
     * 임계값을 60초로 바꾸든 300초로 바꾸든 전부 통과한다 — 값이 무엇이든 테스트가 침묵한다.
     *
     * <p><b>"정확히 임계값" 케이스를 쓰지 않는 이유</b>: {@code countSince} 는
     * {@code createdAt > since} 로 판정하는데, {@code since} 는 서비스가 자기 {@code now} 로
     * 계산한다. 테스트가 만든 "정확히 N초 전" 프레임은 서비스의 {@code now} 가 항상 조금 뒤라
     * <b>언제나 엄격히 더 오래된</b> 값이 된다. 그래서 그 케이스는 {@code >} 든 {@code >=} 든
     * 똑같이 통과해 비교 연산자를 고정해주지 못한다. 대신 임계값 양옆 10초로 값을 고정한다.
     */
    @Test
    @DisplayName("임계값보다 최근 프레임이면 거절된다 (경계 안쪽)")
    void blocksWithdrawal_justInsideThreshold() {
        Long sessionId = startedSession();
        insertFrame(sessionId, LocalDateTime.now().minusSeconds(idleSeconds - 10));

        assertThatThrownBy(() -> memberService.deleteAccount(member.getEmail()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.WITHDRAWAL_BLOCKED_BY_ACTIVE_SESSION);
    }

    @Test
    @DisplayName("임계값보다 오래된 프레임이면 탈퇴된다 (경계 바깥쪽)")
    void allowsWithdrawal_justOutsideThreshold() {
        Long sessionId = startedSession();
        insertFrame(sessionId, LocalDateTime.now().minusSeconds(idleSeconds + 10));

        assertThatCode(() -> memberService.deleteAccount(member.getEmail()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("IN_PROGRESS 세션에 프레임이 하나도 없으면 탈퇴를 막지 않는다")
    void allowsWithdrawal_whenSessionHasNoFrames() {
        // 세션만 만들어지고 첫 프레임이 오기 전에 앱이 죽은 경우
        startedSession();

        assertThatCode(() -> memberService.deleteAccount(member.getEmail()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("진행 중 세션이 없으면 종전대로 탈퇴된다")
    void allowsWithdrawal_whenNoActiveSession() {
        sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(LocalDateTime.now().minusHours(1)).endTime(LocalDateTime.now())
                .status(Status.COMPLETED).totalReps(10).difficultyLevel(1).build());

        assertThatCode(() -> memberService.deleteAccount(member.getEmail()))
                .doesNotThrowAnyException();
    }

    private Long startedSession() {
        return sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(LocalDateTime.now().minusMinutes(5))
                .status(Status.IN_PROGRESS).totalReps(0).difficultyLevel(1).build()).getId();
    }

    /**
     * 프레임 1건을 지정한 시각으로 적재. {@code created_at} 은 엔티티에서 읽기 전용이라
     * (쓰기는 PoseDataService 의 JdbcTemplate 배치 담당) SQL 로 직접 넣어야 나이를 통제할 수 있다.
     */
    private void insertFrame(Long sessionId, LocalDateTime createdAt) {
        // smoothed_knee_angle 을 명시한다. 엔티티에 @Builder.Default = 0.0 이 있지만 그것은
        // **자바 객체를 만들 때** 채워지는 값이라 이 raw SQL 경로에는 오지 않는다. 운영
        // 스키마(V1__baseline.sql)는 DEFAULT 0.00 이 있어 생략해도 통과하지만, 테스트의 H2
        // DDL 은 @Column(nullable=false) 만 보고 NOT NULL 을 만들 뿐 DEFAULT 를 만들지 않는다.
        // 즉 이 컬럼은 운영에서는 생략 가능하고 테스트에서는 아니다 — 그 차이가 여기서 드러난다.
        jdbcTemplate.update(
                "INSERT INTO pose_data (session_id, rep_number, timestamp_sec, joint_coordinates, "
                        + "sync_rate, smoothed_knee_angle, feedback_message, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                sessionId, 1, 1.0, "{}", 72.5, 0.0, "ok", createdAt);
    }
}
