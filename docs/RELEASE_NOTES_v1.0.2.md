# Retra v1.0.2

A Browse update: a Downloads tab, more ROM hack sources, and clearer in-app downloads.

## Changes

- **Smooth 120 Hz scrolling.** Scrolling source catalogs, Library and lists is much smoother. On a 120 Hz phone, a 1,000-game source grid went from a median 65 ms per frame (about 16 fps) to 10 ms, with under 3% late frames. Retra no longer applies an invisible zero-strength blur to every card and button, no longer reserves a GPU layer for every cover, and skips drawing off-screen catalog cards.
- **Downloads tab.** Browse now has a Downloads tab listing every ROM and patch you downloaded in Retra, newest first, with cover, source, system, size and time. Play a game straight from the list, open its details, or remove entries. Clearing the list never removes games from your Library.
- **Pinned source header.** On a source page, the title bar and the Popular / Latest / Filter chips stay at the top while you scroll the games, with a thin divider once games scroll beneath them. The Browse and Library tabs no longer let rows show through a strip above them while scrolling, and in Translucent mode the top bars and pinned rows match the page color instead of showing as a darker band.
- **Game search from Sources.** The Search tab is replaced by Downloads. Tap the search icon on the Sources tab and type at least two letters to search games across every installed source; results appear below the source list.
- **Clearer Download button.** Website games now show **Download** instead of "Download on website". It opens the game's page inside Retra; tap the file's download link and supported GB, GBC, GBA, ZIP, IPS, UPS and BPS files go to your Library and the Downloads tab.
- **Website sources open in one tap.** A source that is browsed on its website (Romhacking.net, Hackdex) shows an **Open** button instead of an empty page, and a catalog search with no matches offers to search the website instead.
- **Retra Sources extension 5.0.** The default extension repository replaces Romhack Plaza with Roms Games, PokéHarbor, PokeMundo, PokéCoders, Operation iDroid, Romhacking.net and Hackdex. Update it from Browse → Extensions; this part works on older app versions too.

## Install

Download **Retra-v1.0.2.apk** and open it on your Android device (Android 8.0 or newer). It installs over v1.0.1 and keeps your library, saves and settings.

Version: `1.0.2` / Android versionCode `453`.
