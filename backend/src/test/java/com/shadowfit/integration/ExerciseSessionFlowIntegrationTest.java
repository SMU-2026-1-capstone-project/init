package com.shadowfit.integration;

import com.shadowfit.dto.report.detailreport.SessionReportResponseDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.grpc.*;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
import com.shadowfit.model.exercise.FeedbackType;
import com.shadowfit.model.exercise.PoseData;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.SessionFeedbackLog;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.model.report.Report;
import com.shadowfit.model.report.ReportType;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.exercise.SessionFeedbackLogRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.report.ReportRepository;
import com.shadowfit.service.Exercise.ExerciseGrpcService;
import com.shadowfit.service.Exercise.SessionService;
import com.shadowfit.service.Report.ReportService;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 운동 세션 결합 e2e 통합 테스트.
 *
 * <p>AI 서버는 띄우지 않고, AI → Spring 콜백을 직접 시뮬레이션 (gRPC servicer 메서드를 자바 코드로
 * 직접 호출). Spring 측의 controller·service·repository·동시성·멱등성 흐름을 H2 인메모리 DB 위에서
 * 한 사이클 단위로 검증한다.
 *
 * <p>수동 e2e 절차(카메라·프론트·AI 컨테이너 포함)는 docs/18-testing-guide.md §8 참조.
 */
@SpringBootTest
@Transactional
@DisplayName("운동 세션 결합 e2e — Spring 측 한 사이클 검증")
class ExerciseSessionFlowIntegrationTest {

    @Autowired private ExerciseGrpcService grpcService;
    @Autowired private SessionService sessionService;
    @Autowired private ReportService reportService;

    @Autowired private SessionRepository sessionRepository;
    @Autowired private PoseDataRepository poseDataRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private ReportRepository reportRepository;
    @Autowired private SessionFeedbackLogRepository feedbackLogRepository;

    private Member testMember;
    private Exercise testExercise;

    @BeforeEach
    void setUp() {
        testMember = memberRepository.saveAndFlush(Member.builder()
                .email("e2e@test.com")
                .username("e2e사용자")
                .password("dummy")
                .preferredUrl("https://youtu.be/dummy")
                .selectedPersona(SelectedPersona.BEGINNER)
                .role(UserRole.USER)
                .build());

        testExercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트")
                .category(ExerciseCategory.LOWER)
                .expectedDurationMinutes(15)
                .syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00"))
                .build());
    }

    /**
     * 새 세션을 IN_PROGRESS 로 저장. 실제 ExerciseAnalysisService.startAnalysis 는
     * @Async + gRPC 호출까지 가지만, 본 테스트는 결합의 *수신측* 만 검증하므로
     * DB 세션 생성만 직접 수행한다.
     */
    private Session createInProgressSession() {
        return sessionRepository.saveAndFlush(Session.builder()
                .member(testMember)
                .exercise(testExercise)
                .referenceSource("https://youtu.be/dummy")
                .startTime(LocalDateTime.now())
                .status(Status.IN_PROGRESS)
                .totalReps(0)
                .difficultyLevel(1)
                .build());
    }

    private PoseDataBatchRequest sampleBatch(Long sessionId, int frameCount, double syncRate) {
        return sampleBatch(sessionId, 1, frameCount, syncRate);
    }

    /**
     * AI 는 rep 이 완성될 때마다 그 rep 의 프레임 묶음을 보낸다 — 그래서 한 배치 = 한 rep 이고,
     * 묶음 안의 프레임은 모두 같은 {@code syncRate}·{@code repNumber} 를 갖는다(ai-server
     * {@code pose.py}).
     *
     * <p>{@code repNumber} 를 실제로 실어 보내는 이유: 세션 완료 시 싱크 통계를 {@code pose_data} 에서
     * <b>rep 단위로</b> 집계하므로(이슈 #75), 이게 0 이면 집계에서 빠져 구버전 AI 폴백 경로로 새어버린다.
     * 그러면 이 테스트가 실제 운영 경로를 검증하지 않게 된다.
     */
    private PoseDataBatchRequest sampleBatch(Long sessionId, int repNumber, int frameCount, double syncRate) {
        PoseDataBatchRequest.Builder b = PoseDataBatchRequest.newBuilder()
                .setSessionId(sessionId);
        for (int i = 0; i < frameCount; i++) {
            b.addPoseData(PoseDataRequest.newBuilder()
                    .setRepNumber(repNumber)
                    .setTimestampSec(i * 0.1)
                    .setJointCoordinates("[{\"index\":11,\"x\":0.5,\"y\":0.5,\"z\":0.0,\"visibility\":1.0}]")
                    .setSyncRate(syncRate)
                    .setFeedbackMessage("자세 양호")
                    .build());
        }
        return b.build();
    }

    private SessionCompleteRequest sampleComplete(Long sessionId, int totalReps, double avg) {
        return SessionCompleteRequest.newBuilder()
                .setSessionId(sessionId)
                .setTotalReps(totalReps)
                .setAvgSyncRate(avg)
                .setMaxSyncRate(avg + 5)
                .setMinSyncRate(avg - 5)
                .setCaloriesBurned(0.0)
                .setDifficultyLevel(1)
                .build();
    }

    @Nested
    @DisplayName("정상 한 사이클")
    class NormalFlow {

        @Test
        @DisplayName("rep 단위 SavePoseDataBatch → CompleteAnalysis 콜백 후 DB 일관 상태")
        void normal_one_cycle() {
            // given: IN_PROGRESS 세션 시작
            Session session = createInProgressSession();
            Long sessionId = session.getId();

            // when 1: rep 1 완성 콜백 (AI → Spring) — 15프레임(실제 R=25 배치 규모에 준함, 다운샘플
            // 윈도우 5개 정확히 채워 3행 남김: WorstSectionCalculator가 worst 구간을 잡으려면
            // 다운샘플 후에도 최소 WORST_WINDOW_SIZE(3)행이 있어야 함(그 아래면 null, 정상 케이스)
            @SuppressWarnings("unchecked")
            StreamObserver<PoseDataResponse> batchObs1 = mock(StreamObserver.class);
            grpcService.savePoseDataBatch(sampleBatch(sessionId, 1, 15, 80.0), batchObs1);

            // when 2: rep 2 완성 콜백 (10프레임 → 다운샘플 후 2행)
            @SuppressWarnings("unchecked")
            StreamObserver<PoseDataResponse> batchObs2 = mock(StreamObserver.class);
            grpcService.savePoseDataBatch(sampleBatch(sessionId, 2, 10, 75.0), batchObs2);

            // when 3: 세션 종료 콜백
            @SuppressWarnings("unchecked")
            StreamObserver<SessionCompleteResponse> completeObs = mock(StreamObserver.class);
            grpcService.completeAnalysis(sampleComplete(sessionId, 2, 77.5), completeObs);

            // then: gRPC 응답이 정상적으로 전달됨
            verify(batchObs1, times(1)).onNext(any(PoseDataResponse.class));
            verify(batchObs1, times(1)).onCompleted();
            verify(batchObs2, times(1)).onNext(any());

            ArgumentCaptor<SessionCompleteResponse> respCaptor =
                    ArgumentCaptor.forClass(SessionCompleteResponse.class);
            verify(completeObs).onNext(respCaptor.capture());
            verify(completeObs).onCompleted();
            assertThat(respCaptor.getValue().getStatus()).isEqualTo(SessionStatus.COMPLETED);
            assertThat(respCaptor.getValue().getSessionId()).isEqualTo(sessionId);

            // then: pose_data 테이블에 다운샘플 대표행만 저장 — rep1(15프레임→3행), rep2(10프레임→2행) = 5행
            // (PoseDataService.DOWNSAMPLE_WINDOW=5, docs/decisions/pose-ingest-downsampling.md §5-1(7)(8))
            List<PoseData> rows = poseDataRepository.findAll();
            assertThat(rows).hasSize(5);
            assertThat(rows).allSatisfy(p -> {
                assertThat(p.getSession().getId()).isEqualTo(sessionId);
                assertThat(p.getJointCoordinates()).isNotEmpty();
                assertThat(p.getSyncRate()).isBetween(70.0, 85.0);
            });

            // then: 세션 종료 상태로 영속화
            Session finished = sessionRepository.findById(sessionId).orElseThrow();
            assertThat(finished.getStatus()).isEqualTo(Status.COMPLETED);
            assertThat(finished.getTotalReps()).isEqualTo(2);
            // 싱크 통계는 AI 가 보낸 값이 아니라 pose_data 에서 rep 단위로 집계한 값이다(이슈 #75).
            // 여기서 두 계산이 갈린다 — rep 가중 (80+75)/2 = 77.5 / 프레임 가중 (80×3+75×2)/5 = 78.0.
            // 다운샘플로 rep1 은 3행, rep2 는 2행만 남았기 때문에 값이 다르고, 77.5 여야 맞다.
            assertThat(finished.getAvgSyncRate()).isEqualByComparingTo(new BigDecimal("77.5"));
            assertThat(finished.getMaxSyncRate()).isEqualByComparingTo(new BigDecimal("80.00"));
            assertThat(finished.getMinSyncRate()).isEqualByComparingTo(new BigDecimal("75.00"));
            assertThat(finished.getEndTime()).isNotNull();
            assertThat(finished.getVersion()).isGreaterThanOrEqualTo(1L);

            // then: precompute-on-write — 세션 완료와 같은 트랜잭션에서 reports가 미리 생성됨
            // (report-read-path.md §9, 조회 시점 pose_data 재계산 없이 바로 읽을 수 있어야 함)
            Report report = reportRepository.findBySessionId(sessionId).orElseThrow();
            assertThat(report.getReportType()).isEqualTo(ReportType.SESSION);
            assertThat(report.getMember().getId()).isEqualTo(testMember.getId());
            assertThat(report.getDetailedAnalysis()).isNotBlank();
            assertThat(report.getDetailedAnalysis()).contains("싱크로율");
        }
    }

    @Nested
    @DisplayName("멱등성 — At-Least-Once 콜백 대응")
    class Idempotency {

        @Test
        @DisplayName("CompleteAnalysis가 두 번 도착해도 첫 결과·endTime 보존, version 추가 증가 없음")
        void duplicate_complete_callback_is_noop() {
            // given
            Session session = createInProgressSession();
            Long sessionId = session.getId();

            // when 1: 첫 콜백
            @SuppressWarnings("unchecked")
            StreamObserver<SessionCompleteResponse> obs1 = mock(StreamObserver.class);
            grpcService.completeAnalysis(sampleComplete(sessionId, 3, 80.0), obs1);

            Session afterFirst = sessionRepository.findById(sessionId).orElseThrow();
            LocalDateTime firstEndTime = afterFirst.getEndTime();
            Long firstVersion = afterFirst.getVersion();
            assertThat(afterFirst.getStatus()).isEqualTo(Status.COMPLETED);
            assertNotNull(firstEndTime);

            // when 2: 같은 sessionId 로 재전송 (값은 약간 달라도 무시되어야 함)
            @SuppressWarnings("unchecked")
            StreamObserver<SessionCompleteResponse> obs2 = mock(StreamObserver.class);
            grpcService.completeAnalysis(sampleComplete(sessionId, 99, 99.9), obs2);

            // then: 첫 결과 그대로 보존
            Session afterSecond = sessionRepository.findById(sessionId).orElseThrow();
            assertThat(afterSecond.getStatus()).isEqualTo(Status.COMPLETED);
            assertThat(afterSecond.getTotalReps()).isEqualTo(3); // 99 로 덮이지 않음
            assertThat(afterSecond.getAvgSyncRate()).isEqualByComparingTo(new BigDecimal("80.0"));
            assertThat(afterSecond.getEndTime()).isEqualTo(firstEndTime);
            assertThat(afterSecond.getVersion()).isEqualTo(firstVersion); // version 증가 없음
        }
    }

    @Nested
    @DisplayName("타임아웃 후 늦은 콜백 — FAILED → COMPLETED 덮어쓰기")
    class LateCallbackAfterTimeout {

        @Test
        @DisplayName("스케줄러가 FAILED 처리한 후에도 AI 콜백이 도착하면 COMPLETED 로 덮어씀")
        void late_callback_overwrites_failed() {
            // given: IN_PROGRESS → 스케줄러가 FAILED 로 떨어뜨린 상태
            Session session = createInProgressSession();
            Long sessionId = session.getId();
            boolean failed = sessionService.markAsFailedIfStillInProgress(
                    sessionId, LocalDateTime.now());
            assertThat(failed).isTrue();
            assertThat(sessionRepository.findById(sessionId).orElseThrow().getStatus())
                    .isEqualTo(Status.FAILED);

            // when: 늦게 도착한 AI 완료 콜백
            @SuppressWarnings("unchecked")
            StreamObserver<SessionCompleteResponse> obs = mock(StreamObserver.class);
            grpcService.completeAnalysis(sampleComplete(sessionId, 5, 82.0), obs);

            // then: COMPLETED 로 덮여쓰임, 운동 결과 유실 X
            Session result = sessionRepository.findById(sessionId).orElseThrow();
            assertThat(result.getStatus()).isEqualTo(Status.COMPLETED);
            assertThat(result.getTotalReps()).isEqualTo(5);
            assertThat(result.getAvgSyncRate()).isEqualByComparingTo(new BigDecimal("82.0"));
        }
    }

    @Nested
    @DisplayName("세션 없을 때 콜백 — 안전 처리")
    class CallbackForUnknownSession {

        @Test
        @DisplayName("존재하지 않는 sessionId 로 SavePoseDataBatch → 에러 응답, DB 변경 없음")
        void save_batch_for_unknown_session_errors_safely() {
            long unknownId = 9999L;
            @SuppressWarnings("unchecked")
            StreamObserver<PoseDataResponse> obs = mock(StreamObserver.class);

            grpcService.savePoseDataBatch(sampleBatch(unknownId, 3, 70.0), obs);

            // SESSION_NOT_FOUND → catch → onError 호출
            verify(obs, times(1)).onError(any(Throwable.class));
            assertThat(poseDataRepository.count()).isZero();
        }
    }

    @Nested
    @DisplayName("개별 세션 삭제 (pose-data-partition-fk-tradeoff.md §5-1)")
    class DeleteSession {

        @Test
        @DisplayName("완료된 세션 삭제 → 세션·pose_data·reports·session_feedback_logs 모두 정리됨")
        void delete_completed_session_removes_pose_data_and_report() {
            // given: rep 완성 콜백 + 종료 콜백까지 거쳐 COMPLETED + pose_data + report(precompute) 생성
            Session session = createInProgressSession();
            Long sessionId = session.getId();

            @SuppressWarnings("unchecked")
            StreamObserver<PoseDataResponse> batchObs = mock(StreamObserver.class);
            grpcService.savePoseDataBatch(sampleBatch(sessionId, 5, 80.0), batchObs);

            // 이번 삭제로 같이 정리돼야 할 session_feedback_logs (SessionFeedbackLog.session의
            // @OnDelete(CASCADE) 계약 검증 — CodeRabbit 리뷰로 보강)
            feedbackLogRepository.saveAndFlush(SessionFeedbackLog.builder()
                    .session(session)
                    .feedbackType(FeedbackType.KNEE_OUT)
                    .occurredAt(LocalDateTime.now())
                    .build());

            @SuppressWarnings("unchecked")
            StreamObserver<SessionCompleteResponse> completeObs = mock(StreamObserver.class);
            grpcService.completeAnalysis(sampleComplete(sessionId, 1, 80.0), completeObs);

            // 5프레임 → 다운샘플 윈도우(5) 1개 → 대표 1행만 저장됨
            assertThat(poseDataRepository.count()).isEqualTo(1);
            assertThat(reportRepository.findBySessionId(sessionId)).isPresent();
            assertThat(feedbackLogRepository.findBySessionIdOrderByOccurredAtAsc(sessionId)).hasSize(1);

            // when
            sessionService.deleteSession(sessionId, testMember.getId());

            // then: 세션 자체도, pose_data(명시적 삭제)도, reports·session_feedback_logs(FK CASCADE)도 전부 사라짐
            assertThat(sessionRepository.findById(sessionId)).isEmpty();
            assertThat(poseDataRepository.count()).isZero();
            assertThat(reportRepository.findBySessionId(sessionId)).isEmpty();
            assertThat(feedbackLogRepository.findBySessionIdOrderByOccurredAtAsc(sessionId)).isEmpty();
        }

        @Test
        @DisplayName("IN_PROGRESS 세션 삭제 시도 → SESSION_DELETE_NOT_ALLOWED, 세션 그대로 남음")
        void delete_in_progress_session_is_rejected() {
            Session session = createInProgressSession();
            Long sessionId = session.getId();

            assertThatThrownBy(() -> sessionService.deleteSession(sessionId, testMember.getId()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.SESSION_DELETE_NOT_ALLOWED);

            assertThat(sessionRepository.findById(sessionId)).isPresent();
        }

        @Test
        @DisplayName("본인 세션이 아니면 SESSION_NOT_FOUND — 존재 여부 비공개")
        void delete_others_session_is_not_found() {
            Session session = createInProgressSession();
            Long sessionId = session.getId();
            sessionService.markAsFailedIfStillInProgress(sessionId, LocalDateTime.now()); // FAILED로 종료 처리

            Member otherMember = memberRepository.saveAndFlush(Member.builder()
                    .email("other@test.com")
                    .username("다른유저")
                    .password("dummy")
                    .selectedPersona(SelectedPersona.BEGINNER)
                    .role(UserRole.USER)
                    .build());

            assertThatThrownBy(() -> sessionService.deleteSession(sessionId, otherMember.getId()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.SESSION_NOT_FOUND);

            assertThat(sessionRepository.findById(sessionId)).isPresent(); // 삭제 안 됨
        }
    }

    @Nested
    @DisplayName("이전 세션과 비교 (CodeRabbit 발견 — 자기 자신과 비교하는 버그)")
    class ReportComparisonWithPrevious {

        @Test
        @DisplayName("가장 최근 완료 세션을 조회해도 comparisonWithPrevious는 자기 자신이 아닌 진짜 이전 세션 기준")
        void comparisonExcludesCurrentSession() {
            // given: 같은 회원·같은 운동으로 먼저 끝난 세션(older, avg 70) → 나중에 끝난 세션(newer, avg 80)
            Session olderSession = sessionRepository.saveAndFlush(Session.builder()
                    .member(testMember).exercise(testExercise)
                    .startTime(LocalDateTime.now().minusHours(2))
                    .status(Status.IN_PROGRESS).totalReps(0).difficultyLevel(1)
                    .build());
            @SuppressWarnings("unchecked")
            StreamObserver<SessionCompleteResponse> olderObs = mock(StreamObserver.class);
            grpcService.completeAnalysis(sampleComplete(olderSession.getId(), 8, 70.0), olderObs);

            Session newerSession = sessionRepository.saveAndFlush(Session.builder()
                    .member(testMember).exercise(testExercise)
                    .startTime(LocalDateTime.now().minusMinutes(10))
                    .status(Status.IN_PROGRESS).totalReps(0).difficultyLevel(1)
                    .build());
            @SuppressWarnings("unchecked")
            StreamObserver<SessionCompleteResponse> newerObs = mock(StreamObserver.class);
            grpcService.completeAnalysis(sampleComplete(newerSession.getId(), 10, 80.0), newerObs);

            // when: 가장 최근(newer) 세션의 리포트 조회
            SessionReportResponseDto result = reportService.getSessionReport(newerSession.getId(), testMember.getId());

            // then: newer가 자기 자신과 비교(diff 0)된 게 아니라 older(70.0) 기준으로 diff 10이어야 함
            assertThat(result.getComparisonWithPrevious()).isNotNull();
            assertThat(result.getComparisonWithPrevious().getSyncRateDiff()).isEqualTo(10);
        }
    }
}
