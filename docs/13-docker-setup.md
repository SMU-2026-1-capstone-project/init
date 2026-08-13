# Docker 설정 가이드

> ## 🔄 2026-08-12 — 이 문서에서 compose YAML 전문 복사본을 걷어냈다
>
> 이 문서는 `docker-compose.yml` 전문을 *"운영 중인 실제 설정"* 이라며 복사해 두고 있었는데,
> 감사해 보니 **9곳이 실물과 달랐다** — initdb 마운트 2줄(#115 로 삭제됨) · `mem_limit`(#164) ·
> `AI_PUBLIC_TOKEN`(#134) · 관측 스택 3서비스 전체 등. 같은 문서 아래쪽 갱신 노트가
> *"AI 8000 은 이제 열려 있다"* 고 정정해 둔 사실조차 위 YAML 블록에는 반영되지 않은 상태였다.
>
> **복사본은 원본이 움직이면 반드시 벌어진다.** 그래서 «무엇이 있는지» 는 파일을 가리키고,
> 이 문서는 **파일 주석에 안 들어가는 배경**만 남긴다.

## 사전 요구사항
- **Docker Desktop** 설치
  ```bash
  # Windows
  winget install Docker.DockerDesktop

  # 설치 후 Docker Desktop 실행 → WSL 2 활성화 확인
  docker --version
  docker compose version
  ```

---

## 구성

> 🔴 **단일 진실 원천은 [`docker-compose.yml`](../docker-compose.yml)** (배포용은 [`docker-compose.prod.yml`](../docker-compose.prod.yml)).
> 값·포트·환경변수는 **그 파일에서 확인한다.** 아래는 목차와 배경일 뿐이다.

| 서비스 | 컨테이너 | 기본 기동 | 역할 |
|---|---|---|---|
| `mysql` | `shadowfit-mysql` | ✅ | DB. 스키마는 **백엔드가 Flyway 로** 적용한다 |
| `shadowfit-backend` | `shadowfit-backend` | ✅ | REST 8080 · gRPC 6565 · 관리 9090 |
| `shadowfit-ai` | `shadowfit-ai` | ✅ | FastAPI 8000(외부) · gRPC 8585(내부) |
| `prometheus` | `shadowfit-prometheus` | ❌ `--profile obs` | 지표 수집, 보존 7일 |
| `grafana` | `shadowfit-grafana` | ❌ `--profile obs` | 대시보드(프로비저닝) |
| `mysqld-exporter` | `shadowfit-mysqld-exporter` | ❌ `--profile obs` | MySQL 내부 지표 |

⚠️ **서비스명과 컨테이너명이 다르다.** `docker compose` 명령은 **왼쪽(서비스명)** 을 받고,
`docker logs`·`docker exec` 는 **오른쪽(컨테이너명)** 을 받는다. 아래 명령어 절도 그 구분을 따른다.

### 왜 initdb 마운트가 없나 — #115

예전에는 `./mysql/schema.sql`·`data.sql` 을 `/docker-entrypoint-initdb.d/` 에 물려
**컨테이너 최초 기동 때 한 번** 실행했다. 문제는 그 «한 번» 이다 — 볼륨이 이미 있으면
두 번 다시 안 돌아서, 나중에 추가된 마이그레이션이 반영됐는지 아무도 몰랐다.
실제로 두 건이 빠진 채 남아 `UPDATE … SET last_active_at` 이 `Unknown column` 으로 실패했다.

지금은 **백엔드가 부팅하며 Flyway 로** `backend/src/main/resources/db/migration/` 을 적용하고,
적용 이력은 `flyway_schema_history` 에 남는다. compose 가 할 일은 **빈 DB 를 만드는 것뿐**이다.
→ [`05-database-design.md`](./05-database-design.md) §「스키마는 이제 Flyway 가 적용한다」

dev 픽스처(테스트 계정·가짜 세션)는 마이그레이션에 넣지 않는다. 필요할 때 손으로:
```bash
docker exec -i shadowfit-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" shadowfit < mysql/dev-seed.sql
```

### 왜 AI 는 8000 만 열려 있나 — 분기 H2 · #134

2026-05-24 **분기 H2(프론트 → AI 직결)** 채택으로 HTTP 8000 이 `ports` 로 돌아왔다.
gRPC 8585 는 Spring 만 같은 도커 네트워크에서 부르므로 `expose` 로 남는다.

인증은 `InternalAuthMiddleware`(`ai-server/app/middleware/auth.py`)가 담당하는데,
**여기에 결함이 있었고 절반만 고쳤다**([#134](https://github.com/Shadowfit/init/issues/134)):

- 예전엔 미들웨어가 요구하는 토큰이 Spring↔AI gRPC 와 **같은 `INTERNAL_API_TOKEN`** 이었고,
  프론트가 그걸 `EXPO_PUBLIC_` 로 들고 있었다 — 그 접두는 **앱 번들에 인라인**되므로
  내부 서비스 토큰이 모든 설치본에 들어갔다
- 2026-08-09 에 값을 분리했다([`ai-auth-token-flow.md`](./decisions/ai-auth-token-flow.md) ㄱ):
  HTTP 는 `AI_PUBLIC_TOKEN`, gRPC 양방향은 `INTERNAL_API_TOKEN`. **내부 토큰은 이제 클라이언트로 안 나간다**
- 🔴 **남은 것**: `AI_PUBLIC_TOKEN` 도 번들에 인라인된다 — **토큰을 추출한 누구나 `POST /pose` 를 부를 수 있다.**
  피해가 AI 세션 상태로 한정될 뿐 호출자 신원은 여전히 없다. 근본 해결(세션 단위 단기 토큰)은 **미결정**

### `mem_limit` 은 어디서 나온 값인가 — #164

AI 컨테이너에 메모리 한도가 걸려 있다. 한도가 없으면 세션 수만큼 커지다 호스트가 바닥나고,
커널 OOM killer 가 **«가장 큰» 프로세스**를 죽인다 — 보통 MySQL(`buffer_pool 2G`)이다.
**원인과 증상이 다른 컨테이너에서 나타나** 진단이 가장 어려운 형태가 된다. 한도를 걸면 AI 만 죽는다.

그리고 이 값이 «동시 세션 상한» 의 근거가 된다 — 검출기 1개 = **98.7MB**(실측,
[`loadtest/results/detector-memory-2026-08-11/`](../loadtest/results/detector-memory-2026-08-11/))이므로
상한을 여기서 **계산**할 수 있고, 그래야 코드에 근거 없는 숫자를 안 박는다.

⚠️ **호스트 RAM 만 올리고 이 캡을 안 풀면 그대로 OOM 이 재발한다** — 둘은 같이 조정한다.
EC2 용 준비값은 파일 주석에 있으나 **미검증**이다(배포 대상이 아직 0대).

### 관측 스택은 왜 기본으로 안 뜨나

profile 로 뺀 이유는 취향이 아니라 **측정 오염**이다. 이 개발 장비는 2물리코어에
MySQL+백엔드+AI 가 이미 동거 중이라, 부하 실험 중 관측 스택까지 돌면 무엇 때문에 느려졌는지
구분할 수 없게 된다. **그래프를 보려고 켠 것이 그래프를 못 믿게 만드는** 상황을 만들지 않는다.

```bash
docker compose --profile obs up -d                       # 켜기
docker compose --profile obs stop prometheus grafana     # 끄기 (관측만)
```

🔴 **`--profile obs down` 을 쓰지 말 것** ([#131](https://github.com/Shadowfit/init/issues/131)).
`--profile` 은 **올릴 대상을 고르는 옵션이라 내릴 때 범위를 좁히지 않는다** — `--dry-run` 으로 확인한
실제 대상은 **backend·ai·mysql 까지 정지 후 제거**다. 상세: [`../monitoring/README.md`](../monitoring/README.md).

---

## 포트 노출 정책

| 컨테이너 | 호스트 노출 | 컨테이너간 통신 |
|---|---|---|
| `shadowfit-mysql` | 3306 (개발 편의) | `shadowfit-mysql:3306` |
| `shadowfit-backend` | 8080(REST) · 6565(gRPC) · **`127.0.0.1:9090`(관리·지표, dev 만)** | 위와 동일 |
| `shadowfit-ai` | **8000** (분기 H2) | `shadowfit-ai:8000` · `shadowfit-ai:8585` |
| `shadowfit-prometheus` | `127.0.0.1:9091` (profile `obs`) | 컨테이너 9090 ↔ 호스트 9091 |
| `shadowfit-grafana` | `127.0.0.1:3000` (profile `obs`) | — |
| `shadowfit-mysqld-exporter` | **없음** (의도) | Prometheus 가 `:9104` 를 긁는다 |

**관리 포트 9090 을 8080 에서 분리한 이유** — `/actuator/prometheus` 를 인증 없이 열어야 하는데
8080 은 외부 노출이라 지표가 그대로 공개된다.

| | dev | prod |
|---|---|---|
| 9090 매핑 | ✅ `127.0.0.1:9090` | ❌ **매핑 안 함** (의도) |
| Prometheus·Grafana | profile `obs` (기본 미기동) | 없음 (미결) |

🔴 **호스트에 여는 것은 전부 `127.0.0.1:` 을 붙인다** ([#128](https://github.com/Shadowfit/init/issues/128)).
인터페이스를 안 적으면 도커는 **모든 인터페이스**에 바인딩하므로 같은 LAN 의 누구나 지표를
무인증으로 읽고, Grafana 는 `.env` 가 없으면 **admin/admin** 이다. 실제로 도입 당일 그 상태였다.

📌 **보호 수단이 «인증» 이 아니라 «네트워크 경계» 하나라는 점을 알고 있어야 한다.**
`/actuator/prometheus` 는 시큐리티 whitelist 에 있다(필터체인이 두 포트에 공유되기 때문에
넣어야 Prometheus 가 긁는다). **9090 을 어딘가에 노출하면 whitelist 가 그대로 뚫린다.**

---

## Backend Dockerfile

실물은 [`backend/Dockerfile`](../backend/Dockerfile). 요점만:

- **멀티스테이지** — `gradle:jdk21` 에서 빌드하고 산출 jar 만 `eclipse-temurin:21-jre` 로 옮긴다
- 빌드는 `./gradlew bootJar -x test` — **래퍼**를 쓴다(`chmod +x` 가 앞에 붙는 건 그래서다).
  테스트는 이미지 빌드에서 빼고 CI 가 따로 돌린다
- `EXPOSE` 는 **8080 과 6565 둘 다** — 6565 는 AI → Spring gRPC 콜백 수신용이라 빠지면 안 된다

무시 목록은 [`backend/.dockerignore`](../backend/.dockerignore). `.git`·`build`·`out` 외에
`Dockerfile`·`docker-compose.yml` 자신과 **`../.env`** 가 들어 있다.

---

## 주요 Docker 명령어

> `docker compose <명령> <서비스명>` 과 `docker <명령> <컨테이너명>` 은 **받는 이름이 다르다**(위 표).

### 개발 환경 (MySQL만 Docker로)
```bash
docker compose up -d mysql            # MySQL 만 실행
docker logs shadowfit-mysql           # 로그 (컨테이너명)
docker compose stop mysql             # 중지

# MySQL 접속 — 비밀번호는 .env 의 MYSQL_ROOT_PASSWORD
docker exec -it shadowfit-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD"
```

### AI 서버
```bash
docker compose up -d shadowfit-ai     # 🔴 서비스명은 shadowfit-ai (ai-server 아님)
docker logs shadowfit-ai
curl http://localhost:8000/health     # 헬스체크
# Swagger: http://localhost:8000/docs
```

### 전체
```bash
docker compose up -d --build          # 전체 빌드 & 실행
docker compose logs -f                # 로그 추적
docker compose ps                     # 상태
docker compose restart shadowfit-backend         # 🔴 backend 아님
docker compose build --no-cache shadowfit-backend
docker compose down                   # 전체 중지
```

🔴 **`docker compose down -v` 는 «MySQL 초기화» 가 아니다.** `-v` 는 이름있는 볼륨을 전부 지우므로
`mysql_data`·`prometheus_data`·`grafana_data` 가 **같이 날아가고**, `down` 자체가 스택 전체를 내린다.
DB 만 비우려면 컨테이너를 살려둔 채 스키마를 다시 만드는 편이 안전하다.

---

## 개발 시 권장 구성
- **MySQL**: Docker 컨테이너로 실행 (항상)
- **Spring Boot**: 로컬에서 `./gradlew bootRun` (핫 리로딩)
- **AI Server**: 로컬에서 `uvicorn app.main:app --reload --port 8000`
- **React Native**: 로컬에서 `npx expo start`

코드 변경이 빨리 반영되면서 MySQL 만 Docker 로 깔끔하게 관리된다.
절차 상세는 [`14-how-to-run.md`](./14-how-to-run.md).

## DB 연결 설정

**dev/prod 두 벌이 아니라 한 벌 + 환경변수**다 — [`application.yml`](../backend/src/main/resources/application.yml):

```yaml
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST:shadowfit-mysql}:${DB_PORT:3306}/${DB_NAME:shadowfit}?...
    username: ${DB_USERNAME:shadowfit}
    password: ${DB_PASSWORD:shadowfit}
```

- **기본값이 컨테이너명**(`shadowfit-mysql`)인 이유는 도커 네트워크 안이 기본 실행 환경이기 때문이다.
  **로컬에서 `bootRun` 할 때는 `DB_HOST=localhost`** — [`.env.example`](../.env.example) 이 그 값으로 되어 있다
- URL 에 `rewriteBatchedStatements=true` 가 붙어 있다. JDBC 드라이버가 batch INSERT 를
  multi-row SQL 한 방으로 재작성한다(부하테스트 개선)
- ⚠️ **`application.properties` 에 datasource·`ddl-auto`·whitelist 를 다시 넣지 말 것.**
  properties 가 yml 을 덮어써서 yml 수정이 조용히 무시된다 — 실제로 `ddl-auto` 가 그렇게 `update` 로
  이겨 Hibernate 가 스키마를 자동 변경한 적이 있다(2026-07-15). 파일 안에 경고 주석이 붙어 있다