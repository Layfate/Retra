# Third-Party Notices

Retra uses and/or interoperates with third-party open-source software. This notice is intended to make the most important ownership and licensing boundaries explicit. It does not replace the license files shipped by each dependency.

## mGBA

Retra's native emulator integration is built around the mGBA emulator core.

- Project: mGBA
- Upstream: https://github.com/mgba-emu/mgba
- Copyright: mGBA contributors, including Jeffrey Pfau
- License: Mozilla Public License 2.0 (MPL-2.0)
- Retra validated/pinned revision: `543a197582c30364584d773a974d7f991892fa43` (see `third_party/mgba.lock`)

When building or redistributing Retra with mGBA, retain the mGBA license and notices from the exact source revision used for the build. Retra does not claim ownership of mGBA.

## glslang and SPIRV-Cross (RetroArch .slang shaders)

Retra's optional `libretra_shaders` compiles imported RetroArch `.slang` shaders with glslang (Vulkan GLSL to SPIR-V) and converts them to OpenGL ES GLSL with SPIRV-Cross.

- Project: glslang (Khronos Group) — https://github.com/KhronosGroup/glslang
- License: BSD-3-Clause, BSD-2-Clause, MIT, Apache-2.0 and the other licenses listed in its `LICENSE.txt` and `LICENSES/` folder. Its generated parser (`glslang_tab.cpp`) carries the Bison skeleton's GPL-3.0 text with the Bison special exception, which allows distributing it as part of a larger work under that work's terms.
- Project: SPIRV-Cross (Khronos Group) — https://github.com/KhronosGroup/SPIRV-Cross
- License: Apache License 2.0
- Retra pinned revisions: Vulkan SDK 1.4.357.0 tags (see `third_party/shader-tools.lock`; fetch with `tools/prepare_shader_tools.py`)

Retain both projects' license files when redistributing builds that include `libretra_shaders`.

## RetroArch shaders

Retra can import shaders written for RetroArch (for example from https://github.com/libretro/slang-shaders and https://github.com/libretro/glsl-shaders). Retra does not bundle them; imported shaders keep their authors' licenses, which are stated in each shader file.

## Android / Jetpack / Material dependencies

Retra also depends on AndroidX, Material Components, Kotlin/coroutines, Room, DataStore, WebKit, and related Android build/runtime libraries. These packages retain their own copyright notices and licenses. Gradle resolves the exact dependency versions declared in `gradle/libs.versions.toml` and `app/build.gradle.kts`.

## User-supplied game content

Retra does not include commercial ROMs, BIOS images, game artwork, or other copyrighted game data. Those files remain the property of their respective rights holders. Users are responsible for using content they are legally permitted to use.
