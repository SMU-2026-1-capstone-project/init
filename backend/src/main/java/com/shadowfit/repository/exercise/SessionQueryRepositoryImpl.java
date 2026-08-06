package com.shadowfit.repository.exercise;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.shadowfit.dto.admin.AdminSessionListItemDto;
import com.shadowfit.dto.admin.AdminSessionSearchCondition;
import com.shadowfit.dto.admin.AdminSessionSortKey;
import com.shadowfit.dto.common.PageResponse;
import com.shadowfit.model.exercise.QExercise;
import com.shadowfit.model.exercise.QSession;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.QMember;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class SessionQueryRepositoryImpl implements SessionQueryRepository {

    private static final QSession session = QSession.session;
    private static final QMember member = QMember.member;
    private static final QExercise exercise = QExercise.exercise;

    private final JPAQueryFactory queryFactory;

    @Override
    public PageResponse<AdminSessionListItemDto> searchForAdmin(
            AdminSessionSearchCondition condition,
            AdminSessionSortKey sortKey,
            boolean ascending,
            int page,
            int size
    ) {
        List<AdminSessionListItemDto> content = queryFactory
                .select(Projections.constructor(AdminSessionListItemDto.class,
                        session.id,
                        member.id,
                        member.username,
                        exercise.id,
                        exercise.name,
                        session.status,
                        session.startTime,
                        session.endTime,
                        session.totalReps,
                        session.avgSyncRate))
                .from(session)
                // 둘 다 inner join 이다 — member_id·exercise_id 가 NOT NULL 이라 짝이 없는 세션이
                // 존재할 수 없다. left join 으로 두면 옵티마이저가 조인 순서를 바꿀 여지가 줄어드는
                // 대신 얻는 것이 없다.
                .join(session.member, member)
                .join(session.exercise, exercise)
                .where(whereOf(condition))
                .orderBy(orderOf(sortKey, ascending))
                .offset((long) page * size)
                .limit(size)
                .fetch();

        return PageResponse.of(content, page, size, countOf(condition));
    }

    /**
     * 총건수.
     *
     * <p><b>목록 쿼리와 조인 구성이 다르다.</b> 목록은 회원명·운동명을 응답에 실어야 해서 두
     * 조인이 항상 필요하지만, 개수를 세는 데는 <b>거르는 데 쓰이는 조인만</b> 있으면 된다.
     *
     * <ul>
     *   <li>{@code exercise} — 필터가 {@code exerciseId} 이고 그건 {@code session} 이 이미
     *       FK 로 들고 있다. 조인할 이유가 없다</li>
     *   <li>{@code member} — 검색어가 {@code users.username} 을 보므로 <b>검색어가 있을 때만</b>
     *       필요하다. 없으면 붙이지 않는다</li>
     * </ul>
     *
     * <p>조건은 목록과 <b>같은 {@link #whereOf} 를 재사용</b>한다. 따로 짜면 "전체 N건"과
     * 실제 목록이 어긋난다 ({@code admin-page-scope.md} §7).
     *
     * <p>⚠️ 조인을 줄여도 이 쿼리는 {@code LIMIT} 이 없어 조건에 맞는 행을 전부 세야 한다.
     * 회원 목록에서 실측된 것과 같은 문제이고, 여기서는 조인까지 얹혀 더 비싸다
     * ({@code admin-page-scope.md} §4-3 ③).
     *
     * <p><b>이 비용은 감수하기로 했다</b>(㉮, 2026-08-06). 근거는 "관리자 트래픽이 드물어서"가
     * 아니라 <b>되돌리는 비용의 비대칭</b>이다 — 관리자 프론트를 만든다는 것은 정해졌지만
     * 페이지 번호냐 무한 스크롤이냐가 미정이라, 무한 스크롤로 정해지면 keyset 을 얹으면서
     * 이 메서드를 안 부르면 되는 반면 지금 지웠다가 페이지 번호로 정해지면 다시 만들어야 한다.
     *
     * <p>⚠️ 감수의 구멍: 스캔 <b>폭</b>은 쟀지만(20만 행) <b>시간</b>은 재지 않았다.
     * {@code EXPLAIN} 은 옵티마이저의 선택이라 코어 수·경합과 무관한 반면, 시간은 로컬
     * 2코어 동거 환경이라 신뢰하지 않기로 한 값이다. 실사용 데이터가 쌓여 분포가 진짜가 되면
     * 재검토 대상이다 ({@code admin-page-scope.md} §4-3 "2026-08-06" 절).
     */
    private long countOf(AdminSessionSearchCondition condition) {
        JPAQuery<Long> query = queryFactory
                .select(session.count())
                .from(session);

        if (StringUtils.hasText(condition.keyword())) {
            query.join(session.member, member);
        }

        Long total = query.where(whereOf(condition)).fetchOne();
        return total == null ? 0L : total;
    }

    /**
     * 조건 배열. {@code where(...)} 가 null 인자를 건너뛰는 성질을 그대로 쓴다
     * ({@code MemberQueryRepositoryImpl} 과 같은 규칙).
     *
     * <p>⚠️ {@link #countOf} 가 {@code member} 조인을 조건부로 붙이므로, <b>검색어 조건과
     * 그 조인은 항상 같이 있거나 같이 없어야 한다.</b> {@link #keywordContains} 가 검색어가
     * 없을 때 null 을 돌려주는 것이 그 짝을 맞춘다 — 여기서 {@code member} 를 보는 조건을
     * 하나 더 늘린다면 조인 조건도 같이 손봐야 한다.
     */
    private BooleanExpression[] whereOf(AdminSessionSearchCondition c) {
        return new BooleanExpression[]{
                statusEq(c.status()),
                exerciseIdEq(c.exerciseId()),
                startedGoe(c.startedFrom()),
                startedLt(c.startedTo()),
                keywordContains(c.keyword())
        };
    }

    private BooleanExpression statusEq(Status status) {
        return status == null ? null : session.status.eq(status);
    }

    /**
     * 운동 종목.
     *
     * <p>{@code session.exercise.id} 로 접근하면 조인 없이 세션이 들고 있는 FK 를 그대로 쓴다.
     * {@code exercise.id} 로 쓰면 조인한 테이블을 보게 돼, 총건수 쿼리에서 불필요한 조인이
     * 되살아난다.
     */
    private BooleanExpression exerciseIdEq(Long exerciseId) {
        return exerciseId == null ? null : session.exercise.id.eq(exerciseId);
    }

    private BooleanExpression startedGoe(LocalDate from) {
        return from == null ? null : session.startTime.goe(from.atStartOfDay());
    }

    /**
     * 기간 종료 조건.
     *
     * <p>종료일을 <b>포함</b>해야 하므로 다음 날 00:00:00 <b>미만</b>으로 잡는다.
     * {@code loe(to.atStartOfDay())} 로 짜면 그날 00:00:00 에 시작한 세션만 걸리고 그날
     * 나머지가 통째로 빠진다 — 회원 목록 가입일 필터와 같은 함정이다.
     */
    private BooleanExpression startedLt(LocalDate to) {
        return to == null ? null : session.startTime.lt(to.plusDays(1).atStartOfDay());
    }

    /**
     * 회원 검색어 — username 또는 email 부분일치.
     *
     * <p>회원 목록과 <b>같은 방식으로 맞췄다.</b> 관리자가 같은 검색어를 두 화면에 쳤을 때
     * 다르게 걸리면 그게 더 혼란스럽다.
     *
     * <p>⚠️ 선행 와일드카드라 인덱스 탐색에는 쓸 수 없다. {@code lower()} 를 씌우던
     * {@code containsIgnoreCase} 는 issue #105 로 <b>양쪽에서 같이 걷어냈다</b> — 대소문자
     * 무시는 이제 {@code utf8mb4_unicode_ci} 컬레이션이 한다. 자세한 사정은
     * {@code MemberQueryRepositoryImpl#keywordContains} 에 있다.
     *
     * <p>게다가 이쪽은 조인 너머라, 옵티마이저가 세션부터 읽을지 회원부터 읽을지 고른다.
     * 회원 목록에는 없던 변수다 ({@code admin-page-scope.md} §4-4 에서 측정).
     */
    private BooleanExpression keywordContains(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        return member.username.contains(keyword)
                .or(member.email.contains(keyword));
    }

    /**
     * 정렬. 키는 {@link AdminSessionSortKey} 화이트리스트에서만 온다.
     *
     * <p>2차 정렬로 {@code id} 를 항상 덧붙인다. 정렬 값이 같은 행들의 순서는 정해져 있지
     * 않아서, 그대로 두면 페이지 경계에서 같은 행이 두 번 나오거나 빠진다. 세션은 회원보다
     * 동점이 흔하다 — {@code totalReps} 는 정수라 값이 겹치기 쉽고, {@code avgSyncRate} 는
     * 분석 전이면 전부 null 이라 <b>한 덩어리로 동점</b>이 된다.
     */
    private OrderSpecifier<?>[] orderOf(AdminSessionSortKey sortKey, boolean ascending) {
        Order direction = ascending ? Order.ASC : Order.DESC;
        AdminSessionSortKey key = sortKey == null ? AdminSessionSortKey.START_TIME : sortKey;

        OrderSpecifier<?> primary = switch (key) {
            case START_TIME -> new OrderSpecifier<>(direction, session.startTime);
            case AVG_SYNC_RATE -> new OrderSpecifier<>(direction, session.avgSyncRate);
            case TOTAL_REPS -> new OrderSpecifier<>(direction, session.totalReps);
        };
        return new OrderSpecifier<?>[]{primary, new OrderSpecifier<>(direction, session.id)};
    }
}
