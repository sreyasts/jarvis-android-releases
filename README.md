<div align="center">
  <img src="app/src/main/res/drawable-nodpi/jarvis_reactor.png" width="212" alt="Jarvis blue reactor">
  <h1>Jarvis for Android</h1>
  <p><strong>A voice and text assistant for English and Malayalam.</strong><br>
  Control your phone offline, add on-device AI, or connect Gemini for web-enabled answers.</p>
  <a href="https://github.com/sreyasts/jarvis-android-releases/releases/latest/download/Jarvis.apk"><img src="https://img.shields.io/badge/Download%20Android%20APK-0877BA?style=for-the-badge&amp;logo=android&amp;logoColor=white" alt="Download Android APK"></a>
  <p><strong>Android 9+</strong> · <strong>64-bit ARM</strong> · Built with Nothing Phone (2a) and (3a) in mind</p>
  <p><a href="#install-on-android">Install guide</a> · <a href="#what-jarvis-can-do">Features</a> · <a href="https://github.com/sreyasts/jarvis-android-releases/releases/latest">Release notes</a> · <a href="docs/BUILD.md">Build from source</a></p>
</div>

## Install on Android

1. On your phone, tap **[Download Android APK](https://github.com/sreyasts/jarvis-android-releases/releases/latest/download/Jarvis.apk)** and open **Jarvis.apk** when it finishes. Use Wi-Fi for the roughly 410 MB download.
2. If Android prompts you, allow **Install unknown apps** for the browser or file manager you used. Return to the installer and tap **Install** or **Update**.
3. Open **Jarvis**. Type a request, or tap the blue reactor and grant microphone access. Try **“What’s my battery level?”** or **“ബാറ്ററി എത്രയാണ്?”**

Already using Jarvis? Install over your existing copy to keep your settings, saved Gemini connection and downloaded AI model. Open **Help → Check for updates** to look for the next version.

## What Jarvis can do

| Feature | How it works |
| --- | --- |
| **Talk or type** | Speak English or Malayalam, or type when you want a silent reply. In Help, choose both languages or select one, preview the voice, and control whether spoken replies are read aloud. Malayalam audio requires a Malayalam voice in your Android text-to-speech engine. The reactor responds to microphone and playback levels. |
| **Control your phone** | Check the time and battery, set a timer, toggle the flashlight, adjust media volume, or open an installed app, Camera or Settings. An alarm request opens Clock with the time filled in for you to review and save. Phone actions require an unlocked device. |
| **Ask AI offline** | In **AI settings**, choose Qwen3 1.7B (1.28 GB) or 4B (2.50 GB) and tap **Download offline AI**. Keep Jarvis open during the download. You can pause and resume. Jarvis verifies the model before using it. |
| **Connect Gemini** | In **AI settings**, enter your own Google AI Studio key, save it and test the connection. Enable **Search & read web pages** for Google Search, public page answers and source links when available. Model access, quota and charges depend on your Google account. Jarvis can fall back to an installed offline model when Gemini is unavailable. |
| **Launch from a shortcut** | In **Assistant & shortcuts → Choose Jarvis**, select it as Android’s digital assistant. On Nothing OS, map the power-button hold gesture to **Digital assistant**. The optional **Floating button** also opens the compact assistant overlay and needs display-over-apps permission. |
| **Control listening** | Tap the reactor or launch the assistant shortcut, then speak when **Listening** appears. You can say “Hey Jarvis” at the start of the request. The microphone is off while idle. **Stop** cancels the current request or speech. The main switch turns Jarvis off. |
| **Personalize replies** | In **Help → Personalize Jarvis**, save your preferred name and reply preferences. Jarvis can use the last two exchanges for follow-up questions during the current app session. |

Built-in phone commands and speech recognition work with no AI account. For general questions, install a Qwen model or connect Gemini. Jarvis uses a limited set of Android actions. Handle calls, messages, payments and deletions directly in their apps.

## Privacy

Jarvis processes speech on the device by default. With a saved Gemini key and an internet connection, Jarvis can send speech after activation to Google. Gemini answers can include the current request, saved profile preferences and up to two recent exchanges. Remove the saved key in **AI settings** to return to local processing. The key is encrypted with Android Keystore and excluded from backup. Android handles permission prompts and approves each APK installation.

## Project links

- [Build from source](docs/BUILD.md) · dependencies, setup and tests
- [Updates and signing](docs/UPDATES.md) · permanent download, release process and APK verification
- [Third-party notices](docs/THIRD_PARTY.md) · software, speech and AI model attribution
- [Report an issue](https://github.com/sreyasts/jarvis-android-releases/issues/new) · include your phone model, Android version and steps to reproduce

Source is publicly viewable. Third-party components carry their own licenses. The Jarvis project source has no project-wide license grant.
