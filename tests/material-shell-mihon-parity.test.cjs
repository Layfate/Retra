const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const assets = path.join(root, 'app/src/main/assets/retra');
const html = fs.readFileSync(path.join(assets, 'index.html'), 'utf8');
const css = fs.readFileSync(path.join(assets, 'style-material-shell.css'), 'utf8');

test('Material shell stylesheet loads last so it owns the app chrome', () => {
  const links = [...html.matchAll(/<link href="([^"]+\.css)" rel="stylesheet"\/>/g)].map(m => m[1]);
  assert.equal(links.at(-1), 'style-material-shell.css');
});

test('top app bar has no divider line (no header border, no tab-row divider)', () => {
  const header = css.match(/\n\.main-header\{[\s\S]*?\}/)?.[0] || '';
  assert.match(header, /border:0/);
  assert.match(header, /box-shadow:none/);
  const tabs = css.match(/#libraryPage \.library-chips\{[\s\S]*?\}/)?.[0] || '';
  assert.match(tabs, /border:0/);
  assert.match(tabs, /box-shadow:none/);
  const nav = css.match(/\.bottom-nav,\nbody\[data-resolved-mode="dark"\] \.bottom-nav[\s\S]*?\}/)?.[0] || '';
  assert.match(nav, /border-top:0/);
});

test('header and navigation use Material icons instead of stroke icons', () => {
  const header = html.match(/<header class="main-header active-header" id="libraryHeader">[\s\S]*?<\/header>/)?.[0] || '';
  assert.equal((header.match(/<svg class="ms"/g) || []).length, 4); // search, filter, more_vert, refresh
  const nav = html.match(/<nav class="bottom-nav">[\s\S]*?<\/nav>/)?.[0] || '';
  assert.equal((nav.match(/class="nav-indicator"/g) || []).length, 4);
  assert.match(nav, /class="ms ms-off"/);
  assert.match(nav, /class="ms ms-on"/);
  assert.match(css, /svg\.ms,[\s\S]*?fill:currentColor;\s*stroke:none;/);
});

test('every back button uses the Material arrow_back glyph', () => {
  const backs = html.match(/<button aria-label="Back" class="back-btn"[^>]*>\n<svg[^>]*>/g) || [];
  assert.ok(backs.length >= 20);
  backs.forEach(b => assert.match(b, /<svg class="ms"/));
});

test('navigation bar uses a Material 3 pill indicator behind the icon only', () => {
  assert.match(css, /\.nav-indicator\{[\s\S]*?width:64px;[\s\S]*?height:32px;[\s\S]*?border-radius:16px;/);
  assert.match(css, /\.nav-item\.active \.nav-indicator\{[\s\S]*?background:var\(--m3-secondary-container\)/);
  assert.match(css, /\.bottom-nav \.nav-item\.active,[\s\S]*?background:transparent/);
});

test('compact grid overlays the title on the cover like Mihon; comfortable puts it below', () => {
  assert.match(css, /library-display-compact \.cover-info\{[\s\S]*?position:absolute/);
  assert.match(css, /library-display-compact \.cover-card::after\{[\s\S]*?display:block/);
  assert.match(css, /library-display-comfortable \.cover-info\{[\s\S]*?position:static/);
});

test('More keeps the Retra logo visible (Light-mode darkening filter is not overridden)', () => {
  assert.match(html, /<div class="more-logo-wrap">\s*<img alt="Retra logo" class="more-logo"/);
  assert.doesNotMatch(css, /\.more-logo\{[^}]*filter/);
  assert.doesNotMatch(css, /\.more-logo\{[^}]*(width|height):/);
});

test('Settings icons have no tile background in any mode; Data and Storage is a flat list', () => {
  assert.match(css, /body\[data-resolved-mode\] #settingsHomePage \.settings-item-icon\{[\s\S]*?background:transparent/);
  assert.match(css, /#dataStoragePage \.data-storage-card,\n#dataStoragePage \.backup-action-grid\{[\s\S]*?width:auto;[\s\S]*?border:0;[\s\S]*?background:transparent/);
  const storage = html.match(/id="dataStoragePage"[\s\S]*?<\/section>/)?.[0] || '';
  assert.equal((storage.match(/class="backup-action-btn"[^>]*>\n<svg class="ms"/g) || []).length, 2);
});

test('settings rows, cards and switches do not bounce (no press scale, smooth switch)', () => {
  assert.match(css, /:is\(#morePage, #settingsHomePage, \.settings-detail-page, \.selection-modal, \.modal-backdrop\)\{\s*--retra-press-scale:1;/);
  assert.match(css, /\.theme-preview-card[\s\S]*?\):is\(:active, \.retra-pressing\)\{[\s\S]*?transform:none !important;/);
  assert.match(css, /\.switch \.slider::before\{\s*transition:transform 200ms cubic-bezier\(\.2, 0, 0, 1\)/);
});

test('Add control dialog is one themed surface (no black list, readable footer)', () => {
  assert.match(css, /\.add-control-dialog,\nbody\[data-resolved-mode="light"\] \.add-control-dialog\{[\s\S]*?background:var\(--m3-surface-container-high\)/);
  assert.match(css, /\.add-control-list,[\s\S]*?background:transparent;/);
  assert.match(css, /\.add-control-footer-btn\{[\s\S]*?color:var\(--accent\)/);
  assert.match(css, /body\[data-resolved-mode="light"\] #screenSizePage \.add-control-icon\.circle,[\s\S]*?background:#2b303b !important/);
});

test('Layouts profiles are inset, slightly larger cards with a Material FAB', () => {
  const card = css.match(/#layoutsSettingsPage \.layout-card,\nbody\[data-resolved-mode="light"\] #layoutsSettingsPage \.layout-card\{[\s\S]*?\}/)?.[0] || '';
  assert.match(card, /min-height:84px/);
  assert.match(card, /border-radius:16px/);
  assert.match(card, /margin:0;/);
  assert.match(css, /\.detail-fab,\n\.detail-fab:active,\n\.detail-fab:focus\{[\s\S]*?border-radius:16px;[\s\S]*?background:var\(--m3-primary-container\) !important/);
});

test('dialogs use a light 3px blur scrim and Material cards with sentence-case actions', () => {
  assert.match(css, /:is\(\.modal-backdrop, \.selection-modal, \.sheet-backdrop, \.rom-card-actions-backdrop\),[\s\S]*?backdrop-filter:blur\(3px\);/);
  assert.match(css, /\.confirm-modal-card\{\s*border:0;\s*border-radius:28px;/);
  assert.match(css, /\.selection-option\.active\{\s*background:transparent !important;/);
  assert.doesNotMatch(html, /class="text-action-btn[^"]*"[^>]*>[A-Z]{2,}[A-Z ]*</);
});

test('all shell colors derive from the active Appearance palette tokens', () => {
  const tokens = css.match(/\nbody\{[\s\S]*?\}/)?.[0] || '';
  assert.match(tokens, /--m3-surface:var\(--shell-start\)/);
  assert.match(tokens, /var\(--accent\)/);
  assert.doesNotMatch(css, /data-theme="[a-z-]+"/); // no per-theme special cases
});
