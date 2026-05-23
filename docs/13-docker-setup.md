# Docker 설정 가이드

## 사전 요구사항
- **Docker Desktop** 설치
  ```bash
  # Windows
  winget install Docker.DockerDesktop

  # 설치 후 Docker Desktop 실행 → WSL 2 활성화 확인
  docker --version
  docker compose version
  ```

## docker-compose.yml (프로젝트 루트, 실제 구성)

> 아래는 운영 중인 실제 설정. 변경 시 `docker-compose.yml` 이 단일 진실 원천.

```yaml
version: '3.8'

services:
  # MySQL 데이터베이스
  mysql:
    image: mysql:8.0
    container_name: shadowfit-mysql
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: ${MYSQL_DATABASE}
      MYSQL_USER: ${MYSQL_USER}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD}
      TZ: Asia/Seoul
    ports:
      - "${MYSQL_PORT:-3306}:3306"
    volumes:
      - mysql_data:/var/lib/mysql
      - ./mysql/schema.sql:/docker-entrypoint-initdb.d/schema.sql
      - ./mysql/data.sql:/docker-entrypoint-initdb.d/data.sql
      - ./mysql/my.cnf:/etc/mysql/conf.d/charset.cnf:ro   # 한글 charset 강제 (커밋 0fe056e)
    command: >
      --character-set-server=utf8mb4
      --collation-server=utf8mb4_unicode_ci
      --skip-character-set-client-handshake
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 20s
    networks:
      - shadowfit-net

  # Spring Boot 백엔드 (배포용)
  shadowfit-backend:
    build:
      context: ./backend
      dockerfile: Dockerfile
    container_name: shadowfit-backend
    restart: unless-stopped
    ports:
      - "8080:8080"      # REST API (외부 공개)
      - "6565:6565"      # gRPC 서버 (AI → Spring 콜백 수신)
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DB_HOST: shadowfit-mysql
      DB_USERNAME: ${DB_USERNAME}
      DB_PASSWORD: ${DB_PASSWORD}
      INTERNAL_API_TOKEN: ${INTERNAL_API_TOKEN}   # AI 와 공유 (gRPC + REST 내부 채널)
      AI_SERVER_HOST: shadowfit-ai
      AI_SERVER_GRPC_PORT: 8585
      AI_SERVER_HTTP_PORT: 8000
      JWT_SECRET: ${JWT_SECRET:-shadowfit-prod-secret-key-change-this}
      OPENAI_API_KEY: ${OPENAI_API_KEY:-}
    depends_on:
      mysql:
        condition: service_healthy
    networks:
      - shadowfit-net

  # Python AI 서버 (MediaPipe + gRPC)
  shadowfit-ai:
    build:
      context: ./ai-server
      dockerfile: Dockerfile
    container_name: shadowfit-ai
    restart: unless-stopped
    # 외부 노출 금지 — 컨테이너 내부 통신만 허용 (커밋 c7657f1)
    # HTTP(8000) 엔드포인트가 무인증이라 외부 노출 시 임의 데이터 주입 위험
    expose:
      - "8000"           # HTTP (FastAPI, 내부 전용)
      - "8585"           # gRPC (Spring 에서 호출, 내부 전용)
    environment:
      DEBUG: "false"
      INTERNAL_API_TOKEN: ${INTERNAL_API_TOKEN}
      POSE_MODEL_COMPLEXITY: 1
      BACKEND_URL: http://shadowfit-backend:8080/api/v1
    healthcheck:
      test: ["CMD", "python", "-c", "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 20s
    networks:
      - shadowfit-net

networks:
  shadowfit-net:
    driver: bridge

volumes:
  mysql_data:
    driver: local
```

### 포트 노출 정책 (커밋 c7657f1 이후)

| 컨테이너 | 호스트 노출 | 컨테이너간 통신 |
|---------|-----------|--------------|
| `shadowfit-mysql` | 3306 (개발 편의) | `shadowfit-mysql:3306` |
| `shadowfit-backend` | 8080 (REST), 6565 (gRPC) | 위와 동일 |
| `shadowfit-ai` | **없음** (`expose` 만) | `shadowfit-ai:8000`, `shadowfit-ai:8585` |

AI 의 8000/8585를 `ports` 로 열면 `INTERNAL_API_TOKEN` 검증이 없는 HTTP 경로(`/health`, `/pose` 등)에 외부에서 직접 접근 가능. 그래서 2026-05-17 부로 `expose` 만 사용.

## Backend Dockerfile

`backend/Dockerfile`:
```dockerfile
# 빌드 스테이지
FROM gradle:8.5-jdk21 AS builder
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY src ./src
RUN gradle build -x test --no-daemon

# 실행 스테이지
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
```

## 주요 Docker 명령어

### 개발 환경 (MySQL만 Docker로 실행)
```bash
# MySQL 컨테이너만 실행
docker compose up -d mysql

# MySQL 로그 확인
docker logs shadowfit-mysql

# MySQL 접속
docker exec -it shadowfit-mysql mysql -u root -pshadowfit

# MySQL 중지
docker compose stop mysql

# MySQL 중지 + 볼륨 삭제 (데이터 초기화)
docker compose down -v
```

### AI 서버 관련
```bash
# AI 서버만 실행
docker compose up -d ai-server

# AI 서버 로그 확인
docker logs shadowfit-ai

# AI 서버 헬스체크
curl http://localhost:8000/health

# Swagger API 문서
# http://localhost:8000/docs
```

### 전체 배포 (Backend + AI Server + MySQL)
```bash
# 전체 빌드 & 실행
docker compose up -d --build

# 로그 확인
docker compose logs -f

# 전체 중지
docker compose down
```

### 유용한 명령어
```bash
# 컨테이너 상태 확인
docker compose ps

# 특정 서비스 재시작
docker compose restart backend

# 빌드 캐시 없이 재빌드
docker compose build --no-cache backend
```

## 개발 시 권장 구성
- **MySQL**: Docker 컨테이너로 실행 (항상)
- **Spring Boot**: 로컬에서 `./gradlew bootRun` (핫 리로딩 지원)
- **AI Server**: 로컬에서 `uvicorn app.main:app --reload --port 8000`
- **React Native**: 로컬에서 `npx expo start`

이렇게 하면 코드 변경 시 빠른 반영이 가능하면서도,
MySQL은 Docker로 깔끔하게 관리할 수 있습니다.

## application.yml Docker 연동 설정

개발 환경에서 Docker MySQL에 연결:
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/shadowfit?useSSL=false&serverTimezone=Asia/Seoul&characterEncoding=UTF-8
    username: shadowfit
    password: shadowfit
```

배포 환경 (Docker 네트워크 내부):
```yaml
spring:
  datasource:
    url: jdbc:mysql://mysql:3306/shadowfit?useSSL=false&serverTimezone=Asia/Seoul&characterEncoding=UTF-8
    username: shadowfit
    password: shadowfit
```

## .dockerignore

`backend/.dockerignore`:
```
.gradle
build
bin
.idea
*.iml
.env
```
