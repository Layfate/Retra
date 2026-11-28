'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const assets = path.resolve(__dirname, '../app/src/main/assets/retra');
const read = name => fs.readFileSync(path.join(assets, name), 'utf8');
const css = read('style-modern-surfaces.css');
const js = read('settings-ui.js');
const html = read('index.html');

test('dialogs use a solid scrim, not a live backdrop blur, and pin to the viewport', () => {
  const backdrop = css.match(/body\[data-resolved-mode\] \.modal-backdrop\.selection-modal\{[\s\S]*?\}/)[0];
  assert.match(backdrop, /position:fixed !important/);
  assert.match(backdrop, /backdrop-filter:none !important/);
});

test('dialogs scroll in exactly one composited area with a fixed title and action bar', () => {
  assert.match(css, /\.selection-modal \.selection-modal-card\{[\s\S]*?display:flex !important;[\s\S]*?overflow:hidden/);
  const list = css.match(/body\[data-resolved-mode\] \.selection-modal \.selection-options,[\s\S]*?\}/)[0];
  assert.match(list, /overflow-y:auto !important/);
  assert.match(list, /overscroll-behavior:contain/);
  assert.match(list, /contain:content/);
  assert.match(css, /\.selection-modal \.selection-actions\{[\s\S]*?flex:none !important/);
});

test('shader options show a colored GPU-cost chip', () => {
  for (const level of ['none', 'low', 'medium', 'high']) assert.match(css, new RegExp(`\\.shader-impact-${level}\\{`));
});

test('settings pages group rows into cards under section labels', () => {
  for (const page of ['videoSettingsPage', 'audioSettingsPage', 'miscSettingsPage', 'librarySettingsPage', 'advancedSettingsPage']) {
    const section = html.slice(html.indexOf(`id="${page}"`), html.indexOf('</section>', html.indexOf(`id="${page}"`)));
    assert.match(section, /<div class="settings-section-label">[^<]+<\/div>\n<div class="detail-list settings-card">/, page);
    assert.doesNotMatch(section, /<div class="detail-list">/, page);
  }
});

test('sliders move continuously and do their work at most once per frame', () => {
  assert.match(html, /id="buttonsOpacityRange" max="100" min="25" step="any"/);
  assert.match(html, /id="volumeRange" max="100" min="0" step="any"/);
  assert.match(js, /function bindSmoothRange\(range, \{ preview, commit \}\)/);
  assert.match(js, /frame = requestAnimationFrame\(\(\) => \{/);
  assert.match(js, /preview\(Math\.round\(Number\(range\.value\)\)\)/);
  // The opacity preview is scoped to the screen editor, never the whole document.
  assert.match(js, /\(screenSizePageEl \|\| document\.documentElement\)\.style\.setProperty\('--retra-buttons-opacity'/);
  assert.match(css, /touch-action:pan-y/);
  assert.match(css, /::-webkit-slider-thumb\{[\s\S]*?transition:none/);
});
