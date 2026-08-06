package com.shadowfit.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 관리자 회원 목록 정렬 키 — <b>화이트리스트</b>.
 *
 * <p>정렬 컬럼을 문자열로 받아 그대로 쿼리에 넘기면 클라이언트가 임의 컬럼을 지정할 수 있다.
 * 정렬 대상은 노출 대상이 아닌 컬럼(예: {@code password})도 될 수 있어, 정렬 순서만으로
 * 값을 좁혀 나가는 추론이 가능해진다. 허용 목록을 enum 으로 고정해 그 경로를 막는다.
 *
 * <p>{@code admin-page-scope.md} §3-A 의 정렬 후보 중 <b>"최근 운동일"은 아직 없다.</b>
 * 그건 파생 값이라 세션 테이블 조인·서브쿼리가 필요하고, 회원마다 최신 세션을 찾는 형태라
 * 이번 인덱스({@code users(created_at)})와 무관한 별도 설계가 된다. 필요해지면 추가한다.
 */
@Schema(description = "회원 목록 정렬 키")
public enum AdminMemberSortKey {

    /** 가입일. 기본값이며 이번에 추가한 {@code idx_users_created_at} 을 타는 유일한 정렬이다. */
    CREATED_AT,

    /** 회원명. 보조 인덱스가 없어 정렬 시 filesort 가 발생한다. */
    USERNAME
}
