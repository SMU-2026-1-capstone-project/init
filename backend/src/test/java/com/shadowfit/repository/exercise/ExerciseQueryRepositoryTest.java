package com.shadowfit.repository.exercise;

import com.shadowfit.dto.admin.AdminExerciseListItemDto;
import com.shadowfit.dto.admin.AdminExerciseSearchCondition;
import com.shadowfit.dto.admin.AdminExerciseSortKey;
import com.shadowfit.dto.common.PageResponse;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.Category;
import com.shadowfit.repository.exercise.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 운동 목록 동적 조회 검증 ({@code admin-page-scope.md} §3-C).
 *
 * <p>[왜 이 테스트가 필요한가] 필터가 2개뿐이라 회원 목록(32가지)만큼 조합이 많지는 않지만,
 * <b>"안 건 조건이 조용히 걸리거나, 건 조건이 조용히 빠지는"</b> 실패 방식은 똑같다. QueryDSL 의
 * {@code where(null)} 건너뛰기에 기대는 구조라 컴파일로는 아무것도 안 잡힌다.
 *
 * <p>특히 <b>목록과 총건수가 같은 조건을 쓰는지</b>가 여기서만 드러난다. 두 쿼리가 갈리면
 * "전체 3건"이라 써놓고 1건만 보여주는 화면이 되는데, 데이터가 적으면 한동안 아무도 모른다.
 */
@SpringBootTest
@Transactional
@DisplayName("관리자 운동 목록 동적 조회 테스트")
class ExerciseQueryRepositoryTest {

    @Autowired private ExerciseQueryRepository exerciseQueryRepository;
    @Autowired private ExercisesRepository exercisesRepository;
    @Autowired private CategoryRepository categoryRepository;

    private Category category;      // LOWER
    private Category categoryCore;
    private Category categoryFull;
    private Category categoryBack;

    @BeforeEach
    void setUp() {
        // 마이그레이션 시드(스쿼트·런지·플랭크)가 이미 들어 있어 조건 없는 조회의 기대값이
        // 흔들린다. 이 테스트는 자기가 넣은 것만 보고 판단한다.
        exercisesRepository.deleteAll();
        exercisesRepository.flush();

        category = categoryRepository.save(Category.builder().name("LOWER").build());
        categoryCore = categoryRepository.save(Category.builder().name("CORE").build());
        categoryFull = categoryRepository.save(Category.builder().name("FULL").build());
        categoryBack = categoryRepository.save(Category.builder().name("BACK").build());
    }

    @Nested
    @DisplayName("필터")
    class Filters {

        @Test
        @DisplayName("조건을 하나도 안 걸면 전부 나온다")
        void noCondition_returnsAll() {
            save("스쿼트", category);
            save("플랭크", categoryCore);

            PageResponse<AdminExerciseListItemDto> result = search(emptyCondition());

            assertThat(result.totalElements()).isEqualTo(2);
            assertThat(result.content()).extracting(AdminExerciseListItemDto::name)
                    .containsExactlyInAnyOrder("스쿼트", "플랭크");
        }

        @Test
        @DisplayName("카테고리 필터를 걸면 그 부위만 나온다")
        void categoryFilter_narrows() {
            save("스쿼트", category);
            save("런지", category);
            save("플랭크", categoryCore);

            PageResponse<AdminExerciseListItemDto> result =
                    search(new AdminExerciseSearchCondition(null, category.getId()));

            assertThat(result.totalElements()).isEqualTo(2);
            assertThat(result.content()).extracting(AdminExerciseListItemDto::name)
                    .containsExactlyInAnyOrder("스쿼트", "런지");
        }

        @Test
        @DisplayName("검색어는 운동명 부분일치다")
        void keyword_partialMatch() {
            save("바벨 스쿼트", category);
            save("플랭크", categoryCore);

            assertThat(search(new AdminExerciseSearchCondition("스쿼", null)).content())
                    .extracting(AdminExerciseListItemDto::name).containsExactly("바벨 스쿼트");
        }

        @Test
        @DisplayName("빈 문자열 검색어는 조건으로 취급하지 않는다 — 전부 나온다")
        void blankKeyword_isIgnored() {
            save("스쿼트", category);
            save("플랭크", categoryCore);

            assertThat(search(new AdminExerciseSearchCondition("   ", null)).totalElements())
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("두 필터를 같이 걸면 AND 로 좁혀진다")
        void bothFilters_areAnded() {
            save("바벨 스쿼트", category);
            save("스쿼트 점프", categoryFull);

            assertThat(search(new AdminExerciseSearchCondition("스쿼트", category.getId()))
                    .content())
                    .extracting(AdminExerciseListItemDto::name).containsExactly("바벨 스쿼트");
        }
    }

    @Nested
    @DisplayName("정렬·페이징")
    class SortAndPaging {

        @Test
        @DisplayName("총건수는 페이지 크기가 아니라 조건에 맞는 전체 건수다")
        void totalElements_isNotPageSize() {
            save("스쿼트", category);
            save("런지", category);
            save("플랭크", categoryCore);

            PageResponse<AdminExerciseListItemDto> result = exerciseQueryRepository.searchForAdmin(
                    emptyCondition(), AdminExerciseSortKey.NAME, true, 0, 2);

            assertThat(result.content()).hasSize(2);
            assertThat(result.totalElements()).isEqualTo(3);
            assertThat(result.totalPages()).isEqualTo(2);
        }

        @Test
        @DisplayName("NAME 오름차순 정렬")
        void sortByName_ascending() {
            save("플랭크", categoryCore);
            save("런지", category);
            save("데드리프트", categoryBack);

            PageResponse<AdminExerciseListItemDto> result = exerciseQueryRepository.searchForAdmin(
                    emptyCondition(), AdminExerciseSortKey.NAME, true, 0, 20);

            assertThat(result.content()).extracting(AdminExerciseListItemDto::name)
                    .containsExactly("데드리프트", "런지", "플랭크");
        }

        @Test
        @DisplayName("페이지를 넘겨도 같은 행이 두 번 나오지 않는다 — 2차 정렬 키(id)")
        void paging_isStableWhenSortValuesTie() {
            // 이름이 같은 3행. 1차 키만으로는 순서가 미정이라 페이지 경계에서 어긋날 수 있다.
            save("동명", category);
            save("동명", category);
            save("동명", category);

            var first = exerciseQueryRepository.searchForAdmin(
                    emptyCondition(), AdminExerciseSortKey.NAME, true, 0, 2);
            var second = exerciseQueryRepository.searchForAdmin(
                    emptyCondition(), AdminExerciseSortKey.NAME, true, 1, 2);

            assertThat(first.content()).hasSize(2);
            assertThat(second.content()).hasSize(1);
            assertThat(first.content()).extracting(AdminExerciseListItemDto::id)
                    .doesNotContainAnyElementsOf(
                            second.content().stream().map(AdminExerciseListItemDto::id).toList());
        }
    }

    // ─── 헬퍼 ──────────────────────────────────────────────────────────────────────

    private AdminExerciseSearchCondition emptyCondition() {
        return new AdminExerciseSearchCondition(null, null);
    }

    private PageResponse<AdminExerciseListItemDto> search(AdminExerciseSearchCondition condition) {
        return exerciseQueryRepository.searchForAdmin(
                condition, AdminExerciseSortKey.CREATED_AT, false, 0, 20);
    }

    private void save(String name, Category category) {
        exercisesRepository.save(Exercise.builder().name(name).category(category).build());
        exercisesRepository.flush();
    }
}