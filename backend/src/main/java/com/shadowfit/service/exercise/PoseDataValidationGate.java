package com.shadowfit.service.exercise;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.observability.SessionMetrics;
import com.shadowfit.grpc.PoseDataRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * pose 배치의 형식·범위 검증 (BE-11). {@code PoseDataService.savePoseDataBatch}가 DB에 쓰기
 * 전에 통과해야 하는 게이트.
 *
 * <p><b>여기서 안 다루는 것 — 세션 소유권.</b> BE-11 원 스코프는 "sessionId 소유권 검증"도
 * 포함했지만, 그건 이슈 #187이 이미 다른 메커니즘(세션 nonce, 채널①에서 신원 확인)으로
 * 닫았다({@code docs/decisions/ai-session-ownership-verification.md} §7-2-2, "#187 닫힘").
 * 이 클래스가 잡는 건 신원은 맞는데 <b>값 자체가 이상한</b> 배치 — AI 오작동·손상 전송 등.
 * 2026-08-30 사용자 confirm으로 스코프를 이쪽으로 좁혔다.
 *
 * <p><b>sync_rate 범위 정정</b> — BE-11 원문서는 "[0.0, 1.0]"이라고 적었지만, 실제로는
 * 0~100 스케일이다({@code pose_data.sync_rate DECIMAL(5,2)}, {@code dtw_calculator.py}의
 * {@code sync_rate >= 70} 분기 확인). 원문서 작성 시점의 가정이 틀렸던 것 — 여기서는 실제
 * 스케일(0~100)로 검증한다.
 *
 * <p><b>timestamp_sec 상한의 의미 정정</b> — "세션 시작 시간 + 60분"이 아니다. 이 값은
 * {@code session.startTime} 기준이 아니라 <b>"첫 프레임 도착(또는 재부착) 기준 경과 시간"</b>이다
 * (재부착 시 리셋됨, {@code SessionRepository} 주석 참고). 그래서 여기서는 세션 시작 시각과
 * 무관하게 <b>절대 상한(60분)</b>으로만 사니티 체크한다 — 절대 다수 운동이 그보다 짧다는
 * 상식적 가드레일이지, 실측으로 도출한 값은 아니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PoseDataValidationGate {

    // 🔴 세 값 다 미검증 잠정치 — 실사용자 트래픽으로 재조정 대상.
    private static final int MAX_BATCH_SIZE = 1000;
    private static final double SYNC_RATE_MIN = 0.0;
    private static final double SYNC_RATE_MAX = 100.0;
    private static final double MAX_TIMESTAMP_SEC = 3600.0; // 60분 — 절대 상한, session.startTime 무관

    private final SessionMetrics sessionMetrics;

    public void validate(Long sessionId, List<PoseDataRequest> batch) {
        if (batch.size() > MAX_BATCH_SIZE) {
            reject(sessionId, "batch_size", "배치 크기 " + batch.size() + "건 > 상한 " + MAX_BATCH_SIZE);
        }

        for (PoseDataRequest frame : batch) {
            double syncRate = frame.getSyncRate();
            if (syncRate < SYNC_RATE_MIN || syncRate > SYNC_RATE_MAX) {
                reject(sessionId, "sync_rate_range", "sync_rate=" + syncRate + " 범위 [0,100] 밖");
            }

            double timestampSec = frame.getTimestampSec();
            if (timestampSec < 0 || timestampSec > MAX_TIMESTAMP_SEC) {
                reject(sessionId, "timestamp_range", "timestamp_sec=" + timestampSec + " 범위 [0," + MAX_TIMESTAMP_SEC + "] 밖");
            }
        }
    }

    private void reject(Long sessionId, String reason, String detail) {
        sessionMetrics.poseBatchInvalid(reason);
        log.warn("세션 {} : pose 배치 거부(reason={}) — {}", sessionId, reason, detail);
        throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
    }
}
