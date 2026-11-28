const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const css = fs.readFileSync(path.join(root, 'app/src/main/assets/retra/ui-polish.css'), 'utf8');
const html = fs.readFileSync(path.join(root, 'app/src/main/assets/retra/index.html'), 'utf8');

test('advanced settings group the reset action under its own section label', () => {
  assert.match(html, /<div class="settings-section-label">Reset<\/div>\n<div class="detail-list settings-card">\n<button class="detail-row tappable danger-row" data-action="Reset advanced settings">/);
  assert.match(css, /\.section-label\s*\{[\s\S]*?color:#78e8ee;[\s\S]*?text-align:center;[\s\S]*?\}/);
});

test('both About panels extend their frame background to the page edges', () => {
  assert.match(css, /#aboutSettingsPage \.about-panel,\s*#aboutPage \.about-panel\s*\{/);
  assert.match(css, /width:calc\(100% \+ \(var\(--content-gutter\) \* 2\)\)/);
  assert.match(css, /margin-left:calc\(var\(--content-gutter\) \* -1\)/);
  assert.match(css, /padding-left:calc\(var\(--content-gutter\) \+ 8px\)/);
});
