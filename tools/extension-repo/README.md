# Retra website sources

The default index contains one extension, **Retra Sources** (4.0). It replaces the former Romhack Plaza package and keeps its extension ID (`org.retra.romhackplaza`), so installed copies update in place and saved Library links keep working. The Homebrew Hub and sample extensions are omitted from the default index.

| Source | Platforms | How it is built |
|---|---|---|
| Roms Games | GB, GBC, GBA | Official releases from the No-Intro lists, with available libretro box art. A game's website button opens a title search on Roms Games; listing a release does not guarantee that website has it. |
| PokéHarbor | GBC, GBA | Hack catalogs from its public WordPress API, including featured artwork. |
| Operation iDroid | GBC, GBA | Hack pages from its sitemap, read from each page's schema.org JSON-LD (title, platform, author, version, cover). NDS hacks are skipped. |
| Romhacking.net | GB, GBC, GBA | Website source with an empty catalog. The site is behind a Cloudflare bot check, so the builder does not fetch it. |
| Hackdex | GB, GBC, GBA | Website source with an empty catalog. The site is behind a Vercel bot check, so the builder does not fetch it. |

A website source's empty catalog page shows an **Open <site>** button, and a catalog search with no matches offers **Search <site> for "…"** when the source has a search URL.

In the updated Retra app, search the catalogs normally. Choose **Download on website** on a game, or **⋮ → Search website** in a source, to browse inside Retra. Supported ROM, ZIP and patch downloads return to Library. Patches need a compatible base ROM. Website pages cannot access Retra's app bridge or local files.

Only downloads explicitly described by a catalog's size, SHA-256 and redistributable license use the existing verified direct-download path. Website downloads are separate, size-limited user downloads; commercial files are not relabeled as openly licensed. Download mirrors, CAPTCHA challenges, unsupported archive formats and missing artwork depend on the source website. Closing the website cancels an active download; website downloads do not resume after process death.

Build the files into a disposable output directory:

```sh
node tools/extension-repo/build-repo.cjs https://raw.githubusercontent.com/Layfate/Retra/main/extensions/ build/extension-repo
```

The builder replaces the output directory's contents. Publish the complete generated tree in `extensions/` on `main`, preserving the base URL used above. The scheduled workflow rebuilds that directory weekly; it can also run manually. The project uses only the `main` branch.

The catalog index is now at:

```text
https://raw.githubusercontent.com/Layfate/Retra/main/extensions/index.json
```

If you added the former `repo` branch URL, uninstall its extension, remove the old repository, then add the new URL and reinstall the extension. Existing saved repository URLs cannot redirect after the old branch is removed. Your imported games and saves remain in Library.
