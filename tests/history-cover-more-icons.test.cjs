const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const libraryUi = fs.readFileSync(path.join(root, 'app/src/main/assets/retra/library-ui.js'), 'utf8');
const appShell = fs.readFileSync(path.join(root, 'app/src/main/assets/retra/app-shell.js'), 'utf8');
const indexHtml = fs.readFileSync(path.join(root, 'app/src/main/assets/retra/index.html'), 'utf8');

test('History resolves native persisted cover artwork just like Library', () => {
  assert.match(libraryUi, /getNativeRomMediaUrl\(rom\.id, 'cover'\).*romCoverObjectUrls/s);
  assert.match(libraryUi, /const initialCover = rom \? \(getNativeRomMediaUrl\(rom\.id, 'cover'\)/);
});

test('cover updates propagate into an already-open History row', () => {
  assert.match(appShell, /#historyList \.history-item/);
  assert.match(appShell, /historyImage\.src = url/);
  assert.match(appShell, /coverWrap\.classList\.remove\('no-cover'\)/);
});

// The More page uses Mihon-style Material icons (filled glyphs, class="ms").
test('More page uses recognizable Material Statistics and Settings icons', () => {
  const statistics = indexHtml.match(/data-more-open="statisticsPage"[\s\S]*?<div class="more-copy"><strong>Statistics<\/strong>/)?.[0] || '';
  const settings = indexHtml.match(/data-more-open="settingsHomePage"[\s\S]*?<div class="more-copy"><strong>Settings<\/strong>/)?.[0] || '';
  assert.match(statistics, /<svg class="ms"/);
  assert.match(statistics, /M19\.88 18\.47/); // query_stats
  assert.match(settings, /<svg class="ms"/);
  assert.match(settings, /M19\.43 12\.98/); // settings gear
});


test('More page uses a Material palette icon with paint dots', () => {
  const colorStyle = indexHtml.match(/data-more-open="colorStylePage"[\s\S]*?<div class="more-copy"><strong>Color Style<\/strong>/)?.[0] || '';
  assert.match(colorStyle, /<svg class="ms"/);
  assert.match(colorStyle, /M12 22C6\.49 22 2 17\.51 2 12/); // palette outline
  assert.match(colorStyle, /cx="6\.5" cy="11\.5"/);
  assert.match(colorStyle, /cx="9\.5" cy="7\.5"/);
});
