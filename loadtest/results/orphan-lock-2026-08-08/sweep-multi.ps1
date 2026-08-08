<#
  #87 수정안 ㄱ(FOR UPDATE) 비용 측정 — 다중 세션 판.

  기존 rebuild-and-measure.ps1 을 그대로 못 쓰는 이유는 리셋 범위 하나다. 그 스크립트는
  session 801 만 지우는데, 이 판은 batch_multi.json 이 901~1900 을 돌아가며 때린다.
  나머지 절차(재빌드 → gRPC 대기 → 리셋 → 60s warmup → 리셋 → 210s ramp)는 동일하게 맞춘다.

  단일 세션 판(ramp-lock-before/after)과의 차이는 **데이터 파일 하나**다. 그 판은 전 요청이
  같은 행을 잠가 경합을 최대로 만들었고(상한), 이 판은 요청마다 다른 세션 행이라 실운영에
  가깝다.

  ⚠️ warmup 뒤에 리셋을 한 번 더 하는 이유: 단일 세션 판 after 측정에서 warmup 의 in-flight
     요청이 DELETE 뒤에 착지해 본측정 시작 시 30 행이 남아 있었다. 같은 흠결을 반복하지 않는다.

  사용: .\sweep-multi.ps1 -Label multi-after
#>
param(
  [Parameter(Mandatory = $true)][string]$Label,
  [int]$WarmupSec = 60
)
$ErrorActionPreference = "Continue"
$root = "E:\init"
$ghzDir = Join-Path $root "loadtest\ghz"
$ghz = Join-Path $root "loadtest\.bin\ghz.exe"
$results = Join-Path $ghzDir "results"
$metaFile = Join-Path $results "metadata.json"
$dataFile = Join-Path $ghzDir "batch_multi.json"

function Reset-Multi {
  docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit -e "DELETE FROM pose_data WHERE session_id BETWEEN 901 AND 1900;" 2>$null | Out-Null
  $c = (docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit -N -e "SELECT COUNT(*) FROM pose_data WHERE session_id BETWEEN 901 AND 1900;" 2>$null | Select-Object -Last 1)
  Write-Host "[$Label] 리셋 후 행: $c"
  return "$c".Trim()
}

$oldStart = (docker inspect -f '{{.State.StartedAt}}' shadowfit-backend 2>$null | Select-Object -Last 1)
Write-Host "[$Label] 재빌드 (기존 StartedAt=$oldStart)"
Push-Location $root
docker compose up -d --build shadowfit-backend 2>&1 | Select-Object -Last 3
Pop-Location

$elapsed = 0
do {
  Start-Sleep -Seconds 5; $elapsed += 5
  $now = (docker inspect -f '{{.State.StartedAt}}' shadowfit-backend 2>$null | Select-Object -Last 1)
  if ($elapsed -ge 900) { Write-Error "[$Label] 컨테이너 교체 타임아웃"; exit 1 }
} while (-not $now -or $now -eq $oldStart)
Write-Host "[$Label] 새 컨테이너: $now (대기 ${elapsed}s)"

[System.IO.File]::WriteAllText($metaFile, ('{"authorization":"Bearer ' + $env:INTERNAL_API_TOKEN + '"}'), (New-Object System.Text.UTF8Encoding($false)))

Set-Location $ghzDir
$ready = $false; $w = 0
while (-not $ready -and $w -lt 300) {
  Start-Sleep -Seconds 5; $w += 5
  & $ghz --insecure --call ExerciseService.SavePoseDataBatch --metadata-file $metaFile --data-file "batch.json" -n 1 -c 1 localhost:6565 *> $null
  if ($LASTEXITCODE -eq 0) { $ready = $true }
}
if (-not $ready) { Write-Error "[$Label] gRPC 기동 안 됨"; exit 1 }
Write-Host "[$Label] gRPC ready (대기 ${w}s)"

Reset-Multi | Out-Null

Write-Host "[$Label] warmup ${WarmupSec}s (c=20, 결과 폐기)..."
& $ghz --insecure --call ExerciseService.SavePoseDataBatch `
  --metadata-file $metaFile --data-file $dataFile -c 20 -z "${WarmupSec}s" localhost:6565 *> $null

Start-Sleep -Seconds 5      # in-flight 착지 대기 — 리셋이 헛돌지 않게
$left = Reset-Multi
if ($left -ne "0") { Write-Error "[$Label] 본측정 전 행이 0 이 아니다 ($left)"; exit 1 }

$out = Join-Path $results "ramp-$Label.json"
Write-Host "[$Label] ramp 측정 시작 (210s)..."
& $ghz --insecure --call ExerciseService.SavePoseDataBatch `
  --metadata-file $metaFile --data-file $dataFile `
  --concurrency-schedule=step --concurrency-start=5 --concurrency-step=5 --concurrency-end=100 `
  --concurrency-step-duration=10s -z 210s `
  -O json -o $out localhost:6565
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $out)) { Write-Error "[$Label] ghz 실패 (exit=$LASTEXITCODE)"; exit 1 }

$j = Get-Content $out -Raw | ConvertFrom-Json
function P($pc) { ($j.latencyDistribution | Where-Object { $_.percentage -eq $pc }).latency / 1e6 }
$err = 0
if ($j.errorDistribution) { $j.errorDistribution.PSObject.Properties | ForEach-Object { $err += $_.Value } }
"==== $Label ===="
"count   : {0}" -f $j.count
"RPS     : {0:N1}" -f $j.rps
"p50     : {0:N0} ms" -f (P 50)
"p90     : {0:N0} ms" -f (P 90)
"p99     : {0:N0} ms" -f (P 99)
"OK      : {0} / {1} ({2:N2}%)" -f $j.statusCodeDistribution.OK, $j.count, (100.0 * $j.statusCodeDistribution.OK / $j.count)
"errors  : {0}" -f $err
if ($err -gt 0) { $j.statusCodeDistribution.PSObject.Properties | Where-Object { $_.Name -ne 'OK' } | ForEach-Object { "  - {0}: {1}" -f $_.Name, $_.Value } }
