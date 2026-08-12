<#
  3-way before/after 측정 하네스 (load-test-strategy.md §7.5).
  매 측정마다 동일 상태로 리셋 → ramp(JSON) → 요약 파싱. 공정 비교용.

  사용:
    $env:INTERNAL_API_TOKEN = "<token>"
    .\measure.ps1 -Label before          # ramp 측정 + results\ramp-before.json
    .\measure.ps1 -Label config          # 설정만 적용 후
    .\measure.ps1 -Label jdbc            # JdbcTemplate 적용 후
    .\measure.ps1 -Label before -Summarize   # 측정 없이 기존 결과 재요약

  전제: 백엔드 :6565, mysql 컨테이너 shadowfit-mysql, batch_multi.json(R=25, session 901~1900),
        그리고 그 세션들이 DB 에 존재할 것 — ..\seed\seed-multi-sessions.sql (프리플라이트가 확인한다).

  기본 페이로드가 다세션인 이유 (#166): 전 요청이 session 801 하나로 가면 모든 INSERT 가 같은
  인덱스 리프로 몰려 커밋이 직렬화되고, 그때 나오는 천장은 시스템의 천장이 아니라 그 경합의
  천장이다. 3차(2026-08-08)의 «천장 = 커밋 fsync» 결론이 그 위에서 나왔고, 4차에서 같은 조건
  같은 코드로 페이로드만 바꾸니 220.4 → 649.4 RPS 였다.
  단일 핫세션을 **일부러** 재현하려면 -DataFile batch.json — 이제 버그가 아니라 조건이다.
#>
param(
  [Parameter(Mandatory = $true)][string]$Label,
  [string]$Target = "localhost:6565",
  [string]$DataFile = "batch_multi.json",
  [switch]$Summarize,          # 측정 건너뛰고 기존 json 만 요약
  [switch]$SkipReset,          # 누적 행 리셋 건너뛰기
  [switch]$SkipPreflight,      # 세션 존재 확인 건너뛰기 (원격 DB 등 docker exec 이 안 되는 환경)
  [int]$WarmupSec = 0          # 본측정 전 warmup 부하 시간(초). 0 이면 생략. 공정비교용 JVM/풀 워밍업
)

# 페이로드가 쓰는 세션 범위. 리셋·프리플라이트가 같은 값을 봐야 한다 — 갈리면 리셋이 아무것도
# 안 지우고도 조용히 성공하고, 판이 거듭될수록 행이 누적된다(«동일 상태 리셋» 전제가 깨진다).
$SessionLo = 901
$SessionHi = 1900
if ($DataFile -eq "batch.json") { $SessionLo = 801; $SessionHi = 801 }

# native exe(mysql/docker/ghz) 가 stderr 로 경고를 내면 PowerShell 5.1 이 NativeCommandError 로
# 승격시켜 스크립트를 죽인다(World-writable config 경고 등). 측정 스크립트라 Continue 로 두고
# 핵심 실패는 $LASTEXITCODE 로 명시 체크한다.
$ErrorActionPreference = "Continue"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here
$results = Join-Path $here "results"
if (-not (Test-Path $results)) { New-Item -ItemType Directory -Path $results | Out-Null }
$out = Join-Path $results "ramp-$Label.json"

function Show-Summary($path, $label) {
  $j = Get-Content $path -Raw | ConvertFrom-Json
  $err = 0
  if ($j.errorDistribution) { $j.errorDistribution.PSObject.Properties | ForEach-Object { $err += $_.Value } }
  $okPct = if ($j.count) { 100.0 * $j.statusCodeDistribution.OK / $j.count } else { 0 }
  function P($pc) { ($j.latencyDistribution | Where-Object { $_.percentage -eq $pc }).latency / 1e6 }
  "==== $label ===="
  "count   : {0}" -f $j.count
  "RPS     : {0:N1}" -f $j.rps
  "p50     : {0:N0} ms" -f (P 50)
  "p90     : {0:N0} ms" -f (P 90)
  "p95     : {0:N0} ms" -f (P 95)
  "p99     : {0:N0} ms" -f (P 99)
  "avg     : {0:N0} ms" -f ($j.average / 1e6)
  "slowest : {0:N0} ms" -f ($j.slowest / 1e6)
  "OK      : {0} / {1} ({2:N2}%)" -f $j.statusCodeDistribution.OK, $j.count, $okPct
  "errors  : {0}" -f $err
  if ($err -gt 0) { $j.statusCodeDistribution.PSObject.Properties | Where-Object { $_.Name -ne 'OK' } | ForEach-Object { "  - {0}: {1}" -f $_.Name, $_.Value } }
  ""
}

if ($Summarize) { Show-Summary $out "$Label (재요약)"; return }

if (-not $env:INTERNAL_API_TOKEN) { Write-Error "INTERNAL_API_TOKEN 미설정."; exit 1 }

# ghz 경로 (.bin 우선)
$ghz = "ghz"
if (-not (Get-Command ghz -ErrorAction SilentlyContinue)) {
  $bin = Join-Path (Split-Path -Parent $here) ".bin\ghz.exe"
  if (Test-Path $bin) { $ghz = $bin } else { Write-Error "ghz 없음"; exit 1 }
}

# 메타데이터 파일
$metaFile = Join-Path $results "metadata.json"
[System.IO.File]::WriteAllText($metaFile, ('{"authorization":"Bearer ' + $env:INTERNAL_API_TOKEN + '"}'), (New-Object System.Text.UTF8Encoding($false)))

# 0) 프리플라이트 — 페이로드가 쓰는 세션이 DB 에 있나.
# 없으면 ghz 는 210초 동안 전 요청이 SESSION_NOT_FOUND 로 거절되는 판을 «완주»하고, 결과 JSON 은
# count 가 채워진 채 OK 0 으로 남는다. 숫자가 나오므로 «측정은 됐는데 성능이 이상하다» 로 읽힌다.
# 30초 쓰고 쓰레기를 얻는 실패를 1초 만에 이유를 말하는 실패로 바꾼다.
$expected = $SessionHi - $SessionLo + 1
if (-not $SkipPreflight) {
  $have = (docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit -N -e `
    "SELECT COUNT(*) FROM exercise_sessions WHERE id BETWEEN $SessionLo AND $SessionHi;" 2>$null | Select-Object -Last 1)
  if ("$have".Trim() -ne "$expected") {
    Write-Host "[preflight] 실패 — $DataFile 이 쓰는 세션 $SessionLo~$SessionHi 중 $have/$expected 개만 존재합니다." -ForegroundColor Red
    Write-Host "            시드를 먼저 적용하세요:" -ForegroundColor Red
    Write-Host "            docker exec -i shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit < ..\seed\seed-multi-sessions.sql" -ForegroundColor Red
    exit 1
  }
  Write-Host "[preflight] 세션 $SessionLo~$SessionHi = $have/$expected ✅" -ForegroundColor DarkGray
}

# 1) 동일 상태 리셋 — 페이로드가 쓰는 세션들의 누적 행 삭제 (세션 row 자체는 보존)
if (-not $SkipReset) {
  Write-Host "[reset] DELETE pose_data WHERE session_id BETWEEN $SessionLo AND $SessionHi ..." -ForegroundColor Yellow
  docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit -e "DELETE FROM pose_data WHERE session_id BETWEEN $SessionLo AND $SessionHi;" 2>$null | Out-Null
  $cnt = (docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit -N -e "SELECT COUNT(*) FROM pose_data WHERE session_id BETWEEN $SessionLo AND $SessionHi;" 2>$null | Select-Object -Last 1)
  Write-Host "[reset] 남은 행: $cnt" -ForegroundColor Yellow
}

# 1.5) warmup — JVM JIT·커넥션풀 워밍업 (공정비교: cold/warm 차이 제거). 결과는 버림.
if ($WarmupSec -gt 0) {
  Write-Host "[$Label] warmup ${WarmupSec}s (c=20, 결과 폐기)..." -ForegroundColor DarkYellow
  & $ghz --insecure --call ExerciseService.SavePoseDataBatch `
    --metadata-file $metaFile --data-file $DataFile `
    -c 20 -z "${WarmupSec}s" $Target *> $null
  # warmup 이 적재한 행 제거 → 본측정 클린 상태 보장
  docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit -e "DELETE FROM pose_data WHERE session_id BETWEEN $SessionLo AND $SessionHi;" 2>$null | Out-Null
  $wc = (docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit -N -e "SELECT COUNT(*) FROM pose_data WHERE session_id BETWEEN $SessionLo AND $SessionHi;" 2>$null | Select-Object -Last 1)
  Write-Host "[$Label] warmup 완료, 본측정 전 행: $wc" -ForegroundColor DarkYellow
}

# 2) ramp (baseline 과 동일 파라미터: 동시성 5→100 step, 210s)
Write-Host "[$Label] ramp 측정 시작 (210s)..." -ForegroundColor Cyan
& $ghz --insecure --call ExerciseService.SavePoseDataBatch `
  --metadata-file $metaFile --data-file $DataFile `
  --concurrency-schedule=step --concurrency-start=5 --concurrency-step=5 --concurrency-end=100 `
  --concurrency-step-duration=10s -z 210s `
  -O json -o $out $Target
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $out)) { Write-Error "ghz 실패 (exit=$LASTEXITCODE)"; exit 1 }
Write-Host "[$Label] 완료 → $out" -ForegroundColor Green
""

# 3) 요약
Show-Summary $out $Label
