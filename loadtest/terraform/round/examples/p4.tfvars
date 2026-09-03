# P4(복제 지연·반동기) — aws/README.md 「P4 — 2대 구성」. 두 인스턴스는 반드시 같은 타입.
round_name = "p4"

vpc_id     = "vpc-xxxxxxxx"
subnet_id  = "subnet-xxxxxxxx"
admin_cidr = "1.2.3.4/32"

key_name               = "measure"
instance_profile_name  = "shadowfit-measure"

internal_ports = [22, 3306] # 소스 → 리플리카: SSH·MySQL. 리플리카 → 소스도 3306(복제가 이 방향으로 붙는다)

instances = [
  { name = "source",  role = "db", instance_type = "c7i.2xlarge" },
  { name = "replica", role = "db", instance_type = "c7i.2xlarge" },
]

# 🔴 두 인스턴스는 반드시 같은 AZ 에 둘 것(같은 서브넷이면 자동으로 같은 AZ) — 다른 AZ 는
# replication-lag-and-semisync.md 기준 아직 실측 없음. AZ 를 바꾸려면 REPL_AZ_MODE 로 라벨을 남길 것.
