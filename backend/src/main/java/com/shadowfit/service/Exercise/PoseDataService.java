package com.shadowfit.service.Exercise;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
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

    private static final String INSERT_POSE_SQL =
            "INSERT INTO pose_data " +
            "(session_id, timestamp_sec, joint_coordinates, sync_rate, is_correct, feedback_message) " +
            "VALUES (?, ?, ?, ?, ?, ?)";

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
     * 저장 전 다운샘플(위치 B: Spring 대표추출, pose-ingest-downsampling.md §3-B) — 라이브 분석
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

        List<PoseDataRequest> downsampled = downsampleByWorstSync(grpcList, DOWNSAMPLE_WINDOW);

        jdbcTemplate.batchUpdate(INSERT_POSE_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                PoseDataRequest grpc = downsampled.get(i);
                ps.setLong(1, sessionId);
                ps.setDouble(2, grpc.getTimestampSec());
                ps.setString(3, grpc.getJointCoordinates());
                ps.setDouble(4, grpc.getSyncRate());
                ps.setBoolean(5, grpc.getSyncRate() >= 40.0); // 40점 기준 (수정 가능)
                ps.setString(6, grpc.getFeedbackMessage());
            }

            @Override
            public int getBatchSize() {
                return downsampled.size();
            }
        });

        log.info("세션 {} : 포즈 데이터 {}개 수신 → {}개로 다운샘플 후 저장 성공",
                sessionId, grpcList.size(), downsampled.size());
    }

    /**
     * window개 프레임 단위로 묶어 그중 sync_rate가 가장 낮은(자세가 가장 안 좋았던) 프레임만
     * 대표로 남긴다 — 평균이 아니라 극값을 남기는 이유는 리포트의 worst-rep 분석이 "구간 평균
     * 자세"가 아니라 "가장 안 좋았던 순간"을 필요로 하기 때문(§4의 평균 vs 대표추출 비교).
     */
    private List<PoseDataRequest> downsampleByWorstSync(List<PoseDataRequest> frames, int window) {
        List<PoseDataRequest> result = new java.util.ArrayList<>();
        for (int start = 0; start < frames.size(); start += window) {
            int end = Math.min(start + window, frames.size());
            PoseDataRequest worst = frames.get(start);
            for (int i = start + 1; i < end; i++) {
                if (frames.get(i).getSyncRate() < worst.getSyncRate()) {
                    worst = frames.get(i);
                }
            }
            result.add(worst);
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