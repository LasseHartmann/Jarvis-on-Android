# Jarvis on Android

A minimalist, 100% voice-controlled Android AI assistant inspired by J.A.R.V.I.S., engineered for the **Xiaomi 15T Pro** (Android 15 / HyperOS 2, 144Hz AMOLED) and compatible with modern Android 12+ phones (`minSdk = 31`, `targetSdk = 35`).

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

## Security & Privacy

- **Zero bundled keys**: No API keys or credentials are shipped in this repository.
- **Hardware-backed encryption**: API keys entered in the app are encrypted locally via Android Keystore (`AES/GCM/NoPadding`) and explicitly excluded from Android cloud backups (`backup_rules.xml` & `data_extraction_rules.xml`).
- **Password & finance shield**: `ScreenTreeSerializer` automatically redacts `isPassword` fields (`[REDACTED_PASSWORD]`) and shields password managers / banking apps before UI context ever leaves the device.
- **Local LAN Ollama note**: `AndroidManifest.xml` enables `usesCleartextTraffic="true"` solely so users can optionally connect to self-hosted local HTTP Ollama/LM Studio instances on their home Wi-Fi; Gemini and cloud API endpoints always use HTTPS.

## Build & Install

### Prerequisites
- Android Studio / Android SDK (`compileSdk 37.1`, JDK 17+)
- USB Debugging enabled on your phone *(on Xiaomi HyperOS, also enable **Developer options → Install via USB**)*

### Compile & Run Unit Tests
```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

### Deploy via ADB (Recommended on Xiaomi HyperOS)
Installing via ADB avoids Android 15's sideloaded "Restricted Settings" block on Accessibility and Notification Listener services:
```powershell
.\scripts\install_xiaomi.ps1
```

### Xiaomi 15T Pro Post-Install Checklist
1. Open **Jarvis** → **Default assistant** → choose **J.A.R.V.I.S.** *(and enable Settings → Additional settings → Gesture shortcuts → Launch digital assistant → Press & hold Power button for 0.5s)*.
2. Open **Phone permissions** → grant runtime permissions, enable **Accessibility pilot** & **Notifications**, and toggle **HyperOS Autostart**, **Background pop-ups**, and **Unrestricted battery**.
3. Open **Provider & API key** → paste your Google Gemini API key (or configure a local Ollama URL).

---

*Unofficial personal open-source project inspired by J.A.R.V.I.S. Not affiliated with or endorsed by Marvel or Disney.*
