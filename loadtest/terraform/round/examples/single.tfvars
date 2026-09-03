# P1(무중단 DDL)·P3(백업/복구) 등 1대 라운드. aws/README.md: "4차 실측 관례" = c7i.2xlarge / m6i.xlarge.
round_name = "p1-p3"

vpc_id     = "vpc-xxxxxxxx"
subnet_id  = "subnet-xxxxxxxx"
admin_cidr = "1.2.3.4/32" # 본인 IP/32 로 바꿀 것

key_name               = "measure"
instance_profile_name  = "shadowfit-measure"

internal_ports = [] # 인스턴스가 하나뿐이라 내부 규칙 불필요

instances = [
  { name = "measure", role = "db", instance_type = "c7i.2xlarge" }
]
