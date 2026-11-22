package com.retra.emulator

/**
 * glslang + SPIRV-Cross conversion of RetroArch .slang passes to GLSL ES 3.00 (libretra_shaders).
 *
 * The library is only built when the pinned shader tools are checked out (see
 * third_party/shader-tools.lock). Without it Retra falls back to its text converter, which
 * handles fewer shaders.
 */
internal object SlangNative {
    val available: Boolean by lazy { runCatching { System.loadLibrary("retra_shaders") }.isSuccess }

    /** Returns [vertex, fragment] GLSL ES sources. Throws with glslang's message when the shader is invalid. */
    fun convert(vertex450: String, fragment450: String): Pair<String, String> {
        val result = nativeConvert(vertex450, fragment450)
        if (result.size < 4 || result[0] != "1") error(result.getOrNull(3)?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "Slang conversion failed")
        return result[1] to result[2]
    }

    /** Converts every slang pass of [pipeline] natively when the library is present. */
    fun upgrade(pipeline: RetroArchShaders.Pipeline): RetroArchShaders.Pipeline {
        if (!available) return pipeline
        return pipeline.copy(passes = pipeline.passes.map { pass ->
            val vertex = pass.vulkanVertex
            val fragment = pass.vulkanFragment
            if (vertex == null || fragment == null) pass
            else {
                val (vs, fs) = try { convert(vertex, fragment) }
                    catch (error: IllegalStateException) { throw IllegalStateException("${pass.spec.shader.name}: ${error.message}") }
                // Struct uniforms ("params.SourceSize") map to semantics by the name after the dot.
                pass.copy(vertex = vs, fragment = fs, uniformNames = emptyMap())
            }
        })
    }

    @JvmStatic private external fun nativeConvert(vertex450: String, fragment450: String): Array<String>
}
