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

## docker-compose.yml (프로젝트 루트)

```yaml
version: '3.8'

services:
  # MySQL 데이터베이스
  mysql:
    image: mysql:8.0
    container_name: shadowfit-mysql
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: shadowfit
      MYSQL_DATABASE: shadowfit
      MYSQL_USER: shadowfit
      MYSQL_PASSWORD: shadowfit
      TZ: Asia/Seoul
    ports:
      - "3306:3306"
    volumes:
      - mysql_data:/var/lib/mysql
      - ./database/schema.sql:/docker-entrypoint-initdb.d/01-schema.sql
      - ./database/seed.sql:/docker-entrypoint-initdb.d/02-seed.sql
    command: >
      --character-set-server=utf8mb4
      --collation-server=utf8mb4_unicode_ci
      --default-authentication-plugin=mysql_native_password
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 5

  # Spring Boot 백엔드 (배포용)
  backend:
    build:
      context: ./backend
      dockerfile: Dockerfile
    container_name: shadowfit-backend
    restart: unless-stopped
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DB_HOST: mysql
      DB_USERNAME: shadowfit
      DB_PASSWORD: shadowfit
      JWT_SECRET: ${JWT_SECRET:-your-256-bit-secret-key}
      OPENAI_API_KEY: ${OPENAI_API_KEY}
    depends_on:
      mysql:
        condition: service_healthy

volumes:
  mysql_data:
    driver: local
```

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

### 전체 배포 (Backend + MySQL)
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
- **React Native**: 로컬에서 `npx expo start`

이렇게 하면 백엔드 코드 변경 시 빠른 반영이 가능하면서도,
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
