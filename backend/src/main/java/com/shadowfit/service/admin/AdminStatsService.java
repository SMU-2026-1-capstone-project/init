package com.shadowfit.service.admin;

import com.shadowfit.dto.admin.AdminStatsOverviewDto;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.repository.exercise.SessionRepository;
import com.shadowfit.repository.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 관리자 대시보드 집계 ({@code admin-page-scope.md} §3-D).
 *
 * <p>실시간 판으로 시작해 시간 수치를 먼저 잰 뒤(§2-1), 비용의 대부분이 {@link #statusDistribution()}
 * 하나(b, 전체 기간 {@code GROUP BY} — 인덱스로 줄일 여지가 없다, §4-5-2 ③)로 좁혀진 것을
 * 확인하고서 그 메서드에만 Caffeine 캐시를 얹었다({@code performance-tactics-availability-tradeoff.md}
 * §3 택틱A, 2026-09-04 사용자 confirm). 나머지 필드(오늘 시작 세션 수 등)는 "오늘" 기준이라
 * 캐싱하면 당일 신선도가 깨지고, 애초에 비용도 크지 않아 캐싱 대상에서 뺐다.
 *
 * <p>패키지가 {@code service.admin} 인 것은 이 서비스가 회원·세션 두 도메인에 걸쳐 있어
 * 기존 {@code service.Member}·{@code service.Exercise} 어느 쪽에도 넣기 어색해서다.
 */
@Service
@RequiredArgsConstructor
public class AdminStatsService {

    /**
     * 활성 회원 판정 기간.
     *
     * <p>7일인 근거는 <b>운동 주기가 주 단위</b>라는 것이다 — 주 2~3회 하는 사람이 하루
     * 쉬었다고 비활성으로 떨어지면 안 되고, 반대로 30일을 잡으면 한 달 전에 한 번 들어온
     * 사람까지 활성으로 세어 숫자가 실제 사용을 반영하지 못한다.
     *
     * <p>🔶 <b>이 값은 근거는 있으나 검증되지 않았다.</b> 실사용 데이터로 재접속 간격
     * 분포를 보기 전에는 7 이 맞는지 알 수 없다. 응답에 {@code activeMemberWindowDays} 로
     * 같이 내려보내는 이유가 이것이다 — 화면이 "활성 153명"이 아니라 "최근 7일 153명"으로
     * 읽히게 해야 나중에 값을 바꿔도 뜻이 어긋나지 않는다.
     */
    public static final int ACTIVE_MEMBER_WINDOW_DAYS = 7;

    private final SessionRepository sessionRepository;
    private final MemberRepository memberRepository;

    @Transactional(readOnly = true)
    public AdminStatsOverviewDto getOverview() {
        // "오늘"은 서버 타임존 기준이다. JDBC URL 이 serverTimezone=Asia/Seoul 이고
        // 엔티티가 LocalDateTime 이라 DB 와 앱이 같은 벽시계를 본다 (application.yml:17).
        LocalDate today = LocalDate.now();
        LocalDateTime dayStart = today.atStartOfDay();
        // 종료 경계는 '다음 날 00:00 미만'이다. loe(오늘 23:59:59) 로 잡으면 그 1초 사이가
        // 빠진다 — A·B 목록 필터가 쓰는 규칙과 같다 (MemberQueryRepositoryImpl#joinedLt).
        LocalDateTime dayEnd = today.plusDays(1).atStartOfDay();
        LocalDateTime activeWindowStart = dayEnd.minusDays(ACTIVE_MEMBER_WINDOW_DAYS);

        return new AdminStatsOverviewDto(
                today,
                sessionRepository.countStartedBetween(dayStart, dayEnd),
                statusDistribution(),
                sessionRepository.averageSyncRateOfCompletedBetween(dayStart, dayEnd),
                memberRepository.countJoinedBetween(dayStart, dayEnd),
                sessionRepository.countDistinctActiveMembersBetween(activeWindowStart, dayEnd),
                ACTIVE_MEMBER_WINDOW_DAYS
        );
    }

    /**
     * 상태별 분포를 <b>모든 상태가 있는</b> 맵으로 만든다.
     *
     * <p>{@code GROUP BY} 결과에는 한 건이라도 있는 상태만 나온다. 그대로 내보내면 화면에서
     * 항목이 사라졌다 나타났다 하고, 특히 "실패 0건"이 <b>표시되지 않는 것</b>과 구분되지
     * 않는다. 0 을 채워 내려보내는 편이 읽는 쪽이 안전하다.
     *
     * <p>{@link EnumMap} 이라 순서는 enum 선언 순서(IN_PROGRESS → COMPLETED → CANCELLED →
     * FAILED)로 고정된다. 화면이 매번 같은 순서로 그려진다.
     *
     * <p>캐싱은 여기가 아니라 {@link SessionRepository#countGroupedByStatus()} 에 걸려 있다 —
     * 이 메서드는 {@code private} 이고 같은 클래스의 {@link #getOverview()} 에서 내부 호출되므로
     * Spring AOP 프록시(self-invocation)를 못 타 {@code @Cacheable} 을 여기 걸면 조용히
     * 무시된다. 기존 카탈로그 3종 캐시도 서비스가 아니라 레포지토리 메서드에 걸려 있는 것과
     * 같은 이유·같은 자리.
     */
    private Map<Status, Long> statusDistribution() {
        Map<Status, Long> distribution = new EnumMap<>(Status.class);
        for (Status status : Status.values()) {
            distribution.put(status, 0L);
        }

        List<Object[]> rows = sessionRepository.countGroupedByStatus();
        for (Object[] row : rows) {
            distribution.put((Status) row[0], (Long) row[1]);
        }
        return distribution;
    }
}
