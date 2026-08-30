# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 저장소 개요

ShadowFit — 3개 서비스로 구성된 모노레포. 제품 소개는 [조직 프로필](https://github.com/Shadowfit) 참고, 이 문서는 **실행·개발**만 다룬다.

| 폴더 | 내용 |
|---|---|
| `frontend/` | React Native(Expo) 앱 — 카메라 촬영·TTS·리포트 UI |
| `backend/` | Spring Boot API 서버 — 회원·인증(JWT)·세션·리포트·gRPC 연동 |
| `ai-server/` | FastAPI 자세 분석 서버 — MediaPipe 관절 추출 + DTW 비교 |
| `mysql/` | 초기 스키마·시드(`dev-seed.sql`) — 운영 스키마는 Flyway가 관리 |
| `docs/` | 설계·운영 문서 (번호순 `NN-topic.md`) |
| `docs/decisions/` | 설계 분기점 — 후보·트레이드오프를 적어두고 사용자 결정을 기다리는 문서 |
| `docs/tasks/` | 로드맵·작업 배분 계획 |
| `loadtest/` | 부하 테스트(ghz/k6) + AWS EC2 무인 측정 인프라(`loadtest/aws/`) |

## 빌드·테스트·실행

### 전체 스택 (Docker Compose)
```bash
cp .env.example .env   # 값 채우기 — INTERNAL_API_TOKEN·AI_PUBLIC_TOKEN 등 필수
docker compose up -d   # mysql + backend + ai-server 전부 기동
```
- `docker compose build shadowfit-backend`(또는 `shadowfit-ai`) — **컨테이너가 떠 있어도 코드 변경 후엔 항상 재빌드**. `up -d`만으로는 캐시된 이미지를 재사용해 구코드가 조용히 돈다.
- Actuator(`/actuator/health`)는 별도 관리 포트 **9090**(`http://localhost:9090`)에 있다 — 앱 API(8080)와 분리돼 있다.
- Observability(Prometheus+Grafana)는 기본 꺼짐 — `docker compose --profile obs up -d`.

### Backend (`backend/`)
```bash
./gradlew bootRun       # 실행
./gradlew test          # 전체 테스트 — H2 인메모리, Docker 불필요
./gradlew test --tests "ExerciseSessionFlowIntegrationTest"   # 단일 테스트
./gradlew build         # 빌드 + 테스트
```
테스트는 `src/test/resources/application.yml`을 따로 써서 MySQL 대신 H2(`MODE=MySQL`)로 뜬다 — gRPC 서버/클라이언트도 테스트 프로파일에서 비활성화된다. 상세: [`docs/18-testing-guide.md`](./docs/18-testing-guide.md).

### AI Server (`ai-server/`)
```bash
pip install -r requirements.txt
uvicorn app.main:app --reload      # 실행
pip install -r requirements-dev.txt
python -m pytest tests -q          # 테스트 — venv에서 ai-server/ 를 작업 디렉터리로
```

### Frontend (`frontend/`)
```bash
npm install
npx expo start           # android/ios/web 서브커맨드도 있음
```
정의된 테스트 스크립트 없음.

## 아키텍처 — 서비스 결합

```
frontend --(REST)--> backend --(gRPC)--> ai-server
                ^                            |
                └──────── (gRPC callback) ───┘
frontend --(HTTP, 카메라 프레임)--> ai-server   # 분기 H2: 프론트→AI 직결
```

- **gRPC 계약은 두 곳에 중복 존재**: `backend/src/main/proto/exercise.proto`와 `ai-server/app/proto/exercise.proto`가 **바이트 단위로 동일**해야 한다. 한쪽만 고치면 CI(`proto-sync-check.yml`)가 diff로 잡지만, 로컬에서 먼저 양쪽을 손으로 맞출 것 — 안 맞으면 런타임 직렬화 오류.
- **AI 서버는 멀티프로세스**(`AI_WORKER_COUNT`, 기본 3) — `entrypoint.sh`가 워커별로 다른 포트(8000/8001/8002)에 띄우고, `ai-nginx`가 Spring이 세션 시작 응답으로 알려준 워커 인덱스(`X-AI-Worker` 헤더)로 고정 라우팅한다. 이 구조는 GIL이 프로세스당 처리량을 직렬화한다는 실측(`docs/decisions/per-process-ceiling-cause.md`)에서 나왔다 — 스레드가 아니라 프로세스를 늘리는 이유가 여기 있다.
- **AI→Spring 완료 콜백은 아웃박스 패턴**(`OutboxEvent`/`OutboxPublisher`)으로 전달을 보장한다 — 예전엔 dual-write라 3회 실패 시 유실됐다.
- **검출기 풀 크기는 컨테이너 메모리 한도에서 유도**한다(`mediapipe_detector.py`) — 검출기 1개 ≈ 98.7MB(실측)이므로 `POSE_DETECTOR_POOL_SIZE`를 안 주면 `(mem_limit − 기본 RSS) / 98.7MB`로 자동 계산된다. 근거 없는 숫자를 코드에 안 박는다는 원칙(둘 다 없으면 기동 거부).
- **`docs/architecture/`**는 Spring↔AI 결합을 4가지 각도(현황 스냅샷·시간순 changelog·커밋 단위·월별 로그)로 다룬다 — 결합면을 고칠 때(RPC 추가/삭제, 전달 보장, proto 밖 계약, 실패 처리, 판정 기준 변경) 반드시 같이 갱신한다.
- **`docs/decisions/`는 결정된 문서가 아니라 분기점 문서**다 — 후보와 트레이드오프까지만 적혀 있고 실제 채택은 사용자 confirm 후 별도로 박제된다. "이 문서에 뭐라고 적혀 있다"를 "이미 결정됐다"로 읽지 말 것.

## 부하 테스트 / AWS 측정 인프라 (`loadtest/`)

- `loadtest/aws/bootstrap.sh` — EC2 인스턴스를 측정 가능한 상태로 세팅. `ROLE` 환경변수로 분기(`db`=MySQL만, `ai-venv`=AI를 컨테이너 없이 venv로, `p6-target`/`p6-loader`=풀 스택 동거 측정 2대 구성).
- `loadtest/AWS-RIDE-ALONG.md` — 인스턴스를 띄울 때 "겸사겸사 잴 것" 목록. 새 EC2 라운드를 계획할 때 먼저 확인.
- 무인 라운드 규칙: bootstrap.sh는 **커밋 SHA로 고정**해서 받는다(브랜치 URL은 CDN 캐시로 옛 파일을 줄 수 있음), 인스턴스는 `--instance-initiated-shutdown-behavior terminate`로 띄우고 측정 끝나면 스스로 내려가게 만든다, 태그는 `Project=shadowfit-measure`.
- 이 프로젝트의 로컬 부하테스트 박스(i3-6100, 물리 2코어)는 **절대 처리량을 못 잰다** — 메커니즘·상대 비교·델타만 신뢰한다. 절대 천장이 필요하면 EC2로 올린다.

## 문서 컨벤션

- `docs/NN-topic.md` — 번호순 참조 문서(API 설계, DB 스키마, 배포 등).
- `docs/decisions/*.md` — 분기점. 다 안 읽어도 되지만, 관련 기능을 고칠 땐 같은 이름의 decision 문서가 있는지 먼저 확인.
- 이 저장소의 문서·커밋 메시지·주석은 **한국어**가 기본이다.
