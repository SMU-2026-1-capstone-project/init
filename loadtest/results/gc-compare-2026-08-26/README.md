# G1 vs ZGC — backend 쓰기 경로에서 GC 알고리즘을 바꿀 근거가 있는가 (2026-08-26)

측정일: 2026-08-26 (UTC 14:51~17:19, 5회 시도)
박스: AWS `c7i.2xlarge`(8 vCPU) · ap-northeast-2, 매 시도 임시 인스턴스(측정 후 terminate)
스택: `eclipse-temurin:21-jre` · Spring Boot(`shadowfit-backend`) · `shadowfit-ai`(mediapipe, StopAnalysis 만 사용) · MySQL 8.0
계기: [#570](https://github.com/Shadowfit/init/issues/570)(backend 컨테이너 mem_limit/cpus 부재) 수정 중 JVM/GC 튜닝 여지를 점검하다가, "지금 만질 근거가 있는가"를 실측으로 확인하기로 함

---

## 0. 한 줄

**바꿀 근거가 없다.** ZGC는 STW pause가 사실상 0(G1은 300초당 26~28ms)이지만, 이 워크로드는
pause가 애초에 문제였던 적이 없다 — Full GC 0건, 힙 committed 는 한도의 15%도 안 썼다.
G1 유지가 맞다는 결론이 실측으로 한 번 더 확인됐을 뿐, 새 결정은 아니다.

---

## 1. 실험 설계

- **팔**: G1(기본) vs ZGC(`-XX:+UseZGC -XX:+ZGenerational`), 둘 다 `-XX:MaxRAMPercentage=75.0` ·
  `mem_limit=2048m`(#570 이 새로 추가한 컨테이너 힙 옵션)
- **순서**: ABAB — 블록마다 backend 컨테이너를 해당 GC 옵션으로 재기동(워밍업 60초 버림 → 측정
  300초), 라운드 간 드리프트를 팔에 고르게 분산
- **부하**: `loadtest/k6/write_p99.js`(세션 시작 `POST /exercises/sessions` → 종료
  `PATCH /sessions/{id}/end`) — 이 프로젝트 기존 rig 재사용. `shadowfit-ai`를 같이 띄워 실제
  세션 완결 왕복(outbox → AI StopAnalysis → CompleteAnalysis 콜백)이 돌게 함
- **지표**: `actuator/prometheus` 스냅샷을 블록 전후로 떠서 `jvm_gc_pause_seconds_{sum,count}`·
  `jvm_gc_memory_allocated_bytes_total`·`jvm_memory_used_bytes{area="heap"}` 델타 계산 +
  `docker stats` CPU% 샘플링(15초 간격) + k6 자체 지연 통계

## 2. 결과 (v5 — 유효판)

| 블록 | GC | pause 합계(300초당) | pause 횟수 | 힙 사용량(종료 시점) | CPU 평균 | iters | failed | conflict |
|---|---|--:|--:|--:|--:|--:|--:|--:|
| 0 | G1 | **28ms** | 4 | 151.0MB | 11.3% | 301 | 12 | 0 |
| 1 | ZGC | **0ms** | 21 | 136.0MB | 11.6% | 300 | 7 | 0 |
| 2 | G1 | **26ms** | 4 | 189.0MB | 13.6% | 300 | 6 | 5 |
| 3 | ZGC | **0ms** | 20 | 190.0MB | 10.8% | 300 | 9 | 6 |

원자료: [`v5/summary.tsv`](v5/summary.tsv) · 블록별 프로메테우스 스냅샷 `v5/prom_b*_{before,after}.txt` ·
k6 로그 `v5/k6_b*.log` · 실행 로그 `v5/gc_experiment.log`

**읽히는 패턴**:
- G1은 모두 Young(minor) GC뿐이고(Full GC 0건, 아래 §3 참고), 300초에 4회·26~28ms 누적 — 이미 무시할 수준
- ZGC는 pause가 사실상 0(0~1ms)이지만 GC 이벤트 횟수는 더 많다(짧은 사이클을 자주 도는 설계대로)
- 힙 사용량·CPU는 두 알고리즘 사이에 뚜렷한 차이가 없다 — 블록 2·3(190MB 근처)이 오히려 GC 종류보다
  **누적 세션 수**(재기동 없이 쌓인 데이터)를 더 크게 반영하는 것으로 보인다

## 3. 힙 한도(`MaxRAMPercentage=75%`) 점검 — 튜닝 아님, 사인 체크만

블록 0 종료 시점 힙 committed ≈ **225MB**(Eden 135MB + Old 83MB + Survivor 7MB) — 컨테이너 힙
한도(2048m × 75% = 1536MB, `jvm_memory_max_bytes{area="heap",id="G1 Old Gen"}`로 실측 확인)의
15%도 안 썼다. GC 이벤트는 전부 `action="end of minor GC"`였고 major/mixed GC 는 0건이다.

**이게 "75%가 적절하다"는 근거는 아니다.** 이 부하는 RATE=1(계정당 간격 48초, §5 참고)로 낮춰
conflict 게이트를 통과시킨 것이라 실제 운영 동시성([[feedback_state_assumption_design_to_it]] 류의
가정치, DAU 1,000)과는 거리가 멀다. 말할 수 있는 것은 **"이 가벼운 부하에서 압박이 없었다"**뿐이고,
그걸 "한도를 낮춰도 된다"로 확대하면 근거 없는 값이 된다([[feedback_no_arbitrary_threshold_values]]).
비율 자체의 실측 검증은 여전히 미결이다(§6).

## 4. 판정

**G1 유지.** ZGC 로 바꿔서 얻는 것(pause 제거)이 애초에 이 백엔드에서 문제였던 적이 없고
(pause 는 이미 26~28ms/300초로 무시할 수준), 힙 사용량 대가도 이번 실측에서는 뚜렷하지 않아
바꿀 이유가 없다. GC 알고리즘·힙 비율 어느 쪽도 지금 튜닝할 근거가 없다는 것이 이 실험의 결론이다.

---

## 5. 시행착오 — v1~v4 (전부 원인 규명 후 폐기, 근거로 남긴다)

이 라운드는 다섯 번 만에 유효판이 났다. 매번 다른 원인이었고, 그 자체가 "이 워크로드를 실제로
만들려면 무엇이 맞아야 하는가"를 보여준다.

| 시도 | 무엇을 했나 | 죽은 이유 | 근거 |
|---|---|---|---|
| **v1** | `shadowfit-ai` 없이 backend·mysql 만 띄우고 RATE=13.5(가정 피크 ×180) | **conflict 4051/4051(100%)** — `PATCH /sessions/{id}/end`는 `end_time`만 쓰고, 세션이 `IN_PROGRESS`를 벗어나는 것은 AI의 `CompleteAnalysis` 콜백이 와야 한다(`measure_http_write_p99.sh` 자신의 주석). AI가 없으니 콜백이 영원히 안 와 계정마다 첫 세션 이후 영구히 갇힘 | [`v1-v3-failed/gc_experiment.log`](v1-v3-failed/gc_experiment.log) |
| **v2** | `shadowfit-ai` 추가, `ROLE=db`의 기본 `.env` 그대로 사용 | AI 부팅 즉시 실패 — `config.py`의 `_assert_tokens_separated()`(#230)가 `AI_PUBLIC_TOKEN`과 `INTERNAL_API_TOKEN`이 같으면 거부한다. `ROLE=db`는 "AI를 안 띄운다"가 전제라 두 토큰을 같은 더미값으로 넣어 두는데, 이 라운드는 AI를 실제로 띄워서 걸렸다 | [`v1-v3-failed/v2_ai_container.log`](v1-v3-failed/v2_ai_container.log)·[`v1-v3-failed/v2_experiment.log`](v1-v3-failed/v2_experiment.log) |
| **v3** | 토큰 분리 + 사전 점검(단발 요청 왕복 확인, 2초에 통과) 추가, 본 실험은 그대로 RATE=13.5·48계정 | **conflict 3522/4051(87%)** — 부하 없는 사전 점검은 2초에 왕복했지만, 실제 동시 부하 아래서는 왕복(outbox 폴 1초 + AI 처리 + 콜백)이 계정당 간격(3.56초)보다 훨씬 오래 걸림 | [`v1-v3-failed/v3_experiment.log`](v1-v3-failed/v3_experiment.log) |
| **v4** | 왕복 여유를 벌려고 계정 수 48→64로 증가(RATE=3) | **iters=0** — k6 `setup()`이 매 실행마다 계정 전부를 쉬지 않고 로그인해서 IP당 60초 60건 레이트리밋에 걸림. `write_p99.js` 자신의 주석이 "계정 수 < 60"을 이미 못박아 뒀는데 놓침 | k6 오류: `login 429(...) 인증 레이트리밋에 걸렸다` |
| **v5** ✅ | 계정 48로 원복, RATE=1(계정당 간격 48초)로 대폭 낮춤 | **성공** — conflict 0~6/300(0~2%), failed 6~12/300(2~4%) | §2 |

## 6. 정직하게 비어 있는 것

- **팔당 반복이 2회뿐이다**([[feedback_measure_design_needs_repeats]] 미달) — G1·ZGC 각 2블록
  으로는 산포를 제대로 못 잰다. 다만 pause 유무(0 vs 26~28ms)는 자릿수 자체가 달라 반복으로
  뒤집힐 결론은 아니다
- **failed 6~12/300(2~4%)·conflict 0~6/300 이 완전히 0은 아니다** — 이 프로젝트 관례상 지연
  절대값(p50/p99)을 SLO 처럼 정밀 인용하면 안 되는 조건이다. GC 델타 비교(양쪽 팔이 같은 조건)만
  유효하다고 본다
- **RATE=1은 실제 배포 부하를 흉내낸 게 아니다** — conflict 게이트를 통과시키려고 인위적으로 낮춘
  값이다. 이 실험은 "가벼운 진짜 쓰기 부하에서 GC 차이가 보이는가"에 답했을 뿐, "운영 규모 부하
  에서도 같은가"는 안 잰다
- **`MaxRAMPercentage=75%` 자체의 실측 검증은 미결**(§3) — 압박이 없었다는 사인 체크일 뿐,
  운영 동시성 가정(DAU 1,000)으로 재본 적은 없다
- **힙 사용량 차이(블록 2·3 이 0·1보다 높음)의 원인을 규명하지 않았다** — GC 알고리즘 때문인지
  누적 세션 수(재기동 사이 쌓인 데이터) 때문인지 안 갈랐다
- **AI 워커 리소스가 이 박스 조건에 맞춰져 있지 않다** — `shadowfit-ai` 는 기본값(3워커, 4 vCPU
  한도)으로 띄웠고, 실제 왕복 지연이 왜 v3 조건(RATE=13.5)에서 그렇게 길었는지 원인 자체는
  안 쟀다(outbox 폴 주기 · AI 워커 큐잉 · 네트워크 중 무엇이 지배적인지 미상)

## 7. 이 결과가 움직이는 것

없음 — G1 은 원래도 디폴트였고 이 실험은 그 선택을 반증하지 못했다(오히려 재확인). 근거 없이
새로 결정할 것을 만들지 않는다([[feedback_user_decides_not_claude]]). #570 의 `JAVA_OPTS` 주입
메커니즘·`mem_limit`은 이 실험으로 실제 작동이 검증됐다는 점만 남긴다.
