# 작업 기록 — 2026-08-20~21 · 브랜치 정리 + M1(신뢰성·보안)

작성: 2026-08-21 (한도 소진으로 중단, 재개용 handoff)
범위: 이 세션에서 한 것 / 안 끝난 것 / 다음에 할 것. **판정은 파일 위치가 아니라 계약 기준**
연관: [`../decisions/project-destination-and-exit-criteria.md`](../decisions/project-destination-and-exit-criteria.md)(E1~E4),
[`../decisions/ai-session-ownership-verification.md`](../decisions/ai-session-ownership-verification.md)(#187 안 (d)),
[`../decisions/pose-batch-idempotency-implementation.md`](../decisions/pose-batch-idempotency-implementation.md)(#188·#276)

> ✅ **추기 (2026-08-22 커밋 시점) — §3 은 §3-4 만 남고 다 닫혔다.**
> 이 문서는 **2026-08-21 의 기록**이라 아래 «가장 먼저 볼 것» 이 이미 끝난 곳을 가리킨다.
>
> | 항목 | 그때 | 지금 |
> |---|---|---|
> | §3-1 #304 — #187 (b) 콜백 가드 | 머지 직전 | ✅ 머지 (`a8294b9`) |
> | §3-2 #185 — refresh token 해싱 | PR 없음 | ✅ [#309](https://github.com/Shadowfit/init/pull/309) 머지 · 이슈 #185 닫힘 |
> | §3-3 #187 (d) — 본체 방어 | **미착수** | ✅ [#323](https://github.com/Shadowfit/init/pull/323)(nonce) + [#326](https://github.com/Shadowfit/init/pull/326)(강제) 머지 · 이슈 #187 닫힘 |
> | §4 #296 — 탐침 간헐 실패 | 열림 | ✅ [#305](https://github.com/Shadowfit/init/pull/305)·[#321](https://github.com/Shadowfit/init/pull/321) 로 닫힘 |
> | §3-4 #291 — 진척 % | draft | 🟡 **여전히 draft — 유일하게 남은 것** |
>
> §5 의 M1 판도 갱신됐다: #187·#185 가 닫혀 **남은 것은 M4(운영·배포)** 다. #276 은 문서가 적어둔 대로 여전히 열려 있다(워커 8 한 점만 실측).
>
> **본문은 그때 쓴 그대로 둔다** — 고쳐 쓰면 «그 시점에 무엇을 몰랐는지» 가 사라진다.

> 🔴 **가장 먼저 볼 것**: 아래 §3 «안 끝난 것». 커밋은 다 됐지만 **#185 는 전체 스위트·PR·머지가 남았고**,
> #187 은 (b)만 됐고 **본체 방어 (d)는 미착수**다.

---

## 0. 한 줄

M1(신뢰성·보안 최소선)에서 **#276·#188 은 닫혔고**, #187 은 심층방어 (b)만·본체 (d)는 미착수,
#185 는 **코드 완료·검증 부분·머지 전**이다. 브랜치 정리도 같이 했다.

---

## 1. 머지 완료 (origin/main 에 들어감)

| PR | 내용 | main 커밋 |
|---|---|---|
| #289 | E3 복제 로컬 라운드 — DBA 결손 3축 마지막이 닫힘 | `28fec8d` |
| #290 | 스타일 식별자 설계(분석/추천) | `f4bf756` |
| #236 | 일지 메모 두 번 500 (#215) | `a1736ac` |
| #270 | 타임아웃 스윕 적재량 선형 (#207) | `488d79e` |
| #280 | **pose 배치 멱등 + 데드락 재시도** (#188·#276) | `4e1d64c` |
| #295 | AI 응답 계약 두 자리 (#218·#267) — **다른 세션이 머지** | `8258e3e` |

### #280 이 실제로 넣은 것 (M1 핵심)
- **#188**: `SavePoseDataBatch` 재전송(AI 3회) + 수신측 멱등(`uk_pose_event` 세션앵커 + ODKU). **이슈 닫음**
- **#276**: 그 멱등 키가 만든 데드락에 **재시도 최대 2회**(`ExerciseGrpcService.savePoseDataBatchWithDeadlockRetry`).
  값은 실측(0회 37.8%·1회 3.8%·**2회 0.0%**). gRPC 핸들러에 건 이유 = 데드락이 `@Transactional` 을
  롤백하므로 트랜잭션 **밖**에서 재시도해야 하고, 자기주입(#175)은 함정이라 핸들러가 자연스러운 «밖».
  관측 `shadowfit.pose.batch.deadlock.retries`. **이슈는 열어 둠**(워커 8 한 점만 실측)
- 마이그레이션 V5→**V6** 승격(main 의 V5 와 충돌, #274)

---

## 2. 브랜치 정리 (완료)

- 삭제: `refactor/remove-app-complete-path`(#244 포함), `feat/193-feedback-rep-key`(#238),
  `work/2026-08-15`·`fix/idempotent-duplicate-writes`·`fix/ddl-rig-stop-writer`·`work/2026-08-15-p6`(전부 대체·중복)
- PR 로 올림: #289(머지됨), #290(머지됨), #291(draft — 아래 §3)

---

## 3. 🔴 안 끝난 것 (재개 시 여기부터)

### 3-1. #304 — #187 (b) 콜백 상태 가드 · **머지 직전**
- 브랜치 `fix/187-callback-status-guard`, 워크트리 `E:/init-wt-187b`
- 내용: `savePoseDataBatch` 가 세션이 `IN_PROGRESS` 아니면 배치 **드롭**(종료 후 주입·늦은 정상배치).
  관측 `shadowfit.pose.batch.rejected`. `ReattachTimeoutRaceTest` 의 옛 동작 단언도 새 동작으로 갱신
- **로컬 전체 스위트 통과(exit 0)**. CI 는 **flaky #296**(`GrpcServerDeadlineProbeTest:200`, 타이밍)만 실패 →
  **재실행 걸어 둠**(run 32387432462). ✅ **할 일: CI green 확인 후 `gh pr merge 304 --squash --delete-branch`**
- ⚠️ 이건 #187 을 **안 닫는다** — «종료 후 창»만 막는 심층방어. 본체는 (d)

### 3-2. #185 — refresh token 해싱 · **검증 부분·PR 없음**
- 브랜치 `fix/185-hash-refresh-token`(푸시됨), 워크트리 `E:/init-wt-185`, 커밋 `3b288c6`
- 완료: `RefreshTokenHasher`(SHA-256 hex) 신설 · `MemberService` 로그인·재발급 해시 저장 ·
  **유예 경로 ㄱ(회전 발급)** · V7(기존 평문 행 삭제) · 죽은 `deleteByToken` 제거
- 검증: `TokenReissueIntegrationTest`(6, 유예 회전·반복유실 revoke 신규) + `MemberServiceTest` **통과**
- **할 일**: ① **전체 스위트 1회**(`cd E:/init-wt-185/backend && ./gradlew test`) ② PR 생성 ③ CI green ④ 머지
- 결정 근거(사용자 confirm): 해시=SHA-256(고엔트로피라 BCrypt 불필요) · 유예=**ㄱ**(ㄴ=UX후퇴, ㄷ=배포 종속·방어약함).
  **실사용자 생기면 ㄷ(가역 암호화)로 승격 재검토** — ㄱ은 막다른 길 아님(해시라 재노출 없음)

### 3-3. #187 본체 방어 (d) — **미착수**
- 안 (d) 세션 nonce + (b). 설계 `ai-session-ownership-verification.md` §3-(d)·§7
- 🟢 **호재**: #295 가 머지되면서 (d)가 손댈 AI 파일 넷의 충돌 위험이 **사라짐**. 이제 현재 main 위에서 바로 가능
- 범위(14파일): proto 양쪽 + `V8` + `Session`/`SessionService`(nonce 생성) + 두 응답 DTO +
  `ExerciseAnalysisService`(start·reattach 에 nonce) + AI `session_state.py`·`exercise_servicer.py`·`pose.py`·`models/pose.py` + pb2 **2벌**(#132)
- **2단계**: ① nonce 오면 검증·없으면 통과(compat) → ② 프론트 동봉 후 강제. 안 그러면 머지 순간 프론트가 못 따라와 세션 전부 깨짐
- 착수 전 **격리 환경 교차사용자 주입 재현**(지금은 AI 컨테이너 토큰 미설정·MySQL 느림으로 **정적 사실만 확정**)

### 3-4. #291 — 진척 % (draft)
- 수치가 08-16 기준이라 낡음. #204·#205·#276·복제(E3, #289 닫힘) 반영 후 ready 로

---

## 4. 이번에 판 이슈

- **#296** `GrpcServerDeadlineProbeTest` 간헐 실패(타이밍 의존). CI 를 간헐적으로 빨갛게 만듦 — **조건 폴링으로 고칠 것**

---

## 5. M1 진행판 (제품 점수 기준)

목적지 문서 마일스톤 M1(신뢰성·보안 최소선):
- ✅ #276 데드락 재시도 · ✅ #188 rep 유실 방어 (둘 다 #280 로 머지)
- 🟡 #187: (b) 머지 직전(#304), (d) 미착수
- ⬜ #185: 코드 완료·머지 전 (#3-2)

**남은 큰 것**: #187 (d), 그리고 M4(운영·배포 — 상시 호스트·prod compose 실검증·관측 운영적용).
제품 점수에서 보안 3.0·운영 2.5 가 가장 큰 구멍(별도 평가 참조).
