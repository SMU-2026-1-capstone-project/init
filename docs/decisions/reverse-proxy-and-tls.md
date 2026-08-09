# 리버스 프록시 · TLS 종료 — nginx 를 넣어야 하나

작성: 2026-08-09
상태: **분석/추천 — 미결정** (결정 ✅ 는 사용자 confirm 후 §9)
범위: 공개 엔드포인트의 TLS 종료와 그 앞단. 배포 호스트 선정 자체는 범위 밖(선행 조건으로만 다룬다)
연관: [`../19-deployment.md`](../19-deployment.md) §1·§6 · [`../tasks/24-semester2-plan.md`](../tasks/24-semester2-plan.md) Week 5 OP-02 · [`2026-05-27-channel-and-youtube-review.md`](./2026-05-27-channel-and-youtube-review.md) Tier 3

---

## 0. 한 줄 요약

**질문을 "nginx 를 넣을까" 로 두면 답이 안 나온다. 실제 질문은 "TLS 를 어디서 종료하나" 이고, 그 답은 배포 호스트가 정해지기 전에는 고를 수 없다.**

지금 nginx 를 넣으면 **검증할 곳이 없는 설정 파일이 하나 더 늘어난다** — `docker-compose.prod.yml` 이 이미 그 상태다(실호스트 검증 0회). 그리고 조사 중에, TLS 보다 먼저 걸리는 것이 나왔다: **프론트의 AI 직결 경로가 릴리스 빌드에서 애초에 안 붙는다**(§1-2).

> 📌 조직 프로필 README 가 기술 스택에 `Nginx` 를 적고 있었는데 **레포 전체에 설정도 컨테이너도 0건**이라 2026-08-09 에 뺐다([Shadowfit/.github 9030009](https://github.com/Shadowfit/.github/commit/903000956e9329370e04f517ed8a34ce6fd3869e)). 이 문서는 그걸 "지금 넣어서 사실로 만들까" 에 대한 검토다.

---

## 1. 지금 사실 (코드 기준)

### 1-1. 공개 엔드포인트가 하나가 아니다

`docker-compose.prod.yml` 이 호스트에 여는 포트:

| 포트 | 무엇 | 트래픽 성격 |
|:--:|---|---|
| 8080 | Spring REST | 요청 수는 적고 건당 작다 (세션 시작/종료·리포트·관리자) |
| 8000 | **AI HTTP — 프론트 직결** | **base64 카메라 프레임. ~3fps 로 세션 내내**([#92](https://github.com/Shadowfit/init/issues/92)) |
| 6565 | Spring gRPC | AI→Spring 콜백 |

**하나만 감싸면 의미가 없다.** 프론트는 Spring 과 AI 양쪽에 직접 붙는다 — 아키텍처가 원래 그렇다(프레임을 Spring 으로 우회시키지 않으려고 일부러 그렇게 했다).

> 🔶 **6565 는 왜 호스트에 열려 있나 — 확인 필요.** AI 는 같은 compose 네트워크(`shadowfit-net`)에 있어 서비스 이름으로 붙는다. 단일 노드 가정이면 호스트 노출이 필요 없어 보이는데, 근거를 찾지 못했다. 프록시 논의와 별개로 **닫을 수 있으면 공격면이 하나 준다.**

### 1-2. 🔴 릴리스 빌드에서 AI 직결이 안 붙는다 — TLS 보다 먼저다

```ts
// frontend/services/api.ts:27-29 — Spring 쪽은 prod 분기가 있다
const BASE_URL = __DEV__ ? `http://${resolveDevHost()}:8080` : 'https://api.shadowfit.com';

// frontend/services/aiService.ts:17 — AI 쪽은 분기 자체가 없다
return `http://${host}:8000/api/v1`;
```

- Spring 쪽은 이미 **https 를 가정**한다. 즉 HTTPS 는 "나중에 하면 되는 것"이 아니라 **프론트 릴리스 빌드가 이미 전제하고 있는 것**이다
- AI 쪽은 http 고정이고, `app.json` 에 cleartext 예외가 없다(`android`·`ios` 블록에 관련 키 없음). **iOS ATS·Android(API 28+) 기본 정책이 평문 HTTP 를 막는다**
- 도메인 `api.shadowfit.com` 은 **플레이스홀더로 보인다** (주석에 "추후 프로덕션 URL"). 보유 여부 확인 필요

**→ 프록시를 무엇으로 하든, AI 경로도 같은 도메인 아래 TLS 로 들어와야 한다.** 이건 프록시 선택보다 앞선 제약이다.

### 1-3. 배포 호스트가 없다

- CD 는 **GHCR 푸시까지만** 동작. 배포 job 미구현 — 대상이 없어서([`../19-deployment.md`](../19-deployment.md) §3.0)
- `docker-compose.prod.yml` 은 `docker compose config` 만 통과했고 **실호스트에서 돌린 적이 없다**
- HTTPS·CORS·rate limit 이 §6 체크리스트에 전부 `TODO`

### 1-4. 곁가지로 나온 것 — prod 프로파일이 사실상 비어 있다

`application.yml:4-5` 가 `spring.profiles.active: prod` 를 **기본값으로** 들고 있고, `application-prod.yml` 은 **없다**. 즉 dev 와 prod 가 같은 설정으로 돈다. 프록시를 넣으면 `server.forward-headers-strategy`(X-Forwarded-* 신뢰) 같은 값이 들어갈 자리가 바로 여기인데, 그 자리가 아직 없다.

---

## 2. 그래서 실제 선택지는 다섯이다

nginx 는 이 중 하나일 뿐이다. **모두 "TLS 를 종료하고 두 upstream(8080·8000)으로 나눈다"** 는 같은 일을 한다.

| | 설정 면적 | 인증서 갱신 | 전제 | 자원 | 비용 | 포폴 시그널 |
|---|---|---|---|---|---|---|
| **ㄱ. nginx + certbot** | 큼 (server 블록·업스트림·갱신 훅) | cron/systemd timer 로 **직접 건다** | 공인 IP + 도메인 + 80/443 개방 | 수십 MB | 호스트 비용만 | 중 — "리버스 프록시로 TLS 종료" 한 줄 |
| **ㄴ. Caddy** | 작음 (도메인 2줄) | **자동** (ACME 내장) | 위와 동일 | nginx 와 비슷 | 호스트 비용만 | 하 — 아는 면접관이 적다 |
| **ㄷ. AWS ALB** | 중 (타깃그룹·리스너·ACM) | **자동** (ACM) | **AWS 를 쓴다는 결정이 선행** | 관리형 | **ALB 고정비가 t3.small 보다 비쌀 수 있다** | 상 — AWS 시그널([[백엔드 포지션 지원]]) |
| **ㄹ. Cloudflare Tunnel** | 작음 | **자동** | 도메인을 CF 에 위임 | 커넥터 1개 | **무료 티어** | 중 — "공인 IP·포트 개방 없이" 는 말할 거리가 된다 |
| **ㅁ. 앱 자체 TLS** (Spring `server.ssl` + uvicorn `--ssl-*`) | 중 | **수동** — 두 프로세스에 각각 배포 | 도메인 | 없음 | 없음 | 하 — 오히려 감점 소지 |

**ㅁ 은 추천하지 않는다.** 인증서를 두 프로세스가 각자 들고, 갱신 때마다 둘 다 재시작해야 한다. 종료 지점을 하나로 모으는 게 프록시의 존재 이유인데 그걸 버리는 선택이다.

---

## 3. 자원 분석 — 비용은 REST 가 아니라 프레임 경로에 있다

TLS 종료 비용은 요청 수 × 바이트에 붙는다. 이 서비스에서 그건 **압도적으로 AI 프레임 경로**다.

- `frontend/app/(tabs)/exercise.tsx` 가 **330ms 간격(~3fps)** 으로 base64 프레임을 쏜다
- [#92](https://github.com/Shadowfit/init/issues/92) 기준 **세션당 800~1,350회** (휴식 중에도 쏘기 때문)
- 같은 시간 Spring 으로 가는 REST 는 세션 시작·종료 + 리포트 조회 = **한 자릿수**

즉 **프록시를 넣는다는 것은 프레임 스트림 전체를 한 번 더 거치게 한다는 뜻**이다.

> ⚠️ **여기는 추정이고 측정이 아니다.** 프레임 크기도, 프록시 통과 비용도 잰 적이 없다([[부하테스트 환경 한계]] — 이 개발 장비에서 재도 절대값은 못 쓴다). **"프록시 때문에 느려진다"고 단정하지 않는다.** 다만 인스턴스 크기를 고를 때 **CPU 예산의 대부분이 TLS + MediaPipe 쪽에 있다**는 것은 설계 전제로 삼을 만하다.
>
> 📌 이 부담을 줄이는 가장 값싼 수단은 프록시 튜닝이 아니라 **[#92](https://github.com/Shadowfit/init/issues/92)(휴식 중 전송 중단)** 다. 같은 인스턴스에서 프레임 수가 줄면 TLS·추론·대역폭이 함께 준다.

---

## 4. nginx 로 얻는 것 중 «지금» 유효한 것과 아닌 것

| 얻는 것 | 지금 유효한가 |
|---|---|
| TLS 종료 (두 upstream 을 한 도메인으로) | ✅ **이게 사실상 유일한 실사용 이유다** |
| Rate limit | ✅ [`../19-deployment.md`](../19-deployment.md) §6 의 `TODO` 를 앱 코드 없이 닫는다 |
| 보안 헤더 · CORS 정리 | 🔶 관리자 프론트가 **별도 웹**으로 확정돼 있어 곧 필요해진다([`admin-page-scope.md`](./admin-page-scope.md) §5-1) |
| AI 수평확장용 sticky session (`hash $arg_session_id`) | ❌ **아직 아니다.** [`2026-05-27-channel-and-youtube-review.md`](./2026-05-27-channel-and-youtube-review.md) Tier 3 에 있지만 `session_state` 가 인메모리라 수평확장 자체가 막혀 있다([`../tasks/30-ai-remaining-work.md`](../tasks/30-ai-remaining-work.md) §3) |
| 정적 파일 서빙 | ❌ 앱이 네이티브다. 서빙할 정적 자원이 없다 |
| 캐싱 | ❌ 카탈로그는 이미 Caffeine 으로 앱 안에서 처리한다 |

**여섯 중 둘만 지금 값을 한다.** "nginx 를 넣으면 이런 것도 된다"의 대부분은 이 프로젝트에서 아직 쓸 자리가 없다.

---

## 5. 곁가지 발견 — CORS 가 관리자 웹을 막는다

프록시 검토 중 `WebConfig.java:11-16` 을 보다 나왔다. 프록시와 별개 사안이라 여기서는 사실만 적는다.

```java
registry.addMapping("/**")
        .allowedOriginPatterns("*")
        .allowedMethods("GET", "POST", "PUT", "DELETE")   // PATCH 가 없다
        .allowCredentials(true);
```

- **`PATCH` 가 빠져 있다.** 그런데 실제 API 는 PATCH 를 쓴다 — `/sessions/{id}/end`, `/preferences/tts`, `/admin/exercises/{id}`, `/admin/exercises/{id}/thresholds`. 네이티브 앱은 CORS 를 안 타서 지금까지 안 드러났지만, **관리자 프론트가 브라우저에 뜨는 순간 preflight 에서 막힌다**
- `allowedOriginPatterns("*")` + `allowCredentials(true)` 조합도 운영 설정으로는 넓다

→ [[트러블슈팅은 GitHub 이슈로]] 에 따라 **별도 이슈로 등록할 사안**이다. 🔶 등록 여부 미결정.

---

## 6. 포폴 관점

[[백엔드 포지션 지원]] 기준. **어느 선택지든 이력서에서는 "HTTPS 붙였다" 한 줄이고, 차별점이 되지 않는다.**

- nginx 는 익숙한 이름이라 감점은 없지만 가점도 작다
- ALB 는 AWS 시그널이 붙지만, 그건 **AWS 를 쓴다는 결정의 부산물**이지 프록시 선택의 성과가 아니다
- 지금 포폴 병목은 프록시가 아니라 [`../tasks/28-remaining-work-plan.md`](../tasks/28-remaining-work-plan.md) §2 의 **#5(외부 통합)** 와 열려 있는 **인가 결함([#138](https://github.com/Shadowfit/init/issues/138))** 이다

> 다만 하나는 말할 거리가 된다 — **"프레임 스트림이 있는 서비스라 TLS 종료 지점을 고를 때 CPU 예산부터 봤다"**(§3). 이건 프록시를 넣었느냐가 아니라 **왜 그 선택을 했는지**라 신입 면접에서 값이 있다.

---

## 7. 추천 (결정 아님)

### 지금

**넣지 않는다.** 검증할 호스트가 없는 상태에서 설정 파일만 늘리는 것이고, `docker-compose.prod.yml` 이 이미 같은 형태로 미검증 상태다.

대신 **더 앞선 것부터 닫는 게 순서다**:

1. [#92](https://github.com/Shadowfit/init/issues/92) — 프레임 수를 줄이면 나중에 어떤 프록시를 고르든 예산이 준다
2. §1-2 의 **AI 직결 prod 분기 부재** — 이슈로 등록 (🔶 미결정)
3. §5 의 **CORS PATCH 누락** — 이슈로 등록 (🔶 미결정)

### 호스트가 정해지면

| 호스트 결정 | 1순위 | 이유 |
|---|---|---|
| 단일 EC2/VM | **ㄴ Caddy** | 자동 갱신. 갱신 실패는 조용히 서비스를 죽이는 사고인데, 그걸 직접 안 짜도 된다 |
| 단일 EC2/VM (팀 친숙도 우선) | **ㄱ nginx** | 팀에 nginx 를 아는 사람이 있으면 그게 더 싸다 |
| AWS 를 본격적으로 쓰기로 | **ㄷ ALB** | 포폴 시그널 + 관리형. **단 고정비가 인스턴스보다 클 수 있어 비용 확인 선행** |
| 공인 IP·포트 개방을 피하고 싶다 | **ㄹ Cloudflare Tunnel** | 무료. 학교 네트워크·집 서버라면 이게 유일한 현실안일 수 있다 |

**어느 쪽이든 백엔드가 할 일은 인증서가 아니다** — `server.forward-headers-strategy` 설정(§1-4 의 빈 prod 프로파일)과, 프록시 뒤에서 클라이언트 IP·스킴이 안 깨지는지 확인이다.

### 팀 역할

[`../tasks/24-semester2-plan.md`](../tasks/24-semester2-plan.md) Week 5 OP-02 가 *"인프라 사람이 nginx 잡으면 백엔드는 cert 경로만"* 이라고 적고 있다. **이 결정이 백엔드 단독 결정이 아닐 수 있다** — 인프라 담당이 따로 있으면 §2 표를 그쪽에 넘기는 게 맞다. 🔶 팀 내 역할 확인 필요.

---

## 8. 선행 조건 (이게 안 정해지면 §2 를 못 고른다)

| | 상태 |
|---|---|
| 배포 대상 호스트 | ❌ 없음 |
| 도메인 보유 | 🔶 `api.shadowfit.com` 은 플레이스홀더로 보임 — 확인 필요 |
| AWS 사용 여부 | 🔶 현재 AWS 는 **DB 실험 재검증용 EC2** 로만 쓰였다. 상시 인스턴스 결정과는 별개 |
| 팀 인프라 담당 | 🔶 확인 필요 (§7) |

> 📌 **TLS 가 고쳐주지 않는 것** — [#134](https://github.com/Shadowfit/init/issues/134) 는 내부 서비스 토큰이 **앱 번들에 들어간다**는 문제다. AI 직결 경로를 공개하는 이상 프록시를 앞에 세워도 토큰은 그대로 노출된다. HTTPS 는 전송을 감쌀 뿐 번들 안의 값을 감추지 못한다.

---

## 9. 결정 로그

- 2026-08-09: 문서 작성. **§7 은 추천이고 결정 전.** 🔶 미결정: ① 프록시 도입 시점·수단 ② §7 «지금» 의 이슈 2건 등록 여부 ③ 팀 역할 확인