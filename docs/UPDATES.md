# Updates and signing

[Download the latest APK](https://github.com/sreyasts/jarvis-android-releases/releases/latest/download/Jarvis.apk) · [Release history](https://github.com/sreyasts/jarvis-android-releases/releases) · [Update manifest](https://github.com/sreyasts/jarvis-android-releases/releases/latest/download/jarvis-update.json)

## On your phone

Jarvis looks for updates as you open it and periodically while it is visible. **Help → Check for updates** runs a manual check. When a compatible newer build is available, **Update** opens its APK in your browser. Android prompts for approval. Install over the existing app to retain your settings, saved Gemini key and downloaded models.

GitHub hosts the two stable links above. Draft releases are hidden from app update checks. The update request fetches release metadata and carries no speech or transcript.

## Verify an APK

The release manifest records the APK SHA-256, size, package name, version code and certificate digest. Compare the downloaded file hash with `sha256` and verify the APK signature against the [published certificate fingerprint](release-signing-cert.sha256).

```powershell
Get-FileHash .\Jarvis.apk -Algorithm SHA256
& "$env:ANDROID_HOME/build-tools/35.0.0/apksigner.bat" verify --print-certs .\Jarvis.apk
```

## Publish an update

1. Complete [developer setup](BUILD.md) and authenticate GitHub CLI with write access to the repo.
2. Update the default version in `app/build.gradle.kts`, commit the source and push to the default branch. Use a version code above the latest published update.
3. Build and stage the APK and manifest with the publisher. Pass `-ReleaseNotes` with a Markdown file when you have a release-specific changelog.

```powershell
.\scripts\publish_update.ps1 -VersionCode 7 -VersionName '0.5.2' -DraftOnly
```

The script builds a non-debuggable arm64 APK, runs unit tests and release lint, checks the package, version, signature and 16 KB ZIP alignment, then uploads the APK, update manifest and voice notice to the draft. Verify the assets and publish the draft as Latest, or rerun the command without `-DraftOnly`. `-SkipBuild` reuses a previously built release APK and still verifies its metadata, signing and alignment.

The publisher uses the original local Android signing key to preserve in-place upgrades. Keep the signing key private and backed up. Android uses the installed certificate to establish update trust. [Android signing documentation](https://developer.android.com/studio/publish/app-signing)
