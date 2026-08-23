# #276 ② — 상한이 어디서 무너지나를 물으러 갔다가, **상한이 애초에 안 걸린다**를 찾았다 (AWS, 2026-08-23)

- 생성 표: [`summary.md`](./summary.md) · 원자료: [`raw.tsv`](./raw.tsv) · 실행 로그: [`run.log`](./run.log)
- 백엔드 로그 발췌: [`backend-log-excerpt.txt`](./backend-log-excerpt.txt) · 잠금 원문: [`innodb-status.txt`](./innodb-status.txt) · 조건: [`MANIFEST.txt`](./MANIFEST.txt)
- rig: [`../../measure_r276_app_retry.sh`](../../measure_r276_app_retry.sh) · 박스: **AWS `c7i.xlarge`**(4 vCPU · 8GB) · `i-0db6a2cf6525f4dd9` · MySQL 8.0.46 · 커밋 `aa3681b`
- ghz → Spring gRPC `SavePoseDataBatch` → `savePoseDataBatchWithDeadlockRetry` · **동시성 8·16·32 × 4블록**(첫 블록 버림) · 라틴 방격 · 판당 500요청 · 중복 페이로드

---

## 0. 한 줄

🔴 **`DEADLOCK_MAX_RETRIES = 2` 는 실사용 경로에서 한 번도 돌지 않는다.** 데드락이 나도
`catch` 가 안 잡는다 — 코드가 잡는 예외 타입(`DeadlockLoserDataAccessException`)을
**Spring 6.2 는 만들지 않는다.**

②가 물으려던 「상한이 어느 동시성에서 무너지나」는 **아직 답이 없다.** 무너질 상한이 안 걸려 있다.

---

## 1. 결과 — 두 값이 어긋난다

| 동시성 | Internal 중앙값 | 잔여 실패율 | **exhausted** | **retried** | 저장된 행 |
|---|---|---|---|---|---|
| 8 | 182 | **36.4%** | **0** | **0** | 500 |
| 16 | 226 | **45.2%** | **0** | **0** | 500 |
| 32 | 211 | **42.2%** | **0** | **0** | 500 |

**이 표는 자기모순이다.** 요청의 36~45% 가 `INTERNAL` 로 죽는데, 재시도 지표는 세 칸 모두
**0** 이다. 재시도가 돌았다면 `retried` 가 오르거나, 소진됐다면 `exhausted` 가 올랐어야 한다.

> 📌 이 rig 은 ghz 의 상태 분포와 지표를 **서로 검산하라고** 같이 걷는다(rig 머리말 §무엇을 읽나).
> 그 설계가 여기서 값을 했다 — 한쪽만 봤으면 «앱 경로에서도 40% 실패» 로 끝났을 판이다.

---

## 2. 판정 — 실패는 데드락이 맞고, 재시도가 안 돈 것이다

### ㄱ. 실패의 정체 = 데드락

백엔드 로그(꼬리 2000줄)에 **`Deadlock found when trying to get lock` 61건**이고,
전부 `pose_data` 의 그 `INSERT ... ON DUPLICATE KEY UPDATE` 다. 다른 유형의 실패는 **0** 이다.

### ㄴ. 그런데 재시도 로그가 **한 줄도 없다**

같은 로그에 `"데드락 재시도"`·`"다시 던진다"`(`ExerciseGrpcService:155-160` 의 문구)가 **0건**이다.
지표도 0 이다. 즉 `catch (DeadlockLoserDataAccessException e)` 블록에 **진입한 적이 없다.**

### ㄷ. 왜 — Spring 6.2 는 그 예외를 만들지 않는다

`spring-jdbc 6.2.19` 의 `SQLExceptionSubclassTranslator` 가 참조하는 `org.springframework.dao.*`
예외 목록을 클래스에서 직접 뽑으면 이렇다:

```
CannotAcquireLockException · DataAccessResourceFailureException · DataIntegrityViolationException
DuplicateKeyException · InvalidDataAccessApiUsageException · PermissionDeniedDataAccessException
PessimisticLockingFailureException · QueryTimeoutException · RecoverableDataAccessException
TransientDataAccessResourceException
```

**`DeadlockLoserDataAccessException` 이 없다.** MySQL 의 데드락(1213 / SQLState 40001)은
`SQLTransactionRollbackException` 으로 올라오고, 이 번역기는 그것을 `CannotAcquireLockException`
계열로 만든다 — 둘 다 `PessimisticLockingFailureException` 의 자식이지만 **서로 형제**다.
코드는 그중 한 형제만 잡는다.

> ⚠️ 「어느 클래스로 번역되는가」는 위 목록에서 좁힌 것이고, 예외 객체의 클래스명을 로그로
> 직접 확인한 것은 아니다(코드가 `e.getMessage()` 만 찍는다). 다만 **«DeadlockLoser 가 아니다»**
> 는 이 판이 실측으로 보인 것이다 — 그 타입이었다면 재시도가 돌았어야 한다.

### ㄹ. 테스트는 왜 못 잡았나 — 가정이 그대로 테스트가 됐다

`ExerciseGrpcServiceTest:94,115` 는 `doThrow(new DeadlockLoserDataAccessException(...))` 로
**그 타입을 직접 던진다.** 그래서 «재시도가 돈다» 를 통과시키는데, 실사용에서는 그 타입이
오지 않는다. 테스트가 검증한 것은 **루프의 동작**이지 **루프가 열리는 조건**이 아니었다.

### ㅁ. 실패율이 «수정 전» 과 같은 자리다

[게이트 라운드](../payload-uniqueness-gate-aws-2026-08-17)가 **재시도가 없던 코드**로 같은 박스
타입·같은 중복 조건에서 낸 값이 c=10 에서 **34.8%** 였다. 이 판의 c=8 은 **36.4%** 다.
재시도가 실제로 돌았다면 [08-20 실측](../r276-retry-2026-08-20)대로 한 자릿수 아래로 내려갔어야 한다.

---

## 3. 그래서 ②는 어떻게 되나

**못 쟀다.** 「상한 2 가 어느 동시성에서 무너지나」는 재시도가 **도는** 코드에서 다시 재야 한다.
이 판이 한 일은 그 앞의 질문에 답한 것이다 — **지금은 안 돈다.**

- 고칠 자리: `ExerciseGrpcService.java:158` 의 `catch` 타입. `PessimisticLockingFailureException`
  이면 `DeadlockLoser`·`CannotAcquireLock` 두 형제를 다 덮는다. 멱등이 서 있으므로 재시도 자체는 안전하다
- 같이 고칠 것: 위 단위 테스트가 **실사용에서 오는 타입**으로도 돌아야 한다
- 그 뒤에 이 rig 을 **그대로 다시** 돌리면 ②의 답이 나온다(스윕·판 수·페이로드가 이미 서 있다)

---

## 4. 🔴 미검증 · 이 판에서 말하면 안 되는 것

1. **레벨 사이 비교를 하면 안 된다.** 36.4 → 45.2 → 42.2% 는 단조롭지 않고, 부하기가 대상과
   **같은 4 vCPU 박스**에 살아 c 를 올리면 ghz 자신이 CPU 를 뺏는다. 이 판은 그것을 못 가른다.
   레벨은 «재시도가 도는가» 를 여러 조건에서 본 것이지 p(c) 곡선이 아니다.
2. **절대 실패율을 운영값으로 인용 금지** — 동거 박스 · 중복 100% 페이로드 · 세션 100개 조건이다.
3. **예외 클래스명을 직접 못 봤다**(§2-ㄷ 의 ⚠️). 고친 뒤 다시 돌리면 그 자리가 로그로 닫힌다.
4. 저장된 행이 판마다 **정확히 500** 인 것은 정상이다 — 세션당 한 요청분(25프레임 → R=5 로 5행)만
   남고 나머지는 중복으로 접힌다. 그게 이 페이로드의 조건이다.

---

## 결정 로그

- **2026-08-23: 12판 회수, 그 외 에러 0.** ②의 답 대신 **결함**이 나왔다 — 이슈로 등록했다.
- **2026-08-23: rig 의 사전 단언 둘이 실제로 걸렸다.** 세션이 `COMPLETED` 로 시드돼 있어
  (`IN_PROGRESS 가 0/100`) 그대로 돌렸으면 배치가 **조용히 버려져** «데드락 0» 이 나왔을 판이다.
  rig 이 무대를 세우고 그 사실을 찍었다(`run.log`).
