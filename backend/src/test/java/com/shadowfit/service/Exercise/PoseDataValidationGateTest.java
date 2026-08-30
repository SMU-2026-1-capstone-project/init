package com.shadowfit.service.Exercise;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.observability.SessionMetrics;
import com.shadowfit.grpc.PoseDataRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@DisplayName("PoseDataValidationGate 테스트")
class PoseDataValidationGateTest {

    @Mock private SessionMetrics sessionMetrics;
    private PoseDataValidationGate gate;

    private static final Long SESSION_ID = 1L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        gate = new PoseDataValidationGate(sessionMetrics);
    }

    @Test
    @DisplayName("정상 배치는 통과한다")
    void validBatch_passes() {
        List<PoseDataRequest> batch = List.of(frame(10.0, 70.0), frame(10.1, 70.0));

        assertThatCode(() -> gate.validate(SESSION_ID, batch)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("배치 크기가 상한(1000)을 넘으면 거부한다")
    void batchTooLarge_rejects() {
        List<PoseDataRequest> batch = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            batch.add(frame(i / 10.0, 70.0));
        }

        assertThatThrownBy(() -> gate.validate(SESSION_ID, batch))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DATA_INTEGRITY_VIOLATION);
        verify(sessionMetrics).poseBatchInvalid("batch_size");
    }

    @Test
    @DisplayName("sync_rate가 음수면 거부한다")
    void negativeSyncRate_rejects() {
        List<PoseDataRequest> batch = List.of(frame(1.0, -5.0));

        assertThatThrownBy(() -> gate.validate(SESSION_ID, batch))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DATA_INTEGRITY_VIOLATION);
        verify(sessionMetrics).poseBatchInvalid("sync_rate_range");
    }

    @Test
    @DisplayName("sync_rate가 100을 넘으면 거부한다 — 0~1 스케일이 아니라 0~100 스케일이다")
    void syncRateOver100_rejects() {
        List<PoseDataRequest> batch = List.of(frame(1.0, 100.1));

        assertThatThrownBy(() -> gate.validate(SESSION_ID, batch))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DATA_INTEGRITY_VIOLATION);
        verify(sessionMetrics).poseBatchInvalid("sync_rate_range");
    }

    @Test
    @DisplayName("sync_rate 경계값(0, 100)은 통과한다")
    void syncRateBoundaries_pass() {
        List<PoseDataRequest> batch = List.of(frame(1.0, 0.0), frame(1.1, 100.0));

        assertThatCode(() -> gate.validate(SESSION_ID, batch)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("timestamp_sec이 음수면 거부한다")
    void negativeTimestamp_rejects() {
        List<PoseDataRequest> batch = List.of(frame(-1.0, 70.0));

        assertThatThrownBy(() -> gate.validate(SESSION_ID, batch))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DATA_INTEGRITY_VIOLATION);
        verify(sessionMetrics).poseBatchInvalid("timestamp_range");
    }

    @Test
    @DisplayName("timestamp_sec이 60분(3600초)을 넘으면 거부한다")
    void timestampOverMax_rejects() {
        List<PoseDataRequest> batch = List.of(frame(3600.1, 70.0));

        assertThatThrownBy(() -> gate.validate(SESSION_ID, batch))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DATA_INTEGRITY_VIOLATION);
        verify(sessionMetrics).poseBatchInvalid("timestamp_range");
    }

    @Test
    @DisplayName("timestamp_sec 경계값(0, 3600)은 통과한다")
    void timestampBoundaries_pass() {
        List<PoseDataRequest> batch = List.of(frame(0.0, 70.0), frame(3600.0, 70.0));

        assertThatCode(() -> gate.validate(SESSION_ID, batch)).doesNotThrowAnyException();
    }

    private static PoseDataRequest frame(double timestampSec, double syncRate) {
        return PoseDataRequest.newBuilder()
                .setTimestampSec(timestampSec)
                .setJointCoordinates("{}")
                .setSyncRate(syncRate)
                .setRepNumber(0)
                .setSmoothedKneeAngle(0.0)
                .setFeedbackMessage("ok")
                .build();
    }
}
