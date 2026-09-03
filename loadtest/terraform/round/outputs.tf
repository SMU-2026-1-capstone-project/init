output "instance_ids" {
  value = { for name, inst in aws_instance.measure : name => inst.id }
}

output "public_ips" {
  value = { for name, inst in aws_instance.measure : name => inst.public_ip }
}

output "private_ips" {
  value       = { for name, inst in aws_instance.measure : name => inst.private_ip }
  description = "다인스턴스 라운드에서 부하기→대상, 소스→리플리카 접속에 쓸 사설 IP."
}

output "security_group_id" {
  value = aws_security_group.measure.id
}
