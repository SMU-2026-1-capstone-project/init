package com.shadowfit.repository.member;

import com.shadowfit.dto.admin.AdminMemberListItemDto;
import com.shadowfit.dto.admin.AdminMemberSearchCondition;
import com.shadowfit.dto.admin.AdminMemberSortKey;
import com.shadowfit.dto.common.PageResponse;
import com.shadowfit.model.member.Member;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.WorkoutLevel;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 회원 목록 동적 조회 검증 ({@code admin-page-scope.md} §3-A).
 *
 * <p>[왜 이 테스트가 필요한가] 필터 5개의 부분집합 32가지가 모두 유효한 조회다. 조건을 안 걸면
 * 사라지고 걸면 붙는 구조라, <b>"안 건 조건이 조용히 걸리거나, 건 조건이 조용히 빠지는"</b> 실패가
 * 컴파일로도 눈으로도 잡히지 않는다. 특히 아래 두 가지는 실행해봐야만 알 수 있다.
 *
 * <ul>
 *   <li><b>가입일 종료 경계</b> — 종료일 당일에 가입한 회원이 포함되는가. {@code loe(atStartOfDay)}
 *       로 잘못 짜면 그날 00:00:00 인 회원만 걸리고 나머지가 통째로 빠지는데, 데이터가 적으면
 *       한동안 아무도 눈치채지 못한다.</li>
 *   <li><b>운동 레벨 NULL</b> — 온보딩 전 회원은 레벨이 없다. 레벨 필터를 걸었을 때 빠지고,
 *       안 걸었을 때는 나와야 한다.</li>
 * </ul>
 *
 * <p>createdAt 은 {@code @CreationTimestamp} 라 저장 시각으로 덮이므로, 가입일 시나리오는
 * 저장 후 JPQL 로 직접 갱신해 원하는 값으로 만든다.
 */
@SpringBootTest
@Transactional
@DisplayName("관리자 회원 목록 동적 조회 테스트")
class MemberQueryRepositoryTest {

    private static final LocalDate JOIN_DAY = LocalDate.of(2026, 3, 10);

    @Autowired private MemberQueryRepository memberQueryRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private EntityManager em;

    @BeforeEach
    void setUp() {
        memberRepository.deleteAll();
        memberRepository.flush();
    }

    @Nested
    @DisplayName("필터")
    class Filters {

        @Test
        @DisplayName("조건을 하나도 안 걸면 전원이 나온다")
        void noCondition_returnsAll() {
            save("alice", "alice@t.com", SelectedPersona.BEGINNER, WorkoutLevel.STARTER, true);
            save("bob", "bob@t.com", SelectedPersona.DIET, null, false);

            PageResponse<AdminMemberListItemDto> result = search(emptyCondition());

            assertThat(result.totalElements()).isEqualTo(2);
            assertThat(result.content()).extracting(AdminMemberListItemDto::username)
                    .containsExactlyInAnyOrder("alice", "bob");
        }

        @Test
        @DisplayName("검색어는 username 과 email 양쪽에 부분일치한다")
        void keyword_matchesUsernameOrEmail() {
            save("alice", "alice@t.com", SelectedPersona.BEGINNER, WorkoutLevel.STARTER, true);
            save("bob", "target@t.com", SelectedPersona.DIET, null, false);
            save("carol", "carol@t.com", SelectedPersona.REHAB, null, false);

            // username 으로 걸리는 경우
            assertThat(search(conditionWithKeyword("ali")).content())
                    .extracting(AdminMemberListItemDto::username).containsExactly("alice");

            // email 로만 걸리는 경우 — username 에는 없는 문자열
            assertThat(search(conditionWithKeyword("target")).content())
                    .extracting(AdminMemberListItemDto::username).containsExactly("bob");
        }

        /**
         * 대소문자 무시가 <b>DB 컬레이션에서 나온다</b>는 것을 못박는 테스트.
         *
         * <p>원래 코드는 {@code containsIgnoreCase()} 였고, 그건 {@code lower(username) like ?}
         * 를 만든다. 그런데 이 프로젝트의 컬레이션은 {@code utf8mb4_unicode_ci} 라 <b>이미
         * 대소문자를 무시한다</b> — {@code lower()} 는 결과를 바꾸지 않으면서 컬럼에 함수만
         * 씌워, 접두 검색으로 바꿔도 인덱스를 못 타게 만들고 있었다(issue #105, 실측은
         * {@code admin-page-scope.md} §4-3 ④).
         *
         * <p>그래서 {@code contains()} 로 바꿨는데, 그 순간 <b>대소문자 무시가 코드에서
         * 사라지고 DB 설정에 의존하게 된다.</b> 컬레이션을 {@code _bin} 이나 {@code _as_cs} 로
         * 바꾸면 동작이 조용히 달라지므로, 그 전제를 여기서 실행으로 잡는다.
         */
        @Test
        @DisplayName("검색어는 대소문자를 무시한다 — 코드가 아니라 DB 컬레이션이 보장한다")
        void keyword_isCaseInsensitive_byCollation() {
            save("Alice", "Alice@T.com", SelectedPersona.BEGINNER, WorkoutLevel.STARTER, true);

            assertThat(search(conditionWithKeyword("alice")).content())
                    .extracting(AdminMemberListItemDto::username).containsExactly("Alice");
            assertThat(search(conditionWithKeyword("ALICE")).content())
                    .extracting(AdminMemberListItemDto::username).containsExactly("Alice");
        }

        @Test
        @DisplayName("페르소나·온보딩 여부를 함께 걸면 둘 다 만족하는 회원만 나온다")
        void multipleConditions_areAnded() {
            save("a", "a@t.com", SelectedPersona.DIET, WorkoutLevel.STARTER, true);
            save("b", "b@t.com", SelectedPersona.DIET, null, false);
            save("c", "c@t.com", SelectedPersona.REHAB, WorkoutLevel.STARTER, true);

            AdminMemberSearchCondition condition = new AdminMemberSearchCondition(
                    null, SelectedPersona.DIET, null, true, null, null);

            assertThat(search(condition).content())
                    .extracting(AdminMemberListItemDto::username).containsExactly("a");
        }
    }

    @Nested
    @DisplayName("운동 레벨 NULL — 온보딩 전 회원")
    class WorkoutLevelNull {

        @Test
        @DisplayName("레벨 필터를 걸면 레벨 없는 회원은 빠진다")
        void levelFilter_excludesNullLevel() {
            save("leveled", "l@t.com", SelectedPersona.BEGINNER, WorkoutLevel.STARTER, true);
            save("nolevel", "n@t.com", SelectedPersona.BEGINNER, null, false);

            AdminMemberSearchCondition condition = new AdminMemberSearchCondition(
                    null, null, WorkoutLevel.STARTER, null, null, null);

            assertThat(search(condition).content())
                    .extracting(AdminMemberListItemDto::username).containsExactly("leveled");
        }

        @Test
        @DisplayName("레벨 필터를 안 걸면 레벨 없는 회원도 나온다 — 조회 수단이 비지 않는다")
        void noLevelFilter_includesNullLevel() {
            save("nolevel", "n@t.com", SelectedPersona.BEGINNER, null, false);

            assertThat(search(emptyCondition()).content())
                    .extracting(AdminMemberListItemDto::username).containsExactly("nolevel");

            // 온보딩 여부로도 찾을 수 있어야 한다
            AdminMemberSearchCondition byOnboarding = new AdminMemberSearchCondition(
                    null, null, null, false, null, null);
            assertThat(search(byOnboarding).content())
                    .extracting(AdminMemberListItemDto::username).containsExactly("nolevel");
        }
    }

    @Nested
    @DisplayName("가입일 범위 — 경계 포함 여부")
    class JoinedRange {

        @BeforeEach
        void seedJoinDates() {
            save("dayStart", "s@t.com", SelectedPersona.BEGINNER, null, false);
            save("dayEnd", "e@t.com", SelectedPersona.BEGINNER, null, false);
            save("nextDay", "x@t.com", SelectedPersona.BEGINNER, null, false);

            setCreatedAt("dayStart", JOIN_DAY.atStartOfDay());
            setCreatedAt("dayEnd", JOIN_DAY.atTime(23, 59, 59));
            setCreatedAt("nextDay", JOIN_DAY.plusDays(1).atStartOfDay());
        }

        @Test
        @DisplayName("종료일 당일 늦은 시각에 가입한 회원도 포함된다")
        void joinedTo_includesWholeDay() {
            AdminMemberSearchCondition condition = new AdminMemberSearchCondition(
                    null, null, null, null, JOIN_DAY, JOIN_DAY);

            assertThat(search(condition).content())
                    .extracting(AdminMemberListItemDto::username)
                    .containsExactlyInAnyOrder("dayStart", "dayEnd");
        }

        @Test
        @DisplayName("종료일 다음 날 가입자는 제외된다")
        void joinedTo_excludesNextDay() {
            AdminMemberSearchCondition condition = new AdminMemberSearchCondition(
                    null, null, null, null, null, JOIN_DAY);

            assertThat(search(condition).content())
                    .extracting(AdminMemberListItemDto::username)
                    .doesNotContain("nextDay");
        }

        @Test
        @DisplayName("시작일 당일 00:00:00 가입자는 포함된다")
        void joinedFrom_includesStartOfDay() {
            AdminMemberSearchCondition condition = new AdminMemberSearchCondition(
                    null, null, null, null, JOIN_DAY, null);

            assertThat(search(condition).content())
                    .extracting(AdminMemberListItemDto::username)
                    .contains("dayStart");
        }
    }

    @Nested
    @DisplayName("정렬·페이징")
    class SortAndPaging {

        @Test
        @DisplayName("총건수는 페이지 크기가 아니라 조건에 맞는 전체 건수다")
        void totalElements_countsAllMatching() {
            for (int i = 0; i < 5; i++) {
                save("user" + i, "u" + i + "@t.com", SelectedPersona.BEGINNER, null, false);
            }

            PageResponse<AdminMemberListItemDto> firstPage =
                    memberQueryRepository.searchForAdmin(
                            emptyCondition(), AdminMemberSortKey.USERNAME, true, 0, 2);

            assertThat(firstPage.content()).hasSize(2);
            assertThat(firstPage.totalElements()).isEqualTo(5);
            assertThat(firstPage.totalPages()).isEqualTo(3);
        }

        @Test
        @DisplayName("페이지를 넘겨도 같은 회원이 두 번 나오지 않는다")
        void paging_hasNoOverlap() {
            for (int i = 0; i < 5; i++) {
                save("user" + i, "u" + i + "@t.com", SelectedPersona.BEGINNER, null, false);
            }

            List<String> page0 = usernamesOf(0, 2);
            List<String> page1 = usernamesOf(1, 2);
            List<String> page2 = usernamesOf(2, 2);

            assertThat(page0).hasSize(2);
            assertThat(page1).hasSize(2);
            assertThat(page2).hasSize(1);
            assertThat(page0).doesNotContainAnyElementsOf(page1);
            assertThat(page1).doesNotContainAnyElementsOf(page2);
        }

        @Test
        @DisplayName("username 오름차순 정렬이 적용된다")
        void sortByUsername_ascending() {
            save("charlie", "c@t.com", SelectedPersona.BEGINNER, null, false);
            save("alice", "a@t.com", SelectedPersona.BEGINNER, null, false);
            save("bob", "b@t.com", SelectedPersona.BEGINNER, null, false);

            PageResponse<AdminMemberListItemDto> result =
                    memberQueryRepository.searchForAdmin(
                            emptyCondition(), AdminMemberSortKey.USERNAME, true, 0, 20);

            assertThat(result.content()).extracting(AdminMemberListItemDto::username)
                    .containsExactly("alice", "bob", "charlie");
        }

        private List<String> usernamesOf(int page, int size) {
            return memberQueryRepository
                    .searchForAdmin(emptyCondition(), AdminMemberSortKey.USERNAME, true, page, size)
                    .content().stream()
                    .map(AdminMemberListItemDto::username)
                    .toList();
        }
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private PageResponse<AdminMemberListItemDto> search(AdminMemberSearchCondition condition) {
        return memberQueryRepository.searchForAdmin(
                condition, AdminMemberSortKey.CREATED_AT, false, 0, 20);
    }

    private AdminMemberSearchCondition emptyCondition() {
        return new AdminMemberSearchCondition(null, null, null, null, null, null);
    }

    private AdminMemberSearchCondition conditionWithKeyword(String keyword) {
        return new AdminMemberSearchCondition(keyword, null, null, null, null, null);
    }

    private void save(String username, String email, SelectedPersona persona,
                      WorkoutLevel level, boolean onboardingCompleted) {
        Member member = Member.builder()
                .username(username)
                .email(email)
                .password("encoded-password")
                .selectedPersona(persona)
                .workoutLevel(level)
                .onboardingCompleted(onboardingCompleted)
                .build();
        memberRepository.save(member);
    }

    /** {@code @CreationTimestamp} 가 덮어쓴 가입일을 시나리오 값으로 되돌린다. */
    private void setCreatedAt(String username, LocalDateTime createdAt) {
        memberRepository.flush();
        em.createQuery("UPDATE Member m SET m.createdAt = :createdAt WHERE m.username = :username")
                .setParameter("createdAt", createdAt)
                .setParameter("username", username)
                .executeUpdate();
        em.clear();
    }
}
