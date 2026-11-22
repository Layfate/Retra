package com.retra.emulator

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Runs RetroArch shader pipelines on the device's real OpenGL ES driver (offscreen). */
@RunWith(AndroidJUnit4::class)
class RetroArchShaderInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    @Before fun setUp() {
        root = File(context.cacheDir, "retroarch-shader-test").apply { deleteRecursively(); mkdirs() }
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        assertTrue(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT */, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE
        ), 0, configs, 0, 1, count, 0)
        assumeTrue("OpenGL ES 3 config required", count[0] > 0)
        eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, SIZE, EGL14.EGL_HEIGHT, SIZE, EGL14.EGL_NONE), 0)
        assertTrue(EGL14.eglMakeCurrent(display, surface, surface, eglContext))
    }

    @After fun tearDown() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
        EGL14.eglTerminate(display)
        root.deleteRecursively()
    }

    private fun write(path: String, text: String) = File(root, path).apply { parentFile?.mkdirs(); writeText(text) }

    /** 2×2 input: red, green (top row) / blue, white (bottom row), texture row 0 = image top. */
    private fun inputTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        val pixels = byteArrayOf(-1, 0, 0, -1, 0, -1, 0, -1, 0, 0, -1, -1, -1, -1, -1, -1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 2, 2, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
            ByteBuffer.allocateDirect(16).put(pixels).position(0))
        return ids[0]
    }

    /** RGBA at screen position (x, y measured from the top). */
    private fun pixel(x: Int, y: Int): List<Int> {
        val buffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(x, SIZE - 1 - y, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        return (0 until 4).map { buffer.get(it).toInt() and 0xff }
    }

    private fun render(pipeline: RetroArchShaders.Pipeline): RetroArchShaderRenderer {
        val renderer = RetroArchShaderRenderer(pipeline, es3 = true)
        assertNull(renderer.init())
        val texture = inputTexture()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        renderer.draw(texture, 2, 2, 0, 0, SIZE, SIZE, SIZE, SIZE, defaultLinear = false)
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
        return renderer
    }

    @Test fun slangMultiPassUsesNativeConverterAliasesAndKeepsOrientation() {
        assertTrue("libretra_shaders should be packaged", SlangNative.available)
        val header = """
            #version 450
            layout(push_constant) uniform Push { vec4 SourceSize; vec4 OutputSize; uint FrameCount; float amount; } params;
            layout(std140, set = 0, binding = 0) uniform UBO { mat4 MVP; } global;
            #pragma parameter amount "Amount" 0.0 0.0 1.0 0.1
            #pragma stage vertex
            layout(location = 0) in vec4 Position;
            layout(location = 1) in vec2 TexCoord;
            layout(location = 0) out vec2 vTexCoord;
            void main() { gl_Position = global.MVP * Position; vTexCoord = TexCoord; }
            #pragma stage fragment
            layout(location = 0) in vec2 uv;
            layout(location = 0) out vec4 FragColor;
        """.trimIndent()
        // Pass 1 inverts into an FBO. Pass 2 reads it by alias, mixes in Original by `amount`
        // (1.0 from the preset, so the final image equals the input) and uses an int literal
        // in float math, which only works through glslang.
        write("invert.slang", "$header\nlayout(set = 0, binding = 2) uniform sampler2D Source;\n" +
            "void main() { FragColor = vec4(1.0 - texture(Source, uv).rgb, 1); }\n")
        write("restore.slang", "$header\nlayout(set = 0, binding = 3) uniform sampler2D Inverted;\n" +
            "layout(set = 0, binding = 4) uniform sampler2D Original;\n" +
            "void main() { vec3 a = 1 - texture(Inverted, uv).rgb; FragColor = vec4(mix(a, texture(Original, uv).rgb, params.amount), 1.0); }\n")
        val preset = write("test.slangp", "shaders = 2\nshader0 = invert.slang\nalias0 = Inverted\nscale_type0 = source\n" +
            "shader1 = restore.slang\namount = 1.0\n")
        val pipeline = SlangNative.upgrade(RetroArchShaders.buildPipeline(RetroArchShaders.parsePreset(preset, root), root))
        assertTrue(pipeline.passes.all { it.vertex.startsWith("#version 300 es") })
        val renderer = render(pipeline)
        assertEquals(listOf(255, 0, 0, 255), pixel(2, 2))                     // top-left: red
        assertEquals(listOf(0, 255, 0, 255), pixel(SIZE - 3, 2))              // top-right: green
        assertEquals(listOf(0, 0, 255, 255), pixel(2, SIZE - 3))              // bottom-left: blue
        assertEquals(listOf(255, 255, 255, 255), pixel(SIZE - 3, SIZE - 3))   // bottom-right: white
        renderer.release()
    }

    @Test fun legacyGlslPassRuns() {
        write("invert.glsl", """
            #pragma parameter strength "Strength" 1.0 0.0 1.0 0.1
            #if defined(VERTEX)
            attribute vec4 VertexCoord; attribute vec4 COLOR; attribute vec4 TexCoord;
            varying vec4 TEX0; uniform mat4 MVPMatrix;
            void main() { gl_Position = MVPMatrix * VertexCoord; TEX0 = TexCoord; }
            #elif defined(FRAGMENT)
            precision mediump float;
            uniform sampler2D Texture; varying vec4 TEX0;
            #ifdef PARAMETER_UNIFORM
            uniform float strength;
            #else
            #define strength 1.0
            #endif
            void main() { gl_FragColor = vec4(mix(texture2D(Texture, TEX0.xy).rgb, 1.0 - texture2D(Texture, TEX0.xy).rgb, strength), 1.0); }
            #endif
        """.trimIndent())
        val preset = write("invert.glslp", "shaders = 1\nshader0 = invert.glsl\n")
        val renderer = render(RetroArchShaders.buildPipeline(RetroArchShaders.parsePreset(preset, root), root))
        assertEquals(listOf(0, 255, 255, 255), pixel(2, 2))                   // inverted red
        assertEquals(listOf(0, 0, 0, 255), pixel(SIZE - 3, SIZE - 3))         // inverted white
        renderer.release()
    }

    /**
     * Compiles and draws every preset of a corpus pushed to the app's external files dir
     * (`files/shader-corpus/<repo>/...`). Skipped when absent. Logs a summary with tag RetraShaderCorpus.
     */
    @Test fun corpusOnDeviceDriver() {
        val corpus = File(context.getExternalFilesDir(null), "shader-corpus")
        assumeTrue(corpus.isDirectory)
        // Optional `-e corpusFilter <regex>` limits the sweep (software GPUs compile slowly).
        val filter = InstrumentationRegistry.getArguments().getString("corpusFilter")?.let(::Regex)
        val texture = inputTexture()
        val failures = LinkedHashMap<String, Int>()
        var ok = 0
        var total = 0
        corpus.listFiles()!!.filter { it.isDirectory }.forEach { repo ->
            val cache = HashMap<String, RetroArchShaders.CompiledPass>()
            repo.walkTopDown().filter { it.isFile && RetroArchShaders.isPreset(it.name) }
                .filter { filter == null || filter.containsMatchIn(it.relativeTo(corpus).invariantSeparatorsPath) }
                .sortedBy { it.path }.forEach { file ->
                total++
                val error = try {
                    val pipeline = SlangNative.upgrade(RetroArchShaders.buildPipeline(RetroArchShaders.parsePreset(file, repo), repo, cache))
                    val renderer = RetroArchShaderRenderer(pipeline, es3 = true)
                    val initError = renderer.init()
                    if (initError == null) {
                        renderer.draw(texture, 2, 2, 0, 0, SIZE, SIZE, SIZE, SIZE, defaultLinear = false)
                        val glError = GLES20.glGetError()
                        renderer.release()
                        if (glError != GLES20.GL_NO_ERROR) "GL error $glError" else null
                    } else initError
                } catch (error: Throwable) { error.message ?: error.javaClass.simpleName }
                if (error == null) ok++ else {
                    val reason = error.replace(Regex("Pass \\d+ \\([^)]*\\)"), "Pass").replace(Regex("[0-9]+"), "#").take(110)
                    failures[reason] = (failures[reason] ?: 0) + 1
                    Log.i(TAG, "FAIL ${file.relativeTo(corpus)}: ${error.take(200)}")
                }
            }
        }
        Log.i(TAG, "SUMMARY $ok / $total presets compiled and drew")
        failures.entries.sortedByDescending { it.value }.take(20).forEach { Log.i(TAG, "REASON ${it.value}x ${it.key}") }
    }

    private companion object {
        const val SIZE = 16
        const val TAG = "RetraShaderCorpus"
    }
}
