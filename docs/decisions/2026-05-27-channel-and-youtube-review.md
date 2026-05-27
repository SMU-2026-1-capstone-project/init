# 검토 기록 — 실시간 채널 (REST/WS) 깊이 분석 + 유튜브 관절좌표 분석 가능성

작성일: 2026-05-27
성격: **검토·논의 기록** (새 결정 박제 아님). 기존 결정 (분기 H2 REST + 10fps 권장) 재확인 + 깊이 파기 + 미래 재검토 트리거 정리.

배경: 운동 중 분석 기능 진행 상태 점검 중 사용자가 "실시간 통신 방식" 과 "유튜브 reference 추출" 두 주제를 깊이 검토. 이 문서는 그 검토 내용을 한 곳에 보관해 다음 분기 결정 시점 (사용자 100+ 도달, 30fps 요구 등) 에 즉시 참조 가능하게 함.

관련 박제 결정:
- [`ai-backend-coupling.md`](./ai-backend-coupling.md) §5-β 분기 H → **H2 (프론트 → AI 직결 REST) 채택** (2026-05-24)
- [`ai-load-budget.md`](./ai-load-budget.md) — `VIDEO_PROCESS_FPS=10` 권장값 근거
- [`23-ai-tasks-detail.md`](../tasks/23-ai-tasks-detail.md) AI-01 — 유튜브 추출 작업 보류 (`project-squat-first` 정책)

---

# PART A. 실시간 채널 — REST vs WebSocket 깊이 분석

## 1. 현재 결정 재확인

**채택**: 분기 H2 — RN → FastAPI 직결 REST `POST /api/v1/pose`
**fps**: 권장 10, 카메라 cap 30 (`ai-server/app/config.py:17-18`)
**서버 간**: gRPC unary (분기 E1 유지) — `SavePoseDataBatch`, `CompleteAnalysis`, `StartAnalysis`, `StopAnalysis`

전체 결합 채널:

| 구간 | 프로토콜 | 형태 | 상태 |
|---|---|---|---|
| RN → FastAPI (카메라 프레임) | **REST** | `POST /api/v1/pose` 매 프레임 | 서버 ready, 프론트 미구현 |
| RN → Spring (세션·리포트·로그인) | **REST** | 일반 HTTP API | 일부 구현 |
| Spring → FastAPI | **gRPC** | unary | 완성 |
| FastAPI → Spring | **gRPC** | unary | 완성 (2026-05-26 통일) |

→ **외부(클라이언트) = REST, 내부(서버 간) = gRPC**. WebSocket 어디에도 없음.

## 2. 분기 H 4 후보 비교 (재정리)

### H1. 백엔드 프록시 REST
```
RN ──HTTP──▶ Spring ──HTTP──▶ FastAPI
```
- 장점: AI 외부 비노출, 트래픽 일원화, AI 무변경
- 단점: 한 홉 추가, 백엔드가 base64 트래픽 통과시킴
- 변경량: Spring controller+service ~70줄

### H2. 프론트 직결 REST ← **채택**
```
RN ──HTTP──▶ FastAPI (POST /pose, INTERNAL_API_TOKEN 인증)
```
- 장점: 한 홉 절감, 백엔드 부하 분산, PPT 아키텍처 일치, AI 무변경(인증 미들웨어만)
- 단점: AI 외부 노출 → 토큰 인증 필수, 백엔드 가시성 약함
- 채택 사유: 사용자 의도(직결) + 성능 + `feedback-minimize-python-changes` 정책 부합

### H3. 백엔드 자체 MediaPipe
- 장점: AI 서버 자체 폐기 가능, 단일화
- 단점: **Java MediaPipe 사실상 없음**, 기존 AI 작업 폐기, JVM CPU 부하
- 평가: 들러리 (실질 검토 가치 낮음)

### H4. WebSocket / gRPC streaming
```
RN ◄═WebSocket═▶ FastAPI ◄═gRPC bidi═▶ Spring
```
- 장점: 지연 가장 낮음, 30fps+, 양방향 push, binary frame
- 단점: AI 변경 큼 (정책 위반), Spring 변경 큼, 재연결 로직 직접
- 채택 안 됨 사유: AI 코드 변경 필요량 + 시연 fps 요구치는 REST 안에 있음

## 3. REST vs WebSocket — 본질 정리

### 3.1 한 줄 정의

- **REST = "독립성"** (매 요청 독립) → 안정성·확장성·단순성·생태계 파생
- **WebSocket = "지속성"** (연결 유지) → 효율·push·순서 보장 파생

### 3.2 양방향성 — A 유형 vs B 유형

| 종류 | 의미 | 누가 가능 |
|---|---|---|
| **A. 요청 묶음 양방향** | 클라 요청 → 서버 응답에 정보 실어 보냄 | REST, gRPC unary 모두 가능 |
| **B. 비요청 push** (server-initiated) | 클라가 안 물어봐도 서버가 먼저 메시지 | WebSocket, SSE, gRPC streaming |

→ **REST 가 못 하는 건 B 뿐**. 우리 도메인에서 빨간색 관절 표시, sync_rate 반환 같은 건 모두 A 유형 → REST 로 충분.

우리 도메인에 B 유형이 필요한 시나리오 (현재 없음):
- 운영자가 사용자 세션 원격 종료
- 점검 broadcast
- 트레이너 실시간 코칭
- 세션 timeout 즉시 통보

### 3.3 효율 비교 (1분 세션, 10fps, base64 30KB binary 가정)

| 항목 | REST | WS | 차이 |
|---|---|---|---|
| 헤더 오버헤드 | ~800B × 600 = 480KB | ~14B × 600 = 8KB | WS 50배+ 절약 |
| 페이로드 인코딩 | base64 (40KB) × 600 = 24MB | binary 30KB × 600 = 18MB | WS 33% 절약 |
| **업로드 총량** | **~25MB** | **~18MB** | **~28% 절감** |
| HTTP 파싱 CPU | ~1~3ms/요청 | ~0.1ms/메시지 | WS 우위 |

→ **시연 규모 (3~10명) 에선 차이 무의미**. MediaPipe 가 CPU 95%+ 점유라 HTTP 오버헤드는 noise. 운영 100명+ 부터 의미 가짐.

### 3.4 끊김 복구 비교

| 시나리오 | REST 복구 | WS 복구 |
|---|---|---|
| 짧은 블립 (1~2초) | 무료 (다음 요청 정상) | 재연결 로직 발동 |
| 백그라운드 (10~30초) | 무료 | 재연결 |
| 긴 오프라인 (분) | 세션 만료 확인 후 재시작 | 재연결 + 세션 동기화 |
| AI 재시작 | 세션 없음 응답 → 재시작 | + 재연결 |
| **코드 분량** | **~10줄** | **~50~100줄** |

→ 모바일 환경 (네트워크 자주 끊김) 에선 REST 의 구조적 우위 결정적.

### 3.5 확장성 + 장애 대응 — REST 압승

| 차원 | REST | WS |
|---|---|---|
| 수평 확장 (인스턴스 추가) | stateless → 자연 | sticky session 또는 외부 상태 필수 |
| 무중단 배포 | 인스턴스 교체 무자각 | 모든 연결 끊김 → thundering herd |
| 단일 요청 실패 | 다음 요청에서 무자각 복구 | 명시적 재연결 |
| 모바일 네트워크 전환 | 무자각 | 재연결 + 재인증 + 세션 동기화 |
| 장애 격리 | 요청 단위 독립 | 연결 단위 영향 |

→ **Thundering Herd**: WS 의 진짜 약점. 인스턴스 1대 다운 → N명 동시 재연결 폭주 → 다른 인스턴스도 다운 → 연쇄. REST 엔 이 문제 없음.

### 3.6 "확장성" 의 두 축 분리

서로 다른 두 축이라 모순 아님:

| 축 | REST 우위 | WS 우위 |
|---|---|---|
| 수평 확장 용이성 (늘리기 쉬움) | ✅ | ❌ |
| 자원 효율 (인스턴스당 dense) | ❌ | ✅ |

비유:
- REST = 작은 컵 여러 개 (per-user 오버헤드 큼, 컵 추가 쉬움)
- WS = 큰 항아리 (per-user 효율 좋음, 항아리 추가 어려움)

→ 같은 1000명 처리 시 REST 는 30 인스턴스, WS 는 5 인스턴스. **인프라 비용은 WS 작지만 설정·운영 복잡도는 WS 큼**.

## 4. FPS 설계

### 4.1 권장값 근거

- `VIDEO_MAX_FPS=30`: 카메라 capability cap
- `VIDEO_PROCESS_FPS=10`: 분석 권장값 (영상 전처리용. 실시간 POST 빈도는 별도)
- AI 측 enforced limit 없음 — 클라가 throttle 안 하면 30fps 도 받음 (CPU 100% 도달 가능)

### 4.2 30fps 필요 트리거

우리 도메인 안에선 30fps 가 필요한 상황 거의 없음:

| 운동 | 1 rep 길이 | 10fps 샘플 | 30fps 필요? |
|---|---|---|---|
| 스쿼트, 런지, 푸시업 | 2~4초 | 20~40 | ❌ |
| 플랭크 (정적) | hold | — | ❌ |
| 점핑잭 | 1초 | 10 | 🟡 경계 |
| **박스점프·플라이오** | 0.5~1초 | 5~10 | ✅ |
| **복싱·격투 (펀치)** | 0.2~0.4초 | 2~4 | ✅ |

→ 우리 로드맵 (스쿼트·런지·플랭크) 안에선 30fps 필요 시점 안 옴. 빠른 동작 추가 시점에만 의미.

### 4.3 시각화 부드러움은 분리 가능

- 카메라 화면: 30fps 로컬 렌더
- 서버 송신: 10fps
- 관절 오버레이: 10fps 응답 → 클라가 lerp 보간으로 30fps 화면에 부드럽게 그림

→ 시각 부드러움 때문에 30fps 송신은 over-engineering.

### 4.4 DB 적재 영향 — 회의록 vs 코드 갭

**중요**: [`05-database-design.md`](../05-database-design.md) §데이터 저장 전략 에 "**1초당 평균값만 저장 (회의록 결정사항)**" 박혀 있으나 현재 코드 (`ai-server/app/api/endpoints/pose.py:107-116`) 는 **rep 의 모든 프레임을 그대로 batch 송신** 중. 갭 존재.

적재량 비교 (사용자 10명, 1년):

| 시나리오 | 1 PoseData row | 1 세션 row | 1년 row | 1년 디스크 |
|---|---|---|---|---|
| 회의록대로 1fps | 2.1KB | 100 row | 730K row | 1.5GB |
| 현재 코드 10fps | 2.1KB | 1,000 row | 7.3M row | 15GB |
| 30fps 로 올리면 | 2.1KB | 3,000 row | 22M row | 46GB |

→ **30fps 가 진짜 문제이기 전에 회의록과 코드 갭부터**. 회의록 결정 이행 시 fps 무관 적재 1/10.

## 5. 사용자 증가 대응 — REST 파훼법 Tier 1~4

fps 고정 (10fps) + 사용자 증가만 가정한 단계적 대응:

### Tier 1 — 코드 한 줄들로 (10~30명)
1. **fps throttle 강제** — 한 사용자 폭주 방지 (다른 사용자 fair share)
2. **JPEG 품질 0.8 → 0.4 + 해상도 320x240** — 페이로드 30KB → 10KB, 처리 속도 1.5~2배
3. **회의록대로 1초당 1개 저장** — DB 부담 1/10

### Tier 2 — 단일 인스턴스 안 짜내기 (30~80명)
4. **HTTP/2 활성화** — 헤더 압축 (HPACK), multiplexing
5. **응답 페이로드 다이어트** — landmarks 핵심 6~8점만, float32, visibility 제거
6. **gzip/brotli 응답 압축** — `GZipMiddleware` 한 줄

### Tier 3 — 수평 확장 (100~500명) ⭐ 핵심
7. **AI 인스턴스 다중 + sticky session** — nginx `hash $arg_session_id`. AI 코드 무변경
8. **docker-compose `replicas: 3~5`**
9. **Spring 측 캐싱** (BE-11 + Caffeine 1분 TTL)

### Tier 4 — 진짜 운영 (500+)
10. **AI 세션 상태 Redis 외부화** (분기 D2 재검토) — AI 큰 변경 필요
11. **자동 스케일링** (k8s HPA)
12. **비동기 처리** (응답 안 기다림, 결과 push)

→ **Tier 1+2 만으로 시연·베타 (50명 이하) 충분**. Tier 3 도 sticky session 만이라 AI 무변경.

## 6. WebSocket 검토 재진입 트리거 (fps 고정 가정)

fps 가 한계 안에 머무는 한 WS 의 진짜 강점이 좁아짐. 다음 신호 중 2개 이상 관찰 시 검토:

```
□ AI CPU 70%+ 일관 도달 (1차는 Tier 3 sticky session)
□ AI CPU 중 MediaPipe 외 HTTP/JSON 처리가 20%+ 점유
□ 동시 활성 세션 100명 돌파
□ 월 클라우드 대역폭 비용이 컴퓨트 비용의 10%+
□ 사용자 컴플레인 "배터리/데이터" 패턴 누적
□ server push (B 유형) 가 본질인 기능 PR (트레이너 실시간 코칭, 운영자 강제 종료)
```

→ **2개 이상이면 HTTP/2 먼저 (싸고 효과 큼), 4개 이상이면 WS 마이그레이션 회의**.

### 중간 단계 — HTTP/2

WS 가기 전 들러야 할 단계:

| 항목 | HTTP/1.1 | HTTP/2 | WS |
|---|---|---|---|
| 헤더 압축 | 매번 ~800B | HPACK ~50B | ~14B |
| 연결 재사용 | keep-alive | multiplexing | 영구 |
| Server push | ❌ | 제한적 | ✅ |
| 코드 변경 | — | 서버 설정 1줄 | 큼 |

→ **REST 의 효율 한계는 HTTP/2 로 절반 해소**. WS 의 진짜 강점 (push) 필요 없으면 HTTP/2 가 비용 효율적.

순서: **REST/1.1 → REST/2 → WS** (건너뛰기 X)

## 7. 추천 액션 — 채널 관련

| 우선 | 항목 | 시점 |
|---|---|---|
| 🔴 | 프론트 카메라 프레임 송신 구현 (REST `POST /api/v1/pose` 100ms) | 시연 핵심 |
| 🔴 | 회의록 결정 이행 (1초당 1개 저장) — `pose.py` 약 30줄 | DB 적재 1/10 효과 |
| 🟡 | Tier 1 (throttle, JPEG↓) 적용 | 베타 진입 전 |
| 🟢 | 모니터링 (Prometheus + Grafana AI CPU·요청 큐 길이) | 운영 진입 전 |
| ⚪ | HTTP/2 활성화 | 동시 50명 도달 시 |
| ⚪ | sticky session + AI replicas | 동시 100명 도달 시 |
| ⚪ | WS 마이그레이션 검토 회의 | 위 트리거 4개+ 시 |

---

# PART B. 유튜브 관절좌표 분석 가능성

## 8. 결론

**기술적으로는 가능. 거의 다 만들어져 있고 막힌 건 "YouTube 다운로드" 한 조각**. 법적·운영 리스크와 우선순위 때문에 현재 보류 (AI-01, [`23-ai-tasks-detail.md`](../tasks/23-ai-tasks-detail.md)).

## 9. 이미 깔려 있는 것

| 조각 | 위치 | 상태 |
|---|---|---|
| Spring 진입점 | `POST /exercises/{id}/reference?youtubeUrl=` (`ExercisesController.java:33`) | 동작 |
| Spring → AI gRPC | `ExerciseAnalysisService.extractReferencePoses` (`:72-103`) | 동작 (응답 빈 배열) |
| proto 정의 | `ExtractRequest{exercise_id, youtube_url}` / `ExtractResponse{extracted_poses[]}` | 확정 |
| DB 저장소 | `exercise_references` 테이블 + `joint_coordinates` JSON | 동작 |
| 로컬 영상 → 좌표 추출 | `video_processor.analyze_video` (cv2 + MediaPipe) | 동작 |
| rep 세그먼트 → 평균 좌표 | `reference_builder.build_reference_sequence` | 동작 (스쿼트, target_length=30) |
| 오프라인 생성 스크립트 | `scripts/generate_reference_squat.py` | 동작 — **로컬 mp4 → reference JSON** |

## 10. 막힌 한 조각

`ai-server/app/grpc/exercise_servicer.py:124-139` `ExtractReferenceData` 가 stub. URL 받아서 빈 배열만 돌려줌.

## 11. 필요한 작업 (AI-01, ~6h)

1. `requirements.txt` 에 `yt-dlp` 추가 (`pytube` 는 차단 대응 약함)
2. `Dockerfile` 에 `ffmpeg` (yt-dlp 가 종종 필요)
3. `youtube_downloader.py` 신설 — URL → 임시 mp4 → finally 삭제
4. `ExtractReferenceData` 본체 = `download → analyze_video → build_reference_sequence → ExtractResponse`

→ 기존 `reference_builder` 가 로컬 mp4 입력으로 잘 돌아가므로 **사실상 다운로드 한 줄 끼우는 작업**.

## 12. 영상 길이 문제

### 12.1 실제 영상 길이 분포

| 형태 | 길이 |
|---|---|
| 짧은 폼 시연 | 30초~1분 |
| 일반 가이드 영상 | 3~10분 |
| 풀 워크아웃 | 10~30분 |
| 인기 채널 정석 자세 영상 | **5~10분이 표준** |

→ 2분 cap 비현실적. 5분 영상 처리 시간 추산: 다운로드 10~30초 + cv2/MediaPipe 30~60초 = **1~2분**.

### 12.2 해법 — 영상 길이 의존 제거

reference 는 정석 rep 평균이 목적. **영상 전체 분석 불필요** (`max_reps=5` 면 됨).

대응 4가지 (조합 가능):

1. **운영자가 구간 지정** ← 가장 효과적
   - `POST /exercises/{id}/reference?youtubeUrl=...&startSec=30&endSec=90`
   - yt-dlp 의 `--download-sections "*30-90"`
   - 영상 길이 무관, 처리 시간 = 지정 구간 분량
2. **화질 낮춰서 다운** — `-f "best[height<=360]"`. MediaPipe 정확도 360p 면 충분
3. **길이 cap 10분** — `--match-filter "duration < 600"`
4. **deadline 180초** — 운영자 등록 흐름이라 길게 OK

### 12.3 추천 조합

```
구간 지정 (필수) + 360p 다운로드 + 10분 cap + deadline 180초
```

→ 5분 영상의 30초 구간만 분석: 다운 5초 + 분석 10초 = **15초 안에 끝남**. 비동기 큐 불필요.

### 12.4 gRPC 타임아웃 / streaming 검토

긴 영상 처리 시 gRPC deadline 초과 위험 검토 → **streaming 은 타임아웃 자체를 풀지 않음** (deadline 은 전체 호출 시간에 적용). 진짜 해법:

| 옵션 | 의미 | 우리 케이스 |
|---|---|---|
| A. unary + deadline 늘리기 + 길이 제한 | 단순, 운영자 한 번 기다림 | ✅ 추천 |
| B. server-streaming (progress) | UX 좋아짐, 본질 해결 X | 과잉 |
| C. 비동기 작업 큐 (job_id + callback) | 시간 제한 없음 | 빈도 낮아 과잉 |

→ **A** (구간 지정 + cap + deadline) 으로 충분.

## 13. 휴식 구간 처리

### 13.1 자동으로 걸러지는 것

`reference_builder._segment_reps` (`reference_builder.py:67-121`) 이 이미 처리:

```python
# rep 시작
if knee_angle < 160: active = True
# bottom 도달
if knee_angle <= 100: seen_bottom = True
# rep 완료
if seen_bottom and knee_angle >= 155: rep 인정
```

그리고 score 정렬 후 top `max_reps=5` 만 사용 — 휴식/설명 구간은 rep 으로 안 잡힘.

| 비운동 구간 | 자동 처리 |
|---|---|
| 인트로/설명 (서 있음) | ✅ rep 안 잡힘 |
| 휴식 | ✅ rep 안 잡힘 |
| 트레이너 살짝 굽힘 (가짜 시범) | ✅ bottom 못 찍으면 rep 미완성 |
| 깊이 얕은 rep | ✅ depth_score=0 → top 5 에서 배제 |
| 카메라 앵글 바뀜 | ✅ landmarks 없으면 스킵 |

### 13.2 안 걸러지는 것 (실제 리스크)

| 케이스 | 대응 |
|---|---|
| MediaPipe 좌표 노이즈 (옷·조명) | **visibility threshold 추가** (1h 작업) |
| 트레이너가 다른 운동 시연 (런지 등) | 운영자 책임 + 운동 종류 매칭 |
| 카메라 앵글 정면/위 | 운영자가 옆면 영상만 등록 (정책) |
| 여러 명 출연 | 운영자 책임 + `PoseTargetTracker` ROI 튜닝 |

권장 보강 (~1h):
```python
KEY_JOINTS = [23, 24, 25, 26, 27, 28]  # 엉덩이·무릎·발목
min_vis = min(lm.visibility for lm in landmarks if lm.index in KEY_JOINTS)
if min_vis < 0.7: continue  # 가려진 프레임 제외
```

## 14. 리스크 (기술 외 영역)

| 리스크 | 무게 | 대응 |
|---|---|---|
| YouTube ToS 회색지대 | 🟡 | "운영자가 직접 등록한 영상만 허용" 정책 |
| 저작권 | 🟡 | 동상 |
| yt-dlp ↔ YouTube 차단 군비경쟁 | 🟡 | 버전 핀 + 운영 단계 주기 업데이트 |
| 운동별 분기 (스쿼트 외) | 🟢 | 스쿼트만이면 즉시 가능. 다른 운동 시 AI-02 선행 |

## 15. 현재 보류된 진짜 이유

기술 준비 ≠ 우선순위. 보류 이유 두 가지:

1. **`project-squat-first`** — 스쿼트 reference 는 `generate_reference_squat.py` 로 오프라인 생성 후 DB 시드 가능. 유튜브 URL 등록 시나리오 아직 필요 없음
2. **`feedback-minimize-python-changes`** — ai-server 는 원작자 영역. 손대기 전 협의 필요

## 16. 추천 — 유튜브 추출

| 시나리오 | 추천 |
|---|---|
| 시연용 | 안 해도 됨. 오프라인 스크립트로 스쿼트 reference DB 시드 |
| 포폴 어필용 | **운영자 등록 흐름만** 6h 작업으로 가능. AI 원작자 협의 + ToS 면피 정책 박기 |
| 새 운동 (런지·플랭크) 추가 시 | 어차피 필요. AI-02 (분석기 분리) 와 묶어서 |

권장 구현 형태 (실행 시):
```
운영자 화면:
  ① 유튜브 URL 입력 + 프리뷰 (YoutubePlayer)
  ② 슬라이더로 reference 구간 지정 (예: 0:30~1:30)
  ③ "등록" → 15~30초 대기 → 완료

서버 흐름:
  yt-dlp --download-sections "*30-90" -f "best[height<=360]" URL
  → analyze_video → build_reference_sequence (visibility 보강 적용)
  → ExtractResponse → Spring 이 exercise_references 테이블 저장
```

---

# 후속 액션 요약

| 우선 | 영역 | 항목 |
|---|---|---|
| 🔴 | 채널 | 프론트 카메라 프레임 송신 구현 (REST `POST /api/v1/pose` 100ms 간격) |
| 🔴 | 채널 | 회의록 결정 이행 — `pose.py` 1초당 1개 저장으로 변경 (~30줄) |
| 🟡 | 채널 | Tier 1 (throttle + JPEG↓) 베타 진입 전 |
| 🟢 | 채널 | 모니터링 인프라 (Prometheus + Grafana) |
| ⚪ | 채널 | HTTP/2 활성화 — 동시 50명+ 시 |
| ⚪ | 채널 | sticky session + AI replicas — 동시 100명+ 시 |
| ⚪ | 채널 | WS 마이그레이션 검토 회의 — 트리거 체크리스트 4개+ 시 |
| ⚪ | 유튜브 | AI-01 보류 유지 — 새 운동 추가 결정까지 |
| ⚪ | 유튜브 | 실행 결정 시 AI-02 (분석기 분리) 와 묶기 |

---

# 관련 문서

- [`ai-backend-coupling.md`](./ai-backend-coupling.md) — 분기 H (채택 결정), 분기 D (AI 세션 상태), 분기 E (프로토콜)
- [`ai-load-budget.md`](./ai-load-budget.md) — fps 권장값 근거
- [`session-end-trigger.md`](./session-end-trigger.md) — ET-H 결정
- [`tts-design.md`](./tts-design.md) — TTS 페르소나·BT-SET 결정
- [`05-database-design.md`](../05-database-design.md) — 회의록 "1초당 평균값" 결정 (코드와 갭 존재)
- [`23-ai-tasks-detail.md`](../tasks/23-ai-tasks-detail.md) — AI-01 (유튜브) 작업 명세, 현재 보류
- [`20-feature-roadmap.md`](../tasks/20-feature-roadmap.md) — 전체 기능 매핑
- [`22-backend-tasks-detail.md`](../tasks/22-backend-tasks-detail.md) — BE-10·11·12 (H2 부속)
