# Jarvis on Android

[![Download Latest APK](https://img.shields.io/github/v/release/LasseHartmann/Jarvis-on-Android?label=Download%20Latest%20APK&style=for-the-badge&color=F0B44C)](https://github.com/LasseHartmann/Jarvis-on-Android/releases/latest)
[![Build & Release APK](https://github.com/LasseHartmann/Jarvis-on-Android/actions/workflows/release.yml/badge.svg)](https://github.com/LasseHartmann/Jarvis-on-Android/actions/workflows/release.yml)

A minimalist, 100% voice-controlled Android AI assistant inspired by J.A.R.V.I.S., engineered for the **Xiaomi 15T Pro** (Android 15 / HyperOS 2, 144Hz AMOLED) and compatible with modern Android 12+ phones (`minSdk = 31`, `targetSdk = 35`).

---

## Install in Seconds (No Android Studio Needed)

### Option 1: Direct 1-Tap Install on Your Phone
1. Open **[Releases → Latest](https://github.com/LasseHartmann/Jarvis-on-Android/releases/latest)** on your phone and tap **`Jarvis.apk`**.
2. Install the APK. *(On Android 15 / Xiaomi HyperOS, before enabling Accessibility/Notifications for a sideloaded APK, open **Settings → Apps → Manage apps → J.A.R.V.I.S. → `⋮` top-right → Allow restricted settings**.)*
3. **Optional auto-updates**: Paste `https://github.com/LasseHartmann/Jarvis-on-Android` into **[Obtainium](https://github.com/ImranR98/Obtainium)** to get one-tap updates whenever a new release drops.

### Option 2: 1-Line PowerShell ADB Install *(Best for Xiaomi 15T Pro)*
Installs directly from GitHub Releases over USB/Wireless ADB **without cloning the repo or installing Android Studio**, and automatically avoids Android 15's Restricted Settings block:
```powershell
irm https://raw.githubusercontent.com/LasseHartmann/Jarvis-on-Android/main/scripts/install_from_github.ps1 | iex
```
*(Requires `adb` — install once via `winget install Google.PlatformTools`. On Xiaomi HyperOS, enable **USB debugging** and **Install via USB** in Developer Options.)*

### Option 3: Build from Source
```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
.\scripts\install_xiaomi.ps1 -SkipBuild
```

---

## Highlights

- **144Hz Minimalist 3D Neural Wake Overlay** (`VoiceInteractionService` / `VoiceInteractionSession`):
  - Summon from any screen or lock screen by holding the **Power Button** as your phone's Default Digital Assistant.
  - Renders a floating **3D Fibonacci neural particle sphere** over native Android frosted-glass blur, with three selectable minimalist finishes (**Amber**, **Frost**, **Mono**).
  - Automatically morphs into a click-through top status pill while piloting apps underneath via Accessibility.
- **Pure Voice Experience & Neural J.A.R.V.I.S. Voice**:
  - Instant speech capture, British RP neural audio via **Gemini Native Audio TTS** (`Charon`, `Orus`, `Enceladus`) with an on-device **Stark Helmet Intercom Audio DSP** (high-pass clarity EQ, 2.7 kHz presence boost, broadcast leveling, subtle metallic helmet acoustics), plus British male (`en-GB`) offline fallback.
  - Voice-only safety confirmations (*"Say confirm or cancel"*) before sensitive calls, SMS, notification replies, DND changes, or destructive/payment taps.
- **34 Native & Accessibility Phone-Control Tools**:
  - **System & Hardware**: Flashlight, per-stream volume, ringer profile, screen brightness/adaptive display, Do Not Disturb, media playback, Settings panels.
  - **Apps & Screen Pilot**: Fuzzy app launcher, URL/web search, live redacted UI tree inspection (`read_screen`), element/coordinate tapping, typing, scrolling/swiping, and global OS actions (Home, Back, Recents, Notifications, Quick Settings, Lock Screen, Screenshot).
  - **Comms, Productivity & Telemetry**: Contact lookup, calls, SMS, active notification summaries & inline replies, alarms, timers, calendar read/create, clipboard, persistent SQLite memory bank, action audit log, and live hardware telemetry.
- **Flexible AI Brain**:
  - **Google Gemini 4**: `gemini-4-hydrogen` (default), `gemini-4-argon`, `gemini-4-neon`, `gemini-4-carbon`.
  - **OpenAI-Compatible / Local Ollama**: Point to OpenAI, OpenRouter, LM Studio, or a local LAN Ollama server.
  - **Offline Reflex Core**: Instant local phone commands work with zero API key and zero internet.

## Xiaomi 15T Pro Post-Install Checklist
1. Open **Jarvis** → **Default assistant** → choose **J.A.R.V.I.S.** *(and enable Settings → Additional settings → Gesture shortcuts → Launch digital assistant → Press & hold Power button for 0.5s)*.
2. Open **Phone permissions** → grant runtime permissions, enable **Accessibility pilot** & **Notifications**, and toggle **HyperOS Autostart**, **Background pop-ups**, and **Unrestricted battery**.
3. Open **Provider & API key** → paste your Google Gemini API key (or configure a local Ollama URL).

## Security & Privacy

- **Zero bundled keys**: No API keys or credentials are shipped in this repository.
- **Hardware-backed encryption**: API keys entered in the app are encrypted locally via Android Keystore (`AES/GCM/NoPadding`) and explicitly excluded from Android cloud backups (`backup_rules.xml` & `data_extraction_rules.xml`).
- **Password & finance shield**: `ScreenTreeSerializer` automatically redacts `isPassword` fields (`[REDACTED_PASSWORD]`) and shields password managers / banking apps before UI context ever leaves the device.
- **Local LAN Ollama note**: `AndroidManifest.xml` enables `usesCleartextTraffic="true"` solely so users can optionally connect to self-hosted local HTTP Ollama/LM Studio instances on their home Wi-Fi; Gemini and cloud API endpoints always use HTTPS.

<details>
<summary><strong>Maintainer Guide: Persistent Release Signing in GitHub Actions (Seamless In-Place Updates)</strong></summary>

By default, GitHub Actions signs release APKs with an ephemeral CI debug key. To keep a stable signature across all future releases (so updates install right over previous versions without losing settings), generate one release keystore locally and add four Repository Secrets under **Settings → Secrets and variables → Actions**:

```powershell
keytool -genkey -v -keystore jarvis-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias jarvis
[Convert]::ToBase64String([IO.File]::ReadAllBytes("jarvis-release.jks")) | Set-Clipboard
```

Add these secrets (never commit `jarvis-release.jks`):
- `JARVIS_KEYSTORE_BASE64` — base64 string copied above
- `JARVIS_KEYSTORE_PASSWORD` — keystore password
- `JARVIS_KEY_ALIAS` — `jarvis`
- `JARVIS_KEY_PASSWORD` — key password

Then push any tag like `git tag v1.0.1 && git push origin v1.0.1` (or click **Run workflow** in the Actions tab) to publish a new Release APK automatically.
</details>

---

*Unofficial personal open-source project inspired by J.A.R.V.I.S. Not affiliated with or endorsed by Marvel or Disney.*
