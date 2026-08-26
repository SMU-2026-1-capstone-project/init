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

    /**
     * 대표 프레임의 {@code pose_data.id} (P5 Tier 0, 32-deferred-items.md). {@code jointCoordinates}
     * 를 여기 바로 담지 않고 PK만 남기는 이유 — 이 DTO는 {@code SessionAnalysisCalculator.calculate}
     * 가 만들어 {@code reports.detailed_analysis} 에 그대로 직렬화된다({@code SessionService
     * .precomputeReport}). 좌표(2.3KB)를 여기 채우면 그 무게가 세션마다 영구히 복제 저장된다 —
     * PK 하나(8바이트)만 저장해두고, 실제 좌표는 <b>리포트를 읽을 때</b>(ReportService) 그 PK로
     * 딱 한 번 더 조회한다. 유효한 대표 프레임이 없으면(§ pickRepresentative) null 일 수 있다.
     */
    private Long poseDataId;

    /**
     * 대표 프레임의 관절 좌표(JSON 문자열, 33관절) — 앱이 그대로 그릴 수 있는 자세 스냅샷.
     *
     * <p>🔴 <b>precompute 시점엔 항상 null 이다.</b> {@code ReportService.buildReportResponse} 가
     * {@code poseDataId} 로 조회해 <b>응답 직전에만</b> 채운다 — {@code detailed_analysis} 에는
     * 절대 저장되지 않는다(위 {@code poseDataId} 주석 참고). 세션에 유효한 대표 프레임이 없거나
     * 그 사이 행이 지워졌으면(파티션 DROP 등) null 로 남는다 — 이 경우 프론트는 화면을 그냥 생략한다.
     */
    private String jointCoordinates;
}
