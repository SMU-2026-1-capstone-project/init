package com.shadowfit.service.Exercise;

import com.shadowfit.dto.exercises.PoseDataRequestDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.grpc.PoseDataBatchRequest;
import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseReference;
import com.shadowfit.model.exercise.PoseData;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.repository.exercise.ExerciseReferenceRepository;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PoseDataService {
    private final PoseDataRepository poseDataRepository;
    private final SessionRepository sessionRepository;
    private final ExercisesRepository exercisesRepository;
    private final ExerciseReferenceRepository referenceRepository;

    /**
     * [HTTP 방식] 포즈 데이터 배치 저장
     * WebClient 등을 통해 리스트 형태로 넘어온 좌표 데이터를 한꺼번에 저장합니다.
     */
    @Transactional
    public void savePoseDataBatch(Long sessionId, List<com.shadowfit.grpc.PoseDataRequest> grpcList) {

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        // 1. gRPC 객체 리스트를 DB 엔티티 리스트로 변환
        List<PoseData> entities = grpcList.stream()
                .map(grpc -> PoseData.builder()
                        .session(session)
                        .timestampSec(grpc.getTimestampSec())
                        .jointCoordinates(grpc.getJointCoordinates())
                        .syncRate(grpc.getSyncRate())
                        .isCorrect(grpc.getSyncRate() >= 40.0)
                        .feedbackMessage(grpc.getFeedbackMessage())
                        .build())
                .toList();

        // 2. DB에 일괄 저장 (Batch Insert)
        poseDataRepository.saveAll(entities);
        log.info("세션 {} : 실시간 포즈 데이터 {}개 배치 저장 완료", sessionId, entities.size());
    }
}

/**
 * [관리자용] 추출된 기준 좌표(정석 자세)를 DB에 저장합니다.
 */
@Transactional
public void saveReferencePoses(Long exerciseId, List<com.shadowfit.grpc.PoseDataRequest> grpcList) {
    // 1. 해당 운동(Exercise) 엔티티가 있는지 확인
    Exercise exercise = exercisesRepository.findById(exerciseId)
            .orElseThrow(() -> new BusinessException(ErrorCode.EXERCISE_NOT_FOUND));

    // 2. gRPC 리스트를 ExerciseReference(기준 좌표 엔티티) 리스트로 변환
    // (만약 엔티티 이름이 다르다면 프로젝트에 맞춰 수정하세요!)
    List<ExerciseReference> referenceEntities = grpcList.stream()
            .map(grpc -> ExerciseReference.builder()
                    .exercise(exercise)
                    .timestampSec(grpc.getTimestampSec())
                    .jointCoordinates(grpc.getJointCoordinates())
                    .build())
            .toList();

    // 3. 기준 좌표 테이블에 일괄 저장
    // exerciseReferenceRepository는 미리 주입받아야 합니다.
    exerciseReferenceRepository.saveAll(referenceEntities);

    log.info("운동 ID {} : 총 {}개의 기준 좌표 저장 완료", exerciseId, referenceEntities.size());
}

    /**
     * [gRPC 방식] 실시간 분석 좌표 배치 저장
     * AI 서버에서 주기적으로 쏴주는 분석 좌표들을 비동기(@Async)로 처리하여
     * 메인 통신 흐름에 지장을 주지 않고 DB에 저장합니다.
     */
    @Async
    @Transactional
    public void savePoseDataBatchGrpc( PoseDataBatchRequest request) {
        if (request.getPoseDataCount() == 0) return;

        // 1. 세션 조회
        Session session = sessionRepository.findById(request.getSessionId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 세션: " + request.getSessionId()));

        // 2. Proto 메시지 리스트 -> Entity 리스트 변환
        List<PoseData> entities = request.getPoseDataList().stream()
                .map(p -> PoseData.builder()
                        .session(session)
                        .timestampSec(p.getTimestampSec())
                        .jointCoordinates(p.getJointCoordinates())
                        .build())
                .collect(Collectors.toList());

        // 3. 저장
        poseDataRepository.saveAll(entities);
    }
}