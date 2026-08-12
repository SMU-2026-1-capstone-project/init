<#
  천장 특정 (load-test-strategy.md §7.8) — 고정 동시성별 throughput 스윕.
  ramp(step) 집계로는 "어느 동시성에서 throughput 가 평탄해지나"를 못 봄.
  → 같은 warm 컨테이너에서 c 를 고정해 여러 번 측정, RPS 곡선의 평탄점 = 백엔드 천장.

  사용: $env:INTERNAL_API_TOKEN=...; .\ceiling.ps1
  전제: 백엔드 :6565 (jdbc 빌드), mysql shadowfit-mysql, batch_multi.json(R=25, session 901~1900),
        세션 시드 ..\seed\seed-multi-sessions.sql 적용 (프리플라이트가 확인한다).

  ⚠️ 이 스크립트가 재는 것이 «천장» 이므로 페이로드 선택이 결론을 정한다 (#166).
     단일 세션으로 재면 인덱스 리프 경합의 천장을 시스템의 천장으로 발표하게 된다 —
     3차(2026-08-08)가 그렇게 «천장 = 커밋 fsync» 를 냈고, 4차가 페이로드만 바꿔 반증했다.
#>
param(
  [int[]]$Levels = @(1,5,10,20,30,40,50,70,100),
  [int]$ReqPerLevel = 2000,   # -n 고정 요청수 (종료 아티팩트 제거 + 표본 확보)
  [int]$WarmupSec = 60,
  [string]$DataFile = "batch_multi.json",   # 단일 핫세션 재현은 batch.json (의도적 opt-in)
  [switch]$SkipPreflight
)

# 세션 집합은 페이로드에서 직접 읽는다 (measure.ps1 과 같은 규약).
. (Join-Path $PSScriptRoot "_payload-sessions.ps1")
$ErrorActionPreference = "Continue"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent (Split-Path -Parent $here)
Set-Location $here
$results = Join-Path $here "results"
if (-not (Test-Path $results)) { New-Item -ItemType Directory -Path $results | Out-Null }
$ghz = Join-Path $root "loadtest\.bin\ghz.exe"
$metaFile = Join-Path $results "metadata.json"
[System.IO.File]::WriteAllText($metaFile, ('{"authorization":"Bearer ' + $env:INTERNAL_API_TOKEN + '"}'), (New-Object System.Text.UTF8Encoding($false)))

$S = Get-PayloadSessions -DataFile $DataFile
function ResetRows { $null = Reset-PayloadRows -Sessions $S }

# 프리플라이트 — 세션이 없으면 전 요청이 SESSION_NOT_FOUND 로 거절된 채 스윕이 «완주» 하고,
# 곡선이 그려진다. 평탄점이 나오지만 그건 거절 처리의 천장이다. 그 판을 아예 시작하지 않는다.
if (-not $SkipPreflight) {
  if (-not (Test-SessionsSeeded -Sessions $S)) { exit 1 }
}

# warmup (JVM JIT·풀)
if ($WarmupSec -gt 0) {
  Write-Host "[ceiling] warmup ${WarmupSec}s (c=20)..." -ForegroundColor DarkYellow
  & $ghz --insecure --call ExerciseService.SavePoseDataBatch --metadata-file $metaFile --data-file $DataFile -c 20 -z "${WarmupSec}s" "localhost:6565" *> $null
  ResetRows
}

$rows = @()
foreach ($c in $Levels) {
  ResetRows
  $out = Join-Path $results "ceil-c$c.json"
  Write-Host ("[ceiling] c={0} n={1} ..." -f $c, $ReqPerLevel) -ForegroundColor Cyan
  # -n 고정 요청수: ghz 가 정해진 요청을 다 끝내고 종료 → in-flight 강제종료(종료 아티팩트) 없음
  & $ghz --insecure --call ExerciseService.SavePoseDataBatch --metadata-file $metaFile --data-file $DataFile `
    -c $c -n $ReqPerLevel -O json -o $out "localhost:6565"
  if (-not (Test-Path $out)) { Write-Host "  (실패)" -ForegroundColor Red; continue }
  $j = Get-Content $out -Raw | ConvertFrom-Json
  function P($pc) { ($j.latencyDistribution | Where-Object { $_.percentage -eq $pc }).latency / 1e6 }
  $okErr = $j.count - $j.statusCodeDistribution.OK
  $rows += [PSCustomObject]@{
    c=$c; RPS=[math]::Round($j.rps,1); p50=[math]::Round((P 50)); p95=[math]::Round((P 95)); p99=[math]::Round((P 99)); count=$j.count; err=$okErr
  }
}
ResetRows
""
Write-Host "==== 천장 스윕 결과 (고정 동시성별) ====" -ForegroundColor Green
$rows | Format-Table -AutoSize | Out-String
