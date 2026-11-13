# Retra catalog extension API 1

Retra extensions supply **catalog data**. Emulator cores remain part of Retra. API 1 installs JSON manifests, not APKs, DEX, Kotlin, JavaScript, or native code. Mihon extensions are incompatible. No additional runtime dependency or Android installation permission is needed. This follows [Android's guidance on dynamic code loading](https://developer.android.com/privacy-and-security/risks/dynamic-code-loading).

## Publish the working homebrew sample

The sample is a CC0 Game Boy cartridge: use the D-pad to move a square. It includes program source, ROM, catalog, extension manifest, repository index, and a license. It is packaged **only in the Android test APK**, never in production Retra.

With no arguments the generator rebuilds the Android test fixture in `app/src/androidTest/assets/browse/` (its `example.org` addresses are placeholders, not live hosting). To build a publishable repository, pass the public HTTPS address it will be served from and an output folder:

```sh
node samples/catalog-extension/build-fixture.cjs https://raw.githubusercontent.com/OWNER/REPO/repo/ ../retra-repo
```

The official Retra repository is served from the `repo` branch of this project (the same layout Mihon's keiyoushi repo uses):

```
https://raw.githubusercontent.com/Layfate/Retra/repo/index.json
```

Publish every generated file (index, package, catalog, ROM, artwork, license) at the top level of that branch. The generator recalculates both hashes; any JSON change needs a regenerated package hash, so always rebuild rather than hand-editing. In Retra open Browse → Extensions → ⋮ → Extension repos → Add, paste the `index.json` URL, install the extension (confirming trust), then open Sources. Select Retra Pad, add it to Library, download it, and play.

Mihon and Tachiyomi repositories (`index.pb`, APK extension lists) are not compatible; Retra explains this when one is added.

## Repository index

The URL must point to a public HTTPS JSON index. A trailing slash appends `index.json`. GitHub `/blob/` links are converted to raw content links. Authentication, private network addresses, cleartext HTTP, URL credentials, non-443 ports, and non-HTTPS redirects are rejected. Indexes are capped at 2 MiB and 500 extensions; duplicate IDs within an index are rejected.

```json
{
  "schemaVersion": 1,
  "name": "Your repository",
  "extensions": [{
    "id": "org.example.homebrew",
    "name": "Homebrew catalog",
    "publisher": "Your publisher name",
    "versionCode": 1,
    "versionName": "1.0",
    "format": "retra.catalog",
    "apiMin": 1,
    "apiMax": 1,
    "platforms": ["GB", "GBC", "GBA"],
    "packageUrl": "https://example.org/extension.json",
    "sha256": "64-lowercase-hex-digits-of-extension-json",
    "icon": "https://example.org/icon.png"
  }]
}
```

IDs use 1–128 ASCII letters, numbers, `.`, `_`, or `-`, starting with a letter or number. IDs are permanent. `versionCode` is a positive, monotonically increasing integer. `versionName` and `icon` are optional. `format`, API range, package hash, and manifest identity are checked before installation. Updates cannot change the originating repository for an installed ID or downgrade it. Conflicting IDs in different repositories cannot replace one another. Unsupported formats or API ranges are displayed as incompatible.

Repositories start untrusted. Trust is an explicit user decision tied to the complete index URL. Publisher names are self-declared; SHA-256 verifies package integrity against the HTTPS index, not independent publisher identity. API 1 has no executable signing identity to verify. Revoking trust or removing the repository blocks new installs and ROM downloads. Installed sources and saved metadata remain available; uninstalling removes sources but preserves Library entries, ROMs, and saves.

## Extension package

```json
{
  "apiVersion": 1,
  "id": "org.example.homebrew",
  "versionCode": 1,
  "sources": [{
    "id": "homebrew",
    "name": "Homebrew",
    "platforms": ["GB"],
    "latest": true,
    "catalogUrl": "https://example.org/catalog.json",
    "icon": "https://example.org/icon.png"
  }]
}
```

Packages are capped at 2 MiB and 50 sources. A source's stable key is `extensionId/sourceId`. Each source's platforms must be a subset of the extension's platforms. API 1 has no extension settings or executable adapter hooks, so no settings button appears.

## Catalog

API 1 fetches one bounded static JSON catalog per source, capped at 8 MiB and 10,000 games. The Kotlin `GameSource` adapter provides popular, latest, search, filtering, details, and 30-item pages over that data. UI pages and images load on demand. This supports static GitHub hosting without a server. `latest` sorts by numeric `updatedAt` (Unix milliseconds); popular sorts by numeric `popularity`, descending. Equal ranks retain publisher order. Search matches title without case sensitivity. Filter values are exact matches without case sensitivity; comma-separated metadata values are supported. Empty filters match everything.

```json
{
  "apiVersion": 1,
  "games": [{
    "id": "permanent-source-game-id",
    "title": "Your homebrew game",
    "platform": "GB",
    "developer": "Developer",
    "publisher": "Publisher",
    "year": "2026",
    "genre": "Puzzle",
    "language": "en",
    "region": "World",
    "gameType": "homebrew",
    "popularity": 10,
    "updatedAt": 1794528000000,
    "description": "Plain text only.",
    "cover": "https://example.org/cover.png",
    "screenshots": ["https://example.org/screen.png"]
  }]
}
```

Only `id`, `title`, and `platform` are required per game. Platforms are GBA, GBC, GB, matching Retra's existing mGBA integration. Covers and screenshots are downloaded natively, decoded to bounded raster images, and served from app-private storage; remote HTML and SVG are not rendered. Image inputs are limited to 4 MiB and 4096×4096 pixels, with a 128 MiB disk cache. Optional `romSha256` lets Retra recognize a matching ROM imported through the normal Library + button. Previously cached catalogs work offline; normal cache lifetime is 15 minutes, and Refresh catalog bypasses it. Game details are embedded in the catalog. Pagination stays mounted when opening details, preserving scroll position.

## Authorized downloads

Omit `download` for catalog-only games, including commercial titles without distribution permission. To offer a game you are authorized to distribute, add:

```json
"download": {
  "url": "https://example.org/game.gb",
  "fileName": "game.gb",
  "sha256": "64-hex-digits-of-the-rom",
  "size": 32768,
  "authorized": true,
  "license": "CC0-1.0",
  "rightsUrl": "https://example.org/LICENSE.txt"
}
```

Recognized licenses: `CC0-1.0`, `CC-BY-4.0`, `CC-BY-SA-4.0`, `public-domain`, `homebrew-freeware`. Retra displays the rights URL and license and asks for confirmation. This is a publisher attestation; Retra cannot independently establish copyright ownership. Do not list unauthorized commercial ROM downloads.

Files must be direct `.gb`, `.gbc`, or `.gba` downloads, matching the game's platform; maximum size is 128 MiB. Retra enforces the published size and SHA-256 before passing the ROM to the existing journaled import path. The original Library + importer retains archive, patch, and `.mgba` support. The catalog Import ROM action uses Android's Storage Access Framework and accepts a direct matching platform ROM; if a hash is known, it must match. Existing ROM IDs, favorites, categories, and saves are preserved when reconnecting by hash. Source identity and game metadata remain separate from the local ROM URI.

Downloads run while the Activity exists. Pause, process death, or connection loss keeps the partial file and persistent state. Tap Download again to resume using HTTP Range; servers returning a complete HTTP 200 response restart safely. Hash validation still covers the entire result. Retra does not automatically restart network transfers or install executable packages in the background. A foreground service/WorkManager integration and server-side catalog adapters can be added if larger catalogs or background downloads are needed.

## Verification

```sh
node --test tests/*.test.cjs
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.retra.emulator.BrowseInstrumentedTest
```

The Android suite uses a fake HTTPS transport and the real Room stores and ROM importer. It covers protocol rejection, trust, installation, compatibility, source operations, search/filter/page boundaries, offline fallback, database reopen, interrupted/resumed and corrupt downloads, duplicate prevention, metadata retention, platform mismatch, and navigation presence. No external catalog or commercial ROM is needed.
