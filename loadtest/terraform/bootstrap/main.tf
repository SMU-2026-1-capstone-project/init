# 사전 준비(한 번) — loadtest/aws/README.md 「사전 준비」 절이 지금까지 손으로 하던 것.
# S3 버킷 + IAM 역할/인스턴스 프로파일은 라운드마다 지우지 않는 자산이라 round/ 와 상태를 분리했다.
# round/ 를 매 라운드 destroy 해도 이 state 는 안 건드린다.

resource "aws_s3_bucket" "results" {
  count  = var.create_bucket ? 1 : 0
  bucket = var.bucket_name
}

data "aws_s3_bucket" "existing" {
  count  = var.create_bucket ? 0 : 1
  bucket = var.bucket_name
}

# EC2 가 이 역할을 맡을 수 있게 — 기존 loadtest/aws/iam/trust-ec2.json 그대로 재사용.
resource "aws_iam_role" "measure" {
  name               = var.role_name
  assume_role_policy = file("${path.module}/../../aws/iam/trust-ec2.json")
}

# 결과 업로드 권한 — 기존 loadtest/aws/iam/policy-s3-results.json 을 그대로 쓰되
# __BUCKET__/__PREFIX__ 플레이스홀더만 치환한다 (README §IAM 이 sed 로 하던 것과 동일).
resource "aws_iam_role_policy" "s3_results" {
  name = "s3-results"
  role = aws_iam_role.measure.id
  policy = replace(
    replace(
      file("${path.module}/../../aws/iam/policy-s3-results.json"),
      "__BUCKET__", var.bucket_name
    ),
    "__PREFIX__", var.bucket_prefix
  )
}

resource "aws_iam_instance_profile" "measure" {
  name = var.role_name
  role = aws_iam_role.measure.name
}

# 회수용 읽기 정책(policy-s3-results-read.json)은 여기서 안 붙인다 — 그건 인스턴스 역할이 아니라
# "운영자 CLI 사용자"(사람)에게 붙이는 것이라 README §IAM 그대로 손으로 처리한다.
# 사람 IAM 사용자 정책을 테라폼이 대신 바꾸는 건 이 모듈의 범위를 넘는 blast radius 라고 판단했다.
