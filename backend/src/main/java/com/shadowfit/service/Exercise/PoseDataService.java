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

import java.sql.PreparedStatement;
import java.sql.SQLException;
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

    private static final String INSERT_POSE_SQL =
            "INSERT INTO pose_data " +
            "(session_id, rep_number, timestamp_sec, joint_coordinates, sync_rate, is_correct, feedback_message) " +
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
                ps.setBoolean(6, grpc.getSyncRate() >= 40.0); // 40점 기준 (수정 가능)
                ps.setString(7, grpc.getFeedbackMessage());
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
        // (위 INSERT)이 그것 때문에 유실되면 주객이 전도된다. 세션이 없으면 위 existsById 에서
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
     * window개마다 첫 프레임만 남기는 <b>균등 샘플링</b>. 행 수가 1/window 로 줄어든다.
     *
     * <p><b>예전엔 "sync_rate 가 가장 낮은 프레임을 대표로 남긴다"고 되어 있었다</b>(이슈 #79).
     * 그 선택은 <b>실행되지 않았다</b> — 한 배치가 곧 한 rep 이고(ai-server 는 rep 이 완성될 때만
     * 콜백한다, {@code pose.py:98-118}) rep 안의 {@code sync_rate} 는 상수라(rep 단위로 채점해
     * 프레임마다 복제, {@code pose.py:111}) 엄격 부등호가 참이 되는 경우가 없었다. 매 window 의
     * 첫 프레임이 그대로 남았고, 지금 코드는 <b>실제로 일어나던 일을 그대로 적은 것</b>이다.
     *
     * <p>따라서 {@code pose-ingest-downsampling.md} §4 의 "평균 vs 대표추출" 비교도 이 데이터에서는
     * 성립하지 않는다 — 두 방식이 같은 상수를 돌려주므로 구분되지 않는다. 그 문서 §4 에 정정 표시를
     * 달아뒀다.
     *
     * <p><b>R≈5 라는 비율 자체는 유효하다.</b> R-sweep 실측(§5-1(4))은 "몇 개를 남기나"의 실험이고
     * 이 이슈는 "그중 어느 것을 남기나"의 문제라 결론이 뒤집히지 않는다.
     *
     * <p>⚠️ 남는 프레임이 달라지면 {@code sync_rate} 는 그대로여도(상수) <b>{@code joint_coordinates}
     * 는 프레임마다 다르다.</b> 즉 어느 좌표가 리포트에 남는지는 이 선택에 달려 있다. "가장 자세가
     * 안 좋았던 순간의 좌표"를 남기고 싶다면 {@code sync_rate} 가 아니라 프레임마다 실제로 다른 값
     * (무릎각 등)을 기준으로 삼아야 하는데, 무엇이 "나쁜 자세"인지 종목별 정의가 필요해 열어뒀다(#79).
     */
    private List<PoseDataRequest> downsample(List<PoseDataRequest> frames, int window) {
        List<PoseDataRequest> result = new java.util.ArrayList<>();
        for (int start = 0; start < frames.size(); start += window) {
            result.add(frames.get(start));
        }
        return result;
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