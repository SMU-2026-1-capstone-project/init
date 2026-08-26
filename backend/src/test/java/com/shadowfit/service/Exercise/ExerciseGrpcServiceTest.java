package com.shadowfit.service.Exercise;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.grpc.ExtractRequest;
import com.shadowfit.grpc.ExtractResponse;
import com.shadowfit.grpc.FeedbackBatchRequest;
import com.shadowfit.grpc.FeedbackBatchResponse;
import com.shadowfit.grpc.PoseDataBatchRequest;
import com.shadowfit.grpc.PoseDataResponse;
import com.shadowfit.grpc.SessionCompleteRequest;
import com.shadowfit.grpc.SessionCompleteResponse;
import com.shadowfit.grpc.SessionStatus;
import com.shadowfit.global.observability.SessionMetrics;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExerciseGrpcService 단위테스트 — gRPC 서비스 구현체가 서비스 계층 예외를 올바른 gRPC
 * Status로 매핑하는지 검증(BusinessException → INVALID_ARGUMENT, 그 외 → INTERNAL).
 */
@DisplayName("ExerciseGrpcService 테스트")
class ExerciseGrpcServiceTest {

    @Mock private PoseDataService poseDataService;
    @Mock private SessionService sessionService;
    @Mock private FeedbackLogService feedbackLogService;
    @Mock private SessionMetrics sessionMetrics;
    private ExerciseGrpcService grpcService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        grpcService = new ExerciseGrpcService(poseDataService, sessionService, feedbackLogService, sessionMetrics);
    }

    @Test
    @DisplayName("savePoseDataBatch 성공 — onNext + onCompleted")
    void savePoseDataBatch_success() {
        PoseDataBatchRequest request = PoseDataBatchRequest.newBuilder().setSessionId(1L).build();
        @SuppressWarnings("unchecked")
        StreamObserver<PoseDataResponse> obs = mock(StreamObserver.class);

        grpcService.savePoseDataBatch(request, obs);

        ArgumentCaptor<PoseDataResponse> captor = ArgumentCaptor.forClass(PoseDataResponse.class);
        verify(obs).onNext(captor.capture());
        verify(obs).onCompleted();
        verify(obs, never()).onError(any());
        assertThat(captor.getValue().getSuccess()).isTrue();
    }

    @Test
    @DisplayName("savePoseDataBatch 실패 — **예상 못한** 예외만 INTERNAL 로 (#209)")
    void savePoseDataBatch_serviceThrows_mapsToInternal() {
        PoseDataBatchRequest request = PoseDataBatchRequest.newBuilder().setSessionId(1L).build();
        @SuppressWarnings("unchecked")
        StreamObserver<PoseDataResponse> obs = mock(StreamObserver.class);
        // 🔵 2026-08-23: 대상을 BusinessException → **예상 못한 예외**로 바꿨다 (#209).
        //    세션 소멸은 이제 NOT_FOUND 다(아래 별도 테스트). INTERNAL 은 «정말 우리가 아플 때» 만 남는다.
        doThrow(new IllegalStateException("커넥션 풀이 죽었다"))
                .when(poseDataService).savePoseDataBatch(anyLong(), anyList());

        grpcService.savePoseDataBatch(request, obs);

        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(obs).onError(captor.capture());
        assertThat(((StatusRuntimeException) captor.getValue()).getStatus().getCode())
                .isEqualTo(Status.Code.INTERNAL);
        verify(obs, never()).onNext(any());
        // 데드락이 아닌 예외는 다시 던지지 않는다 — 재시도는 #276 의 데드락 한 종류에만 걸린다
        verify(poseDataService, times(1)).savePoseDataBatch(anyLong(), anyList());
    }

    @Test
    @DisplayName("savePoseDataBatch 데드락 — 2회 안에 회복하면 성공으로 응답한다 (#276)")
    void savePoseDataBatch_deadlockThenRecovers() {
        PoseDataBatchRequest request = PoseDataBatchRequest.newBuilder().setSessionId(1L).build();
        @SuppressWarnings("unchecked")
        StreamObserver<PoseDataResponse> obs = mock(StreamObserver.class);
        // 두 번 데드락 → 세 번째 성공. 실측(0회 37.8% · 1회 3.8% · 2회 0.0%)이 고른 상한이 2 다.
        doThrow(new DeadlockLoserDataAccessException("deadlock", null))
                .doThrow(new DeadlockLoserDataAccessException("deadlock", null))
                .doNothing()
                .when(poseDataService).savePoseDataBatch(anyLong(), anyList());

        grpcService.savePoseDataBatch(request, obs);

        verify(poseDataService, times(3)).savePoseDataBatch(anyLong(), anyList());
        verify(obs).onCompleted();
        verify(obs, never()).onError(any());
        verify(sessionMetrics, times(2)).poseBatchDeadlockRetry("retried");
        verify(sessionMetrics).poseBatchDeadlockRetry("recovered");
    }

    @Test
    @DisplayName("savePoseDataBatch — 세션 소멸은 INTERNAL 이 아니라 NOT_FOUND 다 (#209)")
    void savePoseDataBatch_sessionNotFound_mapsToNotFound() {
        PoseDataBatchRequest request = PoseDataBatchRequest.newBuilder().setSessionId(1L).build();
        @SuppressWarnings("unchecked")
        StreamObserver<PoseDataResponse> obs = mock(StreamObserver.class);
        doThrow(new BusinessException(ErrorCode.SESSION_NOT_FOUND))
                .when(poseDataService).savePoseDataBatch(anyLong(), anyList());

        grpcService.savePoseDataBatch(request, obs);

        // 🔴 INTERNAL 은 「내가 아프다」다. 세션이 사라진 것은 그게 아니고, 상대는 그 둘을 갈라
        //    다르게 처리한다(사라진 세션에는 재전송하지 않는다).
        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(obs).onError(captor.capture());
        StatusRuntimeException ex = (StatusRuntimeException) captor.getValue();
        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        // 설명이 없으면 상대 로그에 «왜» 가 안 남는다 (#209 §1)
        assertThat(ex.getStatus().getDescription()).contains("SESSION_NOT_FOUND");
        // 영구 실패는 재시도 대상이 아니다 — 한 번만 부른다
        verify(poseDataService, times(1)).savePoseDataBatch(anyLong(), anyList());
    }

    @Test
    @DisplayName("savePoseDataBatch 데드락 — **실사용에서 오는 타입**(CannotAcquireLock)도 재시도한다 (#416)")
    void savePoseDataBatch_deadlockRetriesOnTranslatedType() {
        PoseDataBatchRequest request = PoseDataBatchRequest.newBuilder().setSessionId(1L).build();
        @SuppressWarnings("unchecked")
        StreamObserver<PoseDataResponse> obs = mock(StreamObserver.class);

        // 🔴 이 테스트가 존재하는 이유 (#416).
        //    위 두 테스트는 DeadlockLoserDataAccessException 을 **직접** 던진다. 그런데 실사용에서는
        //    그 타입이 오지 않는다 — Spring 6 의 기본 번역기(SQLExceptionSubclassTranslator)는 그
        //    클래스를 만들지 않고, MySQL 데드락(1213/40001)을 CannotAcquireLockException 계열로 바꾼다.
        //    그래서 재시도 루프가 2026-08-23 까지 **한 번도 돌지 않았는데도** 위 테스트는 초록이었다.
        //    가정이 그대로 테스트가 된 것이고, 이 테스트는 그 가정 밖을 지킨다.
        doThrow(new CannotAcquireLockException("Deadlock found when trying to get lock"))
                .doNothing()
                .when(poseDataService).savePoseDataBatch(anyLong(), anyList());

        grpcService.savePoseDataBatch(request, obs);

        verify(poseDataService, times(2)).savePoseDataBatch(anyLong(), anyList());
        verify(obs).onCompleted();
        verify(obs, never()).onError(any());
        verify(sessionMetrics).poseBatchDeadlockRetry("retried");
        verify(sessionMetrics).poseBatchDeadlockRetry("recovered");
    }

    @Test
    @DisplayName("savePoseDataBatch 데드락 — 상한을 다 쓰면 ABORTED 로 넘겨 AI 재전송에 맡긴다 (#276 ③)")
    void savePoseDataBatch_deadlockExhausted() {
        PoseDataBatchRequest request = PoseDataBatchRequest.newBuilder().setSessionId(1L).build();
        @SuppressWarnings("unchecked")
        StreamObserver<PoseDataResponse> obs = mock(StreamObserver.class);
        doThrow(new DeadlockLoserDataAccessException("deadlock", null))
                .when(poseDataService).savePoseDataBatch(anyLong(), anyList());

        grpcService.savePoseDataBatch(request, obs);

        // 첫 시도 + 재시도 **5회** = 6. 그 이상 던지지 않는다.
        // 🔵 상한은 2026-08-23 에 2 → 3, 2026-08-26 에 3 → 5 로 올라갔다(둘 다 사용자 confirm) —
        //    팔당 판을 6개로 늘려 재본 앱 경로 스윕에서 3 은 잔여 8.0%, 5 는 2.5% 였고
        //    5 가 4 를 짝비교 6/6 에서 이겼다(loadtest/results/r276-ceiling-rank-aws-2026-08-26/).
        verify(poseDataService, times(6)).savePoseDataBatch(anyLong(), anyList());
        ArgumentCaptor<Throwable> deadlockCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(obs).onError(deadlockCaptor.capture());
        // 🔵 2026-08-23: INTERNAL → **ABORTED** (#276 ③). 「우리가 아프다」가 아니라 「경합에 졌다」이고,
        //    AI 의 재전송이 이 코드에서만 돈다 — INTERNAL 이면 상대가 영구 실패와 구분하지 못한다.
        StatusRuntimeException deadlockEx = (StatusRuntimeException) deadlockCaptor.getValue();
        assertThat(deadlockEx.getStatus().getCode()).isEqualTo(Status.Code.ABORTED);
        assertThat(deadlockEx.getStatus().getDescription()).contains("DEADLOCK_RETRY_EXHAUSTED");
        verify(sessionMetrics).poseBatchDeadlockRetry("exhausted");
    }

    @Test
    @DisplayName("extractReferenceData 성공")
    void extractReferenceData_success() {
        ExtractRequest request = ExtractRequest.newBuilder().setExerciseId(1L).build();
        @SuppressWarnings("unchecked")
        StreamObserver<ExtractResponse> obs = mock(StreamObserver.class);

        grpcService.extractReferenceData(request, obs);

        ArgumentCaptor<ExtractResponse> captor = ArgumentCaptor.forClass(ExtractResponse.class);
        verify(obs).onNext(captor.capture());
        verify(obs).onCompleted();
        assertThat(captor.getValue().getSuccess()).isTrue();
    }

    @Test
    @DisplayName("completeAnalysis 성공 — COMPLETED 상태로 응답")
    void completeAnalysis_success() {
        SessionCompleteRequest request = SessionCompleteRequest.newBuilder().setSessionId(1L).build();
        @SuppressWarnings("unchecked")
        StreamObserver<SessionCompleteResponse> obs = mock(StreamObserver.class);

        grpcService.completeAnalysis(request, obs);

        ArgumentCaptor<SessionCompleteResponse> captor = ArgumentCaptor.forClass(SessionCompleteResponse.class);
        verify(obs).onNext(captor.capture());
        verify(obs).onCompleted();
        assertThat(captor.getValue().getStatus()).isEqualTo(SessionStatus.COMPLETED);
    }

    @Test
    @DisplayName("completeAnalysis 실패 — **예상 못한** 예외만 INTERNAL 로 (#209)")
    void completeAnalysis_serviceThrows_mapsToInternal() {
        SessionCompleteRequest request = SessionCompleteRequest.newBuilder().setSessionId(1L).build();
        @SuppressWarnings("unchecked")
        StreamObserver<SessionCompleteResponse> obs = mock(StreamObserver.class);
        // 🔵 2026-08-23: 같은 이유로 «예상 못한 예외» 로 바꿨다 (#209).
        doThrow(new IllegalStateException("커넥션 풀이 죽었다")).when(sessionService).completeSession(any());

        grpcService.completeAnalysis(request, obs);

        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(obs).onError(captor.capture());
        assertThat(((StatusRuntimeException) captor.getValue()).getStatus().getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    @DisplayName("reportFeedbackBatch 성공")
    void reportFeedbackBatch_success() {
        FeedbackBatchRequest request = FeedbackBatchRequest.newBuilder().setSessionId(1L).build();
        @SuppressWarnings("unchecked")
        StreamObserver<FeedbackBatchResponse> obs = mock(StreamObserver.class);
        when(feedbackLogService.saveBatch(request)).thenReturn(3);

        grpcService.reportFeedbackBatch(request, obs);

        ArgumentCaptor<FeedbackBatchResponse> captor = ArgumentCaptor.forClass(FeedbackBatchResponse.class);
        verify(obs).onNext(captor.capture());
        verify(obs).onCompleted();
        assertThat(captor.getValue().getSavedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("reportFeedbackBatch — 입력 오류 BusinessException은 INVALID_ARGUMENT로 매핑(그 외 예외와 구분)")
    void reportFeedbackBatch_businessException_mapsToInvalidArgument() {
        FeedbackBatchRequest request = FeedbackBatchRequest.newBuilder().setSessionId(1L).build();
        @SuppressWarnings("unchecked")
        StreamObserver<FeedbackBatchResponse> obs = mock(StreamObserver.class);
        when(feedbackLogService.saveBatch(request)).thenThrow(new BusinessException(ErrorCode.INVALID_INPUT_VALUE));

        grpcService.reportFeedbackBatch(request, obs);

        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(obs).onError(captor.capture());
        assertThat(((StatusRuntimeException) captor.getValue()).getStatus().getCode())
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    /**
     * 재전송 설계(feedback-batch-retransmission.md 축 C)가 세션 소멸과 입력 오류에 <b>다른 처리</b>를
     * 지정한다 — 전자는 버퍼 폐기, 후자는 그 건만 격리. 둘이 같은 상태코드로 나가면 AI 는
     * description 문자열을 파싱해야 갈라낼 수 있고, 그건 문구가 바뀌면 조용히 깨진다.
     * 이 테스트가 «갈라져 있다» 를 계약으로 잡아둔다 (#238 리뷰 A-2).
     */
    @Test
    @DisplayName("reportFeedbackBatch — SESSION_NOT_FOUND는 NOT_FOUND로 매핑(입력 오류와 상태코드가 갈린다)")
    void reportFeedbackBatch_sessionNotFound_mapsToNotFound() {
        FeedbackBatchRequest request = FeedbackBatchRequest.newBuilder().setSessionId(1L).build();
        @SuppressWarnings("unchecked")
        StreamObserver<FeedbackBatchResponse> obs = mock(StreamObserver.class);
        when(feedbackLogService.saveBatch(request)).thenThrow(new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        grpcService.reportFeedbackBatch(request, obs);

        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(obs).onError(captor.capture());
        Status status = ((StatusRuntimeException) captor.getValue()).getStatus();
        assertThat(status.getCode()).isEqualTo(Status.Code.NOT_FOUND);
        // 문자열 파싱에 기대지 않게 됐지만, 사람이 로그로 읽을 코드명은 그대로 실려 나간다.
        assertThat(status.getDescription()).startsWith(ErrorCode.SESSION_NOT_FOUND.name());
    }

    @Test
    @DisplayName("reportFeedbackBatch — 예상 못한 예외는 INTERNAL로 매핑")
    void reportFeedbackBatch_unexpectedException_mapsToInternal() {
        FeedbackBatchRequest request = FeedbackBatchRequest.newBuilder().setSessionId(1L).build();
        @SuppressWarnings("unchecked")
        StreamObserver<FeedbackBatchResponse> obs = mock(StreamObserver.class);
        when(feedbackLogService.saveBatch(request)).thenThrow(new RuntimeException("boom"));

        grpcService.reportFeedbackBatch(request, obs);

        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(obs).onError(captor.capture());
        assertThat(((StatusRuntimeException) captor.getValue()).getStatus().getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }
}
