package com.shadowfit.dto.report.detailreport;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 회차(rep) 하나의 싱크로율 — 리포트의 회차별 추이 한 점.
 *
 * <p>싱크로율의 자연스러운 단위가 rep 인 이유: DTW 는 시퀀스 한 쌍을 받아 숫자 하나를 내놓는다.
 * 사람마다 운동 속도가 달라 프레임 번호를 1:1 로 맞댈 수 없어 시간축을 늘였다 줄여 정렬하므로,
 * 채점이 rep 단위로 한 번 일어난다(ai-server {@code _summarize_rep}).
 *
 * <p>{@code timeStamp} 는 그 rep 의 중앙 프레임 기준이다 — worst 구간의 대표 시각과 같은 규칙이라
 * 추이 그래프에서 worst 점을 짚을 때 값이 어긋나지 않는다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RepSyncRateDto {
    private int repNumber;      // 1부터. 세션 전체 기준 연번(세트 도입 시 재검토 — 29-ai-code-verification.md §5)
    private double syncRate;    // 소수 1자리로 반올림
    private String timeStamp;   // "01:15" — rep 중앙 프레임
}
