const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const read = p => fs.readFileSync(path.join(root, p), 'utf8');

test('controller haptics are a felt pulse from the vibrator, not a mutable UI tick', () => {
  const manifest = read('app/src/main/AndroidManifest.xml');
  const feedback = read('app/src/main/java/com/retra/emulator/ControllerFeedbackManager.kt');
  // The view haptic constants were the faintest effects Android has and many phones skipped
  // them, so presses were not felt. One short light pulse plays on the vibrator instead.
  assert.match(manifest, /android\.permission\.VIBRATE/);
  assert.match(feedback, /VibrationEffect\.createOneShot\(TICK_MS, amplitude\)/);
  assert.match(feedback, /TICK_MS = 14L/);
  assert.match(feedback, /TICK_AMPLITUDE = 140/);
  // Game/media usage, so the system touch-feedback setting and a silent ringer do not mute it.
  assert.match(feedback, /VibrationAttributes\.USAGE_MEDIA/);
  assert.match(feedback, /AudioAttributes\.USAGE_GAME/);
  // The view haptic remains only as the fallback when no vibrator service exists.
  assert.match(feedback, /HapticFeedbackConstants\.(?:CLOCK_TICK|SEGMENT_FREQUENT_TICK)/);
  assert.doesNotMatch(feedback, /createWaveform/, 'never a long or repeating vibration');
});

test('controller click is a bundled sample on the media stream, not the system touch sound', () => {
  const feedback = read('app/src/main/java/com/retra/emulator/ControllerFeedbackManager.kt');
  const activity = read('app/src/main/java/com/retra/emulator/MainActivity.kt');
  const touch = read('app/src/main/java/com/retra/emulator/GameplayTouchController.kt');
  assert.ok(fs.existsSync(path.join(root, 'app/src/main/res/raw/controller_click.wav')));
  assert.match(feedback, /SoundPool\.Builder\(\)/);
  assert.match(feedback, /R\.raw\.controller_click/);
  assert.doesNotMatch(feedback, /FX_KEY_CLICK|playSoundEffect/);
  // Loaded when controls are bound, so the very first press is not silent; released with the activity.
  assert.match(touch, /controllerFeedback\.prepare\(\)/);
  assert.match(activity, /if \(controllerFeedbackDelegate\.isInitialized\(\)\) controllerFeedback\.release\(\)/);
});

test('controller sound and haptics remain independently configurable', () => {
  const html = read('app/src/main/assets/retra/index.html');
  const settings = read('app/src/main/java/com/retra/emulator/SettingsController.kt');
  assert.match(html, /id="controllerSoundToggle"/);
  assert.match(html, /id="controllerHapticsToggle"/);
  assert.match(settings, /controllerSound/);
  assert.match(settings, /controllerHaptics/);
  assert.match(html, /<h2>Sound<\/h2>/);
});
