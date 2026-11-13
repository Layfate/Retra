const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const cmake = fs.readFileSync(path.join(root, 'app/src/main/cpp/CMakeLists.txt'), 'utf8');
const native = fs.readFileSync(path.join(root, 'app/src/main/cpp/native-lib.cpp'), 'utf8');

test('mGBA core is optimized in Debug builds and -O3 in release', () => {
  const beforeCore = cmake.slice(0, cmake.indexOf('\nadd_subdirectory('));
  assert.match(beforeCore, /set\(CMAKE_C_FLAGS_DEBUG "-O2 -g/);
  assert.match(beforeCore, /set\(CMAKE_C_FLAGS_RELWITHDEBINFO "-O3 -g -DNDEBUG"\)/);
  assert.match(beforeCore, /set\(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG"\)/);
});

test('release builds use ThinLTO via explicit flags (not the broken gold probe)', () => {
  assert.match(cmake, /foreach\(RETRA_LTO_CONFIG RELWITHDEBINFO RELEASE\)/);
  assert.match(cmake, /string\(APPEND CMAKE_SHARED_LINKER_FLAGS_\$\{RETRA_LTO_CONFIG\} " -flto=thin"\)/);
  assert.doesNotMatch(cmake, /include\(CheckIPOSupported\)/);
});

test('static mGBA symbols stay internal to libemulator.so', () => {
  assert.match(cmake, /-Wl,--exclude-libs,ALL/);
  assert.match(cmake, /max-page-size=16384/);
});

test('audio JNI paths stage PCM natively and copy back only produced samples', () => {
  assert.doesNotMatch(native, /env->GetShortArrayElements\(/);
  assert.equal((native.match(/audioStagingLocked\(maxFrames \* 2\)/g) || []).length, 2);
  assert.equal((native.match(/env->SetShortArrayRegion\(\s*output, 0, static_cast<jsize>\(produced \* 2\)/g) || []).length, 2);
});
