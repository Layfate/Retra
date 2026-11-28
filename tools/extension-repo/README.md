# Retra website sources

The default index lists **one extension per website** (`org.retra.<site>`, for example `org.retra.operationidroid`). Each extension holds that website's GB, GBC and GBA sources, so people install only the sites they want under **Browse → Extensions**, and the installed sites then appear in **Sources**. Packages are published as `packages/<site>.json`; catalogs and logos stay in `sources/`.

These extensions replace the former all-in-one **Retra Sources** bundle (`org.retra.romhackplaza`). The source IDs did not change, so the app maps saved Library links and pins from the bundle (`org.retra.romhackplaza/<source>`) to the same source in the per-site extension. Once a refreshed repository no longer lists the bundle, Retra uninstalls it and points the user to Extensions. The Homebrew Hub and sample extensions are omitted from the default index.

| Source | Platforms | How it is built |
|---|---|---|
| Roms Games | GB, GBC, GBA | Official releases from the No-Intro lists, with available libretro box art. A game's website button opens a title search on Roms Games; listing a release does not guarantee that website has it. |
| PokéHarbor | GBC, GBA | Hack catalogs from its public WordPress API, including featured artwork. |
| PokeMundo | GBC, GBA | Hack catalogs from its public WordPress API, including featured artwork. |
| PokéCoders | GBC, GBA | Hack catalogs (its GBA and GBC ROM Hacks categories) from its public WordPress API, including featured artwork. |
| PokéMerald | GBC, GBA | Hack catalogs (its GBA and GBC categories) from its public WordPress API, including featured artwork. |
| Operation iDroid | GBC, GBA | Hack pages from its sitemap, read from each page's schema.org JSON-LD (title, platform, author, version, cover). NDS hacks are skipped. |
| GameBrew | GBA | GBA hack and translation pages from its public MediaWiki API, read from each page's infobox (title, author, version, date, description) with the page image. |
| FE Universe | GBA | Completed Fire Emblem projects on an FE6, FE7 or FE8 base, from its public Discourse JSON, with the topic image. |
| Romhacking.net | GB, GBC, GBA | Website source with an empty catalog. The site is behind a Cloudflare bot check, so the builder does not fetch it. |
| Hackdex | GB, GBC, GBA | Website source with an empty catalog. The site is behind a Vercel bot check, so the builder does not fetch it. |
| RHDI | GB, GBC, GBA | Website source with an empty catalog. The Romhacking Database Initiative loads its listings over a websocket, with no public listing to read. |
| PokéCommunity | GB, GBC, GBA | Website source with an empty catalog, opening the Completed ROM Hacks Showcase forum. The site blocks automated requests, so the builder does not fetch it. |

Each source shows its website's own logo. Logos the builder cannot or should not fetch on every rebuild are kept in `tools/extension-repo/icons/`; the others are downloaded from the site.

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
