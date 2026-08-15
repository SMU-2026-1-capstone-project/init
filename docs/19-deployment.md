# 배포 가이드

마지막 업데이트: 2026-08-07
범위: ShadowFit 배포 절차, 환경 변수 관리, 롤백 전략. 운영 호스팅이 아직 정해지지 않아 **본 문서는 "지금 가능한 절차" + "결정해야 할 사항"** 두 축으로 구성합니다.

> 🔴 **가장 먼저 알아야 할 것**: 배포 대상 호스트가 없다. 상시 EC2 인스턴스가 없고
> (2026-07-25 풀 사이징에 쓴 2대, **2026-08-08 격자 재측정에 쓴 3대** 모두 실측 후 삭제)
> AWS 연결도 아직이다. 그래서 CD 는
> **이미지 빌드·푸시까지만 자동**이고 배포는 수동이다 ([§3.0](#30-cd-파이프라인--어디까지-자동인가)).

---

## 1. 현재 알려진 배포 형태

| 항목 | 현재 상태 |
|------|---------|
| 배포 방식 | `docker compose -f docker-compose.prod.yml pull && up -d` (단일 노드 가정) |
| 컨테이너 | `shadowfit-mysql`, `shadowfit-backend`, `shadowfit-ai` |
| 이미지 | GHCR — `ghcr.io/shadowfit/init/{backend,ai-server}` (CI 가 빌드·푸시) |
| 외부 노출 | Backend REST 8080, gRPC 6565. AI HTTP 8000(미들웨어 보호), gRPC 8585 는 `expose` 만 |
| MySQL 외부 노출 | **없음** (운영 compose). dev compose 만 `${MYSQL_PORT:-3306}` 노출 |
| 스키마 | **Flyway** — 백엔드 부팅 시 적용, 이력은 `flyway_schema_history` ([§2.3](#23-db-스키마-변경--flyway-가-맡는다)) |
| 영속성 | `mysql_data` named volume 한 개 |
| CI | ✅ backend·ai-server 테스트, proto 동기화 검사 |
| CD | 🔶 **이미지 빌드·푸시까지만.** 배포 job 미구현 — 대상 호스트 없음 ([§3.0](#30-cd-파이프라인--어디까지-자동인가)) |
| 모니터링 | **미설정** (actuator health·metrics 는 노출됨) |
| 로깅 | 컨테이너 stdout (`docker logs`) |
| 시크릿 관리 | `.env` (gitignore), 운영에는 `.env` 파일을 호스트에 직접 둠. GHCR 푸시는 `GITHUB_TOKEN` |

> ⚠️ `docker-compose.prod.yml` 은 **실제 호스트에서 검증되지 않았다.** 대상이 없어서다.
> 문법·구성은 맞춰뒀지만(`docker compose config` 통과) "돌려봤다"고 말할 수 없다.

> 운영 호스팅(AWS/GCP/온프레미스/기타)·도메인·HTTPS 인증서·CDN 등은 본 저장소 코드만으로 판단 불가. **TODO** 표시한 절은 사용자가 채우거나, 결정 후 [`decisions/`](./decisions/) 에 별도 문서로 정리.

---

## 2. 배포 전 체크리스트

### 2.1 환경 변수
`.env` 파일(`E:\init\.env`) 에 아래 값이 채워져 있어야 함. 자세한 의미는 `.env.example` 참조.

| 변수 | 용도 | 운영 주의 |
|------|------|---------|
| `MYSQL_ROOT_PASSWORD` | MySQL root 비밀번호 | 강력한 무작위 |
| `MYSQL_DATABASE` | DB 이름 (`shadowfit`) | 통상 변경 불필요 |
| `MYSQL_USER`, `MYSQL_PASSWORD` | 앱 DB 계정 | 운영용 비밀번호 |
| `MYSQL_PORT` | 호스트 노출 포트 | 운영에서는 호스트 노출 자체를 피하는 게 권장 |
| `DB_USERNAME`, `DB_PASSWORD` | Spring 측 DB 접속 (= 위와 동일) | 양쪽 동기 필수 |
| `JWT_SECRET` | JWT 서명 키, **Base64 32바이트 이상** | 깃 절대 X. 환경별로 다른 값 |
| `INTERNAL_API_TOKEN` | Spring ↔ AI gRPC 내부 인증 | 양쪽 컨테이너에 동일 값 주입 |
| `AI_PUBLIC_TOKEN` | 프론트 → AI HTTP 직결 전용 | 🔴 위 값과 **달라야** 한다 (이슈 #134). 앱 번들에 인라인되므로 비밀 아님 |
| `AI_MEM_LIMIT` | AI 컨테이너 메모리 한도 | 🔴 **운영 필수.** 없으면 `up`·헬스체크는 통과하고 **첫 세션에서 죽는다** (#214) — 아래 |
| `POSE_DETECTOR_POOL_SIZE` | 검출기 풀 크기 (선택) | `0` = 위 한도에서 유도(권장). 한도를 못 거는 형태에서만 직접 지정 |
| `OPENAI_API_KEY` | GPT 피드백 (선택) | 발급 후 주입 |

> 🔴 **`AI_MEM_LIMIT` 이 왜 «선택» 이 아닌가** — 검출기 풀이 크기를 이 한도에서 유도하고
> (`ai-server/app/core/mediapipe_detector.py:285-293`), 한도도 `POSE_DETECTOR_POOL_SIZE` 도
> 없으면 **기동을 거부한다**(근거 없는 기본값을 박지 않겠다는 의도적 설계). 그런데 그 호출은
> 기동이 아니라 **StartAnalysis 에서 처음** 일어나므로(`exercise_servicer.py:110`), 증상이
> **배포 직후가 아니라 첫 사용자에게** 나타난다. 그래서 `docker-compose.prod.yml` 은 기본값을
> 두지 않고 명시를 요구한다.
>
> **값 유도(실측)**: 한도 = 기본 RSS 100.5MB + 검출기 98.7MB × 동시 세션 수.
> 실패 방향은 비대칭이다 — 작게 주면 «세션이 덜 받아짐»(돌긴 한다), 크게 주면 «안 뜸».
> 🔴 **운영 호스트가 아직 0대라 «몇 MB» 는 §7 배포 형태 결정에 달려 있다.**

### 2.2 시크릿 로테이션 정책 — **TODO**
- JWT_SECRET 로테이션 주기: ? (제안: 분기 1회 + 사고 시 즉시)
- INTERNAL_API_TOKEN 로테이션 주기: ? (제안: 분기 1회, 무중단 절차 별도 결정)
- OPENAI_API_KEY 로테이션: 발급 정책에 따름

### 2.3 DB 스키마 변경 — Flyway 가 맡는다

**손으로 ALTER 를 치지 않는다.** 백엔드가 부팅하며 미적용 마이그레이션을 적용한다
([이슈 #115](https://github.com/Shadowfit/init/issues/115), [`decisions/schema-migration-tracking.md`](./decisions/schema-migration-tracking.md)).

변경 절차:

1. `backend/src/main/resources/db/migration/` 에 **새 파일**을 추가한다 — `V3__…sql`, `V4__…sql`
2. JPA `@Entity` 와 일치 확인 + 테스트 통과 (`./gradlew :backend:test`)
3. 배포하면 부팅 시 자동 적용된다. 별도 작업 없음

> 🔴 **이미 적용된 파일은 고치지 않는다.** Flyway 가 checksum 으로 감시하므로 내용이 바뀌면
> 다음 부팅이 실패한다. 되돌리려면 되돌리는 내용의 새 버전을 추가한다.

**적용 상태 확인**:

```bash
# dev — 9090 이 루프백에 매핑돼 있다 (docker-compose.yml)
curl -s http://localhost:9090/actuator/flyway | jq '.contexts[].flywayBeans.flyway.migrations[] | {version, description, state}'

# prod — 9090 을 호스트에 매핑하지 않는다(의도된 설계). 컨테이너 안에서 부른다
docker exec shadowfit-backend wget -qO- http://localhost:9090/actuator/flyway \
  | jq '.contexts[].flywayBeans.flyway.migrations[] | {version, description, state}'

# 어느 환경이든 되는 경로 — DB 를 직접 본다
docker exec -it shadowfit-mysql mysql -u"$MYSQL_USER" -p shadowfit \
  -e "SELECT version, description, installed_on, success FROM flyway_schema_history ORDER BY installed_rank;"
```

> 🔴 **8080 이 아니다** ([이슈 #130](https://github.com/Shadowfit/init/issues/130)). 2026-08-08 관리 포트
> 분리로 액추에이터가 9090 으로 옮겨갔는데 이 절차가 8080 을 시키고 있었다. 8080 에 같은 경로를
> 치면 핸들러가 없어 **404** 다(그 전엔 500 이었고, [#129](https://github.com/Shadowfit/init/issues/129) 로 404 가 됐다).
>
> ⚠️ **prod 는 HTTP 경로가 `docker exec` 뿐이다.** 그래서 Flyway 도입(#115)의 목적이던 *"적용 이력을
> 추적 가능한 형태로"* 가 prod 에서는 컨테이너 안에 들어가야만 닿는다. 9090 을 prod 에서 루프백
> (`127.0.0.1:9090:9090`)에 매핑할지는 *"prod 에 관측 스택을 올릴지"* 미결과 같은 자리라 열어둔다
> ([`tasks/28-remaining-work-plan.md`](./tasks/28-remaining-work-plan.md) §7). **결정 전까지는 위 세 번째
> 명령(DB 직접 조회)이 prod 의 1순위 확인 경로다** — 두 번째 명령은 백엔드 이미지에 `wget` 이 있다는
> 전제에 기대는데(healthcheck 가 그걸 쓴다), 그 healthcheck 자체가 실제 호스트에서 검증된 적이 없다.

> ⚠️ Flyway 가 답하는 것은 **"내 파일이 돌았나"** 까지다. 누가 손으로 `ALTER TABLE` 을 치면
> 여기엔 아무것도 안 남는다 — 드리프트 탐지는 별건이고 미도입 상태다.

---

## 3. 배포 절차 (현재)

### 3.0 CD 파이프라인 — 어디까지 자동인가

**이미지 빌드·푸시까지 자동, 배포는 수동이다.** 배포 대상 호스트가 없어서다
(상시 EC2 인스턴스 없음 — 2026-07-25 풀 사이징 2대 · 2026-08-08 격자 재측정 3대,
전부 실측 후 삭제. 실험용으로 띄웠다 지우는 패턴이 두 번 반복됐다).

```
main 에 push
   ↓
테스트 게이트          ← backend-test.yml / ai-server-test.yml 을 workflow_call 로 재사용
   ↓ (통과해야만)
이미지 빌드 → GHCR     ← .github/workflows/cd-backend.yml, cd-ai-server.yml
   ↓
[ 여기서 끊긴다 ]      ← deploy job 미구현
   ↓
수동 배포              ← §3.2
```

산출물:

| 이미지 | 태그 |
|---|---|
| `ghcr.io/shadowfit/init/backend` | `latest`, `sha-<full-commit-sha>` |
| `ghcr.io/shadowfit/init/ai-server` | 〃 |

`latest` 는 "지금 main" 을 가리키는 **이동 태그**라 무엇이 떠 있는지 특정하지 못한다.
되짚을 수 있어야 하는 배포에는 `sha-` 태그를 쓴다.

> **deploy job 을 지금 만들지 않은 이유**: 대상 호스트도 자격증명도 없어 **검증할 수 없는
> 코드**가 되기 때문이다. 붙일 자리와 필요한 것은 `cd-backend.yml` 맨 아래 주석에 적어뒀다.

### 3.1 첫 배포
```bash
# 1) 코드·.env 준비
git clone ...
cd init
cp .env.example .env
vim .env                                  # 운영 값 입력 (§2.1 — 시크릿에 폴백이 없다)

# 2) 이미지 받아서 기동 (빌드하지 않는다 — CI 가 만든 것을 쓴다)
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d

# 3) 헬스체크 — prod 는 9090 을 호스트에 매핑하지 않으므로 ps 의 healthy 가 1순위다
docker compose -f docker-compose.prod.yml ps      # 셋 다 'healthy' (컨테이너가 안에서 9090 을 친다)
docker exec shadowfit-backend wget -qO- http://localhost:9090/actuator/health   # 내용까지 보려면

# 4) 스키마가 적용됐는지 — 여기서 Flyway 결과를 본다 (§2.3 에 세 경로 비교)
docker exec -it shadowfit-mysql mysql -u"$MYSQL_USER" -p shadowfit \
  -e "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
docker exec -it shadowfit-mysql mysql -u"$MYSQL_USER" -p shadowfit -e "SELECT id, name FROM exercises;"
```

> 🔴 **여기서 `curl localhost:8080/actuator/…` 를 치지 않는다** ([#130](https://github.com/Shadowfit/init/issues/130)).
> 액추에이터는 9090 이고 prod 는 그 포트를 호스트에 매핑하지 않는다 — 8080 에 치면 404 다.

> 마스터 데이터(`exercises`, 피드백 템플릿)는 Flyway 가 넣는다. **dev 픽스처(테스트 계정·
> 가짜 세션)는 안 들어간다** — 의도적이다. 운영에 필요 없고 있으면 안 되는 데이터다.

### 3.2 코드 업데이트 배포
```bash
# CI 가 이미 이미지를 올려뒀다. 호스트에서는 받아서 갈아끼우기만 한다.
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml logs -f shadowfit-backend   # Flyway 적용 로그 포함

# 특정 커밋으로 고정 (권장)
IMAGE_TAG=sha-<full-commit-sha> docker compose -f docker-compose.prod.yml up -d
```

> 스키마 변경이 포함된 배포도 별도 절차가 없다 — 백엔드가 뜨면서 Flyway 가 적용한다.
> 실패하면 **애플리케이션이 뜨지 않는다**(부팅 실패). 반쯤 적용된 채로 서비스가 도는 것보다
> 낫다는 판단이고, 그래서 배포 직후 `/actuator/flyway` 확인이 §3.1-4 에 있다.

### 3.3 무중단 배포 — **TODO**
현재 `docker compose up -d` 는 컨테이너 재기동 시 짧은 다운타임 발생. 무중단이 필요해지면:
- Blue-green: 두 번째 backend 컨테이너를 다른 포트로 띄우고 로드밸런서 전환
- Rolling: 단일 노드에서는 어려움 — 멀티 노드 전제
> 무중단 요구 시점·요구 수준이 정해지면 [`decisions/`](./decisions/) 에 결정 문서.

---

## 4. 롤백 절차

### 4.1 코드 롤백
```bash
git log --oneline -10                     # 직전 정상 커밋 SHA 확인
git checkout <previous-sha>
docker compose build
docker compose up -d
```

### 4.2 DB 롤백
스키마 변경 후 문제 발생 시:
- ALTER 적용 전 백업이 있다면 해당 백업으로 복구
- 백업 없으면 reverse ALTER 수동 작성 + 실행
- `mysql_data` 볼륨 삭제 (`docker compose down -v`) 는 **모든 운영 데이터가 날아감** — 절대 운영에서 사용 금지

> 운영 DB 백업 정책 — **TODO**: 백업 빈도, 백업 위치(S3/등), 복구 RTO/RPO 미정. 결정 필요.

---

## 5. 모니터링·로깅 — 🔄 절반은 됐다 (2026-08-08 갱신)

~~현재는 `docker logs <컨테이너명>` 으로 stdout 만 보는 수준.~~ **dev 에는 관측 스택이 있다.** 다만 **prod 구성에는 아직 없다** — 그 결정이 §5-1 이다.

| 영역 | 후보 도구 | 상태 (2026-08-08) |
|------|---------|---------|
| **Spring Actuator** | `actuator/health`·`metrics`·`flyway`·`prometheus` | ✅ **완료.** 🔴 **관리 포트 9090 으로 분리** — 8080 은 외부 노출이라 지표를 열면 공개된다. prod compose 는 9090 을 **매핑하지 않는다** |
| **메트릭 (RPS·풀·JVM + 커스텀 9종)** | Prometheus + Grafana | ✅ **dev 완료** (compose profile `obs`, 대시보드 JSON 프로비저닝) — [`../monitoring/README.md`](../monitoring/README.md). 🔶 **prod 미적용** (§5-1) |
| **correlation id 전파** | MDC + `%X{cid}` | ✅ **완료** — HTTP·gRPC 양방향·`@Async`·스케줄러. 두 서비스 로그를 한 요청으로 이어 본다 |
| 애플리케이션 로그 집계 | ELK, Loki | ❌ 미도입. 로그는 여전히 컨테이너 stdout. **JSON 구조화도 안 했다** — 수집기를 붙일 때 같이 한다 |
| **AI 서버 지표** | (Prometheus 타깃) | ❌ **의도적 제외.** FastAPI 에 계측이 없어 타깃만 적으면 영원히 DOWN 인 타깃이 생긴다. 🔴 **그래서 관측성이 Spring 만 덮는다** — 서킷브레이커가 회로를 열어도 AI 쪽 상태를 볼 지표가 없다 |
| AI 서버 헬스 | 컨테이너 헬스체크(`urllib`) | 🔶 그대로 |
| gRPC 채널 상태 | (없음) | ❌ 미도입 |
| **MySQL 내부 지표** | mysqld_exporter | ❌ 미도입. 🔴 **우선순위가 올라갔다** — 2026-08-08 부하 실험에서 *"MySQL 이 놀고 있었다"* 를 말하려 했는데 근거가 없었다.<br>🔴 **2026-08-09 정정**: ~~"수집한 적 없는 지표였다"~~ 는 **과했다.** 뜨는 수단은 있다 — `loadtest/measure_bufferpool.sh`(`Innodb_buffer_pool_*` 6종)·`measure_lock.sh`. 정확히는 **① 그 실험(`conn_sweep.sh`)에 물려 있지 않았고 ② 그것들은 전후 스냅샷이라 부하 중 시계열이 애초에 안 나온다.** 결론(지표로 좁힌 게 아니라 설정을 바꿔 재현하는 우회로로 갔다)은 그대로다 ([#151](https://github.com/Shadowfit/init/issues/151)) |
| 알람 | Slack 웹훅, PagerDuty | ❌ 미도입. 상시 운영이 아니라 울릴 대상이 없다 |
| 에러 추적 | Sentry | ❌ 미도입 |
| **판단 기준선(SLO)** | — | 🔴 **없다.** 그래프는 생겼는데 *"얼마면 나쁜가"* 가 없다. 실제로 부하 실험에서 판정 기준으로 SLO 를 쓸 수 없어 임의값(plateau 90%)을 미리 못박아 우회했다 |

### 5-1. 🔶 prod 에 관측 스택을 올릴지 — 미결정

지금은 dev compose 에만 있다. 이 결정은 두 가지와 묶여 있다:

1. **배포 대상 호스트가 없다**(§1). AWS 가 붙는 시점(#4)과 같이 정하는 게 맞다
2. **9090 매핑 여부.** prod 에서 `/actuator/flyway` 를 HTTP 로 보려면 루프백 매핑(`127.0.0.1:9090:9090`)이 필요하다. 지금은 `docker exec` 뿐이라 §2.3 의 3번째 명령(DB 직접 조회)이 1순위 경로다

> ⚠️ **올릴 때의 함정**: 호스트 RAM 만 올리고 `docker --memory` 캡을 안 풀면 그대로 OOM 이다 — 2026-07-25 EC2 실험에서 `exit 137` 을 두 번 겪었다. **둘을 같이 조정할 것.**
>
> ⚠️ **2코어급 인스턴스에 앱과 동거시키면 안 된다.** 관측 스택이 CPU 를 다투면 지표 자체가 오염된다. 2026-08-08 실험은 **obs 를 별 인스턴스**(t4g.small, 시간당 약 30원)로 분리해서 수치와 그래프를 같은 판에서 얻었다
| 사용자 분석 | (결정 필요) | 낮음 |

특히 AI 콜백 ERROR 로그는 운영자에게 즉시 알람이 가야 데이터 유실을 조기 발견할 수 있음. [`decisions/ai-backend-coupling.md`](./decisions/ai-backend-coupling.md) §분기 A 와 연계.

---

## 6. 보안 점검

- [x] AI 서버 외부 노출 차단 (커밋 c7657f1, `expose` 만)
- [x] gRPC 내부 통신 토큰 인증 (`INTERNAL_API_TOKEN`)
- [x] JWT 서명 (`JWT_SECRET`)
- [ ] HTTPS 종료 (운영 도메인 미정 — **TODO**)
- [ ] MySQL 호스트 노출 차단 (운영에서는 `ports:` 제거 권장)
- [ ] CSP/CORS 운영 헤더 — **TODO**
- [ ] Rate limit — **TODO**
- [ ] 시크릿을 평문 `.env` 가 아닌 시크릿 매니저(Vault/AWS Secrets Manager)에 보관 — **TODO**

---

## 7. 운영 중 자주 쓰는 명령어

```bash
# 컨테이너 상태
docker compose ps

# 특정 서비스 재시작
docker compose restart shadowfit-backend

# 로그 (실시간 follow)
docker compose logs -f shadowfit-backend
docker compose logs -f shadowfit-ai

# MySQL 접속
docker exec -it shadowfit-mysql mysql -u shadowfit -p shadowfit

# 디스크 사용량 (mysql_data 볼륨 크기)
docker system df -v

# 전체 중지 (데이터는 유지)
docker compose stop

# 전체 중지 + 컨테이너 삭제 (볼륨은 유지)
docker compose down

# ⚠️ 모든 데이터 초기화 (운영 절대 금지)
docker compose down -v
```

---

## 8. 결정·미정 정리

본 문서에서 **TODO** 또는 **미정** 으로 표시된 항목은 다음 결정 문서들로 분리 가능:

- **배포 대상 호스트** ⭐ — 지금 CD 가 끊겨 있는 이유. 상시 인스턴스를 둘지, 필요할 때만 띄울지
- 무중단 배포 요구 수준 (Blue-green / Rolling / 그냥 짧은 다운타임)
- ~~DB 마이그레이션 도구 도입~~ → ✅ **Flyway 채택·도입 완료** (2026-08-07, [이슈 #115](https://github.com/Shadowfit/init/issues/115) / [`decisions/schema-migration-tracking.md`](./decisions/schema-migration-tracking.md))
- 스키마 드리프트 탐지 — Flyway 가 못 보는 구멍("손으로 바꾼 DB"). 기각이 아니라 열어둔 상태
- 시크릿 매니저 도입 (.env / Vault / AWS Secrets Manager)
- 모니터링 스택 (ELK + Prometheus / SaaS / 최소만)
- 백업 정책 (RTO / RPO / 백업 위치)
- HTTPS·도메인 (수동 / Let's Encrypt / 클라우드 CDN)
- 로그 보관 기간 (개인정보보호 관점)

새 분기점이 결정되면 [[feedback_decision_doc]] 정책에 따라 [`decisions/`](./decisions/) 하위에 분석 문서 작성.

---

## 관련 문서
- Docker 구성 상세 → [`13-docker-setup.md`](./13-docker-setup.md)
- 환경 변수 의미 → `.env.example`
- 테스트 (배포 전 자동 검증) → [`18-testing-guide.md`](./18-testing-guide.md)
- 에러 응답 (운영 모니터링 매핑) → [`17-error-codes.md`](./17-error-codes.md)
- AI ↔ Backend 결합 (배포 시 동시성 고려) → [`architecture/ai-backend-integration.md`](./architecture/ai-backend-integration.md)
