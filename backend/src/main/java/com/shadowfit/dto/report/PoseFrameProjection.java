package com.shadowfit.dto.report;

/**
 * worst rep 계산에 필요한 컬럼만. joint_coordinates(2.3KB) 제외 → off-page I/O 회피.
 *
 * <p>{@code repNumber} 는 이슈 #78 로 추가됐다. 그전까지 이 프로젝션은 {@code sync_rate} 가
 * 프레임마다 다르다는 전제로 rep 을 싣지 않았는데, 실제 데이터는 <b>rep 안에서 상수</b>다
 * (ai-server 가 rep 단위로 채점해 프레임마다 복제 전송). rep 을 모르면 계산기가 rep 경계를
 * 넘는 것을 구조적으로 막을 수 없다.
 *
 * <p>{@code feedbackMessage} 는 <b>제거했다</b>(이슈 #80). 값이 문자열 3개뿐이고 전부
 * {@code sync_rate} 를 임계값과 비교한 결과라, 읽어봐야 싱크로율의 동어반복이었다. 유일한
 * 소비자였던 {@code pickDominantFeedback} 이 사라져 더는 조회할 이유가 없다.
 *
 * <p>{@code smoothedKneeAngle} 은 §4-ㄹ 로 추가됐다. {@code syncRate} 는 rep 안에서 상수라
 * <b>rep 을 고를 수는 있어도 rep 안의 프레임을 고를 수는 없다.</b> 대표 프레임을 "가장 깊었던
 * 순간"으로 정하려면 프레임마다 실제로 다른 값이 필요하고, 그게 이 컬럼이다.
 * {@code jointCoordinates} 를 싣지 않는 방침은 유지된다 — 각도는 숫자 하나라 off-page I/O 를
 * 되살리지 않으면서 프레임을 구분할 수 있다는 것이 이 안의 핵심이다.
 *
 * <p>{@code id} 는 P5 Tier 0(32-deferred-items.md)로 추가됐다. 대표 프레임이 정해진 뒤 그
 * 프레임의 {@code jointCoordinates} 하나만 <b>PK로 다시 조회</b>하기 위한 열쇠다 — 여기서
 * 값을 직접 싣지 않는 이유는 그대로다(대부분의 프레임은 대표로 안 뽑혀 그 비용이 버려진다).
 */
public record PoseFrameProjection(
        Long id,
        Double timestampSec,
        Double syncRate,
        Integer repNumber,
        Double smoothedKneeAngle) {}
