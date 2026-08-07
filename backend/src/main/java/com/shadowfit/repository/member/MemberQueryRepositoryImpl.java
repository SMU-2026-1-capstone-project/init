package com.shadowfit.repository.member;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.shadowfit.dto.admin.AdminMemberListItemDto;
import com.shadowfit.dto.admin.AdminMemberSearchCondition;
import com.shadowfit.dto.admin.AdminMemberSortKey;
import com.shadowfit.dto.common.PageResponse;
import com.shadowfit.model.member.QMember;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.WorkoutLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class MemberQueryRepositoryImpl implements MemberQueryRepository {

    private static final QMember member = QMember.member;

    private final JPAQueryFactory queryFactory;

    @Override
    public PageResponse<AdminMemberListItemDto> searchForAdmin(
            AdminMemberSearchCondition condition,
            AdminMemberSortKey sortKey,
            boolean ascending,
            int page,
            int size
    ) {
        List<AdminMemberListItemDto> content = queryFactory
                // 엔티티가 아니라 DTO 로 직접 프로젝션한다 — password 등 목록에 불필요한
                // 컬럼을 애초에 읽지 않는다. AdminMemberListItemDto 주석 참고.
                .select(Projections.constructor(AdminMemberListItemDto.class,
                        member.id,
                        member.username,
                        member.email,
                        member.selectedPersona,
                        member.workoutLevel,
                        member.onboardingCompleted,
                        member.createdAt))
                .from(member)
                .where(whereOf(condition))
                .orderBy(orderOf(sortKey, ascending))
                .offset((long) page * size)
                .limit(size)
                .fetch();

        // 총건수는 목록과 같은 whereOf() 를 재사용한다. 조건을 따로 짜면 "전체 N건"과
        // 실제 목록이 어긋난다 (admin-page-scope.md §7).
        //
        // ⚠️ 이 쿼리는 LIMIT 이 없어 대부분의 필터 조합에서 20만 행을 전부 센다 —
        // 한 화면 비용의 대부분이 여기다(§4-3 ③ 실측). 감수하기로 했고(㉮, 2026-08-06)
        // 그 근거와 구멍은 SessionQueryRepositoryImpl#countOf 주석에 적었다.
        Long total = queryFactory
                .select(member.count())
                .from(member)
                .where(whereOf(condition))
                .fetchOne();

        return PageResponse.of(content, page, size, total == null ? 0L : total);
    }

    /**
     * 조건 배열. QueryDSL 의 {@code where(...)} 는 <b>null 인자를 그냥 건너뛴다</b> —
     * 그래서 "값이 없으면 조건을 걸지 않는다"가 if 문 없이 표현된다. 이 성질 덕분에
     * 목록 쿼리와 count 쿼리가 같은 조건 집합을 그대로 공유할 수 있다.
     */
    private BooleanExpression[] whereOf(AdminMemberSearchCondition c) {
        return new BooleanExpression[]{
                keywordContains(c.keyword()),
                personaEq(c.persona()),
                workoutLevelEq(c.workoutLevel()),
                onboardingCompletedEq(c.onboardingCompleted()),
                joinedGoe(c.joinedFrom()),
                joinedLt(c.joinedTo())
        };
    }

    /**
     * username 또는 email 부분일치.
     *
     * <p>⚠️ 선행 와일드카드({@code LIKE '%x%'})라 <b>인덱스 탐색</b>에는 쓸 수 없다. 다만
     * "그러니 인덱스가 아무 소용 없다"는 틀렸다 — 실측 결과 {@code idx_users_created_at} 은
     * <b>정렬용으로 쓰인다</b>({@code ORDER BY created_at DESC} 를 백워드 인덱스 스캔으로 대체,
     * {@code filesort} 소멸). {@code admin-page-scope.md} §4-3 ① 참고.
     *
     * <p>🔴 대신 여기에 함정이 둘 있다.
     * <ul>
     *   <li>{@code EXPLAIN} 의 {@code rows=20} 은 측정이 아니라 <b>낙관적 추정</b>이다.
     *       매칭이 드물수록 {@code LIMIT 20} 을 채우려 인덱스를 더 훑는다 — 실측으로
     *       0건 매칭 시 20만 행을 전부 읽었다 (§4-3 ②)</li>
     *   <li>~~{@code containsIgnoreCase()} 가 씌우는 {@code lower()}~~ → <b>제거함</b>
     *       (issue #105). 아래 "대소문자" 항목 참고</li>
     * </ul>
     *
     * <p><b>⚠️ 대소문자 무시는 이 코드가 아니라 DB 컬레이션이 한다.</b> 원래는
     * {@code containsIgnoreCase()} 였는데, 그건 {@code lower(username) like ?} 를 만들어
     * <b>결과를 바꾸지 않으면서</b> 컬럼에 함수만 씌웠다 — 컬레이션이 이미
     * {@code utf8mb4_unicode_ci}(대소문자 무시)이기 때문이다. 함수가 씌워지면 접두 검색
     * ({@code LIKE 'kim%'})으로 요구사항을 바꿔도 인덱스를 탈 수 없어서 걷어냈다.
     *
     * <p>대신 <b>컬레이션 의존이 코드에서 안 보이게 됐다.</b> {@code _bin} 이나 {@code _as_cs}
     * 로 바꾸면 검색이 조용히 대소문자를 구분하기 시작한다. 그 전제는 테스트가 잡는다 —
     * {@code MemberQueryRepositoryTest#keyword_isCaseInsensitive_byCollation}, 그리고 테스트
     * H2 도 같은 규칙이 되도록 {@code IGNORECASE=TRUE} 를 걸어뒀다.
     *
     * <p>이 변경만으로 빨라지는 쿼리는 <b>없다.</b> 선행 와일드카드가 그대로라 여전히 인덱스
     * 탐색은 못 한다 — 접두 검색 전환의 선결 조건일 뿐이고, 그 전환 여부는 미결정이다
     * (§4-3 "이제 갈릴 수 있는 결정" 3번).
     */
    private BooleanExpression keywordContains(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        return member.username.contains(keyword)
                .or(member.email.contains(keyword));
    }

    private BooleanExpression personaEq(SelectedPersona persona) {
        return persona == null ? null : member.selectedPersona.eq(persona);
    }

    /**
     * 운동 레벨 등치.
     *
     * <p>온보딩 전 회원은 이 값이 {@code null} 인데, SQL 등치 비교는 NULL 에 걸리지 않으므로
     * 레벨 필터를 걸면 그런 회원은 자연히 빠진다. 별도 처리를 넣지 않는다 — "온보딩 안 한
     * 회원"은 {@code onboardingCompleted=false} 필터로 찾을 수 있어 조회 수단이 비지 않는다.
     */
    private BooleanExpression workoutLevelEq(WorkoutLevel level) {
        return level == null ? null : member.workoutLevel.eq(level);
    }

    private BooleanExpression onboardingCompletedEq(Boolean completed) {
        return completed == null ? null : member.onboardingCompleted.eq(completed);
    }

    private BooleanExpression joinedGoe(LocalDate from) {
        return from == null ? null : member.createdAt.goe(from.atStartOfDay());
    }

    /**
     * 가입일 종료 조건.
     *
     * <p>종료일을 <b>포함</b>해야 하므로 {@code loe(to.atStartOfDay())} 를 쓰면 안 된다 —
     * 그러면 그날 00:00:00 에 가입한 회원만 걸리고 그날 나머지가 통째로 빠진다.
     * 다음 날 00:00:00 <b>미만</b>으로 잡아 종료일 하루를 온전히 포함시킨다.
     */
    private BooleanExpression joinedLt(LocalDate to) {
        return to == null ? null : member.createdAt.lt(to.plusDays(1).atStartOfDay());
    }

    /**
     * 정렬. 키는 {@link AdminMemberSortKey} 화이트리스트에서만 온다.
     *
     * <p>2차 정렬로 {@code id} 를 항상 덧붙인다. 정렬 값이 같은 행들의 순서는 정해져 있지
     * 않아서, 그대로 두면 <b>페이지를 넘길 때 같은 행이 두 번 나오거나 아예 안 나올 수
     * 있다.</b> offset 페이징은 "몇 번째부터"로 자르므로 순서가 흔들리면 경계에서 어긋난다.
     */
    private OrderSpecifier<?>[] orderOf(AdminMemberSortKey sortKey, boolean ascending) {
        Order direction = ascending ? Order.ASC : Order.DESC;
        AdminMemberSortKey key = sortKey == null ? AdminMemberSortKey.CREATED_AT : sortKey;

        OrderSpecifier<?> primary = switch (key) {
            case USERNAME -> new OrderSpecifier<>(direction, member.username);
            case CREATED_AT -> new OrderSpecifier<>(direction, member.createdAt);
        };
        return new OrderSpecifier<?>[]{primary, new OrderSpecifier<>(direction, member.id)};
    }
}
