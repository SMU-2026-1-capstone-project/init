package com.shadowfit.service.Exercise;

import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 고아 창의 <b>폭</b> 측정 (이슈 #87). {@link PoseDataOrphanRaceTest} 가 "그 순서면 고아가
 * 남는다"를 증명했다면, 이 하네스는 <b>"그 순서가 될 구간이 얼마나 넓나"</b>를 잰다.
 * 둘을 곱해야 발생 빈도의 상한이 나오고, 그 상한이 있어야 수정안을 저울질할 수 있다 —
 * 락은 비용이 확실한데(배치 저장 전건) 이득이 불확실하다(드문 결함).
 *
 * <p><b>왜 ghz 가 아니라 여기인가.</b> 창은 {@code savePoseDataBatch} 검증 통과부터 커밋까지라
 * <b>gRPC 계층이 창에 들어가지 않는다</b> — ghz 를 태워도 재는 구간은 같다. 반면 백분위를
 * 읽으려면 {@code /actuator/metrics}(count·total·max 만 준다) 대신 prometheus 레지스트리
 * 의존성을 새로 넣어야 하는데, 여기서는 {@code takeSnapshot()} 으로 그냥 읽힌다.
 *
 * <p><b>세션을 라운드로빈하는 이유.</b> 한 세션에 몰아 쏘면 같은 행을 두고 직렬화돼 가짜 천장이
 * 생긴다 — 이 프로젝트가 이미 겪은 함정이다({@code loadtest/ghz/gen_batch_multi.py} 귀속 분석).
 * 실제 DAU 부하는 서로 다른 세션이므로 세션을 나눠 쏜다.
 *
 * <p><b>⚠️ 절대값 해석 주의.</b> 이 박스는 MySQL(도커)과 JVM 이 동거하는 개발 머신이라
 * 절대 수치는 환경 종속이다(프로젝트가 이미 못박은 전제). 신뢰할 것은 <b>자릿수</b>와
 * <b>동시성에 따른 상대 변화</b>이지 소수점이 아니다.
 *
 * <p>실행법은 {@link PoseDataOrphanRaceTest} 와 같다(3307 컨테이너 + {@code -Drace.mysql=true}).
 */
@SpringBootTest
@ActiveProfiles("race")
@EnabledIfSystemProperty(named = "race.mysql", matches = "true",
        disabledReason = "실제 MySQL(3307)이 필요 — PoseDataOrphanRaceTest 주석의 docker 명령 참고")
@DisplayName("pose_data 고아 창 폭 측정")
class PoseDataOrphanWindowTest {

    private static final String WINDOW_METRIC = "shadowfit.pose.orphan.window";
    private static final int SESSION_COUNT = 30;   // 라운드로빈 대상 — 세션 간 직렬화 회피
    private static final int FRAMES_PER_BATCH = 10; // DOWNSAMPLE_WINDOW=5 → 배치당 2행

    /**
     * 실제 MediaPipe 33 랜드마크 크기의 페이로드(~2KB). {@code "{}"} 로 재면 INSERT 비용을
     * 과소평가해 창이 실제보다 좁게 나온다 — 이 컬럼은 InnoDB off-page 저장 대상이라
     * 크기가 곧 비용이다({@code realmysql-experiments.md} ②).
     */
    private static final String JOINT_COORDINATES = buildLandmarksJson();

    private static String buildLandmarksJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 33; i++) {
            if (i > 0) sb.append(',');
            double b = ((i * 7) % 1000) / 1000.0;
            sb.append("{\"x\":").append(String.format("%.6f", 0.30 + b * 0.40))
              .append(",\"y\":").append(String.format("%.6f", 0.20 + ((b * 17) % 1.0) * 0.60))
              .append(",\"z\":").append(String.format("%.6f", -0.25 + ((b * 13) % 1.0) * 0.50))
              .append(",\"visibility\":").append(String.format("%.6f", 0.85 + ((b * 11) % 1.0) * 0.15))
              .append('}');
        }
        return sb.append(']').toString();
    }

    @Autowired private PoseDataService poseDataService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MeterRegistry meterRegistry;

    private Long memberId;
    private final List<Long> sessionIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Long id : sessionIds) {
            jdbcTemplate.update("DELETE FROM pose_data WHERE session_id = ?", id);
        }
        if (memberId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", memberId);
        }
    }

    @Test
    @DisplayName("검증 통과 → 커밋 구간의 폭을 동시성별로 잰다")
    void measureWindow() throws Exception {
        seedSessions();

        // 워밍업은 버린다 — cold JVM 은 인터프리터 모드라 2~5배 느리다. 이걸 본측정에 섞으면
        // 창이 실제보다 넓게 나온다(db-deep-dive.md §3 "워밍업 통제 필수").
        runPhase(4, 25);

        List<String> report = new ArrayList<>();
        report.add("");
        report.add("=== 고아 창 폭 (검증 통과 → 커밋), 이슈 #87 ===");
        report.add(String.format("%-6s %-8s %10s %10s %10s %10s %10s",
                "동시성", "표본", "p50", "p90", "p95", "p99", "max"));

        for (int concurrency : new int[]{1, 10, 30}) {
            int perThread = concurrency == 1 ? 150 : 40;
            HistogramSnapshot snap = runPhase(concurrency, perThread);
            report.add(String.format("%-6d %-8d %10s %10s %10s %10s %10s",
                    concurrency, snap.count(),
                    ms(percentile(snap, 0.5)), ms(percentile(snap, 0.9)),
                    ms(percentile(snap, 0.95)), ms(percentile(snap, 0.99)),
                    ms(snap.max(TimeUnit.MILLISECONDS))));
            assertThat(snap.count()).as("동시성 %d 표본이 잡혀야 한다", concurrency).isPositive();
        }

        report.add("");
        System.out.println(String.join(System.lineSeparator(), report));
    }

    /** 한 페이즈를 돌리고 그 구간만의 스냅샷을 반환. 페이즈 간 간섭 없게 타이머를 지우고 시작한다. */
    private HistogramSnapshot runPhase(int concurrency, int batchesPerThread) throws Exception {
        resetTimer();

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(concurrency);
        AtomicReference<Throwable> firstError = new AtomicReference<>();

        for (int t = 0; t < concurrency; t++) {
            final int threadIndex = t;
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < batchesPerThread; i++) {
                        // 스레드마다 다른 세션에서 출발해 같은 행 경합을 피한다
                        Long sessionId = sessionIds.get((threadIndex + i * concurrency) % SESSION_COUNT);
                        poseDataService.savePoseDataBatch(sessionId, oneRep(i));
                    }
                } catch (Throwable e) {
                    firstError.compareAndSet(null, e);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean finished = doneGate.await(5, TimeUnit.MINUTES);
        pool.shutdownNow();

        assertThat(firstError.get()).as("배치 저장이 실패하면 측정이 무의미하다").isNull();
        assertThat(finished).as("동시성 %d 페이즈가 시간 안에 끝나야 한다", concurrency).isTrue();

        return timer().takeSnapshot();
    }

    private Timer timer() {
        Timer timer = meterRegistry.find(WINDOW_METRIC).timer();
        assertThat(timer).as("계측이 등록돼야 한다 — PoseDataService.recordOrphanWindow 확인").isNotNull();
        return timer;
    }

    private void resetTimer() {
        Timer existing = meterRegistry.find(WINDOW_METRIC).timer();
        if (existing != null) {
            meterRegistry.remove(existing);
        }
    }

    private double percentile(HistogramSnapshot snap, double p) {
        for (ValueAtPercentile v : snap.percentileValues()) {
            if (Math.abs(v.percentile() - p) < 1e-9) {
                return v.value(TimeUnit.MILLISECONDS);
            }
        }
        return Double.NaN;
    }

    private String ms(double value) {
        return Double.isNaN(value) ? "-" : String.format("%.2fms", value);
    }

    private void seedSessions() {
        Member member = memberRepository.saveAndFlush(Member.builder()
                .email("orphan-window@test.com").username("창폭측정").password("dummy")
                .role(UserRole.USER).build());
        memberId = member.getId();

        Exercise exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(ExerciseCategory.LOWER).expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00"))
                .build());

        for (int i = 0; i < SESSION_COUNT; i++) {
            Session session = sessionRepository.saveAndFlush(Session.builder()
                    .member(member).exercise(exercise).startTime(LocalDateTime.now())
                    .status(Status.IN_PROGRESS).totalReps(0).difficultyLevel(1).build());
            sessionIds.add(session.getId());
        }
    }

    /** 한 배치 = 한 rep. 무릎각만 스쿼트 한 회의 모양으로 움직인다(PoseDataServiceTest 와 같은 전제). */
    private List<PoseDataRequest> oneRep(int repNumber) {
        List<PoseDataRequest> frames = new ArrayList<>();
        int half = FRAMES_PER_BATCH / 2;
        for (int i = 0; i < FRAMES_PER_BATCH; i++) {
            double kneeAngle = i <= half ? 150.0 - (60.0 * i / half)
                                         : 90.0 + (60.0 * (i - half) / half);
            frames.add(PoseDataRequest.newBuilder()
                    .setTimestampSec(i * 0.1)
                    .setJointCoordinates(JOINT_COORDINATES)
                    .setSyncRate(72.5)
                    .setRepNumber(repNumber + 1)
                    .setSmoothedKneeAngle(kneeAngle)
                    .setFeedbackMessage("ok")
                    .build());
        }
        return frames;
    }
}
