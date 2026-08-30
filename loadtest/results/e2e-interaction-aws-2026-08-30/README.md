# §3.3 E2E 상호작용 실험 — AWS 정식 라운드 (2026-08-30, 중단)

## 상태: 🔴 미완료 — 측정값 없음

이 라운드는 §3.3.4 반복 설계(A→B→A→B 교차반복)를 AWS(`c7i.4xlarge` 대상 +
`c7i.xlarge` 부하기, `proc-count-sweep-2026-08-24`와 같은 조건)에서 실행하려던 시도다.
**A 조건 1회차 계정 준비 단계에서 막혀 실측 데이터가 전혀 없다.**

## 무엇을 했나

1. 커밋 [`b29a113`](https://github.com/Shadowfit/init/commit/b29a113f8a7efa1a398a13ae28216b16ca388377)
   (브랜치 `measure/e2e-spring-ai-interaction`)를 REF로 고정해 `bootstrap.sh` 실행.
2. 대상 `i-0a19f9e263ab9b36b`(`c7i.4xlarge`) — `ROLE=p6-target`, mysql·backend·ai·ai-nginx
   전부 healthy 확인.
3. 부하기 `i-033b3d90415575481`(`c7i.xlarge`) — `ROLE=p6-loader`, 대상 private IP로
   AI(8000)·Spring(8080) 연결 확인.
4. A 조건 1회차(`--sessions 160 --dur 90 --fps 3`, `proc-count-sweep-2026-08-24`와 동일 세션수)
   실행 → **계정 59개째에서 `429`**.

## 막힌 지점 — 계정 대량 생성이 auth rate limit에 걸림

`AuthRateLimitFilter`(`application.yml` `security.rate-limit`)가 IP당 `60req/60s`로
로그인·회원가입을 제한한다(`ip-per-window: 60`). `load_ai.py`의 `setup_account`는
세션마다 회원가입+로그인 **2건을 페이싱 없이 직렬로** 호출하므로, 부하기 IP 하나에서
160세션을 준비하면 30번째 계정 근처에서 반드시 막힌다 — 이번엔 59번째(그전 재시도 포함
추정)에서 걸렸다.

**우회 시도 → 권한 분류기가 차단**: 대상 박스의 `docker-compose.yml`에 측정 전용
`AUTH_RATE_LIMIT_ENABLED=false`(코드는 이미 `application.yml`에서 이 env를 읽게 돼 있어
재빌드 불필요)를 넣으려 했으나, **보안 기능(rate limit)을 끄는 조작이라 Claude Code
권한 분류기가 자동 차단했다.** 우회를 시도하지 않고 라운드를 중단, 비용 절감을 위해
두 인스턴스를 즉시 `terminate`했다(확인: 둘 다 `terminated`, launch 시
`DeleteOnTermination=true`로 띄워 EBS도 같이 삭제됨 — 인스턴스 상태는 직접 확인했고
볼륨 상태는 launch 설정에 근거한 기대치이지 별도 재확인은 못 했다, 후속 도구 차단 때문).

## 다음 라운드를 위한 선택지 (사용자 판단 필요)

이 문서는 결정을 내리지 않는다 — [[feedback_user_decides_not_claude]].

| 옵션 | 내용 | 트레이드오프 |
|---|---|---|
| ① `AUTH_RATE_LIMIT_ENABLED=false` 명시 승인 | 사용자가 직접 컨펌하거나 권한 규칙에 추가하면 재시도 시 그대로 통과 | 측정 전용 손잡이, 프로덕션 기본값(true)은 안 건드림 — 다만 "보안 기능 끄기"라는 성격은 그대로 |
| ② 계정을 페이싱해서 만든다 | `load_ai.py`의 `setup_account` 루프에 60요청/60초를 넘지 않게 sleep 삽입(또는 60계정씩 나눠 창 리셋 대기) | 코드 수정 필요·계정 준비 시간이 160세션 기준 3~4배(≈2~3분) 늘어남. 보안 기능은 안 건드림 — **더 안전한 대안** |
| ③ 세션 수를 줄인다(예: 30) | rate limit 안에서 한 번에 준비 가능 | A 조건 처리율이 451rps 천장에서 멀어져 §3.3.3 "천장 근처" 대조 목적이 약해짐 |

**추천은 ②** — 프로덕션 코드·설정을 안 건드리고 이 실험만의 문제(계정 생성 페이싱 부재)를
이 실험 스크립트 안에서 고치는 것이라 범위가 가장 좁다.

## 로컬 스모크 테스트는 유효하다

이 AWS 중단과 별개로, `e2e-interaction-2026-08-30/`의 로컬 스모크 테스트(A 9.0req/s p99
500ms vs B 7.6req/s p99 1703ms)는 세션 3개뿐이라 이 rate limit에 안 걸렸고 그대로 유효하다
— 단 §2-1 반복 규칙 미충족·로컬 절대수치라는 기존 caveat은 그대로다.
