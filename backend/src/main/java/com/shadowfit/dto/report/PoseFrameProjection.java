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
 */
public record PoseFrameProjection(Double timestampSec, Double syncRate, Integer repNumber) {}
