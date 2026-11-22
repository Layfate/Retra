# Retra v1.0.3

One-tap downloads for ROM hack sources, RetroArch shaders, and a proper game page for downloaded games.

## Changes

- **Download really downloads.** Tapping **Download** on a ROM hack no longer opens the website for you to find the link. Retra reads the game page, picks the right download button, follows MediaFire, MEGA, Google Drive, GitHub releases or direct links, checks the file is a GB, GBC, GBA, ZIP or patch file, and downloads it in the background. If one link is dead it tries the page's mirror. In a check of every ROM hack in the PokéCoders, PokéHarbor, PokeMundo and Operation iDroid catalogs, 1,427 of 1,607 downloaded a verified file. Pages without a single file (lists like "Best ROM Hacks", forum-only or online-patcher pages), dead links and `.rar` archives still open the website. Commercial game catalogs keep opening the website only.
- **Downloads in progress.** Browse → Downloads now shows downloads while they run, with a live progress bar and Pause, then moves them to the history when they finish. The game page shows "Finding the download…" while Retra looks for the link, and progress updates no longer make the page or bar stutter.
- **Downloaded games open their game page.** Tapping a downloaded catalog game in your Library opens the same page as your other games, with **Resume** and **Recent saves**. Import ROM and Download are hidden once a game is installed, including on its Browse page.
- **Covers in History.** Games added from Browse now show their cover art in the History tab, like on their Library card.
- **RetroArch shaders.** Settings → Video shader → **Import file** or **Import folder** accepts RetroArch shader packs: a `.zip` or folder of `.slangp` / `.glslp` presets with their shaders, includes and textures, or a single `.slang` / `.glsl` shader. Multi-pass presets, aliases, history and feedback frames, LUT textures and preset parameters are supported. Slang shaders are compiled with glslang and SPIRV-Cross, the same way RetroArch's GL core driver does it. Of the 3,285 presets in libretro's official slang and glsl packs, 3,180 compiled and ran on Android's GPU driver, including all 234 handheld presets. Large packs get a search box and a Remove button. If a preset cannot run on your GPU, Retra explains why and falls back to the normal renderer.

## Install

**v1.0.3 is signed with a new release key, so it cannot install over v1.0.2.** Before updating:

1. In v1.0.2, open Settings → Backup, restore, storage → **Create backup** and save the `.retra` file somewhere outside the app (Downloads, a PC, or cloud storage you open yourself).
2. Uninstall v1.0.2.
3. Download **Retra-v1.0.3.apk** and open it on your Android device (Android 8.0 or newer).
4. In v1.0.3, choose **Restore backup** and pick the `.retra` file.

Google Drive backup in v1.0.3 needs the new signing key registered with Retra's Google Cloud project first; use a local `.retra` backup to move your data. Future updates install over v1.0.3 normally.

Version: `1.0.3` / Android versionCode `454`.
