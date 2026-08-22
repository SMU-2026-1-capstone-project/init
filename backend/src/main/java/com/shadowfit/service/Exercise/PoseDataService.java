package com.shadowfit.service.Exercise;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.observability.SessionMetrics;
import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseReference;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.repository.exercise.ExerciseReferenceRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PoseDataService {

    private final SessionRepository sessionRepository;
    private final ExercisesRepository exercisesRepository;
    private final ExerciseReferenceRepository referenceRepository;
    private final JdbcTemplate jdbcTemplate;
    private final SessionMetrics sessionMetrics;

    // created_at 을 **명시적으로** 쓴다. DB DEFAULT 에 맡기면 재전송마다 값이 달라져
    // uk_pose_event 가 무력해진다 (#188, decisions/pose-batch-idempotency-implementation.md).
    //
    // ON DUPLICATE KEY UPDATE 무동작이 INSERT IGNORE 자리를 대신한다. IGNORE 는 중복만
    // 삼키는 게 아니라 **NOT NULL 위반을 빈 값으로 써버린다**(#219). 이 테이블은
    // joint_coordinates·sync_rate·smoothed_knee_angle 이 전부 NOT NULL 이라 정확히 사정권이고,
    // 유실을 막으려는 코드가 다른 종류의 조용한 오염을 들이면 주객이 전도된다.
    // ODKU 는 중복 키만 흡수하고 NOT NULL 위반은 그대로 터뜨린다.
    private static final String INSERT_POSE_SQL =
            "INSERT INTO pose_data " +
            "(session_id, rep_number, timestamp_sec, joint_coordinates, sync_rate, smoothed_knee_angle, feedback_message, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE session_id = session_id";

    // 다운샘플 윈도우 크기 — R-sweep 실측(docs/decisions/pose-ingest-downsampling.md §5-1(4))에서
    // R=25→5는 처리량 +126%·저장 5배↓지만 R=5→1은 배치 고정비용 비중이 커져 행당 효율이 오히려
    // 악화되는 수확체감 지점이라 R=5를 하한으로 채택(2026-07-25, §5-1(7)(8) 분리배포 실측과 별개 결정).
    private static final int DOWNSAMPLE_WINDOW = 5;

    /**
     * [실시간 저장] FastAPI가 주기적으로 쏴주는 분석 좌표 데이터 묶음을 DB에 저장합니다.
     *
     * JPA saveAll 은 PoseData.id 가 IDENTITY 라 Hibernate batch insert 가 비활성(개별 INSERT N방).
     * 부하 테스트(§7.5)에서 동시성 100에 p99 4.6s·throughput 천장 확인 → JdbcTemplate.batchUpdate
     * 로 multi-row INSERT 단일화. created_at 은 <b>세션 시작 시각을 명시적으로</b> 쓴다(멱등,
     * docs/decisions/pose-batch-idempotency-implementation.md 분기 A) — 예전엔 DB DEFAULT 였다.
     *
     * 저장 전 다운샘플(위치 B: Spring, pose-ingest-downsampling.md §3-B) — 라이브 분석
     * (DTW·sync·rep 감지)은 이 저장 이전 FastAPI에서 이미 끝난 값이라 저장본을 줄여도 영향 없고,
     * 영향받는 건 리포트 시계열 해상도뿐(같은 문서 §1 안전판).
     */
    @Transactional
    public void savePoseDataBatch(Long sessionId, List<com.shadowfit.grpc.PoseDataRequest> grpcList) {
        if (grpcList == null || grpcList.isEmpty()) return;

        // 세션 존재 검증 — pose_data는 파티셔닝을 위해 FK(CASCADE)를 제거해서(2026-07-20,
        // docs/decisions/pose-data-partition-fk-tradeoff.md), 이 체크가 DB의 백업이 아니라
        // 참조무결성을 보장하는 유일한 장치가 됨. 기존 SESSION_NOT_FOUND 계약도 그대로 유지.
        //
        // existsById 가 아니라 findById 인 이유는 start_time 이 필요해서다 — 쿼리 횟수는 같다.
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        // 종료된 세션에는 더 쌓지 않는다 (#187 (b) — 콜백 층 심층방어).
        //
        // 위 findById 는 «세션이 있는가» 만 본다. 그런데 세션이 COMPLETED/FAILED/CANCELLED 로
        // 끝난 뒤에도 행은 남아 있어 통과하고, 그 상태에서 배치를 받으면 **이미 리포트가 확정된
        // 세션에 pose_data 가 더 붙는다.** 정상 경로에선 안 오는 일이다 — endSession 은 endTime
        // 만 찍고 status 는 IN_PROGRESS 로 두므로(사용자가 종료를 눌러도), 늦게 도착하는 정상
        // 배치는 여전히 IN_PROGRESS 를 만나 통과한다. 여기 걸리는 것은 CompleteAnalysis 이후다.
        //
        // 🔴 던지지 않고 조용히 버린다. #188 재시도(#280)가 붙어 있어서, 던지면 AI 가 **영영
        // 거절될 배치를 재시도**한다. 종료 세션 배치는 — 주입이면 버리는 게 맞고(#187), 종료
        // 직전 경합으로 늦은 정상 배치면 이미 리포트가 확정돼 무의미하므로 — 두 경우 다 드롭이 옳다.
        //
        // ⚠️ 이것이 #187 을 «닫지» 않는다. 공격의 본체는 **진행 중인(IN_PROGRESS)** 남의 세션에
        // 끼어드는 것이고, 그건 이 검사를 그대로 통과한다. 채널 ① 의 신원(nonce, 안 d)이 서야
        // 본체가 막힌다. 이 가드는 «종료 후 창» 하나만 닫는 심층방어다.
        if (session.getStatus() != Status.IN_PROGRESS) {
            sessionMetrics.poseBatchRejected(session.getStatus().name());
            log.warn("세션 {} : {} 상태에 도착한 pose 배치 {}건을 버린다 (#187 (b))",
                    sessionId, session.getStatus(), grpcList.size());
            return;
        }

        // 이 배치가 만들 모든 행의 created_at. 세션 하나가 값 하나를 공유한다.
        // 재전송에도 같은 값이 나오는 것이 멱등의 근거이고(start_time 은 세션 생성 시 1회
        // 세팅되고 바뀌는 곳이 없다), 세션이 파티션 경계에서 쪼개지지 않는 것이 부수 이득이다.
        LocalDateTime sessionAnchor = session.getStartTime();

        // 위 검증이 통과한 순간부터 이 트랜잭션이 커밋될 때까지가 고아 행이 생길 수 있는 창이다
        // (이슈 #87). 그 사이에 회원 탈퇴가 세션을 지우고 pose_data 정리까지 끝내버리면, 아래
        // INSERT 가 정리 뒤에 착지해 아무도 다시 훑지 않는 행으로 남는다. 창의 폭을 알아야
        // 발생 빈도의 상한을 잡고 수정안을 저울질할 수 있어 여기서 잰다.
        recordOrphanWindow(System.nanoTime());

        List<PoseDataRequest> downsampled = downsample(grpcList, DOWNSAMPLE_WINDOW);

        jdbcTemplate.batchUpdate(INSERT_POSE_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                PoseDataRequest grpc = downsampled.get(i);
                ps.setLong(1, sessionId);
                // proto3 라 구버전 AI(rep_number 미전송)에서는 0 이 들어온다 — 컬럼 DEFAULT 와 같은 값이라
                // 배포 순서를 맞추지 않아도 깨지지 않는다. 0 은 "미상"이고, MAX 를 취해도 카운트가 늘지 않는다.
                ps.setInt(2, grpc.getRepNumber());
                ps.setDouble(3, grpc.getTimestampSec());
                ps.setString(4, grpc.getJointCoordinates());
                ps.setDouble(5, grpc.getSyncRate());
                // 구버전 AI 는 이 필드를 안 보내 0(미상)이 들어온다 — rep_number 와 같은 방식이라
                // 배포 순서를 맞추지 않아도 깨지지 않는다. 스쿼트 무릎각은 0 이 될 수 없으므로
                // 유효값과 구분되고, 대표 프레임 선택에서 후보에서 빠진다.
                ps.setDouble(6, grpc.getSmoothedKneeAngle());
                ps.setString(7, grpc.getFeedbackMessage());
                ps.setObject(8, sessionAnchor);
            }

            @Override
            public int getBatchSize() {
                return downsampled.size();
            }
        });

        // 활동 시각 갱신 — 이 배치가 도착했다는 것 자체가 "사용자가 아직 운동 중"이라는 신호다
        // (session-liveness-vs-elapsed-time.md ㄷ안). 타임아웃·재부착 판정이 이 값을 앵커로 쓴다.
        //
        // JPA 가 아니라 여기서 직접 UPDATE 하는 이유: 엔티티로 갱신하면 @Version 이 따라 올라가고,
        // 그 낙관적 락은 AI 완료 콜백과 타임아웃 스케줄러의 경쟁을 조율하는 장치다. 운동 중 내내
        // version 이 바뀌면 지금은 드물어서 지표로 관측하던 그 경쟁이 상시화된다.
        //
        // 실패해도 배치를 되돌리지 않는다 — 이 UPDATE 는 판정 보조이고, 사용자가 실제로 한 운동
        // (위 INSERT)이 그것 때문에 유실되면 주객이 전도된다. 세션이 없으면 위 findById 에서
        // 이미 걸러졌으므로 여기서 0 행이 나오는 경우는 그 사이 삭제된 때뿐이다.
        jdbcTemplate.update("UPDATE exercise_sessions SET last_active_at = ? WHERE id = ?",
                LocalDateTime.now(), sessionId);

        // 수신/저장 행수를 분포로 남겨 실측 다운샘플 비율(R≈5)이 운영 중에도 유지되는지 관측한다.
        // 로그는 배치 1건씩만 보여주지만 지표는 "지난 1시간 평균 몇 프레임이 몇 행이 됐나"에 답한다.
        sessionMetrics.poseBatch(grpcList.size(), downsampled.size());

        log.info("세션 {} : 포즈 데이터 {}개 수신 → {}개로 다운샘플 후 저장 성공",
                sessionId, grpcList.size(), downsampled.size());
    }

    /**
     * 창의 끝점을 <b>커밋</b>으로 잡는다 (이슈 #87). {@code batchUpdate} 반환이 아니라 커밋인
     * 이유는, 탈퇴 쪽 정리가 우리 커밋 <i>뒤에</i> 돌면 우리 행까지 같이 지워져 고아가 안 남기
     * 때문이다 — 위험한 구간은 검증 통과부터 커밋 직전까지다.
     *
     * <p>트랜잭션이 없거나(테스트 등) 롤백되면 기록하지 않는다. 롤백된 배치는 행을 남기지
     * 않으므로 애초에 고아를 만들 수 없다.
     */
    private void recordOrphanWindow(long windowStartNanos) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sessionMetrics.poseOrphanWindow(Duration.ofNanos(System.nanoTime() - windowStartNanos));
            }
        });
    }

    /**
     * window개마다 <b>가장 깊은 프레임</b>(= {@code smoothedKneeAngle} 최소)을 대표로 남긴다.
     * 행 수가 1/window 로 줄어드는 것은 같고, 줄어든 뒤 <b>어느 프레임이 남는지</b>가 달라진다.
     *
     * <p><b>왜 첫 프레임이 아니라 최소값인가.</b> 남는 프레임이 달라져도 {@code syncRate} 는
     * 그대로지만(rep 단위 상수) <b>{@code jointCoordinates} 는 프레임마다 다르다.</b> 즉 이 선택이
     * 리포트에 그려질 자세를 결정한다. 스쿼트에서 자세가 무너지는 지점은 바닥이므로 그 순간을
     * 남긴다(decisions/worst-section-rep-resolution.md §4-ㄹ).
     *
     * <p><b>이 선택은 여기서 하지 않으면 되돌릴 수 없다.</b> 버려진 프레임은 DB 에 없으므로 리포트가
     * 나중에 아무리 잘 골라도 복구되지 않는다. 그래서 대표 프레임 선택이 저장 시점에 있다.
     *
     * <p><b>이슈 #79 와의 관계.</b> 예전 코드는 "{@code sync_rate} 가 가장 낮은 프레임을 남긴다"고
     * 되어 있었으나 <b>실행되지 않았다</b> — 한 배치가 곧 한 rep 이고(ai-server 는 rep 완성 시에만
     * 콜백, {@code pose.py:98-118}) rep 안의 {@code sync_rate} 는 상수라 엄격 부등호가 참이 되는
     * 경우가 없었다. #79 에서 그 죽은 비교를 걷어내고 균등 샘플링으로 정직하게 적었는데,
     * <b>그 코드가 하려던 일 자체는 유효했다</b> — 비교 기준이 프레임마다 같은 값이었을 뿐이다.
     * 이제 프레임마다 실제로 다른 값이 생겨 의도대로 동작한다.
     *
     * <p><b>{@code R≈5} 비율은 그대로다.</b> R-sweep 실측
     * ({@code pose-ingest-downsampling.md} §5-1(4))은 "몇 개를 남기나"의 실험이고 이 변경은
     * "그중 어느 것을 남기나"라서 결론이 뒤집히지 않는다. 반환 행 수도 동일하다.
     *
     * <p><b>0(미상)은 후보에서 뺀다.</b> 구버전 AI 는 이 필드를 안 보내 전부 0 이 되는데, 그때
     * 최소값을 고르면 사실상 첫 프레임이 남아 기존 동작과 같아진다 — 다만 그것을 "가장 깊어서"가
     * 아니라 <b>"고를 근거가 없어서"</b> 로 명시한다. 유효값과 0 이 섞인 window 에서 0 이 이기면
     * 실제로 가장 깊은 프레임이 밀려나므로, 섞임을 방지하는 것이기도 하다.
     */
    private List<PoseDataRequest> downsample(List<PoseDataRequest> frames, int window) {
        List<PoseDataRequest> result = new java.util.ArrayList<>();
        for (int start = 0; start < frames.size(); start += window) {
            int end = Math.min(start + window, frames.size());
            result.add(pickDeepest(frames, start, end));
        }
        return result;
    }

    /**
     * {@code [start, end)} 구간에서 {@code smoothedKneeAngle} 이 가장 작은(= 가장 깊은) 프레임.
     *
     * <p>유효값(&gt; 0)이 하나도 없으면 <b>구간의 첫 프레임</b>으로 떨어진다 — 고를 근거가 없을 때
     * 임의로 고르지 않고 예전 동작(균등 샘플링)을 유지한다는 뜻이다.
     *
     * <p>동률이면 먼저 나온 프레임이 남는다(엄격 부등호). 입력이 시간 오름차순이라 순서가 확정돼
     * 있어 같은 입력이면 항상 같은 프레임이 나온다 — 바닥에서 잠깐 멈춰 같은 각도가 이어질 때
     * 내려간 직후 프레임이 남는다.
     */
    private PoseDataRequest pickDeepest(List<PoseDataRequest> frames, int start, int end) {
        PoseDataRequest deepest = null;
        for (int i = start; i < end; i++) {
            PoseDataRequest candidate = frames.get(i);
            if (candidate.getSmoothedKneeAngle() <= 0.0) {
                continue; // 미상 — 유효한 무릎각이 아니다
            }
            if (deepest == null || candidate.getSmoothedKneeAngle() < deepest.getSmoothedKneeAngle()) {
                deepest = candidate;
            }
        }
        return deepest != null ? deepest : frames.get(start);
    }

    /**
     * [관리자용] AI가 유튜브에서 추출한 '정석 기준 좌표'를 DB에 저장합니다.
     */
    @Transactional
    @CacheEvict(cacheNames = "exerciseReferences", key = "#exerciseId")
    public void saveReferencePoses(Long exerciseId, List<com.shadowfit.grpc.PoseDataRequest> grpcList) {
        if (grpcList == null || grpcList.isEmpty()) return;

        Exercise exercise = exercisesRepository.findByIdCached(exerciseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXERCISE_NOT_FOUND));

        List<ExerciseReference> referenceEntities = grpcList.stream()
                .map(grpc -> ExerciseReference.builder()
                        .exercise(exercise)
                        .timestampSec(grpc.getTimestampSec())
                        .jointCoordinates(grpc.getJointCoordinates())
                        .build())
                .collect(Collectors.toList());

        // 🔴 재추출은 «추가» 가 아니라 «교체» 다 (#220). 기존 행을 두고 saveAll 하면 rep 두 벌이
        //    이어붙어 **한 벌처럼** 읽힌다 — AI 가 이 표를 순서대로 훑어 각도 시퀀스 하나로 만들고
        //    (`_parse_reference_poses`), 그때 timestamp_sec 은 아예 안 본다. 2026-08-16 실측에서
        //    37행이 74행이 됐고 표에는 «등록 완료» 로그만 남았다.
        //    같은 트랜잭션이라 삭제·삽입이 원자적이다 — 중간에 실패해도 정답지가 비지 않는다.
        long removed = referenceRepository.deleteByExerciseId(exerciseId);
        referenceRepository.saveAll(referenceEntities);
        log.info("운동 ID {} : 기준 좌표 {}개 등록 완료 (기존 {}개 교체)",
                exerciseId, referenceEntities.size(), removed);
    }
}