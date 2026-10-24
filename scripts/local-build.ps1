# SPDX-License-Identifier: AGPL-3.0-or-later
# 노트북 release 빌드 (정식 키 서명 + 로컬 판 번호).
#
#   .\scripts\local-build.ps1                      # :app:assembleRelease :bench:assembleRelease
#   .\scripts\local-build.ps1 -Tasks ':app:assembleRelease'
#   .\scripts\local-build.ps1 -BaseRun 210         # gh 없이 기준 번호를 직접 줄 때
#   .\scripts\local-build.ps1 -Abis 'arm64-v8a,x86_64'  # 에뮬레이터용까지 (기본은 arm64 만)
#
# - 기준 번호(baseRun) = main 의 최근 GitHub 빌드(build.yml) 실행 번호 (gh run list).
#   판 = 1.0.0-rc.<baseRun>-local, versionCode = 1000 + baseRun + 1 (AshBuild.kt).
# - 앱은 정식 키, 측정 앱(bench)은 시험용(디버그) 키로 서명한다. 해당 태스크가 있을 때만 그 키를 묻는다.
# - 키 비밀번호는 실행할 때 대표님이 직접 입력한다. 파일·로그에 남기지 않고, 끝나면 환경변수에서 지운다.
# - 키 파일은 열지 않고 경로만 넘긴다.
param(
    [int]$BaseRun = 0,
    [string]$KeyAlias = $env:ASH_KEY_ALIAS,
    [string]$KeystoreFile = (Join-Path $env:USERPROFILE '.ash-keys\ash-reader-release.jks'),
    [string]$DebugKeyAlias = $env:ASH_DEBUG_KEY_ALIAS,
    [string]$DebugKeystoreFile = (Join-Path $env:USERPROFILE '.ash-keys\ash-reader-debug.jks'),
    [string[]]$Tasks = @(':app:assembleRelease', ':bench:assembleRelease'),
    # 노트북 빌드는 실기기용 arm64 만 (x86_64·32비트는 CI 에서)
    [string]$Abis = 'arm64-v8a'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

# --- 빌드 도구 경로 (CLAUDE.md '빌드') ---
# ndk-build(MuPDF)는 NDK·프로젝트 경로에 공백이 있으면 실패한다 → SDK 는 C:\Android\Sdk
$sdk = if ($env:ANDROID_HOME -and $env:ANDROID_HOME -notmatch ' ') { $env:ANDROID_HOME } else { 'C:\Android\Sdk' }
if (-not (Test-Path $sdk)) { throw "Android SDK 가 없습니다: $sdk" }
$env:ANDROID_HOME = $sdk
$env:ANDROID_NDK_HOME = Join-Path $sdk 'ndk\28.2.13676358'
foreach ($p in @('C:\msys64\usr\bin', (Join-Path $env:USERPROFILE '.cargo\bin'), (Join-Path $sdk 'cmake\3.22.1\bin'))) {
    if ((Test-Path $p) -and ($env:Path -split ';' -notcontains $p)) { $env:Path = "$env:Path;$p" }
}

# --- 기준 번호 ---
if ($BaseRun -le 0) {
    Push-Location $repo
    try { $n = gh run list --workflow build.yml --branch main --limit 1 --json number --jq '.[0].number' } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0 -or -not ($n -as [int])) {
        throw 'gh 로 최근 빌드 번호를 못 읽었습니다. gh auth login 을 하거나 -BaseRun <번호> 로 주세요.'
    }
    $BaseRun = [int]$n
}
Write-Host "기준 GitHub 빌드: #$BaseRun → 판 1.0.0-rc.$BaseRun-local, versionCode $(1000 + $BaseRun + 1)"

# --- 서명 ---
function Read-Secret([string]$prompt) {
    $s = Read-Host $prompt -AsSecureString
    $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }
}
# 빌드 전에 비밀번호·별칭을 keytool 로 먼저 확인한다 (비밀번호는 환경변수로만 넘긴다)
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'

$started = Get-Date
try {
    # 앱은 정식(릴리스) 키 (D-014)
    if ($Tasks -match '^:app:') {
        if (-not (Test-Path $KeystoreFile)) { throw "키 파일이 없습니다: $KeystoreFile" }
        if (-not $KeyAlias) { $KeyAlias = Read-Host '키 별칭(alias)' }
        $env:ASH_KEYSTORE_FILE = $KeystoreFile
        $env:ASH_KEY_ALIAS = $KeyAlias
        $env:ASH_KEYSTORE_PASSWORD = Read-Secret '키 저장소 비밀번호'
        $env:ASH_KEY_PASSWORD = Read-Secret '키 비밀번호 (같으면 같은 것을 입력)'
        if (Test-Path $keytool) {
            & $keytool -list -keystore $KeystoreFile -storepass:env ASH_KEYSTORE_PASSWORD *> $null
            if ($LASTEXITCODE -ne 0) { throw '키 저장소 비밀번호가 맞지 않습니다. 다시 실행해 주세요.' }
            & $keytool -list -keystore $KeystoreFile -storepass:env ASH_KEYSTORE_PASSWORD -alias $KeyAlias *> $null
            if ($LASTEXITCODE -ne 0) { throw "별칭 '$KeyAlias' 이 키 저장소에 없습니다." }
            Write-Host '키 저장소 비밀번호·별칭 확인됨'
        }
    }

    # 측정 앱(bench)은 시험용(디버그) 키로 서명한다(bench/build.gradle.kts). 기기의 CI 판과 같은 키여야 덮어 설치된다.
    if ($Tasks -match '^:bench:') {
        if (-not (Test-Path $DebugKeystoreFile)) { throw "시험용 키 파일이 없습니다: $DebugKeystoreFile" }
        if (-not $DebugKeyAlias) { $DebugKeyAlias = Read-Host '시험용 키 별칭(alias)' }
        $env:ASH_DEBUG_KEYSTORE_FILE = $DebugKeystoreFile
        $env:ASH_DEBUG_KEY_ALIAS = $DebugKeyAlias
        $env:ASH_DEBUG_KEYSTORE_PASSWORD = Read-Secret '시험용 키 저장소 비밀번호'
        if (Test-Path $keytool) {
            & $keytool -list -keystore $DebugKeystoreFile -storepass:env ASH_DEBUG_KEYSTORE_PASSWORD -alias $DebugKeyAlias *> $null
            if ($LASTEXITCODE -ne 0) { throw '시험용 키 비밀번호 또는 별칭이 맞지 않습니다. 다시 실행해 주세요.' }
            Write-Host '시험용 키 비밀번호·별칭 확인됨'
        }
    }

    # 저장소 경로(C:\Users\Ahn SH\…)의 공백을 피하려고 부모 폴더를 Z: 에 잠깐 연결해 빌드한다.
    # 글자를 늘 Z: 로 고정한다 — 경로가 바뀌면 Gradle·Rust 캐시를 다시 쓰지 못해 처음부터 빌드한다.
    $drive = $null; $buildDir = $repo
    if ($repo -match ' ') {
        $parent = Split-Path -Parent $repo
        $mapped = subst | Where-Object { $_ -like 'Z:\:*' }
        if ($mapped -and $mapped -notlike "*=> $parent") { throw "Z: 가 다른 곳에 연결돼 있습니다: $mapped" }
        if (-not $mapped) {
            if (Test-Path 'Z:\') { throw 'Z: 드라이브가 이미 쓰이고 있습니다.' }
            subst Z: $parent
            if ($LASTEXITCODE -ne 0) { throw 'subst Z: 실패' }
            $drive = 'Z:'
        }
        $buildDir = Join-Path 'Z:\' (Split-Path -Leaf $repo)
    }
    Push-Location $buildDir
    try {
        & .\gradlew.bat @Tasks "-Pash.baseRun=$BaseRun" "-Pash.abis=$Abis"
        if ($LASTEXITCODE -ne 0) { throw "gradlew 실패 (종료 코드 $LASTEXITCODE)" }
    } finally {
        Pop-Location
        if ($drive) { subst $drive /D | Out-Null }
    }
} finally {
    Remove-Item Env:ASH_KEYSTORE_PASSWORD, Env:ASH_KEY_PASSWORD, Env:ASH_DEBUG_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
}
$elapsed = (Get-Date) - $started
Write-Host ("빌드 시간: {0:hh\:mm\:ss}" -f $elapsed)

# --- 서명 인증서 확인 (D-014: AE:6D:B1:29:…:25:B6:F1) ---
$apksigner = Get-ChildItem (Join-Path $sdk 'build-tools\36.0.0\apksigner.bat') -ErrorAction SilentlyContinue
foreach ($apk in Get-ChildItem (Join-Path $repo '*\build\outputs\apk\release\*.apk') -ErrorAction SilentlyContinue) {
    Write-Host "== $($apk.FullName)"
    if ($apksigner) { cmd /c "$($apksigner.FullName) verify --print-certs `"$($apk.FullName)`" 2>&1" | Select-String 'SHA-256' }
}
