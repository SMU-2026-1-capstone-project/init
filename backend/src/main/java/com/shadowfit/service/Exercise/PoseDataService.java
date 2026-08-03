package com.shadowfit.service.Exercise;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.observability.SessionMetrics;
import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseReference;
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

    private static final String INSERT_POSE_SQL =
            "INSERT INTO pose_data " +
            "(session_id, rep_number, timestamp_sec, joint_coordinates, sync_rate, smoothed_knee_angle, feedback_message) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)";

    // 다운샘플 윈도우 크기 — R-sweep 실측(docs/decisions/pose-ingest-downsampling.md §5-1(4))에서
    // R=25→5는 처리량 +126%·저장 5배↓지만 R=5→1은 배치 고정비용 비중이 커져 행당 효율이 오히려
    // 악화되는 수확체감 지점이라 R=5를 하한으로 채택(2026-07-25, §5-1(7)(8) 분리배포 실측과 별개 결정).
    private static final int DOWNSAMPLE_WINDOW = 5;

    /**
     * [실시간 저장] FastAPI가 주기적으로 쏴주는 분석 좌표 데이터 묶음을 DB에 저장합니다.
     *
     * JPA saveAll 은 PoseData.id 가 IDENTITY 라 Hibernate batch insert 가 비활성(개별 INSERT N방).
     * 부하 테스트(§7.5)에서 동시성 100에 p99 4.6s·throughput 천장 확인 → JdbcTemplate.batchUpdate
     * 로 multi-row INSERT 단일화. created_at 은 DB DEFAULT CURRENT_TIMESTAMP 에 위임.
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
        if (!sessionRepository.existsById(sessionId)) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }

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
            }

            @Override
            public int getBatchSize() {
                return downsampled.size();
            }
        });

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

        referenceRepository.saveAll(referenceEntities);
        log.info("운동 ID {} : 기준 좌표 {}개 등록 완료", exerciseId, referenceEntities.size());
    }
}