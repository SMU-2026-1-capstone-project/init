output "bucket_name" {
  value = var.bucket_name
}

output "instance_profile_name" {
  value = aws_iam_instance_profile.measure.name
}

output "role_arn" {
  value = aws_iam_role.measure.arn
}
