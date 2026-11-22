package com.retra.emulator

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/**
 * Runs a RetroArch multi-pass shader pipeline (see [RetroArchShaders]) on the GL thread.
 *
 * Every intermediate texture keeps the image's top row at texture row 0, like the emulator frame
 * Retra uploads; only the pass that draws to the screen flips Y. Supports pass aliases,
 * PassOutputN, OriginalHistoryN / PrevN, PassFeedbackN, LUT textures, float / sRGB framebuffers,
 * mipmapped inputs and preset parameters. Every method must run with the GL context current.
 */
internal class RetroArchShaderRenderer(
    private val pipeline: RetroArchShaders.Pipeline,
    private val es3: Boolean
) {
    private class Target(var texture: Int = 0, var framebuffer: Int = 0, var width: Int = 0, var height: Int = 0, var format: Int = 0)

    private enum class Ref { ORIGINAL, SOURCE, OUTPUT, VIEWPORT, PASS_OUTPUT, PASS_FEEDBACK, HISTORY, LUT }
    private class SizeUniform(val location: Int, val ref: Ref, val index: Int, val vec4: Boolean)
    private class SamplerUniform(val location: Int, val ref: Ref, val index: Int)
    private class ValueUniform(val location: Int, val type: Int, val value: Float)
    private class Program(
        val id: Int,
        val position: Int,
        val texCoords: IntArray,
        val colors: IntArray,
        val mvp: Int,
        val frameCount: Int,
        val frameCountType: Int,
        val frameDirection: Int,
        val frameDirectionType: Int,
        val sizes: List<SizeUniform>,
        val samplers: List<SamplerUniform>,
        val values: List<ValueUniform>
    )

    private val passes = pipeline.passes
    private val programs = ArrayList<Program>()
    private val targets = Array(passes.size) { Target() }
    private val feedbackTargets = Array(passes.size) { Target() }
    private val history = Array(pipeline.historyDepth) { Target() }
    private var historyHead = 0
    private val luts = ArrayList<Target>()
    private var blackTexture = 0
    private var stockProgram: Program? = null
    private var frameCount = 0L
    private var maxTextureSize = 4096
    private var maxTextureUnits = 8
    private var halfFloatTargets = false
    private val lastToScreen = passes.last().spec.let { it.scaleTypeX == null && it.scaleTypeY == null }

    // Per-draw scratch state.
    private val textureIds = IntArray(passes.size + 8)
    private val outputWidth = IntArray(passes.size)
    private val outputHeight = IntArray(passes.size)
    private var originalTexture = 0
    private var originalWidth = 1
    private var originalHeight = 1
    private var viewportWidth = 1
    private var viewportHeight = 1

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }
    private val offscreenMvp = floatArrayOf(2f, 0f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 0f, 1f, 0f, -1f, -1f, 0f, 1f)
    private val screenMvp = floatArrayOf(2f, 0f, 0f, 0f, 0f, -2f, 0f, 0f, 0f, 0f, 1f, 0f, -1f, 1f, 0f, 1f)

    /** Compiles every pass and loads LUTs. Returns an error message, or null on success. */
    fun init(): String? {
        if (pipeline.needsEs3 && !es3) return "Slang shaders need OpenGL ES 3.0, which this device does not provide"
        val ints = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, ints, 0); maxTextureSize = ints[0].coerceIn(2048, 8192)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_IMAGE_UNITS, ints, 0); maxTextureUnits = ints[0].coerceAtLeast(8)
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
        halfFloatTargets = es3 && ("GL_EXT_color_buffer_half_float" in extensions || "GL_EXT_color_buffer_float" in extensions)
        blackTexture = createTexture()
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
            ByteBuffer.allocateDirect(4).put(byteArrayOf(0, 0, 0, -1)).position(0))
        return try {
            passes.forEachIndexed { index, pass ->
                val label = "Pass ${index + 1} (${pass.spec.shader.name})"
                val program = try { link(pass.vertex, pass.fragment, label) } catch (error: IllegalStateException) {
                    // Legacy RetroArch GLSL that is not valid GLSL ES 1.00 often is valid 3.00: its
                    // COMPAT_* macros switch to in/out/texture() when __VERSION__ >= 130.
                    if (pass.spec.kind != RetroArchShaders.Kind.GLSL || !es3) throw error
                    runCatching { link("#version 300 es\n" + pass.vertex, "#version 300 es\n" + pass.fragment, label) }.getOrElse { throw error }
                }
                programs += describe(program, index)
            }
            pipeline.luts.forEach { luts += loadLut(it) }
            stockProgram = describe(link(STOCK_VERTEX, STOCK_FRAGMENT, "Stock pass"), -1)
            null
        } catch (error: Exception) {
            release()
            error.message ?: "Shader failed to compile"
        }
    }

    fun release() {
        programs.forEach { GLES20.glDeleteProgram(it.id) }
        programs.clear()
        stockProgram?.let { GLES20.glDeleteProgram(it.id) }
        stockProgram = null
        (targets + feedbackTargets + history + luts).forEach { releaseTarget(it) }
        luts.clear()
        if (blackTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(blackTexture), 0)
        blackTexture = 0
    }

    /**
     * Renders one frame. [inputTexture] holds the emulator frame; the result lands in the
     * viewport rectangle of the default framebuffer.
     */
    fun draw(inputTexture: Int, inputWidth: Int, inputHeight: Int, viewportX: Int, viewportY: Int, viewportW: Int, viewportH: Int,
             surfaceWidth: Int, surfaceHeight: Int, defaultLinear: Boolean) {
        if (programs.size != passes.size) return
        originalTexture = inputTexture; originalWidth = inputWidth; originalHeight = inputHeight
        viewportWidth = viewportW.coerceAtLeast(1); viewportHeight = viewportH.coerceAtLeast(1)
        for (index in passes.indices) {
            val pass = passes[index]
            val spec = pass.spec
            val toScreen = index == passes.lastIndex && lastToScreen
            val sourceWidth = if (index == 0) inputWidth else outputWidth[index - 1]
            val sourceHeight = if (index == 0) inputHeight else outputHeight[index - 1]
            val width = if (toScreen) viewportWidth else axis(spec.scaleTypeX, spec.scaleX, sourceWidth, viewportWidth)
            val height = if (toScreen) viewportHeight else axis(spec.scaleTypeY, spec.scaleY, sourceHeight, viewportHeight)
            outputWidth[index] = width; outputHeight[index] = height
            if (index in pipeline.feedbackPasses) {
                val previous = targets[index]; targets[index] = feedbackTargets[index]; feedbackTargets[index] = previous
            }
            if (toScreen) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(viewportX, viewportY, width, height)
            } else {
                ensureTarget(targets[index], width, height, formatFor(pass))
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targets[index].framebuffer)
                GLES20.glViewport(0, 0, width, height)
            }
            drawPass(programs[index], index, if (toScreen) screenMvp else offscreenMvp, defaultLinear)
        }
        if (!lastToScreen) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(viewportX, viewportY, viewportWidth, viewportHeight)
            val last = passes.lastIndex
            blit(targets[last].texture, outputWidth[last], outputHeight[last], screenMvp, defaultLinear)
        }
        if (history.isNotEmpty()) {
            historyHead = (historyHead - 1 + history.size) % history.size
            val slot = history[historyHead]
            ensureTarget(slot, inputWidth, inputHeight, GLES20.GL_RGBA)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, slot.framebuffer)
            GLES20.glViewport(0, 0, inputWidth, inputHeight)
            blit(inputTexture, inputWidth, inputHeight, offscreenMvp, false)
        }
        frameCount++
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    // ---------------------------------------------------------------- drawing

    private fun drawPass(program: Program, index: Int, mvp: FloatArray, defaultLinear: Boolean) {
        val spec = passes[index].spec
        GLES20.glUseProgram(program.id)
        bindQuad(program)
        if (program.mvp >= 0) GLES20.glUniformMatrix4fv(program.mvp, 1, false, mvp, 0)
        if (program.frameCount >= 0) {
            val count = if (spec.frameCountMod > 0) frameCount % spec.frameCountMod else frameCount
            setScalar(program.frameCount, program.frameCountType, count.toFloat(), count.toInt())
        }
        if (program.frameDirection >= 0) setScalar(program.frameDirection, program.frameDirectionType, 1f, 1)
        program.values.forEach { setScalar(it.location, it.type, it.value, it.value.toInt()) }
        program.sizes.forEach { uniform ->
            val (w, h) = size(uniform.ref, uniform.index, index)
            if (uniform.vec4) GLES20.glUniform4f(uniform.location, w, h, 1f / w, 1f / h)
            else GLES20.glUniform2f(uniform.location, w, h)
        }
        var unit = 0
        program.samplers.forEach { sampler ->
            if (unit >= maxTextureUnits) return@forEach
            val texture = texture(sampler.ref, sampler.index, index)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            if (sampler.ref != Ref.LUT && texture != blackTexture) {
                val consumer = when (sampler.ref) {
                    Ref.SOURCE -> index
                    Ref.PASS_OUTPUT, Ref.PASS_FEEDBACK -> sampler.index + 1
                    else -> 0
                }
                val consumerSpec = passes.getOrNull(consumer)?.spec
                val linear = consumerSpec?.filterLinear ?: defaultLinear
                val mipmap = sampler.ref == Ref.SOURCE && spec.mipmapInput && es3
                if (mipmap) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
                applySampling(linear, consumerSpec?.wrap ?: "clamp_to_edge", mipmap)
            }
            GLES20.glUniform1i(sampler.location, unit)
            unit++
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        unbindQuad(program)
    }

    private fun blit(texture: Int, width: Int, height: Int, mvp: FloatArray, linear: Boolean) {
        val program = stockProgram ?: return
        GLES20.glUseProgram(program.id)
        bindQuad(program)
        if (program.mvp >= 0) GLES20.glUniformMatrix4fv(program.mvp, 1, false, mvp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        applySampling(linear, "clamp_to_edge", false)
        program.samplers.forEach { GLES20.glUniform1i(it.location, 0) }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        unbindQuad(program)
    }

    private fun bindQuad(program: Program) {
        if (program.position >= 0) {
            quad.position(0)
            GLES20.glEnableVertexAttribArray(program.position)
            GLES20.glVertexAttribPointer(program.position, 2, GLES20.GL_FLOAT, false, 16, quad)
        }
        program.texCoords.forEach { location ->
            quad.position(2)
            GLES20.glEnableVertexAttribArray(location)
            GLES20.glVertexAttribPointer(location, 2, GLES20.GL_FLOAT, false, 16, quad)
        }
        program.colors.forEach { location ->
            GLES20.glDisableVertexAttribArray(location)
            GLES20.glVertexAttrib4f(location, 1f, 1f, 1f, 1f)
        }
        quad.position(0)
    }

    private fun unbindQuad(program: Program) {
        if (program.position >= 0) GLES20.glDisableVertexAttribArray(program.position)
        program.texCoords.forEach { GLES20.glDisableVertexAttribArray(it) }
    }

    private fun setScalar(location: Int, type: Int, value: Float, intValue: Int) {
        when (type) {
            GL_UNSIGNED_INT -> if (es3) GLES30.glUniform1ui(location, intValue.coerceAtLeast(0))
            GLES20.GL_INT, GLES20.GL_BOOL -> GLES20.glUniform1i(location, intValue)
            else -> GLES20.glUniform1f(location, value)
        }
    }

    private fun size(ref: Ref, index: Int, pass: Int): Pair<Float, Float> = when (ref) {
        Ref.ORIGINAL -> originalWidth.toFloat() to originalHeight.toFloat()
        Ref.SOURCE -> if (pass == 0) originalWidth.toFloat() to originalHeight.toFloat()
            else outputWidth[pass - 1].toFloat() to outputHeight[pass - 1].toFloat()
        Ref.OUTPUT -> outputWidth[pass].toFloat() to outputHeight[pass].toFloat()
        Ref.VIEWPORT -> viewportWidth.toFloat() to viewportHeight.toFloat()
        Ref.PASS_OUTPUT, Ref.PASS_FEEDBACK -> if (index in passes.indices && outputWidth[index] > 0)
            outputWidth[index].toFloat() to outputHeight[index].toFloat() else originalWidth.toFloat() to originalHeight.toFloat()
        Ref.HISTORY -> originalWidth.toFloat() to originalHeight.toFloat()
        Ref.LUT -> luts.getOrNull(index)?.let { it.width.toFloat() to it.height.toFloat() } ?: (1f to 1f)
    }

    private fun texture(ref: Ref, index: Int, pass: Int): Int = when (ref) {
        Ref.ORIGINAL -> originalTexture
        Ref.SOURCE -> if (pass == 0) originalTexture else targets[pass - 1].texture.takeIf { it != 0 } ?: blackTexture
        Ref.OUTPUT, Ref.VIEWPORT -> blackTexture
        Ref.PASS_OUTPUT -> targets.getOrNull(index)?.texture?.takeIf { it != 0 } ?: blackTexture
        Ref.PASS_FEEDBACK -> feedbackTargets.getOrNull(index)?.texture?.takeIf { it != 0 } ?: blackTexture
        Ref.HISTORY -> if (index == 0) originalTexture
            else history.getOrNull((historyHead + index - 1) % history.size.coerceAtLeast(1))?.texture?.takeIf { it != 0 } ?: blackTexture
        Ref.LUT -> luts.getOrNull(index)?.texture ?: blackTexture
    }

    private fun applySampling(linear: Boolean, wrap: String, mipmap: Boolean) {
        val mag = if (linear) GLES20.GL_LINEAR else GLES20.GL_NEAREST
        val min = when {
            mipmap && linear -> GLES20.GL_LINEAR_MIPMAP_LINEAR
            mipmap -> GLES20.GL_NEAREST_MIPMAP_NEAREST
            else -> mag
        }
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, min)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, mag)
        val mode = wrapMode(wrap)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, mode)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, mode)
    }

    /** OpenGL ES 2 only allows clamp-to-edge for non-power-of-two textures. */
    private fun wrapMode(wrap: String) = if (!es3) GLES20.GL_CLAMP_TO_EDGE else when (wrap) {
        "repeat" -> GLES20.GL_REPEAT
        "mirrored_repeat" -> GLES20.GL_MIRRORED_REPEAT
        else -> GLES20.GL_CLAMP_TO_EDGE
    }

    private fun axis(type: RetroArchShaders.ScaleType?, scale: Float, source: Int, viewport: Int): Int {
        val value = when (type) {
            RetroArchShaders.ScaleType.VIEWPORT -> viewport * scale
            RetroArchShaders.ScaleType.ABSOLUTE -> scale
            else -> source * scale
        }
        return value.roundToInt().coerceIn(1, maxTextureSize)
    }

    // ---------------------------------------------------------------- targets & textures

    private fun formatFor(pass: RetroArchShaders.CompiledPass): Int {
        val format = pass.format.orEmpty().uppercase()
        return when {
            (pass.spec.floatFramebuffer || "SFLOAT" in format) && halfFloatTargets -> GLES30.GL_RGBA16F
            (pass.spec.srgbFramebuffer || "SRGB" in format) && es3 -> GLES30.GL_SRGB8_ALPHA8
            es3 -> GLES30.GL_RGBA8
            else -> GLES20.GL_RGBA
        }
    }

    private fun ensureTarget(target: Target, width: Int, height: Int, format: Int) {
        if (target.texture != 0 && target.width == width && target.height == height && target.format == format) return
        if (target.texture == 0) target.texture = createTexture()
        if (target.framebuffer == 0) target.framebuffer = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
        fun allocate(internal: Int) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, target.texture)
            val type = if (internal == GLES30.GL_RGBA16F) GLES30.GL_HALF_FLOAT else GLES20.GL_UNSIGNED_BYTE
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, internal, width, height, 0, GLES20.GL_RGBA, type, null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, target.texture, 0)
        }
        allocate(format)
        var actual = format
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE && format != GLES20.GL_RGBA) {
            actual = if (es3) GLES30.GL_RGBA8 else GLES20.GL_RGBA
            allocate(actual)
        }
        // New targets start black so history / feedback reads are defined on the first frames.
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        target.width = width; target.height = height; target.format = format
    }

    private fun releaseTarget(target: Target) {
        if (target.texture != 0) GLES20.glDeleteTextures(1, intArrayOf(target.texture), 0)
        if (target.framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(target.framebuffer), 0)
        target.texture = 0; target.framebuffer = 0; target.width = 0; target.height = 0
    }

    private fun createTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun loadLut(spec: RetroArchShaders.LutSpec): Target {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(spec.file.path, bounds)
        require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096) { "Texture ${spec.file.name} is unsupported or too large" }
        val bitmap = BitmapFactory.decodeFile(spec.file.path, BitmapFactory.Options().apply {
            inPremultiplied = false; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("Could not read texture ${spec.file.name}")
        try {
            val rgba = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val pixels = ByteBuffer.allocateDirect(rgba.byteCount).order(ByteOrder.nativeOrder())
            rgba.copyPixelsToBuffer(pixels); pixels.position(0)
            val target = Target(texture = createTexture(), width = rgba.width, height = rgba.height)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, rgba.width, rgba.height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
            val mipmap = spec.mipmap && es3
            if (mipmap) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            applySampling(spec.linear, spec.wrap, mipmap)
            if (rgba !== bitmap) rgba.recycle()
            return target
        } finally { bitmap.recycle() }
    }

    // ---------------------------------------------------------------- programs

    private fun link(vertex: String, fragment: String, label: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex, label)
        val fs = try { compile(GLES20.GL_FRAGMENT_SHADER, fragment, label) } catch (error: Exception) { GLES20.glDeleteShader(vs); throw error }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs); GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program).orEmpty().trim()
            GLES20.glDeleteProgram(program)
            error("$label failed to link: ${log.lineSequence().firstOrNull().orEmpty().take(200)}")
        }
        return program
    }

    private fun compile(type: Int, source: String, label: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader).orEmpty().trim()
            GLES20.glDeleteShader(shader)
            val stage = if (type == GLES20.GL_VERTEX_SHADER) "vertex" else "fragment"
            error("$label $stage stage: ${log.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(200)}")
        }
        return shader
    }

    /** Resolves each active attribute and uniform of [program] to a RetroArch semantic once. */
    private fun describe(program: Int, passIndex: Int): Program {
        val pass = passes.getOrNull(passIndex)
        val slang = pass?.spec?.kind != RetroArchShaders.Kind.GLSL
        val count = IntArray(1); val size = IntArray(1); val type = IntArray(1); val length = IntArray(1)
        val nameBuffer = ByteArray(256)

        var position = -1
        val texCoords = ArrayList<Int>(); val colors = ArrayList<Int>()
        GLES20.glGetProgramiv(program, GLES20.GL_ACTIVE_ATTRIBUTES, count, 0)
        for (i in 0 until count[0]) {
            GLES20.glGetActiveAttrib(program, i, nameBuffer.size, length, 0, size, 0, type, 0, nameBuffer, 0)
            val name = String(nameBuffer, 0, length[0])
            val location = GLES20.glGetAttribLocation(program, name)
            if (location < 0) continue
            when {
                slang && passIndex >= 0 -> when (location) { 0 -> position = location; 1 -> texCoords += location }
                name == "Position" || name == "VertexCoord" -> position = location
                name.endsWith("TexCoord") -> texCoords += location
                name == "COLOR" -> colors += location
            }
        }

        var mvp = -1; var frameCount = -1; var frameCountType = 0; var frameDirection = -1; var frameDirectionType = 0
        val sizes = ArrayList<SizeUniform>(); val samplers = ArrayList<SamplerUniform>(); val values = ArrayList<ValueUniform>()
        val aliases = passes.mapIndexedNotNull { index, compiled -> compiled.alias?.let { it to index } }.toMap()
        val lutIndex = pipeline.luts.mapIndexed { index, lut -> lut.name to index }.toMap()
        GLES20.glGetProgramiv(program, GLES20.GL_ACTIVE_UNIFORMS, count, 0)
        for (i in 0 until count[0]) {
            GLES20.glGetActiveUniform(program, i, nameBuffer.size, length, 0, size, 0, type, 0, nameBuffer, 0)
            val glName = String(nameBuffer, 0, length[0]).removeSuffix("[0]")
            val location = GLES20.glGetUniformLocation(program, glName)
            if (location < 0) continue
            val name = pass?.uniformNames?.get(glName) ?: glName.substringAfterLast('.')
            val uniformType = type[0]
            if (uniformType == GLES20.GL_SAMPLER_2D) {
                samplerRef(name, slang, passIndex, aliases, lutIndex)?.let { (ref, index) -> samplers += SamplerUniform(location, ref, index) }
                    ?: run { samplers += SamplerUniform(location, Ref.OUTPUT, 0) } // unknown: bind black
                continue
            }
            when {
                name == "MVP" || name == "MVPMatrix" -> mvp = location
                name == "FrameCount" -> { frameCount = location; frameCountType = uniformType }
                name == "FrameDirection" -> { frameDirection = location; frameDirectionType = uniformType }
                name in CONSTANTS -> values += ValueUniform(location, uniformType, CONSTANTS.getValue(name))
                name in pipeline.parameterValues -> values += ValueUniform(location, uniformType, pipeline.parameterValues.getValue(name))
                uniformType == GLES20.GL_FLOAT_VEC2 || uniformType == GLES20.GL_FLOAT_VEC4 ->
                    sizeRef(name, slang, passIndex, aliases, lutIndex)?.let { (ref, index) ->
                        sizes += SizeUniform(location, ref, index, uniformType == GLES20.GL_FLOAT_VEC4)
                    }
            }
        }
        return Program(program, position, texCoords.toIntArray(), colors.toIntArray(), mvp, frameCount, frameCountType,
            frameDirection, frameDirectionType, sizes, samplers, values)
    }

    private fun samplerRef(name: String, slang: Boolean, pass: Int, aliases: Map<String, Int>, luts: Map<String, Int>): Pair<Ref, Int>? {
        lutIndex(name, luts)?.let { return Ref.LUT to it }
        return if (slang) when {
            name == "Source" -> Ref.SOURCE to 0
            name == "Original" -> Ref.ORIGINAL to 0
            else -> numbered(name, "PassOutput")?.let { Ref.PASS_OUTPUT to it }
                ?: numbered(name, "PassFeedback")?.let { Ref.PASS_FEEDBACK to it }
                ?: numbered(name, "OriginalHistory")?.let { Ref.HISTORY to it }
                ?: aliases[name]?.let { Ref.PASS_OUTPUT to it }
                ?: name.removeSuffix("Feedback").takeIf { it != name }?.let { aliases[it] }?.let { Ref.PASS_FEEDBACK to it }
        } else legacy(name, "Texture", pass, aliases)
    }

    private fun sizeRef(name: String, slang: Boolean, pass: Int, aliases: Map<String, Int>, luts: Map<String, Int>): Pair<Ref, Int>? {
        if (slang) {
            when (name) {
                "SourceSize" -> return Ref.SOURCE to 0
                "OriginalSize" -> return Ref.ORIGINAL to 0
                "OutputSize" -> return Ref.OUTPUT to 0
                "FinalViewportSize" -> return Ref.VIEWPORT to 0
            }
            if (!name.endsWith("Size")) return null
            val base = name.removeSuffix("Size")
            lutIndex(base, luts)?.let { return Ref.LUT to it }
            return numbered(base, "PassOutput")?.let { Ref.PASS_OUTPUT to it }
                ?: numbered(base, "PassFeedback")?.let { Ref.PASS_FEEDBACK to it }
                ?: numbered(base, "OriginalHistory")?.let { Ref.HISTORY to it }
                ?: aliases[base]?.let { Ref.PASS_OUTPUT to it }
                ?: base.removeSuffix("Feedback").takeIf { it != base }?.let { aliases[it] }?.let { Ref.PASS_FEEDBACK to it }
        }
        if (name == "OutputSize") return Ref.OUTPUT to 0
        val suffix = listOf("TextureSize", "InputSize").firstOrNull { name.endsWith(it) } ?: return null
        val base = name.removeSuffix(suffix)
        return if (base.isEmpty()) Ref.SOURCE to 0 else legacy(base + "Texture", "Texture", pass, aliases)
    }

    /** Legacy names: Texture, OrigTexture, PrevTexture, PrevNTexture, PassNTexture, PassPrevNTexture, aliasTexture. */
    private fun legacy(name: String, suffix: String, pass: Int, aliases: Map<String, Int>): Pair<Ref, Int>? {
        if (!name.endsWith(suffix)) return null
        val base = name.removeSuffix(suffix)
        return when {
            base.isEmpty() -> Ref.SOURCE to 0
            base == "Orig" -> Ref.ORIGINAL to 0
            base == "Prev" -> Ref.HISTORY to 1
            numbered(base, "PassPrev") != null -> (pass - numbered(base, "PassPrev")!!).let { if (it < 0) Ref.ORIGINAL to 0 else Ref.PASS_OUTPUT to it }
            numbered(base, "Pass") != null -> (numbered(base, "Pass")!! - 1).let { if (it < 0) Ref.ORIGINAL to 0 else Ref.PASS_OUTPUT to it }
            numbered(base, "Prev") != null -> Ref.HISTORY to numbered(base, "Prev")!! + 1
            else -> aliases[base]?.let { Ref.PASS_OUTPUT to it }
        }
    }

    private fun numbered(name: String, prefix: String): Int? =
        if (name.startsWith(prefix)) name.substring(prefix.length).takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull() else null

    private fun lutIndex(name: String, luts: Map<String, Int>) = luts[name]

    private companion object {
        const val GL_UNSIGNED_INT = 0x1405
        /** Semantics RetroArch fills with fixed values for single-frame, unrotated output. */
        val CONSTANTS = mapOf(
            "Rotation" to 0f, "TotalSubFrames" to 1f, "CurrentSubFrame" to 1f,
            "OriginalFPS" to 59.7275f, "FrameTimeDelta" to 16743f
        )
        const val STOCK_VERTEX = """
            attribute vec4 VertexCoord;
            attribute vec2 TexCoord;
            uniform mat4 MVPMatrix;
            varying vec2 vTex;
            void main() { gl_Position = MVPMatrix * VertexCoord; vTex = TexCoord; }
        """
        const val STOCK_FRAGMENT = """
            precision mediump float;
            uniform sampler2D Texture;
            varying vec2 vTex;
            void main() { gl_FragColor = texture2D(Texture, vTex); }
        """
    }
}
