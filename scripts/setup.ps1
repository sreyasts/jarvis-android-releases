param([string]$AndroidSdk)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path

function Get-VerifiedFile([string]$Url, [string]$RelativePath, [string]$Sha256) {
    $destination = Join-Path $projectRoot $RelativePath
    if (Test-Path -LiteralPath $destination) {
        if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ieq $Sha256) {
            Write-Host "Verified $RelativePath"
            return
        }
        throw "Checksum mismatch: $RelativePath. Remove that file and run setup again."
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    $temporary = "$destination.download"
    try {
        Invoke-WebRequest -Uri $Url -OutFile $temporary -TimeoutSec 240
        if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash -ine $Sha256) {
            throw "Downloaded file failed verification: $RelativePath"
        }
        Move-Item -LiteralPath $temporary -Destination $destination
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
    Write-Host "Downloaded and verified $RelativePath"
}

Get-VerifiedFile `
    'https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar' `
    'libs/sherpa-onnx-2.0.1.aar' `
    'a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7'

$asrFiles = @{
    'base-encoder.int8.onnx' = '0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11'
    'base-decoder.int8.onnx' = '9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d'
    'base-tokens.txt' = 'b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126'
}
$asrReady = Test-Path -LiteralPath (Join-Path $projectRoot 'app/src/androidTest/assets/english-speech.wav')
foreach ($fileName in $asrFiles.Keys) {
    $modelPath = Join-Path $projectRoot "app/src/main/assets/models/whisper/$fileName"
    if (-not (Test-Path -LiteralPath $modelPath)) { $asrReady = $false; break }
    if ((Get-FileHash -LiteralPath $modelPath -Algorithm SHA256).Hash -ine $asrFiles[$fileName]) { $asrReady = $false; break }
}
if (-not $asrReady) {
    Get-VerifiedFile `
        'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2' `
        'libs/asr-download/whisper-base.tar.bz2' `
        '911b2083efd7c0dca2ac3b358b75222660dc09fb716d64fbfc417ba6c99ff3de'
    & python (Join-Path $PSScriptRoot 'extract_asr.py') (Join-Path $projectRoot 'libs/asr-download/whisper-base.tar.bz2')
    if ($LASTEXITCODE -ne 0) { throw 'Whisper model extraction failed.' }
}
$asrChecksums = foreach ($fileName in $asrFiles.Keys | Sort-Object) {
    $modelPath = Join-Path $projectRoot "app/src/main/assets/models/whisper/$fileName"
    if ((Get-FileHash -LiteralPath $modelPath -Algorithm SHA256).Hash -ine $asrFiles[$fileName]) { throw "Speech model checksum failed: $fileName" }
    "$($asrFiles[$fileName])  $fileName"
}
$asrChecksums | Set-Content -LiteralPath (Join-Path $projectRoot 'app/src/main/assets/models/whisper/checksums.txt') -Encoding utf8NoBOM
Write-Host 'Verified offline Whisper speech models'

$voiceFiles = @{
    'model.onnx' = '59ec096e3dd57bbf029faecf576dc2dd73bc0f92fb3e2a14fab0d54a29fd90fa'
    'tokens.txt' = '2619c1a9de1bcf928162f40c583caf39368cfd6b2340c7bcad51dc634411ec36'
}
$voiceReady = $true
foreach ($fileName in $voiceFiles.Keys) {
    $voicePath = Join-Path $projectRoot "app/src/main/assets/models/tts/$fileName"
    if (-not (Test-Path -LiteralPath $voicePath)) { $voiceReady = $false; break }
    if ((Get-FileHash -LiteralPath $voicePath -Algorithm SHA256).Hash -ine $voiceFiles[$fileName]) { $voiceReady = $false; break }
}
if (-not $voiceReady) {
    Get-VerifiedFile `
        'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-northern_english_male-medium-int8.tar.bz2' `
        'libs/asr-download/jarvis-voice-northern.tar.bz2' `
        '9b2afdc8f35426f6b2739f255e67841679a52b56d19ece874a1eae208cb88ad1'
    & python (Join-Path $PSScriptRoot 'extract_voice.py') (Join-Path $projectRoot 'libs/asr-download/jarvis-voice-northern.tar.bz2')
    if ($LASTEXITCODE -ne 0) { throw 'Voice extraction failed.' }
}
Get-VerifiedFile `
    'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/lexicon-en-us.txt' `
    'app/src/main/assets/models/tts/lexicon.txt' `
    '8b67ae549a465eee78f92ecb74644ad90c3c58ce65f19c68baf85878a10ef73a'
foreach ($fileName in $voiceFiles.Keys) {
    if ((Get-FileHash -LiteralPath (Join-Path $projectRoot "app/src/main/assets/models/tts/$fileName") -Algorithm SHA256).Hash -ine $voiceFiles[$fileName]) { throw "Voice checksum failed: $fileName" }
}
Write-Host 'Verified bundled British voice'
Copy-Item -LiteralPath (Join-Path $projectRoot 'docs/VOICE_NOTICE.txt') -Destination (Join-Path $projectRoot 'app/src/main/assets/models/tts/VOICE_LICENSE')

if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_HOME }
if (-not $AndroidSdk -and $env:LOCALAPPDATA) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if ($AndroidSdk -and (Test-Path -LiteralPath $AndroidSdk)) {
    $propertiesPath = Join-Path $projectRoot 'local.properties'
    if (-not (Test-Path -LiteralPath $propertiesPath)) {
        $sdkForProperties = $AndroidSdk.Replace('\', '/').Replace(':', '\:')
        Set-Content -LiteralPath $propertiesPath -Value "sdk.dir=$sdkForProperties" -Encoding ascii
    }
}
Get-VerifiedFile `
    'https://huggingface.co/krut42/voice-lid-ecapa-voxlingua107/resolve/f050aac33e6908a2da7a74f6dde9790b79b69673/lid-ecapa-voxlingua107.int8.onnx' `
    'app/src/main/assets/models/language/lid-ecapa-voxlingua107.int8.onnx' `
    '3f1504000353f1da5015ae0df0bdae8d1fd7064680e45a8c9cb02740d78c4fa9'

Get-VerifiedFile `
    'https://huggingface.co/akm9191/sherpa-onnx-indicconformer-ml-int8/resolve/0ea74b1557da742520c68f0c9777bb4d0147c766/model.int8.onnx' `
    'app/src/main/assets/models/malayalam/model.int8.onnx' `
    '390b8f9e7092324d9da586829114db62f792e917d59e86e57771f058ff0d6733'

$llamaPath = Join-Path $projectRoot 'libs/llama.cpp'
$llamaRevision = '3b4d6ab3f79651935fba1dd93732f29022a7f5f5'
if (-not (Test-Path -LiteralPath (Join-Path $llamaPath '.git'))) {
    & git clone --no-checkout --filter=blob:none https://github.com/ggml-org/llama.cpp.git $llamaPath
    if ($LASTEXITCODE -ne 0) { throw 'Could not download llama.cpp.' }
    & git -C $llamaPath checkout --detach $llamaRevision
    if ($LASTEXITCODE -ne 0) { throw 'Could not check out pinned llama.cpp.' }
}
$actualRevision = & git -C $llamaPath rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $actualRevision.Trim() -ne $llamaRevision) { throw "llama.cpp must use pinned commit $llamaRevision; existing checkout was left unchanged." }
$mmapPatch = Join-Path $PSScriptRoot 'llama-android-mmap.patch'
& git -C $llamaPath apply --reverse --check $mmapPatch 2>$null
if ($LASTEXITCODE -ne 0) {
    & git -C $llamaPath apply $mmapPatch
    if ($LASTEXITCODE -ne 0) { throw 'Could not apply the Android memory-mapping patch.' }
}
Write-Host 'Setup complete. Install NDK 27.0.12077973 and CMake 3.22.1; build with --gradle-user-home .gradle-user-home. The optional Qwen model downloads from AI settings on the phone.'
