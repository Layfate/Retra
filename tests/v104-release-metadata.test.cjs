const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const read = rel => fs.readFileSync(path.join(root, rel), 'utf8');

test('v1.0.0 Android release metadata and user-facing fallbacks stay aligned', () => {
  const gradle = read('app/build.gradle.kts');
  const settings = read('app/src/main/assets/retra/settings-ui.js');
  const html = read('app/src/main/assets/retra/index.html');
  const readme = read('README.md');

  assert.match(gradle, /versionCode = 451/);
  assert.match(gradle, /versionName = "1.0.0"/);
  assert.match(settings, /Retra v1.0.0/);
  assert.match(settings, /let versionName = '1.0.0'/);
  assert.match(html, /Retra v1.0.0/);
  assert.match(html, /Stable 1.0.0/);
  assert.match(readme, /img\.shields\.io\/badge\/version-v1\.0\.0-/);
});
