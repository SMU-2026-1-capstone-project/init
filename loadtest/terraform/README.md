# 부하테스트 EC2 인프라 — Terraform

`loadtest/aws/README.md`·`../AWS-RIDE-ALONG.md` 가 매 라운드 사람이 손으로 하던 것(인스턴스 생성,
보안그룹, S3 버킷, IAM 인스턴스 프로파일)을 코드로 옮긴 것. **값·포트·정책은 그 두 문서와 기존
`loadtest/aws/iam/*.json` 을 그대로 재사용했다** — 여기서 새로 지어낸 숫자는 없다.

🔴 **아직 한 번도 `terraform apply` 로 실제 띄워본 적이 없다.** `terraform validate`/`plan` 문법
검증만 거쳤다. AWS-RIDE-ALONG.md 의 다른 절차들도 처음엔 "적힌 대로 될 것"이라고만 표시했다가
실제로 밟으며 여러 번 고쳐진 전례가 있으니(§ P6, R10-a 등 "이 절차로 띄워본 적 없다" 표기),
이 모듈도 첫 실행은 축소 리허설처럼 다뤄야 한다.

## 왜 두 디렉터리로 나눴나

| | 수명 | 담당 |
|---|---|---|
| `bootstrap/` | **한 번** — S3 버킷, IAM 역할/인스턴스 프로파일 | `loadtest/aws/README.md` §사전 준비 |
| `round/` | **라운드마다** — 보안그룹, EC2 인스턴스(1~2대) | `loadtest/aws/README.md` §인스턴스, §P6/§P4 |

한 state 로 합치면 라운드 끝나고 `terraform destroy` 할 때 S3 버킷·IAM 역할까지 같이 지워질
위험이 있다("항상 임시 생성→측정→삭제"인 건 EC2 뿐이지 버킷/IAM 이 아니다 — 버킷은 여러 라운드에
걸쳐 재사용된다). 분리해서 `round/` 만 마음 놓고 지운다.

## 쓰는 법

```bash
# 1) 한 번만
cd bootstrap
cp terraform.tfvars.example terraform.tfvars   # 값 채우기
terraform init && terraform apply

# 2) 라운드마다
cd ../round
cp examples/p6.tfvars terraform.tfvars   # 라운드에 맞는 예시 골라서 복사·수정
#   instance_profile_name 은 위 1)의 output.instance_profile_name 을 넣는다
terraform init && terraform apply

# ... SSH 접속 → run_all.sh 실행 (아래 "자동화 범위" 참고) ...

# 3) 라운드 끝나면
terraform destroy   # round/ 에서만. bootstrap/ 은 그대로 둔다
```

## 자동화 범위 — 여기까지만 하고 그 다음은 그대로 손으로

- **하는 것**: 인스턴스 생성 + 보안그룹 + (옵션) 부팅 시 `bootstrap.sh` 자동 실행(`ROLE`·`REF` 만)
- **안 하는 것**: `run_all.sh` 실행. `GHZ_RPS`·`REPLICA_HOST`·`AI_PUBLIC_TOKEN` 같은 라운드별 값은
  인스턴스가 뜬 *뒤에* 서로의 사설 IP를 알아야 정해지는 것들이라, `user_data`로 앞서 박아넣으면
  오히려 실수를 코드로 고정하는 꼴이 된다. `aws/README.md` 의 실행 커맨드를 그대로 SSH 로 친다.
- **운영자 IAM 읽기 정책**(`policy-s3-results-read.json`)도 자동화하지 않았다 — 그건 인스턴스가
  아니라 사람(운영자 CLI 사용자)에게 붙는 정책이라, 테라폼이 남의 IAM 사용자 정책을 건드리는 건
  이 모듈이 잴 대상(EC2 인프라)을 벗어난다고 판단했다.

## 라운드별 `internal_ports`/`instances` 값은 지어내지 않았다

`round/examples/*.tfvars` 세 개(`single`·`p6`·`p4`)의 포트·인스턴스 타입은 전부
`loadtest/aws/README.md`·`AWS-RIDE-ALONG.md` §4 에 실제로 적혀 있던 값을 그대로 옮긴 것이다.
새 라운드(R10-a 1대 구성 등)는 `single.tfvars` 를 베이스로 `role`만 바꾸면 된다(`ai-venv` 등).

## 남은 것 (일부러 안 건드림)

- `AUTO_SHUTDOWN`(`run_all.sh` 안 환경변수)은 여전히 SSH 로 손수 넘긴다 — 이건 인프라가 아니라
  측정 실행의 조건값이다.
- 요금 태그(`Project=shadowfit-measure`)는 인스턴스·볼륨에 이미 넣었지만, `aws/README.md` 가 말한
  "볼륨에도 붙인다"의 볼륨 태그는 `root_block_device.tags` 로 넣었다 — 다만 이 필드가 실제
  Cost Explorer 필터에 잡히는지는 미검증이다(AWS 프로바이더 문서상 지원되나 이 계정에서 실측 안 함).
