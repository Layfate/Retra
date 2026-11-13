# Retra website sources

The default index contains one extension, **PokéHarbor · PokeMundo · Roms Games** (3.0). It upgrades the former Romhack Plaza package using the same extension ID. The Homebrew Hub and sample extensions are omitted from the default index.

- PokéHarbor and PokeMundo: GBA and GBC hack catalogs from their public WordPress APIs, including featured artwork.
- Roms Games: official GB, GBC and GBA releases from the No-Intro lists, with available libretro box art. A game's website button opens a title search on Roms Games; listing a release does not guarantee that website has it.

In the updated Retra app, search the catalogs normally. Choose **Download on website** on a game, or **⋮ → Search website** in a source, to browse inside Retra. Supported ROM, ZIP and patch downloads return to Library. Patches need a compatible base ROM. Website pages cannot access Retra's app bridge or local files.

Only downloads explicitly described by a catalog's size, SHA-256 and redistributable license use the existing verified direct-download path. Website downloads are separate, size-limited user downloads; commercial files are not relabeled as openly licensed. Download mirrors, CAPTCHA challenges, unsupported archive formats and missing artwork depend on the source website. Closing the website cancels an active download; website downloads do not resume after process death.

Build the files into a disposable output directory:

```sh
node tools/extension-repo/build-repo.cjs https://raw.githubusercontent.com/Layfate/Retra/repo/ build/extension-repo
```

The builder replaces the output directory's contents. Publish the complete generated tree on the `repo` branch, preserving the base URL used above. The scheduled workflow rebuilds it weekly; it can also run manually. Existing users refresh their repository and update the installed extension. The new in-app browser also requires the updated APK.
