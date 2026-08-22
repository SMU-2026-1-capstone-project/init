# 메이저 버전 업 2건 — springdoc 3.x 와 Gradle 9 는 같은 문을 통과한다

작성일: 2026-08-22
상태: **결정 대기**. §5 의 선택지 중 무엇도 채택되지 않았다.
계기: dependabot PR [#189](https://github.com/Shadowfit/init/pull/189)·[#190](https://github.com/Shadowfit/init/pull/190) 이 2026-08-11 부터 11일째 열려 있다
연관: [`architecture-review-2026-08-11.md`](./architecture-review-2026-08-11.md)

---

## 0. 한 줄 요약

**둘 다 Spring Boot 4 라인의 문턱이다.** springdoc 3.x 도 Gradle 9 도 Boot 4 와 함께 도착했고, 우리는 Boot **3.5.16** 에 남기로 되어 있다.

다만 문턱을 넘었을 때 벌어지는 일이 다르다.

| | 넘으면 | 성격 |
|---|---|---|
| #189 springdoc 3.1.0 | **부서진다** — 390건 중 219건 실패 | 사실 확인 끝. 판단할 것이 없다 |
| #190 Gradle 9.7.0 | **돈다** — 컴파일 통과 | 진짜 분기점. "지원 밖인데 도는 것"을 받을지 |

---

## 1. 현재 스택 (사실)

`backend/build.gradle`, `backend/gradle/wrapper/gradle-wrapper.properties` 기준이다.

| 항목 | 현재 |
|---|---|
| Spring Boot | **3.5.16** |
| Gradle (wrapper) | **8.14.4** |
| Java toolchain | 21 |
| `com.google.protobuf` plugin | 0.10.0 |
| `springdoc-openapi-starter-webmvc-ui` | **2.8.6** |

Boot 메이저 4.x 는 올리지 않는 방침이다. 이 문서는 그 방침을 다시 묻지 않고, **그 방침이 이 두 PR 에 무엇을 의미하는지**만 따진다.

---

## 2. #189 — springdoc 2.8.6 → 3.1.0

### 2-1. POM 이 직접 말한다

Maven Central 의 부모 POM 을 받아 확인했다.

| 버전 | 부모 POM |
|---|---|
| springdoc 2.8.6 (현재) | `spring-boot-starter-parent` **3.4.4** |
| springdoc 2.8.17 | `spring-boot-starter-parent` **3.5.13** |
| **springdoc 2.9.0** | `spring-boot-starter-parent` **3.5.16** |
| springdoc 3.1.0 (PR #189) | `spring-boot-starter-parent` **4.1.0** |

의존성 목록도 갈린다.

| | 2.8.6 | 3.1.0 |
|---|---|---|
| 톰캣 | `org.apache.tomcat.embed:tomcat-embed-core` | `org.springframework.boot:spring-boot-tomcat` |
| 액추에이터 | `spring-boot-starter-actuator` | `spring-boot-actuator-autoconfigure` |
| 테스트 | — | `spring-boot-starter-webmvc-test` |

`spring-boot-tomcat` 과 `spring-boot-starter-webmvc-test` 는 **Boot 4 에서 신설된 아티팩트**다. Boot 3.5 의 의존성 관리(BOM)에는 존재하지 않는다.

### 2-2. CI 실측이 그와 일치한다

[job 93833724741](https://github.com/Shadowfit/init/actions/runs/31507799617/job/93833724741) (2026-08-11):

```
390 tests completed, 219 failed, 5 skipped

ShadowfitApplicationTests > contextLoads() FAILED
  Caused by: java.lang.IllegalStateException at SpringApplication.java:823
    Caused by: java.lang.NoClassDefFoundError
               at ServletWebServerApplicationContextFactory.java:46
```

`ServletWebServerApplicationContextFactory` 는 **어떤 웹서버 컨텍스트를 만들지 고르는 자리**다. 여기서 `NoClassDefFoundError` 가 나면 서블릿 웹서버 인프라가 통째로 안 뜨고, 그래서 `@SpringBootTest` 계열이 전부 쓰러진다. 219건이라는 숫자는 결함 219개가 아니라 **컨텍스트 하나가 안 뜬 결과**다.

> 🔴 이건 "테스트가 깨졌으니 고치면 된다" 가 아니다. **Boot 4 를 요구하는 변경**이고, Boot 4 를 안 가기로 한 이상 고칠 방법이 없다.

### 2-3. 대신 갈 곳이 있다 — springdoc 2.9.0

2.9.0 의 부모 POM 이 **Boot 3.5.16** 이다. 우리 버전과 정확히 같다. 2.8.6 은 그 사이 11개 버전 뒤처져 있다.

`build.gradle:47` 에 적힌 commons-lang3 취약점 우회(GHSA-j288-q9x7-2f5v)가 상위 springdoc 에서 정리됐는지는 **확인하지 않았다** — 2.9.0 으로 올릴 때 별도로 봐야 한다(§8).

---

## 3. #190 — gradle-wrapper 8.14.4 → 9.7.0

### 3-1. 공식 지원 매트릭스에는 없다

Spring 문서를 직접 인용한다.

| 플러그인 | 요구 Gradle |
|---|---|
| **Boot 3.5** Gradle plugin | "requires Gradle 7.x (7.6.4 or later) **or 8.x (8.4 or later)**" |
| **Boot 4.0** Gradle plugin | "requires Gradle 8.x (8.14 or later) **or 9.x**" |

즉 **Gradle 9 지원은 Boot 4.0 플러그인부터 명시된다.** 우리가 쓰는 3.5 플러그인의 지원 목록에 9.x 는 없다.

`protobuf-gradle-plugin 0.10.0` 은 README 에서 "requires at least **Gradle 7.6** and Java 11" 이라고만 말하고, 상한이나 Gradle 9 지원 여부는 언급하지 않는다.

### 3-2. 그런데 실제로는 돈다 (2026-08-22 실측)

PR 의 초록 체크는 2026-08-11 것이고 그 사이 main 이 35커밋 앞섰다. 그래서 **오늘 다시 쟀다.**

- 베이스: `origin/main` = `adfa24b`
- 변경: `backend/gradle/wrapper/` + `gradlew` + `gradlew.bat` **만** PR #190 것으로 교체 (`build.gradle` 무수정)
- 명령: `./gradlew compileJava compileTestJava --no-daemon --console=plain`

```
> Task :generateProto
> Task :compileJava
> Task :compileTestJava

BUILD SUCCESSFUL in 2m 35s
9 actionable tasks: 9 executed
```

protobuf 코드 생성도, Boot 플러그인 구성도 Gradle 9.7.0 에서 정상 동작했다.

> ⚠️ **`:backend:test` 는 돌리지 않았다.** 컴파일까지만 확인했다. "빌드가 구성되고 컴파일된다" 와 "테스트가 통과한다" 는 다른 주장이다(§8).

### 3-3. Gradle 10 을 막는 폐기 경고는 하나뿐이고, 우리 코드다

`--warning-mode all` 로 다시 돌려 개별 경고를 확인했다.

```
Build file 'backend/build.gradle': line 45
Declaring dependencies using multi-string notation has been deprecated.
This will fail with an error in Gradle 10.
```

해당 줄:

```groovy
implementation group: 'org.modelmapper', name: 'modelmapper', version: '3.2.6'
```

→ `implementation 'org.modelmapper:modelmapper:3.2.6'` 로 바꾸면 끝난다.

**플러그인(Boot 3.5.16, protobuf 0.10.0)에서 나온 폐기 경고는 0건이다.** 즉 Gradle 9 에서 낡은 API 를 쓰고 있는 쪽은 플러그인이 아니라 우리 빌드 스크립트다. 이 한 줄은 Gradle 8 에 머물러도 무해하게 고칠 수 있다.

---

## 4. 그래서 둘은 같은 이야기인가

같은 문(Boot 4 경계)이지만 **문 너머가 다르다.**

```
Boot 3.5.16 (현재)
   │
   ├─ springdoc 3.1.0 ──→ 부모 POM 이 Boot 4.1.0
   │                      Boot 4 전용 아티팩트를 끌어옴
   │                      → 컨텍스트가 안 뜬다 (실측)
   │
   └─ Gradle 9.7.0   ──→ Boot 3.5 플러그인의 지원 목록 밖
                          그러나 컴파일은 통과 (실측)
                          → 깨지는 게 아니라 "보증이 없다"
```

#189 는 **사실**의 문제이고 #190 은 **보증**의 문제다. 전자는 판단할 게 없고, 후자는 판단해야 한다.

---

## 5. 선택지 (결정 대기)

### 5-1. #189 springdoc

| 안 | 내용 | 대가 |
|---|---|---|
| **A** | 닫고 **2.9.0** 상향 PR 을 따로 연다 | 없음에 가깝다. 부모 POM 이 우리 Boot 버전과 일치 |
| B | 닫고 2.8.6 에 머문다 | 11버전 뒤처진 채로 남는다. 보안 패치를 놓칠 수 있다 |
| C | 열어둔다 | 아무 일도 일어나지 않고 dependabot PR 만 쌓인다 |

### 5-2. #190 Gradle 9

| 안 | 내용 | 대가 |
|---|---|---|
| **A** | 닫고 Gradle 8 최신(8.14.x)에 머문다 | Gradle 9 의 이득을 포기. 다만 지금 필요한 이득이 식별되지 않았다 |
| B | 받는다 | 컴파일은 확인됐지만 **Spring 공식 지원 밖**이다. 문제가 생겨도 "지원 조합이 아니다" 로 끝난다. 되돌리기는 쉽다(래퍼 3파일) |
| C | Boot 4 로 갈 때 같이 간다 | 지금은 닫되 "Boot 4 이전 시 세트로 처리" 를 명시. 현재 Boot 4 계획은 없다 |

### 5-3. 어느 안을 고르든 별개로 할 수 있는 것

- `build.gradle:45` multi-string notation 정리 (§3-3) — Gradle 8 에서도 무해
- `dependabot.yml` 에 `ignore` 규칙 추가 (§6) — 재등장 차단

---

## 6. 왜 닫아도 또 올라오나

`.github/dependabot.yml` 현재 내용:

```yaml
groups:
  backend-dependencies:
    patterns: ["*"]
    update-types: ["minor", "patch"]
```

**minor/patch 만 그룹으로 묶는다.** 메이저는 그룹 밖 개별 PR 로 나오고, `ignore` 규칙이 없다. 그래서 #189 를 닫아도 springdoc 3.2.0 이 나오면 새 PR 이 다시 올라온다.

닫는 것만으로는 이 흐름이 멈추지 않는다는 뜻이다. 막으려면 `ignore` 에 `version-update:semver-major` 를 넣어야 하는데, 이는 **"메이저는 사람이 결정한다" 는 방침을 설정에 박는 것**이라 그 자체가 결정 대상이다.

---

## 7. 확인 방법 (재현)

```bash
# springdoc 부모 POM
curl -s https://repo1.maven.org/maven2/org/springdoc/springdoc-openapi/3.1.0/springdoc-openapi-3.1.0.pom \
  | grep -A3 spring-boot-starter-parent

# Gradle 9 실측
git worktree add --detach /tmp/g9 origin/main
cd /tmp/g9 && git checkout <pr190> -- backend/gradle/wrapper/ backend/gradlew backend/gradlew.bat
cd backend && ./gradlew compileJava compileTestJava --no-daemon --warning-mode all
```

---

## 8. 재지 않은 것 (정직 박제)

- **`:backend:test` 를 Gradle 9 에서 돌리지 않았다.** §3-2 는 컴파일까지의 주장이다. PR #190 의 08-11 초록 체크가 테스트까지 통과했지만, 그건 35커밋 뒤처진 베이스다.
- **springdoc 2.9.0 으로 실제 빌드해보지 않았다.** 부모 POM 일치는 강한 신호지만 실측은 아니다.
- **commons-lang3 명시 상향(`build.gradle:47`)이 2.9.0 에서도 필요한지 확인하지 않았다.**
- **Gradle 9 로 올렸을 때의 이득을 측정하지 않았다.** 빌드 시간·구성 캐시 등 이득이 있는지 모르는 상태에서 "이득이 식별되지 않았다"(§5-2 A)고 적었다 — 없다는 뜻이 아니라 **안 쟀다**는 뜻이다.
- **Boot 4 이전 비용은 이 문서의 범위가 아니다.** 여기서는 "안 간다" 를 전제로만 썼다.
