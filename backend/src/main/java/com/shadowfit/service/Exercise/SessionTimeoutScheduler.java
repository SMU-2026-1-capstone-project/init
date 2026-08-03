package com.shadowfit.service.Exercise;

import com.shadowfit.global.observability.CorrelationIds;
import com.shadowfit.global.observability.SessionMetrics;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.repository.exercise.SessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 운동 세션 타임아웃 관리 스케줄러
 *
 * 네트워크 장애로 FastAPI로부터 분석 결과를 받지 못한 세션을 자동으로 실패 처리합니다.
 * 예상 운동시간 + 버퍼 시간 이상 IN_PROGRESS 상태가 유지되면 FAILED로 변경합니다.
 *
 * [동시성 정책]
 * 타임아웃 직전에 FastAPI가 완료 결과를 보내올 수 있으므로 Session 엔티티에 @Version 으로
 * 낙관적 락을 걸어두었고, 충돌 발생 시 본 스케줄러는 양보합니다 (FastAPI 결과 우선).
 */
@Slf4j
@Service
public class SessionTimeoutScheduler {

    private final SessionRepository sessionRepository;
    private final SessionService sessionService;
    private final SessionMetrics sessionMetrics;
    private final Integer idleMinutes;
    private final Integer defaultBufferMinutes;

    public SessionTimeoutScheduler(SessionRepository sessionRepository,
                                   SessionService sessionService,
                                   SessionMetrics sessionMetrics,
                                   @Value("${exercise.session.timeout.idle-minutes:10}") Integer idleMinutes,
                                   @Value("${exercise.session.timeout.default-buffer-minutes:30}") Integer bufferMinutes) {
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
        this.sessionMetrics = sessionMetrics;
        this.idleMinutes = idleMinutes;
        this.defaultBufferMinutes = bufferMinutes;
    }

    /**
     * 매 1분마다 타임아웃된 세션을 확인하고 FAILED 상태로 변경합니다.
     *
     * 타임아웃 계산식:
     * 타임아웃시간 = 세션 시작시간 + (예상 운동시간 + 버퍼시간)
     */
    @Scheduled(fixedDelayString = "${exercise.session.timeout.check-interval-minutes:1}m",
               initialDelayString = "30s")
    public void checkAndTimeoutSessions() {
        // 스케줄러는 들어오는 요청이 없어 물려받을 correlation id 가 없다 — tick 1회를 하나의 흐름으로
        // 보고 스스로 발급한다. 그러면 AI 콜백(cid 다름)과 이 tick 이 같은 sessionId 를 건드린 로그가
        // "다른 cid + 같은 sessionId" 로 남아, 경쟁이 실제로 일어난 순간을 사후에 짚을 수 있다.
        // (try-with-resources 는 catch 보다 자원을 먼저 닫으므로, 실패 로그가 cid 를 잃지 않도록
        //  스코프를 바깥에 두고 try/catch 를 안쪽에 둔다.)
        try (CorrelationIds.Scope tick = CorrelationIds.startTask("timeout-sweep")) {
            sweep();
        }
    }

    private void sweep() {
        try {
            log.debug("세션 타임아웃 체크 시작 - 버퍼시간: {}분", defaultBufferMinutes);

            List<Session> inProgressSessions = sessionRepository.findByStatus(Status.IN_PROGRESS);

            if (inProgressSessions.isEmpty()) {
                log.debug("타임아웃 체크 대상 세션 없음");
                return;
            }

            LocalDateTime now = LocalDateTime.now();
            int timeoutCount = 0;
            int yieldedCount = 0;

            for (Session session : inProgressSessions) {
                // 식은 Session 이 갖는다 — 재부착 허용 판정(SessionService.findReattachableSession)이
                // 같은 식을 써야 두 기준이 어긋나지 않는다(이슈 #59 2단계).
                LocalDateTime timeoutThreshold = session.timeoutThreshold(idleMinutes, defaultBufferMinutes);

                if (!session.isTimedOutAt(now, idleMinutes, defaultBufferMinutes)) {
                    continue;
                }

                try (CorrelationIds.Scope perSession = CorrelationIds.withSession(session.getId())) {
                    try {
                        // notifyAi=true — 걷어가는 세션은 AI 에 상태가 살아있을 수 있다. 통보하지
                        // 않으면 그 상태가 프로세스 재시작까지 남고, CompleteAnalysis 가 오지 않아
                        // pose_data 에 rep 이 있는데도 리포트가 안 만들어진다 (이슈 #98).
                        boolean changed = sessionService.markAsFailedIfStillInProgress(session.getId(), now, true);
                        if (changed) {
                            log.warn("세션 타임아웃 처리 - 세션 ID: {}, 멤버: {}, 운동: {}, 시작시간: {}, 타임아웃기준: {}",
                                    session.getId(),
                                    session.getMember().getId(),
                                    session.getExercise().getName(),
                                    session.getStartTime(),
                                    timeoutThreshold);
                            timeoutCount++;
                            sessionMetrics.sessionTransition(Status.FAILED, "timeout-scheduler");
                        }
                    } catch (ObjectOptimisticLockingFailureException e) {
                        // FastAPI 완료 콜백이 동시에 같은 세션을 갱신함. 결과 데이터가 더 가치있으므로 양보.
                        yieldedCount++;
                        sessionMetrics.optimisticLockConflict("timeout-scheduler", "yield");
                        log.info("세션 타임아웃 양보 - 세션 ID: {} (FastAPI 결과 우선)", session.getId());
                    } catch (Exception e) {
                        log.error("세션 {} 타임아웃 처리 실패", session.getId(), e);
                    }
                }
            }

            if (timeoutCount > 0 || yieldedCount > 0) {
                log.info("세션 타임아웃 처리 완료 - FAILED 전환: {}건, FastAPI 양보: {}건",
                        timeoutCount, yieldedCount);
            }

        } catch (Exception e) {
            log.error("세션 타임아웃 체크 중 에러 발생", e);
        }
    }
}