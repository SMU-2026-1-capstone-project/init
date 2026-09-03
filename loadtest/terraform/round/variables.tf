variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "round_name" {
  description = "AWS-RIDE-ALONG.md 의 라운드 이름 (예: p6, p4, r10a, online-ddl). 보안그룹·인스턴스 이름에 그대로 들어간다."
  type        = string
}

variable "vpc_id" {
  description = "인스턴스가 들어갈 VPC. 다인스턴스 라운드(P6·P4)는 같은 VPC·서브넷이 조건이다 (aws/README.md)."
  type        = string
}

variable "subnet_id" {
  type = string
}

variable "admin_cidr" {
  description = "운영자가 SSH 로 접속할 출발지 CIDR (예: 1.2.3.4/32). 0.0.0.0/0 금지 — 매번 본인 IP 로 좁힐 것."
  type        = string
}

variable "key_name" {
  description = "기존 AWS 키페어 이름. 이 모듈은 키를 만들지 않는다 — measure.pem 은 이미 사람이 갖고 있는 자원이라 새로 발급하지 않는다."
  type        = string
}

variable "instance_profile_name" {
  description = "bootstrap/ 모듈의 output.instance_profile_name 값 (기본 shadowfit-measure)."
  type        = string
  default     = "shadowfit-measure"
}

variable "root_volume_size_gb" {
  description = "gp3 루트 볼륨 크기. README: 'gp3 100GB 면 1,000만 행 스윕에 충분'."
  type        = number
  default     = 100

  validation {
    # 2026-09-01 실측: al2023 AMI 스냅샷이 30GB 미만을 거부한다
    # (InvalidBlockDeviceMapping: "Volume of size 20GB is smaller than snapshot ..., expect size >= 30GB").
    condition     = var.root_volume_size_gb >= 30
    error_message = "al2023 AMI 스냅샷 최소 크기가 30GB다 (실측 확인, 2026-09-01)."
  }
}

variable "internal_ports" {
  description = <<-EOT
    인스턴스끼리(같은 보안그룹 self 참조) 열어야 하는 TCP 포트. 라운드마다 다르므로 기본값을 두지 않는다.
    실측 근거(aws/README.md):
      - P6(동거 용량, 부하기→대상): [22, 8000, 8080, 6565]
      - P4(복제, 소스↔리플리카):     [22, 3306]
      - P1/P3(1대):                 [] (인스턴스가 하나뿐이라 내부 규칙 불필요)
  EOT
  type = list(number)
}

variable "instances" {
  description = <<-EOT
    이번 라운드에 띄울 인스턴스 목록. 예:
      P6  = [{name="target", role="p6-target", instance_type="c7i.4xlarge"},
             {name="loadgen", role="p6-loader", instance_type="c7i.xlarge"}]
      P4  = [{name="source",  role="db", instance_type="c7i.2xlarge"},
             {name="replica", role="db", instance_type="c7i.2xlarge"}]
      P1/P3 = [{name="measure", role="db", instance_type="c7i.2xlarge"}]
    role 는 bootstrap.sh 의 ROLE 환경변수로 그대로 넘어간다.
  EOT
  type = list(object({
    name          = string
    role          = string
    instance_type = string
    ami           = optional(string)
  }))
}

variable "auto_bootstrap" {
  description = <<-EOT
    부팅 시 user_data 로 bootstrap.sh(ROLE·REF 만)를 자동 실행할지.
    aws/README.md 의 run_all.sh 실행(GHZ_RPS·REPLICA_HOST 등 라운드별 값)은 인스턴스 간 IP를
    서로 알아야 해서 여기서 자동화하지 않는다 — bootstrap 까지만 자동, 실측 실행은 그대로 SSH 로 손수.
  EOT
  type    = bool
  default = true
}

variable "build_ref" {
  description = "bootstrap.sh 의 REF (측정 대상 커밋/브랜치). 기본값 main 은 bootstrap.sh 자체 기본값과 같다."
  type        = string
  default     = "main"
}
