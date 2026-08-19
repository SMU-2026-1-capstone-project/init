# P6 2라운드 rig — EC2 리허설 1차 (2026-08-17)

rig: [`../README.md`](../README.md) · 설계: [`../../../../docs/decisions/ai-coresidency-capacity.md`](../../../../docs/decisions/ai-coresidency-capacity.md) §11

> ⚠️ **측정값이 아니다.** 20초 판·5·10세션짜리 축소 리허설이고, **경로가 서는지만** 본다.
> 여기 숫자를 용량으로 인용하지 말 것.

**무대**: 대상 `c7i.4xlarge`(172.31.33.157) + 부하기 **`c7i.xlarge`**(4 vCPU — §T 전제) ·
ap-northeast-2c · AL2023 · 커밋 `c81708f` 의 rig 3파일을 scp 로 얹어서 돌렸다.
**중단**: 13판 중 8판째에서 사람이 끊었다(§T 블록 전). 인스턴스는 둘 다 terminate 했다.

---

## 1. 선 것 (EC2 실행 검증 ✅)

| 항목 | 근거 |
|---|---|
| **③ 캡 단언** | `caps.tsv` — 팔 C 가 `shadowfit-ai 8000000000 / backend 4000000000 / mysql 4000000000`, 기대값(`.env` 8·4·4)과 **일치 OK**. 팔 A·B 는 `0 → OK` |
| ③ 부수 발견 | **`STALE` 이 한 번도 안 났다** = compose 가 override 를 지우면 컨테이너를 실제로 재생성한다. 지금까지 «아마 그럴 것» 이던 전제가 확인됐다 |
| **④ 레벨 순서 치환** | 로그 `레벨 순서 5 10` · 버림판이 블록 첫 레벨을 따라감 · `coresidency.tsv` 새 열 `loader_cpus` 전 판 `full` |
| **⑤ 앵커 판** | `B anc1 10` 행 존재 · **앵커 앞에서 팔 B 버림판이 먼저** 돌았다(계열 오염 방지가 실제로 동작) |
| **⑦ 부하기 샘플러** | `loader_*.tsv` 판마다 생성(8판 전부) |
| **⑨ ghz 지연 백분위** | `ghz.tsv` 에 `p50/p95/p99` — 11.0~13.7ms. 팔 A 는 설계대로 `-`. **H3 판정 열이 처음으로 존재한다** |
| **⑨ 옆 지표** | `side.tsv` 4,639행 · `pre/mid/post` 세 국면 · **`grpc_server_*`·`hikaricp_*`·MySQL `Threads_running` 이 실제로 걷힌다**(actuator 74줄 확인) |
| 무결성 | 8판 전부 `detect_pct` 100.00 · `nolease`·`nopose`·`setup_fail` 0 · ghz 실패 0/569 |

## 2. 못 본 것 (다음 리허설로)

| 항목 | 왜 |
|---|---|
| **⑧ 자기 프로브** | 아래 §3-1 결함으로 스윕이 스스로 껐다 — 고쳤고 **재검증 필요** |
| **⑩ §T 코어 팔** | 블록이 격자 **뒤에** 붙는데 그 전에 끊었다 |
| `cores_assert_*` 게이트 4종 | 스윕이 끝까지 안 가서 판정 단계에 도달 못 함 |
| preflight 단계 | S3 때문에 통째로 건너뜀(§3-3) — 손으로 대조했다 |

## 3. 이번에 잡은 것

### 3-1. 🔴 `$SSH` 에 `-n` 이 있으면 프로브 자산이 0바이트로 간다

`setup_probe` 는 `$SSH "cat > …" < frames.json` 으로 자산을 민다. `TARGET_SSH` 에 `-n`
(stdin 차단)을 붙이면 **원격 `cat` 이 빈 입력을 받는다.**

⭐ **rig 이 스스로 잡았다** — 크기 대조가 `0/418135 B` 를 보고 프로브를 끄면서 사유를 로그에
남겼다. 이 가드가 없었으면 «프로브가 돌았는데 표가 이상하다» 로 끝났을 것이다.
→ 경고문에 «-n 이면 이것» 을 박았다. **탑승 명령에서 `-n` 을 빼는 것이 정답이다.**

### 3-2. 🔴 그 함정이 팔 C 캡 단언에도 잠복해 있었다

`assert_caps` 가 `while read … <<EOF` 안에서 `$SSH` 로 `.env` 를 읽었다. `-n` 없는 정상
ssh 는 **heredoc 을 먹으므로** 루프가 첫 줄만 돌고 끝난다 — 즉 **컨테이너 하나만 확인하고
통과**한다. 이번 라운드는 `-n` 이 붙어 있어서 우연히 안 터졌다.
→ 기대값을 **루프 앞에서 한 번에** 읽도록 고쳤다(stdin 먹는 ssh 스텁으로 3행 전부 기록 확인).

### 3-3. S3 를 못 쓴다 — `preflight_s3` 가 하드 실패다

> 🔴 **이 절의 결론은 2026-08-17 에 뒤집혔다. 아래 진단은 틀렸다** (기록으로 남긴다).
> **인스턴스 프로파일은 붙일 수 있었다** — 기동 시 `--iam-instance-profile Name=shadowfit-measure`
> 한 줄이면 되고, 관리자 자격증명도 IAM 수정도 필요 없었다. `t3.micro` 로 EC2→버킷 쓰기를
> 실증했다(`_iam_probe_20260817-135448/put_ok.txt`).
> **왜 틀렸나**: `iam:GetInstanceProfile` 거절 **하나**를 보고 「붙일 수 없다」로 갔는데,
> **조회 권한과 사용 권한은 다른 것**이다(최소권한 정책에서는 오히려 흔한 모양이다).
> 이 rig 이 `assert_caps` 에 `UNREADABLE` 을 따로 둔 이유 —「캡이 없다」가 아니라
> 「못 물었다」— 와 **같은 실패 모드를 IAM 쪽에서 저질렀다.**
> **판별법**: `run-instances --dry-run` 을 진짜 이름과 **가짜 이름으로 대조**한다. 가짜가
> `InvalidParameterValue` 로 거절되면 AWS 가 존재를 실제로 검증한다는 뜻이고, 그때에야
> 진짜 쪽 `DryRunOperation` 을 신뢰할 수 있다.

이 IAM 사용자(`shadowfit-loadtest-temp`)는 `iam:GetInstanceProfile`·`ListInstanceProfiles` 가
없어 인스턴스 프로파일을 붙일 수 없다. `phase_coresidency_preflight` 는 S3 쓰기 실패 시
`break` 라 **리허설까지 통째로 안 돈다.**
→ 이번엔 `PHASES="coresidency_rehearsal"` 만 돌리고 결과는 scp 로 받았다.
**정식 라운드는 S3 가 있어야 한다**(원문 회수 경로가 그것뿐이다) — 프로파일 붙은 인스턴스로
띄우거나 IAM 권한을 열어야 한다.

### 3-4. 탑승 실무 메모 (다음에 시간 아끼는 것들)

- 대상 박스 **root SSH 가 기본으로 막혀 있다**(AL2023). `sudo cp /home/ec2-user/.ssh/authorized_keys
  /root/.ssh/` 를 먼저 해야 부하기가 대상을 몬다
- **Windows 에서 scp 로 rig 을 얹으면 CRLF** 라 `bash: $'\r': command not found` 로 죽는다.
  올린 뒤 `sed -i 's/\r$//'` 필수
- 부트스트랩 소요 — 대상(이미지 빌드 포함) **약 9분**, 부하기 약 4분
- 리허설 판당 약 60초(DUR 20 + 판 사이 정리) → 13판 ≈ 13분

## 4. 원문

`coresidency.tsv` · `ghz.tsv` · `side.tsv` · `caps.tsv` · `probe_rtt.tsv`(헤더만 — §3-1) ·
`loader_*.tsv` · `req_*_summary.tsv` · `run_all.log`.
⚠️ `ghz_*.json`·`req_*.tsv`(요청 단위 원본, 263MB)는 **안 받았다** — 리허설이라 값이 필요 없다.
