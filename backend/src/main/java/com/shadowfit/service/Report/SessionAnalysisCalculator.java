package com.shadowfit.service.Report;

import com.shadowfit.dto.report.PoseFrameProjection;
import com.shadowfit.dto.report.detailreport.RepSyncRateDto;
import com.shadowfit.dto.report.detailreport.WorstSectionDto;
import com.shadowfit.model.exercise.Session;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * worst rep(싱크로율이 가장 낮은 회차) 계산 — 읽기 경로(ReportService)와 쓰기 경로
 * (SessionService.applyComplete, precompute-on-write)가 공유하는 순수 계산 컴포넌트로 분리.
 * Session·List&lt;PoseFrameProjection&gt;만 받고 다른 서비스를 의존하지 않아, SessionService가 이걸
 * 직접 의존해도 ReportService와의 순환 의존이 생기지 않는다(report-read-path.md §9-1).
 *
 * <p><b>이슈 #78 로 슬라이딩 윈도우에서 rep 단위로 바뀌었다.</b> 예전에는 연속 3프레임의 평균이
 * 가장 낮은 구간을 찾으면서 "단일 프레임은 노이즈 영향이 커서 구간으로 본다"고 적어뒀는데,
 * {@code sync_rate} 는 ai-server 가 <b>rep 단위로 채점해 프레임마다 복제</b>한 값이라 rep 안에서
 * 상수다 — 윈도우가 방어한다는 노이즈가 존재하지 않았다. 그 전제 위에서 세 가지가 틀렸다:
 *
 * <ul>
 *   <li>다운샘플(R≈5)로 rep 당 남는 행이 rep 길이에 비례해, <b>행이 3개 미만인 짧은 rep 은
 *       자기 값만으로 윈도우를 채우지 못했다.</b> 이웃 rep 의 높은 값이 섞여 평균이 올라가
 *       실제로 가장 나쁜 rep 이 worst 로 안 뽑혔다</li>
 *   <li>경계를 걸친 윈도우가 선택되면 <b>어느 rep 의 점수도 아닌 값</b>이 리포트로 나갔다</li>
 *   <li>대표 timestamp 가 rep 안의 임의 지점이라 <b>rep 해상도밖에 없는 데이터에 프레임 단위
 *       정밀도를 가장</b>했다</li>
 * </ul>
 *
 * <p>지금은 rep 으로 묶어 평균이 가장 낮은 rep 을 고른다. <b>평균을 쓰는 것은 의도적이다</b> —
 * 지금 데이터에서는 rep 안이 상수라 어느 프레임을 봐도 같지만, 나중에 프레임별 채점이 도입되면
 * (decisions/worst-section-rep-resolution.md §4-ㄷ) 이 코드를 고치지 않아도 의미가 유지된다.
 *
 * <p>이름이 {@code WorstSectionCalculator} 였다가 바뀌었다 — 같은 rep 그룹핑에서 회차별 추이
 * ({@link #calculateRepTrend})도 나오면서 worst 만 계산하지 않게 됐기 때문이다. 이 프로젝트는
 * <b>이름·주석이 하는 일과 어긋난 지점</b>에서 결함이 반복해 나왔으므로(#78·#79·#80) 그대로 두지 않는다.
 */
@Component
public class SessionAnalysisCalculator {

    public WorstSectionDto calculate(Session session, List<PoseFrameProjection> poseFrames) {
        if (poseFrames == null || poseFrames.isEmpty()) {
            return null;
        }

        Map<Integer, List<PoseFrameProjection>> framesByRep = groupByRep(poseFrames);
        if (framesByRep.isEmpty()) {
            return null; // rep 을 알 수 있는 프레임이 하나도 없음 (§ groupByRep 주석)
        }

        int worstRep = 0;
        double worstAverage = Double.MAX_VALUE;
        List<PoseFrameProjection> worstFrames = null;

        for (Map.Entry<Integer, List<PoseFrameProjection>> entry : framesByRep.entrySet()) {
            Double average = averageSyncRate(entry.getValue());
            if (average == null) {
                continue; // syncRate 가 전부 null 인 rep — 평균을 낼 수 없으므로 후보에서 배제
            }
            // 엄격 부등호라 동률이면 먼저 나온(= 번호가 작은) rep 이 남는다. 조회가 rep 오름차순이라
            // 순서가 확정돼 있어 같은 입력이면 항상 같은 rep 이 나온다.
            if (average < worstAverage) {
                worstAverage = average;
                worstRep = entry.getKey();
                worstFrames = entry.getValue();
            }
        }

        if (worstFrames == null) {
            return null; // 유효한(syncRate 가 있는) rep 이 하나도 없음
        }

        PoseFrameProjection representative = pickRepresentative(worstFrames);

        WorstSectionDto worst = new WorstSectionDto();
        // repTrend 의 어느 점이 worst 인지 잇는 열쇠. reason 문자열에서 파싱하게 두면 잠정 문구(#80)에
        // 프론트가 묶인다. 같은 groupByRep 결과에서 나오므로 추이의 최솟값과 반드시 일치한다.
        worst.setRepNumber(worstRep);
        worst.setExerciseName(session.getExercise().getName());
        worst.setTimeStamp(formatTimestamp(representative.timestampSec()));
        worst.setReason(buildWorstReason(worstRep, worstAverage));
        // jointCoordinates 는 여기서 안 채운다 — 이 DTO가 detailed_analysis 에 그대로 저장되므로
        // (SessionService.precomputeReport), PK만 남기고 실제 좌표는 읽기 시점에 조회한다
        // (WorstSectionDto.poseDataId 주석 참고).
        worst.setPoseDataId(representative.id());
        return worst;
    }

    /**
     * 회차별 싱크로율 추이 (rep 오름차순).
     *
     * <p>{@link #calculate} 와 같은 재료(rep 그룹 + rep 평균)를 쓰지만 <b>고르는 대신 전부</b>
     * 내놓는다. worst 는 "가장 나빴던 한 회차"만 알려주므로 "3회차부터 계속 떨어졌다" 같은 흐름을
     * 볼 수 없었고, 데이터는 {@code pose_data} 에 rep 별로 이미 있는데 노출하는 경로만 없었다.
     *
     * <p>측정된 rep 이 없으면 <b>빈 리스트</b>다(null 아님) — 응답 계약을 단순하게 두려는 것이고,
     * "측정 안 됨"은 같은 응답의 {@code totalReps}·{@code avgSyncRate} 로 이미 드러난다.
     *
     * <p>rep 하나가 한 점이므로 크기는 세션의 rep 수(수십 규모)다. 이 계산은 조회 때가 아니라
     * precompute 시점에 한 번 돌고 결과가 {@code reports.detailed_analysis} 에 저장된다.
     */
    public List<RepSyncRateDto> calculateRepTrend(List<PoseFrameProjection> poseFrames) {
        if (poseFrames == null || poseFrames.isEmpty()) {
            return List.of();
        }

        List<RepSyncRateDto> trend = new ArrayList<>();
        for (Map.Entry<Integer, List<PoseFrameProjection>> entry : groupByRep(poseFrames).entrySet()) {
            Double average = averageSyncRate(entry.getValue());
            if (average == null) {
                continue; // syncRate 가 전부 null 인 rep — 추이에 구멍으로 남기고 점을 찍지 않는다
            }
            PoseFrameProjection representative = pickRepresentative(entry.getValue());
            trend.add(new RepSyncRateDto(
                    entry.getKey(),
                    // 소수 1자리 반올림 — rep 안 값이 상수라 지금은 나눗셈 오차만 정리하는 수준이지만,
                    // 프레임별 채점이 도입되면 실제 평균이 되므로 자리수를 미리 고정해 둔다.
                    Math.round(average * 10.0) / 10.0,
                    formatTimestamp(representative.timestampSec())
            ));
        }
        return trend;
    }

    /**
     * rep 번호로 묶는다. 입력이 rep 오름차순이라 LinkedHashMap 의 순회 순서도 rep 오름차순이다.
     *
     * <p>{@code repNumber <= 0} 은 <b>"미상"이라 제외한다</b> — 컬럼이 생기기 전에 저장된 행과,
     * rep_number 를 안 보내는 구버전 AI 의 행이 여기 해당한다(마이그레이션
     * {@code 2026-07-31-add-pose-data-rep-number.sql}). 섞으면 서로 다른 rep 이 하나로 뭉뚱그려져,
     * 고치려던 것과 똑같은 결함이 다시 생긴다. #75 의 {@code findRepAverageSyncRates} 도 같은 기준이다.
     *
     * <p>그 결과 <b>미상 행만 있는 세션은 worst 를 못 낸다</b>(null). 예전 코드는 rep 을 안 봤으므로
     * 뭔가를 내놓긴 했지만, 그 값이 어느 rep 의 것인지 말할 수 없었다 — 틀린 값을 내놓느니 없다고
     * 하는 편이 낫다는 판단이다. 실사용 데이터에서는 #74 이후 저장분이 모두 rep 을 싣는다.
     */
    private Map<Integer, List<PoseFrameProjection>> groupByRep(List<PoseFrameProjection> poseFrames) {
        Map<Integer, List<PoseFrameProjection>> framesByRep = new LinkedHashMap<>();
        for (PoseFrameProjection frame : poseFrames) {
            Integer repNumber = frame.repNumber();
            if (repNumber == null || repNumber <= 0) {
                continue;
            }
            framesByRep.computeIfAbsent(repNumber, key -> new ArrayList<>()).add(frame);
        }
        return framesByRep;
    }

    /**
     * rep 을 대표할 프레임 = <b>가장 깊게 앉은 순간</b>({@code smoothedKneeAngle} 최소).
     *
     * <p>예전에는 {@code frames.get(size / 2)} 즉 <b>배열 중앙</b>이었다. 슬라이딩 윈도우 시절의
     * "윈도우 중앙"을 그대로 옮긴 것인데, 그게 스쿼트의 바닥이라는 근거가 없었다 — 바닥에서
     * 잠깐 멈추거나 내려가는 속도와 올라오는 속도가 다르면 어긋난다. 이 선택은 두 가지를
     * 결정하므로 근거가 있어야 한다: 리포트의 {@code timeStamp}, 그리고 그 프레임의
     * {@code jointCoordinates}(= 앱이 그릴 자세).
     *
     * <p>기준은 ai-server 가 rep 경계를 판정할 때 쓰는 값과 같다(좌우 무릎각 평균의 3프레임 평활).
     * 그래야 "이 rep 의 바닥"과 "가장 깊은 프레임"이 서로 다른 근거를 갖지 않는다
     * (decisions/worst-section-rep-resolution.md §4-ㄹ).
     *
     * <p><b>유효값(&gt; 0)이 없으면 중앙으로 떨어진다</b> — 구버전 AI 가 보낸 행과 컬럼 도입 이전
     * 행이 여기 해당한다. 예전 동작을 그대로 유지한다는 뜻이고, "가장 깊어서"가 아니라
     * <b>"고를 근거가 없어서"</b> 중앙이라는 것을 명시한다.
     *
     * <p>⚠️ 이 선택은 저장 시점의 다운샘플({@code PoseDataService.pickDeepest})이 그 프레임을
     * 남겼을 때만 의미가 있다. 버려진 프레임은 DB 에 없으므로 여기서 되찾을 수 없다.
     */
    private PoseFrameProjection pickRepresentative(List<PoseFrameProjection> frames) {
        PoseFrameProjection deepest = null;
        for (PoseFrameProjection frame : frames) {
            Double kneeAngle = frame.smoothedKneeAngle();
            if (kneeAngle == null || kneeAngle <= 0.0) {
                continue; // 미상 — 유효한 무릎각이 아니다
            }
            // 엄격 부등호라 동률이면 먼저 나온(= 시간이 이른) 프레임이 남는다. 조회가 시간
            // 오름차순이라 순서가 확정돼 있어 같은 입력이면 항상 같은 프레임이 나온다.
            if (deepest == null || kneeAngle < deepest.smoothedKneeAngle()) {
                deepest = frame;
            }
        }
        return deepest != null ? deepest : frames.get(frames.size() / 2);
    }

    /** rep 안의 non-null syncRate 평균. 전부 null 이면 null(= 후보 배제). */
    private Double averageSyncRate(List<PoseFrameProjection> frames) {
        double sum = 0.0;
        int count = 0;
        for (PoseFrameProjection frame : frames) {
            Double rate = frame.syncRate();
            if (rate == null) {
                continue;
            }
            sum += rate;
            count++;
        }
        return count == 0 ? null : sum / count;
    }

    private String formatTimestamp(Double timestampSec) {
        if (timestampSec == null) return "00:00";
        int totalSeconds = timestampSec.intValue();
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    /**
     * ⚠️ <b>문구는 잠정이다</b>(이슈 #80, decisions/worst-section-rep-resolution.md §8-3).
     *
     * <p>예전에는 {@code feedback_message} 의 최빈값을 덧붙여 {@code "싱크로율 21% · 즉시 자세
     * 수정 필요"} 같은 문자열을 만들었는데, 그 메시지는 문자열 3개가 전부이고 <b>전부
     * {@code sync_rate} 를 임계값과 비교한 결과</b>라 뒷부분이 앞부분의 함수였다 — 새 정보가 0.
     * 게다가 rep 안에서 상수라 "가장 자주 등장한 것"을 세는 계산 자체가 실행될 이유가 없었다.
     *
     * <p>그래서 동어반복을 걷어내고 회차만 드러낸다. <b>사람이 읽을 사유</b>(무릎/상체 각도 기반
     * 진단)를 만드는 것은 #80 의 별도 선택지이며, 최종 문구는 그때 확정한다.
     */
    private String buildWorstReason(int repNumber, double averageSyncRate) {
        return String.format("%d회차 · 싱크로율 %d%%", repNumber, Math.round(averageSyncRate));
    }
}
