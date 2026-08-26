package com.shadowfit.repository.exercise;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.shadowfit.dto.admin.AdminExerciseListItemDto;
import com.shadowfit.dto.admin.AdminExerciseSearchCondition;
import com.shadowfit.dto.admin.AdminExerciseSortKey;
import com.shadowfit.dto.common.PageResponse;
import com.shadowfit.model.exercise.QExercise;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class ExerciseQueryRepositoryImpl implements ExerciseQueryRepository {

    private static final QExercise exercise = QExercise.exercise;

    private final JPAQueryFactory queryFactory;

    @Override
    public PageResponse<AdminExerciseListItemDto> searchForAdmin(
            AdminExerciseSearchCondition condition,
            AdminExerciseSortKey sortKey,
            boolean ascending,
            int page,
            int size
    ) {
        List<AdminExerciseListItemDto> content = queryFactory
                // DTO 직접 프로젝션 — description(TEXT)·targetJoints(JSON) 를 애초에 읽지 않는다.
                // AdminExerciseListItemDto 주석 참고.
                .select(Projections.constructor(AdminExerciseListItemDto.class,
                        exercise.id,
                        exercise.name,
                        exercise.category.id,
                        exercise.category.name,
                        exercise.analysisSupported,
                        exercise.expectedDurationMinutes,
                        exercise.createdAt))
                .from(exercise)
                .join(exercise.category)
                .where(whereOf(condition))
                .orderBy(orderOf(sortKey, ascending))
                .offset((long) page * size)
                .limit(size)
                .fetch();

        // 총건수는 목록과 같은 whereOf() 를 재사용한다 (admin-page-scope.md §7).
        //
        // 회원·세션 목록에서는 이 count 가 화면 비용의 대부분이었지만(20만 행 전수 카운트),
        // 여기서는 대상이 3행이라 사실상 공짜다. 같은 형태를 유지하는 이유는 비용이 아니라
        // "건수와 목록이 같은 조건에서 나온다"는 성질 쪽이다.
        Long total = queryFactory
                .select(exercise.count())
                .from(exercise)
                .where(whereOf(condition))
                .fetchOne();

        return PageResponse.of(content, page, size, total == null ? 0L : total);
    }

    /**
     * 조건 배열. QueryDSL 의 {@code where(...)} 는 <b>null 인자를 건너뛴다</b> — "값이 없으면
     * 조건을 걸지 않는다"가 if 문 없이 표현되고, 목록과 count 가 같은 조건 집합을 공유할 수 있다.
     */
    private BooleanExpression[] whereOf(AdminExerciseSearchCondition c) {
        return new BooleanExpression[]{
                nameContains(c.keyword()),
                categoryEq(c.categoryId())
        };
    }

    /**
     * 운동명 부분일치.
     *
     * <p>선행 와일드카드({@code LIKE '%x%'})라 인덱스 탐색에 쓸 수 없지만, 이 테이블은 보조
     * 인덱스가 아예 없고 행이 3개라 논의 대상이 아니다.
     *
     * <p><b>대소문자 무시는 이 코드가 아니라 DB 컬레이션이 한다</b>({@code utf8mb4_unicode_ci}).
     * {@code containsIgnoreCase()} 를 쓰지 않는 이유는 회원 검색과 같다 — 결과를 바꾸지 않으면서
     * 컬럼에 {@code lower()} 만 씌우기 때문이다(이슈 #105).
     */
    private BooleanExpression nameContains(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        return exercise.name.contains(keyword);
    }

    private BooleanExpression categoryEq(Long categoryId) {
        return categoryId == null ? null : exercise.category.id.eq(categoryId);
    }

    /**
     * 정렬. 키는 {@link AdminExerciseSortKey} 화이트리스트에서만 온다.
     *
     * <p>2차 정렬로 {@code id} 를 항상 덧붙인다 — 정렬 값이 같은 행들의 순서는 정해져 있지 않아,
     * 그대로 두면 페이지 경계에서 같은 행이 두 번 나오거나 아예 빠질 수 있다(회원 목록과 동일).
     */
    private OrderSpecifier<?>[] orderOf(AdminExerciseSortKey sortKey, boolean ascending) {
        Order direction = ascending ? Order.ASC : Order.DESC;
        AdminExerciseSortKey key = sortKey == null ? AdminExerciseSortKey.CREATED_AT : sortKey;

        OrderSpecifier<?> primary = switch (key) {
            case NAME -> new OrderSpecifier<>(direction, exercise.name);
            case CREATED_AT -> new OrderSpecifier<>(direction, exercise.createdAt);
        };
        return new OrderSpecifier<?>[]{primary, new OrderSpecifier<>(direction, exercise.id)};
    }
}