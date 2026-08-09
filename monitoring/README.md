# 관측 스택 — Prometheus + Grafana

커스텀 지표 9종을 **시계열로** 보기 위한 스택. 2026-08-08 도입([`../docs/tasks/28-remaining-work-plan.md`](../docs/tasks/28-remaining-work-plan.md) §2-6).

## 켜고 끄기

**기본으로는 뜨지 않는다.** compose profile 뒤에 있다.

```bash
docker compose --profile obs up -d                          # 켜기
docker compose --profile obs stop prometheus grafana        # 끄기 (관측만)
docker compose --profile obs rm -sf prometheus grafana      # 컨테이너까지 지우기
```

> 🔴 **`--profile obs down` 을 쓰지 말 것** ([#131](https://github.com/Shadowfit/init/issues/131)). `--profile` 은 **올릴 대상을 고르는 옵션이고 내릴 대상을 좁히지 않는다.** `down` 은 compose 파일의 서비스를 통째로 내린다 — `--dry-run` 으로 확인한 실제 대상:
>
> ```
> shadowfit-backend  Stopping → Removing      ← 피시험 대상
> shadowfit-ai       Stopping → Removing
> mysql              Stopping → Removing      ← 데이터 볼륨은 남지만 컨테이너는 사라진다
> grafana · prometheus  Stopping → Removing
> ```
>
> 이 명령이 쓰이는 상황이 하필 **실험 도중**(아래 경고 참조)이라, 문서대로 치면 **그래프만 끄려다 측정 대상까지 죽는다.** 게다가 `down` 은 컨테이너를 제거하므로 판을 처음부터 다시 세워야 한다. 서비스 이름을 명시해야 범위가 실제로 좁혀진다 — 위 두 명령은 `--dry-run` 에서 grafana·prometheus **둘만** 건드리는 것을 확인했다.

데이터는 어느 쪽이든 볼륨에 남는다. 볼륨까지 버리려면 `docker volume rm` 을 따로 쓴다.

| | 주소 | 비고 |
|---|---|---|
| Grafana | http://localhost:3000 | `admin` / `admin` (`GRAFANA_USER`·`GRAFANA_PASSWORD` 로 변경 가능) |
| Prometheus | http://localhost:9091 | 호스트 9091 ↔ 컨테이너 9090. 백엔드 관리포트와 헷갈리지 않게 어긋냈다 |
| 백엔드 지표 원본 | http://localhost:9090/actuator/prometheus | dev 에서만 열린다 (아래) |

대시보드는 프로비저닝된다 — Grafana 를 켜면 **Shadowfit** 폴더에 이미 들어와 있다. UI 에서 만들지 않는다.

> 🔴 **부하 실험 중에는 끄고 돌린다.** 이 개발 장비는 2물리코어에 MySQL+백엔드+AI 가 이미 동거 중이라([`../docs/decisions/load-test-strategy.md`](../docs/decisions/load-test-strategy.md)), 관측 스택까지 같이 돌면 **무엇 때문에 느려졌는지 구분할 수 없다.** 그래프를 보려고 켠 것이 그래프를 못 믿게 만든다.
>
> 실험 중 관측이 필요하면 관측 스택만 **다른 장비**에서 띄우고 `prometheus.yml` 의 타깃을 그쪽으로 돌리는 게 맞다. 지금은 안 한다.

## 왜 포트가 나뉘어 있나

`management.server.port: 9090` 으로 **액추에이터를 앱 포트(8080)에서 분리**했다.

이유는 `/actuator/prometheus` 다. Prometheus 가 긁어가려면 인증 없이 닿아야 하는데, 8080 은 외부 노출이라 whitelist 에 넣으면 **지표가 인터넷에 공개된다** — 엔드포인트별 응답시간·에러율·회원 수 추이가 전부 보인다.

```
prod (docker-compose.prod.yml)              dev (docker-compose.yml)
┌──────────────────────────┐                ┌──────────────────────────┐
│ 8080 → 호스트 매핑 O     │ 앱 API         │ 8080 → 매핑 O            │
│ 9090 → 매핑 X            │ 관리·지표      │ 9090 → 매핑 O ← 차이     │
└──────────────────────────┘                └──────────────────────────┘
   도커 네트워크 안의                           로컬 검증(verify 스킬)이
   Prometheus 만 닿는다                        호스트에서 curl 하므로 연다
```

AI 서버 gRPC 8585 를 `expose` 만 하고 `ports` 에 안 쓰는 것과 같은 방식이다.

> 🔴 **검증에서 뒤집힌 것 (2026-08-08)** — 도입 시 *"포트를 나누면 시큐리티가 관리 포트엔 안 걸린다"* 고 봤는데 **틀렸다. 필터체인이 두 포트에 공유된다.**
>
> ```
> 분리만 했을 때:  9090 /actuator/prometheus → 401   ← Prometheus 가 못 긁는다
>                  9090 /actuator/health     → 200   ← whitelist 에 있어서
> ```
>
> 그래서 `application.yml` whitelist 에 `/actuator/prometheus` 를 넣어야 했다. 그대로 뒀으면 스크레이프가 **"타깃 DOWN" 으로 조용히 실패**했을 것이다.
>
> **결과적으로 보호 수단이 "인증"이 아니라 "네트워크 경계" 하나로 분명해졌다.** 8080 에 같은 경로를 쳐도 지표는 안 샌다(검증: 500 + 일반 오류 본문, 지표 문자열 없음) — 액추에이터가 9090 에서만 서비스되기 때문이다. **9090 을 어딘가에 노출하는 순간 whitelist 가 그대로 뚫린다. prod compose 에 매핑을 추가하지 말 것.**

> 🔴 **그리고 dev 에서 그 경계가 이미 뚫려 있었다 ([#128](https://github.com/Shadowfit/init/issues/128), 2026-08-08 수정).** 위 문단은 *"prod 에 매핑을 추가하지 말 것"* 만 경고했는데, 정작 dev 매핑이 `"9090:9090"` — 인터페이스를 안 적으면 도커는 **모든 인터페이스**에 바인딩하므로 같은 LAN 의 누구나 지표를 무인증으로 읽을 수 있었다. Grafana(3000)·Prometheus(9091)도 같았고, Grafana 는 `.env` 가 없으면 **admin/admin** 이다.
>
> 셋 다 `127.0.0.1:` 로 묶었다. 용도가 호스트 로컬 `curl`·브라우저라 위 표의 `localhost` URL 은 그대로 동작한다. **경계를 근거로 삼는 설계는 그 경계를 실제로 어디에 그었는지까지 적어야 한다** — 이 문서가 그걸 안 적어서 한동안 못 보고 있었다.

## `/actuator/health` 도 같이 옮겨갔다

포트를 나누면 health 도 9090 으로 간다. 8080 을 치던 두 곳을 함께 고쳤다:

- `docker-compose.prod.yml` 컨테이너 healthcheck
- `.claude/skills/verify/SKILL.md` 로컬 검증 절차

둘 다 안쪽/로컬에서 부르므로 매핑 없이 닿는다.

## 무엇을 보나

대시보드([`grafana/dashboards/shadowfit-backend.json`](grafana/dashboards/shadowfit-backend.json))는 5개 묶음이다.

| 묶음 | 왜 있나 |
|---|---|
| **커넥션 풀** | 풀 사이징 실험에서 pool=10·c=100 이 47% 타임아웃으로 붕괴한 걸 실측했는데 **그 순간의 그래프가 없어 "결과만 있고 과정이 없다"** 로 남았다. 이 스택의 1순위 용도 — ✅ **2026-08-08 채웠고, 답이 예상과 달랐다**(아래) |
| **아웃박스** | 적체는 "지금 3건"이 아니라 **기울기**가 답이다. 발행기가 죽으면 값만 계속 오른다 |
| **세션 파이프라인** | 낙관락 충돌(스케줄러↔AI 콜백 경쟁)은 지금까지 **코드와 재현 실험으로만** 증명돼 있고 실제 빈도를 볼 수단이 없었다 |
| **pose_data (#87)** | 고아 행 수 × 창의 폭(p99) 을 곱해야 빈도 상한이 나온다. 그 상한이 있어야 "핫패스에 상시 락을 얹을 만한가"를 판단할 수 있다 |
| **HTTP · JVM** | 기본 축. 기본 접힘 |

### ✅ 1순위 용도는 채워졌다 — 그런데 그래프가 «붕괴» 를 안 보여줬다 (2026-08-08)

이 스택을 세운 이유(위 표 1행)를 2026-08-08 부하 실험에서 실제로 썼다. 결과: [`../loadtest/results/pool-cliff-2026-08-08/`](../loadtest/results/pool-cliff-2026-08-08/).

**보러 갔던 붕괴가 없었다.** 대신 `hikaricp_*` 시계열이 다른 답을 줬다:

> 🔴 **정정 (2026-08-08).** 아래 표의 초판은 `query_range` **전체 창의 최댓값**을 그대로 적어서, 실제로는 **다른 실험 구간(옛 코드 대조군)의 값**을 이 실험의 것처럼 인용했다. 판별로 정렬한 값으로 바꾼다. 경위는 결과 [README §5 ④](../loadtest/results/pool-cliff-2026-08-08/README.md).

| c=100 판 | active | pending | 획득 최대 | timeout | 읽는 법 |
|---|:--:|:--:|---|:--:|---|
| **pool=20** | 2 | 0 | 0.001초 | **0** | 풀이 **놀고 있다** — 20개 중 2개 |
| **pool=5** | **5**(=상한) | **95** | 0.957초 | **0** | 풀이 **포화다** — 95개가 줄을 섰다 |

**그런데 두 판의 RPS 가 같다** (203.9 vs 208.9).

**풀 상태가 «놀고 있음» 과 «포화 + 95 대기» 로 갈리는데 처리량이 안 변한다.** 그러니 병목은 커넥션이 아니다. 그리고 포화한 쪽에서도 `timeout` 이 0 이다 — 커넥션당 작업이 짧아(다운샘플 후 5행 INSERT) 0.96초 대기가 30초 한도에 한참 못 미치고 큐가 빠진다. 즉 **포화 ≠ 붕괴.**

**이게 «수치만 있을 때는 구분되지 않던 상태»다.** RPS 표만 보면 두 판이 그냥 똑같은 숫자다 — 풀이 한가해서 같은 건지, 포화인데 버텨서 같은 건지 알 수 없다. 그 둘을 가른 건 그래프다.

> 📌 **관측 스택의 값어치가 여기서 처음 드러났다.** 답이 "무너지는 과정"이 아니라 **"무너지지 않는 이유"** 로 나왔고, 그건 그래프 없이는 말할 수 없었다.

🔴 **그런데 같은 그래프가 답하지 못한 것도 분명해졌다.** 두 판 다 RPS 가 ~205 에 묶여 있는데, **무엇이 묶고 있는지는 이 스택으로 안 나온다** — 풀도 아니고(위 표) 백엔드 CPU 도 아니다(스윕 중 `system_cpu` 0.51~0.56). **MySQL 지표는 아예 수집되지 않았다**(타깃이 백엔드 하나뿐). 초판이 이 자리에 *"MySQL 은 놀고 백엔드가 탄다"* 고 쓴 것은 **없는 데이터를 근거로 든 것**이라 철회했다.
>
> ⚙️ **이 스택의 다음 숙제 2개가 거기서 나온다**: ① **`mysqld_exporter`** — "MySQL 은 놀았다"를 말하려면 필요하다(이 문서 아래에서 *"`SHOW STATUS` 와 중복"* 이라 뺐던 그 항목이다). ② **`scrape_interval` 15초가 판 9.5초보다 길다** — 판당 샘플이 1개뿐이라 판 안의 모양을 못 본다.
>
> ✅ **후속 (같은 날 저녁): 천장은 «커밋 fsync» 로 규명됐다** ([`../loadtest/results/ceiling-fsync-2026-08-08/`](../loadtest/results/ceiling-fsync-2026-08-08/)) — 다만 **이 스택으로 알아낸 게 아니다.** 별도 인프라에서 `docker stats` 로 세 박스 CPU 를 직접 재고, MySQL 내구성 설정을 직접 바꿔 대조해서 나왔다.
>
> 🔴 **그래서 위 숙제 ①의 근거가 세졌다.** 이 스택은 백엔드 하나만 긁고 있어서 *"MySQL 쪽은 어떤가"* 에 아무 답을 못 했고, 그 공백이 **없는 수치를 결론 근거로 적는 사고**로 이어졌다. 다음 부하 실험 전에 **DB 타깃을 붙이는 것**이 이 스택의 1순위 보완이다.

⚠️ **2코어 로컬에서는 여전히 택1이다.** 위 측정은 **obs 를 자기 인스턴스로 분리**해서 얻었다(EC2 3대). 로컬에서 부하와 관측을 동거시키면 측정이 오염된다 — 이 문서가 아래에서 경고하는 그대로다.

### 지표 이름이 코드와 다르게 보이는 이유

Micrometer 가 Prometheus 관례로 바꾼다. 코드에서 `shadowfit.outbox.pending` 이면 쿼리에서는 `shadowfit_outbox_pending` 이고, 카운터에는 `_total` 이, 타이머에는 `_seconds_*` 가 붙는다.

## 패널이 "No data" 로 보이는 것은 대개 정상이다

**Micrometer 는 카운터·타이머를 "첫 기록 시점"에 만든다.** 그래서 서버를 갓 띄우면 `/actuator/prometheus` 에 게이지 2종(`shadowfit_outbox_pending`·`shadowfit_pose_orphan_rows`)만 나오고 나머지 7종은 **아예 없다.** 도입 검증 때 실제로 그랬다.

세션이 한 번 돌면 채워진다. 즉 **빈 패널 ≠ 고장**이다.

다만 그 성질 때문에 "대시보드 쿼리 이름이 맞는지"를 서버로는 확인할 수 없다(없는 지표는 이름이 틀려도 똑같이 안 나온다). 그래서 이름을 **테스트로 고정**했다:

```
backend/src/test/java/com/shadowfit/global/observability/SessionMetricsExportNamesTest.java
```

레지스트리를 직접 만들어 9종을 한 번씩 기록한 뒤 scrape 결과에서 이름·태그·백분위 라벨을 검사한다. **이 테스트가 깨지면 대시보드 JSON 도 같이 고쳐야 한다.**

## 🔴 한계 · 넣지 않은 것

- **ai-server (FastAPI) 지표 없음** — Python 쪽에 계측이 아예 없다. 타깃만 적으면 영원히 DOWN 인 타깃이 하나 생겨 "관측 스택이 고장난 것처럼" 보이므로 아예 뺐다
- **mysqld_exporter 없음** — ~~MySQL 내부 지표는 `loadtest/` 스크립트가 `SHOW STATUS` 로 이미 뜨고 있다. 중복이고 컨테이너만 는다~~
  🔴 **2026-08-09 정정 — 이 근거가 틀렸다. «중복» 이 아니다.**
  `loadtest/measure_bufferpool.sh`(`Innodb_buffer_pool_*` 6종)·`measure_lock.sh` 가 `SHOW STATUS` 를 쓰는 것은 맞다. 그런데 그건 **특정 실험이 전후로 찍는 스냅샷**이고, exporter 가 주는 것은 **부하 중 시계열**이다. 같은 것으로 보고 «중복» 이라 판정했다.
  실제로 어긋난 사례: 2026-08-08 fsync 천장 실험이 쓴 [`../loadtest/results/ceiling-fsync-2026-08-08/conn_sweep.sh`](../loadtest/results/ceiling-fsync-2026-08-08/conn_sweep.sh) 는 **MySQL 지표를 하나도 걷지 않는다.** 스냅샷 스크립트는 그 실험에 물려 있지도 않았다.
  → 도입 여부는 다시 판단할 사안이다 ([#151](https://github.com/Shadowfit/init/issues/151)).
- **알림(Alertmanager) 없음** — 상시 운영이 아니라 볼 때만 켜는 구조라 울릴 대상이 없다
- **고아 행 게이지는 실시간이 아니다** — 스케줄러가 미리 채운 값을 읽는다(대용량 anti-join 을 스크레이프마다 돌리면 그 자체가 부하). **최대 갱신주기만큼 지연**이 있다
- **보존 7일** — 그보다 오래된 것은 사라진다. 실험 결과를 남기려면 그래프를 캡처하거나 수치를 문서에 적을 것
- **8080 의 `/actuator/health` 가 500 을 반환한다** — whitelist(두 포트 공유)는 통과하는데 핸들러가 9090 으로 갔기 때문이다. 404 가 맞는 응답이라 깔끔하지 않다. 그 줄을 빼면 **9090 헬스체크가 401 로 깨지므로** 지금은 감수한다. 영향은 "옛 URL 을 치면 404 대신 500" 하나다
- **실측으로 확인한 범위** — 스크레이프 타깃 UP, 그라파나→프로메테우스 도달, 게이지 2종·HikariCP·HTTP·JVM 쿼리 값 반환까지. **부하를 넣은 상태의 그래프는 아직 안 봤다** — 이 스택의 1순위 용도(풀 붕괴의 과정)는 rig 를 다시 돌려야 채워진다
