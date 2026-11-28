'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const html = read('app/src/main/assets/retra/index.html');
const js = read('app/src/main/assets/retra/settings-ui.js');
const css = read('app/src/main/assets/retra/style-modern-surfaces.css');
const updater = read('app/src/main/java/com/retra/emulator/AppUpdateController.kt');

test('a newer GitHub release opens a full-screen "New version available!" page', () => {
  assert.match(html, /<div class="app-update-screen" id="appUpdateModal"[^>]*role="dialog"/);
  assert.match(html, /<h1 class="app-update-title" id="appUpdateTitle">New version available!<\/h1>/);
  assert.match(html, /id="appUpdateInstallBtn" type="button">Download<\/button>/);
  assert.match(html, /id="appUpdateLaterBtn" type="button">Not now<\/button>/);
  assert.match(html, /href="https:\/\/github\.com\/Layfate\/Retra\/releases" data-update-link="">past release notes<\/a>/);
  assert.match(css, /\.app-update-screen\{[\s\S]*?position:fixed;[\s\S]*?inset:0;/);
  assert.match(css, /\.app-update-actions\{[\s\S]*?flex:none;/);
});

test('release notes render as Markdown without innerHTML and skip the Install section', () => {
  const renderer = js.slice(js.indexOf('function renderReleaseNotes'), js.indexOf('function openExternalUpdateLink'));
  assert.doesNotMatch(renderer + js.slice(js.indexOf('function appendReleaseInline'), js.indexOf('function renderReleaseNotes')), /innerHTML/);
  assert.match(renderer, /install\|installation\|download/);
  assert.match(js, /renderReleaseNotes\(appUpdateNotes, result\.notes\)/);
  assert.match(js, /if \(\/new\|feature\|added\/\.test\(t\)\) return '✨';/);
  assert.match(updater, /MAX_RELEASE_NOTES_CHARS = 20_000/);
});

test('an automatic check that failed offline runs again when Wi-Fi or mobile data returns', () => {
  assert.match(updater, /if \(!manual\) automaticCheckStartedThisProcess\.set\(false\)/);
  assert.match(js, /if \(!manual\) automaticUpdateCheckFailed = status === 'error';/);
  assert.match(js, /window\.addEventListener\('online'/);
  assert.match(js, /if \(appUpdateModal\?\.classList\.contains\('open'\)\) \{ closeAppUpdateModal\(\); return true; \}/);
});
