variable "region" {
  description = "리전. 부하테스트 라운드가 지금까지 ap-northeast-2a AZ 를 써왔다 (replication-lag-and-semisync.md §10-2)."
  type        = string
  default     = "ap-northeast-2"
}

variable "bucket_name" {
  description = "결과를 받을 S3 버킷 이름. loadtest/aws/README.md §사전준비 1 — 근거 없는 기본값을 넣지 않는다, 반드시 명시할 것."
  type        = string
}

variable "bucket_prefix" {
  description = "버킷 안 프리픽스. S3_BASE=s3://버킷/프리픽스 의 프리픽스와 같아야 한다."
  type        = string
  default     = "shadowfit"
}

variable "create_bucket" {
  description = "버킷을 이번에 새로 만들지, 이미 있는 버킷을 재사용할지. 라운드가 반복되며 같은 버킷을 계속 쓰는 게 README 의 전제라 기본값을 두지 않는다 — 매번 의식하고 고를 것."
  type        = bool
}

variable "role_name" {
  description = "IAM 역할·인스턴스 프로파일 이름. loadtest/aws/README.md 가 이미 이 이름으로 만들어 써왔다."
  type        = string
  default     = "shadowfit-measure"
}
