<#
  ② 백엔드 격리 부하 테스트 (load-test-strategy.md §3.2) — SavePoseDataBatch gRPC.
  MediaPipe 를 건너뛰고 Spring+MySQL 적재 경로만 ghz 로 풀스로틀.

  사전조건 (README.md 참조):
    - 백엔드 gRPC 가 :6565 에서 reflection 켜진 채 떠 있음 (application.yml grpc.server.reflection-enabled: true)
    - $env:INTERNAL_API_TOKEN 설정 (서버와 동일 값)
    - 세션 row 존재 — batch_multi.json 이 쓰는 901~1900. 없으면 전 요청이 SESSION_NOT_FOUND 다:
        docker exec -i shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit < ..\seed\seed-multi-sessions.sql
      (-DataFile batch.json 으로 단일 핫세션을 재현할 때는 dev-seed 의 더미 801 이면 된다)
    - ghz 설치 (README §설치)

  사용:
    $env:INTERNAL_API_TOKEN = "<server-token>"
    .\run-save-pose-batch.ps1 -Mode smoke      # 경로·인증 검증 (5 call)
    .\run-save-pose-batch.ps1 -Mode baseline   # 순차 1건 — batch 1건 지연 분해
    .\run-save-pose-batch.ps1 -Mode ramp       # 동시성 step ramp — throughput 천장 + p99

  기본 페이로드는 다세션이다 (#166). 단일 session 801 로 재면 모든 INSERT 가 같은 인덱스
  리프로 몰려 «가짜 천장» 이 나온다 — 4차 실측에서 같은 조건 페이로드만 바꿔 220.4 → 649.4 RPS.
  그 조건을 일부러 재현하려면 -DataFile batch.json.
#>
param(
  [ValidateSet("smoke", "baseline", "ramp")]
  [string]$Mode = "smoke",
  [string]$Target = "localhost:6565",
  [string]$DataFile = "batch_multi.json"
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

if (-not $env:INTERNAL_API_TOKEN) {
  Write-Error "INTERNAL_API_TOKEN 미설정. `$env:INTERNAL_API_TOKEN = '<server-token>' 후 재실행."
  exit 1
}
if (-not (Get-Command ghz -ErrorAction SilentlyContinue)) {
  Write-Error "ghz 미설치. README.md §설치 참조 (scoop install ghz / go install)."
  exit 1
}
if (-not (Test-Path $DataFile)) {
  Write-Error "$DataFile 없음. gen_batch_multi.py 또는 README 의 생성 블록으로 생성."
  exit 1
}

# 프리플라이트 — 페이로드가 쓰는 세션이 DB 에 있나. 없으면 ghz 는 판을 정상 완주하고
# «count 는 찼는데 OK 가 0» 인 결과 JSON 을 남긴다. 그건 측정이 아니라 거절 처리 벤치마크다.
$SessionLo = 901; $SessionHi = 1900
if ($DataFile -eq "batch.json") { $SessionLo = 801; $SessionHi = 801 }
$expected = $SessionHi - $SessionLo + 1
$have = (docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit -N -e `
  "SELECT COUNT(*) FROM exercise_sessions WHERE id BETWEEN $SessionLo AND $SessionHi;" 2>$null | Select-Object -Last 1)
if ("$have".Trim() -ne "$expected") {
  Write-Error ("$DataFile 이 쓰는 세션 $SessionLo~$SessionHi 중 $have/$expected 개만 존재. 시드 먼저:`n" +
    "  docker exec -i shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit < ..\seed\seed-multi-sessions.sql")
  exit 1
}

$results = Join-Path $here "results"
if (-not (Test-Path $results)) { New-Item -ItemType Directory -Path $results | Out-Null }

# 메타데이터는 파일로 전달 — Win PowerShell 5.1 이 native exe 로 JSON 인라인 인자의
# 따옴표를 벗겨먹어 ghz 가 파싱 실패하므로 (--metadata-file 로 우회). 토큰 포함이라 results/(gitignore).
$metaFile = Join-Path $results "metadata.json"
$metadata = '{"authorization":"Bearer ' + $env:INTERNAL_API_TOKEN + '"}'
[System.IO.File]::WriteAllText($metaFile, $metadata, (New-Object System.Text.UTF8Encoding($false)))
$call = "ExerciseService.SavePoseDataBatch"
$common = @(
  "--insecure",
  "--call", $call,
  "--metadata-file", $metaFile,
  "--data-file", $DataFile
)

switch ($Mode) {
  "smoke" {
    Write-Host "[smoke] 경로·인증 검증 — 5 call, c=1" -ForegroundColor Cyan
    ghz @common -n 5 -c 1 $Target
  }
  "baseline" {
    Write-Host "[baseline] 순차 — 200 call, c=1 (batch 1건 지연 p50/95/99). 동시성 1 이라 페이로드 분산과 무관" -ForegroundColor Cyan
    ghz @common -n 200 -c 1 -O html -o "$results\baseline.html" $Target
    Write-Host "리포트: $results\baseline.html" -ForegroundColor Green
  }
  "ramp" {
    Write-Host "[ramp] 동시성 step 5->100 (10s/step) — throughput 천장 + 콜백 p99" -ForegroundColor Cyan
    ghz @common `
      --concurrency-schedule=step `
      --concurrency-start=5 --concurrency-step=5 --concurrency-end=100 `
      --concurrency-step-duration=10s `
      -z 210s `
      -O html -o "$results\ramp.html" $Target
    Write-Host "리포트: $results\ramp.html" -ForegroundColor Green
    Write-Host "→ throughput 가 평탄해지는 동시성 = 백엔드 천장. 그 지점 p99 를 SLO 와 비교 (doc §11)." -ForegroundColor Yellow
  }
}
