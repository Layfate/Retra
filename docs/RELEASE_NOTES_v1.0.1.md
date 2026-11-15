# Retra v1.0.1

A smoothness and polish update for Retra v1.0.0.

## Changes

- **Edge-to-edge game details.** The Information list on Browse game and extension details pages now reaches both sides of the screen, matching the Settings detail pages, while text stays aligned with the section headings.
- **Faster first screen.** Web fonts load without blocking the first paint. On slow or offline networks the interface appears immediately in the system font and switches to the selected font once it has loaded.
- **Lighter interface rendering.** The interface WebView no longer renders into an extra full-screen offscreen layer, saving a composite pass and GPU memory on every frame.
- **Smoother scroll start and stop.** Scroll tracking no longer toggles a document-wide class, which forced a full style recalculation at the start and end of every scroll gesture.
- **No pauses when saving interface preferences.** Interface preferences are written in the background instead of blocking the interface on a disk write.
- **Working update check and Help Center link.** The in-app update check and the Help Center link now point to the official `Layfate/Retra` repository.

## Install

Download **Retra-v1.0.1.apk** and open it on your Android device (Android 8.0 or newer). It installs over v1.0.0 and keeps your library, saves and settings.

Note: Retra v1.0.0's built-in update check pointed to a repository that does not exist, so v1.0.0 will not announce this update. Install v1.0.1 manually once; later updates will be detected in the app.

Version: `1.0.1` / Android versionCode `452`.
