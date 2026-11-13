<div align="center">

<img src="docs/images/retra-logo.webp" alt="Retra logo" width="128">

# Retra

**Game Boy, Game Boy Color, and Game Boy Advance on Android.**

An open-source emulator powered by [mGBA](https://mgba.io/), with a game library, customizable controls, and built-in source browsing.

[![Version](https://img.shields.io/badge/version-v1.0.0-E0457B)](https://github.com/Layfate/Retra/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#download-and-install)
[![License](https://img.shields.io/badge/license-MPL--2.0-blue.svg)](LICENSE)

[**Download APK**](https://github.com/Layfate/Retra/releases/latest) ·
[Features](#features) ·
[Browse setup](#browse-setup) ·
[Documentation](docs/README.md)

</div>

## Download and install

1. Download **Retra-v1.0.0.apk** from the [latest release](https://github.com/Layfate/Retra/releases/latest).
2. Open it on your Android device and allow installation from that source if prompted.
3. Launch Retra, tap **+** in Library, and select your games.

Requires **Android 8.0 or newer**. A GBA BIOS is optional, except for Single-Pak receivers.

Retra does not include ROMs or BIOS files. Use games and files you are legally allowed to use.

## Features

| | What you can do |
|---|---|
| **Play** | Run GB, GBC, and GBA games with up to 16× fast-forward, slow motion, cheats, ROM patches, and shaders. |
| **Organize** | Manage your library with cover art, categories, search, sorting, history, and playtime statistics. |
| **Browse** | Add source extensions, search catalogs, view game details, and open download websites inside Retra. |
| **Save** | Use battery saves, save states, auto-resume, `.sav` / `.srm` import, portable `.retra` backups, and optional Google Drive backup and restore. |
| **Customize** | Edit button positions, sizes, and opacity with separate portrait and landscape layouts. |
| **Connect** | Use Local Link, Single-Pak / Multiboot, or Remote Link over Wi-Fi or Bluetooth. |

Your saves, covers, and layouts stay associated with a game when its ROM is moved, renamed, or imported again.

## Browse setup

1. Open **Browse → Extensions → ⋮ → Extension repos → Add**.
2. Paste the repository URL below and review the trust prompt.
3. Install the listed extension, then open **Sources** to browse its catalogs.

```text
https://raw.githubusercontent.com/Layfate/Retra/repo/index.json
```

Search across enabled sources from the **Search** tab. See the [source guide](tools/extension-repo/README.md) for supported websites and download behavior.

## Build from source

<details>
<summary>Developer setup</summary>

Use Android Studio with JDK 17 and the project's Android SDK, NDK, and CMake versions. Python 3 is needed to prepare the mGBA core.

```bash
git clone https://github.com/Layfate/Retra.git
cd Retra
python3 tools/prepare_mgba.py
```

On Windows, use `python` if `python3` is unavailable. The setup script fetches the mGBA revision pinned in [`third_party/mgba.lock`](third_party/mgba.lock).

Open the project in Android Studio, let Gradle sync, and run it on a device or emulator.

See [Contributing](CONTRIBUTING.md) for validation commands and development guidelines.

</details>

## Help and contributing

- [Troubleshooting](TROUBLESHOOTING.md) — installation, gameplay, saves, and multiplayer help.
- [Documentation](docs/README.md) — architecture, storage, and development notes.
- [Changelog](CHANGELOG.md) — release history.
- [Contributing](CONTRIBUTING.md) — development guidelines and checks.
- [Security](SECURITY.md) — how to report a vulnerability.

## License

Retra is released under the [Mozilla Public License 2.0](LICENSE). The mGBA core retains its own copyright and license notices; see [Third-party notices](THIRD_PARTY_NOTICES.md).

<div align="center">

Made by **Zense** · Powered by mGBA

</div>
