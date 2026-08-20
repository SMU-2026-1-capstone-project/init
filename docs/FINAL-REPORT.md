# ShadowFit 최종 결과 보고서

> 실시간 운동 자세 교정 모바일 앱 (React Native · Spring Boot · FastAPI 풀스택 MVP)
> 학교 제출·발표용 통합 보고서 — 최종 결과 / AI 활용 검증 / 발표·시연 요지를 한 권으로 통합

| 항목 | 내용 |
|---|---|
| 프로젝트명 | ShadowFit |
| 한 줄 정의 | 휴대폰 카메라만으로 운동 자세를 실시간 분석·교정하는 하이브리드 모바일 앱 |
| 기술 스택 | React Native(Expo) · Spring Boot 4.0(Java 21) · FastAPI(Python 3.12) · MySQL 8.0 · Docker |
| 핵심 기술 | MediaPipe(포즈 추정) · DTW(싱크로율) · gRPC(내부 통신) · GPT API(피드백) |
| 산출물 | 본 보고서 / 시연 영상 / 발표 자료 |

---

## 목차

1. 배경 및 동기
2. 요구사항 정의
3. 시스템 설계
4. 구현
5. 평가 (성능 실측)
6. AI 활용 검증
7. 발표·시연 요지
8. 결론 및 향후 과제
- 부록 A. API 명세 요약
- 부록 B. 데이터베이스 스키마 요약
- 부록 C. 정직성 캐비엇 (측정 한계 명시)

---

## 1. 배경 및 동기

### 1.1 문제 정의
홈 트레이닝(홈트) 인구는 늘었지만, 혼자 운동할 때 **자세가 맞는지 즉시 알려주는 피드백이 없다.** 잘못된 자세는 운동 효과를 떨어뜨리고 부상으로 이어진다. PT(개인 트레이너)는 비용 부담이 크고, 기존 홈트 앱 대부분은 영상 재생·횟수 카운트에 그쳐 **"내 자세가 기준과 얼마나 일치하는가"** 를 정량적으로 보여주지 못한다.

### 1.2 해결 아이디어
사용자가 기준 영상(로컬 영상 또는 YouTube URL)을 고르면, 휴대폰 카메라로 본인 동작을 촬영하고 **MediaPipe 관절 추출 + DTW 시계열 비교** 로 기준 동작과의 **싱크로율(0~100%)** 을 실시간 산출한다. 자세가 틀어지면 즉시 **TTS 음성 피드백**으로 교정하고, 운동이 끝나면 **달력 일지·세션 리포트**로 기록을 누적한다.

### 1.3 목표
- 카메라 한 대로 동작하는 **무(無)센서** 자세 분석 (별도 웨어러블 불필요)
- 운동 중 **실시간(저지연)** 음성 교정
- 운동 데이터의 **시계열 누적·분석** 기반 성장 추적
- 페르소나(헬린이/헬창/다이어트/재활)별 **맞춤 난이도** 적용

### 1.4 대상 운동
스쿼트(하체)를 1차 타깃으로 구현하고, 데드리프트·턱걸이를 확장 대상으로 설계했다. (현재 MVP는 스쿼트 중심)

---

## 2. 요구사항 정의

현재까지 구현된 코드 기준의 도메인별 요구사항이다.

### 2.1 회원 / 인증 (`/member`)
- 회원가입 / 로그인(JWT 발급) / 로그아웃(`JwtBlacklist` 토큰 무효화) / 회원탈퇴
- 온보딩 조회 및 단계별 부분 저장(키·몸무게·레벨·페르소나·선호 영상)
- 부가 구성: `RefreshToken` 엔티티, `JwtAuthFilter`, 인증 실패/인가 실패 커스텀 핸들러

### 2.2 온보딩 / 사용자 속성 (`Member`)
- 페르소나(`BEGINNER`/`ADVANCED`/`DIET`/`REHAB`, 기본 BEGINNER), 운동 레벨, 신체 정보(height/weight)
- 선호 영상(`preferredUrl`, `YoutubeValidator` 검증), 온보딩 완료 플래그
- TTS 설정(`ttsEnabled` 기본 true, `ttsSpeed` 기본 1.0, 0.5~2.0)

### 2.3 운동 분석 세션 (`/exercises`)
- 기준 좌표 추출: YouTube → MediaPipe → `ExerciseReference` 저장
- 세션 시작: App → Spring → gRPC → FastAPI, 즉시 `sessionId` 반환
- 세션 종료: `totalReps` 등 수신 후 `COMPLETED` 처리
- 세션 타임아웃 자동 실패: 스케줄러(1분 주기)가 (시작+예상시간+30분 버퍼) 초과 세션을 `FAILED` 처리, `@Version` 낙관락으로 FastAPI 결과 우선
- 상태 enum: `IN_PROGRESS` / `COMPLETED` / `FAILED`(+`CANCELLED`)

### 2.4 포즈 데이터 / 내부 API (`/internal/*`)
- 포즈 데이터 배치 저장: FastAPI가 `sessionId`별 배치 전송, `X-Internal-Token` 검증
- 피드백 발화 이벤트 배치 저장: **gRPC `ExerciseService.ReportFeedbackBatch`** — 세션 종료/세트 경계 시 batch 송신, 실시간 매 rep 호출 금지

### 2.5 피드백 템플릿 (`/exercises/{id}/feedback-templates`)
- 세션 시작 시 클라이언트가 받아 device TTS로 매핑
- `FeedbackType` 8종(KNEE_OUT/IN, HIP_LOW/HIGH, BACK_BENT, SHOULDER_TILT, ELBOW_BENT, HEAD_DOWN)
- (exercise+feedback_type) 유니크, `priority`로 다중 검출 시 우선순위, 발화 로그(`SessionFeedbackLog`)

### 2.6 운동 기록 / 리포트 (`/reports`)
- 주간 운동 요약, 메인 달력 데이터, 일일 메모 upsert
- 세션 상세 보고서(worst 구간, 이전 비교, 파트별 점수)
- `DailyLog`: 메모, 기분(`Mood`), 누적 운동시간, 누적 칼로리

### 2.7 TTS 환경설정 / 관리자
- TTS 설정 조회·변경(`/preferences/tts`)
- 관리자 싱크로율 임계값 변경(`/admin/exercises/{id}/thresholds`, `ROLE_ADMIN`, `beginner < advanced` 필수)

---

## 3. 시스템 설계

### 3.1 아키텍처 개요 — 3-tier 마이크로서비스
운동 분석은 **CPU 집약적 추론**(MediaPipe), 비즈니스 로직은 **트랜잭션·정합성** 중심이라 책임을 분리했다.

```
[React Native 앱]              [AI Server (FastAPI)]            [Backend (Spring Boot)]
 │ 카메라 프레임 ───────────►  │ MediaPipe 관절 추출      │      │ 회원/세션/기록/보고서 CRUD │
 │ (Base64)                    │ 관절 각도 계산           │◄────►│ 인증(JWT) · 외부 API 게이트  │
 │ ◄─────────────────────────  │ DTW 싱크로율 계산        │ gRPC │ GPT 피드백 생성            │
 │ 관절 좌표 + 싱크로율         │ 참고 영상 사전 분석      │      │ MySQL                     │
 └─ 화면 표시 / device TTS      └──────────────────────────┘      └────────────────────────────┘
```

| 구성 요소 | 역할 | 기술 |
|---|---|---|
| Spring Boot 백엔드 | 비즈니스 로직, DB, 인증, 외부 API 게이트웨이 | Java 21, Spring Security, JPA |
| FastAPI AI 서버 | 포즈 추정, 싱크로율 계산, 영상 분석 | Python 3.12, MediaPipe |
| Spring ↔ FastAPI | 분석 시작 / 포즈 수신 | gRPC + 내부 REST(`X-Internal-Token`) |

### 3.2 핵심 설계 결정
1. **실시간 부하 분리** — 운동 중 발화 피드백은 **device TTS**로 처리하고, 서버 저장은 **종료 시 배치 1회**. 매 rep마다 서버를 때리지 않아 실시간 경로를 가볍게 유지.
2. **참고 영상 전처리** — 기준 영상의 관절 좌표를 미리 추출해 DB에 1회 저장(`exercise_references`). 세션마다 재추출하지 않음.
3. **동시성 제어** — `exercise_sessions.version`(JPA `@Version`)으로 AI 완료 콜백과 타임아웃 스케줄러의 동시 갱신 충돌을 감지, 충돌 시 FastAPI 결과 우선·재시도(최대 3회).
4. **내부 API 보호** — FastAPI ↔ Spring 호출은 `X-Internal-Token` 헤더 + gRPC `InternalAuthInterceptor`(`Authorization: Bearer`)로 인증.
5. **멱등성** — 피드백 로그는 `(session_id, occurred_at, feedback_type)` 유니크 + `INSERT IGNORE`로 재시도 안전.
6. **언어 정책** — 사용자 노출 텍스트는 한국어 단일, 다국어 분리 필드를 만들지 않음.

### 3.3 데이터 저장 전략
- **1초당 평균값만 저장** — 모든 프레임이 아닌 초당 평균 포즈를 저장해 데이터량을 통제.
- **JSON 좌표** — `joint_coordinates`를 JSON 타입으로 유연하게 저장.
- **시계열 인덱스** — 세션별 시간순 조회용 `(session_id, timestamp_sec)` 복합 인덱스.
- **인코딩** — 전체 `utf8mb4` 강제(한국어 피드백 깨짐 방지).

### 3.4 사용자 플로우
```
로그인 → 온보딩(페르소나 설정) → 메인 대시보드
→ 운동 시작(기준 영상 설정 + 실시간 분석) → TTS 보조
→ 운동 기록(대시보드 / 상세 보고서) → 마이페이지
```

---

## 4. 구현

### 4.1 인증·보안 (Spring Security)
- JWT 발급/검증(`JwtAuthFilter`), 로그아웃 시 `JwtBlacklist`로 즉시 무효화, `RefreshToken` 엔티티로 재발급.
- 인증 실패(`CustomAuthenticationEntryPoint`)·인가 실패(`CustomAccessDeniedHandler`) 응답 표준화.
- 내부 호출은 외부 인증과 분리된 `X-Internal-Token` / gRPC 인터셉터로 이중화.

### 4.2 운동 세션 수명주기
- 시작 즉시 `sessionId`를 반환해 앱이 기다리지 않게 함(비동기 분석 시작).
- 종료 콜백으로 `totalReps`·싱크로율 통계 집계 후 `COMPLETED`.
- `SessionTimeoutScheduler`(1분 주기)가 방치된 세션을 `FAILED`로 정리. 콜백과 스케줄러가 같은 세션을 동시에 만지는 경합을 `@Version` 낙관락으로 해소.

### 4.3 포즈 데이터 적재
- FastAPI가 세션별 포즈를 모아 **배치 INSERT**(`SavePoseDataBatch` gRPC / 내부 REST).
- JdbcTemplate `batchUpdate` 적용 — 건별 INSERT 대비 처리량/지연 대폭 개선(§5.1).

### 4.4 리포트 / 일지
- 주간 요약·달력·세션 상세 리포트. 세션 상세는 worst 구간·이전 세션 대비·파트별 점수 제공.
- 상세 조회는 무거운 JSON blob 대신 **필요 컬럼만 projection**해 페이로드를 줄임(§5.2).
- `DailyLog`는 (user_id, log_date) 유니크 upsert로 하루 한 행 유지.

### 4.5 AI 서버 (FastAPI)
- `POST /pose` 단일 이미지 포즈 추정, `POST /sync` 싱크로율 계산, `POST /video/analyze` 기준 좌표 추출.
- MediaPipe 33개 관절 추출 → 분석 대상 관절 각도 계산 → DTW로 기준 시퀀스와 정렬·비교 → 싱크로율 산출.

### 4.6 프론트엔드 (React Native / Expo)
- 카메라 프레임 캡처(Base64) → AI 서버 전송 → 좌표·싱크로율 수신 → 화면 오버레이.
- 세션 시작 시 피드백 템플릿을 받아 device TTS로 한국어 음성 재생(`ttsSpeed` 반영).

---

## 5. 평가 (성능 실측)

> 모든 수치는 레포지토리 `docs/portfolio/realmysql-experiments.md`·`loadtest/`의 실측 결과를 인용한다. 측정 환경·한계는 **부록 C(정직성 캐비엇)** 에 명시한다.

이 프로젝트의 데이터는 **시계열 대용량(`pose_data`) + JSON 컬럼 + gRPC 동기 적재 + 집계**라는 특성을 갖는다. DAU 1,000 시나리오를 가정해 `pose_data`를 **1억 행(133,334 세션 × 750행, ~11GB) 합성 시딩**한 뒤, 쓰기·읽기·인덱스·페이지네이션·보존(파티션)의 5개 병목과 동시성(lost-update·MVCC)·저장(JSON 트림)·메모리(버퍼풀) 영역을 측정·개선했다. ⚠️ 합성 데이터는 **행수·크기는 현실적이나 값 분포는 균일**(단일 템플릿 복제)하여, 결론이 값 분포에 무관한 구조 실험만 수행하고 선택도 의존 실험(인덱스 카디널리티·옵티마이저)은 제외했다(부록 C).

### 5.1 쓰기 — 배치 INSERT
건별 INSERT → JdbcTemplate `batchUpdate` 적용:
- **처리량 +99%**, **p99 지연 −37%**.

### 5.2 읽기 — JSON projection
세션 리포트 조회 시 `joint_coordinates`(2.3KB JSON, InnoDB off-page 저장) 전체를 헛로드하던 경로를 DTO projection으로 교체(측정 시점 3컬럼, **현재 4컬럼** — `joint_coordinates` 를 안 싣는 방침은 유지):

| 지표 | before | after | 효과 |
|---|---|---|---|
| payload (로컬 412만 행) | 1,716.8 KB | 22.4 KB | **−98.7%** |
| warm 쿼리 (로컬 412만 행) | 12.1 ms | 1.5 ms | **8x (−87%)** |
| payload (AWS 1억 행) | 1,740.1 KB | 22.6 KB | **−98.7%** |
| warm 쿼리 (AWS 1억 행) | 40.6 ms | 1.4 ms | **29~41x** |

→ **측정 조건**: warm(같은 쿼리 cold 721ms → warm 12ms), 750행/세션, 측정 시점 3컬럼 프로젝션. **−98.7% 는 DB→앱 payload** 지 앱→클라이언트 응답 바이트가 아니다. 절대 ms 는 하드웨어 종속이라 배수까지만 인용한다 — 조건 전체는 [`realmysql-experiments.md` §②b](./portfolio/realmysql-experiments.md#projection-98-7).

→ 인덱스는 동일. 차이는 JSON이 off-page overflow로 저장돼 SELECT 시 추가 random I/O가 발생하는데, projection이 이를 회피한 것.

→ **1억 행 재검증(2026-07-15, AWS EC2 m6i.xlarge)에서 배수가 오히려 커졌다.** 버퍼풀은 2GB로 동일한데 테이블이 25배(~230GB) 커지며 작업셋 대비 버퍼풀 비율이 나빠져, 풀엔티티 로드의 off-page 랜덤 I/O가 캐시에 덜 걸리고 실제 디스크를 더 탄 탓. 결론이 뒤집힌 게 아니라 스케일에 비례해 강화된 것이다. payload 감소율은 두 환경에서 동일(세션당 바이트 비율이라 행수 무관).

→ ⚠️ **프레임 주의**: 이 쿼리는 사용자가 매번 누르는 조회가 아니라 **세션 종료 시 precompute가 세션당 한 번 도는 것**이고, 비동기라 사용자는 이 지연을 원래 못 느낀다. "리포트가 빨라졌다"가 아니라 **"세션마다 반복되는 잡의 I/O·버퍼풀 점유를 줄여 시스템 자원 부담을 낮췄다"**가 정확한 표현이다.

### 5.3 인덱스 & 실행계획 (EXPLAIN)
리포트 쿼리 `(session_id, timestamp_sec)` 복합 인덱스를 `EXPLAIN ANALYZE`로 검증:
- 인덱스 사용: `type=ref`, `Extra=NULL` — **filesort 없이** WHERE+ORDER BY를 인덱스가 완결.
- `IGNORE INDEX`로 풀스캔 강제 시: **412만 행 스캔 + filesort = 85초**.

→ "인덱스를 추가해 빨라졌다"가 아니라 **"이미 최적임을 측정으로 확인"** 한 것이 정직한 결론(인덱스 부재 시 85초가 lookup으로 바뀌는 역할은 대조로 입증).

### 5.4 페이지네이션 — offset → keyset (1억 행 rig)
전체 테이블 시간순 페이지네이션을 OFFSET 방식과 keyset(cursor) 방식으로 비교(`EXPLAIN ANALYZE`, warm):

| 깊이(OFFSET) | offset | keyset | speedup |
|---|---|---|---|
| 1만 | 3.04 ms | 0.079 ms | 39x |
| 10만 | 27.7 ms | 0.035 ms | 798x |
| 100만 | 290 ms | 0.034 ms | 8,504x |
| 1,000만 | 2,933 ms | 0.046 ms | 64,039x |
| **5,000만** | **25,963 ms (26초)** | **0.053 ms** | **489,868x** |

→ OFFSET은 **O(N) 선형**(깊이 10배 → 시간 ~10배, 스캔 후 폐기). keyset은 PK 범위 점프라 **깊이 무관(≈O(log n))**. 캐시돼도 선형이 유지되므로 병목은 디스크 I/O가 아니라 **행 스캔·폐기(CPU)** 자체.

### 5.5 파티션 — DROP PARTITION vs DELETE (TTL)
`pose_data`를 `created_at` 월별 14파티션으로 전환한 뒤, 만료 데이터(~8M 행) 제거를 두 방식으로 비교:

| 작업 | 행수 | 소요 | 디스크 회수 |
|---|---|---|---|
| `DELETE WHERE created_at<…` | 8,301,450 | **1,118,936 ms (18.6분)** | 952MB 그대로(미회수) |
| `ALTER … DROP PARTITION` | 7,560,000 | **1,790 ms (1.8초)** | 파일 삭제 → ~910MB 즉시 회수 |

→ **약 625배**. DELETE는 행단위 삭제(undo·인덱스 유지·락) + 빈 파일 잔존, DROP은 파티션 `.ibd` 파일째 unlink.

핵심 판단은 *"1억 행이니까 파티션"이 아니라* **"크기는 충분조건이 아니고, raw 데이터를 단기 버퍼로 보는 TTL 용도가 있어서 DROP이 정답"** 이라는 점이다. 세션 리포트 쿼리는 인덱스로 이미 빠르고 pruning 이득이 없다(오히려 파티션-로컬 인덱스로 미세 손해).

### 5.6 동시성 — lost-update 재현·방지 (트랜잭션 격리·락)
같은 사용자가 **같은 날 두 세션을 동시에 종료**하면 그날 누적 운동시간(`daily_logs`)을 더하는 read-modify-write가 경합한다. `READ COMMITTED` 격리에서 갱신 손실(lost-update)을 재현하고, 세 가지 방지책을 비교했다(`total += 10`·`+= 20`, 기댓값 30):

| 전략 | 최종값 | 손실 | 락 비용 |
|---|---|---|---|
| naive read-modify-write | 10 또는 20 | **유실** | 없음(둘 다 옛값 읽음) |
| 원자 UPDATE (`SET x = x + v`) | 30 | 없음 | UPDATE 1문장 X락 직렬화 |
| 비관락 (`SELECT … FOR UPDATE`) | 30 | 없음 | 트랜잭션 내내 X락 점유(블로킹) |
| 낙관락 (`@Version` CAS) | 30 | 없음 | 블로킹 0, 충돌 시 재시도 |

→ `READ COMMITTED`만으로는 막지 못한다(MVCC 스냅샷이라 두 트랜잭션이 같은 옛값을 읽음). `performance_schema.data_locks`로 `FOR UPDATE`의 `X,REC_NOT_GAP` 락이 한쪽은 `GRANTED`·다른 쪽은 `WAITING`임을 직접 관찰해 비관락의 직렬화를 입증했다. **단일 카운터 누적은 원자 UPDATE가 최적**(왕복·블로킹 0)이고, 실제 코드의 **타임아웃 스케줄러 ↔ FastAPI 콜백 경합**은 저경합·세션 분리라 `@Version` 낙관락(블로킹 비용 회피, 충돌 시 최대 3회 재시도)을 채택한 근거를 같은 실험으로 뒷받침했다.

### 5.7 JSON 저장 — 트림 33→13
MediaPipe가 뱉는 **33개 landmark 전부**를 `joint_coordinates`에 저장하지만(`_landmarks_to_json`), 스쿼트 분석이 실제로 쓰는 건 **13개**(`constants.py LANDMARK`)뿐이다. 사용 13개만 남기면:

- `joint_coordinates` 평균 **2,344 B → 916 B (−60.9%)**. 1억 행 환산 시 ~234GB → ~92GB.
- **키는 못 줄인다**: `z`(3D 각도)·`visibility`(추적 신뢰도)가 모두 실사용 → 13 landmark × 4 key가 트림 천장.
- 단 이는 **국소 미봉책**이고, 진짜 해법은 raw 프레임 시계열을 OLTP에서 분리(raw→buffer/serving)하는 것이다. 적용 지점은 `pose.py:93`(ai-server, 별도 결정).

### 5.8 동시성 읽기 — MVCC 스냅샷
**설계 가정(DAU 1,000)** 에선 여러 사용자의 라이브 세션 적재(쓰기)와 타 사용자의 주간/월간 요약 조회(`findByMemberIdAndStartTimeBetween`, 읽기)가 같은 테이블에 **동시 발생**하는 것이 정상 운영 조건이다(단일 세션 자체는 쓰기→종료→읽기 순차이므로, 동시성은 교차 사용자·테이블 수준). 또한 단일 집계 조회 내부에서도 수십 ms간 다수 행을 스캔하므로 그 사이 커밋된 변경이 결과를 어긋나게 하면 안 된다. 이 동시 적재↔조회가 무엇을 보는지 격리수준별로 관찰했다:

- **REPEATABLE READ(기본)**: Reader 트랜잭션 첫 SELECT=100 → 중간에 Writer가 50행 INSERT+COMMIT → 같은 트랜잭션 재SELECT **여전히 100**(스냅샷 고정), COMMIT 후 150. 일관된 읽기 보장.
- **READ COMMITTED 대비**: 같은 상황에서 재SELECT가 150(매 문장 새 스냅샷).
- **읽기↔쓰기 비블로킹(RR)**: Reader가 트랜잭션 점유 중에도 Writer INSERT가 461ms에 완료, `data_locks` **0행** — 일반 SELECT는 락 없이 undo로 과거 버전을 재구성. → 적재와 조회가 서로를 막지 않는다.
- **SERIALIZABLE 대비**: 같은 시나리오를 SERIALIZABLE로 돌리면 일반 SELECT가 잠금읽기(S 넥스트키락)로 바뀌어 `data_locks`에 `RECORD S` 101개, Writer INSERT가 **1,998ms** 블로킹(RR의 4배). 읽기가 락을 잡아 직렬화되는 MVCC의 반대편 — 격리수준 4단계 중 RR(기본)·RC·SERIALIZABLE을 직접 관찰.

### 5.9 메모리 — 버퍼풀 작업셋 vs 풀 크기
`pose_data_scale`(8,256만 행/10.4GB)을 버퍼풀 128MB로 스캔해 작업셋 크기별 캐시 효율을 측정했다:

| 작업셋 | 디스크 읽기 | hit율 | 시간 |
|---|---|---|---|
| 540MB 파티션 (≫ 풀) | cold·warm 모두 **~485MB 매번** | ~84% 고정 | ~10초 |
| 수MB PK범위 (< 풀) | warm **0MB** | 100% | ~0.45초 |

→ 작업셋이 풀을 초과하면 warm 재실행도 캐시가 무력(eviction 재miss). **⭐ 함정**: 순차 스캔은 InnoDB 선읽기(read-ahead)가 페이지를 비동기로 당겨와 **표준 hit율 공식(1−`reads`/`req`)이 거짓 99.9%** 가 된다 — `reads`는 동기 미스만 카운트하기 때문. 진짜 물리 I/O는 `read_ahead`+`Innodb_data_read`(바이트)로 봐야 84%/485MB가 드러난다.

### 5.10 측정 요약
| 영역 | 개선 | 수치 |
|---|---|---|
| 쓰기 | 배치 INSERT | 처리량 +99%, p99 −37% |
| 읽기 | JSON projection | **DB→앱** payload −98.7%, warm 쿼리 8x(로컬 412만 행, 2026-06-02) → 29~41x(AWS 1억 행, 2026-07-15). warm·750행/세션·3컬럼 시절<br>⚠️ precompute 잡의 자원 절감이지 사용자 체감 지연 아님 · [조건](./portfolio/realmysql-experiments.md#projection-98-7) |
| 인덱스 | 풀스캔 대조 | 인덱스 부재 시 85초 |
| 페이지네이션 | offset→keyset | 최대 489,868x |
| 보존 | DROP PARTITION | DELETE 대비 625x |
| 동시성 | lost-update 방지 | RC 재현 → 원자 UPDATE·낙관락 |
| JSON 저장 | 트림 33→13 | `joint_coordinates` −60.9% |
| 동시성 읽기 | MVCC 스냅샷 | RR 일관읽기·읽기쓰기 비블로킹 |
| 메모리 | 버퍼풀 작업셋 | 작업셋>풀이면 warm 무력(read-ahead 함정) |

---

## 6. AI 활용 검증

> 본 절은 프로젝트에서 사용한 AI 기술의 **사용 범위와 책임 경계**를 명시한다.
> ⚠️ **프롬프트 내역(아래 6.4)은 실제 사용 로그로 직접 채워야 하며, 보고서 작성 단계에서 임의 생성하지 않았다.**

### 6.1 MediaPipe (포즈 추정)
- 역할: 카메라 프레임에서 사람의 **33개 관절 랜드마크**(x, y, z, visibility) 추출.
- 위치: AI 서버(FastAPI, Python). React Native용 SDK보다 안정적·고성능이라 서버 측 실행.
- 산출: 관절 좌표 → 분석 대상 관절 각도 계산의 입력.

### 6.2 DTW (Dynamic Time Warping, 싱크로율)
- 역할: 사용자 동작 시계열과 **기준 동작 시계열**을 시간축 정렬 후 거리 비교 → 싱크로율(0~100%).
- 이유: 운동 속도·박자가 사람마다 달라 단순 프레임 1:1 비교가 불가능. DTW가 속도 차를 흡수.

### 6.3 GPT API (운동 종료 피드백)
- 역할: 세션 종료 후 누적 데이터(worst 구간·파트별 점수)를 바탕으로 **요약 피드백·개선 팁** 생성(`reports.summary`).
- 경계: **운동 중 실시간 경로에는 사용하지 않음.** 실시간 교정은 규칙 기반 피드백 템플릿 + device TTS가 담당. (외부 LLM 호출 지연을 실시간 경로에서 배제)

### 6.4 Claude (개발 보조) — 사용 범위 및 프롬프트 내역
- 사용 범위: 설계 트레이드오프 분석 문서화, 성능 실험 설계·해석 보조, 코드 리뷰·리팩터링 제안.
- 책임 경계: 최종 설계·구현·측정 판단은 개발자가 수행. AI는 후보 제시·검증 보조에 한정.
- **프롬프트 내역**: *(실제 사용 로그를 여기에 표로 첨부 — 작성자 직접 기입)*

  | 일자 | 목적 | 프롬프트 요지 | 채택 여부 |
  |---|---|---|---|
  | (기입) | (기입) | (기입) | (기입) |

### 6.5 AI 책임 경계 요약
| 기술 | 담당 영역 | 실시간 경로 |
|---|---|---|
| MediaPipe | 관절 추출 | O (서버) |
| DTW | 싱크로율 | O (서버) |
| 규칙 기반 템플릿 + TTS | 실시간 교정 발화 | O (앱) |
| GPT API | 종료 후 요약 피드백 | X (배치) |
| Claude | 개발 보조 | — |

---

## 7. 발표·시연 요지

### 7.1 차별점
1. **정량 싱크로율** — 영상 재생·횟수 카운트에 그치는 기존 앱과 달리, 기준 동작 대비 일치율을 0~100%로 제시.
2. **실시간 음성 교정** — device TTS로 운동 흐름을 끊지 않고 즉시 피드백(서버 왕복 없는 실시간 경로).
3. **페르소나 맞춤 난이도** — 동일 운동도 헬린이/헬창/다이어트/재활별 임계값 차등.
4. **데이터 기반 성장 추적** — 시계열 누적·세션 리포트·달력 일지.
5. **production 수준 백엔드** — 1억 행 합성 데이터로 인덱스·페이지네이션·파티션·배치 적재를 실측·튜닝.

### 7.2 데모 시나리오 (제안)
1. 로그인 → 온보딩(페르소나 선택)
2. 스쿼트 기준 영상(YouTube URL) 등록 → 기준 좌표 추출
3. 카메라로 스쿼트 수행 → 실시간 싱크로율 + 자세 틀어짐 시 TTS 교정
4. 세션 종료 → 상세 리포트(worst 구간·파트별 점수·이전 비교)
5. 달력 일지에서 누적 기록·기분 메모 확인

### 7.3 기대효과
- PT 없이도 **즉각적·정량적** 자세 피드백 제공 → 부상 위험 감소, 운동 효과 향상.
- 누적 데이터로 **자기 주도 성장 관리** 가능.
- 무센서(카메라 단독) 구조로 **접근성**이 높음.

---

## 8. 결론 및 향후 과제

### 8.1 결론
ShadowFit은 카메라 한 대로 운동 자세를 정량 분석·교정하는 풀스택 MVP를 구현했다. 추론(MediaPipe/DTW)과 비즈니스 로직을 분리한 3-tier 구조, 실시간 부하를 device TTS로 분리한 설계, `@Version` 낙관락 기반 동시성 제어로 **production을 의식한 설계**를 적용했다. 백엔드는 1억 행 데이터로 인덱스·페이지네이션·파티션·배치 적재를 **측정으로 검증**해, 추측이 아닌 증거 기반으로 병목을 다뤘다.

### 8.2 향후 과제
- 대상 운동 확장(데드리프트·턱걸이) 및 운동별 기준 좌표·피드백 템플릿 정교화.
- 트랜잭션 격리·락 실험(lost-update 재현/방지), 옵티마이저·통계 실험 등 잔여 성능 카드 완료.
- 실 사용자 기반 싱크로율 임계값 튜닝 및 TTS 발화 타이밍 UX 개선.

---

## 부록 A. API 명세 요약

| 도메인 | 주요 엔드포인트 |
|---|---|
| 회원/인증 | `POST /member/signup`·`/login`·`/logout`, `DELETE /member/{email}`, `GET·PATCH /member/onboarding/{email}` |
| 운동 세션 | `POST /exercises/{id}/reference`, `POST /exercises/sessions`, `PUT /exercises/sessions/{id}/complete` |
| 내부 API | `POST /internal/exercises/pose-data`(REST), gRPC `ExerciseService.ReportFeedbackBatch` |
| 피드백 템플릿 | `GET /exercises/{id}/feedback-templates` |
| 리포트 | `GET /reports/weekly-summary`·`/calendar`·`/session/{id}`, `POST /reports/daily-logs` |
| TTS/관리자 | `GET·PATCH /preferences/tts`, `PATCH /admin/exercises/{id}/thresholds` |
| AI 서버 | `POST /pose`·`/sync`·`/video/analyze`, `GET /sync/onboarding-guide` |

## 부록 B. 데이터베이스 스키마 요약

| 테이블 | 역할 | 핵심 컬럼 / 인덱스 |
|---|---|---|
| `users` | 회원·온보딩·TTS 설정 | `selected_persona`, `tts_enabled/speed` |
| `exercises` | 운동 마스터 | `sync_threshold_beginner/advanced`, `expected_duration_minutes` |
| `exercise_references` | 기준 좌표 | `joint_coordinates`(JSON), `idx_exercise_ref_id` |
| `exercise_sessions` | 세션 | `status`, `version`(@Version), 싱크로율 통계 |
| `pose_data` | 시계열 포즈 | `joint_coordinates`(JSON), `idx_session_timestamp` |
| `daily_logs` | 달력 일지 | `mood`, `uk_user_date` |
| `reports` | 운동 보고서 | `summary`(GPT), `comparison_with_previous`(JSON) |
| `exercise_feedback_templates` | TTS 멘트 | `priority`, `uk_exercise_feedback` |
| `session_feedback_logs` | 발화 로그 | `sync_rate_at_trigger`, `INSERT IGNORE` 멱등 |

## 부록 C. 정직성 캐비엇 (측정 한계 명시)

평가 수치를 과대 해석하지 않도록 측정 전제를 명시한다.

| 항목 | 한계 / 전제 |
|---|---|
| 데이터 규모 | `pose_data` 1억 행은 **DAU 1,000 가정의 합성 시딩**. 실 서비스 데이터가 아님. |
| 합성 JSON | 행수·payload 디커플링 위해 더미 JSON `{}` 사용(실제 2.3KB면 JSON 바이트만 ~230GB라 로컬 불가). 절대 ms는 로컬 기준, **상대 delta가 신뢰 구간**. |
| 처리량 | 실제 DAU는 작아 **처리량 자랑은 부적절**. 가치는 데이터량·정합성·측정 방법론 축. |
| 인덱스 | "추가로 빨라짐"이 아니라 **"이미 최적임을 측정으로 발견"** 이 정직한 헤드라인. |
| 파티션 | 세션 리포트엔 pruning 이득 0. 정당화는 **TTL(DROP PARTITION) 용도** 한정. 샤딩·복제는 규모상 실수요 0이라 미적용(개념만). |
| 측정 환경 | 로컬 Docker MySQL 8.0, 버퍼풀 2GB·`sort_buffer` 64M 튜닝 상태. 워밍업 통제(cold/warm 구분) 후 warm 기준. |

---

### 참고 (레포 내 근거 문서)
- `docs/01-project-overview.md` · `docs/REQUIREMENTS.md` · `docs/05-database-design.md`
- `docs/portfolio/realmysql-experiments.md` (성능 실측)
- `docs/decisions/load-test-strategy.md` · `loadtest/README.md` (부하 측정)
</content>
</invoke>
