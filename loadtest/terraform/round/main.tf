# 라운드마다 뜨고 죽는 것 — bootstrap/ state 와 분리해서, 라운드가 끝나 여기를 destroy 해도
# S3 버킷·IAM 은 안 건드린다.
#
# 2026-08-24 사용자 결정("측정 다하면 EC2 자동으로 끄는걸로")에 따라
# instance_initiated_shutdown_behavior 는 항상 terminate 로 박는다 — run_all.sh 의 AUTO_SHUTDOWN
# 가드(S3 업로드 성공 시에만 끈다)가 있어야 안전하다는 전제는 그대로 aws/README.md 를 따른다.

data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-*-x86_64"]
  }
}

resource "aws_security_group" "measure" {
  name        = "shadowfit-measure-${var.round_name}"
  description = "shadowfit loadtest round: ${var.round_name}"
  vpc_id      = var.vpc_id

  tags = {
    Project = "shadowfit-measure"
    Round   = var.round_name
  }
}

resource "aws_security_group_rule" "ssh_admin" {
  type              = "ingress"
  security_group_id = aws_security_group.measure.id
  from_port         = 22
  to_port           = 22
  protocol          = "tcp"
  cidr_blocks       = [var.admin_cidr]
  description       = "operator SSH"
}

# 인스턴스끼리(부하기→대상, 소스↔리플리카)만 여는 포트. var.internal_ports 가 라운드별로 다르다.
resource "aws_security_group_rule" "internal" {
  for_each = toset([for p in var.internal_ports : tostring(p)])

  type              = "ingress"
  security_group_id = aws_security_group.measure.id
  from_port         = tonumber(each.value)
  to_port           = tonumber(each.value)
  protocol          = "tcp"
  self              = true
  description       = "inter-instance (round: ${var.round_name})"
}

resource "aws_security_group_rule" "egress_all" {
  type              = "egress"
  security_group_id = aws_security_group.measure.id
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
  description       = "outbound: S3 upload, package repos, GitHub raw"
}

resource "aws_instance" "measure" {
  for_each = { for inst in var.instances : inst.name => inst }

  ami                    = coalesce(each.value.ami, data.aws_ami.al2023.id)
  instance_type          = each.value.instance_type
  subnet_id              = var.subnet_id
  vpc_security_group_ids = [aws_security_group.measure.id]
  key_name               = var.key_name
  iam_instance_profile   = var.instance_profile_name

  # 2026-08-24 결정 — stop 은 EBS 요금이 계속 나가고 "꺼진 채 잊힌다".
  instance_initiated_shutdown_behavior = "terminate"

  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_size_gb
    delete_on_termination = true

    tags = {
      Project = "shadowfit-measure"
      Round   = var.round_name
    }
  }

  user_data = var.auto_bootstrap ? templatefile("${path.module}/bootstrap-userdata.sh.tftpl", {
    role = each.value.role
    ref  = var.build_ref
  }) : null

  tags = {
    Name    = "shadowfit-measure-${var.round_name}-${each.key}"
    Project = "shadowfit-measure"
    Round   = var.round_name
    Role    = each.value.role
  }
}
