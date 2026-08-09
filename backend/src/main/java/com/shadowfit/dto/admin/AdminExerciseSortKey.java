package com.shadowfit.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자 운동 목록 정렬 키 — <b>화이트리스트</b>.
 *
 * <p>{@link AdminMemberSortKey} 와 같은 이유로 enum 이다: 정렬 컬럼을 문자열로 받아 그대로 쿼리에
 * 넘기면 클라이언트가 임의 컬럼을 지정할 수 있고, 정렬 순서만으로 값을 좁혀 나가는 추론이 가능해진다.
 *
 * <p><b>여기엔 인덱스를 타는 정렬이 하나도 없다.</b> {@code exercises} 테이블은 PK 외에 보조
 * 인덱스가 없어 두 키 모두 filesort 가 걸린다. 회원 목록과 달리 인덱스를 얹지 않은 이유는 대상이
 * 3행이기 때문이고, 종목 수가 의미 있게 늘어나면 그때 다시 볼 자리다.
 */
@Schema(description = "운동 목록 정렬 키")
public enum AdminExerciseSortKey {

    /** 등록일. 기본값. */
    CREATED_AT,

    /** 운동명. */
    NAME
}