package com.shadowfit.service.Exercise;

import com.shadowfit.global.error.BusinessException;
import com.shadowfit.global.error.ErrorCode;
import com.shadowfit.grpc.PoseDataRequest;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.UserRole;
import com.shadowfit.repository.exercise.ExercisesRepository;
import com.shadowfit.repository.exercise.PoseDataRepository;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 세션 재부착 검증 (이슈 #59 2단계).
 *
 * <p>여기서 보는 것은 <b>재부착 허용 판정과 rep 카운트 복원</b>이다 — gRPC 송신
 * ({@code ExerciseAnalysisService.reattachSession})은 AI 서버가 필요해 이 테스트의 범위가 아니다.
 * AI 쪽 멱등 가드는 ai-server {@code tests/test_session_reattach.py}.
 *
 * <p>핵심 회귀 대상은 "타임아웃 기준을 지났는데 아직 IN_PROGRESS 인 틈"이다. 스케줄러가 1분마다
 * 돌기 때문에 그 틈이 실제로 존재하고, 상태만 보고 재부착을 허용하면 곧바로 FAILED 가 된다.
 */
@SpringBootTest
@Transactional
@DisplayName("세션 재부착 테스트")
class SessionReattachTest {

    @Autowired private SessionService sessionService;
    @Autowired private PoseDataService poseDataService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private PoseDataRepository poseDataRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;

    // 재부착 판정이 실제로 읽는 값과 같은 프로퍼티를 테스트도 읽는다 — 기본값을 상수로 박아두면
    // 설정을 바꿨을 때 테스트만 조용히 옛 기준으로 통과한다.
    @Value("${exercise.session.timeout.default-buffer-minutes:30}")
    private int bufferMinutes;

    private static final int EXPECTED_DURATION_MINUTES = 15;

    private Member owner;
    private Member stranger;
    private Exercise exercise;

    @BeforeEach
    void setUp() {
        owner = memberRepository.saveAndFlush(Member.builder()
                .email("reattach-owner@test.com").username("owner").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        stranger = memberRepository.saveAndFlush(Member.builder()
                .email("reattach-stranger@test.com").username("stranger").password("dummy")
                .selectedPersona(SelectedPersona.BEGINNER).role(UserRole.USER).build());
        exercise = exercisesRepository.saveAndFlush(Exercise.builder()
                .name("스쿼트").category(ExerciseCategory.LOWER)
                .expectedDurationMinutes(EXPECTED_DURATION_MINUTES)
                .syncThresholdBeginner(new BigDecimal("60.00"))
                .syncThresholdAdvanced(new BigDecimal("85.00"))
                .analysisSupported(true)
                .build());
    }

    private Session session(Member member, LocalDateTime startTime, Status status, LocalDateTime endTime) {
        return sessionRepository.saveAndFlush(Session.builder()
                .member(member).exercise(exercise)
                .startTime(startTime).status(status).endTime(endTime)
                .totalReps(0).difficultyLevel(1).build());
    }

    private Session inProgressSession() {
        return session(owner, LocalDateTime.now(), Status.IN_PROGRESS, null);
    }

    private PoseDataRequest frame(int repNumber, double timestampSec, double syncRate) {
        return PoseDataRequest.newBuilder()
                .setRepNumber(repNumber)
                .setTimestampSec(timestampSec)
                .setJointCoordinates("{}")
                .setSyncRate(syncRate)
                .setFeedbackMessage("ok")
                .build();
    }

    @Nested
    @DisplayName("재부착 허용 판정")
    class Eligibility {

        @Test
        @DisplayName("진행 중인 본인 세션은 재부착할 수 있다")
        void 진행중_본인세션_허용() {
            Session s = inProgressSession();

            Session found = sessionService.findReattachableSession(s.getId(), owner.getId());

            assertThat(found.getId()).isEqualTo(s.getId());
        }

        @Test
        @DisplayName("남의 세션은 404 — 존재 여부를 노출하지 않는다")
        void 남의세션_404() {
            Session s = inProgressSession();

            assertThatThrownBy(() -> sessionService.findReattachableSession(s.getId(), stranger.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SESSION_NOT_FOUND);
        }

        @Test
        @DisplayName("없는 세션도 같은 404 — 남의 세션과 구분되지 않아야 한다")
        void 없는세션_404() {
            assertThatThrownBy(() -> sessionService.findReattachableSession(999_999L, owner.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SESSION_NOT_FOUND);
        }

        @Test
        @DisplayName("이미 끝난 세션은 이어붙일 대상이 아니다")
        void 완료된세션_404() {
            Session s = session(owner, LocalDateTime.now().minusHours(2), Status.COMPLETED, LocalDateTime.now());

            assertThatThrownBy(() -> sessionService.findReattachableSession(s.getId(), owner.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SESSION_NOT_FOUND);
        }

        @Test
        @DisplayName("종료 요청된 세션은 status 가 아직 IN_PROGRESS 여도 재부착 대상이 아니다")
        void 종료요청된세션_404() {
            // endSession 은 endTime 만 기록하고 COMPLETED 전환은 AI 콜백 몫이라 이 상태가 실재한다.
            // status 만 보면 통과해버려서 "이미 끝낸 운동을 다시 시작"시키게 된다.
            Session s = session(owner, LocalDateTime.now(), Status.IN_PROGRESS, LocalDateTime.now());

            assertThatThrownBy(() -> sessionService.findReattachableSession(s.getId(), owner.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SESSION_NOT_FOUND);
        }

        @Test
        @DisplayName("타임아웃 기준을 지났으면 IN_PROGRESS 여도 410")
        void 타임아웃경과_410() {
            // 스케줄러가 아직 안 걷어간 틈을 재현한다 — 상태는 IN_PROGRESS 지만 기준은 지났다.
            LocalDateTime longAgo = LocalDateTime.now()
                    .minusMinutes(EXPECTED_DURATION_MINUTES + bufferMinutes + 1);
            Session s = session(owner, longAgo, Status.IN_PROGRESS, null);

            assertThatThrownBy(() -> sessionService.findReattachableSession(s.getId(), owner.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SESSION_REATTACH_EXPIRED);
        }

        @Test
        @DisplayName("타임아웃 기준 직전이면 아직 허용된다 — 버퍼 안이면 이어할 수 있다")
        void 타임아웃직전_허용() {
            LocalDateTime justInside = LocalDateTime.now()
                    .minusMinutes(EXPECTED_DURATION_MINUTES + bufferMinutes - 1);
            Session s = session(owner, justInside, Status.IN_PROGRESS, null);

            assertThat(sessionService.findReattachableSession(s.getId(), owner.getId()).getId())
                    .isEqualTo(s.getId());
        }

        @Test
        @DisplayName("허용 판정이 스케줄러와 같은 식을 쓴다")
        void 스케줄러와_같은_식() {
            // 두 기준이 어긋나면 "재부착은 됐는데 곧 FAILED" 창이 생긴다. 식을 Session 이 갖는 이유.
            Session s = inProgressSession();
            LocalDateTime threshold = s.timeoutThreshold(bufferMinutes);

            assertThat(threshold).isEqualTo(
                    s.getStartTime().plusMinutes(EXPECTED_DURATION_MINUTES).plusMinutes(bufferMinutes));
            assertThat(s.isTimedOutAt(threshold.minusSeconds(1), bufferMinutes)).isFalse();
            assertThat(s.isTimedOutAt(threshold.plusSeconds(1), bufferMinutes)).isTrue();
        }
    }

    @Nested
    @DisplayName("rep 카운트 복원")
    class RepCountRestore {

        @Test
        @DisplayName("pose_data 가 없으면 0 — null 이 아니라 0 이어야 호출부가 분기하지 않는다")
        void 프레임없음_0() {
            Session s = inProgressSession();

            assertThat(poseDataRepository.findMaxRepNumberBySessionId(s.getId())).isZero();
        }

        @Test
        @DisplayName("저장된 rep 중 가장 큰 번호를 복원한다")
        void 최대_rep번호_복원() {
            Session s = inProgressSession();
            // 다운샘플(window 5)이 걸려도 rep 당 최소 1행은 남는다 — rep 개수 세기에는 영향이 적다.
            poseDataService.savePoseDataBatch(s.getId(), List.of(
                    frame(1, 0.1, 70.0), frame(1, 0.2, 71.0)));
            poseDataService.savePoseDataBatch(s.getId(), List.of(
                    frame(2, 1.1, 80.0), frame(2, 1.2, 81.0)));
            poseDataService.savePoseDataBatch(s.getId(), List.of(
                    frame(3, 2.1, 90.0)));

            assertThat(poseDataRepository.findMaxRepNumberBySessionId(s.getId())).isEqualTo(3);
        }

        @Test
        @DisplayName("rep_number 를 안 보내는 구버전 AI 도 깨지지 않는다 — 0 으로 들어간다")
        void 구버전AI_0() {
            // proto3 라 미전송 필드는 0 이고 컬럼 DEFAULT 도 0 이다. 배포 순서를 맞추지 않아도 된다.
            Session s = inProgressSession();
            poseDataService.savePoseDataBatch(s.getId(), List.of(
                    PoseDataRequest.newBuilder()
                            .setTimestampSec(0.1).setJointCoordinates("{}")
                            .setSyncRate(70.0).setFeedbackMessage("ok").build()));

            assertThat(poseDataRepository.findMaxRepNumberBySessionId(s.getId())).isZero();
        }

        @Test
        @DisplayName("다른 세션의 rep 이 섞이지 않는다")
        void 세션별_격리() {
            Session mine = inProgressSession();
            Session other = session(stranger, LocalDateTime.now(), Status.IN_PROGRESS, null);
            poseDataService.savePoseDataBatch(mine.getId(), List.of(frame(2, 0.1, 70.0)));
            poseDataService.savePoseDataBatch(other.getId(), List.of(frame(9, 0.1, 70.0)));

            assertThat(poseDataRepository.findMaxRepNumberBySessionId(mine.getId())).isEqualTo(2);
            assertThat(poseDataRepository.findMaxRepNumberBySessionId(other.getId())).isEqualTo(9);
        }
    }
}
