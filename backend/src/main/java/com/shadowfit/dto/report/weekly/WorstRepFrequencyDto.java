package com.shadowfit.dto.report.weekly;

/**
 * 주간 «B층» — worst 회차 분포 한 점.
 *
 * <p>기간 내 세션마다 {@code detailed_analysis.worstSection.repNumber} 를 세어
 * {@code repNumber} 별 빈도로 묶은 값이다({@code JSON_TABLE}, 설계 §13-2 Q3).
 *
 * <p>🔴 <b>«국면» 이 아니라 «회차» 다.</b> {@code WorstSectionDto} 에 국면 이름표가 없어(#80)
 * 만들 수 있는 것은 회차 번호 기준 분포뿐이다.
 *
 * @param repNumber worst 로 뽑힌 회차 번호
 * @param count     기간 내 그 회차가 worst 였던 세션 수
 */
public record WorstRepFrequencyDto(int repNumber, long count) {
}
