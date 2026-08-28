# AI 워커 부하-중 자발적 장애 빈도 — 가속 스트레스 (2026-08-28, EC2 2대)

측정일: 2026-08-28 · 대상 `c7i.2xlarge`(i-049fc27ab2a98bc0e) · 부하기 `c7i.xlarge`(i-0079eae9c7cfd72db)
· 리전 ap-northeast-2 · 커밋 `437ff9134e40b8f79483f125d3aeec4649905af`
설계: [`../../../docs/decisions/ai-worker-load-soak-experiment.md`](../../../docs/decisions/ai-worker-load-soak-experiment.md)
rig: [`../../measure_ai_worker_load_soak.sh`](../../measure_ai_worker_load_soak.sh) ·
[`../../measure_ai_worker_load_soak_monitor.sh`](../../measure_ai_worker_load_soak_monitor.sh)

---

## 0. 한 줄

**계획한 3시간을 못 채웠다 — 부하 시작 약 90초 만에 대상 박스가 SSH·앱 포트 전부 무응답이 됐고,
소프트 리부트로만 회수됐다.** 원래 채널(`shadowfit-ai`의 RestartCount/OOMKilled)로 답을 얻기 전에
**대상 전체가 죽었다** — 근거를 보면 원인은 `shadowfit-ai`가 아니라 **MySQL InnoDB 내부 락 정체
(장기 세마포어 대기)가 시스템 전체를 얼린 것**으로 강하게 의심된다(§3). "장애 0회"도
"장애 N회"도 아닌 **제3의 결과**다 — 설계 §7 결과표에 없던 갈래.

---

## 1. 무대

| | 값 |
|---|---|
| 대상 | `c7i.2xlarge`(8 vCPU·15GiB) — 스왑 없음 |
| 컨테이너 캡(실측, `docker inspect`) | `shadowfit-ai` cpus=4·mem=20GB(20971520000B) · `shadowfit-backend` cpus=4·mem=2GB(2147483648B) · `shadowfit-mysql` **무제한**(NanoCpus=0, Memory=0) |
| 부하기 | `c7i.xlarge`(4 vCPU), 대상과 private IP로 통신(8080) |
| 부하 방식 | §4-1(설계 문서) — **세션 시작(`POST /exercises/sessions`)·종료(`PATCH /sessions/{id}/end`)만 반복**, 프레임 스트리밍 없음 |
| 목표 동접 | 203(가정 피크 67.5 × 3) — 실제 준비된 계정 **193**(95%, 캐너리 판과의 레이트리밋 창 겹침으로 초반 10개 로그인 실패) |
| hold_sec | 900초(15분) |
| 계획 기간 | 3시간(2~4시간 범위 중간값) |

---

## 2. 시간순 경위

| 시각(UTC) | 사건 |
|---|---|
| 07:21:32 | 대상 모니터(`measure_ai_worker_load_soak_monitor.sh`) 시작. `base_restart_count=0` |
| 07:21:56, 07:26:57 | 모니터 샘플 2개 정상 기록 — `restart_count=0 · status=running · health=healthy · oom_killed=false · mem 12.27~12.28% · cpu 0.67~0.73% · in_progress_sessions=0` |
| ~07:22:44–07:30:59 | 부하기에서 계정 193개 준비(가입·로그인·온보딩) — 10개는 초반 레이트리밋으로 실패 |
| 07:30:07 | 대상 박스의 systemd 저널이 **마지막 정상 항목**을 남긴다(2분 주기 `refresh-policy-routes` 루틴) — 이후 **재부팅 전까지 저널에 아무 항목도 없다** |
| 07:31:54 | 부하 시작 — 워커 193개가 `POST /exercises/sessions` 발사 시작 |
| 07:32:00.698 | `shadowfit-backend` 로그의 **마지막 정상 활동**(`운동 분석 요청 시작 - userId: 134, 135`) — 이후 그 요청의 `Hibernate: select` 로그를 끝으로 출력이 끊긴다 |
| ~07:31:57(추정) | 모니터의 3번째 샘플(5분 주기)이 있어야 할 시각 — **영원히 안 옴**. `docker inspect`·`docker stats`·`docker exec mysql` 중 하나에서 멈춘 것으로 보인다 |
| 07:38:32.456638 | `shadowfit-mysql` 로그의 **마지막 줄**: `[Warning] [MY-012985] [InnoDB] A long semaphore wait:` — **메시지가 이 헤더에서 끊긴다**(정상이면 스레드 상태 덤프가 이어진다). 로그 쓰기 자체가 도중에 멈췄다는 뜻으로 읽힌다 |
| ~07:43–08:06 | 대상 박스: SSH(22)·8080·8000·9090 전부 무응답(banner exchange 자체가 안 끝남). AWS `describe-instance-status`는 이 구간 내내 `State=running · SystemStatus=ok · InstanceStatus=ok`(호스트/하이퍼바이저는 계속 건강하다고 보고) |
| 07:54 | 부하기의 부하 스크립트를 강제 종료(pkill) — 새 부하를 끊었는데도 **10분 넘게 회복 안 됨** |
| 08:06:38 | `aws ec2 reboot-instances`로 소프트 리부트(사용자 승인, EBS 보존) |
| 08:10:42–08:11:27 | 새 부팅(boot 0) 시작, SSH 08:11:15 복구 |
| 08:11 이후 | 컨테이너 3개 모두 `docker inspect`: `OOMKilled=false · ExitCode=0 · Status=running`, `RestartCount=0`(shadowfit-ai) — **단 이건 리부트 뒤 새로 시작된 인스턴스의 상태다. 리부트 전 프로세스가 어떻게 끝났는지는 이 필드로 못 본다**(호스트 리부트는 정상 종료 기록을 안 남긴다) |

---

## 3. 원인 — 강한 정황, 확정은 아니다

### 3-1. `shadowfit-ai`가 원인이라는 증거는 없다

설계 문서(§2)가 판정 채널로 정한 것은 `shadowfit-ai`의 `RestartCount`·`OOMKilled`였다. 그런데
이 채널로 아무것도 못 봤다 — **대상 전체가 리부트 전까지 죽었고, 리부트 후의 값은 새 인스턴스의
것이라 무의미**하다. `shadowfit-ai`가 이 사고의 원인인지 피해자인지조차 이 라운드로는 못 가른다.

### 3-2. 정황이 가리키는 것 — MySQL InnoDB 내부 정체

- **스왑이 없다**(`swapon --show` 빈 값) — 메모리가 실제로 바닥났다면 OOM killer가 즉시(스왑 대기
  없이) 개입했어야 하는데, 커널 저널 어디에도 `Out of memory: Killed process`류 메시지가 없다
  (`journalctl -k -b -1`, `dmesg --ctime` 둘 다 grep 무결과). **단 이것도 확정 증거가 아니다** —
  저널 자체가 07:30:07에 죽어서 그 뒤 일어난 OOM 이벤트가 있었어도 기록되지 않았을 수 있다
- **MySQL의 마지막 로그가 "장기 세마포어 대기" 경고 헤더에서 끊긴다.** InnoDB가 내부 뮤텍스/락을
  비정상적으로 오래 기다리는 스레드를 감지했다는 뜻이고, 메시지가 완성되지 못한 채 로그 자체가
  멈췄다는 건 **그 순간 시스템이 통째로 얼었다**는 신호로 읽힌다
- **`shadowfit-mysql`은 이 라운드에서 유일하게 CPU·메모리 캡이 없는 컨테이너**다(§1). 193개
  계정의 동시 가입·로그인(각 2회 보호 경로) + 193개 세션의 반복 시작/종료(`INSERT INTO
  exercise_sessions`·`SELECT ... FROM users WHERE email=?` 등)가 몰리면서 락 경합이 폭발했을
  가능성이 있다 — 단 정확한 트리거(어느 쿼리·어느 테이블)는 **이 로그만으로는 특정 못 한다**
- **systemd 저널이 부하 시작(07:31:54) 전인 07:30:07에 이미 죽었다는 점이 걸린다** — 부하 자체가
  방아쇠라면 저널도 07:32 이후에 죽어야 자연스러운데, 그보다 먼저 멈췄다. 계정 준비 단계
  (203개 signup+login+onboarding, 07:22~07:31)의 후반부가 이미 부담을 주고 있었을 가능성도
  배제 못 한다 — **"부하가 죽였다"와 "계정 준비가 죽였다"를 이 라운드는 못 가른다**

### 3-3. 정직하게 — 확정하지 못한 것

- **직접 증거(스레드 덤프·OOM killer 로그·`SHOW ENGINE INNODB STATUS`)가 하나도 없다.** 전부
  "마지막 로그가 어디서 끊겼는가"라는 간접 정황이다
- **`shadowfit-ai` 자체의 상태를 사고 당시 한 번도 못 봤다.** 죽었는지 살아있었는지 모른다
- **재현 판 없음** — 이 사고가 이 강도(203세션 동접, 900초 hold)에서 항상 나는지, 이 라운드
  고유의 우연인지 모른다(설계 §7이 이미 "이날·이 강도 고유의 관측"이라는 한계를 못박아 뒀다)

---

## 4. §0(원래 질문)에 대한 답

`ai-channel-pool-hardening.md` §6이 원래 물은 것은 "실제 운영 부하에서 `shadowfit-ai`가 얼마나
자주 자발적으로 죽는가"였다. 이 라운드는 그 질문에 **직접 답을 못 냈다** — 대신 그보다 상위
레벨에서 **"이 컨테이너 캡 구성(AI 4cpu·backend 4cpu·MySQL 무제한, 8vCPU 박스)으로 동접
~200을 밀면 박스 전체가 응답 불능에 빠질 수 있다"**는, 원래 설계에 없던 발견을 냈다.

이건 §0의 질문보다 **더 급한 발견일 수 있다** — `shadowfit-ai`가 개별적으로 얼마나 튼튼한지와
별개로, 이 인프라 구성 자체가 이 정도 동접에서 버티지 못한다면 그게 먼저 막아야 할 문제다.

---

## 5. 미결 — 다음에 물을 것

- **MySQL 캡을 걸고 재현되는지** — 컨테이너 캡 없는 MySQL이 원인이라는 가설이 맞다면, 캡을 걸면
  이 사고가 없어지거나 형태가 바뀌어야 한다
- **동접을 낮춰 재현 문턱을 찾는 것** — 203이 필요조건인지, 훨씬 낮은 동접에서도 나는지
- **`SHOW ENGINE INNODB STATUS`·스레드 덤프를 실시간으로 걷는 계측**을 미리 붙이고 재시도 —
  이번엔 사고가 나고 나서야 로그를 뒤져서 간접 증거만 얻었다
- **`shadowfit-ai` 고유의 장애 빈도 질문(§0 원본)은 여전히 미답** — 이 인프라 안정성 문제를
  먼저 닫아야 그 질문을 다시 물을 수 있다
