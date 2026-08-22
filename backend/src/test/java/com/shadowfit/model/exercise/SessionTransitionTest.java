package com.shadowfit.model.exercise;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 세션 상태 전이의 계약 — 엔티티만으로 검증한다(스프링 컨텍스트·DB 없음).
 *
 * <p>[왜 필요한가] 이 전이들은 이전까지 서비스 쪽 setter 호출 나열이었고, 그래서 "완료" 의 두 번째
 * 사본이 생겼을 때 아무 장치도 막지 못했다(이슈 #174·#179). 전이를 메서드로 올리면서 <b>가드와
 * 반환값이 도메인 계약</b>이 됐으므로, 서비스를 거치지 않고 그 계약 자체를 고정한다.
 *
 * <p>특히 반환값은 눈에 안 띄는 계약이다 — {@code false} 를 무시해도 컴파일되고, 그러면 재전송
 * 때마다 일일 통계가 다시 누적된다. 여기서 "안 바뀌었으면 false" 를 못박아 둔다.
 */
@DisplayName("Session 상태 전이")
class SessionTransitionTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 16, 10, 0);
    private static final LocalDateTime AT = LocalDateTime.of(2026, 8, 16, 10, 30);

    private Session inProgress() {
        return Session.builder()
                .startTime(START)
                .status(Status.IN_PROGRESS)
                .totalReps(0)
                .difficultyLevel(1)
                .build();
    }

    private SyncStats sync() {
        return SyncStats.of(82.5, 95.0, 40.0);
    }

    @Nested
    @DisplayName("complete")
    class Complete {

        @Test
        @DisplayName("IN_PROGRESS 세션을 완료로 확정하고 true 를 돌려준다")
        void 정상_전이() {
            Session s = inProgress();

            boolean transitioned = s.complete(12, sync(), BigDecimal.valueOf(120.5), AT);

            assertThat(transitioned).isTrue();
            assertThat(s.getStatus()).isEqualTo(Status.COMPLETED);
            assertThat(s.getEndTime()).isEqualTo(AT);
            assertThat(s.getTotalReps()).isEqualTo(12);
            assertThat(s.getAvgSyncRate()).isEqualByComparingTo("82.50");
            assertThat(s.getMaxSyncRate()).isEqualByComparingTo("95.00");
            assertThat(s.getMinSyncRate()).isEqualByComparingTo("40.00");
            assertThat(s.getCaloriesBurned()).isEqualByComparingTo("120.5");
        }

        @Test
        @DisplayName("이미 COMPLETED 면 false 를 돌려주고 첫 기록을 그대로 둔다 — 재전송 멱등")
        void 재전송_멱등() {
            Session s = inProgress();
            s.complete(12, sync(), BigDecimal.valueOf(120.5), AT);

            boolean transitioned = s.complete(99, SyncStats.of(10.0, 10.0, 10.0),
                    BigDecimal.valueOf(1.0), AT.plusMinutes(5));

            assertThat(transitioned)
                    .as("false 를 봐야 호출자가 일일 통계·리포트 선계산을 건너뛴다")
                    .isFalse();
            assertThat(s.getTotalReps()).isEqualTo(12);
            assertThat(s.getEndTime()).isEqualTo(AT);
            assertThat(s.getAvgSyncRate()).isEqualByComparingTo("82.50");
        }

        @Test
        @DisplayName("측정된 rep 이 없으면 싱크 통계는 0 이 아니라 null 이다 (#75)")
        void 측정_없음은_null() {
            Session s = inProgress();

            s.complete(0, SyncStats.none(), BigDecimal.ZERO, AT);

            assertThat(s.getAvgSyncRate()).isNull();
            assertThat(s.getMaxSyncRate()).isNull();
            assertThat(s.getMinSyncRate()).isNull();
        }
    }

    @Nested
    @DisplayName("markEnded")
    class MarkEnded {

        @Test
        @DisplayName("종료 시각만 찍고 status 는 IN_PROGRESS 그대로 둔다")
        void status는_안_바뀐다() {
            Session s = inProgress();

            boolean ended = s.markEnded(AT);

            assertThat(ended).isTrue();
            assertThat(s.getEndTime()).isEqualTo(AT);
            assertThat(s.getStatus())
                    .as("COMPLETED 전이는 AI 콜백 몫 — 여기서 바꾸면 '분석 대기 중' 구간이 사라진다")
                    .isEqualTo(Status.IN_PROGRESS);
        }

        @Test
        @DisplayName("이미 종료 시각이 있으면 false 를 돌려주고 첫 시각을 보존한다")
        void 두번째_호출은_멱등() {
            Session s = inProgress();
            s.markEnded(AT);

            boolean ended = s.markEnded(AT.plusMinutes(5));

            assertThat(ended).isFalse();
            assertThat(s.getEndTime()).isEqualTo(AT);
        }
    }

    @Nested
    @DisplayName("fail")
    class Fail {

        @Test
        @DisplayName("IN_PROGRESS 세션은 걷어낸다")
        void 진행중은_걷힌다() {
            Session s = inProgress();

            boolean failed = s.fail(AT);

            assertThat(failed).isTrue();
            assertThat(s.getStatus()).isEqualTo(Status.FAILED);
            assertThat(s.getEndTime()).isEqualTo(AT);
        }

        @Test
        @DisplayName("이미 완료된 세션은 FAILED 로 덮지 않는다 — 실제 운동 기록이 사라진다")
        void 완료된건_안_덮는다() {
            Session s = inProgress();
            s.complete(12, sync(), BigDecimal.valueOf(120.5), AT);

            boolean failed = s.fail(AT.plusMinutes(5));

            assertThat(failed).isFalse();
            assertThat(s.getStatus()).isEqualTo(Status.COMPLETED);
            assertThat(s.getTotalReps()).isEqualTo(12);
        }

        @Test
        @DisplayName("사용자가 종료를 눌렀지만 아직 IN_PROGRESS 인 세션은 걷힌다 — 타임아웃 safety net")
        void 종료눌렀는데_결과가_안_온_세션() {
            Session s = inProgress();
            s.markEnded(AT);

            boolean failed = s.fail(AT.plusMinutes(40));

            assertThat(failed).isTrue();
            assertThat(s.getStatus()).isEqualTo(Status.FAILED);
            assertThat(s.getEndTime())
                    .as("스케줄러가 찍은 시각으로 덮인다")
                    .isEqualTo(AT.plusMinutes(40));
        }
    }
}
