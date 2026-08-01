package com.shadowfit.dto.report.detailreport;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WorstSectionDto {

    /**
     * 싱크로율이 가장 낮았던 회차 번호 — {@code repTrend} 의 어느 점이 worst 인지 잇는 열쇠다.
     *
     * <p>이게 없으면 클라이언트가 worst 점을 찾는 방법이 두 가지뿐인데 둘 다 취약하다:
     * {@code timeStamp} 문자열 비교(mm:ss 라 같은 초에 걸친 rep 이 둘이면 모호)나
     * {@code reason} 파싱이다. 특히 <b>{@code reason} 문구는 잠정</b>이라(이슈 #80) 거기에
     * 의존하면 문구를 확정하는 순간 프론트가 깨진다.
     *
     * <p>추이({@code repTrend})를 응답에 넣으면서 생긴 필드다. 그전에는 이을 대상이 없어
     * "DTO 구조 유지"로 정해뒀었다(decisions/worst-section-rep-resolution.md §8-3).
     */
    private Integer repNumber;           // 2

    private String exerciseName;         // "스쿼트"
    private String timeStamp;            // "01:15" — 그 회차의 중앙 프레임
    private String reason;               // "2회차 · 싱크로율 75%" (문구 잠정 — 이슈 #80)
}
