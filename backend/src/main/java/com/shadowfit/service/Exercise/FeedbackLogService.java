package com.shadowfit.service.Exercise;

import com.shadowfit.dto.exercises.feedback.FeedbackBatchRequestDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.SessionFeedbackLog;
import com.shadowfit.repository.exercise.SessionFeedbackLogRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackLogService {
    private final SessionFeedbackLogRepository feedbackLogRepository;
    private final SessionRepository sessionRepository;

    @Transactional
    public int saveBatch(FeedbackBatchRequestDto request) {
        Session session = sessionRepository.findById(request.sessionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        List<SessionFeedbackLog> logs = request.events().stream()
                .map(event -> SessionFeedbackLog.builder()
                        .session(session)
                        .feedbackType(event.feedbackType())
                        .syncRateAtTrigger(event.syncRateAtTrigger())
                        .occurredAt(event.occurredAt())
                        .build())
                .toList();

        feedbackLogRepository.saveAll(logs);
        log.info("세션 {} 피드백 로그 {}건 저장 완료", request.sessionId(), logs.size());
        return logs.size();
    }
}