# Troubleshooting

Common problems and the first things to try before opening an issue. Retra does not include ROMs or BIOS files; use only files you are legally allowed to use.

## Before you start

1. Update to the [latest release](https://github.com/Layfate/Retra/releases/latest).
2. Fully close Retra and reopen it. Restart the device if the problem continues.
3. Try another known-good ROM to tell a game-specific problem from an app-wide one.
4. Before reinstalling or changing storage, create a backup: **More → Data and Storage → Create backup**.

## Installing and updating

- **Android version.** Retra requires Android 8.0 (API 26) or newer.
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`.** The installed copy was signed with a different key. Create a backup, uninstall it, install the official release, then restore the backup.
- **Update not detected.** Retra checks GitHub Releases at launch and again when the connection returns. Use **Settings → About → Check for updates**, or open the [releases page](https://github.com/Layfate/Retra/releases/latest) directly.

## A game won't start, shows a black screen or freezes

1. Set **Settings → Display → Video shader** to **None**.
2. Leave **Settings → Advanced → Cartridge save type** on **Automatic** unless the game needs an override.
3. If **Use BIOS** is on, turn it off and try again.
4. If an IPS, UPS or BPS patch is applied, test the unpatched ROM.

## Gameplay or fast-forward isn't smooth

- Test at **1×** first, then each fast-forward speed separately.
- Set **Video shader** to **None** while testing.
- Close demanding background apps and exclude Retra from battery optimization.
- At 8× and 16× Retra skips drawing frames, never game logic. Sustained high speeds can hit thermal limits, so test again once the device has cooled.

## Audio problems

- Return to **1×**: fast-forward and slow motion change audio timing on purpose.
- Check **Settings → Sound & haptics → Enable sound** and **Mute while fast-forwarding**.
- If a problem only happens when switching speeds, note the exact sequence (for example `1× → 16× → 1×`) in your report.

## On-screen controls

Portrait and landscape layouts are separate. Open **Settings → Display → Screen size** and rotate the device to edit the layout you want. Drag to move, use the handle to resize, **+** to add controls, and **Reset layout** to start over. Layout profiles are under **Settings → Controls**. Button sounds and D-pad vibration are under **Settings → Sound & haptics**.

## Missing saves or save states

Don't start a new save over the old one.

1. Make sure it's the same ROM (same revision, and the same patch if one was used). Saves follow the ROM's content, not its file name.
2. To import a battery save, open the game, then **Menu → Import save** and pick the matching `.sav` or `.srm`.
3. To restore a backup, use **More → Data and Storage → Restore backup**.

Removing a game from the Library keeps its data. Only **Delete Game Data** erases it.

## Backups and Google Drive

- **Local backups.** `.retra` files hold saves, save states, cheats, library data, layouts, artwork, statistics and settings. They never include ROMs or BIOS files. Save them somewhere ordinary such as Downloads or Documents.
- **Google Drive.** Open **More → Data and Storage**, connect your account, then use **Backup Now** or **Restore from Google Drive**. Backups are stored in **My Drive / Retra Backups**. Automatic backups wait for a network, or for Wi-Fi when **Back up on Wi-Fi only** is on.
- Keep a local backup as well if your saves matter to you.

## Cheats

Turn on **Settings → Gameplay → Enable cheats**, then manage codes from the in-game **Menu → Cheats**. A code must match the game's region and revision. Changes a cheat made to a game's own save can't always be undone. Cheats are unavailable during Local Link.

## BIOS

Retra doesn't need a BIOS. To use one, open **Settings → Advanced**, turn on **Use BIOS** and select the file. Local Single-Pak / Multiboot requires a user-provided 16 KiB GBA BIOS.

## Cover art

Automatic cover art needs a connection; if **Cover art on Wi-Fi only** is on, connect to Wi-Fi. Use **Settings → Library → Find missing covers** to retry. A cover you set yourself always takes priority.

## Link multiplayer

- Both players need compatible game versions. Set speed to **1×** and keep both devices awake with Retra in the foreground.
- **Wi-Fi Remote Link:** use the same local network, without a VPN, client isolation or a restrictive hotspot.
- **Bluetooth Remote Link:** turn Bluetooth on and grant Retra's Bluetooth permissions on both devices.
- Remote Link emulates the Link Cable (Multi-Pak). The GBA Wireless Adapter isn't supported.

## Reporting a bug

Search the [existing issues](https://github.com/Layfate/Retra/issues) first. A good report includes:

- Retra version, Android version and device model
- System (GB, GBC or GBA), and whether the ROM is patched
- Exact steps to reproduce, the speed in use, and portrait or landscape
- Any error message, plus a screenshot or recording if useful

Use a specific title, such as *"Audio crackles after returning from 16× to 1× on Android 15"*. Never attach ROMs, BIOS files, private saves, account details or signing keys to a public issue.
