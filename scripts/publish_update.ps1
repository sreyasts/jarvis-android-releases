param(
    [Parameter(Mandatory)][ValidateRange(1, 2100000000)][int]$VersionCode,
    [Parameter(Mandatory)][ValidateLength(5,48)][ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9._-]+)?$')][string]$VersionName,
    [ValidatePattern('^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$')][string]$Repository = 'sreyasts/jarvis-android-releases',
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$ReleaseNotes,
    [switch]$SkipBuild,
    [switch]$DraftOnly
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$tag = "v$VersionName"
$staging = Join-Path $projectRoot ".jarvis-share/publish/$tag"
New-Item -ItemType Directory -Path $staging -Force | Out-Null
if (-not $AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$buildTools = Join-Path $AndroidSdk 'build-tools/35.0.0'
$apk = Join-Path $staging 'Jarvis.apk'

function Run-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed ($LASTEXITCODE). Check release status before retrying." }
}

$publishedText = & gh release list --repo $Repository --limit 1 --exclude-drafts --exclude-pre-releases --json tagName
if ($LASTEXITCODE -ne 0) { throw 'Could not read the release channel.' }
$published = @($publishedText | ConvertFrom-Json)
$previous = $null
if ($published.Count -gt 0) {
    $previousDir = Join-Path $staging 'previous'
    New-Item -ItemType Directory -Path $previousDir -Force | Out-Null
    Run-Checked 'gh' @('release', 'download', '--repo', $Repository, '--pattern', 'jarvis-update.json', '--dir', $previousDir, '--clobber')
    $previous = Get-Content -LiteralPath (Join-Path $previousDir 'jarvis-update.json') -Raw | ConvertFrom-Json
    if ($VersionCode -le [long]$previous.versionCode) { throw 'VersionCode must be greater than the published version.' }
}

Push-Location -LiteralPath $projectRoot
try {
    if (-not $SkipBuild) {
        Run-Checked (Join-Path $projectRoot 'gradlew.bat') @('--gradle-user-home', '.gradle-user-home', '--no-daemon',
            ':app:assembleRelease', ':app:testDebugUnitTest', ':core:test', ':safety:test', ':app:lintRelease',
            '-PjarvisAbi=arm64-v8a', '-PjarvisUseLegacySigningKey=true',
            "-PjarvisVersionCode=$VersionCode", "-PjarvisVersionName=$VersionName", "-PjarvisUpdateRepository=$Repository")
    }
    Copy-Item -LiteralPath 'app/build/outputs/apk/release/app-release.apk' -Destination $apk
} finally { Pop-Location }

$badging = & (Join-Path $buildTools 'aapt.exe') dump badging $apk
if ($LASTEXITCODE -ne 0) { throw 'APK metadata could not be read.' }
$packageLine = $badging | Where-Object { $_ -like 'package:*' } | Select-Object -First 1
if ($packageLine -notmatch "name='dev.jarvis.app'" -or $packageLine -notmatch "versionCode='$VersionCode'" -or
    $packageLine -notmatch ("versionName='" + [regex]::Escape($VersionName) + "'")) { throw 'APK package/version does not match this release.' }
if (-not ($badging -match "native-code: 'arm64-v8a'$") -or ($badging -match "native-code:.*x86")) { throw 'Publish the arm64 phone APK only.' }
if ($badging -contains 'application-debuggable') { throw 'Publish a non-debuggable release APK.' }
$minLine = $badging | Where-Object { $_ -match "^sdkVersion:'([0-9]+)'$" } | Select-Object -First 1
if ($minLine -notmatch "^sdkVersion:'([0-9]+)'$") { throw 'APK minimum Android version missing.' }
$minSdk = [int]$Matches[1]
if ($minSdk -lt 28 -or $minSdk -gt 100 -or (Get-Item -LiteralPath $apk).Length -gt 2000000000) {
    throw 'APK metadata exceeds the update-feed schema limits.'
}
$signing = & (Join-Path $buildTools 'apksigner.bat') verify --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$certificateLine = $signing | Where-Object { $_ -match '^Signer #1 certificate SHA-256 digest: ' } | Select-Object -First 1
if ($certificateLine -notmatch ': ([a-fA-F0-9]{64})$') { throw 'APK signing certificate missing.' }
$certificate = $Matches[1].ToLowerInvariant()
$expectedCertificate = (Get-Content -LiteralPath (Join-Path $projectRoot 'docs/release-signing-cert.sha256') -Raw).Trim()
if ($certificate -cne $expectedCertificate -or ($previous -and $certificate -cne $previous.signingCertificateSha256)) {
    throw 'Signing key changed. Keep the existing key so installed apps can update and retain their settings and downloaded model.'
}
Run-Checked (Join-Path $buildTools 'zipalign.exe') @('-c', '-P', '16', '4', $apk)
$manifest = [ordered]@{
    schema = 1; packageName = 'dev.jarvis.app'; versionCode = $VersionCode; versionName = $VersionName
    minSdk = $minSdk; apkUrl = "https://github.com/$Repository/releases/download/$tag/Jarvis.apk"
    sha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    sizeBytes = (Get-Item -LiteralPath $apk).Length; signingCertificateSha256 = $certificate
}
$manifestPath = Join-Path $staging 'jarvis-update.json'
$manifest | ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding utf8NoBOM
$notesPath = Join-Path $staging 'release-notes.md'
@"
Jarvis $VersionName for Nothing Phone (2a)/(3a), arm64 Android 9 or newer.

Install this APK over the existing Jarvis app. Keep app data to retain your settings, downloaded model and Gemini connection. Obsolete voice profiles are removed.
Android asks you to approve installation. The in-app Update button clears after the newer version is installed.

Speech and replies support English and Malayalam. With an internet connection and a saved Gemini key, Jarvis sends speech after activation to Gemini for transcription and uses Gemini to answer. It remembers each model's quota reset, tries the next available model, and falls back to offline AI. Without internet, speech stays on the phone.
Offline AI: download Qwen3 1.7B (1.28 GB) or Qwen3 4B (2.50 GB) in AI settings. Malayalam audio requires an installed Malayalam system voice. Typed requests stay silent. Alarm requests open Clock for review and saving.
Assistant setup opens Android's actual settings chooser. Overlay access is optional and cannot bypass Android restricted settings.
Assistant gestures and floating-button taps open a compact overlay. The microphone only opens after voice activation. When Listening appears, you can say “Hey Jarvis” followed immediately by your request. Phone actions require unlocking. Physical Nothing hardware performance is unverified.

Voice model attribution and license: see VOICE_NOTICE.txt and the notices bundled in the APK.
"@ | Set-Content -LiteralPath $notesPath -Encoding utf8NoBOM
if ($ReleaseNotes) { Copy-Item -LiteralPath $ReleaseNotes -Destination $notesPath }

$existingText = & gh release view $tag --repo $Repository --json isDraft 2>$null
if ($LASTEXITCODE -eq 0) {
    if (-not ($existingText | ConvertFrom-Json).isDraft) { throw 'That version is already published. Increase versionCode and versionName.' }
} else {
    Run-Checked 'gh' @('release', 'create', $tag, '--repo', $Repository, '--draft', '--title', "Jarvis $VersionName", '--notes-file', $notesPath)
}
Run-Checked 'gh' @('release', 'upload', $tag, '--repo', $Repository, '--clobber', $apk, $manifestPath, (Join-Path $projectRoot 'docs/VOICE_NOTICE.txt'))
if ($DraftOnly) {
    Write-Host "Draft uploaded: $tag. The public update feed is unchanged until this release is published."
    return
}
# Publish only after both the APK and matching feed are uploaded successfully.
Run-Checked 'gh' @('release', 'edit', $tag, '--repo', $Repository, '--draft=false', '--latest', '--notes-file', $notesPath)
Write-Host "Published: https://github.com/$Repository/releases/latest/download/Jarvis.apk"
