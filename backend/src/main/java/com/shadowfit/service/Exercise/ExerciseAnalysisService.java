package com.shadowfit.service.Exercise;

import com.shadowfit.dto.exercises.FastApiRequestDto;
import com.shadowfit.dto.exercises.VideoRequestDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.util.YoutubeValidator;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.repository.ExercisesRepository;
import com.shadowfit.repository.MemberRepository;
import com.shadowfit.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ExerciseAnalysisService {
    private final WebClient webClient;
    private final SessionRepository sessionRepository;
    private final ExercisesRepository exercisesRepository;
    private final MemberRepository memberRepository;

    @Value("${internal.api.token}")
    private String internalToken;

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
