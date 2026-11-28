const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const libraryUi = fs.readFileSync(path.join(root, 'app/src/main/assets/retra/library-ui.js'), 'utf8');
const appShell = fs.readFileSync(path.join(root, 'app/src/main/assets/retra/app-shell.js'), 'utf8');
const css = fs.readFileSync(path.join(root, 'app/src/main/assets/retra/style-core.css'), 'utf8');

test('Statistics Playtime by ROM resolves the same persisted cover source as Library', () => {
  assert.match(libraryUi, /return getNativeRomMediaUrl\(rom\.id, 'cover'\) \|\| romCoverObjectUrls\.get\(rom\.id\) \|\| rom\.cover/);
  assert.match(libraryUi, /const cover = statisticsCoverUrl\(rom\)/);
  assert.match(libraryUi, /async function hydratePlaytimeCover\(row, rom\)/);
  assert.match(libraryUi, /getNativeRomMediaUrl\(rom\.id, 'cover'\).*romCoverObjectUrls/s);
});

test('Changing a ROM cover updates an already-open Playtime by ROM row', () => {
  assert.match(appShell, /#playtimeList \.playtime-item/);
  assert.match(appShell, /dataset\?\.playtimeRom/);
  assert.match(appShell, /statisticsImage\.src = url/);
});

test('Playtime cover artwork keeps a compact portrait cover shape', () => {
  assert.match(css, /\.playtime-cover\{[\s\S]*?width: 42px;[\s\S]*?height: 58px;/);
  assert.match(css, /\.playtime-cover img\{[\s\S]*?object-fit: cover;/);
});

test('Statistics shows the most played game with its cover art', () => {
  const html = fs.readFileSync(path.join(root, 'app/src/main/assets/retra/index.html'), 'utf8');
  const shell = fs.readFileSync(path.join(root, 'app/src/main/assets/retra/style-material-shell.css'), 'utf8');
  assert.match(html, /id="statsHero"[\s\S]*id="statsHeroCover"[\s\S]*id="statsHeroTitle"[\s\S]*id="statsHeroTime"/);
  assert.match(libraryUi, /function renderStatisticsHero\(top, totalPlaytime\)/);
  assert.match(libraryUi, /renderStatisticsHero\(entries\[0\] \|\| null, totalPlaytime\)/);
  // The hero waits for cached cover art like the rows do, and a cover change updates it.
  assert.match(libraryUi, /if \(!url\) hydrateStatisticsCover\(cover, rom, showBackdrop\)/);
  assert.match(appShell, /statisticsHero\.dataset\?\.romId/);
  assert.match(shell, /#statisticsPage \.stats-hero-backdrop\{[\s\S]*?filter:blur/);
  // Ranked rows draw a playtime bar relative to the most played game.
  assert.match(libraryUi, /Math\.round\(\(millis \/ longest\) \* 100\)/);
});
