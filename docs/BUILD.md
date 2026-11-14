# Build Jarvis from source

## Requirements

- Windows with PowerShell 7, Git and Python 3.11+
- JDK 17 and Android SDK Platform 35, Build Tools 35.0.0, NDK 27.0.12077973, and CMake 3.22.1
- Several gigabytes of free disk space for speech models and build output
- An arm64 phone with Android 9+ or an x86_64 Android emulator with AVX2

## Setup and build

```powershell
git clone https://github.com/sreyasts/jarvis-android-releases.git
cd jarvis-android-releases
.\scripts\setup.ps1
.\gradlew.bat --gradle-user-home .gradle-user-home :app:assembleDebug :app:testDebugUnitTest :core:test :safety:test :app:lintDebug
```

Set `ANDROID_HOME` or pass `-AndroidSdk <path>` to setup if Android Studio installed the SDK outside its standard Windows location. Setup downloads and verifies the bundled speech assets and checks out the pinned llama.cpp revision. The optional Qwen models download from **AI settings** on the phone.

The development APK is at `app/build/outputs/apk/debug/app-debug.apk`. Add `-PjarvisAbi=arm64-v8a` for a smaller phone-only build. A development build uses your local signing key. Use a test device if you already have a public build installed under a different signing key.

Use a dedicated emulator for `:app:connectedDebugAndroidTest`. Some runtime tests also require a pinned Qwen model. The live Gemini test uses separate credentials supplied to the test device. Keep them out of the checkout and issue reports.

## Modules

| Directory | Responsibility |
| --- | --- |
| [app](../app) | Compose interface, Android assistant integration, phone actions, Gemini, native AI runtime, and update checks |
| [voice](../voice) | Microphone recording, offline recognition and speech synthesis |
| [core](../core) | Command parsing, audio data types and update validation |
| [safety](../safety) | Voice input policy |
| [scripts](../scripts) | Dependency setup and release publishing |

See [updates and signing](UPDATES.md) for maintainer release commands.
