# 이 디렉터리는 이력 보관용이다 — 더 이상 여기에 추가하지 않는다

2026-08-07 Flyway 도입([이슈 #115](https://github.com/Shadowfit/init/issues/115),
[`docs/decisions/schema-migration-tracking.md`](../../docs/decisions/schema-migration-tracking.md))
이후 **새 스키마 변경은 여기가 아니라 Flyway 마이그레이션으로 간다.**

```
backend/src/main/resources/db/migration/V3__…sql   ← 새 변경은 여기
```

## 여기 있는 파일들은 뭔가

Flyway 가 없던 시절, `schema.sql` 이 `CREATE TABLE IF NOT EXISTS` 라 **이미 만들어진 DB 에는
신규 컬럼·인덱스가 반영되지 않아서** 기존 인스턴스에 손으로 적용하려고 만든 파일들이다.

문제는 그걸 **적용했는지 아무 데도 안 남았다**는 것이었다. 실제로 두 건이 빠진 채로 남아
`UPDATE ... SET last_active_at` 이 `Unknown column` 으로 실패했다 — 그게 #115 다.

## 🔴 다시 실행하지 말 것

여기 있는 6개는 **전부 `V1__baseline.sql` 에 이미 반영돼 있다.** V1 은 이 변경들이 모두
적용된 뒤의 최종 상태를 캡처한 것이다. 다시 돌리면:

- `1060` Duplicate column — 컬럼이 이미 있다
- `1061` Duplicate key name — 인덱스가 이미 있다
- `1091` Can't DROP … — 지울 인덱스가 이미 없다

## 그럼 왜 안 지우나

각 파일에 **왜 그 변경이 필요했는지**가 주석으로 남아 있다 — 인덱스 컬럼 순서를 그렇게
고른 이유, 컬럼을 NULL 허용으로 둔 이유, 온라인 DDL 의 잠금 특성 같은 것들. V1 은 결과만
담고 있어서 그 판단 과정이 안 보인다. 결정 근거를 잃지 않으려고 남긴다.

## 적용 이력은 이제 어디서 보나

```sql
SELECT version, description, script, installed_on, success
FROM flyway_schema_history ORDER BY installed_rank;
```

또는 앱이 떠 있으면 `GET /actuator/flyway`.
