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
3. 인스턴스 — 4차 실측 관례(c7i.2xlarge / m6i.xlarge), gp3 100GB 면 1,000만 행 스윕에 충분
4. **종료 동작 확인** — `AUTO_SHUTDOWN=1` 을 쓸 거면 인스턴스의
   `InstanceInitiatedShutdownBehavior` 가 `stop` 인지 본다. `terminate` 면 **EBS까지 날아간다**

### IAM — `iam/` 의 두 파일

플레이스홀더 두 개(`__BUCKET__`·`__PREFIX__`)만 바꿔 쓴다. `__PREFIX__` 는 `S3_BASE` 의
프리픽스와 같아야 한다 — `S3_BASE=s3://my-bucket/shadowfit` 이면 `shadowfit`.

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
```

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

## 설정

| 변수 | 기본 | 비고 |
|---|---|---|
| `S3_BASE` | **필수** | `s3://버킷/프리픽스` |
| `RUN_ID` | `ec2-<날짜시각>` | 결과 디렉터리·S3 프리픽스 |
| `PHASES` | `preflight rehearsal ddl ridealong collect` | 단계 선택 |
| `AUTO_SHUTDOWN` | `0` | `1` 이면 **업로드 성공 시에만** 정지 |
| `SYNC_SEC` | `300` | S3 주기 업로드 간격 |
| `WRITER_MAX_SEC` | `14400` | ⚠️ rig 기본은 5,400. 아래 참고 |
| `TIMEOUT_DDL` | `43200` | 12시간 (로컬 추정 5.9시간 × 2) |
| `REHEARSAL_SESSIONS` | `134` | 10만 행 |

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
- **主-P3·P4**(백업/복구·복제) — 설계 문서부터 써야 한다
- **從-R3**(3-way 조인) — `reports`·`exercise_sessions`·`users` 시딩이 선행. 지금은 사유만 기록한다
- 이 러너 자체는 **한 번도 EC2 에서 안 돌았다.** 첫 실행은 `PHASES="preflight rehearsal"`
  로 끊어서 확인하는 편이 싸다