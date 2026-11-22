package com.retra.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RetroArchShadersTest {
    @get:Rule val temp = TemporaryFolder()

    private fun write(path: String, text: String) = File(temp.root, path).apply { parentFile?.mkdirs(); writeText(text) }

    @Test fun slangStockShaderBecomesGlslEs3() {
        write("include/push.inc", """
            layout(push_constant) uniform Push { vec4 SourceSize; vec4 OutputSize; uint FrameCount; float strength; } params;
            #pragma parameter strength "Strength ↳" 0.5 0.0 1.0 0.05
        """.trimIndent())
        write("shaders/test.slang", """
            #version 450
            #pragma name TestPass
            #include "../include/push.inc"
            layout(std140, set = 0, binding = 0) uniform UBO { mat4 MVP; } global;
            #pragma stage vertex
            layout(location = 0) in vec4 Position;
            layout(location = 1) in vec2 TexCoord;
            layout(location = 0) out vec2 vTexCoord;
            void main() { gl_Position = global.MVP * Position; vTexCoord = TexCoord; }
            #pragma stage fragment
            layout(location = 0) in vec2 uv;
            layout(location = 0) out vec4 FragColor;
            layout(set = 0, binding = 2) uniform sampler2D Source;
            void main() { const vec3 c = texture(Source, uv).rgb * params.strength; FragColor = vec4(c, 1.0); }
        """.trimIndent())
        val preset = write("test.slangp", "shaders = 1\nshader0 = shaders/test.slang\nfilter_linear0 = true\nstrength = 0.75\n")
        val pipeline = RetroArchShaders.buildPipeline(RetroArchShaders.parsePreset(preset, temp.root), temp.root)
        val pass = pipeline.passes.single()
        assertEquals("TestPass", pass.alias)
        assertTrue(pass.vertex.startsWith("#version 300 es"))
        assertTrue("uniform _retra_UBO global;" in pass.vertex && "struct _retra_Push {" in pass.fragment)
        assertTrue("params.strength" in pass.fragment)
        // Varyings with different names but the same location are joined by location.
        assertTrue("out vec2 _retra_v0;" in pass.vertex && "in vec2 _retra_v0;" in pass.fragment)
        assertFalse("binding" in pass.fragment || "push_constant" in pass.fragment)
        assertFalse("const vec3" in pass.fragment)
        assertTrue(pass.fragment.all { it.code < 128 })
        assertEquals(0.75f, pipeline.parameterValues["strength"])
        assertEquals(true, pass.spec.filterLinear)
    }

    @Test fun legacyGlslKeepsCompatMacrosAndDetectsHistory() {
        write("lcd.glsl", """
            #version 130
            #pragma parameter glow "Glow" 1.0 0.0 2.0 0.1
            #if defined(VERTEX)
            attribute vec4 VertexCoord; attribute vec4 TexCoord; varying vec4 TEX0; uniform mat4 MVPMatrix;
            void main() { gl_Position = MVPMatrix * VertexCoord; TEX0 = TexCoord; }
            #elif defined(FRAGMENT)
            uniform sampler2D Texture; uniform sampler2D PrevTexture; uniform sampler2D Prev2Texture; varying vec4 TEX0;
            void main() { gl_FragColor = texture2D(Texture, TEX0.xy) + texture2D(Prev2Texture, TEX0.xy); }
            #endif
        """.trimIndent())
        val preset = write("lcd.glslp", "shaders = \"1\"\nshader0 = \"lcd.glsl\"\n")
        val pipeline = RetroArchShaders.buildPipeline(RetroArchShaders.parsePreset(preset, temp.root), temp.root)
        val pass = pipeline.passes.single()
        assertTrue(pass.vertex.startsWith("#define VERTEX"))
        assertFalse("#version" in pass.vertex)
        assertEquals(3, pipeline.historyDepth)
        assertFalse(pipeline.needsEs3)
    }

    @Test fun referencesOverrideAndFeedbackIsDetected() {
        write("a.slang", "#version 450\n#pragma stage vertex\nvoid main(){}\n#pragma stage fragment\nlayout(set=0,binding=3) uniform sampler2D PassFeedback0;\nvoid main(){}\n")
        write("base.slangp", "shaders = 2\nshader0 = a.slang\nshader1 = a.slang\nscale_type1 = viewport\nalias0 = First\n")
        val preset = write("child/top.slangp", "#reference \"../base.slangp\"\nscale_type1 = absolute\nscale1 = 320\n")
        val parsed = RetroArchShaders.parsePreset(preset, temp.root)
        assertEquals(RetroArchShaders.ScaleType.ABSOLUTE, parsed.passes[1].scaleTypeX)
        assertEquals(320f, parsed.passes[1].scaleX)
        assertEquals("First", parsed.passes[0].alias)
        assertEquals(setOf(0), RetroArchShaders.buildPipeline(parsed, temp.root).feedbackPasses)
    }

    @Test(expected = IllegalArgumentException::class)
    fun pathsCannotLeaveThePack() {
        File(temp.root, "secret.slang").writeText("x")
        val pack = File(temp.root, "pack").apply { mkdirs() }
        val preset = File(pack, "p.slangp").apply { writeText("shaders = 1\nshader0 = ../secret.slang\n") }
        RetroArchShaders.parsePreset(preset, pack)
    }

    /**
     * Converts every preset of a real shader repo. Set RETRA_SHADER_CORPUS to a folder holding
     * extracted libretro slang-shaders / glsl-shaders; RETRA_SHADER_OUT receives the converted
     * sources for a WebGL2 compile check.
     */
    @Test fun corpus() {
        val corpus = System.getenv("RETRA_SHADER_CORPUS")?.let(::File)
        assumeTrue(corpus?.isDirectory == true)
        fun json(value: String) = buildString {
            append('"')
            value.forEach { c ->
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c.code < 32 -> append(' ')
                    else -> append(c)
                }
            }
            append('"')
        }
        val shaderIndex = LinkedHashMap<String, Int>()
        val shaders = ArrayList<String>()
        val presets = ArrayList<String>()
        val failures = LinkedHashMap<String, Int>()
        var ok = 0; var total = 0
        var parseNanos = 0L; var buildNanos = 0L
        // Optional: Vulkan GLSL per slang shader (index.vert / index.frag) for the native converter harness.
        val dump = System.getenv("RETRA_SLANG_DUMP")?.let { File(it).apply { mkdirs() } }
        val started = System.nanoTime()
        corpus!!.listFiles()!!.filter { it.isDirectory }.forEach { root ->
            val cache = HashMap<String, RetroArchShaders.CompiledPass>()
            root.walkTopDown().filter { it.isFile && RetroArchShaders.isPreset(it.name) }.forEach { file ->
                total++
                try {
                    val t0 = System.nanoTime()
                    val parsed = RetroArchShaders.parsePreset(file, root)
                    val t1 = System.nanoTime()
                    val pipeline = RetroArchShaders.buildPipeline(parsed, root, cache)
                    parseNanos += t1 - t0; buildNanos += System.nanoTime() - t1
                    val indices = pipeline.passes.map { pass ->
                        shaderIndex.getOrPut(pass.spec.shader.path) {
                            dump?.let { dir ->
                                if (pass.vulkanVertex != null) {
                                    File(dir, "${shaders.size}.vert").writeText(pass.vulkanVertex!!)
                                    File(dir, "${shaders.size}.frag").writeText(pass.vulkanFragment!!)
                                }
                            }
                            shaders += "{\"file\":${json(pass.spec.shader.relativeTo(corpus).invariantSeparatorsPath)},\"vs\":${json(pass.vertex)},\"fs\":${json(pass.fragment)}}"
                            shaders.size - 1
                        }
                    }
                    presets += "{\"preset\":${json(file.relativeTo(corpus).invariantSeparatorsPath)},\"shaders\":$indices}"
                    ok++
                } catch (error: Exception) {
                    val reason = (error.message ?: error.javaClass.simpleName).replace(Regex("[0-9]+"), "#").take(80)
                    failures[reason] = (failures[reason] ?: 0) + 1
                }
            }
        }
        println("CORPUS converted $ok / $total presets, ${shaders.size} unique shaders in ${(System.nanoTime() - started) / 1_000_000} ms " +
            "(parse ${parseNanos / 1_000_000} ms, convert ${buildNanos / 1_000_000} ms)")
        failures.entries.sortedByDescending { it.value }.take(15).forEach { println("CORPUS fail ${it.value}x ${it.key}") }
        System.getenv("RETRA_SHADER_OUT")?.let {
            File(it).writeText("{\"shaders\":[${shaders.joinToString(",")}],\"presets\":[${presets.joinToString(",")}]}")
        }
    }
}
