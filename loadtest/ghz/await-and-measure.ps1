<#
  빌드 완료(컨테이너 교체) 감지 → 기동 대기 → config 측정 자동 실행.
  사용: .\await-and-measure.ps1 -Label config -OldStart "<기존 StartedAt>"
#>
param(
  [Parameter(Mandatory = $true)][string]$Label,
  [Parameter(Mandatory = $true)][string]$OldStart,
  [int]$MaxWaitSec = 1200
)
$ErrorActionPreference = "Continue"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

# 1) 컨테이너 교체 대기 (StartedAt 이 바뀔 때까지)
Write-Host "[await] 컨테이너 교체 대기 (기준 StartedAt=$OldStart)..."
$elapsed = 0
do {
  Start-Sleep -Seconds 5; $elapsed += 5
  $now = (docker inspect -f '{{.State.StartedAt}}' shadowfit-backend 2>$null | Select-Object -Last 1)
  if ($elapsed -ge $MaxWaitSec) { Write-Error "[await] 타임아웃: 컨테이너 교체 안 됨"; exit 1 }
} while (-not $now -or $now -eq $OldStart)
Write-Host "[await] 새 컨테이너 감지: StartedAt=$now (대기 ${elapsed}s)"

# 2) gRPC 기동 대기 — reflection 으로 ExerciseService 응답할 때까지 (smoke 1콜)
$token = $env:INTERNAL_API_TOKEN
$results = Join-Path $here "results"
if (-not (Test-Path $results)) { New-Item -ItemType Directory -Path $results | Out-Null }
$metaFile = Join-Path $results "metadata.json"
[System.IO.File]::WriteAllText($metaFile, ('{"authorization":"Bearer ' + $token + '"}'), (New-Object System.Text.UTF8Encoding($false)))
$ghz = Join-Path (Split-Path -Parent $here) ".bin\ghz.exe"

Write-Host "[await] gRPC 기동 대기 (smoke 폴링)..."
$ready = $false; $w = 0
while (-not $ready -and $w -lt 300) {
  Start-Sleep -Seconds 5; $w += 5
  Set-Location $here
  # 기동 확인용 핑이라 batch.json(단일 801) 을 그대로 쓴다 — 측정이 아니므로 #166 의 «가짜 천장»
  # 과 무관하고, dev-seed 의 801 만 있으면 되어 다세션 시드 적용 전에도 «서버가 떴나» 를 물을 수
  # 있다. 이 핑이 넣는 25행은 아래 measure.ps1 의 리셋 범위 밖이므로 여기서 지운다.
  & $ghz --insecure --call ExerciseService.SavePoseDataBatch --metadata-file $metaFile --data-file "batch.json" -n 1 -c 1 localhost:6565 *> $null
  if ($LASTEXITCODE -eq 0) { $ready = $true }
}
if (-not $ready) { Write-Error "[await] gRPC 기동 안 됨"; exit 1 }
docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit -e "DELETE FROM pose_data WHERE session_id=801;" 2>$null | Out-Null
Write-Host "[await] gRPC ready (대기 ${w}s). 측정 시작."
""

# 3) config 측정 (measure.ps1 재사용: 901~1900 리셋 → 프리플라이트 → ramp → 요약)
& (Join-Path $here "measure.ps1") -Label $Label
