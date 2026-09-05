# EC2 무인 측정 (loadtest/aws/)

임시 EC2 인스턴스에 올려 **밤새 돌리고 아침에 결과만 받는** 실행기. 무엇을 태울지는
[`../AWS-RIDE-ALONG.md`](../AWS-RIDE-ALONG.md) 가 정한다. 이 디렉터리는 **어떻게 돌리는가**만 다룬다.

| 파일 | 역할 |
|---|---|
| `bootstrap.sh` | 빈 인스턴스 → 측정 가능한 상태 (docker·repo·`.env`·MySQL·도구 이미지) |
| `run_all.sh` | 단계 순차 실행 + S3 주기 업로드 + 매니페스트 + 조건부 자동 정지 |

---

## 사전 준비 (한 번)

1. **S3 버킷** — 결과를 받을 곳. 리전은 인스턴스와 맞추는 편이 싸다
2. **인스턴스 프로파일** — 그 버킷에 `s3:PutObject`·`s3:ListBucket`. `run_all.sh` 가
   **제일 먼저** 쓰기를 시험한다. 8시간 돌고 업로드에서 막히는 것이 최악이라서다
3. 인스턴스 — **라운드마다 다르다.** gp3 100GB 면 1,000만 행 스윕에 충분

   | 라운드 | 인스턴스 | 대수 |
   |---|---|---|
   | P1(무중단 DDL)·P3(백업/복구) 등 | c7i.2xlarge / m6i.xlarge (4차 실측 관례) | 1대 (러너 = 측정 대상) |
   | **P6(동거 용량)** | **대상 c7i.4xlarge + 부하기 c7i.xlarge** | **2대** — 아래 「P6 — 2대 구성」 |

   P6 의 대상이 c7i.4xlarge 로 고정된 이유는 값 비교다 — 08-14 의 「동시 156세션」이 나온
   박스가 아니면 **팔 A 가 기준선 구실을 못 한다**
   ([`../../docs/decisions/ai-coresidency-capacity.md`](../../docs/decisions/ai-coresidency-capacity.md) §10).
4. **종료 동작 확인** — `InstanceInitiatedShutdownBehavior` 를 본다.
   🔴 **이 줄은 오래 「확인한다」만 적고 방법이 없었다.**

   > 🔑 **2026-08-24 사용자 결정 — «측정 다하면 EC2 자동으로 끄는걸로».**
   > 그래서 `run_all.sh` 의 `AUTO_SHUTDOWN` **기본값이 1 이 됐고**, 이 속성의 **의도된 값은
   > `terminate`** 다. 이유는 «끄는 것» 의 뜻이다 — `stop` 은 컴퓨트만 멈추고 **EBS 요금은
   > 계속 나가며**, 무엇보다 **꺼진 채 잊힌다.** 실제로 도는 박스 둘 다 이미 `terminate` 였다.
   >
   > ⚠️ **그래서 아래의 「EBS 가 날아간다」는 경고가 사라진 게 아니라 «받아들인 대가» 다.**
   > 그것이 안전한 이유는 하나뿐이다 — **`run_all.sh` 는 S3 업로드가 성공했을 때만 끈다**
   > (`FINAL_OK`). 업로드가 실패하면 박스를 남긴다. 그 가드가 없으면 이 결정은 위험하다.
   >
   > 🔴 **손으로 도는 판은 이 보호가 없다.** rig 을 SSH 로 직접 부르면(`run_arms.py` 등)
   > `run_all.sh` 를 안 거치므로 **아무것도 안 끄고, 아무것도 안 지킨다.** 그때는 사람이 끈다.
   >
   > 🔴 **그리고 그런 판에 «박스 안 `shutdown -h +N`» 만 따로 거는 것이 제일 위험하다.**
   > 2026-08-24 에 실제로 났다 — N 프로세스 스윕이 `run_sweep.sh` 를 직접 부르는 판이었는데
   > 박스에 **인스턴스 프로파일이 안 붙어 `aws s3` 가 «Unable to locate credentials»** 였다.
   > 산출물은 `/root` 에만 있었고 종료 예약은 걸려 있었다. **가드 없이 종료만 가진 상태**라,
   > 54분 안에 사람이 회수하지 않았으면 17판이 볼륨과 함께 사라졌다.
   >
   > **규칙 둘로 정리한다:**
   > - **올릴 곳이 없으면 자동 종료를 켜지 않는다** — `FINAL_OK` 가 성립할 수 없다
   > - **`run_all.sh` 밖에서 부르는 판은 자동 종료를 켜지 않는다** — 회수를 먼저 하고 사람이 끈다

   **⚠️ 먼저 — 이 속성은 «OS 안에서 끈 경우» 에만 적용된다:**

   | 끄는 방법 | 이 속성이 관여하나 |
   |---|---|
   | 콘솔 · CLI `stop-instances` | ❌ **무관** — 무조건 stop |
   | 박스 안에서 `shutdown -h` · `poweroff` | ⭕ **여기서만** stop/terminate 가 갈린다 |

   즉 위험은 **`AUTO_SHUTDOWN=1` 을 쓰거나 박스 안에서 `shutdown` 을 칠 때**만 온다
   ([#379](https://github.com/Shadowfit/init/pull/379) 가 자동 정지를 리플리카까지 넓히면서
   **미검증으로 남겨둔 자리**가 이것이다). 손으로 도는 라운드(從 R10-a 등)는 **콘솔에서
   Stop 하면 이 속성을 안 봐도 된다.**

   ```bash
   # ① 이미 떠 있는 인스턴스
   aws ec2 describe-instance-attribute      --instance-id i-xxxxxxxx --attribute instanceInitiatedShutdownBehavior
   # → {"InstanceInitiatedShutdownBehavior": {"Value": "stop"}}

   # ② 띄울 때 아예 박아둔다 (AWS 기본은 stop 이므로 **반드시 명시**한다)
   aws ec2 run-instances ... --instance-initiated-shutdown-behavior terminate

   # ③ 바꾸기 (실행 중에도 된다)
   aws ec2 modify-instance-attribute      --instance-id i-xxxxxxxx --instance-initiated-shutdown-behavior terminate

   # ⚠️ 박스를 남기며 들여다볼 라운드라면 반대로 stop 을 넣고 AUTO_SHUTDOWN=0 을 같이 쓴다.
   #    둘 중 하나만 바꾸면 «자동으로 꺼지는데 볼륨이 남는다» 같은 어중간한 상태가 된다.
   ```

   콘솔이면 **인스턴스 → 작업 → 인스턴스 설정 → 종료 동작 변경**.

   🔴 **박스 안에서는 못 본다.** 인스턴스 프로파일 권한이 [`iam/policy-s3-results.json`](iam/policy-s3-results.json)
   의 **S3 다섯뿐**이라 `ec2:DescribeInstanceAttribute` 가 없다. **본인 자격증명(로컬·콘솔)으로
   확인할 것** — SSH 로 들어가서 보려던 계획이면 안 된다.

   **그리고 볼륨 쪽도 같이 본다.** 「terminate 면 EBS 까지」가 성립하려면 **terminate + 루트
   볼륨 `DeleteOnTermination=true`** 둘이 만나야 하고, **런치 때 만든 루트 볼륨은 기본이 `true`** 다.

   ```bash
   aws ec2 describe-instances --instance-ids i-xxxxxxxx      --query 'Reservations[].Instances[].BlockDeviceMappings[].{dev:DeviceName,del:Ebs.DeleteOnTermination}'
   ```

   ⚠️ **둘 다 안전한 설정은 없다** — `false` 로 두면 인스턴스를 지워도 **볼륨이 남아 요금이
   계속 나간다**(§산출물 끝의 경고와 같은 자리). 고르는 문제다.

   ⚠️ **위 명령들은 이 저장소에 실행 이력이 없다** — AWS CLI 표준 문법이고, 여기서 처음 밟는다.
5. **요금 태그** — 아래

### 요금을 기록하려면 (태그)

이 repo 에는 지금까지 **EC2 요금 기록이 한 줄도 없다.** 4차까지 인스턴스를 여러 번 띄웠는데
`loadtest/results/*/README.md` 어디에도 금액이 없어서, 「AWS 실측 얼마 드나」에 **추정으로밖에
답할 수 없다.** 태그 하나면 그 칸이 열린다.

시작할 때 붙이는 게 가장 확실하다:

```bash
aws ec2 run-instances ... \
  --tag-specifications \
    'ResourceType=instance,Tags=[{Key=Project,Value=shadowfit-measure}]' \
    'ResourceType=volume,Tags=[{Key=Project,Value=shadowfit-measure}]'
```

**볼륨에도 붙인다** — 인스턴스를 끈 뒤 남은 볼륨 요금이 어디로 갔는지 나중에 가르려면 필요하다.

`preflight` 가 태그를 읽어 **없으면 경고한다.** 인스턴스가 살아 있을 때만 고칠 수 있어서다.
(태그를 IMDS 로 읽으려면 인스턴스의 «메타데이터의 태그 허용» 이 켜져 있어야 한다. 꺼져 있으면
경고만 뜨고 측정은 그대로 돈다 — 막지 않는다.)

끝나면 `MANIFEST.txt` 의 `# 요금` 절에 **곱해야 할 것들**(타입·리전·가동 시간·볼륨)이 남는다.
실제 청구액은 인스턴스 안에서 알 수 없으므로 하루 이틀 뒤 Cost Explorer 에서
`Project=shadowfit-measure` 로 필터해 **`(미기입)` 칸을 채운다.**

> 다음 라운드부터는 이 값이 선례가 된다 — 추정이 아니라 실측으로 답할 수 있다.

### IAM — `iam/` 의 세 파일

플레이스홀더 두 개(`__BUCKET__`·`__PREFIX__`)만 바꿔 쓴다. `__PREFIX__` 는 `S3_BASE` 의
프리픽스와 같아야 한다 — `S3_BASE=s3://my-bucket/shadowfit` 이면 `shadowfit`.

| 파일 | 붙는 대상 | 용도 |
|---|---|---|
| `trust-ec2.json` | 역할 신뢰 정책 | EC2 가 이 역할을 맡을 수 있게 |
| `policy-s3-results.json` | **EC2 역할** | 결과를 **올린다** |
| `policy-s3-results-read.json` | **운영자 CLI 사용자** | 결과를 **회수한다** ([#199](https://github.com/Shadowfit/init/issues/199)) |

🔴 **세 번째가 없으면 무인 실행의 회수 경로가 안 닫힌다.** 러너는 업로드에 성공하면 스스로
꺼지는데(`run_all.sh` 의 `AUTO_SHUTDOWN`), S3 를 읽을 권한을 가진 주체가 그 인스턴스뿐이면
**결과를 올려놓고 아무도 못 읽는다.** 08-12 라운드에서 실제로 이 상태였다.

```bash
BUCKET=my-bucket
PREFIX=shadowfit

sed -e "s/__BUCKET__/$BUCKET/g" -e "s/__PREFIX__/$PREFIX/g" \
    loadtest/aws/iam/policy-s3-results.json > /tmp/policy.json

aws iam create-role --role-name shadowfit-measure \
    --assume-role-policy-document file://loadtest/aws/iam/trust-ec2.json
aws iam put-role-policy --role-name shadowfit-measure \
    --policy-name s3-results --policy-document file:///tmp/policy.json

aws iam create-instance-profile --instance-profile-name shadowfit-measure
aws iam add-role-to-instance-profile --instance-profile-name shadowfit-measure \
    --role-name shadowfit-measure

# 🔴 회수용 — 운영자 CLI 사용자에게 «읽기만» 붙인다 (#199).
#    삭제 권한은 주지 않는다 (인스턴스 역할에 안 준 것과 같은 이유).
sed "s/__BUCKET__/$BUCKET/g" \
    loadtest/aws/iam/policy-s3-results-read.json > /tmp/policy-read.json

aws iam put-user-policy --user-name "$(aws sts get-caller-identity --query Arn \
    --output text | sed 's#.*/##')" \
    --policy-name shadowfit-measure-results-read \
    --policy-document file:///tmp/policy-read.json
```

⚠️ **프로파일 이름을 믿지 말 것.** `~/.aws/credentials` 에 `shadowfit-admin` 같은 이름이
있어도 실제 주체는 다를 수 있다 — 08-12 에 그 프로파일이 `default` 와 **같은 임시 사용자
키**를 담고 있어 한참 헤맸다. 붙이기 전에 `aws sts get-caller-identity` 로 **UserId 를 직접
확인**한다(위 명령이 그 값을 그대로 쓰는 이유).

> ✅ **2026-08-17 확인 — 이 계정에는 `shadowfit-measure` 프로파일이 이미 있고, 임시 사용자
> (`shadowfit-loadtest-temp`)로도 기동 시 붙일 수 있다.** 위 `create-role` 절차는 **처음
> 만들 때만** 필요하다. 그냥 기동 명령에 `--iam-instance-profile Name=shadowfit-measure` 를 넣을 것.
> 🔴 **`iam:GetInstanceProfile` 이 거절된다고 「못 붙인다」로 결론내지 말 것** — 조회 권한과
> 사용 권한은 다르다. 2026-08-17 리허설 두 번이 그 오진으로 S3 를 통째로 건너뛰었다.
> 확인은 `run-instances --dry-run` 을 **가짜 이름과 대조**해서 한다(가짜는 `InvalidParameterValue`).

인스턴스 시작 시 이 인스턴스 프로파일을 붙이거나, 이미 떠 있으면
`aws ec2 associate-iam-instance-profile --instance-id i-xxx --iam-instance-profile Name=shadowfit-measure`.

**권한을 이렇게 자른 이유:**

| 넣은 것 | 왜 |
|---|---|
| `s3:ListBucket` (버킷 단위) | `aws s3 sync` 가 목적지를 나열해 무엇을 올릴지 정한다. 로컬 스모크에서 이게 없을 때 나온 에러가 정확히 `AccessDenied ... ListObjectsV2` 였다 |
| `s3:PutObject` | 업로드 본체 |
| `s3:GetObject` | sync 가 기존 객체와 비교할 때 쓴다. 없어도 대개 도는데, **8시간 뒤에 막히는 것보다 지금 넣는 쪽이 싸다** |
| `s3:AbortMultipartUpload` | 큰 파일(pt-osc 로그·writer 원시 tsv)이 멀티파트로 올라가다 끊기면 조각이 남는다. 정리 권한 |

| 뺀 것 | 왜 |
|---|---|
| **`s3:DeleteObject`** | `sync` 를 `--delete` 없이 쓰므로 필요 없다. **이 역할로는 결과를 지울 수 없다** — 무인 실행이라 사고의 방향을 한쪽으로 막아두는 편이 낫다 |
| `s3:*` / 전체 버킷 쓰기 | 객체 권한은 `__PREFIX__/*` 로만. 다른 프리픽스는 못 건드린다 |

⚠️ **버킷이 SSE-KMS(고객 관리 키)면 `kms:GenerateDataKey`·`kms:Decrypt` 를 그 키에 대해
따로 줘야 한다.** 기본 SSE-S3 면 필요 없다. 이걸 빠뜨리면 `preflight` 의 쓰기 시험에서
바로 막히므로 8시간을 잃지는 않는다.

⚠️ 버킷 리전은 인스턴스와 맞추는 편이 싸다(교차 리전 전송료). 보존 기간(수명주기 규칙)은
**정하지 않았다** — 근거 없는 기본값을 넣지 않는다.

## 실행

```bash
sudo -i
curl -fsSL https://raw.githubusercontent.com/Shadowfit/init/main/loadtest/aws/bootstrap.sh -o bootstrap.sh
bash bootstrap.sh

cd /root/init
S3_BASE=s3://내버킷/shadowfit nohup bash loadtest/aws/run_all.sh > /root/run_all.log 2>&1 &
```

`nohup` 없이 `&` 만 붙이면 **SSH 가 끊길 때 같이 죽는다.** 그러면 컴퓨터를 못 끈다.

진행은 SSH 로 `tail -f /root/run_all.log`, 또는 S3 의 `phases.tsv` 를 본다.

### P6 — 2대 구성 (동거 용량)

🔴 **P6 는 위 절차가 그대로 안 통한다.** 다른 라운드는 「러너 = 측정 대상」인데,
P6 의 rig 은 **부하기에서 돌며 대상 박스를 SSH 로 몬다**(`../results/coresidency-2026-08-15/coresidency_sweep.sh:39`).
그래서 `run_all.sh` 가 도는 자리도 **부하기**다.

🔴 **`coresidency` 를 `ddl`·`backup` 과 같은 `PHASES` 에 넣지 말 것** — 러너의 자리 자체가
다르다(`run_all.sh:62`). 섞으면 다른 단계가 「측정 대상」으로 삼는 박스가 부하기가 된다.

**사람이 먼저 해야 하는 것** (부트스트랩보다 앞이다)

1. 인스턴스 **2대** — 대상 `c7i.4xlarge`, 부하기 **`c7i.xlarge` 이상**. 같은 VPC·서브넷(사설 IP 로 붙는다)
   🔴 **부하기를 `c7i.large`(2 vCPU)로 띄우지 말 것** — §T(⑩ 부하기 코어 팔)의 「제한 2코어」와
   「전체」가 같은 조건이 되어 **한 판도 안 돈다.** 실패가 아니라 경고 한 줄로 건너뛰고
   게이트는 통과하므로, 로그를 안 읽으면 ⑩ 을 검증한 줄 알고 라운드가 끝난다 (#312)
2. 보안그룹 인바운드 — 부하기 → 대상 **22**(SSH), **8000**(AI HTTP), **8080**(Spring), **6565**(gRPC)
3. **SSH 키를 부하기에 둔다** — `/root/.ssh/measure.pem`, `chmod 600`. 부하기가 대상을 몬다.
   ✅ **대상 쪽 `root@` 로그인은 부트스트랩이 연다**(2026-09-02, #642 — P4 절과 같은 수정)
4. **두 박스가 같은 토큰을 든다** — `AI_PUBLIC_TOKEN`(프레임 경로)·`INTERNAL_API_TOKEN`(gRPC 메타데이터)을
   대상과 부하기에서 **각각** 같은 값으로. 어긋나면 preflight 가 막는다.
   ⚠️ **두 변수끼리는 달라야 한다** — 같은 값이면 AI 가 아예 안 뜬다(#230 단언 · #261)

**부트스트랩 — `ROLE` 로 갈라 돌린다**

```bash
# 대상 박스 (AI + Spring + MySQL 세 컨테이너가 다 떠야 한다)
ROLE=p6-target bash bootstrap.sh

# 부하기 박스 (ghz·페이로드·프레임 자산. 끝에 실행 명령을 그대로 뱉는다)
ROLE=p6-loader bash bootstrap.sh
```

**실행 — 부하기에서**

```bash
cd /root/init
S3_BASE=s3://내버킷/shadowfit TARGET_HOST=<대상 사설 IP> \
TARGET_SSH="ssh -i /root/.ssh/measure.pem -o StrictHostKeyChecking=no root@<대상 사설 IP>" \
AI_PUBLIC_TOKEN=<대상 .env 의 AI_PUBLIC_TOKEN> GHZ_TOKEN=<대상 .env 의 INTERNAL_API_TOKEN> \
GHZ_RPS=19 GHZ_DATA=/root/batch_multi.json GHZ_BIN=/usr/local/bin/ghz \
PHASES="coresidency_preflight coresidency_rehearsal coresidency collect" \
  nohup bash loadtest/aws/run_all.sh > /root/run_all.log 2>&1 &
```

팔(`A B C`)·레벨(`20 40 60 80 120 160`)·`DUR`·`REPEATS` 는 **기본값이 확정값**이라 안 적어도 된다.
안 적으면 안 되는 것이 둘이다:

- `GHZ_RPS` — **기본값이 없다.** 「정한 값」이라 근거가 결과의 조건 칸에 같이 가야 해서다
  ([`../../docs/decisions/ai-coresidency-capacity.md`](../../docs/decisions/ai-coresidency-capacity.md) §5-2·결정 로그)
- ~~`GHZ_BIN`~~ → ✅ **고쳤다 (#249, 2026-08-17)** — 기본값이 `/usr/local/bin/ghz` 라 이제 안 적어도 된다.
  아래는 왜 그 함정이 있었는지의 기록이다. 🔴 **기본값이 부트스트랩 설치 경로와 다르다.** `bootstrap.sh` 는 릴리스 바이너리를
  `/usr/local/bin/ghz` 에 깔지만(`bootstrap.sh:179`) 러너 기본값은 `/home/ec2-user/go/bin/ghz`
  다(`run_all.sh:118`, 옛 라운드의 go install 경로). 안 적으면 preflight 가 `GHZ_BIN(실행권한)`
  으로 막는다 — 막히긴 하니 라운드를 버리진 않는다

라운드는 **12판 ≈ 2시간**, 부트스트랩·preflight·리허설까지 **3.5시간 안팎**이다.
`coresidency_rehearsal` 이 실패하면 **정판을 안 돈다** — 리허설 판정은 종료 코드가 아니라
결과 표(`setup_fail`·`req`)로 한다.

### 팔 D — 관측 스택 동거 비용 (從 R9, Q5·가설 H5)

🟢 **코드는 이미 있다.** `CORES_ARMS`(기본 `"A B C"`)에 `D` 를 더하기만 하면 된다 —
`ARMS 에 D 를 넣으려면 "A B C D"`(rig 헤더 주석), `run_all.sh` 도 `CORES_ARMS` 를 그대로
`coresidency_sweep.sh` 의 `ARMS` 로 넘긴다. 팔 D = 팔 C + `docker compose --profile obs
up -d`(prometheus·grafana·mysqld-exporter·cadvisor·node-exporter).

```bash
CORES_ARMS="A B C D" \
PHASES="coresidency_preflight coresidency_rehearsal coresidency collect" \
  ... (나머지는 위와 동일)
```

🔴 **1·2라운드 둘 다 「길어지면 뺀다」로 D 를 뺐다** — 판이 **+19개(+42분)** 는다
([`AWS-RIDE-ALONG.md` §1 從-R9](../AWS-RIDE-ALONG.md)). **다음 P6 류 라운드를 돌리기 전에
D 를 넣을지부터 정할 것** — 여유 시간이 없으면 `CORES_ARMS="A B C"` 로 그대로 두고, 있으면
위처럼 `D` 를 더한다. 판정선은 설계 그대로: **D 가 C 대비 두 자릿수 % 내려가면 H5(관측
스택이 AI 부하에서도 안 비싸다) 반증**([`ai-coresidency-capacity.md` §3(팔 D)](../../docs/decisions/ai-coresidency-capacity.md)).

✅ **관측 스택 이미지는 부트스트랩이 미리 당겨둔다**(`bootstrap.sh` ROLE=p6-target, 2026-09-02
추가) — 그래서 판 중간에 D 로 처음 전환해도 이미지 pull 지연이 그 판의 소요에 안 섞인다.
⚠️ 이 사전 pull 자체는 아직 실제 EC2 에서 안 돌려봤다.

⚠️ **여기 적힌 절차로 2대를 실제로 띄워 본 적은 없다.** 포트·키 경로는 rig 코드에서 읽은 것이다.

### P4 — 2대 구성 (복제 지연 · 반동기)

🔴 **P6 와 자리가 반대다.** P6 는 부하기에서 돌며 대상을 SSH 로 몰지만, **P4 의 러너는
소스 박스에서 돈다.** 이유는 시계 하나다 — 하트비트를 «소스 시각 vs 리플리카 시각» 으로
재면 두 인스턴스의 시계 차이가 그대로 지연으로 찍힌다
([설계](../../docs/decisions/replication-lag-and-semisync.md) §7). 그래서 쓰는 것도 읽는
것도 소스 박스의 시계로 묶고, 리플리카는 원격 3306·SSH 로만 만진다.

🔴 `repl` 을 `ddl`·`backup` 과 같은 `PHASES` 에 넣지 말 것. 저 둘은 디스크가 지배하고
이쪽은 무대(1,000만 행)를 자기 조건으로 고정한다 — 섞이면 셋 다 오염된다.

**사람이 먼저 해야 하는 것**

1. 인스턴스 **2대 — 타입이 같아야 한다.** 다르면 관측된 지연이 「복제 구조 때문」인지
   「기계 차이 때문」인지 원리적으로 안 갈린다(설계 §3). 「리플리카는 싼 걸로」는 운영
   선택지지 이 실험의 조건이 아니다
2. 보안그룹 — 소스 → 리플리카 **3306**·**22**, 리플리카 → 소스 **3306**(복제가 이 방향으로 붙는다)
3. **SSH 키를 소스 박스에** (`/root/.ssh/measure.pem`, `chmod 600`) — 소스가 리플리카에
   사본을 붓고 컨테이너를 올린다. ✅ **리플리카 쪽 `root@` 로그인은 부트스트랩이 연다**
   (2026-09-02, #642) — AL2023 기본은 막혀 있는데, 그동안 이 단계가 문서에 없어서 매번
   손으로 뚫었다. 지금은 `bootstrap.sh` 가 role 과 무관하게 항상 `ec2-user`/`ubuntu` 의
   `authorized_keys` 를 root 로도 복사해둔다 — `measure.pem` 이 launch 시 지정한 키페어면
   추가 조치 없이 `root@<리플리카 사설 IP>` 가 바로 된다
4. 둘 다 `ROLE=db` 로 부트스트랩 — 이 라운드는 백엔드·AI 를 안 쓴다
5. **AZ 를 정하고 라벨로 넘긴다** — 같은 AZ / 다른 AZ. **이 선택이 Q2 의 답을 자릿수
   단위로 바꾼다**(설계 §9-1 ②).
   🟢 **08-22 라운드는 «같은 AZ» 로 정해서 돌았다**([결정](../../docs/decisions/replication-lag-and-semisync.md) §10-2 ㉯) — 그래서
   그 결과에는 **「같은 AZ」 조건이 붙어 있고 다른 AZ 는 실측이 없다.**
   🔴 **다음 라운드에도 이건 손잡이다** — 라벨(`REPL_AZ_MODE`)이 조건 칸에 그대로 박힌다

**실행 — 소스 박스에서**

```bash
cd /root/init
OUTDIR=$PWD/loadtest/results/replication-aws-$(date +%F) \
S3_BASE=s3://내버킷/shadowfit \
REPLICA_HOST=<리플리카 사설 IP> \
REPLICA_SSH="ssh -i /root/.ssh/measure.pem -o StrictHostKeyChecking=no root@<리플리카 사설 IP>" \
REPL_AZ_MODE="same-az(ap-northeast-2a)" \
PHASES="repl_preflight repl_gate repl ridealong collect" \
  nohup bash loadtest/aws/run_all.sh > /root/run_all.log 2>&1 &
```

💡 **`OUTDIR` 은 이제 안 줘도 된다 (2026-08-23).** [#366](https://github.com/Shadowfit/init/pull/366) 이
`PHASES` 에서 라운드를 추론하게 했고, [#377](https://github.com/Shadowfit/init/pull/377) 이
이름을 **`<라운드>-aws-<날짜>`** 로 맞췄다 — 같은 날 두 번째 판이면 `-b-`·`-c-` 로 가른다
(`run_all.sh:107~130`). [#358](https://github.com/Shadowfit/init/issues/358) 은 그것으로 **닫혔다.**
🔴 **08-22 라운드는 그 수정 전이라 위처럼 손으로 줬다** — 위 명령을 그대로 두는 이유는
「그때 무엇을 밟았나」가 재현 절차이기 때문이다. 지금 돌리면 `OUTDIR` 줄을 빼도 된다.
⚠️ 다만 **그 추론 경로는 EC2 에서 아직 안 밟혔다**(#377 미검증).

`repl_gate` 가 무대를 세우고 게이트 G1~G3 을 본다. **실패하면 `repl` 을 건너뛴다** —
계측(G3 양성 대조군)이 안 선 채로 잰 지연은 「복제의 성질」이 아니라 「무대의 결함」이다.

rig 과 손잡이·산출물은 [`../results/replication-2026-08-17/REPL2-RIG.md`](../results/replication-2026-08-17/REPL2-RIG.md).

🟢 **이 절차로 2대를 실제로 돌렸다 — 2026-08-22** ([결과](../results/replication-aws-2026-08-22/README.md) · `c7i.2xlarge` 2대 ·
같은 AZ · **10판 전부 유효 · 결함 0**). 소요는 **러너 1.39h**(`repl` 단계만 80분 —
측정 30분 + **판 사이 따라잡기 50분**). 위 포트·키 경로는 그 라운드가 실제로 쓴 값이다.

🟢 **그리고 「끊어서 먼저 보라」가 값을 했다.** `REPL_SESSIONS=134` 스모크를 앞에 태웠고
**1회차가 G2 에서 막혔다** — `harden_replica` 가 건 `super_read_only=ON` 이
`INSTALL PLUGIN` 을 막아 리플리카 쪽 반동기 플러그인이 안 깔렸다
([#380](https://github.com/Shadowfit/init/pull/380)). **1,000만 행 시딩을 마친 뒤에야 만났을
결함**이라, 다음 라운드에도 이 순서를 유지할 것.

🔴 **안 밟은 것은 남아 있다** — **다른 AZ** 는 안 쟀고, 그건 인스턴스를 다시 띄우는 **새 라운드**다.

### R10-a — 1대 구성 (프레임 경로 계측)

무대 결정은 [`r10-loadgen-topology.md` §7](../../docs/decisions/r10-loadgen-topology.md):
**쪼갠다, R10-a(1대 동거)를 먼저.** 대상 **`c7i.4xlarge`(16 vCPU) 한 대**뿐이고 부하기 박스가 없다.

🔴 **MySQL·Spring·도커 스택을 안 쓴다.** rig 이 uvicorn 을 직접 띄우고 gRPC 로 세션을 연다.
**도커로 띄우면 안 된다** — 계측 노브(`FRAME_PATH_METRICS`·`GIL_SWITCH_INTERVAL`)가
compose 를 안 넘어간다([#399](https://github.com/Shadowfit/init/issues/399)). 그래서 ROLE 이 따로 있다.

```bash
# 대상 박스 하나 — venv·의존성·proto 스텁까지 세운다 (mediapipe 때문에 3~8분)
ROLE=ai-venv bash bootstrap.sh
```

🔴 **파이썬은 3.12 다. 못 맞추면 부트스트랩이 멈춘다** — 기준 관측(「346 RPS 에 9.5 vCPU」)이
`python:3.12-slim` 컨테이너의 것이고 R10 이 재는 것이 **GIL 거동**이라, 다른 버전으로 재면
비교가 성립하지 않는다. 배포판에 3.12 가 없으면 손으로 깔고 `AI_PY=/경로/python3.12` 로 준다
(Ubuntu 22.04 기본 저장소엔 없다).

**실행 — 같은 박스에서**

```bash
cd /root/init
nohup python3 loadtest/results/frame-path-overhead-2026-08-23/run_arms.py \
  --sessions 160 --dur 90 --pool 201 \
  --plan "A,A,B,A@0.0005,A@0.05,B@0.0005,B,A@0.0005,A@0.05,B@0.0005,A,A@0.0005,A@0.05,B@0.0005,A,B,A@0.05,B@0.0005,A,B,A@0.0005,B@0.0005,A,B,A@0.0005,A@0.05" \
  --discard 1 \
  --out loadtest/results/frame-path-r10a-$(date +%F) > /root/r10a.log 2>&1 &
```

| | |
|---|---|
| 규모 | **160세션 · 90초 · 풀 201** — 기존 라운드와 같은 조건이라야 비교가 된다([설계 §8-2](../../docs/decisions/ai-receive-path-scaling.md)). rig 기본은 8세션·45초·풀=세션+4 라 **안 주면 다른 판**이다 |
| 팔 | A(기준) · B(구간·`lease` + 앵커) · `A@0.0005`·`A@0.05`(GIL **양방향**) · `B@0.0005`(계측×GIL **상호작용**, 2×2 를 닫는 칸) 🔴 **정본은 [설계 §13](../../docs/decisions/ai-receive-path-scaling.md) 이고 위 판 문자열은 거기서 옮긴 것이다** — 바꾸려면 설계부터 고칠 것 |
| 배열 | **5×5 라틴 방격 + 버림 1 = 26판.** 각 팔이 **각 위치에 정확히 한 번** 온다 — 재기동이 −24%, 판 순서가 −6.4% 를 흔들기 때문(설계 §9-4) |
| ⏱ 소요 | 판당 120초면 **52분**, 160초면 **69분** — `setup`(160세션 여는 시간)이 미측정이라 **버림판이 처음 준다.** 인스턴스 1.5시간 |
| 🔴 **안 읽는 값** | `handler_concurrency` — 부하기가 동거해서 **절대값이 다친다.** R10-b(2대)의 몫이다(§7) |
| 러너 | 🟢 **`PHASES="framepath collect"` 로 돈다** — 아래 「무인으로 돌리려면」. 위 손 명령도 그대로 유효하다(회수만 손으로) |

⚠️ **이 절차로 실제로 띄워 본 적은 없다.** ROLE 은 `bash -n` 과 역할 분기 검증만 거쳤고,
위 판은 rig 의 `parse_arm` 으로 파싱·라틴 방격만 검산했다.

#### 무인으로 돌리려면 — `PHASES="framepath collect"`

```bash
cd /root/init
S3_BASE=s3://내버킷/shadowfit \
FP_PLAN="<설계 §13 의 문자열>" \
PHASES="framepath collect" \
  nohup bash loadtest/aws/run_all.sh > /root/run_all.log 2>&1 &
```

🔴 **`FP_PLAN` 은 기본값이 없다.** 안 주면 게이트가 막는다 — 격자의 정본은 설계 §13 이고,
러너에 예시를 박아두면 **그 예시가 조용히 정본이 된다**(2026-08-23 에 실제로 격자가 두 벌이
된 적이 있다). `FP_SESSIONS`·`FP_DUR`·`FP_POOL`(160·90·201)은 **재현 대상이 있는 값**이라
기본을 둔다.

**게이트가 보는 것 다섯** — 넷은 «환경 결함이 측정 결과로 위장하는» 자리다:

| | 왜 |
|---|---|
| venv 가 **3.12** 인가 | 아니면 GIL 거동이 다른 기계다 — 이 판의 주 질문이 그 노브다 |
| `FP_PLAN` | 위 |
| `frames.json` | 이 박스에서 못 만든다(mediapipe 필요) |
| 🔴 **8100/8685 가 비었나** | 전 판 서버가 안 죽어 있으면 **팔이 조용히 안 바뀐다.** 기존 서버가 응답하므로 판은 정상으로 보인다 |
| `/proc` | 도커가 없어 `docker stats` 가 없다. CPU 축의 유일한 수단([#400](https://github.com/Shadowfit/init/issues/400) ⑤) |

**끝나고 보는 것**(막지 않는다 — 판정은 사람이 한다): `nolease>0`(풀 소진 → 그 판 무효) ·
`setup_fail>0` · **계측 ON 판이 하나라도 있나**(없으면 구간·`lease` 가 통째로 빈다) ·
스냅샷 회수 실패 · `cpu.error`.

산출물은 `<OUTDIR>/framepath/` — `arms_<tag>.json` · `raw_*.tsv` · `server_*.log` ·
`run_arms.log` · `ai_venv_conditions.txt`(부트스트랩이 만든 것을 복사한다).

### R10-b — 2대 구성 (핸들러 동시성 · 최소 격자)

🟢 **2026-09-02 코드 반영.** R10-a 가 GIL 스위치 간격을 반증하고, 곁다리 프로세스분리 판이
「16 중 9.5」를 «프로세스당 천장»으로 이미 답해버려서
([`ai-receive-path-scaling.md`](../../docs/decisions/ai-receive-path-scaling.md)), R10-b 가
원래 답하려던 것 중 남은 건 **`GRPC_MAX_WORKERS`(핸들러 동시성, 기본 10,
`ai-server/app/config.py:28`)가 그 천장을 만드는 손잡이인가** 하나다. 설계는
[`r10-loadgen-topology.md` §7](../../docs/decisions/r10-loadgen-topology.md) — **착수(EC2 기동)
는 이 코드 반영과 별개로 확인**받는다([[feedback_user_decides_not_claude]]).

🔴 **자리가 R10-a 와 반대다.** R10-a 는 대상 박스 자신이 러너였는데, R10-b 는 **부하기가
러너**다(P6·coresidency 와 같은 자리) — `run_arms.py` 가 `--remote-target`/`--remote-ssh` 로
대상을 SSH 로 원격 기동·종료하고, 부하는 부하기 자신이 네트워크로 대상 gRPC 를 친다.

```bash
# 대상: ROLE=ai-venv (R10-a 와 같다) — MySQL·Spring·도커 없이 venv 만
ROLE=ai-venv bash bootstrap.sh
# 부하기: gRPC 클라이언트 + venv (서버는 안 띄운다). ROLE 은 대상과 같은 ai-venv 로 충분하다
#         — run_arms.py 가 로더 쪽에서 필요로 하는 것도 같은 venv·proto 스텁이다
ROLE=ai-venv bash bootstrap.sh
```

**실행 — 부하기에서**

```bash
cd /root/init
S3_BASE=s3://내버킷/shadowfit \
FP_REMOTE_TARGET=<대상 사설 IP> \
FP_REMOTE_SSH="ssh -i /root/.ssh/measure.pem -o StrictHostKeyChecking=no root@<대상 사설 IP>" \
FP_PLAN="B,B,B#5,B#20,B#5,B#20,B,B#20,B,B#5" \
PHASES="framepath collect" \
  nohup bash loadtest/aws/run_all.sh > /root/run_all.log 2>&1 &
```

| | |
|---|---|
| 최소 격자(사용자 확정, 2026-09-02) | **팔은 `GRPC_MAX_WORKERS` 값 하나**(계측은 켠 채 고정 — `B` 만 쓴다, `A/B` 대조는 R10-a 가 이미 닫았다). 기본(10) 대조군 + 5·20 두 값. 버림 1 + 각 3판, 위치 균형(`feedback_measure_design_needs_repeats`) — 위 `FP_PLAN` 예시가 그 배열이다 |
| 규모 | R10-a 와 같은 조건(160세션·90초·풀201)을 유지할 것 — 다르면 비교가 안 된다 |
| 🔴 CPU | **판 전체 평균만**(`cpu_remote.avg_vcpu`), warmup 미제외. R10-a 의 시계열 `cpu` 와 **같은 표에 놓지 말 것** — 이 판이 진짜 답하는 것은 처리량·지연이 `GRPC_MAX_WORKERS` 로 갈리는가다 |
| ⏱ 소요 | 미실측 — R10-a(26판·52~69분)보다 판 수는 적지만(8판) SSH 왕복(원격 기동·종료·CPU 스냅샷)이 더 걸린다. 축소 리허설로 먼저 잰다 |
| 게이트 | R10-a 의 다섯 + **여섯째**(대상 SSH·인터프리터·버전·부하기→대상 gRPC 포트) — `fp_gate()` §6 |

✅ **2026-09-02 실전 라운드 완료** — [결과](../results/frame-path-r10b-2026-09-02/README.md).
`GRPC_MAX_WORKERS`(5·10·20)는 처리량·지연을 안 움직인다(팔간 차이 2% 안, 잡음 수준) —
R10-a 의 GIL 반증에 이어 이 후보도 지워지고 「16 중 9.5」는 **프로세스당 천장**만 남았다.
리허설 중 원격 부팅 버그 셋(bootstrap.sh 의 `.env` heredoc 오염 · SSH `nohup` 응답 미분리 ·
`--bind` 원격 자동전환 누락)을 실전에서 처음 걸러 고쳤다 — 로컬 단위검증만으론 못 잡는
종류였다. 🔴 **깨진 채 남은 것**: 원격 CPU 계측(`cpu_remote`)이 전 판 실패
([#647](https://github.com/Shadowfit/init/issues/647)) · 리허설도 `run_all.sh` 를 그대로
태우면 AUTO_SHUTDOWN 대상이 되는데 **러너(부하기) 쪽에 취소용 root SSH 가 없었다**
([#648](https://github.com/Shadowfit/init/issues/648), 아래 리허설 명령에 `AUTO_SHUTDOWN=0`
을 넣은 이유).

⚠️ **리허설은 `AUTO_SHUTDOWN=0` 을 꼭 넣을 것** — `PHASES` 에 무엇을 넣든 `run_all.sh` 를
직접 부르면 끝에 자동종료 로직을 그대로 탄다(#648). 축소 리허설로 먼저 밟고, 문제없으면
본판(위 「실행」 명령, `AUTO_SHUTDOWN` 기본값 1)을 돌린다.

```bash
cd /root/init
AUTO_SHUTDOWN=0 \
FP_REMOTE_TARGET=<대상 사설 IP> \
FP_REMOTE_SSH="ssh -i /root/.ssh/measure.pem -o StrictHostKeyChecking=no root@<대상 사설 IP>" \
FP_SESSIONS=8 FP_DUR=15 FP_PLAN="B,B,B#5,B#20" \
PHASES="framepath" \
  bash loadtest/aws/run_all.sh
```

### 보정값 (從 R11) — 2026-09-02 부터 `calibration` 단계로 상설화

[`round-to-round-nonreproducibility.md`](../../docs/decisions/round-to-round-nonreproducibility.md) §2·§5
축0. **CPU% 는 시간이지 일의 양이 아니다** — 물리 호스트의 유효 클럭이 라운드마다 다르면
같은 CPU% 로 다른 처리량이 나온다(08-17 관측: AI CPU 869.3%→869.0%인데 처리량 +17.7%).
단일 스레드로 고정 프레임 수를 추론해 걸린 시간만 재면 그 박스가 초당 얼마나 일하는지가
남는다 — R6 이 이미 이 방법으로 79.9fps 를 냈다. **판정에 쓰든 안 쓰든 무조건 기록한다**:
지금 못 걸으면 그 박스가 사라진 뒤엔 영영 못 잰다(P6 1·2라운드 보정값을 그렇게 잃었다).

🟢 **ROLE=ai-venv 라운드(R10-a·R10-b)는 `PHASES` 에 `calibration` 만 추가하면 된다**
(소요 1~2분, 새 인스턴스 0):

```bash
PHASES="framepath calibration collect"
```

산출물은 `<OUTDIR>/calibration/` — `box.txt`(인스턴스 타입·ID·AZ) · `scaling_raw.txt`(전체
표) · `scaling_1w.txt`(워커=1 행만, 라운드 간 대조용). 게이트: `FP_VENV` 실행 파일 존재
(venv 3.12) · `CORES_RIG/frames.json` 존재 — 둘 다 R10 라운드가 이미 요구하는 것이라 추가
준비가 없다.

🔴 **P6(도커) 대상 박스는 이 phase 로 못 돈다** — mediapipe 가 host venv 가 아니라
`shadowfit-ai` 컨테이너 안에 있다. 대상 박스에서 손으로 돌린다(부트스트랩 뒤, 측정 전후
아무 때나 — 판정에 안 섞이므로 순서가 중요하지 않다):

```bash
# 대상 박스에서 — frames.json 을 컨테이너로 복사하고 그 안에서 돌린다
docker cp loadtest/results/coresidency-2026-08-15/frames.json shadowfit-ai:/tmp/frames.json
docker exec -i -e SCALING_WORKERS=1 -w /app shadowfit-ai \
  python - /tmp/frames.json scaling < loadtest/results/ai-path-profile-2026-08-17/profile_e2e_and_scaling.py \
  | tee coresidency/calibration_scaling.txt
```

⚠️ **이 손 명령은 아직 실제로 안 돌려봤다** — `docker exec` 로 stdin 스크립트를 먹이는 경로와
컨테이너 안 import 경로(`WORKDIR /app`)는 Dockerfile 을 읽고 유도한 것이지 실측이 아니다.
다음 P6 라운드에서 처음 밟을 때 이 줄이 안 되면 여기부터 고칠 것.

## 설정

| 변수 | 기본 | 비고 |
|---|---|---|
| `S3_BASE` | **필수** | `s3://버킷/프리픽스` |
| `RUN_ID` | `ec2-<날짜시각>` | 결과 디렉터리·S3 프리픽스 |
| `PHASES` | `preflight rehearsal ddl ridealong collect` | 단계 선택 |
| `REPLICA_HOST` | (없음) | P4 필수 — 리플리카 사설 IP |
| `REPLICA_SSH` | `ssh root@$REPLICA_HOST` | P4 — 키를 쓰면 직접 지정 |
| `REPL_AZ_MODE` | (없음) | P4 — 조건 칸에 그대로 들어간다. 비면 경고(막지는 않는다) |
| `REPL_SESSIONS` | `13334` | P4 무대 = 1,000만 행 |
| `TIMEOUT_REPL_GATE` / `TIMEOUT_REPL` | `10800` / `14400` | 3시간 / 4시간 |
| `FP_REMOTE_TARGET` | (없음) | R10-b — 대상 사설 IP. 비면 R10-a 와 같은 로컬(동거) 경로 |
| `FP_REMOTE_SSH` | `ssh root@$FP_REMOTE_TARGET` | R10-b — 키를 쓰면 직접 지정 |
| `FP_REMOTE_ROOT` | `/root/init` | R10-b — 대상의 저장소 루트 |
| `AUTO_SHUTDOWN` | **`1`** | **업로드 성공 시에만** 정지. 기본이 켜짐(2026-08-24 결정) — 박스를 남기려면 `0` |
| `SHUTDOWN_DELAY_MIN` | `5` | 정지까지의 유예(분). 취소: 박스 안에서 `pkill -f 'shutdown'`. ⚠️ **취소하려면 그 박스에 SSH 로 들어가야 하는데, 리허설을 `run_all.sh` 로 직접 돌리면 러너(예: R10-b 의 부하기)가 대상이 아닌 경우가 있다**(#648) — 2026-09-02부터 부트스트랩이 role 과 무관하게 모든 박스에 `root@` 접근을 열어두므로(#642), 어느 박스가 러너든 `measure.pem` 으로 들어가 취소할 수 있다 |
| `SYNC_SEC` | `300` | S3 주기 업로드 간격 |
| `WRITER_MAX_SEC` | `14400` | ⚠️ rig 기본은 5,400. 아래 참고 |
| `TIMEOUT_DDL` | `43200` | 12시간 (로컬 추정 5.9시간 × 2) |
| `REHEARSAL_SESSIONS` | `134` | 10만 행 |
| `CALIB_RIG` | `results/ai-path-profile-2026-08-17/profile_e2e_and_scaling.py` | 從 R11 보정값 rig 경로 |
| `TIMEOUT_CALIB` | `300` | 從 R11 — 실측 1~2분의 여유폭 |

---

## 설계 — 왜 이렇게 생겼나

**목표는 «오류가 안 나게» 가 아니라 «오류가 나도 밤을 통째로 잃지 않게» 다.**
이 rig 는 이미 한 번 무인 실행에서 죽었다([#183](https://github.com/Shadowfit/init/issues/183)).

| 장치 | 이유 |
|---|---|
| `set -e` 를 **안 쓴다** | 한 단계가 죽어도 다음 단계는 돈다. 실패는 `phases.tsv` 의 `FAIL(rc)`·`TIMEOUT` 으로 남는다 — 「재봤더니 0」과 「재지 못했다」는 다르다 |
| **주기 S3 업로드** | 5판째 죽어도 4판은 건진다. 최종 업로드만 믿지 않는다 |
| **리허설 실패 = 전체 중단** | 축소 판이 안 도는데 정판을 8시간 돌리는 건 돈만 태우는 일이다 |
| **사전 확인 실패 = 전체 중단** | 그 상태로 돌리면 **환경 결함이 측정 결과로 찍힌다.** `percona-toolkit` 이미지가 없으면 팔 B 4판이 전부 «DDL실패» 인데, 표에서는 도구의 성질과 구분되지 않는다 |
| **업로드 실패 시 정지 안 함** | 인스턴스 안에만 있는 결과를 끄는 것은 측정을 버리는 것과 같다 |
| **매니페스트** | 인스턴스 타입·디스크·커밋·버퍼풀·단계별 소요. 이 프로젝트에서 **조건 없는 수치는 인용 불가**라 이 파일이 없으면 측정도 반쪽이다 |

### 워치독을 «단계»가 아니라 «명령»에 거는 이유

`timeout` 은 프로그램을 실행하는 명령이라 **쉘 함수에는 못 씌운다.** 씌운 것처럼 보이고
조용히 안 걸린다. 그래서 상한은 각 단계 안에서 실제로 오래 도는 외부 명령
(`probe.sh`·`ddl_sweep.sh`·`docker exec`)에 직접 건다.

### `WRITER_MAX_SEC` 를 올려 둔 이유

rig 기본은 5,400초(90분)인데 팔 B 로컬 실측이 2,360초다. 여유가 2.3배뿐이라, EBS 가
로컬 NVMe 보다 느려 팔 B 가 늘어나면 **writer 가 DDL 도중 먼저 죽는다.** 측정은 계속
도는데 `max_stall`·`p50` 만 못 쓰게 되는, 제일 나쁜 실패 모양이다. 4시간으로 올려 둔다.

---

## 산출물

```
loadtest/results/online-ddl-<RUN_ID>/
├── MANIFEST.txt          ← 측정 조건. 인용할 때 같이 본다
├── phases.tsv            ← 단계별 OK/FAIL/TIMEOUT + 소요
├── rehearsal/            ← ⚠️ 경로 점검용 축소 판. **측정값이 아니다**
├── ddl/                  ← 본 측정 (ddl.tsv · writer 원시 로그 · pt-osc 로그)
└── ridealong/            ← 從 항목 (R1 worst-section · R2 MySQL 지표 · R3 미실행 사유)
```

같은 구조가 S3 의 `<S3_BASE>/<RUN_ID>/` 에도 올라간다.

**끄기 전에 [`../AWS-RIDE-ALONG.md`](../AWS-RIDE-ALONG.md) §5 를 열 것.** 결과를 repo 에
어디에 기록하는지는 같은 문서 §6 에 있다.

---

## 아직 안 붙인 것

- **主-P2**(다운샘플 다세션 재측정) — 백엔드 컨테이너와 ghz, `gen_batch_multi.py` 재생성이
  선행이라 단계로 안 넣었다. `PHASES` 에 추가하려면 그 준비부터 설계해야 한다
- ~~**主-P3·P4**(백업/복구·복제) — 설계 문서부터 써야 한다~~
  → ✅ **둘 다 돌았다.** P3 는 08-13 라운드, **P4 는 08-22 라운드**(2대 · 10판 전부 유효 ·
  [결과](../results/replication-aws-2026-08-22/README.md)). 단계는 `repl_preflight`·`repl_gate`·`repl` 이고 절차는 위 「P4 — 2대 구성」.
  🔴 **남은 것은 «안 잰 것» 하나 — 다른 AZ** 다
- **從-R3**(3-way 조인) — `reports`·`exercise_sessions`·`users` 시딩이 선행. 지금은 사유만 기록한다
- ~~이 러너 자체는 **한 번도 EC2 에서 안 돌았다**~~ → 🔴 **낡은 문장이었다 (2026-08-23 정정).**
  `MANIFEST.txt` 가 커밋된 EC2 라운드만 **여덟**이다 — `online-ddl-aws-2026-08-12` ·
  `backup-restore-aws-2026-08-13`(+`-b-`) · `coresidency-aws-2026-08-15`·`-16`·`-b-16`·`-17` ·
  `replication-aws-2026-08-22`.
  ⚠️ **그래도 「끊어서 먼저 보라」는 유효하다** — 새 `PHASES` 조합의 첫 실행은 게이트까지만
  끊는 편이 싸다. 08-22 스모크가 정확히 그렇게 G2 결함을 잡았다