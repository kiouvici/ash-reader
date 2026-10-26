# SPDX-License-Identifier: AGPL-3.0-or-later
# 작업 09 정식 서명 잠금: GitHub Environment 'release' 에 정식 키 시크릿 4개를 넣는다.
# 대표님이 직접 실행한다(대표님 결정 2026-10-26 21:03·21:07). Claude 는 실행하지 않는다.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File "C:\Users\Ahn SH\dev\ash-reader\scripts\set-release-secrets.ps1"
#
# - ASH_KEYSTORE_BASE64 = 키 파일(.ash-keys\ash-reader-release.jks)을 base64 로
# - ASH_KEYSTORE_PASSWORD·ASH_KEY_PASSWORD = scripts\local-build.ps1 이 Windows DPAPI 로 잠가 둔 비밀번호를 풀어서
# - ASH_KEY_ALIAS = 저장해 둔 별칭(비밀 아님)
# 값은 gh 에 표준입력으로만 넘긴다(명령 줄·화면·로그·파일에 남기지 않음). 이미 있는 값은 덮어쓴다.
# 끝으로 비밀번호 백업: 스토어·키 비밀번호를 화면에 보이지 않게 클립보드에 하나씩 복사한다(클립보드 기록·클라우드 동기화 제외).
# 비밀번호 관리 앱에 붙여 넣고 Enter → 그때(또는 30초 뒤) 클립보드를 지운다.
param(
    [string]$Repo = 'kiouvici/ash-reader',
    [string]$KeystoreFile = (Join-Path $env:USERPROFILE '.ash-keys\ash-reader-release.jks')
)
$ErrorActionPreference = 'Stop'
$vault = Join-Path $env:LOCALAPPDATA 'AshReader\signing'

function Get-Saved([string]$name) {
    $f = Join-Path $vault "$name.dpapi"
    if (-not (Test-Path $f)) { return $null }
    $ss = (Get-Content -Raw $f).Trim() | ConvertTo-SecureString
    $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($ss)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }
}

# 외부 명령을 출력 없이 돌리고 종료 코드만 돌려준다(5.1 은 오류 출력을 버릴 때 Stop 이면 멈추므로 잠시 Continue)
function Invoke-Quiet([string]$exe, [string[]]$argv) {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & $exe @argv 2>&1 | Out-Null; $LASTEXITCODE } finally { $ErrorActionPreference = $old }
}

# --- 준비물 확인 ---
$gh = (Get-Command gh -ErrorAction SilentlyContinue).Source
if (-not $gh) { $gh = Join-Path $env:ProgramFiles 'GitHub CLI\gh.exe' }
if (-not (Test-Path $gh)) { throw 'gh(GitHub CLI)가 없습니다.' }
if ((Invoke-Quiet $gh @('auth', 'status')) -ne 0) { throw 'gh 로그인이 필요합니다: gh auth login' }
if ((Invoke-Quiet $gh @('api', "repos/$Repo/environments/release")) -ne 0) { throw "GitHub 에 Environment 'release' 가 없습니다. Settings › Environments 에서 먼저 만들어 주세요." }
if (-not (Test-Path $KeystoreFile)) { throw "키 파일이 없습니다: $KeystoreFile" }

$aliasFile = Join-Path $vault 'release.alias'
$alias = if (Test-Path $aliasFile) { (Get-Content -Raw $aliasFile).Trim() } else { $null }
$storePw = Get-Saved 'release-store'
$keyPw = Get-Saved 'release-key'
if (-not $alias -or -not $storePw) { throw '저장해 둔 정식 키 별칭·비밀번호가 없습니다. 먼저 scripts\local-build.ps1 로 한 번 빌드해 주세요(처음 한 번 비밀번호를 물음).' }
if (-not $keyPw) { $keyPw = $storePw }

# 비밀번호·별칭이 키 파일과 맞는지 keytool 로 먼저 확인(비밀번호는 환경변수로만 넘김)
$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\keytool.exe' } else { $null }
if ($keytool -and (Test-Path $keytool)) {
    $env:ASH_SET_SECRET_TMP = $storePw
    try {
        if ((Invoke-Quiet $keytool @('-list', '-keystore', $KeystoreFile, '-storepass:env', 'ASH_SET_SECRET_TMP', '-alias', $alias)) -ne 0) { throw '저장해 둔 비밀번호 또는 별칭이 키 파일과 맞지 않습니다. local-build.ps1 -Forget 뒤 다시 빌드해 비밀번호를 새로 저장해 주세요.' }
    } finally { Remove-Item Env:ASH_SET_SECRET_TMP -ErrorAction SilentlyContinue }
    Write-Host '정식 키 비밀번호·별칭 확인됨'
}

# --- 시크릿 넣기: 값은 표준입력으로만(줄바꿈 없이 UTF-8 바이트 그대로) ---
function Set-ReleaseSecret([string]$name, [string]$value) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $gh
    $psi.Arguments = "secret set $name --env release --repo $Repo"
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    $bytes = [Text.Encoding]::UTF8.GetBytes($value)
    # 넘긴 값 확인(값은 보이지 않고 글자 수·SHA-256 앞 8자리만) — 같은 값을 다시 넣으면 같은 숫자가 나온다
    $sha = [Security.Cryptography.SHA256]::Create()
    $h = ([BitConverter]::ToString($sha.ComputeHash($bytes)) -replace '-', '').Substring(0, 8).ToLower()
    $p.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
    $p.StandardInput.Close()
    $null = $p.StandardOutput.ReadToEnd()
    $err = $p.StandardError.ReadToEnd()
    $p.WaitForExit()
    if ($p.ExitCode -ne 0) { throw "$name 넣기 실패: $err" }
    Write-Host ("  {0} 넣음 — 글자 수 {1}, SHA-256 앞자리 {2}" -f $name, $value.Length, $h)
}

Write-Host "Environment 'release' 에 정식 키 시크릿 4개를 넣습니다($Repo)."
Set-ReleaseSecret 'ASH_KEYSTORE_BASE64' ([Convert]::ToBase64String([IO.File]::ReadAllBytes($KeystoreFile)))
Set-ReleaseSecret 'ASH_KEYSTORE_PASSWORD' $storePw
Set-ReleaseSecret 'ASH_KEY_ALIAS' $alias
Set-ReleaseSecret 'ASH_KEY_PASSWORD' $keyPw
Write-Host ''
Write-Host "Environment 'release' 시크릿 목록(이름만):"
& $gh secret list --env release --repo $Repo

# --- 비밀번호 백업(대표님 결정 21:07): 화면 출력 없이 클립보드로 하나씩 ---
# 클립보드는 STA 스레드에서만 된다(powershell.exe -File 은 기본 STA)
if ([Threading.Thread]::CurrentThread.GetApartmentState() -ne 'STA') { throw '클립보드를 쓰려면 STA 가 필요합니다: powershell -STA -File ... 로 다시 실행해 주세요.' }
Add-Type -AssemblyName System.Windows.Forms
function Copy-Private([string]$text) {
    $d = New-Object System.Windows.Forms.DataObject
    $d.SetData([System.Windows.Forms.DataFormats]::UnicodeText, $true, $text)
    # Windows 클립보드 기록(Win+V)·클라우드 클립보드에 남지 않게
    $d.SetData('ExcludeClipboardContentFromMonitorProcessing', (New-Object IO.MemoryStream (, [byte[]](1, 0, 0, 0))))
    $d.SetData('CanIncludeInClipboardHistory', (New-Object IO.MemoryStream (, [byte[]](0, 0, 0, 0))))
    $d.SetData('CanUploadToCloudClipboard', (New-Object IO.MemoryStream (, [byte[]](0, 0, 0, 0))))
    [System.Windows.Forms.Clipboard]::SetDataObject($d, $true)
    # 들어간 값이 맞는지(값은 보이지 않고 같은지만) 확인
    $back = [System.Windows.Forms.Clipboard]::GetText()
    if ($back -cne $text) { throw "클립보드에 다른 값이 들어갔습니다(글자 수 $($back.Length), 기대 $($text.Length)). 클립보드를 지우고 멈춥니다." }
}
function Wait-EnterOr30s {
    $end = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $end) {
        if ([Console]::KeyAvailable -and [Console]::ReadKey($true).Key -eq 'Enter') { return }
        Start-Sleep -Milliseconds 200
    }
    Write-Host '  (30초가 지나 클립보드를 지웁니다)'
}
# 항목 목록은 List 로 만든다 — @(@(…)) 한 개짜리는 PowerShell 이 풀어 버려 글자 하나('5')만 복사되던 버그(대표님 21:20)
$items = New-Object 'System.Collections.Generic.List[object]'
if ($storePw -ceq $keyPw) {
    $items.Add([pscustomobject]@{ Label = '스토어·키 비밀번호(둘이 같음)를'; Value = $storePw })
} else {
    $items.Add([pscustomobject]@{ Label = '스토어 비밀번호를'; Value = $storePw })
    $items.Add([pscustomobject]@{ Label = '키 비밀번호를'; Value = $keyPw })
}
Write-Host ''
Write-Host "비밀번호 백업 — 키 별칭: $alias (비밀 아님)"
try {
    for ($i = 0; $i -lt $items.Count; $i++) {
        Copy-Private $items[$i].Value
        Write-Host ("[{0}/{1}] {2} 복사했습니다(글자 수 {3}). 비밀번호 관리 앱에 붙여 넣고 Enter" -f ($i + 1), $items.Count, $items[$i].Label, $items[$i].Value.Length)
        Wait-EnterOr30s
        [System.Windows.Forms.Clipboard]::Clear()
    }
} finally {
    [System.Windows.Forms.Clipboard]::Clear()
    $storePw = $null; $keyPw = $null; $items = $null
}
Write-Host '클립보드를 지웠습니다. 끝났습니다 — 대화창에 "시크릿 넣음"이라고 알려 주세요.'
