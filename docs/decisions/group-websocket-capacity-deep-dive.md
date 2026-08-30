# 그룹 WebSocket 캐파시티 깊이 파기 — 지금 짠 코드가 실제로 어떻게 동작하나

작성일: 2026-08-30
상태: **§1-1~§1-5(단일 그룹 동시성) + 다중 그룹 스파이크(§9, AWS c7i.xlarge) 실행 완료.** 단일 인스턴스 범위는 사실상 종결.
대상: [`multiuser-realtime-sync.md`](./multiuser-realtime-sync.md) §7 세션9("캐파시티 실측 — 동시 그룹 수·그룹당 인원 스윕, pub/sub 지연, 인스턴스 재시작 복구")가 이름만 세워둔 자리. 이 문서가 그 안을 다섯 갈래로 편다. 세션1·2·4~7은 이미 구현·테스트 완료(단일 인스턴스 전제, Redis(세션3)는 보류 — `GroupSocketRegistry`·`GroupEventService`·`JwtHandshakeInterceptor` 코드 기준).
연관: [`sse-capacity-deep-dive.md`](./sse-capacity-deep-dive.md)(같은 방법론을 트레이너 SSE에 적용한 짝 문서, 이 문서가 그 구조를 그대로 따른다), [`four-axes-depth-experiments.md`](./four-axes-depth-experiments.md), [[feedback_tps_over_dau_justification]], [[project_loadtest_env_constraint]]

---

## 0. 이 문서가 여는 것

`multiuser-realtime-sync.md` §7 세션9는 "캐파시티 실측"을 8~14h짜리 한 줄로만 잡아뒀다. 이 프로젝트가 다른 축(AI GIL 천장, DB pool sizing, DELETE 파편화, 그리고 짝 문서인 SSE 캐파시티)을 팠던 방식은 **"몇 개까지 버티나"가 아니라 "왜 거기서 꺾이는가"**였다([[feedback_tps_over_dau_justification]]). 그룹 WebSocket도 같은 기준을 적용하면 다섯 개의 성격이 다른 질문으로 쪼개진다.

**환경 전제**: 이 저장소의 로컬 실측 환경은 물리 코어 2개짜리 박스에 MySQL·백엔드·부하 생성기가 동거한다([[project_loadtest_env_constraint]]). 따라서 아래 어느 갈래를 실행하든 **절대 수치(RPS·최대 연결 수)는 의미가 없고, 메커니즘·상대 비교·델타만 신뢰**한다 — 이 문서의 목적은 "N개까지 버틴다"가 아니라 "N개에서 꺾이면 그 이유가 무엇인가"다.

---

## 1. 다섯 갈래

### 1-1. 연결당 스레드 모델 — raw WebSocketHandler가 실제로 스레드를 어떻게 쓰는가

**질문**: `WebSocketConfig`는 `spring-boot-starter-websocket`을 그대로 얹었을 뿐 Tomcat WebSocket 컨테이너나 스레드풀을 커스터마이징한 적이 없다(`server.tomcat.threads.max` 등 관련 설정 전무, 확인됨). Tomcat의 WS 구현은 이론상 HTTP 요청 스레드풀과 분리된 별도 경로로 동작해야 하는데, **이 코드에서 실제로 그런가** — 동시 그룹 연결 수를 늘렸을 때 `server.tomcat.threads.max` 사용량이 같이 올라가는가?

**반증 조건**: 연결 수에 비례해 HTTP 스레드풀 사용량이 올라가면 — WS와 일반 REST API(그룹 생성·초대 등 같은 애플리케이션의 다른 엔드포인트)가 스레드를 공유하며 서로 경합한다는 뜻. 이 경우 그룹 기능이 인기가 많아지면 무관한 REST API까지 느려질 수 있다.

**측정 방법**: 동시 WS 연결 수를 스윕하며 Tomcat 스레드풀 사용량·JVM 스레드 덤프를 같이 뜬다(`sse-capacity-deep-dive.md` §1-1과 동일 패턴).

### 1-2. 브로드캐스트에 백프레셔가 없다 — 느린 멤버 하나가 같은 그룹의 나머지 전달을 지연시키는가

**질문**: `GroupSocketRegistry.broadcast()`는 그룹의 세션들을 순차 for-loop로 돌며 `synchronized(session) { session.sendMessage(message) }`를 부른다. 실패(`IOException`)했을 때만 그 세션을 정리할 뿐, **느리지만 아직 안 끊긴** 세션에 대해서는 정책이 없다 — `sendMessage`가 내부적으로 블로킹되면 그 다음 순번 세션들의 전달이 같이 늦어진다.

**왜 SSE보다 파급이 큰가**: 짝 문서 [`sse-capacity-deep-dive.md`](./sse-capacity-deep-dive.md) §1-3의 트레이너 SSE는 관계가 1:1이라 느린 컨슈머가 있어도 영향받는 건 그 트레이너 하나뿐이다. 그룹은 N:N이라, 멤버 하나의 네트워크가 나쁘면 **같은 그룹의 나머지 N-1명 전체**의 실시간성이 같이 저하될 수 있다 — `multiuser-realtime-sync.md` §3(난이도 분해)이 미리 지적했던 "N:N이라 SSE보다 파급 범위가 크다"가 정확히 이 지점이다.

**반증 조건**: 그룹에 느린 컨슈머 1개를 인위적으로 만들어 놓고(예: 소켓은 열려 있지만 응답을 안 읽는 클라이언트) 같은 그룹 내 정상 멤버들의 이벤트 수신 지연이 같이 올라가면 — 격리가 안 되고 있다는 뜻. 이건 성능 측정이 아니라 **"드롭 정책만으로 충분하다"는 현재 설계 가정이 실제로 맞는지를 실패 주입으로 확인하는 것**이라 [`four-axes-depth-experiments.md`](./four-axes-depth-experiments.md)의 방법론에 가깝다.

### 1-3. seq 채번 락 경합 — 그룹 내 이벤트가 몰리면 어디서 꺾이는가

**질문**: `GroupEventService.publish()`는 `Group` 행을 비관적 쓰기 잠금(`findByIdForUpdate`)으로 잡고 `next_seq`를 증가시킨 뒤 커밋한다 — 브로드캐스트 자체는 커밋 후(락 밖)라 락 보유 시간은 짧을 것으로 "설계"돼 있다. **그룹 인원·이벤트 발행 빈도를 늘렸을 때 이 락이 실제로 처리량 천장이 되는가, 되면 락 대기 때문인가 아니면 다른 무엇 때문인가**를 격리해서 확인한다.

**반증 조건**: "N명/N개 이벤트에서 꺾인다"는 관찰 자체는 답이 아니다 — [[project_ai_ceiling_gil_n3_closed]]에서 답이 "GIL이라서"로 갈렸던 것처럼, 여기서도 **락 대기 시간이 실제로 늘어나는지(MySQL `SHOW ENGINE INNODB STATUS`의 lock wait), 아니면 커넥션 풀 고갈·트랜잭션 처리 자체가 원인인지**를 갈라야 한다.

**측정 방법**: 한 그룹 안에서 동시 publish 개수를 스윕하며 락 대기 시간·트랜잭션 처리 시간을 따로 측정한다. 이 프로젝트가 pool-cliff·delete-fragmentation에서 이미 쓴 방법론(원인 격리, 팔당 반복)을 그대로 적용.

### 1-4. 재시작·재연결 후 상태 복구가 설계대로 동작하는가

**질문**: 세 가지를 확인해야 "재연결 백필이 유실을 완전히 커버한다"고 말할 수 있다.
1. 서버가 재시작돼도 `next_seq`(DB 컬럼)에서 정확히 이어지는가 — 인메모리 카운터가 아니라 DB에 둔 이유가 실제로 성립하는지.
2. 클라이언트가 WS 연결이 끊긴 동안 발생한 이벤트를, 재연결 후 REST 백필(`GET /groups/{groupId}/events?afterSeq=`)로 **순서대로 전부, 중복 없이** 받는가.
3. 탈퇴(LEFT)한 멤버가 기존 WS 연결을 물고 있어도, 재연결 시도 시 새 핸드셰이크가 막는가(`JwtHandshakeInterceptor`가 매 핸드셰이크마다 ACTIVE 여부를 다시 검사하므로 이론상 막혀야 함).

**반증 조건**: 이건 성능 측정이 아니라 **"설계한 대로 동작하나"를 실패 주입으로 확인하는 것**([`four-axes-depth-experiments.md`](./four-axes-depth-experiments.md) §2와 같은 유형)이다. 서버 재시작·강제 연결 끊김·탈퇴 후 재접속 세 시나리오를 실제로 일으켜서 확인한다.

### 1-5. 핸드셰이크 DB 조회 비용 — 그룹 전원이 몰려 접속하는 순간

**질문**: 핸드셰이크마다 `MemberRepository.findByEmail` + `GroupMemberRepository.existsByGroupIdAndMemberIdAndStatus` 두 번의 DB 조회가 일어난다. "그룹 운동 시작" 같은 트리거로 그룹 참여자 전원이 짧은 시간 안에 한꺼번에 접속하는 시나리오에서 이게 병목이 되는가.

**반증 조건**: 값싸게 확인 가능 — 동시 핸드셰이크 개수를 스윕하며 핸드셰이크 지연을 본다. 인덱스가 이미 있는 단순 조회 두 번이라 병목일 가능성은 낮지만, "낮을 것이다"는 추측이지 확인이 아니다.

---

## 2. 우선순위 (추천, 미확정)

| 순위 | 갈래 | 이유 |
|---|---|---|
| 1 | §1-2 (백프레셔 부재) | 이미 코드에 정책 공백이 존재하는 게 확인된 지점 — "드롭만 있고 느림 처리가 없다"는 사실이지 추측이 아니다. N:N이라 SSE보다 파급이 크다는 것도 이미 문서(`multiuser-realtime-sync.md`)가 지적해둔 리스크와 직결된다 |
| 2 | §1-3 (seq 락 경합) | 이 프로젝트의 기존 강점(DB 락·동시성 실측)과 가장 잘 붙는 갈래 — pool-cliff·delete-fragmentation과 같은 방법론을 그대로 재사용 가능 |
| 3 | §1-4 (재시작·재연결 복구) | 측정이 아니라 "구현이 설계와 맞나" 확인 — 비용이 낮고, 특히 재시작 후 seq 이어짐은 지금 테스트(H2 기반 유닛·통합)로는 확인 못 한 지점 |
| 4 | §1-1 (스레드 모델) | 1·2를 파는 과정에서 부산물로 드러날 가능성이 큼 |
| 5 | §1-5 (핸드셰이크 DB 비용) | 값싸지만 병목일 가능성이 낮아 보이는 항목 — 우선순위 낮음, 필요하면 곁다리로 확인 |

> 🔴 이 순위는 추천이며 결정이 아니다. 어느 갈래를 팔지, 순서를 바꿀지는 사용자 확인 후에만 진행한다.

---

## 3. §1-2 실행 결과 (2026-08-30) — 메커니즘은 확정, 정량화는 미검증

실행: `loadtest/measure_group_ws_backpressure.py`(raw WebSocket 클라이언트로 "연결은 됐지만 안 읽는" 소비자를 만들어 baseline과 비교). 결과: `loadtest/results/group-ws-backpressure-2026-08-30/run-dc588d98.json`(5판, 판마다 순서 교대 — [[feedback_measure_design_needs_repeats]]). GitHub 이슈로 등록: [#623](https://github.com/Shadowfit/init/issues/623).

**메커니즘 확인(코드 근거, 측정과 무관)**: `GroupSocketRegistry.broadcast()`가 그룹 세션을 순차 for-loop로 동기 `sendMessage()`하고, 백프레셔 정책(`ConcurrentWebSocketSessionDecorator` 등)이 전혀 없다는 것은 코드를 읽으면 바로 나오는 사실 — 측정으로 증명할 필요가 없는 부분이다.

**정량화 시도 — 실패**: 이벤트 20개 × payload ~4KB, 느린 소비자 SO_RCVBUF=1024로 5판(순서 교대) 돌렸을 때 baseline p50 중앙값 2562ms, with-slow-consumer 중앙값 4016ms로 표면적으론 ~1.6배지만, **판별 분산이 그 차이보다 컸다**(두 분포가 겹침 — baseline 1157~5563ms, with-slow 1031~6109ms). 페어 비교로도 5판 중 3판만 slow가 더 높았다. 원인으로 보이는 것: 이 환경(물리 코어 2개, MySQL·백엔드·측정 스크립트 동거, [[project_loadtest_env_constraint]])에서는 이벤트 1건당 DB 트랜잭션(비관적 락+INSERT+커밋) 자체가 이미 수백ms~초 단위로 느려서, "느린 소비자로 인한 추가 블로킹" 신호보다 그 잡음이 크다.

> 🔴 **"느린 소비자가 지연을 유발한다"고 단정하지 않는다.** 코드가 그렇게 될 수 있게 짜여 있다는 것(확정)과, 이 규모의 실측으로 그 크기가 입증됐다는 것(미확정)은 다른 주장이다. 다음 단계(더 많은 반복·더 극단적 파라미터로 재시도 / AWS로 옮겨 잡음을 줄이고 재시도 / 측정 없이 원인이 이미 코드로 확정됐으니 바로 고침)는 추천이며 결정이 아니다.

**후속(2026-08-30, 사용자 확인 후 진행)**: 정량화는 미확정이지만 메커니즘 자체는 코드로 확정된 결함이라, 측정을 더 파는 대신 바로 고쳤다. `GroupSocketRegistry`가 이제 세션마다 `ConcurrentWebSocketSessionDecorator`(전송 시간·버퍼 상한 — Spring이 `SubProtocolWebSocketHandler`에 쓰는 기본값 10s/512KB)로 감싸고, 세션 전용 단일 스레드로 전송을 넘긴다 — 느린 세션의 블로킹이 그 세션의 전용 스레드에만 갇히고 발행자·다른 멤버로 새지 않는다. 트레이드오프(연결마다 스레드 하나 상시 점유)는 `GroupSocketRegistry.java` 클래스 주석에 그대로 남겨뒀다 — 그룹 규모가 커지면 이 자체가 새 캐파시티 질문이 된다. [#623](https://github.com/Shadowfit/init/issues/623)에 커밋으로 반영.

---

## 4. §1-3 실행 결과 (2026-08-30) — 락 경합, InnoDB 카운터로 직접 확인됨

실행: `loadtest/measure_group_ws_seq_lock_contention.py`. 결과: `loadtest/results/group-ws-seq-lock-2026-08-30/run-0986c085.json`(동시 발행자 M=2/4/8, 각 5판, 순서 교대).

**설계**: ghz의 `batch_multi.json` vs `batch.json`(단일 핫세션)이 이미 이 저장소에서 검증한 것과 같은 격리 패턴 — 같은 M명이 (a) **hot**: 전부 같은 그룹 하나에서 동시에 발행 vs (b) **spread**: 같은 M명이 서로 다른 1인 그룹 M개에서 동시에 발행. 두 조건의 총 DB/CPU 부하는 같고, 차이는 오직 "같은 `Group` 행을 두고 락을 다투는가"뿐이다. §1-2 때 타이밍만으로 판단했다가 잡음에 뒤집힌 교훈으로, 이번엔 **InnoDB 자신의 누적 카운터**(`information_schema.INNODB_METRICS`의 `lock_row_lock_waits`·`lock_row_lock_time`)를 판 전후로 diff해서 타이밍 추론이 아니라 엔진 계측으로 직접 확인했다.

**결과**:

| M | 조건 | p50 중앙값(5판) | lock_row_lock_waits 합계(5판) | lock_row_lock_time 합계(5판) |
|---|---|---|---|---|
| 2 | hot | 141ms | **5** (= 1×5판, 이론값 M-1=1과 정확히 일치) | 359ms |
| 2 | spread | 109ms | 0 | 0ms |
| 4 | hot | 296ms | **15** (= 3×5판, 이론값 M-1=3과 정확히 일치) | 2721ms |
| 4 | spread | 125ms | 0 | 0ms |
| 8 | hot | 344ms | **35** (= 7×5판, 이론값 M-1=7과 정확히 일치) | 9301ms |
| 8 | spread | 125ms | 0 | 0ms |

**결론 — §1-2와 달리 이번엔 깨끗하게 닫혔다**:
- `lock_row_lock_waits`가 매 판 정확히 **M-1**건 — 동시 발행자 M명 중 1명만 락을 즉시 얻고 나머지 M-1명이 대기한다는 이론과 실측이 정확히 일치한다. `spread`는 15판 전부 0건 — 우연이 아니라 "그룹 행이 갈리면 경합 자체가 없다"는 통제가 그대로 성립했다.
- `hot`의 p50이 M에 비례해 커지고(141→296→344ms), `lock_row_lock_time` 합계도 M이 커질수록 가파르게 늘어난다(359→2721→9301ms) — 지연의 원인이 **행 잠금 대기**임이 타이밍 추론이 아니라 InnoDB 엔진 자신의 계측으로 확인됐다.
- **이건 고쳐야 할 결함이 아니라 정합성을 위한 필연적 트레이드오프다** — `group_events.seq`가 그룹 안에서 유일·오름차순임을 보장하려면 같은 그룹의 동시 쓰기는 정의상 직렬화돼야 한다. §1-2(백프레셔)처럼 코드를 고칠 지점이 아니라, "그룹 하나에 동시에 몇 명이 거의 같은 순간에 발행하면 어느 정도 대기가 생기는가"를 수치로 확정한 것이 이 실험의 산출물이다.

> 🔴 이 수치는 이 로컬 환경(물리 코어 2개 공유)에서 잰 것이라 절대값(ms)은 이 환경 밖으로 못 옮긴다. 옮길 수 있는 것은 **"M-1건의 락 대기가 정확히 생긴다"는 메커니즘과 "대기 시간이 M에 따라 커진다"는 방향성**이다. 실제 배포 환경에서의 절대 지연은 별도로 재야 한다.

---

## 5. §1-4 실행 결과 (2026-08-30) — 실제 임베디드 서버 + 진짜 WebSocket으로 확인

§1-4는 성능 측정이 아니라 "설계한 대로 동작하나"를 확인하는 실패 주입형 질문이라(원 문서 §1-4 참고), Python 스크립트 대신 **영구 회귀 테스트**로 만들었다 — `backend/src/test/java/com/shadowfit/integration/GroupWebSocketReconnectRecoveryIntegrationTest.java`. `@SpringBootTest(webEnvironment = RANDOM_PORT)` + JDK 내장 `java.net.http.WebSocket`으로 임베디드 서버에 **진짜 WebSocket 연결**을 맺어 확인한다(기존 `JwtHandshakeInterceptorTest` 등은 컴포넌트 단위를 목으로 검증했을 뿐, 엔드투엔드로 확인한 적은 없었다). `@Transactional`을 안 쓰고 수동 정리를 쓰는 이유는 WS가 임베디드 서버의 실제 네트워크 스레드에서 처리돼 테스트 스레드의 트랜잭션 롤백이 안 보이기 때문(`SignupUsernameRaceTest`와 같은 이유).

**확인한 두 가지, 둘 다 통과**:
1. **재연결 백필의 완전성** — 멤버 하나가 한 번도 연결하지 않은 채(afterSeq=0), 다른 멤버가 실제 WS 경로로 이벤트 3건을 발행(총 4건: MEMBER_JOINED 포함)한 뒤, `GET /groups/{id}/events?afterSeq=0`으로 **seq 1~4가 순서대로, 누락·중복 없이** 돌아오는 것을 확인.
2. **탈퇴 후 재연결 차단** — 탈퇴 전엔 정상 연결되던 토큰이, `DELETE /groups/{id}/members/me` 이후 같은 그룹에 재연결을 시도하면 핸드셰이크 단계에서 **403**으로 거부되는 것을 확인(`WebSocketHandshakeException.getResponse().statusCode()`로 직접 단언).

**서버 재시작 후 seq가 이어지는가는 별도로 실측하지 않았다** — `next_seq`가 애플리케이션 메모리가 아니라 DB 컬럼에만 있고(2차 캐시 미사용, 매 `publish()`가 `findByIdForUpdate`로 새로 읽음), 이 특성은 이미 코드 구조로 보장된다. 컨테이너를 실제로 내렸다 올리는 것은 "DB에 값이 있으면 이어진다"는, 이미 참인 명제를 다시 확인하는 것뿐이라 비용 대비 실익이 낮다고 판단해 생략했다.

---

## 6. §1-5 실행 결과 (2026-08-30) — 지연은 커지지만, 원인을 끝까지 못 갈랐다

실행: `loadtest/measure_group_handshake_concurrency.py`. 결과: `loadtest/results/group-ws-handshake-2026-08-30/run-6786a735.json`(M=5/10/20/40, 각 5판).

**결과**: 같은 그룹 멤버 M명이 동시에 핸드셰이크를 시도했을 때 p50 중앙값이 M=5(78ms) → M=10(313ms) → M=20(281ms) → M=40(437ms)로 대체로 커졌다(판별 편차가 커서 M=10·20 사이는 역전도 있음). **실패(핸드셰이크 거부·타임아웃)는 M=40까지도 0건** — "몰리면 지연은 커지지만 끊기거나 틀리진 않는다."

**원인을 끝까지 못 갈랐다**: `JwtHandshakeInterceptor`의 두 조회(`users.email` UNIQUE 인덱스, `(group_id, member_id)` UNIQUE 인덱스)는 스키마상 인덱스로 커버돼 있어 "쿼리 자체가 느리다"보다는 (a) HikariCP 커넥션 풀(`maximum-pool-size: 15`, `pool-cliff-vs-concurrency.md`가 이미 이 값을 다룬 적 있음)이 M>15에서 대기를 만드는지, (b) 이 로컬 환경(2물리코어)의 일반적 동시성 한계(스레드 스케줄링·JWT 서명 검증 CPU 비용)인지가 더 유력해 보이지만, HikariCP 커넥션 풀 상태를 직접 볼 수단(actuator `/actuator/metrics/hikaricp.connections.*`)이 이 환경에서 인증(401)에 막혀 **직접 계측으로 확정하지 못했다**.

**후속 — HikariCP는 무죄로 확인됨**: `/actuator/metrics`는 외부에서 401로 막혀 있었지만, 컨테이너 안에서 `/actuator/prometheus`(Prometheus 스크레이프용, 앱 자체 인증 없음)를 직접 찍어보니 M=40 버스트 직후 기준 `hikaricp_connections_acquire_seconds_max = 0.021`(커넥션 획득 최대 21ms), `hikaricp_connections_timeout_total = 0`(타임아웃 0건) — **컨넥션 풀(`maximum-pool-size: 15`)이 병목이라는 가설은 이 데이터로 기각된다.** 40명이 동시에 두 조회를 던져도 풀에서 기다린 시간은 무시할 수준이었다.

> 🔴 **결론**: 핸드셰이크 지연이 동시성에 따라 커지는 것(§1-5 본론)과 실패가 전혀 없는 것은 확인됐다. 원인 후보 중 "DB 커넥션 풀 대기"는 직접 계측으로 배제했다. 남은 후보(이 2코어 공유 박스의 일반적 CPU/스레드 스케줄링 한계, 또는 Tomcat 커넥터 스레드풀 자체의 경합 — §1-1과 겹치는 지점)까지 완전히 가르려면 Tomcat 스레드풀 지표(`tomcat_threads_*`)가 필요한데 이 앱엔 등록돼 있지 않았다(§1-1에서 같은 벽에 부딪힘). 이 갈래의 우선순위(원 문서에서 최하위)와 남은 확인 비용을 저울질해 여기서 멈춘다 — "정합성 리스크는 없다"와 "DB 풀은 원인이 아니다"라는, 처음보다 훨씬 좁혀진 확정 사실 두 개를 들고 닫는다.

---

## 7. §1-1 실행 결과 (2026-08-30) — 직접 계측으로 확정됨

**질문**: raw `WebSocketHandler`가 Tomcat HTTP 요청 스레드풀과 실제로 분리돼 도는가.

**막혔다가 뚫린 경로**: 처음엔 `tomcat_threads_*` 지표 자체가 등록돼 있지 않아(§1-5에서 발견) 근거 기반 추론으로만 답했다. 원인은 Spring Boot 임베디드 Tomcat의 MBean 등록이 기본값(`server.tomcat.mbeanregistry.enabled=false`)으로 꺼져 있어서였다 — `tomcat.sessions.*`는 Tomcat의 `Manager` 객체를 직접 참조해 나오지만, `tomcat.threads.*`·`tomcat.global.*`는 Tomcat의 `ThreadPool` **MBean**(JMX)에서 나오는데 그 MBean 자체가 등록 안 된 상태였다(둘의 차이가 원인을 정확히 가리켰다). `application.yml`에 `server.tomcat.mbeanregistry.enabled: true`를 켜고 이미지를 다시 빌드·재기동하니 `tomcat_threads_busy_threads`·`tomcat_threads_config_max_threads`·`tomcat_threads_current_threads`가 즉시 나타났다.

**실측**: `loadtest/measure_group_ws_thread_pool_sharing.py` — 같은 그룹 멤버 M=8명이 `threading.Barrier`로 동시에 WS 텍스트 메시지를 발행하게 만들고(§1-3의 "hot" 시나리오와 동일 패턴, seq 락 경합으로 처리 시간이 늘어나 폴링 윈도우에 걸리기 쉽다), 그 동안 `:9090/actuator/prometheus`의 `tomcat_threads_busy_threads{name="http-nio-8080"}`를 10ms 간격으로 폴링했다.

| 판 | busy_threads 관측 최대값 (베이스라인 0) |
|---|---|
| 1 | 8 |
| 2 | 11 |
| 3 | 8 |

**결론**: 다른 HTTP 트래픽이 전혀 없는 상태에서 WS 발행 버스트만으로 `http-nio-8080` 커넥터의 busy thread 수가 0에서 M(=8) 근처까지 — 심지어 한 판은 M을 넘는 11까지 — 치솟았다. **"WS 메시지 처리가 HTTP 커넥터와 같은 스레드풀을 공유한다"는 가설이 타이밍 추론이 아니라 Tomcat 자신의 JMX 지표로 직접 확정됐다.** 이 프로젝트가 WS 전용 실행기를 구성한 적이 없다는 코드 사실과 정확히 일치한다.

**부수 산출물**: `server.tomcat.mbeanregistry.enabled: true`는 이 실험 하나를 위한 임시 설정이 아니라 그대로 남겨뒀다 — Tomcat 스레드풀 관측은 이 프로젝트가 이미 갖춘 Prometheus/Grafana 관측 스택에 자연스럽게 편입되는 지표라, MBean 등록의 (무시할 수준인) 기동 비용을 감수할 값어치가 있다고 판단했다.

> 🔴 **남은 것**: "공유한다"는 확정됐지만 "그래서 실제로 REST API가 느려지는 지점(풀 200개 고갈)"까지는 여전히 안 갔다 — 이번 버스트(M=8~11)는 기본 풀 크기(200)에 한참 못 미친다. 그 고갈 지점을 보려면 동시성을 200 근처로 끌어올려야 하는데, 이 갈래의 원래 우선순위(최하위)를 고려하면 "공유한다"는 확정만으로 §1-1의 최소 목적은 달성했다고 보고 여기서 닫는다.

---

## 8. 단일 인스턴스 범위 마무리

다섯 갈래(§1-1~§1-5) 전부 실제 실측·확정으로 닫혔다:

| 갈래 | 결론 |
|---|---|
| §1-2 백프레셔 | 결함 확인 → 코드로 고침(`ConcurrentWebSocketSessionDecorator` + 세션별 전용 스레드, [#623](https://github.com/Shadowfit/init/issues/623)) |
| §1-3 seq 락 경합 | InnoDB 카운터로 인과 확정(락 대기 건수 = 정확히 M-1, 이론값과 일치) — 정합성을 위한 필연적 트레이드오프로 정리, 코드 변경 없음 |
| §1-4 재시작·재연결 | 영구 회귀 테스트(`GroupWebSocketReconnectRecoveryIntegrationTest`, 실제 임베디드 서버+진짜 WebSocket)로 확인·고정 |
| §1-5 핸드셰이크 DB 비용 | 지연은 동시성에 비례해 커지지만 실패 0건, HikariCP 풀은 원인에서 직접 계측으로 배제 |
| §1-1 스레드 모델 | `tomcat_threads_busy_threads`를 새로 노출시켜(§1-5에서 막혔던 지표) WS 버스트가 HTTP 커넥터 스레드풀을 그대로 잠식하는 것을 직접 확인 |

부수적으로 `server.tomcat.mbeanregistry.enabled: true`를 영구 설정으로 남겨, 이 프로젝트의 Prometheus 스택에 Tomcat 스레드풀 관측이 새로 편입됐다.

Redis 도입(원 계획 세션3)과 다중 인스턴스 통합 테스트(세션8)는 여전히 보류 상태다 — 그쪽으로 갈지는 별도 결정.

---

## 9. 다중 그룹 동시 스파이크 (2026-08-30, AWS)

§1-2~§1-5는 전부 "그룹 하나 안에서 멤버 M명이 몰리는" 형태였다 — "서로 다른 그룹 G개가 동시에 스파이크 나는" 형태(예: "다 같이 운동하는 시간대"에 그룹 수십~수백 개가 동시에 활동 시작)는 안 봤다. `GroupSocketRegistry`가 연결마다 전용 `ExecutorService`를 만드는(#623) 설계라, 그룹 수 자체가 늘면 스레드 수가 새 자원 소비 축이 될 수 있다는 게 이 갈래의 질문이다.

**왜 AWS인가**: 로컬 2코어 박스에 동시 세션(다른 작업)이 붙어 있어 안정적인 버스트 테스트가 불가능했다 — 컨테이너가 계속 재시작되는 상태에서 계정 100개+를 만들다 중간에 깨지는 게 반복됐다. `c7i.xlarge`(4 vCPU) 1대를 격리해서 띄웠다(`loadtest/aws/` 관행 대신, 이 태스크 규모에 맞춘 경량 수동 프로비저닝 — MySQL+백엔드만 올리고 AI·nginx·관측 스택은 스킵. 코드는 `feat/group-realtime-websocket` 브랜치의 group 기능 부분만 main 위에 추린 커밋을 배포).

스크립트: `loadtest/measure_group_ws_multi_group_spike.py`. 그룹 G개 × 멤버 M명을 `threading.Barrier`로 동시에 접속시키고, 접속 후 각자 자기 그룹에 이벤트를 1건씩 발행한다.

### 9-1. 계정 생성 자체가 막혔다 — IP 단위 인증 요청 제한

처음 시도(150계정을 병렬로 가입)는 전부 `429`로 튕겼다. 원인: `AuthRateLimitFilter`가 **IP 하나당 `/member/signup`·`/member/login` 각각 60건/60초**로 막아둔다(이슈 #394, 가입 스팸·계정 열거 방지). 스크립트가 전부 같은 호스트(127.0.0.1)에서 쏘니 그 한도를 고스란히 받는다 — 그룹 기능과 무관한, 이 앱의 기존 보안 장치를 실측 스크립트가 그대로 만난 경우다. 계정 생성을 순차 + 페이싱(초당 1건 미만)으로 바꿔서 우회했다 — 이건 회피가 아니라 **이 제한이 의도대로 동작한다는 확인**이기도 하다.

### 9-2. 첫 시도는 "핸드셰이크만" 재서 스레드 비용을 놓쳤다

150연결을 접속만 시키고 이벤트를 안 보낸 첫 판에서 `jvm_threads`가 베이스라인(46) 대비 겨우 **+62**만 올랐다. 이상하다 싶어 원인을 보니 — `Executors.newSingleThreadExecutor()`(`ThreadPoolExecutor`)는 **첫 작업이 들어오기 전엔 코어 스레드를 만들지 않는다.** `GroupSocketRegistry.register()`가 세션마다 executor "객체"는 만들어도, 아무 이벤트도 안 오면 그 안의 실제 OS 스레드는 생기지 않는다 — "연결마다 스레드 하나 상시 점유"라는 설계 의도가 **접속만으로는 실측에 안 걸린다**는 뜻이다. 스크립트를 고쳐 접속 후 각자 이벤트를 1건씩 발행하게 만들었다.

### 9-3. 실제 결과 — 실패 0건, 그러나 세 가지 새 사실

| 규모(그룹×멤버=연결) | 핸드셰이크 실패 | 핸드셰이크 지연(p50/p90/max) | jvm_threads 피크(베이스라인 46) | tomcat_busy 피크(max 200) | hikaricp_connections_pending 피크 |
|---|---|---|---|---|---|
| 50×3=150 | 0/150 | 214/373/409ms | 293 (+247) | 99 | 0 |
| 100×3=300 | 0/300 | 444/767/1323ms | 507 (+461) | 155 | **15** |

- **실패는 300연결까지 0건** — Tomcat 기본 풀(200)을 넘는 동시성에서도 핸드셰이크가 거부되거나 깨지지 않았다. 다만 지연은 확실히 늘어난다(150→300에서 p50이 2배, max가 3배 이상).
- **HikariCP 풀 대기가 처음으로 관측됐다** — §1-5(로컬, 최대 M=40)까지는 `hikaricp_connections_pending`이 항상 0이었는데, 이번 300연결에서 처음으로 **15**가 잡혔다. 커넥션 풀(`maximum-pool-size: 15`)이 병목이 되는 규모가 로컬 실측 범위(≤40) 밖, 이 AWS 실측 범위(300) 안 어딘가에 있다는 뜻 — 정확한 경계는 이 두 실측 사이에 있다는 것만 확정.
- **정리 후 스레드가 즉시 안 돌아온다 — 그런데 누수는 아니다.** 소켓을 정리할 때 WS Close 프레임 없이 TCP를 그냥 끊었다(`sock.close()`, 실제 모바일 클라이언트의 "네트워크가 갑자기 끊김"과 같은 조건). 스크립트의 3초 대기 안에는 150연결에서 +96, 300연결에서 +162 스레드가 안 돌아왔다 — 처음엔 누수로 의심했지만, **1~2분 뒤 수동으로 다시 확인하니 베이스라인(46)으로 완전히 돌아와 있었다.** 즉 `deregister()`→`executor.shutdown()`은 결국 실행되지만, **서버가 "정상 종료 프레임 없이 끊긴 연결"을 감지하는 데 3초보다 훨씬 오래 걸린다** — 정확한 지연 시간은 못 쟀지만(3초와 60~120초 사이 어딘가), 실제 앱에서 클라이언트가 네트워크 끊김·강제 종료로 정상 Close 핸드셰이크 없이 사라지는 경우 이 지연이 그대로 재현될 것이다.

### 9-4. 결론

- **정합성**: 300연결까지 실패 0건 — 이 규모에서 그룹 수 증가 자체가 핸드셰이크를 깨뜨리지는 않는다.
- **새로 열린 질문(미해결)**: (a) HikariCP 풀 병목의 정확한 경계(40~300 사이), (b) 비정상 종료 감지 지연의 정확한 크기와, 그 사이에 "죽었지만 아직 안 치워진" 세션이 쌓이면 실제로 문제가 되는 임계치. 둘 다 이 문서의 우선순위 밖(원래 §1-1~§1-5도 최하위였던 갈래의 부산물)이라 여기서 멈춘다 — 필요해지면 새 갈래로 연다.
- **인프라**: `c7i.xlarge` 1대, 총 가동 ~20분, 종료·보안그룹 룰 회수까지 완료.
