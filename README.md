# Retra!

An open-source Android emulator for **Game Boy Advance** (mGBA) and **RPG Maker XP/VX/VX Ace** (mkxp-z) games, with a Mihon-style library.

- **Library:** add GBA/GB/GBC ROMs, RPG Maker game folders (the one with `Game.ini`) or `.zip`/`.mkxpz` archives, and the RPG Maker XP RTP.
- **Play:** on-screen gamepad, save states, battery saves, save import/export.
- **Browse:** search the game repos you add and download straight into your library.

Retra ships **no game sources and no games**. Only add repos and play games you have the right to.

## Building

Open the project in Android Studio and run it. The mGBA core is in `app/src/main/jniLibs/`.
The mkxp-z core is built by the **Build mkxp-z core** GitHub Actions workflow (`.github/workflows/mkxp-z-core.yml`).
Copy its artifacts into `app/src/main/jniLibs/<abi>/`.

## Writing a repo

A repo is a JSON file served over **https**. Users add its URL in **Browse → Add repo**.

```json
{
  "name": "My Homebrew Repo",
  "games": [
    {
      "title": "Space Demo",
      "url": "roms/space-demo.gba",
      "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
      "description": "A homebrew shoot-em-up"
    },
    { "title": "My Fan RPG", "url": "https://cdn.example.org/my-fan-rpg.mkxpz" }
  ]
}
```

| Field | Required | Meaning |
|---|---|---|
| `url` | yes | Download link, absolute or relative to the repo URL. Must be https. |
| `title` | no | Shown in Browse. Defaults to the file name. |
| `file` | no | File name to save as. Defaults to the last part of `url`. Must end in `.gba`, `.gb`, `.gbc`, `.zip`, `.mkxpz` or `.7z`. |
| `sha256` | no | Hex SHA-256 of the file. If it's set and the download doesn't match, Retra discards the download. |
| `description` | no | One line shown under the title. |

Entries with a non-https link or an unsupported file type are skipped.
RPG Maker games are best packed as `.mkxpz` or `.zip`, with the `.rgssad`/`.rgss2a`/`.rgss3a` archives stored uncompressed.

## License

GPL-3.0. Uses [LibretroDroid](https://github.com/Swordfish90/LibretroDroid) (GPL-3.0),
[mGBA](https://mgba.io) (MPL-2.0) and [mkxp-z](https://github.com/mkxp-z/mkxp-z) (GPL).
