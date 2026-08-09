# ShadowFit — 통합 레포


> 제품 소개·핵심 기능·아키텍처는 [조직 프로필](https://github.com/Shadowfit)을 참고하세요. 이 문서는 **이 레포를 어떻게 실행하고 개발하는지**만 다룹니다.

---

## 빠른 시작

```bash
git clone https://github.com/Shadowfit/init.git
cd init
cp .env.example .env   # 값 채우기
docker compose up -d   # mysql + backend + ai-server 전부 기동
```

프론트엔드는 별도로 `frontend/`에서 `npx expo start`. 전체 순서(사전 설치 포함)는 [`docs/14-how-to-run.md`](./docs/14-how-to-run.md) 참고.

---

## 폴더 구조

| 폴더 | 내용 |
| :--- | :--- |
| `frontend/` | React Native(Expo) 앱 |
| `backend/` | Spring Boot API 서버 |
| `ai-server/` | FastAPI 자세 분석 서버 |
| `mysql/` | 초기 스키마·시드(`schema.sql`, `data.sql`) |
| `docs/` | 설계·운영 문서 (API, DB, 아키텍처, 트러블슈팅 등) |
| `loadtest/` | 부하 테스트 스크립트 (ghz, seed 스크립트) |
| `postman/` | API 테스트용 Postman 컬렉션 |

전체 구조는 [`docs/02-folder-structure.md`](./docs/02-folder-structure.md)에 더 자세히 있습니다.

---

## 파트별 안내

### 📱 Frontend — `frontend/`

React Native(Expo) 클라이언트. 화면, 카메라 촬영, TTS 재생을 담당합니다.

```bash
cd frontend
npm install
npx expo start
```

### ⚙️ Backend — `backend/`

Spring Boot API 서버. 회원·인증(JWT)·세션 라이프사이클·리포트·gRPC 연동을 담당합니다.

```bash
cd backend
./gradlew bootRun
```

- API 문서: 로컬 기동 후 `http://localhost:8080/swagger-ui`
- 설계 문서: [`docs/07-api-design.md`](./docs/07-api-design.md), [`docs/05-database-design.md`](./docs/05-database-design.md)

### 🤖 AI Server — `ai-server/`

FastAPI 서버. MediaPipe로 관절 좌표를 추출하고 DTW로 기준 동작과 비교합니다.

```bash
cd ai-server
pip install -r requirements.txt
uvicorn app.main:app --reload
```

- 가이드: [`docs/06-mediapipe-guide.md`](./docs/06-mediapipe-guide.md)

---

## 서비스 간 연동

Backend ↔ AI Server는 gRPC로 통신합니다. 스키마는 양쪽에 각각 있는 `exercise.proto`이며, 하나를 고치면 **양쪽 다 수동으로 동기화**해야 합니다. 결합 구조 상세는 [`docs/architecture/`](./docs/architecture/README.md) 참고.

```
frontend --(REST)--> backend --(gRPC)--> ai-server
                ^                            |
                └──────── (gRPC callback) ───┘
frontend --(HTTP, 카메라 프레임)--> ai-server
```

---

## 더 읽을 문서

| 문서 | 내용 |
| :--- | :--- |
| [`docs/14-how-to-run.md`](./docs/14-how-to-run.md) | 처음 세팅부터 실행까지 전체 가이드 |
| [`docs/13-docker-setup.md`](./docs/13-docker-setup.md) | Docker Compose 구성 |
| [`docs/07-api-design.md`](./docs/07-api-design.md) | REST API 설계 |
| [`docs/05-database-design.md`](./docs/05-database-design.md) | DB 스키마 |
| [`docs/architecture/`](./docs/architecture/README.md) | AI↔Backend 결합 구조 |
| [`docs/18-testing-guide.md`](./docs/18-testing-guide.md) | 테스트 가이드 |
| [`docs/17-error-codes.md`](./docs/17-error-codes.md) | 에러 코드 |

---

