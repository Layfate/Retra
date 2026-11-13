'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {createHash} = require('node:crypto');

const directory = path.resolve(__dirname, '../extensions');
const base = 'https://raw.githubusercontent.com/Layfate/Retra/main/extensions/';

function publishedFile(url) {
  assert.ok(url.startsWith(base), `Catalog file must be hosted on main: ${url}`);
  const relative = new URL(url).pathname.slice(new URL(base).pathname.length);
  const file = path.resolve(directory, relative);
  assert.ok(file.startsWith(directory + path.sep), 'Catalog path must stay inside extensions');
  return fs.readFileSync(file);
}

test('published extension packages match their checksums and all sources resolve on main', () => {
  const index = JSON.parse(fs.readFileSync(path.join(directory, 'index.json'), 'utf8'));
  assert.equal(index.schemaVersion, 1);
  assert.ok(index.extensions.length > 0);
  for (const extension of index.extensions) {
    const bytes = publishedFile(extension.packageUrl);
    assert.equal(extension.sha256, createHash('sha256').update(bytes).digest('hex'));
    assert.equal(publishedFile(extension.icon).readUInt32BE(0), 0x89504e47);
    const manifest = JSON.parse(bytes);
    assert.equal(manifest.id, extension.id);
    assert.equal(manifest.versionCode, extension.versionCode);
    assert.ok(manifest.sources.length > 0);
    for (const source of manifest.sources) {
      assert.equal(publishedFile(source.icon).readUInt32BE(0), 0x89504e47);
      const catalog = JSON.parse(publishedFile(source.catalogUrl));
      assert.equal(catalog.apiVersion, 1);
      assert.ok(catalog.games.length > 0, `${source.id} must retain its games`);
      assert.ok(catalog.games.every(game => source.platforms.includes(game.platform)));
    }
  }
});
