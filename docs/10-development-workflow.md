# 개발 워크플로 가이드

## 개발 환경 전체 셋업 순서

### 1단계: 도구 설치
```bash
# 1. Node.js 18+ 설치
# https://nodejs.org/

# 2. JDK 21 설치
winget install Microsoft.OpenJDK.21

# 3. Docker Desktop 설치 (MySQL 컨테이너용)
winget install Docker.DockerDesktop

# 4. Git 설치
winget install Git.Git

# 5. VS Code 또는 IntelliJ IDEA 설치
```

### 2단계: 프로젝트 초기화
```bash
# 프로젝트 루트
cd shadowfit

# Git 초기화
git init
```

### 3단계: Docker로 MySQL 실행
```bash
# 프로젝트 루트에서 (docker-compose.yml 필요 - 13-docker-setup.md 참고)
docker compose up -d mysql

# DB 자동 생성 확인
docker exec -it shadowfit-mysql mysql -u root -pshadowfit -e "SHOW DATABASES;"
```

### 4단계: Backend 셋업
```bash
# 환경 변수 설정 (.env 또는 시스템 환경 변수)
# DB_PASSWORD=shadowfit
# JWT_SECRET=your-secret-key
# OPENAI_API_KEY=sk-xxx

# 서버 실행
cd backend
./gradlew bootRun
```

### 5단계: Frontend 셋업
```bash
# Expo 프로젝트 생성
npx create-expo-app@latest frontend --template tabs
cd frontend

# 의존성 설치 (08-libraries-reference.md 참고)
# Expo 패키지 설치
npx expo install expo-camera expo-av expo-media-library ...

# npm 패키지 설치
npm install axios zustand react-native-calendars ...

# 개발 서버 시작
npx expo start
```

### 6단계: 실제 디바이스 테스트
```bash
# 1. Expo Go 앱 설치 (기본 기능 테스트)
# 2. Development Build 생성 (카메라/MediaPipe 테스트)
npm install -g eas-cli
eas build:configure
eas build --profile development --platform android
```

## Git 브랜치 전략

> 🔴 **실제로 쓰는 방식이 이 그림과 다르다** (2026-08-08 확인). `develop` 브랜치는 **존재하지 않는다**(`git branch -a` 에 0건). 실제로는 **main 하나에 짧은 작업 브랜치를 직접 PR** 하는 방식(GitHub Flow)이다.
>
> ```
> main                          ← 유일한 통합 브랜치. PR 로만 들어간다
> ├── feat/observability-stack  ← 작업 단위 브랜치, 머지 후 삭제
> ├── fix/admin-query-param-400
> ├── docs/ai-remaining-work
> └── test/pose-data-orphan-race
> ```
>
> **접두어도 `feature/` 가 아니라 `feat/` 를 쓴다.** 실제 이력에서 관측된 것:
>
> | 접두어 | 용도 | 예 |
> |---|---|---|
> | `feat/` | 새 기능 | `feat/outbox` · `feat/session-reattach` |
> | `fix/` | 버그 수정 | `fix/timeout-notifies-ai` · `fix/sync-rate-null-average` |
> | `docs/` | 문서·결정 | `docs/outbox-review-followup` |
> | `test/` | 테스트 전용 | `test/reattach-timeout-race` |
> | `chore/` · `ci/` | 잡정리·CI | `ci/backend-test-workflow` |
>
> ⚠️ `refactor/`·`hotfix/` 는 규칙에는 있지만 **실제 이력에 거의 없다.**
>
> 📌 **PR 은 필수 경로다.** main 에 직접 푸시하지 않고, CI(`backend-test.yml`·`ai-server-test.yml`·`proto-sync-check.yml`)가 통과해야 머지된다([`18-testing-guide.md`](./18-testing-guide.md) §7). CodeRabbit 리뷰도 붙는다 — 이 저장소는 리뷰에서 잡힌 결함이 여러 건 있어서 **PR 을 형식이 아니라 게이트로 쓴다.**
>
> 🔴 **머지 후 브랜치를 지운다.** 삭제 이력과 복원용 해시는 [`handoff/branch-cleanup-2026-08-06.md`](./handoff/branch-cleanup-2026-08-06.md) 에 남긴다.

### 브랜치 명명 규칙 (실제)
```
feat/{기능명}      - 새 기능
fix/{버그설명}     - 버그 수정
docs/{문서명}      - 문서 작업
test/{대상}        - 테스트 전용
chore/ · ci/       - 잡정리 · CI
```

## 개발 분담 예시 (4인 팀 기준)
| 담당 | 영역 | 주요 작업 |
|------|------|----------|
| A | Frontend - 핵심 | 카메라, MediaPipe, 싱크로율 UI |
| B | Frontend - 서비스 | 달력, 보고서, 마이페이지 |
| C | Backend | Spring Boot API, DB, JWT |
| D | AI/알고리즘 | DTW 알고리즘, GPT 피드백, TTS |

## .gitignore 설정
```gitignore
# Frontend
frontend/node_modules/
frontend/.expo/
frontend/dist/

# Backend
backend/build/
backend/.gradle/
backend/bin/
*.class

# IDE
.idea/
.vscode/
*.iml

# 환경 변수
.env
.env.local
application-prod.yml

# OS
.DS_Store
Thumbs.db

# 빌드 산출물
*.apk
*.ipa
*.jar
```

## 동시 실행 (개발 시)
터미널 3개를 열어 동시 실행:
```bash
# 터미널 0: Docker (MySQL)
cd shadowfit
docker compose up -d mysql

# 터미널 1: Backend
cd shadowfit/backend
./gradlew bootRun

# 터미널 2: Frontend
cd shadowfit/frontend
npx expo start
```

## API 테스트
```bash
# Swagger UI (브라우저)
http://localhost:8080/swagger-ui.html

# 또는 Postman / Insomnia 사용
```
