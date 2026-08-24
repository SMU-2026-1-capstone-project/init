package com.shadowfit.service.exercise;

import com.shadowfit.dto.exercises.VideoRequestDto;
import com.shadowfit.global.observability.CallCancellation;
import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.dto.report.detailreport.RepSyncRateDto;
import com.shadowfit.dto.report.detailreport.SessionDetailedAnalysis;
import com.shadowfit.dto.report.detailreport.WorstSectionDto;
import com.shadowfit.dto.report.detailreport.ExerciseSessionDto;
import com.shadowfit.dto.report.record.CalendarDayDto;
import com.shadowfit.dto.report.record.CalendarMainResponseDto;
import com.shadowfit.dto.report.record.DailyActivityResponseDto;
import com.shadowfit.dto.report.record.DailyLogSummaryDto;
import com.shadowfit.dto.exercises.session.ActiveSessionResponseDto;
import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.global.observability.CorrelationIds;
import com.shadowfit.global.observability.SessionMetrics;
import com.shadowfit.global.security.SessionNonceGenerator;
import com.shadowfit.model.outbox.OutboxEvent;
import com.shadowfit.repository.outbox.OutboxEventRepository;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.member.MemberRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.shadowfit.grpc.SessionStatus;
import com.shadowfit.grpc.SessionCompleteRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


//공통세션
@Service
@RequiredArgsConstructor
public class SessionService {
    private final SessionRepository sessionRepository;
    private final ExercisesRepository exercisesRepository;
    private final MemberRepository memberRepository;
    private final PoseDataRepository poseDataRepository;
    private final SessionMetrics sessionMetrics;
    private final OutboxEventRepository outboxRepository;
    private final SessionNonceGenerator sessionNonceGenerator;
    // completeSession의 실제 반영(@Transactional)을 갖는 별도 빈 — self 주입 대신 이걸 부른다
    // (이슈 #175). 자기호출은 AOP 프록시를 우회해 @Transactional이 조용히 무시되는데, 다른 빈으로
    // 옮기면 이 클래스가 자기 프록시를 알아야 할 이유 자체가 없어진다.
    private final SessionCompletionTx sessionCompletionTx;

    // ExerciseAnalysisService 주입은 제거됐다. endSession 이 gRPC 를 직접 부르지 않고 아웃박스 행만
    // 남기게 되면서 유일한 사용처가 사라졌고, 그 결과 SessionService ↔ ExerciseAnalysisService
    // 순환 의존(@Lazy 로 우회하던)도 함께 없어졌다. 아웃박스가 두 서비스 사이의 결합을 끊은 셈.

    // 재부착 허용 시간 상한 — SessionTimeoutScheduler 와 같은 프로퍼티를 읽는다. 상한을 별도 상수로
    // 두면 두 값이 어긋날 때 "재부착은 됐는데 곧 걷혀가는" 세션이 생긴다(findReattachableSession).
    // @RequiredArgsConstructor 라 생성자 파라미터로는 못 넣어 필드 주입을 쓴다.
    @Value("${exercise.session.timeout.idle-minutes:10}")
    private Integer idleMinutes;

    @Value("${exercise.session.timeout.default-buffer-minutes:30}")
    private Integer defaultBufferMinutes;

    // ExerciseAnalysisService 와 같은 프로퍼티를 읽는다(중복이지만 위 순환 의존 회피 원칙과
    // 같은 이유 — SessionService 는 gRPC/AI 서비스 의존을 갖지 않는다). 값이 sessionId 에만
    // 딸린 순수 함수라 두 곳에서 같은 값을 읽어도 어긋날 일이 없다(getActiveSession).
    @Value("${ai.channel-pool-size:3}")
    private int aiChannelPoolSize;

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

        // 소유권 비밀값은 **여기서, 이 트랜잭션 안에서** 만든다 (#187 안 (d)).
        //
        // 세션을 AI 에 알리는 StartAnalysis 는 afterCommit 에서 비동기로 나가고
        // (ExerciseAnalysisService 의 registerSynchronization), REST 응답도 커밋 뒤에 나간다.
        // 즉 커밋된 이 값 하나를 두 경로가 나중에 같이 읽는 모양이라야 세 곳(클라·AI·DB)이
        // 어긋나지 않는다. 나중 단계에서 만들면 «클라가 받은 값» 과 «AI 가 받은 값» 이 갈릴 수 있다.
        // 🔴 startTime 의 «초 이하» 를 여기서 버린다 (#392).
        //
        // 이 값은 세션 시작 시각이면서 동시에 **pose_data 의 멱등 앵커**다 — PoseDataService 가
        // 이 값을 그대로 created_at 에 넣고(#188), 리포트·재부착 조회는 그 값으로 파티션을
        // 특정한다(PoseDataRepository 클래스 주석). 즉 «메모리의 값» 과 «저장된 값» 이 정확히
        // 같아야 조회가 성립한다.
        //
        // 그런데 두 컬럼 모두 MySQL 정밀도 0 이라(V1: start_time DATETIME · created_at TIMESTAMP)
        // 초 이하가 **버림이 아니라 반올림**으로 사라진다. 안 자르면 두 가지가 어긋난다:
        //   ① 응답 body 는 14:32:04.7 을 «04» 로 보내는데 DB 에는 «05» 가 박힌다
        //   ② 아직 DB 를 안 거친 엔티티로 조회하면 나노초가 남아 등호가 안 맞고 **조용히 0행**이 된다
        // 자르면 둘 다 사라진다 — 이건 정밀도를 «버리는» 게 아니라, 저장소가 이미 버리고 있던
        // 것을 애플리케이션이 **명시**하는 것이다.
        Session session = Session.builder()
                .member(member)
                .exercise(exercise)
                .referenceSource(finalUrl)
                .startTime(LocalDateTime.now().withNano(0))
                .status(Status.IN_PROGRESS)
                .sessionNonce(sessionNonceGenerator.generate())
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
                .map(session -> ActiveSessionResponseDto.from(
                        session, Math.floorMod(session.getId(), aiChannelPoolSize)));
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
        assertReattachable(session);
        return session;
    }

    /**
     * {@link #findReattachableSession} 의 시스템 트리거 버전 — 서킷브레이커 OPEN 자동 복구
     * (ai-channel-pool-hardening.md §3-1 ㄴ) 전용. 회원 소유권 조건이 없다: 호출자가 사용자
     * 요청이 아니라 아웃박스 발행기이므로 대조할 {@code currentMemberId} 자체가 없다 —
     * {@code sessionRepository.findIdsByStatus(IN_PROGRESS)} 로 서버가 직접 뽑은 id 만 여기로
     * 들어온다(클라이언트 입력 아님).
     *
     * <p>검증 로직({@link #assertReattachable})은 그대로 공유한다 — "이어붙일 수 있는 상태인가"의
     * 정의는 트리거 주체와 무관하다.
     *
     * @throws BusinessException {@code SESSION_NOT_FOUND}(없음/이미 끝남/종료 요청됨),
     *                           {@code SESSION_REATTACH_EXPIRED}(시간 초과) — 둘 다
     *                           호출부(ExerciseAnalysisService.reattachFromOutbox)에서
     *                           "재시도해도 소용없다"(TERMINAL_FAILED)로 해석된다.
     */
    @Transactional(readOnly = true)
    public Session findReattachableSessionById(Long sessionId) {
        Session session = sessionRepository.findSessionWithExerciseById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        assertReattachable(session);
        return session;
    }

    /** {@link #findReattachableSession} 이 쓰는 검증. */
    private void assertReattachable(Session session) {
        if (session.getStatus() != Status.IN_PROGRESS || session.getEndTime() != null) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }

        if (session.isTimedOutAt(LocalDateTime.now(), idleMinutes, defaultBufferMinutes)) {
            throw new BusinessException(ErrorCode.SESSION_REATTACH_EXPIRED);
        }
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
        // 상태 전이 + 리포트 선계산을 시작하기 직전이다. 여기서부터가 되돌릴 수 없는 쓰기라
        // 호출자가 이미 포기했으면 시작하지 않는다 (#206 결함 B). gRPC 밖 호출(앱 콜백 경로 등)
        // 에서는 이 검사가 아무 일도 하지 않는다.
        //
        // ⚠️ 아래 낙관적 락 재시도 루프 «밖» 이다. 안에 넣으면 재시도마다 다시 보게 되는데,
        //    한 번 시작한 전이는 끝내는 편이 맞다 — 중간에 그만두면 세션이 어중간한 상태로 남고,
        //    그건 이 검사가 아끼려던 자원보다 비싸다.
        CallCancellation.abortIfAbandoned("세션 종료 (session=" + request.getSessionId() + ")");

        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                sessionCompletionTx.applyComplete(request);
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
        if (!session.markEnded(LocalDateTime.now())) {
            return;
        }
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
     * [타임아웃 처리] 세션이 아직 IN_PROGRESS 상태이면 FAILED로 변경합니다. <b>AI 통보는 하지 않는다.</b>
     *
     * 별도 트랜잭션으로 실행되어 한 세션의 충돌이 다른 세션 처리에 영향을 주지 않습니다.
     * FastAPI 완료 콜백과 동시 진행 시 OptimisticLockingFailure가 발생할 수 있으며,
     * 이때는 호출 측이 catch하고 양보합니다(FastAPI 결과 우선).
     *
     * <p>AI 에 상태가 남아 있을 수 있는 경로라면 {@link #markAsFailedIfStillInProgress(Long,
     * LocalDateTime, boolean)} 에 {@code true} 를 넘겨야 한다 — 판단 근거는 그쪽 주석에 있다.
     *
     * @return FAILED로 전환되었으면 true, 이미 다른 상태이거나 세션이 없으면 false
     */
    @Transactional
    public boolean markAsFailedIfStillInProgress(Long sessionId, LocalDateTime endTime) {
        return failIfInProgress(sessionId, endTime, false);
    }

    /**
     * [타임아웃 처리 + AI 통보] 위와 같되, 전환에 성공하면 AI 중단 통보를 <b>같은 트랜잭션에</b>
     * 아웃박스 행으로 적재한다 (이슈 #98).
     *
     * <p><b>왜 필요한가</b> — 지금까지 세션을 FAILED 로 걷어갈 때 AI 에는 아무 말도 하지 않았다.
     * 그러면 두 가지가 남는다: (1) AI 메모리의 {@code SessionState} 가 프로세스 재시작까지 안 지워지고
     * (제거하는 곳이 {@code StopAnalysis} 핸들러 하나뿐이다), (2) {@code CompleteAnalysis} 콜백이
     * 오지 않아 {@code pose_data} 에 rep 이 쌓여 있는데도 리포트가 만들어지지 않는다.
     *
     * <p><b>사용자가 종료를 눌러도 못 고친다</b> — {@code endSession} 의 멱등 가드가 {@code endTime
     * != null} 인데 그 {@code endTime} 을 이 메서드가 이미 채워놨기 때문에 조기 return 하고,
     * {@code OutboxEvent.stopAnalysis} 를 만드는 유일한 지점을 지나쳐 버린다. 그래서 "지연"이 아니라
     * "영영"이었다.
     *
     * <p><b>왜 같은 트랜잭션인가</b> — 상태 전환과 통보 적재가 갈라지면 이 이슈가 지적한 문제를
     * 형태만 바꿔 되풀이한다("FAILED 인데 통보는 없는" 상태). 둘 다 MySQL 이라 원자적으로 커밋된다.
     * 낙관적 락 충돌로 롤백되면 아웃박스 행도 같이 사라지는데, 그게 옳다 — 충돌은 AI 완료 콜백이
     * 이겼다는 뜻이고 그 경우 AI 는 이미 자기 상태를 지웠으므로 통보할 대상이 없다.
     *
     * <p><b>세션이 되살아난다</b> — {@code StopAnalysis} 는 "그만해"가 아니라 "그만하고 결과 보내"다.
     * AI 가 {@code CompleteAnalysis} 로 답하고, {@code applyComplete} 의 멱등 가드는 COMPLETED 만
     * 걸러내므로 FAILED 가 COMPLETED 로 덮인다. 의도한 것이다 — 그 덮어쓰기는 실수가 아니라
     * "사용자가 실제로 운동한 데이터는 어떤 경우에도 유실되면 안 된다"는 기존 방침이고(위 completeSession
     * 주석), 통보가 안 가서 그 방침이 <b>닿지 못하던 경로에 닿게</b> 하는 것이 이 변경이다.
     *
     * <p><b>AI 가 그 세션을 모르면</b> {@code success=false} 인 정상 응답이 오고, 그건
     * {@code ExerciseAnalysisService.stopAnalysis} 에서 {@code TERMINAL_FAILED} 로 분류돼 재시도 없이
     * 한 번에 끝난다. 세션은 FAILED 로 남는다. 헛방이어도 폭주하지 않는다.
     *
     * <p><b>왜 모든 호출처가 쓰지 않는가</b> — AI 에 상태가 있을 수 있는 경로만 통보해야 한다.
     * 넷 중 둘뿐이다:
     * <ul>
     *   <li>✅ {@code SessionTimeoutScheduler} — 이 이슈의 대상</li>
     *   <li>✅ {@code ExerciseAnalysisService} 의 StartAnalysis {@code onError} — gRPC 에러는 "실패"가
     *       아니라 "모름"이다. AI 가 요청을 받아 상태를 만들고 응답만 못 돌아왔을 수 있어, 안 보내면
     *       같은 누수가 남는다</li>
     *   <li>❌ 서킷 OPEN — StartAnalysis 를 아예 안 보냈다. 게다가 같은 서킷에 막혀 통보도 도달 못 한다</li>
     *   <li>❌ {@code failSessionFast} — 방금 보낸 StopAnalysis 가 실패해서 걷어내는 중이다. 여기서
     *       통보하면 자기가 자기를 부른다. 무한루프는 아니지만(두 번째엔 이미 FAILED 라 false)
     *       쓸모없는 gRPC 왕복이 한 번 더 생긴다</li>
     * </ul>
     *
     * @param notifyAi 전환 성공 시 AI 중단 통보를 적재할지
     */
    @Transactional
    public boolean markAsFailedIfStillInProgress(Long sessionId, LocalDateTime endTime, boolean notifyAi) {
        return failIfInProgress(sessionId, endTime, notifyAi);
    }

    /**
     * 두 오버로드의 실제 구현. {@code private} 이라 자기호출이 프록시를 우회하는 문제가 없다 —
     * 트랜잭션 경계는 위 두 public 메서드가 갖는다.
     */
    private boolean failIfInProgress(Long sessionId, LocalDateTime endTime, boolean notifyAi) {
        Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return false;
        }
        // 덮어쓰기 전에 읽어둔다 — 아래 통보 중복 판정의 근거다.
        boolean hadEndTime = session.getEndTime() != null;

        // IN_PROGRESS 가 아니면 false — 그 가드는 Session.fail 안에 있다.
        if (!session.fail(endTime)) {
            return false;
        }
        sessionRepository.saveAndFlush(session);

        // 여기서 endTime 이 이미 있었다면 그건 endSession 이 남긴 것이다 — 이 메서드가 남긴
        // 것이라면 status 가 FAILED 라 위 가드에서 이미 빠져나갔다. 그리고 endSession 은
        // endTime 을 찍을 때 통보 행도 함께 남긴다(:379). 즉 통보는 이미 적재돼 있다.
        //
        // endSession 이 status 는 IN_PROGRESS 로 두기 때문에(전환은 applyComplete 몫) 이 경로가
        // 나중에 같은 세션을 다시 집을 수 있다 — 사용자가 종료했는데 AI 결과가 끝내 안 와서
        // 스케줄러가 걷어가는 경우다. 그때 또 적재하면 같은 세션에 통보가 두 건이 된다.
        boolean alreadyNotified = hadEndTime;

        if (notifyAi && !alreadyNotified) {
            // endSession 과 같은 형태 — cid 를 행에 실어야 발행기(다른 스레드·다른 시각)에서
            // 이 전환과 통보가 하나의 흐름으로 이어진다.
            outboxRepository.save(OutboxEvent.stopAnalysis(sessionId, CorrelationIds.current()));
        }
        return true;
    }

}