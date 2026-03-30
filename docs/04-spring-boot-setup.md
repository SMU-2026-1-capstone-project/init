# Spring Boot 설치 및 설정 가이드

## 사전 요구사항
- **JDK 21** (LTS)
- **Gradle** 8.x 또는 **Maven** (Gradle 권장)
- **Docker & Docker Compose** (MySQL 컨테이너용)
- **IntelliJ IDEA** (권장) 또는 VS Code + Java Extension Pack

## 1. JDK 21 설치

### Windows
```bash
# winget으로 설치
winget install Microsoft.OpenJDK.21

# 또는 Amazon Corretto
winget install Amazon.Corretto.21

# 설치 확인
java --version
javac --version
```

### 환경 변수 설정
1. `JAVA_HOME` 시스템 변수 추가 → JDK 21 설치 경로
2. `Path`에 `%JAVA_HOME%\bin` 추가

## 2. Spring Boot 프로젝트 생성

### 방법 1: Spring Initializr (권장)
1. https://start.spring.io/ 접속
2. 설정:
   - **Project**: Gradle - Groovy
   - **Language**: Java
   - **Spring Boot**: 4.0.x (최신 안정 버전)
   - **Group**: com.shadowfit
   - **Artifact**: backend
   - **Packaging**: Jar
   - **Java**: 21
3. Dependencies 추가:
   - Spring Web
   - Spring Data JPA
   - MySQL Driver
   - Spring Security
   - Lombok
   - Spring Boot DevTools
   - Validation
4. Generate → 다운로드 → `shadowfit/backend/`에 압축 해제

### 방법 2: CLI로 생성
```bash
# Spring Boot CLI 설치 (SDKMAN 사용)
curl -s "https://get.sdkman.io" | bash
sdk install springboot

# 프로젝트 생성
spring init --dependencies=web,data-jpa,mysql,security,lombok,devtools,validation \
  --java-version=21 --type=gradle-project --group-id=com.shadowfit \
  --artifact-id=backend --name=shadowfit backend
```

## 3. build.gradle 설정

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '4.0.5'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.shadowfit'
version = '0.0.1-SNAPSHOT'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom annotationProcessor
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot 핵심
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-web'

    // MySQL
    runtimeOnly 'com.mysql:mysql-connector-j'

    // JWT 인증
    implementation 'io.jsonwebtoken:jjwt-api:0.12.5'
    runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.5'
    runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.5'

    // Swagger API 문서
    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6'

    // Lombok
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'

    // DevTools
    developmentOnly 'org.springframework.boot:spring-boot-devtools'

    // 테스트
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.security:spring-security-test'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

## 4. application.yml 설정

```yaml
# src/main/resources/application.yml
spring:
  application:
    name: shadowfit

  # 프로필 설정
  profiles:
    active: dev

---
# 개발 환경
spring:
  config:
    activate:
      on-profile: dev

  datasource:
    url: jdbc:mysql://localhost:3306/shadowfit?useSSL=false&serverTimezone=Asia/Seoul&characterEncoding=UTF-8
    username: root
    password: ${DB_PASSWORD:your_password}
    driver-class-name: com.mysql.cj.jdbc.Driver

  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.MySQLDialect

server:
  port: 8080

# JWT 설정
jwt:
  secret: ${JWT_SECRET:your-256-bit-secret-key-here-change-in-production}
  expiration: 86400000  # 24시간

# GPT API
openai:
  api-key: ${OPENAI_API_KEY}

---
# 운영 환경
spring:
  config:
    activate:
      on-profile: prod

  datasource:
    url: jdbc:mysql://${DB_HOST}:3306/shadowfit
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}

  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
```

## 5. MySQL (Docker로 실행)

Docker Compose로 MySQL을 실행합니다. 별도 MySQL 설치가 필요 없습니다.
자세한 Docker 설정은 `13-docker-setup.md`를 참고하세요.

```bash
# 프로젝트 루트에서 MySQL만 먼저 실행
docker compose up -d mysql

# DB 접속 확인
docker exec -it shadowfit-mysql mysql -u root -p
```

MySQL이 실행되면 `shadowfit` 데이터베이스가 자동 생성됩니다 (docker-compose.yml에서 설정).

## 6. 프로젝트 실행

```bash
cd backend

# Gradle Wrapper로 실행
./gradlew bootRun

# 또는 Windows
gradlew.bat bootRun

# 빌드 후 실행
./gradlew build
java -jar build/libs/backend-0.0.1-SNAPSHOT.jar
```

## 7. API 문서 확인
서버 실행 후 Swagger UI에서 API 확인:
```
http://localhost:8080/swagger-ui.html
```

## 8. IntelliJ IDEA 설정 (권장)

1. IntelliJ IDEA 실행 → Open → `shadowfit/backend` 폴더 선택
2. Gradle 자동 인식 후 의존성 다운로드
3. **Settings → Build → Compiler → Annotation Processors** → Enable annotation processing 체크 (Lombok용)
4. **Run Configuration** → Spring Boot → Main class: `com.shadowfit.ShadowfitApplication`

## 주의사항
- `application.yml`에 실제 비밀번호/API 키를 넣지 말 것 → 환경 변수 사용
- `.gitignore`에 `application-prod.yml` 추가
- `ddl-auto: update`는 개발 환경에서만 사용, 운영에서는 `validate`
