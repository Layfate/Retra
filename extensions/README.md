# Retra extension repository

Catalog extensions for Retra (extension API 1). Add this URL in Retra under **Browse → Extensions → ⋮ → Extension repos → Add**:

```
https://raw.githubusercontent.com/Layfate/Retra/main/extensions/index.json
```

## Extensions

- **Romhack Plaza**: GB, GBC and GBA ROM hacks and fan translations listed on romhackplaza.org, plus catalogs of official GB, GBC and GBA games with box art.

Everything here is catalog data. Commercial games are listed for your Library only, with no download links. Add a game, then link your own cartridge dump with Import ROM. Romhack Plaza entries link to the page hosting the hack's patch. Apply it to your own clean ROM from Library → +.

Covers come from libretro-thumbnails, and game details from libretro-database.

These files are published from `extensions/` on `main` and refreshed weekly by `tools/extension-repo/build-repo.cjs`. Do not edit them by hand.
