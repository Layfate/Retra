# Retra developer docs

Everything a maintainer needs in one page. For what Retra does, see the [project README](../README.md). For what changed in each version, see the [changelog](../CHANGELOG.md) and [GitHub Releases](https://github.com/Layfate/Retra/releases).

- [Architecture](#architecture)
- [Persistent data](#persistent-data)
- [Controller feedback](#controller-feedback)
- [Google Drive setup](#google-drive-setup)
- [Building and testing](#building-and-testing)
- [Releasing](#releasing)
- [Version history](#version-history)

## Architecture

The app is a native Android shell around a packaged WebView interface.

```text
Web UI (app/src/main/assets/retra: HTML/CSS/JS, split by feature)
  │  JavaScript bridge
  ▼
MainActivity: lifecycle and wiring only (kept under 1,500 lines)
  ├─ Gameplay      EmulationSessionManager, GameplayController, GameplayLayoutController,
  │                GameplayTouchController, ControllerFeedbackManager, AudioController
  ├─ Data          RomIdentityStore, SaveDataRepository, SaveStateRepository,
  │                BackupRepository, GameplayLayoutRepository, RetraPreferences
  ├─ Multiplayer   MultiplayerController, RemoteLinkTransport
  └─ Services      GoogleDriveApiRepository, ArtworkRepository, AppUpdateController
  ▼
Native mGBA core (JNI)
```

- **The Web UI only presents.** Storage and gameplay behavior stay native and cross the bridge through `WebUiController` and the bridge methods.
- **Features live in controllers and repositories, not in `MainActivity`.** Shared models (`NativeLibraryItem`, `PendingPatchLaunch`, …) are top-level in `RetraModels.kt`.
- **Emulation speed and presentation are independent.** `EmulationSessionManager` runs the core at the exact speed (0.2×–16×). `GameplayFrameMailbox` keeps four preallocated frame buffers and always replaces an older pending frame, so fast-forward can't build a backlog. `GameplayFramePresenter` draws the newest frame on each VSync through `ShaderGameView` (OpenGL). `DisplayPerformanceManager` picks a display mode that keeps pacing even, so the interface stays responsive.
- **Audio never blocks emulation.** `AudioController` writes to `AudioTrack` on its own thread and resets its resampler when the speed changes.
- **Input comes before feedback.** `GameplayTouchController` owns pointers, D-pad hysteresis and key state. Haptics and sound run after the key reaches the core.

Architecture tests under `tests/` enforce these boundaries and the size limits.

## Persistent data

```text
romId    = permanent key (from the SHA-256 of the playable ROM bytes)
Room     = library metadata (database retra_rom_identity.db)
Files    = per-ROM user data under persistent_data/<folder>/<romId>/
DataStore = app-wide settings
```

- **Identity.** A ROM's file name, path and URI can change; its `romId` doesn't. A cache keyed on `uri + size + lastModified` skips rehashing unchanged files. Patched ROMs are hashed after patching. Older identity hashes are kept as aliases so upgrades never assign a new `romId`.
- **Per-ROM folders.** `Saves`, `SaveStates`, `Cheats` (`cheats.json`), `Config` (`config.json`), `Layouts` (`portrait.json` / `landscape.json`), `Covers`, `Backgrounds`, `Backups`. Removing a ROM from the Library keeps them. Only **Delete Game Data** removes them.
- **Crash-safe import.** Each import writes a journal that moves `PREPARING → ROM_READY → DATABASE_COMMITTED → COMPLETE`. On startup, unfinished imports are rolled back or completed. Copies are staged, verified, fsynced and atomically replaced.
- **Battery saves** use verified temporary writes, recovery of interrupted sessions and rotating backups.
- **Backups** (`.retra`) carry saves, states, settings, layouts, covers, statistics and playtime. BIOS files and paths stay on the device.

## Controller feedback

- **Haptics:** one short, light pulse per press. The D-pad ticks on press and on each direction change, never while held. Rate-limited so rolling and chords don't buzz.
- **Sound:** a short click at media volume, with its own rate limit.
- Both are on by default and can be turned off separately in Settings.

## Google Drive setup

Cloud backup uses Google Identity Services plus Drive API v3 with only the `https://www.googleapis.com/auth/drive.file` scope. Backups go to `My Drive / Retra Backups /`. No client secret ships in the app.

1. In Google Cloud, enable **Google Drive API**.
2. Configure the OAuth consent screen (app name Retra, the `drive.file` scope). While it's in Testing, add every tester's Google account as a test user.
3. Create an **Android OAuth client** for package `com.retra.emulator` with the **SHA-1 of the certificate that signed the installed APK**. Add a client for each certificate you use: debug, release, and Play App Signing if published on Play.
4. In Retra, run **Backup Now**, then check that one `Retra Backups` folder with a `.retra` snapshot appears in Drive. A second backup must reuse that folder.

If sign-in fails, the installed APK's SHA-1 almost always doesn't match the OAuth client, or the account isn't a test user.

## Building and testing

Requirements: Android Studio, JDK 17, the project's SDK/NDK/CMake versions, and Python 3.

```bash
python3 tools/prepare_mgba.py          # fetch the mGBA revision pinned in third_party/mgba.lock
node --test tests/*.test.cjs           # web, contract and architecture tests
python3 tools/release_gate.py          # syntax checks, tests and release-file checks
./gradlew :app:testDebugUnitTest       # Kotlin unit tests
./gradlew :app:connectedDebugAndroidTest   # instrumentation tests on a device
```

CI (`.github/workflows/regression.yml`) runs the tests, Kotlin compile and lint on every push.

## Releasing

1. Bump `versionName` / `versionCode` in `app/build.gradle.kts`, the version strings the tests check, the About page and the README.
2. Add a `CHANGELOG.md` entry. Its text is also the GitHub release body.
3. Build with `./gradlew :app:assembleRelease`. It must be signed with the production release key: phones only accept an update signed with the same key. Check it with `apksigner verify --print-certs`.
4. Smoke-test on a real phone. Check a fresh install and an upgrade that keeps the library, saves and settings, plus battery saves, save states and fast-forward speeds. Also cover portrait/landscape layouts, and Google Drive and Link if they changed.
5. Tag the tested commit and publish with `gh release create vX.Y.Z`, attaching `Retra-vX.Y.Z.apk`.

## Version history

| Version | Highlights |
|---|---|
| v1.0.5 | One extension per website, redesigned Settings and Statistics, reliable Google Drive backups, update screen, consistent dialogs |
| v1.0.4 | More sources with real logos, stronger controller haptics and sound, unified controller look, smoother interface on more phones |
| v1.0.3 | ROM title editing and storage polish |
| v1.0.2 | Update checker and reinstall recovery |
| v1.0.1 | Create and restore backups |
| v1.0.0 | First stable release |
| v0.8–v0.9 | Pre-release development: core emulator, library, Screen Editor, shaders, Drive sync, Link, architecture refactor |

Full details are in the [changelog](../CHANGELOG.md). Older engineering notes are in the git history.
