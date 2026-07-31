package com.shadowfit.service.Exercise;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowfit.dto.exercises.VideoRequestDto;
import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.dto.report.detailreport.ExerciseSessionDto;
import com.shadowfit.dto.report.detailreport.WorstSectionDto;
import com.shadowfit.dto.report.record.CalendarDayDto;
import com.shadowfit.dto.report.record.CalendarMainResponseDto;
import com.shadowfit.dto.report.record.DailyActivityResponseDto;
import com.shadowfit.dto.report.record.DailyLogSummaryDto;
import com.shadowfit.dto.exercises.session.ActiveSessionResponseDto;
import com.shadowfit.dto.report.record.WeeklyActivityResponseDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.observability.CorrelationIds;
import com.shadowfit.global.observability.SessionMetrics;
import com.shadowfit.global.util.SetSummaryFormatter;
import com.shadowfit.model.outbox.OutboxEvent;
import com.shadowfit.repository.outbox.OutboxEventRepository;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.report.Report;
import com.shadowfit.model.report.ReportType;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.report.ReportRepository;
import com.shadowfit.service.Report.WorstSectionCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.shadowfit.grpc.SessionStatus;
import com.shadowfit.grpc.SessionCompleteRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;


//공통세션
@Service
@RequiredArgsConstructor
public class SessionService {
    private final SessionRepository sessionRepository;
    private final ExercisesRepository exercisesRepository;
    private final MemberRepository memberRepository;
    private final ObjectMapper objectMapper;
    private final com.shadowfit.service.Report.DailyLogService dailyLogService;
    private final PoseDataRepository poseDataRepository;
    private final WorstSectionCalculator worstSectionCalculator;
    private final ReportRepository reportRepository;
    private final SessionMetrics sessionMetrics;
    private final OutboxEventRepository outboxRepository;

    // 자기 주입: completeSession → applyComplete 호출이 Spring 프록시를 통과해 @Transactional이 적용되도록 함.
    @Lazy
    @Autowired
    private SessionService self;

    // ExerciseAnalysisService 주입은 제거됐다. endSession 이 gRPC 를 직접 부르지 않고 아웃박스 행만
    // 남기게 되면서 유일한 사용처가 사라졌고, 그 결과 SessionService ↔ ExerciseAnalysisService
    // 순환 의존(@Lazy 로 우회하던)도 함께 없어졌다. 아웃박스가 두 서비스 사이의 결합을 끊은 셈.

    // 재부착 허용 시간 상한 — SessionTimeoutScheduler 와 같은 프로퍼티를 읽는다. 상한을 별도 상수로
    // 두면 두 값이 어긋날 때 "재부착은 됐는데 곧 걷혀가는" 세션이 생긴다(findReattachableSession).
    // @RequiredArgsConstructor 라 생성자 파라미터로는 못 넣어 필드 주입을 쓴다.
    @Value("${exercise.session.timeout.default-buffer-minutes:30}")
    private Integer defaultBufferMinutes;

    /**
     * [세션 생성] 새로운 운동 분석 프로세스를 시작하기 위한 초기 레코드를 생성합니다.
     *
     * @param appDto          사용자가 선택한 운동 및 영상 정보
     * @param currentMemberId 현재 로그인한 사용자 ID
     * @return 생성된 세션 엔티티
     */
    @Transactional
    public Session createSession(VideoRequestDto appDto, Long currentMemberId, String finalUrl) {
        // 회원 row를 잠그고 그 안에서 활성 세션 체크 → 다른 트랜잭션이 같은 회원에 대해 끼어들 수
        // 없어 존재하지 않는지 확인하고 나서 즉시 액세스 삽입 사이의 레이스(TOCTOU)가 안 생김. 유니크
        // 제약(generated column) 시도는 member_id의 FK가 ON DELETE CASCADE라 MySQL이 막아서 폐기
        // (2026-07-16, "Cannot add foreign key constraint").
        Member member = memberRepository.findByIdForUpdate(currentMemberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (sessionRepository.existsByMemberIdAndStatus(currentMemberId, Status.IN_PROGRESS)) {
            throw new BusinessException(ErrorCode.SESSION_ALREADY_IN_PROGRESS);
        }

        Exercise exercise = exercisesRepository.findByIdCached(appDto.getExerciseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EXERCISE_NOT_FOUND));

        // 종목 행은 시드돼 있어도 ai-server에 분석기가 없으면(런지·플랭크) 세션이 조용히 빈 결과를
        // 내놓는다 — "선택은 되는데 안 되는" 상태. 여기서 막아 의도적 제한으로 만든다.
        // 분석기가 붙으면 exercises.analysis_supported 를 TRUE 로 바꾸는 것만으로 열린다.
        if (!Boolean.TRUE.equals(exercise.getAnalysisSupported())) {
            throw new BusinessException(ErrorCode.EXERCISE_NOT_SUPPORTED);
        }

        Session session = Session.builder()
                .member(member)
                .exercise(exercise)
                .referenceSource(finalUrl)
                .startTime(LocalDateTime.now())
                .status(Status.IN_PROGRESS)
                .build();

        return sessionRepository.save(session);
    }

    /**
     * [진행 중 세션 조회] 이 회원에게 IN_PROGRESS 세션이 있으면 반환합니다.
     *
     * <p>없던 이유가 아니라 <b>있어야 하는 이유</b>: 클라는 세션 id를 화면 상태로만 들고 있어서
     * 앱 프로세스가 죽으면 잃는다. 그 상태로 다시 "시작"을 누르면 서버엔 IN_PROGRESS가 남아 있어
     * {@code SESSION_ALREADY_IN_PROGRESS}(409)로 막히고, 재개도 새 시작도 못 하는 상태가 된다
     * (타임아웃 스케줄러가 걷어갈 때까지). 서버는 {@code existsByMemberIdAndStatus}로 그 사실을
     * 이미 알면서 409를 던질 때만 썼는데, 이 메서드로 읽기 경로를 열어준다. 이슈 #59 1단계.
     *
     * <p>클라가 sessionId를 로컬에 저장했다 복원하는 방식은 택하지 않았다 — 재설치·기기 변경 시
     * 여전히 갇히고, 저장된 id가 이미 타임아웃으로 FAILED 처리된 낡은 값일 수 있다. 서버가
     * 진실의 출처다.
     *
     * <p><b>"진행 중"의 정의를 {@code status == IN_PROGRESS} 로 잡은 이유</b>: 이 조건이
     * {@code createSession} 이 409를 던지는 조건({@code existsByMemberIdAndStatus})과 정확히 같다.
     * 즉 이 API는 "왜 새 세션을 못 만드는가"에 그대로 답한다. 만약 endTime이 null인 것만 골라
     * 돌려주면, 종료를 눌렀지만 AI 콜백을 기다리는 동안엔 "활성 세션 없음"인데 생성은 409로 막히는
     * — 좁아졌을 뿐 똑같은 갇힘이 남는다.
     *
     * <p>대신 응답에 {@code endTime}을 실어 클라가 두 상태를 구분하게 한다: null이면 이어하기,
     * 값이 있으면 결과 처리 대기. {@code endSession}은 endTime만 기록하고 status 전환은 AI
     * 콜백(applyComplete)이 하므로 status만으로는 구분되지 않는다.
     *
     * @return 진행 중 세션이 없으면 {@code Optional.empty()} — 이는 정상 상태이며 예외가 아니다.
     */
    @Transactional(readOnly = true)
    public Optional<ActiveSessionResponseDto> getActiveSession(Long currentMemberId) {
        return sessionRepository
                .findFirstByMemberIdAndStatusOrderByStartTimeDesc(currentMemberId, Status.IN_PROGRESS)
                .map(ActiveSessionResponseDto::from);
    }

    /**
     * [재부착 전제 검증] 이 세션에 다시 붙어도 되는지 확인하고 세션을 돌려준다 (이슈 #59 2단계).
     *
     * <p>실제 재부착(gRPC 송신)은 {@code ExerciseAnalysisService.reattachSession} 이 한다 — 이 서비스는
     * gRPC 의존을 갖지 않는다(위 71행 주석: 아웃박스가 끊어놓은 순환 의존을 되살리지 않기 위함).
     *
     * <p>허용 조건 4가지. 앞의 셋은 "이 세션이 이어할 수 있는 상태인가"이고, 마지막 하나가 시간이다.
     * <ol>
     *   <li>소유자 일치 — 남의 세션에 붙는 것을 막는다. 없거나 남의 것이면 둘 다 404 로 통일해
     *       세션 id 존재 여부가 새어나가지 않게 한다({@code findByIdAndMemberId} 계약).</li>
     *   <li>{@code IN_PROGRESS} — COMPLETED/FAILED 는 이미 끝난 세션이라 이어붙일 대상이 아니다.</li>
     *   <li>{@code endTime == null} — 사용자가 이미 종료를 눌렀고 AI 결과 콜백을 기다리는 중이면
     *       status 는 아직 IN_PROGRESS 다(전환은 applyComplete 몫). 이 상태로 재부착하면 끝낸 운동을
     *       다시 시작시키는 셈이다.</li>
     *   <li>타임아웃 기준 이전 — 스케줄러가 1분마다 돌기 때문에 기준을 지나고도 아직 IN_PROGRESS 인
     *       틈이 존재한다. 상태만 믿으면 그 틈에 재부착이 성공하고 곧바로 FAILED 가 된다.
     *       스케줄러와 <b>같은 식</b>({@code Session.isTimedOutAt})을 쓴다.</li>
     * </ol>
     *
     * @throws BusinessException {@code SESSION_NOT_FOUND}(없음/남의 것/이미 끝남/종료 요청됨),
     *                           {@code SESSION_REATTACH_EXPIRED}(시간 초과)
     */
    @Transactional(readOnly = true)
    public Session findReattachableSession(Long sessionId, Long currentMemberId) {
        Session session = sessionRepository.findSessionWithExerciseByIdAndMemberId(sessionId, currentMemberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        if (session.getStatus() != Status.IN_PROGRESS || session.getEndTime() != null) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }

        if (session.isTimedOutAt(LocalDateTime.now(), defaultBufferMinutes)) {
            throw new BusinessException(ErrorCode.SESSION_REATTACH_EXPIRED);
        }

        return session;
    }

    /**
     * [세션 완료] AI 서버로부터 수신한 분석 결과를 바탕으로 세션을 최종 업데이트합니다.
     *
     * 낙관적 락 충돌 시(스케줄러가 동시에 FAILED로 변경한 경우) 재조회하여 COMPLETED로 덮어씁니다.
     * 사용자가 실제로 운동한 데이터(rep/sync rate)는 어떤 경우에도 유실되면 안 됩니다.
     *
     * @param request AI 서버(gRPC)에서 넘어온 최종 분석 데이터
     */
    public void completeSession(SessionCompleteRequest request) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                self.applyComplete(request);
                return;
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                // 동시에 스케줄러가 FAILED로 변경한 케이스. 재조회 후 COMPLETED로 덮어쓰기 위해 재시도.
                // 헤드라인 서사(스케줄러↔AI 콜백 경쟁)의 실제 발생 빈도를 볼 수 있는 유일한 지점이라
                // 지표로 남긴다 — 이긴 쪽(retry)과 3회로도 못 이긴 쪽(exhausted)을 구분해서.
                sessionMetrics.optimisticLockConflict("ai-callback", attempt == maxAttempts ? "exhausted" : "retry");
                if (attempt == maxAttempts) {
                    throw e;
                }
            }
        }
    }

    @Transactional
    public void applyComplete(SessionCompleteRequest request) {
        Session session = sessionRepository.findById(request.getSessionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        // 멱등성: FastAPI가 응답 유실로 같은 결과를 재전송한 경우(2-1, 2-2) 첫 완료 시각/기록을 보존하고 즉시 종료
        if (session.getStatus() == Status.COMPLETED) {
            return;
        }

        session.setStatus(Status.COMPLETED);
        session.setEndTime(LocalDateTime.now());

        session.setTotalReps(request.getTotalReps());
        session.setAvgSyncRate(java.math.BigDecimal.valueOf(request.getAvgSyncRate()));
        session.setCaloriesBurned(java.math.BigDecimal.valueOf(request.getCaloriesBurned()));

        sessionRepository.saveAndFlush(session);

        int exerciseMinutes = (int) java.time.Duration.between(session.getStartTime(), session.getEndTime()).toMinutes();
        dailyLogService.accumulateStats(session.getMember().getId(), session.getStartTime().toLocalDate(),
                exerciseMinutes, java.math.BigDecimal.valueOf(request.getCaloriesBurned()));

        precomputeReport(session);

        sessionMetrics.sessionTransition(Status.COMPLETED, "ai-callback");
    }

    /**
     * precompute-on-write (report-read-path.md §9) — 세션 완료 시점에 worst 구간을 1회 계산해
     * reports에 저장. GET /reports/sessions/{id} 조회 때마다 pose_data를 재계산하던 것을 제거하는
     * 게 목적(db-deep-dive.md §B-3). applyComplete와 같은 트랜잭션(§9-2)이라 여기서 예외가 나면
     * 세션 완료 자체가 롤백된다(§9-3) — completeSession의 낙관적 락 재시도, AI 콜백 재전송이 그대로
     * 재시도 경로가 됨. pose_data가 아직 없는 경우는 WorstSectionCalculator가 null을 돌려주는
     * 정상 케이스라 예외가 아니다(§9-3에서 실패로 분류하지 않기로 함).
     */
    private void precomputeReport(Session session) {
        List<PoseFrameProjection> poseFrames = poseDataRepository.findFramesBySessionId(session.getId());
        WorstSectionDto worstSection = worstSectionCalculator.calculate(session, poseFrames);

        Report report = new Report();
        report.setMember(session.getMember());
        report.setSession(session);
        report.setReportType(ReportType.SESSION);
        if (worstSection != null) {
            try {
                report.setDetailedAnalysis(objectMapper.writeValueAsString(worstSection));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("worst 구간 직렬화 실패 - 세션 " + session.getId(), e);
            }
        }
        reportRepository.save(report);
    }

    /**
     * [세션 종료 — 분기 2.A.ET ET-H, 단일 endpoint 분배자 패턴]
     * 클라가 "운동 종료" 버튼 → endTime 기록 + 같은 트랜잭션에 AI 통보를 아웃박스 행으로 적재.
     *
     * - endTime 만 즉시 기록. 통계 갱신(totalReps/avgSync) 은 AI 의 CompleteAnalysis 콜백이 별도 처리
     * - AI 로의 gRPC 는 이 경로에서 <b>일어나지 않는다</b>. OutboxPublisher 가 행을 집어 송신하므로
     *   요청 스레드는 외부 호출을 기다리지 않고, 송신이 실패해도 행이 남아 재시도된다
     * - 본인 세션이 아니면 ACCESS_DENIED, 이미 종료된 세션이면 멱등 (변경 없음, 200 OK)
     * - 통보가 끝내 전달되지 못하면: SessionTimeoutScheduler 가 여전히 safety net (IN_PROGRESS → FAILED)
     */
    @Transactional
    public void endSession(Long sessionId, Long currentMemberId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        if (!session.getMember().getId().equals(currentMemberId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        // 멱등: 이미 endTime 기록된 세션은 변경 없음 (AI 재호출도 안 함)
        if (session.getEndTime() != null) {
            return;
        }

        session.setEndTime(LocalDateTime.now());
        sessionRepository.saveAndFlush(session);

        // AI 통보를 "지금 gRPC"가 아니라 "같은 트랜잭션 안 아웃박스 행 INSERT"로 바꾼다.
        // 둘 다 MySQL 이므로 세션 변경과 통보 기록이 원자적으로 커밋되고, 전달 책임은
        // OutboxPublisher 가 진다(at-least-once). 이전 afterCommit 방식은 커밋 뒤 gRPC 가
        // 실패하면(오류·데드라인·서킷 OPEN) 복구 수단이 없었다(at-most-once).
        //
        // cid 를 행에 실어야 한다 — 발행기는 @Scheduled 스레드라 MDC 가 비어 있고, 아웃박스는
        // 스레드가 아니라 시간·프로세스 경계를 넘으므로 런타임 캡처로는 원리상 이을 수 없다.
        outboxRepository.save(OutboxEvent.stopAnalysis(sessionId, CorrelationIds.current()));
    }

    /**
     * [개별 세션 삭제] 세션 1건만 지운다 (pose-data-partition-fk-tradeoff.md §5-1).
     *
     * - IN_PROGRESS는 삭제 불가(SESSION_DELETE_NOT_ALLOWED) — AI가 아직 분석 중일 수 있어, 먼저
     *   종료(또는 타임아웃)된 뒤에만 삭제 가능.
     * - pose_data는 파티셔닝 때문에 FK(CASCADE)가 없어 명시적으로 먼저 지움. reports·
     *   session_feedback_logs는 exercise_sessions FK가 ON DELETE CASCADE라 세션 삭제로 자동 정리.
     * - 세션 1건(~750행) 규모라 동기 삭제로 충분 — 회원 탈퇴(대량, PoseDataCleanupService 비동기)와
     *   달리 별도 배치/비동기 불필요.
     */
    @Transactional
    public void deleteSession(Long sessionId, Long currentMemberId) {
        Session session = sessionRepository.findByIdAndMemberId(sessionId, currentMemberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        if (session.getStatus() == Status.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.SESSION_DELETE_NOT_ALLOWED);
        }

        poseDataRepository.deleteBySessionIdIn(List.of(session.getId()));
        sessionRepository.delete(session);
        sessionRepository.flush();
    }

    /**
     * [타임아웃 처리] 세션이 아직 IN_PROGRESS 상태이면 FAILED로 변경합니다.
     *
     * 스케줄러 호출용. 별도 트랜잭션으로 실행되어 한 세션의 충돌이 다른 세션 처리에 영향을 주지 않습니다.
     * FastAPI 완료 콜백과 동시 진행 시 OptimisticLockingFailure가 발생할 수 있으며,
     * 이때는 호출 측이 catch하고 양보합니다(FastAPI 결과 우선).
     *
     * @return FAILED로 전환되었으면 true, 이미 다른 상태이거나 세션이 없으면 false
     */
    @Transactional
    public boolean markAsFailedIfStillInProgress(Long sessionId, LocalDateTime endTime) {
        Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || session.getStatus() != Status.IN_PROGRESS) {
            return false;
        }
        session.setStatus(Status.FAILED);
        session.setEndTime(endTime);
        sessionRepository.saveAndFlush(session);
        return true;
    }

    @Transactional(readOnly = true)
    public WeeklyActivityResponseDto getWeeklyActivity(Long memberId) {

        // 1. 이번 주 시작일(월)과 종료일(일) 계산
        LocalDate today = LocalDate.now();
        LocalDate startOfWeek = today.with(java.time.DayOfWeek.MONDAY);
        LocalDate endOfWeek = today.with(java.time.DayOfWeek.SUNDAY);

        // 2. 이번 주 모든 세션 조회 — exercise fetch join으로 N+1 방지
        List<Session> weeklySessions = sessionRepository.findWeeklySessionsWithExercise(
                memberId, startOfWeek.atStartOfDay(), endOfWeek.atTime(23, 59, 59));

        // 3. 통계 계산 (Duration 계산 시 NPE 방어)
        int totalMinutes = weeklySessions.stream()
                .mapToInt(s -> {
                    if (s.getStartTime() == null || s.getEndTime() == null) return 0;
                    return (int) java.time.Duration.between(s.getStartTime(), s.getEndTime()).toMinutes();
                })
                .sum();

        // BigDecimal -> Double 변환 최적화
        double totalCalories = weeklySessions.stream()
                .map(s -> s.getCaloriesBurned() != null ? s.getCaloriesBurned() : java.math.BigDecimal.ZERO)
                .mapToDouble(java.math.BigDecimal::doubleValue)
                .sum();

        // 4. 요일별 그래프 데이터 가공
        List<DailyLogSummaryDto> dailyLogs = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate date = startOfWeek.plusDays(i);
            int dailyMins = weeklySessions.stream()
                    .filter(s -> s.getStartTime() != null && s.getStartTime().toLocalDate().equals(date))
                    .mapToInt(this::calculateDuration) // endTime == null(진행중 세션) NPE 방어 — totalMinutes 블록과 동일 가드
                    .sum();

            dailyLogs.add(new DailyLogSummaryDto(
                    date.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN),
                    dailyMins,
                    date.equals(today)
            ));
        }

        List<ExerciseSessionDto> todayDetails = weeklySessions.stream()
                .filter(s -> s.getStartTime() != null && s.getStartTime().toLocalDate().equals(today))
                .map(this::toSessionDto)
                .collect(Collectors.toList());

        return WeeklyActivityResponseDto.builder()
                .dateRange(String.format("%d월 %d일 - %d일",
                        startOfWeek.getMonthValue(), startOfWeek.getDayOfMonth(), endOfWeek.getDayOfMonth()))
                .totalWorkouts(weeklySessions.size())
                .totalMinutes(totalMinutes)
                .totalCalories((int) totalCalories)
                .dailyLogs(dailyLogs)
                .todayDetails(todayDetails)
                .build();
    }

    @Transactional(readOnly = true)
    public CalendarMainResponseDto getCalendarMain(Long memberId, int year, int month) {
        // 1. 해당 월의 모든 세션 조회
        LocalDate startOfMonth = LocalDate.of(year, month, 1);
        LocalDate endOfMonth = startOfMonth.with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());

        List<Session> monthlySessions = sessionRepository.findByMemberIdAndStartTimeBetween(
                memberId, startOfMonth.atStartOfDay(), endOfMonth.atTime(23, 59, 59));

        // 2. 상단 카드 데이터 계산 (평균 싱크로율)
        // avg_sync_rate 는 nullable — 분석 전/실패 세션은 값이 없다. 예전엔 null 을 0.0 으로
        // 치환해 평균에 넣었는데, 그러면 "측정 안 됨"이 "싱크로율 0%"로 집계돼 사용자에게
        // 보이는 평균이 실제보다 낮아진다. 값이 있는 세션만으로 평균을 낸다.
        double avgSyncRate = monthlySessions.stream()
                .map(Session::getAvgSyncRate)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(java.math.BigDecimal::doubleValue)
                .average()
                .orElse(0.0);

        // 3. 달력 날짜별 기록 여부 표시
        List<CalendarDayDto> dayDtos = monthlySessions.stream()
                .map(s -> s.getStartTime().toLocalDate())
                .distinct()
                .map(date -> {
                    CalendarDayDto dto = new CalendarDayDto();
                    dto.setDate(date.toString());
                    dto.setHasRecord(true);

                    // 월 평균과 같은 이유로 값 없는 세션은 평균에서 제외한다(0점으로 치지 않음)
                    double dailyAvg = monthlySessions.stream()
                            .filter(s -> s.getStartTime().toLocalDate().equals(date))
                            .map(Session::getAvgSyncRate)
                            .filter(java.util.Objects::nonNull)
                            .mapToDouble(java.math.BigDecimal::doubleValue)
                            .average()
                            .orElse(0.0);

                    dto.setDailyAvgSyncRate(dailyAvg);

                    return dto;
                })
                .collect(Collectors.toList());

        CalendarMainResponseDto response = new CalendarMainResponseDto();
        response.setMonthlyExerciseDays((int) monthlySessions.stream().map(s -> s.getStartTime().toLocalDate()).distinct().count());
        response.setTotalAvgSyncRate((int) avgSyncRate);
        response.setConsecutiveDays(calculateConsecutiveDays(memberId)); // 연속 일수 계산 유틸 호출

        response.setYear(year);   // 파라미터로 받은 year 세팅
        response.setMonth(month); // 파라미터로 받은 month 세팅

        response.setRecords(dayDtos);

        return response;
    }

    // 달력에서 특정 날짜 클릭 시 그 날의 운동 목록 조회.
    // 주간 요약의 todayDetails 와 동일한 매핑(toSessionDto)을 재사용 — 오늘/과거 날짜 구분 없이 일관.
    @Transactional(readOnly = true)
    public DailyActivityResponseDto getDailyActivity(Long memberId, LocalDate date) {
        List<Session> sessions = sessionRepository.findByMemberIdAndStartTimeBetween(
                memberId, date.atStartOfDay(), date.atTime(23, 59, 59));

        List<ExerciseSessionDto> details = sessions.stream()
                .filter(s -> s.getStartTime() != null)
                .map(this::toSessionDto)
                .collect(Collectors.toList());

        return DailyActivityResponseDto.builder()
                .date(date.toString())
                .totalWorkouts(details.size())
                .sessions(details)
                .build();
    }

    // Session → ExerciseSessionDto 공용 매핑 (주간 todayDetails / 일별 조회 공유)
    private ExerciseSessionDto toSessionDto(Session s) {
        ExerciseSessionDto detail = new ExerciseSessionDto();
        detail.setSessionId(s.getId());
        detail.setExerciseName(s.getExercise().getName());
        // 세트 표기는 SetSummaryFormatter 한 곳에서만 만든다 — 과거 여기만 "0세트"로 어긋나
        // 같은 세션이 화면마다 0/1세트로 다르게 보였음(#69).
        detail.setSetSummary(SetSummaryFormatter.format(s.getTotalReps()));
        detail.setSyncRate(s.getAvgSyncRate() != null ? s.getAvgSyncRate().doubleValue() : 0.0);
        return detail;
    }

    private int calculateDuration(Session session) {
        if (session.getStartTime() == null || session.getEndTime() == null) {
            return 0;
        }
        return (int) java.time.Duration.between(session.getStartTime(), session.getEndTime()).toMinutes();
    }

    private int calculateConsecutiveDays(Long memberId) {
        LocalDate today = LocalDate.now();

        // 최근 100일치 활동 날짜를 한 번에 조회 (루프 N+1 → 쿼리 1방)
        Set<LocalDate> activeDates = sessionRepository.findDistinctActiveDates(
                        memberId,
                        today.minusDays(100).atStartOfDay(),
                        today.atTime(23, 59, 59)
                ).stream()
                .map(java.sql.Date::toLocalDate)
                .collect(Collectors.toSet());

        // 오늘 기록 없으면 어제부터 체크 (오늘 아직 안 했을 수도 있으니)
        LocalDate checkDate = activeDates.contains(today) ? today : today.minusDays(1);

        int consecutiveDays = 0;
        while (activeDates.contains(checkDate)) {
            consecutiveDays++;
            checkDate = checkDate.minusDays(1);
        }
        return consecutiveDays;
    }
}