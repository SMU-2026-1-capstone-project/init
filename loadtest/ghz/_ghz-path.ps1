<#
  ghz 실행 파일을 찾는 규칙 — rig 전체가 이 한 곳만 본다 (#194).

  전에는 스크립트마다 달랐다: measure.ps1 은 PATH→.bin, ceiling/await/rebuild 는 .bin 만,
  run-save-pose-batch.* 는 PATH 만. 그래서 어떤 설치법을 따르든 절반이 «미설치» 로 죽었다.
  README 가 안내하는 scoop 설치(=PATH)만 한 사람은 천장 스윕을 못 돌렸고, .bin 에 내려받기만
  한 사람은 러너를 못 돌렸다. .bin/ 은 gitignore 대상이라 clone 만으로는 생기지도 않는다.

  순서는 «.bin 우선 → PATH 폴백» 이다.
  저장소 안에 일부러 받아둔 바이너리가 있으면 그것으로 재는 쪽이 재현성에 맞다 — 판마다
  머신 전역 PATH 사정에 따라 다른 버전이 돌면, 그 차이는 결과에 남지 않고 조용하다.
  measure.ps1 이 원래 «# ghz 경로 (.bin 우선)» 이라고 적어놓고 코드는 PATH 를 먼저 보고 있었다.
  주석 쪽이 옳았으므로 코드를 주석에 맞췄다.

  사용:
    . (Join-Path $PSScriptRoot "_ghz-path.ps1")
    $ghz = Resolve-Ghz          # 못 찾으면 찾아본 자리를 전부 적고 exit 1
#>

function Resolve-Ghz {
  param([switch]$Quiet)

  $binPath = Join-Path (Split-Path -Parent $PSScriptRoot) ".bin\ghz.exe"
  $resolved = $null
  $origin = $null

  if (Test-Path $binPath) {
    $resolved = $binPath
    $origin = "저장소 .bin"
  } else {
    $cmd = Get-Command ghz -ErrorAction SilentlyContinue
    if ($cmd) {
      $resolved = $cmd.Source
      $origin = "PATH"
    }
  }

  if (-not $resolved) {
    # «미설치» 라고 하지 않는다. 설치는 돼 있는데 이 스크립트가 보는 자리에 없는 경우가 더
    # 흔하고, 그때 «재설치하세요» 는 시간을 태운다. 찾아본 자리를 그대로 적는다.
    Write-Host "ghz 를 못 찾았습니다. 찾아본 자리:" -ForegroundColor Red
    Write-Host "  1) $binPath   (없음)" -ForegroundColor Red
    Write-Host "  2) PATH 의 ghz          (없음)" -ForegroundColor Red
    Write-Host "둘 중 하나를 채우세요 — scoop install ghz, 또는 릴리스 바이너리를 위 경로에 두기" -ForegroundColor Red
    Write-Host "  (loadtest/README.md `§ghz 설치`. .bin/ 은 gitignore 대상이라 clone 만으로는 안 생깁니다)" -ForegroundColor Red
    exit 1
  }

  if (-not $Quiet) {
    # 어느 바이너리로 쟀는지를 판 시작 시 남긴다. 결과 파일만 보고 사후에 «그때 뭘로 쟀나» 를
    # 물으면 답이 없다 — .bin 과 PATH 에 버전이 다른 ghz 가 있어도 지금은 조용하다.
    # ghz 는 --version 을 stderr 로 낸다. PowerShell 5.1 이 NativeCommandError 로 승격시켜
    # 호출부의 ErrorActionPreference=Stop 을 밟으므로 이 호출 동안만 낮춘다.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $ver = (& $resolved --version 2>&1 | Select-Object -First 1)
    $ErrorActionPreference = $prev
    Write-Host "[ghz] $origin — $resolved ($ver)" -ForegroundColor DarkGray
  }

  return $resolved
}
