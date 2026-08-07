package com.shadowfit.global;

import com.shadowfit.dto.report.record.Mood;
import com.shadowfit.model.exercise.ExerciseCategory;
import com.shadowfit.model.exercise.Status;
import com.shadowfit.model.member.SelectedPersona;
import com.shadowfit.model.member.Sex;
import com.shadowfit.model.outbox.OutboxStatus;
import com.shadowfit.model.report.ReportType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스키마 ENUM 컬럼과 Java enum 상수 이름의 일치를 단언한다 — issue #106 회귀 방지.
 *
 * <p><b>왜 이 테스트가 따로 필요한가.</b> 나머지 테스트는 이 부류를 <b>구조적으로 못 잡는다.</b>
 * 테스트 DB 는 {@code ddl-auto: create-drop}({@code src/test/resources/application.yml}) 이라
 * 스키마를 <b>Java 엔티티에서 생성</b>한다. 즉 Java 와 마이그레이션 정본({@code V1__baseline.sql})이 어긋나도
 * 테스트 쪽에는 언제나 Java 기준 스키마가 만들어져 초록불이 뜬다. 스키마 소스가 둘인데
 * 테스트는 한쪽만 본다 — 이 테스트만 나머지 한쪽인 마이그레이션 파일을 직접 읽는다.
 *
 * <p><b>어긋나면 무슨 일이 나는가.</b> 엔티티가 {@code @Enumerated(EnumType.STRING)} 이라
 * 상수 이름이 그대로 저장된다. ENUM 목록에 없는 문자열은
 * <ul>
 *   <li>쓰기 — strict 모드에서 INSERT/UPDATE 실패 (시끄럽게 터진다)</li>
 *   <li>읽기 — 매칭 실패로 <b>조용히 0건</b> (에러가 아니다). #106 이 이 경우였다</li>
 * </ul>
 *
 * <p>ENUM 의 <b>선언 순서</b>는 단언하지 않는다 — 문자열로 저장하므로 소속만 맞으면 되고,
 * 순서는 MySQL 에서 {@code ORDER BY} 대상일 때만 의미가 있다.
 */
class SchemaEnumConsistencyTest {

    /**
     * {@code 테이블.컬럼} → 그 컬럼에 매핑된 Java enum.
     *
     * <p>새 ENUM 컬럼이 스키마에 생기면 여기에 추가해야 한다 — 빠뜨리면
     * {@link #모든_ENUM_컬럼이_매핑표에_있다()} 가 실패한다. 매핑표가 스스로 낡지 않게 하는 장치다.
     */
    private static final Map<String, Class<? extends Enum<?>>> MAPPING = new LinkedHashMap<>() {{
        put("users.sex", Sex.class);
        put("users.selected_persona", SelectedPersona.class);
        put("exercises.category", ExerciseCategory.class);
        put("exercise_sessions.status", Status.class);
        put("daily_logs.mood", Mood.class);
        put("reports.report_type", ReportType.class);
        put("outbox_events.status", OutboxStatus.class);
    }};

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w+)`?", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENUM_COLUMN = Pattern.compile(
            "^\\s*`?(\\w+)`?\\s+ENUM\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    private static final Map<String, Set<String>> SCHEMA_ENUMS = parseSchemaEnums();

    static Stream<Object[]> 매핑() {
        return MAPPING.entrySet().stream().map(e -> new Object[]{e.getKey(), e.getValue()});
    }

    @ParameterizedTest(name = "{0} ↔ {1}")
    @DisplayName("스키마 ENUM 값과 Java enum 상수 이름이 일치한다")
    @MethodSource("매핑")
    void 스키마와_자바가_일치한다(String column, Class<? extends Enum<?>> enumType) {
        Set<String> schemaValues = SCHEMA_ENUMS.get(column);
        assertThat(schemaValues)
                .as("%s 컬럼이 schema.sql 에 ENUM 으로 없다 — 매핑표가 낡았거나 컬럼 타입이 바뀌었다", column)
                .isNotNull();

        Set<String> javaNames = Arrays.stream(enumType.getEnumConstants())
                .map(Enum::name)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        assertThat(javaNames)
                .as("%s: @Enumerated(STRING) 이라 상수 이름이 그대로 저장된다. "
                        + "어긋나면 쓰기는 실패하고 읽기는 조용히 0건이다 (issue #106)", enumType.getSimpleName())
                .containsExactlyInAnyOrderElementsOf(schemaValues);
    }

    @Test
    @DisplayName("schema.sql 의 모든 ENUM 컬럼이 매핑표에 있다 — 새 컬럼이 감시망을 빠져나가지 못하게")
    void 모든_ENUM_컬럼이_매핑표에_있다() {
        assertThat(SCHEMA_ENUMS.keySet())
                .as("schema.sql 에 ENUM 컬럼이 하나도 안 잡혔다면 파싱이 깨진 것이다")
                .isNotEmpty();
        assertThat(SCHEMA_ENUMS.keySet())
                .as("매핑표에 없는 ENUM 컬럼 — 이 테스트에 추가하거나, Java enum 이 붙지 않는 컬럼이면 그 사실을 여기 주석으로 남길 것")
                .containsExactlyInAnyOrderElementsOf(MAPPING.keySet());
    }

    /** {@code CREATE TABLE} 을 만나면 현재 테이블을 갱신하며 ENUM 컬럼을 모은다. */
    private static Map<String, Set<String>> parseSchemaEnums() {
        Map<String, Set<String>> found = new LinkedHashMap<>();
        String currentTable = null;

        for (String line : readSchemaLines()) {
            Matcher table = CREATE_TABLE.matcher(line);
            if (table.find()) {
                currentTable = table.group(1);
                continue;
            }
            Matcher column = ENUM_COLUMN.matcher(line);
            if (currentTable != null && column.find()) {
                found.put(currentTable + "." + column.group(1), splitEnumValues(column.group(2)));
            }
        }
        return found;
    }

    /** {@code 'A', 'B'} → {@code [A, B]} */
    private static Set<String> splitEnumValues(String raw) {
        return Arrays.stream(raw.split(","))
                .map(v -> v.trim().replaceAll("^'|'$", ""))
                .filter(v -> !v.isEmpty())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /** 스키마 정본. Flyway 도입 전에는 {@code mysql/schema.sql} 이었다 (이슈 #115). */
    private static final String BASELINE_RESOURCE = "/db/migration/V1__baseline.sql";

    /**
     * 스키마 정본을 <b>클래스패스에서</b> 읽는다.
     *
     * <p>예전에는 작업 디렉터리에서 위로 거슬러 올라가며 {@code mysql/schema.sql} 을 찾았다 —
     * Gradle 은 {@code backend/} 를, IDE 는 저장소 루트를 작업 디렉터리로 잡아서였다.
     * Flyway 도입으로 정본이 {@code src/main/resources} 아래로 들어오면서 그 탐색이 필요
     * 없어졌다. 리소스로 잡으면 작업 디렉터리와 무관하게 항상 같은 파일을 읽는다.
     *
     * <p>못 찾으면 <b>실패</b>시킨다 — 조용히 건너뛰면 가드가 사라진 줄도 모른다.
     */
    private static List<String> readSchemaLines() {
        try (InputStream in = SchemaEnumConsistencyTest.class.getResourceAsStream(BASELINE_RESOURCE)) {
            if (in == null) {
                throw new AssertionError(
                        BASELINE_RESOURCE + " 을 클래스패스에서 찾지 못했다. 마이그레이션 파일이 옮겨졌거나 "
                                + "이름이 바뀌었는지 확인할 것 — 이 테스트가 보는 유일한 스키마 소스다.");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().toList();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(BASELINE_RESOURCE + " 을 읽지 못했다", e);
        }
    }
}
