const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const read = rel => fs.readFileSync(path.join(root, 'app/src/main/assets/retra', rel), 'utf8');

test('source page header stays over the status bar when insets change after it opens', () => {
  const browse = read('browse-ui.js');
  const insets = read('categories-ui.js');
  const css = read('style-browse.css');
  // Native insets announce themselves, and the pinned header re-reads main's padding then.
  assert.match(insets, /window\.dispatchEvent\(new Event\('retra:insets'\)\)/);
  assert.match(browse, /addEventListener\?\.\('retra:insets'/);
  // It also re-syncs one frame after the page opens.
  assert.match(browse, /openSubPage\('catalogPage'\);\s*\n\s*syncCatalogTop\(\);[\s\S]{0,120}requestAnimationFrame \|\| setTimeout\)\(resyncCatalogTop\)/);
  // Backstop strip above the header covers the status-bar area.
  assert.match(css, /#catalogTop::before\{[\s\S]*?bottom:100%;[\s\S]*?height:calc\(var\(--device-safe-top, 0px\) \+ 64px\);[\s\S]*?background:inherit;/);
});
