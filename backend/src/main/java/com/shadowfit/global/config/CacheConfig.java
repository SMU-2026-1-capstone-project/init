package com.shadowfit.global.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine 캐시 배선.
 *
 * <p><b>{@code spring.cache.caffeine.spec} 프로퍼티 방식에서 이 클래스로 옮겼다.</b> 프로퍼티
 * 방식은 {@code cache-names} 로 등록한 모든 캐시에 <b>같은 spec 하나</b>만 적용한다 — 카탈로그
 * 3종({@code feedbackTemplates}·{@code exercises}·{@code exerciseReferences})과 관리자 대시보드
 * 집계 캐시가 목적이 다른데(카탈로그는 "안 변하는 데이터의 안전판", 대시보드는 "정합성을
 * 정해진 시간만큼 포기"), 같은 spec을 쓰면 둘 중 하나가 틀린 값을 갖는다.
 *
 * <p><b>{@code adminDashboardStats}</b>: {@code AdminStatsService#statusDistribution()} 전용
 * (`docs/decisions/performance-tactics-availability-tradeoff.md` §3 택틱A, `admin-page-scope.md`
 * §4-5-2 ④의 b). TTL 5분은 새로 지어낸 값이 아니라 `admin-page-scope.md` §2("관리자 대시보드
 * 숫자는 몇 분 늦어도 된다")를 해석한 `redis-adoption.md` §5·§9의 기존 "1~5분" 범위 중 상한을
 * 그대로 채택한 것. 무효화는 <b>TTL 만료만</b>이다 — {@code @CacheEvict} 를 걸지 않는 이유는
 * 세션 상태 전이 지점이 {@code SessionService}·{@code ExerciseAnalysisService} 등 여러 곳이라
 * 하나라도 빠뜨리면 "고쳤다고 착각한 채 stale"이 남고, 트레이드오프 문서 §3 택틱A가 이미
 * "캐시 미스·만료 시 기존 DB 경로로 자연 후퇴, 새 실패모드 없음"을 채택 근거로 쓰고 있어
 * evict 배선은 그 근거와 어긋난다. {@code maximumSize=1} 은 파라미터가 없는 메서드라 가능한
 * 키가 정확히 하나뿐이라서다 — 여유를 준 게 아니라 실제 카디널리티를 그대로 쓴 값.
 */
@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();

        Caffeine<Object, Object> catalogSpec = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.HOURS)
                .maximumSize(500)
                .recordStats();
        Caffeine<Object, Object> adminDashboardStatsSpec = Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(1)
                .recordStats();

        manager.registerCustomCache("feedbackTemplates", catalogSpec.build());
        manager.registerCustomCache("exercises", catalogSpec.build());
        manager.registerCustomCache("exerciseReferences", catalogSpec.build());
        manager.registerCustomCache("adminDashboardStats", adminDashboardStatsSpec.build());

        return manager;
    }
}
