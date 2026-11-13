<div align="center">

<img src="docs/images/retra-logo.webp" alt="Retra logo" width="128">

# Retra

**A modern, open-source Game Boy Advance emulator for Android — powered by mGBA.**

[![Version](https://img.shields.io/badge/version-v1.0.0-E0457B)](https://github.com/Layfate/Retra/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#requirements)
[![License](https://img.shields.io/badge/license-MPL--2.0-blue.svg)](LICENSE)
[![Core](https://img.shields.io/badge/core-mGBA-8A2BE2)](https://mgba.io/)

[**Download**](https://github.com/Layfate/Retra/releases) ·
[**Features**](#features) ·
[**Build**](#build-from-source) ·
[**Troubleshooting**](TROUBLESHOOTING.md) ·
[**Changelog**](CHANGELOG.md)

</div>

---

Retra pairs the accuracy of the **mGBA** core with a clean, mobile-first interface. Your library, saves, layouts, and settings are tied to each game's content, so they follow the game even when files are moved or renamed.

> [!IMPORTANT]
> Retra does **not** include ROMs, BIOS files, or any copyrighted game content. Only use games and files you are legally allowed to use.

## Highlights

| | |
|---|---|
| 🎮 **Accurate emulation** | GB, GBC, and GBA through mGBA, with BIOS support, patches, and cheats |
| ⚡ **Smooth turbo** | Up to 16× fast-forward without stutter, frame backlog, or audio glitches |
| 💾 **Never lose progress** | Battery saves, save states, auto-resume, and portable `.retra` backups |
| 🕹️ **Your controls, your way** | Drag, resize, and fade on-screen buttons separately for portrait and landscape |
| 🔗 **Play together** | Local Link, Single-Pak/Multiboot, and Wi-Fi or Bluetooth Remote Link |
| ☁️ **Sync** | Optional Google Drive backup and restore |

## Features

<details open>
<summary><b>Emulation & gameplay</b></summary>

- Game Boy, Game Boy Color, and Game Boy Advance via mGBA.
- Fast-forward (2×/4×/8×/16×) and slow motion, with timing kept steady at every speed.
- Stable 60/120 Hz gameplay presentation on high-refresh screens, independent of emulation speed.
- BIOS support, ROM patches, cheats, statistics, and per-game settings.
- Optional GLSL shaders and game-only color presets (Classic, Vivid, Warm, Muted).

</details>

<details>
<summary><b>Saves & data</b></summary>

- Battery saves, save states, and automatic resume.
- **Import save** accepts `.sav` and `.srm` files with size and filename safety checks.
- Each game is identified by a SHA-256 hash of its content, so saves, covers, and layouts reconnect after a ROM is moved, renamed, or re-imported.
- **Create Backup** / **Restore Backup** with portable `.retra` files.
- Android Auto Backup, device transfer, and *Keep app data* reinstall support.

</details>

<details>
<summary><b>Controls & interface</b></summary>

- Screen Editor with independent portrait and landscape layouts.
- Draggable, resizable on-screen controls with adjustable opacity.
- Optional tap sound and haptics.
- Automatic cover art, plus manual cover and background overrides.
- Library with categories, search, sorting, history, and statistics.

</details>

<details>
<summary><b>Multiplayer & sync</b></summary>

- **Local Link** — GBA Link Cable-style multiplayer on one device.
- **Single-Pak / Multiboot** — two players on one device (needs a user-provided 16 KiB GBA BIOS for the receiver).
- **Wi-Fi Remote Link** and **Bluetooth Remote Link** between devices, with connection health checks, jitter-aware input delay, and automatic recovery.
- Google Drive sync with conflict-safe behavior, plus a local folder fallback.

</details>

## Install

1. Open the [**Releases**](https://github.com/Layfate/Retra/releases) page and download the latest APK.
2. Open the APK on your Android device and allow installs from that source if asked.
3. Launch Retra, tap **+**, and add your games.

### Requirements

- Android **8.0** (API 26) or newer.
- Your own legally obtained ROMs. A GBA BIOS is optional, except for Single-Pak receivers.

Having trouble? See [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md).

## Build from source

### Prerequisites

- Android Studio with the SDK requested by the Gradle project
- Android NDK **r28** and CMake **3.22.1**
- Python 3
- The locked mGBA revision `543a197582c30364584d773a974d7f991892fa43` (recorded in `third_party/mgba.lock`)

### Steps

```bash
git clone https://github.com/Layfate/Retra.git
cd Retra
python3 tools/prepare_mgba.py   # fetches mGBA at the locked commit into third_party/mgba/
```

Then open the folder in Android Studio, let Gradle sync, and run on a device.

### Command-line checks

```bash
# macOS / Linux
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug

# Windows
gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug

# Release gate (no Android SDK needed)
python3 tools/release_gate.py
```

CMake refuses to build against a different or modified mGBA checkout.

### Release metadata

```text
versionName = 1.0.0
versionCode = 451
minSdk      = 26
```

## Project structure

```text
Retra/
├─ app/src/main/
│  ├─ assets/retra/      WebView interface (HTML, CSS, JS)
│  ├─ cpp/               Native mGBA bridge and CMake config
│  └─ java/…/emulator/   Android controllers, storage, and runtime
├─ docs/                 Architecture, release, and engineering notes
├─ tests/                Lightweight regression tests
├─ third_party/          mGBA source location
└─ tools/                Setup and release-validation scripts
```

## Documentation

| Document | What it covers |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Runtime architecture and module boundaries |
| [`docs/PERSISTENT_STORAGE_ARCHITECTURE.md`](docs/PERSISTENT_STORAGE_ARCHITECTURE.md) | How saves, metadata, and backups are stored |
| [`docs/CONTROLLER_FEEDBACK.md`](docs/CONTROLLER_FEEDBACK.md) | Controller sound and haptics |
| [`docs/README.md`](docs/README.md) | Full documentation index |
| [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) | Common problems and how to report bugs |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | How to contribute |
| [`SECURITY.md`](SECURITY.md) | Reporting security issues |

## License

Retra is released under the **Mozilla Public License 2.0** — see [`LICENSE`](LICENSE).

Retra uses the separately maintained [mGBA](https://mgba.io/) core, which keeps its own copyright and MPL-2.0 notices. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

Game content, BIOS files, artwork, and trademarks belong to their respective owners and are not distributed with Retra.

---

<div align="center">

Made by **Zense** · Powered by mGBA

</div>
