package com.shadowfit.service.Exercise;

import com.shadowfit.dto.exercises.FastApiRequestDto;
import com.shadowfit.dto.exercises.VideoRequestDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.util.YoutubeValidator;
import com.shadowfit.grpc.AnalyzeResponse;
import com.shadowfit.grpc.ExerciseServiceGrpc;
import com.shadowfit.grpc.AnalyzeRequest;
import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseReference;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.repository.ExerciseReferenceRepository;
import com.shadowfit.repository.ExercisesRepository;
import com.shadowfit.repository.MemberRepository;
import com.shadowfit.repository.SessionRepository;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExerciseAnalysisService {
    private final WebClient webClient;
    private final SessionRepository sessionRepository;
    private final ExercisesRepository exercisesRepository;
    private final MemberRepository memberRepository;
    private final SessionService sessionService;
    private final ExerciseReferenceRepository referenceRepository;

    @Value("${internal.api.token}")
    private String internalToken;

    @GrpcClient("fastapi-client")
    private ExerciseServiceGrpc.ExerciseServiceStub exerciseAsyncStub;

    /**
     * [쪼개기 1] 기준 좌표 추출 전용 메서드 (등록 단계)
     * 컨트롤러에서 받은 유튜브 URL을 FastAPI에게 gRPC로 전달합니다.
     */
    public void extractReferencePoses(Long exerciseId, String youtubeUrl) {
        // 1. gRPC 요청 객체 생성 (ExtractRequest는 proto에 정의한 그 이름이어야 합니다)
        com.shadowfit.grpc.ExtractRequest request = com.shadowfit.grpc.ExtractRequest.newBuilder()
                .setExerciseId(exerciseId)
                .setYoutubeUrl(youtubeUrl) // 유튜브 URL 전달
                .build();

        log.info("FastAPI에게 기준 좌표 추출 요청 전송 - 운동 ID: {}", exerciseId);

        // 2. gRPC 비동기 호출
        exerciseAsyncStub.extractReferenceData(request, new StreamObserver<com.shadowfit.grpc.ExtractResponse>() {
            @Override
            public void onNext(com.shadowfit.grpc.ExtractResponse value) {
                log.info("FastAPI 추출 시작 응답 수신 - 운동 ID: {}", value.getExerciseId());
            }

            @Override
            public void onError(Throwable t) {
                log.error("좌표 추출 gRPC 통신 장애: {}", t.getMessage());
            }

            @Override
            public void onCompleted() {
                log.info("좌표 추출 gRPC 요청 완료");
            }
        });
    }


    @Transactional
    public Long startAnalysis(VideoRequestDto appDto, Long currentMemberId) {
        // 1. 세션 생성 (상태는 READY 또는 IN_PROGRESS)
        Session savedSession = sessionService.createSession(appDto, currentMemberId);
        Long sessionId = savedSession.getId();

        // 2. [핵심] 외부 전용(Spring -> FastAPI) 메서드를 비동기로 호출
        // 여기서 appDto와 sessionId를 넘겨줍니다.
        this.sendAnalysisRequestToFastApi(sessionId, appDto);

        return sessionId; // 0.1초 만에 앱으로 반환!
    }

    @Async // 별도 스레드에서 실행
    @Transactional(readOnly = true) // DB 조회용 트랜잭션
    public void sendAnalysisRequestToFastApi(Long sessionId, VideoRequestDto appDto) {
        log.info("비동기 분석 요청 시작 - 세션 ID: {}", sessionId);

        // 1. DB에서 정석 좌표 리스트를 긁어온다.
        List<ExerciseReference> referencePoses = referenceRepository.findByExerciseId(appDto.getExerciseId());

        // 2. gRPC 요청 빌드
        AnalyzeRequest.Builder requestBuilder = AnalyzeRequest.newBuilder()
                .setExerciseId(appDto.getExerciseId())
                .setSessionId(sessionId)
                .setReferenceSource(YoutubeValidator.extractId(appDto.getReferenceSource()));

        for (ExerciseReference ref : referencePoses) {
            requestBuilder.addReferencePoses(PoseDataRequest.newBuilder()
                    .setTimestampSec(ref.getTimestampSec())
                    .setJointCoordinates(ref.getJointCoordinates())
                    .build());
        }

        // 3. gRPC 비동기 호출 (FastAPI 서버로 슛!)
        exerciseAsyncStub.startAnalysis(requestBuilder.build(), new StreamObserver<AnalyzeResponse>() {
            @Override
            public void onNext(AnalyzeResponse value) {
                log.info("FastAPI 응답 수신 - 세션: {}", value.getSessionId());
            }
            @Override
            public void onError(Throwable t) {
                log.error("gRPC 통신 장애: {}", t.getMessage());
            }
            @Override
            public void onCompleted() {
                log.info("FastAPI 전송 완료");
            }
        });
    }



    @Transactional
    public Long sendToAnalysisServer(VideoRequestDto appDto,Long currentMemberId){
        Member member = memberRepository.findById(currentMemberId)
                .orElseThrow(()-> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Exercise exercise = exercisesRepository.findById(appDto.getExerciseId())
                .orElseThrow(()->new BusinessException(ErrorCode.EXERCISE_NOT_FOUND));

        Session session = Session.builder()
                .user(member)
                .exercise(exercise)
                .referenceSource(appDto.getReferenceSource())
                .startTime(LocalDateTime.now())
                .status(Status.IN_PROGRESS)
                .build();

        Session savedSession = sessionRepository.save(session);
        Long sessionId = savedSession.getId();
        String youtubeVideoId = YoutubeValidator.extractId(appDto.getReferenceSource());

        //파이썬 dto 생성
        FastApiRequestDto apiDto = FastApiRequestDto.builder().
                exerciseId(appDto.getExerciseId())
                .youtubeUrl(youtubeVideoId)
                .sessionId(sessionId)
                .build();

        //파이썬으로 전송
        webClient.post()
                .uri("http://localhost:8000/analyze")
                .header("X-Internal-Token",internalToken)
                .bodyValue(apiDto)
                .retrieve()
                .bodyToMono(Void.class)
                .doOnSuccess(v->System.out.println("FastAPI 전송 성공: "+sessionId))
                .doOnError(e->System.err.println("전송 실패: "+e.getMessage()))
                .subscribe();

        return sessionId;
    }

}
