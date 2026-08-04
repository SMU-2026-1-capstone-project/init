package com.shadowfit.repository.exercise;

import com.shadowfit.dto.admin.AdminSessionListItemDto;
import com.shadowfit.dto.admin.AdminSessionSearchCondition;
import com.shadowfit.dto.admin.AdminSessionSortKey;
import com.shadowfit.dto.common.PageResponse;
import com.shadowfit.model.exercise.Exercise;
import com.shadowfit.model.exercise.ExerciseCategory;
import com.shadowfit.model.exercise.Session;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.repository.member.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 세션 목록 동적 조회 검증 ({@code admin-page-scope.md} §3-B).
 *
 * <p>[회원 목록 테스트와 무엇이 다른가] 구조는 같지만 <b>조인이 생겼다.</b> 그래서 회원 목록에는
 * 없던 실패 두 가지가 가능해진다.
 *
 * <ul>
 *   <li><b>총건수의 조건부 조인</b> — {@code countOf()} 는 검색어가 있을 때만 {@code member} 를
 *       조인한다. 조건과 조인이 어긋나면 총건수가 목록과 달라지는데, <b>양쪽 다 숫자는 나오므로</b>
 *       눈으로는 안 잡힌다. 이 클래스에서 가장 중요한 검증이다.</li>
 *   <li><b>프로젝션 채움</b> — 회원명·운동명이 조인 너머에서 온다. 조인을 빠뜨려도 컴파일은
 *       되고 null 이 실려 나갈 수 있다.</li>
 * </ul>
 *
 * <p>가입일과 달리 {@code startTime} 은 {@code @CreationTimestamp} 가 아니라 직접 지정하는
 * 값이라, 시나리오 시각을 그대로 저장한다 — 회원 테스트처럼 나중에 UPDATE 로 되돌릴 필요가 없다.
 */
@SpringBootTest
@Transactional
@DisplayName("관리자 세션 목록 동적 조회 테스트")
class SessionQueryRepositoryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);

    @Autowired private SessionQueryRepository sessionQueryRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ExercisesRepository exercisesRepository;

    private Exercise squat;
    private Exercise pushup;

    @BeforeEach
    void setUp() {
        sessionRepository.deleteAll();
        memberRepository.deleteAll();
        exercisesRepository.deleteAll();
        sessionRepository.flush();

        squat = saveExercise("스쿼트", ExerciseCategory.LOWER);
        pushup = saveExercise("푸시업", ExerciseCategory.UPPER);
    }

    @Nested
    @DisplayName("조인 — 회원 목록에 없던 실패 지점")
    class Join {

        @Test
        @DisplayName("회원명·운동명이 조인 너머에서 채워진다")
        void projection_fillsJoinedColumns() {
            Member hong = saveMember("hong", "hong@t.com");
            saveSession(hong, squat, DAY.atTime(10, 0), Status.COMPLETED, 30, "75.00");

            AdminSessionListItemDto row = search(emptyCondition()).content().get(0);

            assertThat(row.username()).isEqualTo("hong");
            assertThat(row.exerciseName()).isEqualTo("스쿼트");
            assertThat(row.memberId()).isEqualTo(hong.getId());
            assertThat(row.exerciseId()).isEqualTo(squat.getId());
        }

        @Test
        @DisplayName("검색어는 조인 너머의 username·email 양쪽에 걸린다")
        void keyword_matchesJoinedMemberColumns() {
            Member hong = saveMember("hong", "hong@t.com");
            Member kim = saveMember("kim", "target@t.com");
            saveSession(hong, squat, DAY.atTime(10, 0), Status.COMPLETED, 30, "75.00");
            saveSession(kim, squat, DAY.atTime(11, 0), Status.COMPLETED, 20, "80.00");

            assertThat(search(conditionWithKeyword("hon")).content())
                    .extracting(AdminSessionListItemDto::username).containsExactly("hong");

            // username 에는 없고 email 에만 있는 문자열
            assertThat(search(conditionWithKeyword("target")).content())
                    .extracting(AdminSessionListItemDto::username).containsExactly("kim");
        }

        @Test
        @DisplayName("검색어를 걸어도 총건수와 목록이 어긋나지 않는다 — count 의 조건부 조인")
        void count_matchesContent_whenKeywordJoinsMember() {
            Member kim = saveMember("kim", "kim@t.com");
            Member lee = saveMember("lee", "lee@t.com");
            // kim 3건 / lee 2건 — 검색어로 kim 만 걸리면 총건수가 3 이어야 한다.
            saveSession(kim, squat, DAY.atTime(9, 0), Status.COMPLETED, 10, "70.00");
            saveSession(kim, squat, DAY.atTime(10, 0), Status.COMPLETED, 20, "75.00");
            saveSession(kim, pushup, DAY.atTime(11, 0), Status.FAILED, 5, null);
            saveSession(lee, squat, DAY.atTime(12, 0), Status.COMPLETED, 30, "80.00");
            saveSession(lee, pushup, DAY.atTime(13, 0), Status.COMPLETED, 40, "85.00");

            PageResponse<AdminSessionListItemDto> result = search(conditionWithKeyword("kim"));

            assertThat(result.totalElements()).isEqualTo(3);
            assertThat(result.content()).hasSize(3);
            assertThat(result.content())
                    .allSatisfy(row -> assertThat(row.username()).isEqualTo("kim"));
        }

        @Test
        @DisplayName("검색어가 없으면 총건수는 전체를 센다 — 조인을 안 붙여도 값이 같다")
        void count_withoutKeyword_countsAll() {
            Member kim = saveMember("kim", "kim@t.com");
            Member lee = saveMember("lee", "lee@t.com");
            saveSession(kim, squat, DAY.atTime(9, 0), Status.COMPLETED, 10, "70.00");
            saveSession(lee, squat, DAY.atTime(10, 0), Status.COMPLETED, 20, "75.00");

            assertThat(search(emptyCondition()).totalElements()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("기간 필터 경계")
    class PeriodBoundary {

        @Test
        @DisplayName("종료일 당일 늦은 시각에 시작한 세션도 포함된다")
        void endDate_includesWholeDay() {
            Member hong = saveMember("hong", "hong@t.com");
            saveSession(hong, squat, DAY.atTime(23, 59, 59), Status.COMPLETED, 30, "75.00");

            AdminSessionSearchCondition condition =
                    new AdminSessionSearchCondition(null, null, DAY, DAY, null);

            assertThat(search(condition).totalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("종료일 다음 날 00:00:00 은 빠진다")
        void endDate_excludesNextDay() {
            Member hong = saveMember("hong", "hong@t.com");
            saveSession(hong, squat, DAY.plusDays(1).atStartOfDay(), Status.COMPLETED, 30, "75.00");

            AdminSessionSearchCondition condition =
                    new AdminSessionSearchCondition(null, null, DAY, DAY, null);

            assertThat(search(condition).totalElements()).isZero();
        }

        @Test
        @DisplayName("시작일 당일 00:00:00 은 포함된다")
        void startDate_includesMidnight() {
            Member hong = saveMember("hong", "hong@t.com");
            saveSession(hong, squat, DAY.atStartOfDay(), Status.COMPLETED, 30, "75.00");

            AdminSessionSearchCondition condition =
                    new AdminSessionSearchCondition(null, null, DAY, DAY, null);

            assertThat(search(condition).totalElements()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("필터 조합")
    class Filters {

        @Test
        @DisplayName("조건을 하나도 안 걸면 전부 나온다")
        void noCondition_returnsAll() {
            Member hong = saveMember("hong", "hong@t.com");
            saveSession(hong, squat, DAY.atTime(9, 0), Status.COMPLETED, 10, "70.00");
            saveSession(hong, pushup, DAY.atTime(10, 0), Status.FAILED, 0, null);

            assertThat(search(emptyCondition()).totalElements()).isEqualTo(2);
        }

        @Test
        @DisplayName("상태 필터가 걸리면 그 상태만 나온다")
        void statusFilter() {
            Member hong = saveMember("hong", "hong@t.com");
            saveSession(hong, squat, DAY.atTime(9, 0), Status.COMPLETED, 10, "70.00");
            saveSession(hong, squat, DAY.atTime(10, 0), Status.FAILED, 0, null);

            AdminSessionSearchCondition condition =
                    new AdminSessionSearchCondition(Status.FAILED, null, null, null, null);

            assertThat(search(condition).content())
                    .extracting(AdminSessionListItemDto::status).containsExactly(Status.FAILED);
        }

        @Test
        @DisplayName("운동 종목 필터는 조인 없이 세션의 FK 로 걸린다")
        void exerciseFilter() {
            Member hong = saveMember("hong", "hong@t.com");
            saveSession(hong, squat, DAY.atTime(9, 0), Status.COMPLETED, 10, "70.00");
            saveSession(hong, pushup, DAY.atTime(10, 0), Status.COMPLETED, 20, "75.00");

            AdminSessionSearchCondition condition =
                    new AdminSessionSearchCondition(null, pushup.getId(), null, null, null);

            assertThat(search(condition).content())
                    .extracting(AdminSessionListItemDto::exerciseName).containsExactly("푸시업");
        }

        @Test
        @DisplayName("여러 조건은 AND 로 묶인다")
        void conditionsAreAnded() {
            Member kim = saveMember("kim", "kim@t.com");
            Member lee = saveMember("lee", "lee@t.com");
            saveSession(kim, squat, DAY.atTime(9, 0), Status.FAILED, 0, null);      // 정답
            saveSession(kim, squat, DAY.atTime(10, 0), Status.COMPLETED, 10, "70.00"); // 상태 불일치
            saveSession(lee, squat, DAY.atTime(11, 0), Status.FAILED, 0, null);     // 회원 불일치

            AdminSessionSearchCondition condition = new AdminSessionSearchCondition(
                    Status.FAILED, squat.getId(), DAY, DAY, "kim");

            PageResponse<AdminSessionListItemDto> result = search(condition);

            assertThat(result.totalElements()).isEqualTo(1);
            assertThat(result.content().get(0).username()).isEqualTo("kim");
        }
    }

    @Nested
    @DisplayName("정렬·페이징")
    class SortAndPaging {

        @Test
        @DisplayName("기본 정렬은 시작시각 최신순이다")
        void defaultSort_isLatestFirst() {
            Member hong = saveMember("hong", "hong@t.com");
            saveSession(hong, squat, DAY.atTime(9, 0), Status.COMPLETED, 10, "70.00");
            saveSession(hong, squat, DAY.atTime(11, 0), Status.COMPLETED, 30, "90.00");
            saveSession(hong, squat, DAY.atTime(10, 0), Status.COMPLETED, 20, "80.00");

            assertThat(search(emptyCondition()).content())
                    .extracting(AdminSessionListItemDto::totalReps)
                    .containsExactly(30, 20, 10);
        }

        @Test
        @DisplayName("총건수는 페이지 크기가 아니라 조건에 맞는 전체 건수다")
        void totalElements_isNotPageSize() {
            Member hong = saveMember("hong", "hong@t.com");
            for (int i = 0; i < 5; i++) {
                saveSession(hong, squat, DAY.atTime(9, i), Status.COMPLETED, i, "70.00");
            }

            PageResponse<AdminSessionListItemDto> result =
                    sessionQueryRepository.searchForAdmin(
                            emptyCondition(), AdminSessionSortKey.START_TIME, false, 0, 2);

            assertThat(result.content()).hasSize(2);
            assertThat(result.totalElements()).isEqualTo(5);
            assertThat(result.totalPages()).isEqualTo(3);
        }

        @Test
        @DisplayName("싱크로율이 전부 null 이어도 페이지 경계에서 행이 중복되지 않는다")
        void nullSyncRate_doesNotBreakPaging() {
            // avgSyncRate 는 분석 결과가 회수되기 전이면 null 이다. 그 상태에서 이 값으로
            // 정렬하면 모든 행이 동점이 되어, 2차 정렬(id)이 없으면 페이지 경계가 흔들린다.
            Member hong = saveMember("hong", "hong@t.com");
            for (int i = 0; i < 6; i++) {
                saveSession(hong, squat, DAY.atTime(9, i), Status.IN_PROGRESS, i, null);
            }

            List<Long> firstPage = idsOf(0);
            List<Long> secondPage = idsOf(1);

            assertThat(firstPage).hasSize(3);
            assertThat(secondPage).hasSize(3);
            assertThat(firstPage).doesNotContainAnyElementsOf(secondPage);
        }

        private List<Long> idsOf(int page) {
            return sessionQueryRepository
                    .searchForAdmin(emptyCondition(), AdminSessionSortKey.AVG_SYNC_RATE, false, page, 3)
                    .content().stream()
                    .map(AdminSessionListItemDto::id)
                    .toList();
        }
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private PageResponse<AdminSessionListItemDto> search(AdminSessionSearchCondition condition) {
        return sessionQueryRepository.searchForAdmin(
                condition, AdminSessionSortKey.START_TIME, false, 0, 20);
    }

    private AdminSessionSearchCondition emptyCondition() {
        return new AdminSessionSearchCondition(null, null, null, null, null);
    }

    private AdminSessionSearchCondition conditionWithKeyword(String keyword) {
        return new AdminSessionSearchCondition(null, null, null, null, keyword);
    }

    private Member saveMember(String username, String email) {
        return memberRepository.save(Member.builder()
                .username(username)
                .email(email)
                .password("encoded-password")
                .selectedPersona(SelectedPersona.BEGINNER)
                .build());
    }

    private Exercise saveExercise(String name, ExerciseCategory category) {
        return exercisesRepository.save(Exercise.builder()
                .name(name)
                .category(category)
                .build());
    }

    private void saveSession(Member member, Exercise exercise, LocalDateTime startTime,
                             Status status, int totalReps, String avgSyncRate) {
        sessionRepository.save(Session.builder()
                .member(member)
                .exercise(exercise)
                .startTime(startTime)
                .status(status)
                .totalReps(totalReps)
                .avgSyncRate(avgSyncRate == null ? null : new BigDecimal(avgSyncRate))
                .build());
    }
}
