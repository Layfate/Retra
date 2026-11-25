const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const read = rel => fs.readFileSync(path.join(root, rel), 'utf8');

test('Screen Editor loads the gameplay controller parity layer last', () => {
  const html = read('app/src/main/assets/retra/index.html');
  assert.match(html, /style-gameplay-controller-parity\.css/);
  assert.ok(
    html.indexOf('style-gameplay-controller-parity.css') > html.indexOf('style-scroll-performance.css'),
    'controller parity CSS must load after all appearance/editor layers'
  );
});

test('Screen Editor controller geometry and glyphs mirror native gameplay resources', () => {
  const css = read('app/src/main/assets/retra/style-gameplay-controller-parity.css');
  const layout = read('app/src/main/res/layout/activity_main.xml');
  const dpad = read('app/src/main/res/drawable/ic_controller_dpad_up.xml');
  const menu = read('app/src/main/res/drawable/ic_controller_menu.xml');

  assert.match(layout, /android:id="@\+id\/buttonL"[\s\S]*?android:layout_width="72dp"[\s\S]*?android:layout_height="30dp"/);
  assert.match(css, /\.preview-shoulder[\s\S]*?width:\s*72px !important;[\s\S]*?height:\s*30px !important;/);
  assert.match(layout, /android:id="@\+id\/dpadContainer"[\s\S]*?android:layout_width="150dp"[\s\S]*?android:layout_height="150dp"/);
  assert.match(css, /\.preview-dpad \{[\s\S]*?width:\s*150px !important;[\s\S]*?height:\s*150px !important;/);
  assert.match(dpad, /M5\.5,15 L12,8\.5 L18\.5,15/);
  assert.match(css, /M5\.5 15 L12 8\.5 L18\.5 15/);
  assert.match(menu, /M5\.75,7\.5 L18\.25,7\.5 M5\.75,12 L18\.25,12 M5\.75,16\.5 L18\.25,16\.5/);
  assert.match(css, /--native-controller-stroke: 2\.4;/);
  assert.match(css, /stroke-width: var\(--native-controller-stroke\) !important/);
});

test('every controller glyph shares one spec and every face one palette', () => {
  const drawable = name => read(`app/src/main/res/drawable/${name}.xml`);
  for (const name of ['ic_controller_dpad_up', 'ic_controller_menu', 'ic_controller_fast_forward',
    'ic_controller_quick_load', 'ic_controller_quick_save', 'ic_controller_screenshot']) {
    const xml = drawable(name);
    assert.match(xml, /android:viewportWidth="24"/, name);
    assert.match(xml, /android:width="24dp"/, name);
    for (const stroke of xml.matchAll(/android:strokeWidth="([^"]+)"/g)) assert.equal(stroke[1], '2.4', name);
    assert.doesNotMatch(xml, /strokeLineCap="(square|butt)"|strokeLineJoin="miter"/, name);
    assert.doesNotMatch(xml.replace(/#FFF7F9FC/g, ''), /strokeColor="#/, name);
  }
  for (const name of ['bg_controller_circle', 'bg_controller_pill', 'bg_controller_round_rect',
    'bg_controller_shoulder_left', 'bg_controller_shoulder_right', 'bg_controller_square']) {
    const xml = drawable(name);
    assert.deepEqual([...new Set([...xml.matchAll(/<solid android:color="([^"]+)"/g)].map(m => m[1]))].sort(), ['#5C11171D', '#7545525A'], name);
    assert.deepEqual([...new Set([...xml.matchAll(/<stroke android:width="([^"]+)" android:color="([^"]+)"/g)].map(m => m[1] + ' ' + m[2]))].sort(),
      ['1.5dp #CCFFFFFF', '1.5dp #FFFFFFFF'], name);
  }
  // The D-pad glyph renders at its 24dp size inside the 44dp face.
  const layout = read('app/src/main/res/layout/activity_main.xml');
  for (const id of ['buttonUp', 'buttonDown', 'buttonLeft', 'buttonRight']) {
    assert.match(layout, new RegExp(`android:id="@\\+id/${id}"[^>]*?android:padding="10dp"`), id);
  }
  // Lettered buttons share one typeface.
  for (const id of ['buttonL', 'buttonR', 'buttonA', 'buttonB']) {
    // Up to the element's closing tag, so a later button's attributes cannot satisfy it.
    assert.match(layout, new RegExp(`android:id="@\\+id/${id}"[^>]*?android:fontFamily="sans-serif-medium"`), id);
  }
  assert.match(read('app/src/main/java/com/retra/emulator/GameplayLayoutController.kt'), /Typeface\.create\("sans-serif-medium", Typeface\.BOLD\)/);
});

test('Screen Editor glyph markup draws the same paths as the native vectors', () => {
  const js = read('app/src/main/assets/retra/controller-editor-ui.js');
  assert.match(js, /M5\.25 12A7\.5 7\.5 0 1 1 7\.93 17\.75/);
  assert.match(read('app/src/main/res/drawable/ic_controller_quick_load.xml'), /M5\.25,12 A7\.5,7\.5 0,1 1,7\.93,17\.75/);
  assert.match(js, /m6\.5 6\.5 5\.5 5\.5-5\.5 5\.5/);
  assert.match(read('app/src/main/res/drawable/ic_controller_fast_forward.xml'), /M6\.5,6\.5 L12,12 L6\.5,17\.5 M12,6\.5 L17\.5,12 L12,17\.5/);
  assert.match(js, /M8\.5 4\.5v4h6v-4/);
  assert.match(read('app/src/main/res/drawable/ic_controller_quick_save.xml'), /M8\.5,4\.5 L8\.5,8\.5 L14\.5,8\.5 L14\.5,4\.5/);
  assert.match(js, /circle cx="12" cy="13" r="3\.25"/);
  assert.doesNotMatch(js, /M7\.35 16\.35|M5\.5 4\.5h10\.8|r="3\.2"/, 'old glyphs are gone');
});

test('Add-control restore thumbnails no longer substitute generic D-pad or Start\/Select logos', () => {
  const js = read('app/src/main/assets/retra/controller-editor-ui.js');
  assert.match(js, /native-dpad-preview/);
  assert.match(js, /native-start-select-preview/);
  assert.match(js, /native-shoulder-preview/);
  assert.doesNotMatch(js, /case 'dpad':[\s\S]{0,220}M10 3h4v7h7v4h-7v7h-4v-7H3v-4h7z/);
  assert.doesNotMatch(js, /case 'startSelect':[\s\S]{0,160}>SS</);
});

test('native gameplay L/R use asymmetric curved shoulder drawables while Start/Select keep the pill', () => {
  const layout = read('app/src/main/res/layout/activity_main.xml');
  const left = read('app/src/main/res/drawable/bg_controller_shoulder_left.xml');
  const right = read('app/src/main/res/drawable/bg_controller_shoulder_right.xml');

  assert.match(layout, /android:id="@\+id\/buttonL"[\s\S]*?android:background="@drawable\/bg_controller_shoulder_left"/);
  assert.match(layout, /android:id="@\+id\/buttonR"[\s\S]*?android:background="@drawable\/bg_controller_shoulder_right"/);
  assert.match(layout, /android:id="@\+id\/buttonSelect"[\s\S]*?android:background="@drawable\/bg_controller_pill"/);
  assert.match(layout, /android:id="@\+id\/buttonStart"[\s\S]*?android:background="@drawable\/bg_controller_pill"/);

  assert.match(left, /android:topLeftRadius="12dp"/);
  assert.match(left, /android:topRightRadius="28dp"/);
  assert.match(left, /android:bottomRightRadius="16dp"/);
  assert.match(left, /android:bottomLeftRadius="12dp"/);
  assert.match(right, /android:topLeftRadius="28dp"/);
  assert.match(right, /android:topRightRadius="12dp"/);
  assert.match(right, /android:bottomRightRadius="12dp"/);
  assert.match(right, /android:bottomLeftRadius="16dp"/);
});
