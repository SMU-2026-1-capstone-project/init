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

## 5. 미결 — 다음에 물을 것 (§6 재현 시도 이후 갱신)

- ~~MySQL 캡을 걸고 재현되는지~~ — ✅ **§6에서 답함: 재현된다.** 캡이 사고를 막지 못했고,
  오히려 직접 증거가 "MySQL 원인" 가설을 반증하는 쪽으로 나왔다
- **동접을 낮춰 재현 문턱을 찾는 것** — 여전히 미답
- ~~`SHOW ENGINE INNODB STATUS`·스레드 덤프를 실시간으로 걷는 계측~~ — ✅ §6에서 붙였고 직접
  증거를 얻었다(MySQL은 사고 직전까지 idle이었다는 것)
- 🆕 **EBS(gp3) IOPS/처리량 크레딧 소진** — §6이 새로 세운 후보. 메모리는 사고 직전까지
  건강했는데(13.4GB 여유) SSH·백엔드·메트릭 스크레이핑이 **같은 30초 창 안에 동시에** 죽었다
  — 디스크 I/O 전체가 막히면 정확히 이 모양이 난다. 미검증
- **`shadowfit-ai` 고유의 장애 빈도 질문(§0 원본)은 여전히 미답**
- **이번 재현 라운드는 obs 프로파일(Grafana·Prometheus·cAdvisor 등) 추가와 MySQL 캡 적용이
  같은 라운드에 겹쳤다** — 변수 하나만 바꾼 깨끗한 대조가 아니다(§6-4)

---

## 6. 재현 시도 — MySQL 캡 3072m + 실시간 계측 (2026-08-28, 같은 날 2차 라운드)

### 6-0. 한 줄

**사고가 다시 났다 — MySQL 캡은 막지 못했다.** 그런데 이번엔 처음으로 사고 직전 순간의
직접 증거를 얻었고, 그 증거가 §3의 "MySQL 락 정체" 가설을 **뒷받침하지 않고 오히려
반박한다** — MySQL은 사고 직전까지 완전히 idle·healthy했다. 대신 호스트 전체(SSH·백엔드·
메트릭 스크레이핑)가 **같은 15~30초 창 안에 동시에** 죽었다는, 더 이상한 관찰을 새로 얻었다.

### 6-1. 무대 — 1차와 달라진 것

| | 1차(§0~§5) | 2차(이 절) |
|---|---|---|
| 대상 | `i-049fc27ab2a98bc0e` | `i-054ee168874fd51df`(새 인스턴스, 같은 스펙) |
| MySQL | `mem_limit` 없음 | **3072m**(커밋 `9aa9879`) — 근거: idle 실측 583.7MiB이 아니라
  `--innodb-buffer-pool-size=2G`(이미 설정됨) 기준(idle은 JVM과 달리 InnoDB 버퍼풀이
  점진적으로 채워지므로 하한 근거로 못 씀, docker-compose.yml 주석 참고) |
| 계측 | 5분 간격 RestartCount/OOMKilled만 | 🆕 **7초 간격** `SHOW ENGINE INNODB STATUS` +
  백엔드 스레드덤프(`kill -3`, JRE 이미지라 jstack 없음) — `measure_ai_worker_load_soak_diagnostics.sh`
  (커밋 `e615088`) |
| obs 스택 | 없음 | 🆕 Grafana·Prometheus·cAdvisor·node-exporter·mysqld-exporter
  (`--profile obs`, 같은 대화의 별개 작업이 커밋 `e89e14b`로 push한 것을 이 라운드가 채택) |
| 부하 기간 | 3시간(계획), 90초 만에 사고 | **45분**(계획 — 1차가 90초 만에 났으니 여러 배
  확인하기 충분하다고 판단) |
| 계정 | 193/203 | 준비 중 사고 발생(정확한 준비 완료 수는 로그에 없음 — 부하기 인스턴스가
  이미 terminate됨) |

### 6-2. 시간순 경위

| 시각(UTC) | 사건 |
|---|---|
| 09:22:15 | MySQL 재생성, `mem_limit=3221225472`(3072m) 적용 확인(`docker inspect`) |
| ~09:23~09:25 | obs 프로파일 기동(Grafana·Prometheus·cAdvisor·node-exporter·mysqld-exporter) — `shadowfit-ai`도 새 env var로 재생성(무해, MySQL은 안 건드려짐) |
| idle 실측(재생성 후 30초 간격 3회) | MySQL 583.7MiB / 3.72% — 3회 동일 |
| 09:26~09:27:58 | 5분 모니터·7초 진단 폴러 기동 |
| 09:28:27 | 부하 시작(계정 준비 단계, 203개 목표) |
| ~09:36~09:37(추정) | 계정 준비 완료, 실제 세션 시작/종료 워커 루프 개시 |
| **09:38:29** | systemd 저널의 **마지막 정상 항목**(SSH 세션 로그) — 이후 재부팅 전까지 공백 |
| **09:38:35.670Z** | `SHOW ENGINE INNODB STATUS`의 **마지막 성공 캡처**(90개 중 마지막) — 아래 §6-3 |
| **~09:38:45(추정)** | Prometheus의 `node_memory_MemAvailable_bytes` 시계열이 멎는다(그 뒤 값이 반복됨 — staleness 창) |
| **09:39:07** | 외부 워치(포트 확인)가 SSH·8080 동시 무응답 감지 |
| 09:39:07~09:43 | 3연속 실패 확인 후 로더의 부하 스크립트 강제 종료(`pkill`) |
| 09:43:03 | `aws ec2 reboot-instances`(소프트 리부트, 사용자 승인) |
| **09:44:08~10** | (재부팅 종료 시퀀스 도중) 커널이 **`shadowfit-ai` 컨테이너의 `uvicorn`**을
  OOM kill — `anon-rss:4693880kB`(≈4.5GB), `oom-kill:constraint=CONSTRAINT_NONE...global_oom`
  (컨테이너 캡이 아니라 **호스트 전체** 메모리 부족으로 커널이 개입했다는 뜻) |
| 09:44:18 | 이전 부팅 저널의 마지막 항목(새 부팅 시작 직전) |
| 09:44:32 | SSH 복구(재부팅 요청 후 **약 1.5분** — 1차의 4.5분보다 빠름) |
| 09:45 | 진단 로그 회수(`innodb_status_poll.log` 848KB·`backend_threaddumps.log` 6.8MB) |
| 09:47 · 09:47 | 대상·부하기 인스턴스 둘 다 terminate 확인 |

### 6-3. 사고 직전 MySQL의 실제 상태 — 반증 증거

09:38:35.670Z(사고 추정 시각 30~40초 전) `SHOW ENGINE INNODB STATUS` 전문(`innodb_status_poll.log:22676` 이하)을 그대로 인용한다:

```
0 queries inside InnoDB, 0 queries in queue
0 read views open inside InnoDB
Process ID=1, Main thread ID=..., state=sleeping
RW-shared spins 0, rounds 0, OS waits 0
RW-excl spins 0, rounds 0, OS waits 0
Buffer pool size   131063
Database pages     1531        ← 버퍼풀의 1.2%만 사용
Pending reads 0, Pending writes: LRU 0, flush list 0, single page 0
--- (5개 트랜잭션 전부) 0 lock struct(s), 0 row lock(s)
```

**세마포어 대기도, 락 경합도, 대기 중인 I/O도 없다.** MySQL은 사고 30~40초 전까지 완전히
정상 동작 중이었다. 이건 §3-2가 세운 "MySQL InnoDB 내부 락 정체" 가설과 **정면으로 배치된다**
— 그 가설은 1차 라운드의 간접 증거(로그가 "장기 세마포어 대기" 헤더에서 끊긴 것)에서 나왔는데,
이번 직접 증거는 그 끊긴 지점이 "MySQL이 실제로 그 락을 오래 기다리고 있었다"가 아니라
"MySQL이 멀쩡히 돌다가 시스템 전체가 얼면서 로그 쓰기 자체가 같이 멈췄다"에 더 가깝다는 것을
시사한다.

### 6-4. 새로 나온 것 — 거의 동시에 멎은 세 개의 독립 채널

세 개의 서로 다른 프로세스가 **거의 같은 30초 창(09:38:29~09:38:45)**에서 동시에 멈췄다:

1. systemd 저널(호스트 커널/시스템 로그)
2. `SHOW ENGINE INNODB STATUS` 폴러(MySQL 컨테이너에 대한 `docker exec`)
3. Prometheus의 node-exporter 스크레이핑(HTTP, 컨테이너 간 통신)

이 셋은 **서로 다른 프로세스·다른 경로**다 — 한 애플리케이션이 죽어서 나는 증상이 아니라
**호스트 자체가 거의 순간적으로 얼어붙었다**는 신호로 읽힌다. 그런데 `node_memory_MemAvailable_bytes`는
얼기 직전까지 13.4GB(전체 15GB 중)로 **평평했다** — 점진적으로 메모리가 잠식된 흔적이 없다.
`cAdvisor`가 본 `shadowfit-ai` 컨테이너 메모리도 얼기 직전까지 **604MB로 평평**했다(20GB
캡의 3%). 즉 **메모리 고갈이 서서히 진행된 흔적은 어디에도 없다** — 갑자기, 거의 순간적으로
멎었다.

🆕 **새 후보: EBS(gp3) I/O 크레딧 소진.** 메모리는 안 튀었는데 디스크에 물린 모든 것(저널
쓰기·MySQL 쿼리·HTTP 응답)이 동시에 멎는 건 디스크 I/O 자체가 막히는 그림과 맞는다. gp3
볼륨은 기본 3,000 IOPS·125MB/s 처리량인데, 계정 193~203개의 동시 가입·로그인·온보딩(각 2~3
보호 경로) + MySQL 커밋 + 도커 로그 쓰기가 겹치면 순간적으로 그 한도에 닿을 수 있다.
**이 라운드는 EBS 지표(`VolumeQueueLength`·`VolumeThroughputPercentage` 등)를 안 걷어서
직접 확인은 못 했다** — 다음 라운드 후보로 남긴다.

### 6-5. OOM kill을 어떻게 읽어야 하는가 — 원인이 아니라 결과일 가능성

09:44:10의 OOM kill(`shadowfit-ai`의 uvicorn, anon-rss 4.5GB, `global_oom`)은 얼핏 "역시
메모리가 원인이었다"로 보이지만, 시점이 걸린다 — **사고 발생(09:38대)으로부터 5분 넘게 지난,
재부팅 시퀀스 도중**이다. cAdvisor가 그 직전까지 본 `shadowfit-ai` 메모리는 604MB였다(다만
§6-4대로 그 값도 09:38:45 이후로는 스크레이핑이 멎어 "그 이후 실제로 무슨 일이 있었는지"는
안 보인다 — 최후 관측치가 최후 실제값이라는 보장이 없다). 두 갈래가 다 가능하다:

- **①** 시스템이 이미 얼어붙은 뒤(디스크 I/O 막힘 등), 사용자가 부하를 끄고 SSH 재시도를
  반복하고 재부팅 명령이 들어오는 동안 셧다운 절차 자체(bash·systemd 프로세스들)가 추가
  메모리를 요구했고, 이미 아슬아슬하던 메모리가 그 압박에 넘어갔다 — **OOM은 결과**
- **② ** uvicorn이 그 5분 사이 실제로 4.5GB까지 부풀었고 그게 애초에 얼어붙음의 원인이었다
  (cAdvisor가 그걸 못 본 건 스크레이핑 자체가 멎어서다) — **OOM은 원인**

**이 라운드는 둘을 못 가른다.** ①이 더 그럴듯해 보이지만(멀쩡하던 값이 정확히 스크레이핑이
멎은 시점부터 안 보이게 된 것 자체가 우연일 수도 있다), 확정하지 않는다.

### 6-6. 정직하게 — 이 라운드의 한계

- **obs 프로파일 추가와 MySQL 캡 적용이 같은 라운드에 겹쳤다.** 대화 중 다른 작업이 obs
  스택을 같은 시점에 push했고, 이 라운드가 그걸 같이 채택했다(§6-1) — "MySQL 캡만 바꾼
  깨끗한 대조"가 아니다. obs 스택 자체가 호스트 메모리·디스크 I/O에 추가 부담을 얹었을
  가능성을 배제 못 한다
- **EBS I/O 지표를 안 걷어서 §6-4의 새 가설(I/O 크레딧 소진)은 검증이 아니라 추정이다**
- **§6-5의 OOM 인과관계(원인/결과)를 못 가른다**
- 재부팅 후 컨테이너 3개 전부 `OOMKilled=false·RestartCount=0`(새 인스턴스 상태라 무의미,
  1차와 같은 한계)
