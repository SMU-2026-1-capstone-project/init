# GitHub 이 503 이라 못 올린 코멘트 (2026-08-18)

작성일: 2026-08-18 03:1x
상태: **대기** — GitHub GraphQL API 가 503 이라 `gh issue comment` 가 실패했다. 복구되면 그대로 올린다.
🔴 이 파일은 **임시**다. 올린 뒤 지운다.

---

## #219 — INSERT IGNORE 전수 확인 (감사 완료)

### 전수

```
INSERT IGNORE            →  FeedbackLogService.java:34  (session_feedback_logs)  ← 유일
ON DUPLICATE KEY UPDATE  →  PoseDataService(pose_data) · DailyLogRepository(daily_logs)
```

`PoseDataService` 는 이미 ODKU 로 갈아탔고 주석이 이유를 적어뒀다 — *«IGNORE 는 중복만
삼키는 게 아니라 NOT NULL 위반을 빈 값으로 써버린다»*. 즉 **이 이슈의 경고는 이미 한 번 반영됐다.**

### 남은 한 자리는 두 위험 다 막혀 있다

| 이슈가 경고한 것 | 실제 |
|---|---|
| FK 위반 → 행이 조용히 사라진다 | ✅ 막힘 — `existsById()` 가 먼저 `SESSION_NOT_FOUND` 를 던진다 |
| NOT NULL 위반 → 빈 값 | ✅ 막힘 — `feedback_type` 은 `FeedbackType.valueOf()` 가 실패 시 `INVALID_INPUT_VALUE` |

⚠️ FK 쪽 **TOCTOU 창은 남는다** — 검증 통과 후 착지 전에 탈퇴 CASCADE 가 세션을 지우면
IGNORE 가 조용히 버린다. #87 과 같은 창인데 `pose_data` 는 그 창을 지표로 재고 여기는 안 잰다.

### 🔴 대신 다른 둘이 열려 있다

**① `sync_rate_at_trigger DECIMAL(5,2)` 에 검증 없는 double**

`ps.setDouble(3, event.getSyncRateAtTrigger())` — proto double 그대로다. 컬럼 최대는
**999.99** 이고, 넘으면 IGNORE 가 경고로 낮추고 **조용히 잘라서 넣는다.**
`feedback_type` 은 enum 으로 검증하면서 이 값은 안 한다.

**② `occurred_at` 미설정이 멱등을 흔든다**

proto Timestamp 가 비면 `Timestamps.toMillis` 가 0 → **1970-01-01** 이 된다. 그 컬럼은
**유니크 키의 일부**(`uk_session_event(session_id, occurred_at, feedback_type)`)라,
타임스탬프를 안 채운 이벤트가 **전부 같은 키로 뭉쳐** 두 번째부터 삼켜진다.
「중복 흡수」가 아니라 **유실**인데 로그에는 `skipped` 로 찍힌다.

### 📌 지금은 잠재다 — 이 경로는 한 번도 안 불린다

`ReportFeedbackBatch` 호출부가 **ai-server 에 없다**(자동생성 스텁뿐). AI 가 실제로 부르는
것은 `SavePoseDataBatch`·`CompleteAnalysis`·`StopAnalysis` **셋뿐**이다. 즉
`FeedbackLogService` 전체가 죽은 경로이고(#193·#228), 위 둘은 **감지기를 켜는 순간** 열린다.

### 닫는 형태

전수 확인이라는 이 이슈의 목적은 **달성됐다.** ①②는 **#193/#228 구현의 선행 조건**으로
옮겨 붙이는 것이 맞다 — 감지기를 켜기 전에 범위 검증과 타임스탬프 필수화가 필요하다.

---

## #273 — 잔결함 셋 추가 (2026-08-18 라운드)

### ⑤ 잠금을 손으로 지우면 두 판이 같은 표에 쓴다

#207 판에서 실제로 났다. 앞 판을 `pkill` 로 죽였는데 프로세스가 안 죽었고, **잠금 디렉터리를
손으로 `rmdir` 해서** 다음 판을 들여보냈다.

```
ps -ef | grep -c timeout_scheduler_memory.sh   →  6   (트리 둘)
timeout_mem.tsv                                 →  arm 1 이 세 번, 순서도 뒤죽박죽
```

두 판이 같은 `FAKE_LO` 대역을 서로 지우고 다시 만들어 한쪽 팔이 **세션 0개인 채로** 측정된
행이 섞였다. 잠금 메시지는 *"정말 죽은 판이면 이 디렉터리를 지우고 다시 돌린다"* 라고
안내하는데 **정말 죽었는지 확인하는 법**은 안 적혀 있다.

**고칠 것**: 잠금 디렉터리에 **PID 를 남기고**, 잠겨 있을 때 그 PID 가 살아있는지 보여준다.

### ⑥ `cte_max_recursion_depth` 기본값 1000 이 팔을 잘랐다

```
🔴 스윕 중단 — 팔이 안 섰다 — IN_PROGRESS 를 10000 개로 원했는데 0 개다
```

⚠️ **1,000개 팔은 한도에 정확히 걸려 «우연히» 통과했다.** 팔 값이 하나만 더 컸어도 조용히
부족한 세션으로 돌 뻔했다. **고침**: rig 이 삽입 전에 `SET GLOBAL cte_max_recursion_depth` 를 올린다.

### ⑦ 지표를 부팅 중에 읽으면 0 이 나오고, 「측정했더니 0」과 안 갈린다

#207 1차가 그랬다. 앞 판(P2)이 끝나며 백엔드를 재기동했고 그 부팅 중에
`/actuator/prometheus` 를 긁어 `grep` 이 아무것도 못 찾았다 → `awk '{print s+0}'` 이 **0**.
세 지표가 전부 0 인 것이 유일한 단서였다. **고침**: `assert_metrics` 로 시작 전에 한 번 묻는다.
