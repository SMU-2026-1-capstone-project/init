package com.shadowfit.service.Exercise;

import com.shadowfit.grpc.SessionCompleteRequest;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.model.exercise.PoseData;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 싱크 통계 집계 테스트 (이슈 #75).
 *
 * <p>세션 완료 시 avg/max/min sync 를 <b>AI 가 보낸 값이 아니라 {@code pose_data} 에서 직접</b>
 * 집계하도록 바꾼 것을 고정한다. 여기서 지키는 것은 세 가지다:
 *
 * <ol>
 *   <li><b>rep 가중</b>이어야 한다 — 다운샘플 때문에 프레임 단위로 평균 내면 값이 달라진다</li>
 *   <li>재부착 세션도 <b>전 구간</b>이 반영돼야 한다 — AI 는 재부착 이후 rep 만 갖고 있다</li>
 *   <li>측정된 rep 이 없으면 <b>0 이 아니라 null</b> 이어야 한다 — 0 은 월 평균을 끌어내린다</li>
 * </ol>
 */
@SpringBootTest
@Transactional
@DisplayName("싱크 통계 집계 테스트 (#75)")
class SessionSyncStatsTest {

    @Autowired private SessionService sessionService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private com.shadowfit.repository.exercise.CategoryRepository categoryRepository;
    @Autowired private PoseDataRepository poseDataRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    // 발행기가 테스트 도중 돌면 검증이 흔들린다 (SessionServiceTest 와 같은 이유).
    @MockitoBean private OutboxPublisher outboxPublisher;

    private Session session;

    @BeforeEach
    void setUp() {
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email("syncstats@test.com").username("syncstats").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        Category category = categoryRepository.save(Category.builder().name("LOWER").build());
        Exercise exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(category).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00"))
                .analysisSupported(true).build());
        session = sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(LocalDateTime.now().minusMinutes(10))
                .status(Status.IN_PROGRESS).build());
    }

    /** 한 rep 의 프레임들은 같은 sync_rate 를 공유한다 (ai-server {@code pose.py} 가 그렇게 채운다). */
    private void saveRep(int repNumber, double syncRate, int frameCount) {
        for (int i = 0; i < frameCount; i++) {
            poseDataRepository.save(PoseData.builder()
                    .session(session)
                    .repNumber(repNumber)
                    .timestampSec(repNumber * 10.0 + i)
                    .jointCoordinates("{}")
                    .syncRate(syncRate)
                    .build());
        }
        poseDataRepository.flush();

        // created_at 을 세션 시작 시각으로 맞춘다 (#392 앵커 등호 조회의 전제).
        //
        // 엔티티가 이 컬럼을 insertable=false 로 막아 둬서 JPA 픽스처로는 못 넣는다 — 프로덕션에서
        // 그 값을 넣는 것은 PoseDataService 의 JdbcTemplate 배치다(#188). 안 맞추면 rep 평균
        // 조회가 0행을 받아, 이 테스트가 «집계가 틀렸다» 가 아니라 «데이터가 없다» 로 깨진다.
        jdbcTemplate.update("UPDATE pose_data SET created_at = ? WHERE session_id = ?",
                session.getStartTime(), session.getId());
    }

    private void completeWithAiReported(double aiAvgSyncRate) {
        sessionService.applyComplete(SessionCompleteRequest.newBuilder()
                .setSessionId(session.getId())
                .setTotalReps(3)
                .setAvgSyncRate(aiAvgSyncRate)
                .setMaxSyncRate(aiAvgSyncRate)
                .setMinSyncRate(aiAvgSyncRate)
                .setCaloriesBurned(10.0)
                .build());
    }

    private Session reload() {
        sessionRepository.flush();
        return sessionRepository.findById(session.getId()).orElseThrow();
    }

    /**
     * 이 테스트가 핵심이다 — 두 계산 방식의 값을 <b>일부러 다르게</b> 만들어 어느 쪽인지 못 숨기게 한다.
     *
     * <p>rep1(sync 50)은 프레임 4개, rep2(sync 100)는 1개다. 다운샘플이 rep 마다 다른 수의 행을
     * 남기기 때문에 실제로 이런 모양이 된다.
     *
     * <ul>
     *   <li>프레임 가중(틀림): (50×4 + 100×1) / 5 = <b>60</b></li>
     *   <li>rep 가중(맞음): (50 + 100) / 2 = <b>75</b></li>
     * </ul>
     *
     * GROUP BY rep_number 를 빼면 60 이 나와서 여기서 깨진다.
     */
    @Test
    @DisplayName("rep 가중 평균이다 — 프레임 수가 많은 rep 이 더 무거워지지 않는다")
    void rep가중_평균() {
        saveRep(1, 50.0, 4);
        saveRep(2, 100.0, 1);

        completeWithAiReported(0.0);

        Session saved = reload();
        assertThat(saved.getAvgSyncRate()).isEqualByComparingTo("75.00");
        assertThat(saved.getAvgSyncRate()).as("프레임 가중이면 60 이 나온다").isNotEqualByComparingTo("60.00");
    }

    /**
     * 재부착 세션 회귀 — AI 는 재부착 이후 rep 만 갖고 있어서 평균이 후반 구간 기준이 된다.
     * {@code pose_data} 에는 재부착 이전 rep 도 남아 있으므로 Spring 이 집계하면 전 구간이 나온다.
     */
    @Test
    @DisplayName("재부착 세션도 전 구간이 반영된다 — AI 가 보낸 후반 구간 값을 쓰지 않는다")
    void 재부착_전구간() {
        saveRep(1, 90.0, 2);   // 재부착 이전 — AI 메모리엔 없다
        saveRep(2, 90.0, 2);   // 재부착 이전
        saveRep(3, 30.0, 2);   // 재부착 이후 — AI 는 이것만 안다

        completeWithAiReported(30.0);  // AI 가 보고한 값 = 후반 구간만

        Session saved = reload();
        assertThat(saved.getAvgSyncRate()).isEqualByComparingTo("70.00");  // (90+90+30)/3
        assertThat(saved.getMaxSyncRate()).isEqualByComparingTo("90.00");
        assertThat(saved.getMinSyncRate()).isEqualByComparingTo("30.00");
    }

    /**
     * max/min 회귀 — AI 가 proto 로 보내는데도 {@code applyComplete} 가 {@code set} 을 안 해서
     * 컬럼이 <b>항상 NULL</b> 이었다. 재부착과 무관한, 처음부터 있던 누락이다.
     */
    @Test
    @DisplayName("max/min 도 저장된다 — 이전엔 항상 NULL 이었다")
    void maxmin_저장() {
        saveRep(1, 40.0, 1);
        saveRep(2, 80.0, 1);

        completeWithAiReported(60.0);

        Session saved = reload();
        assertThat(saved.getMaxSyncRate()).isNotNull().isEqualByComparingTo("80.00");
        assertThat(saved.getMinSyncRate()).isNotNull().isEqualByComparingTo("40.00");
    }

    /**
     * 이게 이 이슈의 출발점이다 — 0 을 저장하면 커밋 {@code 0914082} 가 넣은
     * {@code filter(Objects::nonNull)} 방어를 그냥 통과해서 월 평균을 끌어내린다.
     * "측정 안 됨"은 0% 가 아니다.
     */
    @Test
    @DisplayName("측정된 rep 이 없으면 0 이 아니라 null — 월 평균 집계가 걸러낼 수 있어야 한다")
    void 측정없음_null() {
        completeWithAiReported(0.0);  // pose_data 없음. AI 는 0.0 을 보낸다

        Session saved = reload();
        assertThat(saved.getAvgSyncRate()).as("0.0 이면 nonNull 필터를 통과해 평균에 섞인다").isNull();
        assertThat(saved.getMaxSyncRate()).isNull();
        assertThat(saved.getMinSyncRate()).isNull();
    }

    /**
     * {@code rep_number = 0} 은 "미상"이다 — 컬럼이 생기기 전 데이터와 구버전 AI 의 행.
     * 서로 다른 rep 이 하나로 뭉뚱그려지므로 집계에서 빼야 한다.
     */
    @Test
    @DisplayName("rep_number = 0 (미상) 행은 집계에서 제외한다")
    void 미상행_제외() {
        saveRep(0, 10.0, 5);   // 구버전 데이터 — 섞이면 평균이 내려간다
        saveRep(1, 80.0, 1);

        completeWithAiReported(0.0);

        Session saved = reload();
        assertThat(saved.getAvgSyncRate()).isEqualByComparingTo("80.00");
    }
}
