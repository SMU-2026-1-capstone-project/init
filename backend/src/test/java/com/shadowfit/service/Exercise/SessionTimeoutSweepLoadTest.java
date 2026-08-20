package com.shadowfit.service.Exercise;

import com.shadowfit.model.exercise.Status;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 타임아웃 스윕 1회의 <b>할당량·소요시간</b>이 IN_PROGRESS 세션 수 N 에 어떻게 반응하는지 잰다 (#207).
 *
 * <p><b>무엇을 묻는가.</b> {@code SessionTimeoutScheduler.sweep()} 은
 * {@code findByStatus(IN_PROGRESS)} 로 <b>진행 중 세션 전부</b>를 엔티티로 물린 뒤 Java 에서 거른다.
 * 제한도 페이징도 프로젝션도 없다. 그러면 적재량이 「타임아웃된 세션 수」가 아니라
 * 「진행 중 세션 전체」에 비례하고, <b>세션이 안 끝날수록 스윕이 무거워지는 방향</b>이 된다.
 *
 * <p><b>핵심 조작 — 걷어갈 대상을 0 으로 둔다.</b> 모든 IN_PROGRESS 세션의 {@code last_active_at} 을
 * 판 직전 시각으로 맞춘다. 그러면 임계가 {@code lastActiveAt + idleMinutes}(기본 10분) 라 하나도
 * 타임아웃이 아니다. <b>일이 0 인데도 적재가 일어난다</b>는 것이 #207 의 요점이므로, 이 조건에서
 * 재야 「할 일이 많아서 오래 걸린 것」과 갈린다. 아래 {@code assertNothingCollected} 가 매 판마다
 * 이 전제를 다시 확인한다 — 확인 없이는 재는 것이 무엇인지 말할 수 없다.
 *
 * <h2>설계</h2>
 * <ul>
 *   <li><b>버림판</b> — 레벨마다 한 판씩 먼저 돌리고 버린다. 첫 판에는 JIT·클래스 로딩·커넥션 풀
 *       채우기가 섞여 있어 그대로 세면 «첫 레벨이 제일 비싸다» 는 가짜 결론이 나온다</li>
 *   <li><b>라틴 방격 3라운드</b> — 팔당 1판이면 «레벨» 과 «판 순서» 가 같은 축에 겹쳐 원리적으로
 *       분리가 안 된다. 순환 라틴 방격(ABC / BCA / CAB)으로 각 레벨이 각 위치에 정확히 한 번
 *       오게 해서 시간 추세를 상쇄한다</li>
 *   <li><b>지표는 할당량이 우선</b> — 힙 사용량은 GC 타이밍에 흔들려 «판 간 변동이 재려던 효과보다
 *       큰» 함정에 빠지기 쉽다(#87 에서 락 비용을 그렇게 놓쳤다). {@code getCurrentThreadAllocatedBytes}
 *       는 누적 할당이라 GC 와 무관하고, #207 이 말하는 «적재량» 에 직접 답한다.
 *       소요시간은 같이 찍되 <b>보조</b>로 본다</li>
 * </ul>
 *
 * <h2>읽는 법</h2>
 * 이 환경은 2코어에 MySQL·백엔드가 동거하므로 <b>절대치는 인용하지 않는다.</b>
 * 보는 것은 <b>N 대비 기울기</b> 하나다.
 * <ul>
 *   <li>할당량이 N 에 선형 → #207 확정</li>
 *   <li>할당량이 N 에 평탄 → <b>#207 이 틀린 것이다</b> (JPA 가 예상과 달리 스트리밍하는 등)</li>
 * </ul>
 *
 * <h2>실행법</h2>
 * <pre>
 *   docker run -d --name shadowfit-sweep-mysql -e MYSQL_ROOT_PASSWORD=sweeptest \
 *     -e MYSQL_DATABASE=shadowfit -p 3308:3306 mysql:8.0 \
 *     --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
 *   ./gradlew :backend:test --tests '*SessionTimeoutSweepLoadTest' -Dsweep.load=true
 * </pre>
 *
 * <p>⚠️ 시스템 프로퍼티가 없으면 통째로 건너뛴다. CI 는 영향받지 않는다.
 */
@SpringBootTest
@ActiveProfiles("sweepload")
@EnabledIfSystemProperty(named = "sweep.load", matches = "true")
@DisplayName("#207 — 타임아웃 스윕 적재량이 진행 중 세션 수에 비례하는가")
class SessionTimeoutSweepLoadTest {

    /**
     * 레벨. 4배씩, 최상단이 최하단의 16배다.
     *
     * <p>🔴 <b>처음엔 1천/1만/10만이었고 그 리그는 못 돌았다</b>(2026-08-17). N=100,000 판에서
     * 스윕 하나가 이 박스를 통째로 붙들어, 옆에서 도는 리셋 UPDATE 가 단독 8.4초짜리에서
     * <b>분당 1,070행</b>으로 무너졌다 — 완주 추정 22시간. 되돌리면서 상단을 16,000 으로 낮췄다.
     *
     * <p>16배면 선형과 평탄을 가르기에 충분하다. 이 리그가 답하는 질문은 «기울기가 있는가» 이지
     * «10만에서 몇 초인가» 가 아니다 — 후자는 어차피 이 환경에서 인용할 수 없는 값이다.
     */
    private static final int[] LEVELS = {1_000, 4_000, 16_000};

    /** 시딩 총량. 가장 큰 레벨을 덮으면 되고, 판마다 status 를 갈아 N 을 만든다. */
    private static final int SEED_TOTAL = 16_000;

    /** 순환 라틴 방격 — 각 레벨이 각 위치에 정확히 한 번 온다. */
    private static final int[][] LATIN_SQUARE = {
            {0, 1, 2},
            {1, 2, 0},
            {2, 0, 1},
    };

    private static final int SEED_CHUNK = 5_000;

    @Autowired
    private SessionTimeoutScheduler scheduler;

    @Autowired
    private JdbcTemplate jdbc;

    private static boolean seeded = false;

    @BeforeAll
    static void banner() {
        seeded = false;
    }

    @Test
    @DisplayName("걷어갈 대상이 0 인데도 적재가 N 에 비례하는지")
    void sweepLoadScalesWithInProgressCount() {
        seedOnce();

        // ── 버림판 ────────────────────────────────────────────────────────────
        // 결과를 쓰지 않는다. JIT·클래스 로딩·커넥션 풀 채우기를 여기서 태운다.
        for (int level : LEVELS) {
            setInProgressCount(level);
            measureOneSweep();
        }

        // ── 본판 ─────────────────────────────────────────────────────────────
        Map<Integer, List<Sample>> byLevel = new LinkedHashMap<>();
        for (int level : LEVELS) {
            byLevel.put(level, new ArrayList<>());
        }

        for (int round = 0; round < LATIN_SQUARE.length; round++) {
            for (int position = 0; position < LATIN_SQUARE[round].length; position++) {
                int level = LEVELS[LATIN_SQUARE[round][position]];
                setInProgressCount(level);

                Sample sample = measureOneSweep();
                assertNothingCollected(level);

                byLevel.get(level).add(sample);
                System.out.printf("  R%d P%d  N=%,7d  alloc=%,12d B  %,8.1f ms%n",
                        round + 1, position + 1, level, sample.allocatedBytes, sample.millis());
            }
        }

        report(byLevel);

        // 판정은 사람이 한다 — 이 테스트는 재는 장치다. 여기서 단언하는 것은 «잰 것이
        // 무엇인지» 뿐이다: 아홉 판이 전부 성립했고, 그동안 걷어간 세션이 하나도 없다.
        for (int level : LEVELS) {
            assertThat(byLevel.get(level)).hasSize(LATIN_SQUARE.length);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private record Sample(long allocatedBytes, long nanos) {
        double millis() {
            return nanos / 1_000_000.0;
        }
    }

    /**
     * 판 하나. 스윕을 호출한 스레드의 누적 할당 증가분과 벽시계를 잰다.
     *
     * <p>스윕은 이 스레드에서 동기로 돈다({@code @Scheduled} 를 안 태우고 직접 부른다).
     * AI 통보는 gRPC 가 아니라 아웃박스 행 INSERT 라 다른 스레드로 새지 않는다 —
     * 다만 이 판은 걷어갈 대상이 0 이라 그 경로 자체가 안 돈다.
     */
    private Sample measureOneSweep() {
        com.sun.management.ThreadMXBean tmx =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

        long allocBefore = tmx.getCurrentThreadAllocatedBytes();
        long t0 = System.nanoTime();

        scheduler.checkAndTimeoutSessions();

        long nanos = System.nanoTime() - t0;
        long allocated = tmx.getCurrentThreadAllocatedBytes() - allocBefore;

        return new Sample(allocated, nanos);
    }

    /**
     * IN_PROGRESS 를 정확히 {@code n} 건으로 맞추고, 그 전부를 <b>타임아웃 대상이 아니게</b> 만든다.
     *
     * <p>{@code last_active_at = NOW()} 이면 임계가 {@code now + idleMinutes} 라 어느 것도 걸리지
     * 않는다. 나머지는 COMPLETED 로 밀어 쿼리 결과에서 뺀다.
     */
    private void setInProgressCount(int n) {
        // 🔴 델타만 쓴다. 처음엔 «전부 COMPLETED 로 밀고 앞 N 개를 되살리기» 였는데, 그러면 판마다
        //    전 행이 갱신된다. status 는 세 보조 인덱스 전부의 컬럼이라 행마다 인덱스 유지가 붙고,
        //    그 비용이 판 준비에 얹혀 리그 자체를 못 돌게 만들었다. 아래는 «이미 맞는 행» 을
        //    WHERE 에서 빼므로 레벨 간 차이만큼만 쓴다.
        //
        //    last_active_at 은 시딩 때 한 번만 넣는다 — 판마다 NOW() 로 갱신하면 그것도 N 행
        //    쓰기다. 대신 프로파일이 idle-minutes 를 크게 잡아, 리그가 도는 내내 어느 세션도
        //    타임아웃이 아니게 만든다. 판정 코드는 그대로 타고 결과만 항상 false 다.
        long cutoffId = minSessionId() + n - 1;

        jdbc.update("UPDATE exercise_sessions SET status = 'COMPLETED' "
                + "WHERE id > ? AND status <> 'COMPLETED'", cutoffId);
        jdbc.update("UPDATE exercise_sessions SET status = 'IN_PROGRESS' "
                + "WHERE id <= ? AND status <> 'IN_PROGRESS'", cutoffId);

        Integer actual = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exercise_sessions WHERE status = 'IN_PROGRESS'", Integer.class);
        assertThat(actual)
                .as("판 준비: IN_PROGRESS 가 정확히 N 이어야 한다")
                .isEqualTo(n);
    }

    /**
     * 이 판이 «일이 0 인 판» 이었는지 확인한다. 하나라도 FAILED 로 넘어갔다면 재는 대상이
     * 달라진 것이라 그 판의 수치는 못 쓴다.
     */
    private void assertNothingCollected(int expectedInProgress) {
        Integer stillInProgress = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exercise_sessions WHERE status = 'IN_PROGRESS'", Integer.class);
        assertThat(stillInProgress)
                .as("걷어갈 대상이 0 이어야 한다 — 하나라도 FAILED 가 됐으면 «일이 0» 전제가 깨진 것이다")
                .isEqualTo(expectedInProgress);
    }

    private Long minSessionId() {
        return jdbc.queryForObject("SELECT MIN(id) FROM exercise_sessions", Long.class);
    }

    private void seedOnce() {
        if (seeded) {
            return;
        }

        Long memberId = ensureMember();
        Long exerciseId = jdbc.queryForObject("SELECT MIN(id) FROM exercises", Long.class);
        assertThat(exerciseId)
                .as("exercises 마스터 시드(V2)가 있어야 한다 — Flyway 가 돌았는지 확인할 것")
                .isNotNull();

        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM exercise_sessions", Integer.class);
        int toInsert = SEED_TOTAL - (existing == null ? 0 : existing);

        for (int done = 0; done < toInsert; done += SEED_CHUNK) {
            int batch = Math.min(SEED_CHUNK, toInsert - done);
            List<Object[]> rows = new ArrayList<>(batch);
            for (int i = 0; i < batch; i++) {
                rows.add(new Object[]{memberId, exerciseId});
            }
            // last_active_at 을 여기서 한 번만 박는다. 임계는 lastActiveAt + idleMinutes 인데
            // 프로파일이 idleMinutes 를 크게 잡으므로, 리그가 도는 내내 전부 «타임아웃 아님» 이다.
            jdbc.batchUpdate(
                    "INSERT INTO exercise_sessions (member_id, exercise_id, start_time, last_active_at, status) "
                            + "VALUES (?, ?, NOW(), NOW(), 'COMPLETED')",
                    rows);
        }

        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM exercise_sessions", Integer.class);
        System.out.printf("%n시딩 완료 — exercise_sessions %,d 행%n", total);
        seeded = true;
    }

    /**
     * 세션의 FK 를 채울 회원 하나. 회원당 IN_PROGRESS 1개 제약은 <b>애플리케이션에만</b> 있고
     * DB 제약이 아니므로(생성 시도가 FK CASCADE 때문에 폐기됐다, V1 주석) 한 명을 재사용해도 된다.
     * 이 리그가 재는 것은 «몇 행이 엔티티가 되는가» 이지 회원 분포가 아니다.
     */
    private Long ensureMember() {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM users WHERE email = ?", Long.class, "sweepload@rig.local");
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        jdbc.update("INSERT INTO users (email, password, username, role) VALUES (?, ?, ?, ?)",
                "sweepload@rig.local", "x", "sweepload-rig", "USER");
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                "sweepload@rig.local");
    }

    private void report(Map<Integer, List<Sample>> byLevel) {
        System.out.printf("%n=== #207 스윕 적재량 — 걷어갈 대상 0, 라틴 방격 3라운드 ===%n");
        System.out.printf("%-10s %16s %16s %10s%n", "N", "할당 중앙값(B)", "N당 할당(B)", "중앙 ms");

        Long baseAlloc = null;
        Integer baseLevel = null;
        for (Map.Entry<Integer, List<Sample>> e : byLevel.entrySet()) {
            int level = e.getKey();
            long medAlloc = median(e.getValue().stream().mapToLong(Sample::allocatedBytes).sorted().toArray());
            double medMs = median(e.getValue().stream().mapToLong(Sample::nanos).sorted().toArray()) / 1_000_000.0;

            System.out.printf("%-10s %,16d %,16d %10.1f%n", String.format("%,d", level),
                    medAlloc, medAlloc / level, medMs);

            if (baseAlloc == null) {
                baseAlloc = medAlloc;
                baseLevel = level;
            }
        }

        if (baseAlloc != null && baseAlloc > 0) {
            long topAlloc = median(byLevel.get(LEVELS[LEVELS.length - 1]).stream()
                    .mapToLong(Sample::allocatedBytes).sorted().toArray());
            double allocRatio = (double) topAlloc / baseAlloc;
            double levelRatio = (double) LEVELS[LEVELS.length - 1] / baseLevel;
            System.out.printf("%n  N 이 %.0f배일 때 할당은 %.1f배%n", levelRatio, allocRatio);
            System.out.printf("  → 선형이면 #207 확정 · 평탄하면 #207 기각 (판정은 결과 문서에서)%n");
        }
        System.out.printf("%n⚠️ 절대치 인용 금지 — 2코어 동거 환경이다. 보는 것은 N 대비 기울기뿐이다.%n%n");
    }

    private static long median(long[] sorted) {
        if (sorted.length == 0) {
            return 0;
        }
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1
                ? sorted[mid]
                : (sorted[mid - 1] + sorted[mid]) / 2;
    }
}
