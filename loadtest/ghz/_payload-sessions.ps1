<#
  페이로드가 쓰는 세션 id 를 **페이로드에서 직접** 읽는다 (#166).

  왜 파일 이름이나 상수가 아닌가: 리셋 대상·프리플라이트 대상·실제 요청이 가는 곳,
  이 셋이 갈리면 rig 가 조용히 거짓말을 한다. 리셋이 엉뚱한 세션을 지우면 «동일 상태»
  전제가 깨지고, 그때 나오는 처리량 저하는 원인을 못 찾는다. #166 이 정확히 그 사고였고,
  #167(익스포터 자격증명이 두 곳에서 따로 정해짐)도 같은 모양이다.
  단일 출처는 페이로드 파일 하나뿐이다 — 요청이 실제로 그 값을 쓰기 때문이다.

  gen_batch_multi.py --sessions 를 다른 범위로 재생성해도 따라온다.

  사용:
    . (Join-Path $PSScriptRoot "_payload-sessions.ps1")
    $S = Get-PayloadSessions -DataFile $DataFile
    # $S.Ids  $S.Lo  $S.Hi  $S.Count  $S.SqlIn
#>

function Get-PayloadSessions {
  param([Parameter(Mandatory = $true)][string]$DataFile)

  $path = (Resolve-Path -LiteralPath $DataFile -ErrorAction SilentlyContinue)
  if (-not $path) { throw "페이로드 없음: $DataFile" }

  # 50MB 급 JSON 이라 ConvertFrom-Json 은 PS 5.1 에서 수십 초~분 단위다. 필요한 건 정수 하나뿐이라
  # 정규식으로 훑는다 (51.6MB / 약 1.4초 실측, 2026-08-12).
  $raw = [IO.File]::ReadAllText($path)
  $ids = [regex]::Matches($raw, '"sessionId"\s*:\s*(\d+)') | ForEach-Object { [int]$_.Groups[1].Value }
  if (-not $ids -or $ids.Count -eq 0) { throw "$DataFile 에서 sessionId 를 못 찾았다 — 페이로드 형식 확인 필요" }

  $uniq = $ids | Sort-Object -Unique
  [PSCustomObject]@{
    Ids   = $uniq
    Lo    = $uniq[0]
    Hi    = $uniq[-1]
    Count = $uniq.Count
    # IN 목록으로 낸다. 범위(BETWEEN)가 아니라 실제 id 집합이라 페이로드가 듬성듬성해도 정확하다.
    SqlIn = ($uniq -join ",")
  }
}

<#
  판 시작 전 세션 존재 확인. 없으면 ghz 는 판을 «완주» 하고 count 는 찬 채 OK 0 인 결과를
  남긴다 — 숫자가 나오므로 실패로 안 보인다. 그 판을 아예 시작하지 않는다.
  $true = 통과.
#>
function Test-SessionsSeeded {
  param([Parameter(Mandatory = $true)]$Sessions, [string]$SeedHint = "..\seed\seed-multi-sessions.sql")

  # mysql 클라이언트가 stderr 로 내는 경고(World-writable config file ...)를 PowerShell 5.1 이
  # NativeCommandError 로 승격시켜 호출부의 ErrorActionPreference=Stop 을 밟는다. 2>$null 로도
  # 안 막힌다(리다이렉트해도 ErrorRecord 로 감싼다). 이 호출 동안만 낮춘다.
  $prev = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  $have = (docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit -N -e `
    "SELECT COUNT(*) FROM exercise_sessions WHERE id IN ($($Sessions.SqlIn));" 2>$null | Select-Object -Last 1)
  $ErrorActionPreference = $prev

  # «세션이 없다» 와 «물어보지도 못했다» 는 다른 사건이다. 구분하지 않으면 docker 데몬이
  # 내려간 상황에서 «시드를 적용하세요» 라고 안내하게 되고, 시드를 아무리 넣어도 안 고쳐진다.
  # (2026-08-12 에 실제로 그렇게 나왔다 — Docker Desktop 이 죽어 있었다.)
  if ("$have".Trim() -notmatch '^\d+$') {
    Write-Host "[preflight] 세션 수를 못 물어봤습니다 — MySQL 컨테이너에 질의가 실패했습니다." -ForegroundColor Red
    Write-Host "            docker 데몬과 shadowfit-mysql 컨테이너 상태를 먼저 확인하세요:" -ForegroundColor Red
    Write-Host "            docker ps --filter name=shadowfit-mysql" -ForegroundColor Red
    Write-Host "            (이건 «시드가 없다» 가 아닙니다. 시드를 넣어도 안 고쳐집니다.)" -ForegroundColor Red
    return $false
  }

  if ("$have".Trim() -eq "$($Sessions.Count)") {
    Write-Host "[preflight] 세션 $($Sessions.Lo)~$($Sessions.Hi) = $have/$($Sessions.Count) ✅" -ForegroundColor DarkGray
    return $true
  }
  Write-Host "[preflight] 실패 — 페이로드가 쓰는 세션 $($Sessions.Count)개 중 $have 개만 존재합니다 (범위 $($Sessions.Lo)~$($Sessions.Hi))." -ForegroundColor Red
  Write-Host "            시드를 먼저 적용하세요:" -ForegroundColor Red
  Write-Host "            docker exec -i shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit < $SeedHint" -ForegroundColor Red
  return $false
}

<# 페이로드가 쓰는 세션들의 누적 pose_data 삭제 (세션 row 자체는 보존). 남은 행 수를 돌려준다. #>
function Reset-PayloadRows {
  param([Parameter(Mandatory = $true)]$Sessions)

  $prev = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit -e `
    "DELETE FROM pose_data WHERE session_id IN ($($Sessions.SqlIn));" 2>$null | Out-Null
  $cnt = (docker exec shadowfit-mysql mysql -ushadowfit -pshadowfit shadowfit -N -e `
    "SELECT COUNT(*) FROM pose_data WHERE session_id IN ($($Sessions.SqlIn));" 2>$null | Select-Object -Last 1)
  $ErrorActionPreference = $prev
  return "$cnt".Trim()
}
