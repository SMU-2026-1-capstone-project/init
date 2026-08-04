package com.shadowfit.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자 세션 목록 정렬 키 — <b>화이트리스트</b>.
 *
 * <p>{@link AdminMemberSortKey} 와 같은 이유로 enum 이다 — 정렬 컬럼을 문자열로 받으면
 * 노출 대상이 아닌 컬럼으로도 정렬할 수 있고, 정렬 순서만으로 값을 좁히는 추론이 가능해진다.
 *
 * <p>§3-B 의 정렬 후보 3종을 그대로 담았다.
 */
@Schema(description = "세션 목록 정렬 키")
public enum AdminSessionSortKey {

    /**
     * 시작 시각. 기본값이며 {@code idx_session_status_starttime (status, start_time)} 을
     * 타는 유일한 정렬이다. 상태 필터가 함께 걸리면 등치(status)로 좁힌 뒤 그 안이
     * {@code start_time} 순이라 탐색·정렬이 한 인덱스로 풀린다
     * ({@code admin-page-scope.md} §4-1).
     */
    START_TIME,

    /**
     * 평균 싱크로율. 인덱스가 없어 filesort 가 발생한다.
     *
     * <p>⚠️ 이 값은 <b>nullable</b> 이다 — 세션이 끝나고 AI 분석 결과가 회수돼야 채워진다.
     * MySQL 은 NULL 을 가장 작은 값으로 취급하므로 오름차순이면 앞에, 내림차순이면 뒤에
     * 몰린다. "싱크로율 낮은 세션 추적"이 이 정렬의 용도인데, 오름차순으로 보면 <b>분석
     * 전 세션이 먼저 나와</b> 의도와 어긋난다. 별도 처리를 넣지 않은 이유는 그 판단이
     * 화면 요구사항이라 아직 정해지지 않았기 때문이다.
     */
    AVG_SYNC_RATE,

    /** 총 반복 수. 인덱스가 없어 filesort 가 발생한다. */
    TOTAL_REPS
}
