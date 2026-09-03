# P6(동거 용량) — aws/README.md 「P6 — 2대 구성」. 대상 c7i.4xlarge + 부하기 c7i.xlarge 이상.
# 🔴 부하기를 c7i.large(2 vCPU)로 내리지 말 것 — #312, 판이 조용히 스킵된다.
round_name = "p6"

vpc_id     = "vpc-xxxxxxxx"
subnet_id  = "subnet-xxxxxxxx" # 두 인스턴스 모두 같은 서브넷 — 사설 IP로 붙는다
admin_cidr = "1.2.3.4/32"

key_name               = "measure"
instance_profile_name  = "shadowfit-measure"

internal_ports = [22, 8000, 8080, 6565] # 부하기 → 대상: SSH·AI HTTP·Spring·gRPC

instances = [
  { name = "target",  role = "p6-target", instance_type = "c7i.4xlarge" },
  { name = "loadgen", role = "p6-loader", instance_type = "c7i.xlarge" },
]

# 적용 후 사람이 할 일 (자동화 범위 밖 — aws/README.md 그대로):
#   1) terraform output private_ips 로 대상 사설 IP 확인
#   2) 부하기에 SSH 키(/root/.ssh/measure.pem) 배치
#   3) 대상·부하기에 AI_PUBLIC_TOKEN·INTERNAL_API_TOKEN 각각 같은 값으로 설정
#   4) 부하기에서 run_all.sh 를 GHZ_RPS 등 라운드별 값과 함께 실행
