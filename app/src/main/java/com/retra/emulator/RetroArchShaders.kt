package com.retra.emulator

import java.io.File
import java.util.Locale

/**
 * Reads RetroArch shader presets (.slangp / .glslp) and shaders (.slang / legacy .glsl) and turns
 * them into OpenGL ES sources Retra can run.
 *
 * - .slang (Vulkan GLSL 450) is rewritten as GLSL ES 3.00: uniform blocks become plain uniforms,
 *   varyings are matched by location, Vulkan-only syntax is removed.
 * - Legacy RetroArch .glsl already targets GLES through its COMPAT_* macros; each stage is
 *   compiled as GLSL ES 1.00 with VERTEX / FRAGMENT / PARAMETER_UNIFORM defined.
 *
 * Pure JVM code (no Android or GL classes) so it can be unit tested. Every file access stays
 * inside the imported pack folder.
 */
internal object RetroArchShaders {
    enum class Kind { SLANG, GLSL }
    enum class ScaleType { SOURCE, VIEWPORT, ABSOLUTE }

    data class Parameter(val name: String, val description: String, val default: Float, val min: Float, val max: Float)

    data class PassSpec(
        val shader: File,
        val kind: Kind,
        val filterLinear: Boolean?,
        val wrap: String,
        val scaleTypeX: ScaleType?,
        val scaleTypeY: ScaleType?,
        val scaleX: Float,
        val scaleY: Float,
        val alias: String?,
        val floatFramebuffer: Boolean,
        val srgbFramebuffer: Boolean,
        val mipmapInput: Boolean,
        val frameCountMod: Int
    )

    data class LutSpec(val name: String, val file: File, val linear: Boolean, val wrap: String, val mipmap: Boolean)

    data class Preset(val passes: List<PassSpec>, val luts: List<LutSpec>, val values: Map<String, String>)

    data class CompiledPass(
        val spec: PassSpec,
        val vertex: String,
        val fragment: String,
        /** Alias from the preset or the shader's `#pragma name`. */
        val alias: String?,
        /** Framebuffer format from `#pragma format` (slang only). */
        val format: String?,
        /** GLSL uniform name -> RetroArch semantic (block members are flattened to `block_member`). */
        val uniformNames: Map<String, String>,
        val parameters: List<Parameter>,
        /** The shader's own `#pragma name`, kept so a cached conversion can be reused by another preset. */
        val pragmaName: String? = null,
        /** Previous original frames this shader reads (OriginalHistoryN / PrevNTexture). */
        val historyDepth: Int = 0,
        /** PassFeedbackN indices this shader reads. */
        val feedbackIndices: Set<Int> = emptySet(),
        /** Names read as `<name>Feedback`, resolved against pass aliases per preset. */
        val feedbackNames: Set<String> = emptySet(),
        /** Slang only: the stage sources as Vulkan GLSL 4.50 for the native glslang converter. */
        val vulkanVertex: String? = null,
        val vulkanFragment: String? = null
    ) {
        // Keep multi-megabyte shader sources out of logs and exception messages.
        override fun toString() = "CompiledPass(${spec.shader.name})"
    }

    /** Scans a converted pass once for history / feedback reads, so presets sharing it don't rescan. */
    private fun withReferences(pass: CompiledPass): CompiledPass {
        val sources = pass.vertex + "\n" + pass.fragment
        val history = maxOf(
            HISTORY_REF.findAll(sources).maxOfOrNull { it.groupValues[1].toInt() } ?: 0,
            PREV_REF.findAll(sources).maxOfOrNull { (it.groupValues[1].toIntOrNull() ?: 0) + 1 } ?: 0
        ).coerceAtMost(MAX_HISTORY)
        return pass.copy(
            historyDepth = history,
            feedbackIndices = FEEDBACK_INDEX_REF.findAll(sources).map { it.groupValues[1].toInt() }.toSet(),
            feedbackNames = FEEDBACK_NAME_REF.findAll(sources).map { it.groupValues[1] }.toSet()
        )
    }

    data class Pipeline(
        val passes: List<CompiledPass>,
        val luts: List<LutSpec>,
        val parameterValues: Map<String, Float>,
        /** Number of previous original frames shaders read (OriginalHistoryN / PrevNTexture). */
        val historyDepth: Int,
        /** Passes whose previous-frame output is read (PassFeedbackN / aliasFeedback). */
        val feedbackPasses: Set<Int>,
        val needsEs3: Boolean
    )

    const val MAX_PASSES = 64
    private const val MAX_SOURCE_BYTES = 2 * 1024 * 1024
    private const val MAX_INCLUDE_DEPTH = 16

    fun isPreset(name: String) = name.substringAfterLast('.', "").lowercase(Locale.US) in setOf("slangp", "glslp")

    /** Legacy RetroArch .glsl files carry both stages behind VERTEX / FRAGMENT defines. */
    fun isRetroArchGlsl(source: String) =
        Regex("""#\s*(?:if\s+defined\s*\(?\s*VERTEX|ifdef\s+VERTEX|elif\s+defined\s*\(?\s*FRAGMENT)""").containsMatchIn(source)

    fun isSlang(source: String) = Regex("""#\s*pragma\s+stage\s+(vertex|fragment)""").containsMatchIn(source)

    // ---------------------------------------------------------------- presets

    fun parsePreset(file: File, root: File): Preset {
        val values = LinkedHashMap<String, Pair<String, File>>()
        loadPreset(file, root, values, 0)
        val count = values["shaders"]?.first?.trim()?.toIntOrNull() ?: error("Preset has no \"shaders\" count")
        require(count in 1..MAX_PASSES) { "Presets can have 1–$MAX_PASSES passes" }
        fun value(key: String) = values[key]?.first
        fun bool(key: String) = value(key)?.trim()?.lowercase(Locale.US)?.let { it == "true" || it == "1" }
        fun scaleType(key: String) = when (value(key)?.trim()?.lowercase(Locale.US)) {
            null, "" -> null
            "source" -> ScaleType.SOURCE
            "viewport" -> ScaleType.VIEWPORT
            "absolute" -> ScaleType.ABSOLUTE
            else -> error("Unknown $key")
        }
        val passes = (0 until count).map { i ->
            val (path, dir) = values["shader$i"] ?: error("Preset is missing shader$i")
            val shader = resolve(dir, path, root)
            val kind = when (shader.extension.lowercase(Locale.US)) {
                "slang" -> Kind.SLANG
                "glsl" -> Kind.GLSL
                else -> error("Pass $i is not a .slang or .glsl shader")
            }
            val type = scaleType("scale_type$i")
            val scale = value("scale$i")?.trim()?.toFloatOrNull()
            PassSpec(
                shader = shader, kind = kind,
                filterLinear = bool("filter_linear$i"),
                wrap = value("wrap_mode$i")?.trim()?.lowercase(Locale.US) ?: "clamp_to_border",
                scaleTypeX = scaleType("scale_type_x$i") ?: type,
                scaleTypeY = scaleType("scale_type_y$i") ?: type,
                scaleX = value("scale_x$i")?.trim()?.toFloatOrNull() ?: scale ?: 1f,
                scaleY = value("scale_y$i")?.trim()?.toFloatOrNull() ?: scale ?: 1f,
                alias = value("alias$i")?.trim()?.takeIf { IDENTIFIER.matches(it) },
                floatFramebuffer = bool("float_framebuffer$i") == true,
                srgbFramebuffer = bool("srgb_framebuffer$i") == true,
                mipmapInput = bool("mipmap_input$i") == true,
                frameCountMod = value("frame_count_mod$i")?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            )
        }
        val luts = value("textures").orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { name ->
            require(IDENTIFIER.matches(name)) { "Invalid texture name $name" }
            val (path, dir) = values[name] ?: error("Preset texture $name has no file")
            LutSpec(
                name = name,
                file = resolve(dir, path, root).also { require(it.isFile) { "Missing texture ${it.name}" } },
                linear = bool("${name}_linear") ?: false,
                wrap = value("${name}_wrap_mode")?.trim()?.lowercase(Locale.US) ?: "clamp_to_border",
                mipmap = bool("${name}_mipmap") ?: false
            )
        }
        return Preset(passes, luts, values.mapValues { it.value.first })
    }

    private fun loadPreset(file: File, root: File, values: MutableMap<String, Pair<String, File>>, depth: Int) {
        require(depth < MAX_INCLUDE_DEPTH) { "Preset #reference chain is too deep" }
        val text = readText(file)
        val dir = file.parentFile ?: root
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("#reference")) {
                val target = line.removePrefix("#reference").trim().trim('"')
                loadPreset(resolve(dir, target, root), root, values, depth + 1)
                return@forEach
            }
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEach
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val key = line.substring(0, eq).trim()
            var value = line.substring(eq + 1).trim()
            value = if (value.startsWith("\"")) value.drop(1).substringBefore('"')
                else value.substringBefore('#').substringBefore("//").trim()
            if (key.isNotEmpty()) values[key] = value to dir
        }
    }

    /** Resolves a preset/include path relative to [dir], refusing anything outside [root]. */
    fun resolve(dir: File, path: String, root: File): File {
        val clean = path.trim().trim('"').replace('\\', '/')
        require(clean.isNotEmpty() && !clean.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(clean)) { "Invalid path $path" }
        // Lexical normalization (no filesystem round trips): packs are plain copied files, no links.
        val base = root.absoluteFile.toPath().normalize()
        val target = dir.absoluteFile.toPath().resolve(clean).normalize()
        require(target.startsWith(base)) { "Path leaves the shader folder: $path" }
        return target.toFile().also { require(it.isFile) { "Missing file ${clean.substringAfterLast('/')}" } }
    }

    private fun readText(file: File): String {
        require(file.length() <= MAX_SOURCE_BYTES) { "${file.name} is too large" }
        return file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
    }

    // ---------------------------------------------------------------- pipeline

    /** [cache] (shader path -> conversion) lets an import convert each shared shader file once. */
    fun buildPipeline(preset: Preset, root: File, cache: MutableMap<String, CompiledPass>? = null): Pipeline {
        val passes = preset.passes.map { spec ->
            val cached = cache?.get(spec.shader.path)
            if (cached != null) return@map cached.copy(spec = spec, alias = spec.alias ?: cached.pragmaName)
            val compiled = withReferences(when (spec.kind) {
                Kind.SLANG -> compileSlang(spec, root)
                Kind.GLSL -> compileGlsl(spec, root)
            })
            cache?.put(spec.shader.path, compiled)
            compiled
        }
        val parameters = passes.flatMap { it.parameters }.associateBy { it.name }
        val values = parameters.mapValues { (name, parameter) ->
            preset.values[name]?.trim()?.toFloatOrNull()?.coerceIn(minOf(parameter.min, parameter.max), maxOf(parameter.min, parameter.max)) ?: parameter.default
        }
        val history = passes.maxOf { it.historyDepth }
        val aliases = passes.mapIndexedNotNull { index, pass -> pass.alias?.let { it to index } }.toMap()
        val feedback = passes.flatMap { pass -> pass.feedbackIndices + pass.feedbackNames.mapNotNull { aliases[it] } }
            .filter { it in passes.indices }.toSet()
        return Pipeline(passes, preset.luts, values, history, feedback, passes.any { it.spec.kind == Kind.SLANG })
    }

    const val MAX_HISTORY = 16

    // ---------------------------------------------------------------- includes & pragmas

    private fun inlineIncludes(file: File, root: File, depth: Int = 0, seen: MutableSet<String> = mutableSetOf()): String {
        require(depth < MAX_INCLUDE_DEPTH) { "#include chain is too deep" }
        val text = readText(file)
        val dir = file.parentFile ?: root
        val out = StringBuilder()
        text.lineSequence().forEach { line ->
            val match = INCLUDE.find(line)
            if (match != null) {
                val target = resolve(dir, match.groupValues[1], root)
                out.append(inlineIncludes(target, root, depth + 1, seen)).append('\n')
            } else if (PRAGMA_ONCE.matches(line)) {
                if (!seen.add(file.path)) return ""
            } else out.append(line).append('\n')
        }
        return out.toString()
    }

    private fun parameters(source: String): List<Parameter> = PARAMETER.findAll(source).mapNotNull { match ->
        val numbers = match.groupValues[3].trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
        if (numbers.size < 3) null else Parameter(match.groupValues[1], match.groupValues[2], numbers[0], numbers[1], numbers[2])
    }.distinctBy { it.name }.toList()

    // ---------------------------------------------------------------- slang

    fun compileSlang(spec: PassSpec, root: File): CompiledPass {
        val full = inlineIncludes(spec.shader, root)
        val parameters = parameters(full)
        val name = Regex("""(?m)^\s*#\s*pragma\s+name\s+(\w+)""").find(full)?.groupValues?.get(1)
        val format = Regex("""(?m)^\s*#\s*pragma\s+format\s+(\w+)""").find(full)?.groupValues?.get(1)
        val shared = StringBuilder(); val vertex = StringBuilder(); val fragment = StringBuilder()
        var target = shared
        full.lineSequence().forEach { line ->
            val stage = STAGE.find(line)
            when {
                stage != null -> target = if (stage.groupValues[1] == "vertex") vertex else fragment
                else -> target.append(line).append('\n')
            }
        }
        require(vertex.isNotBlank() && fragment.isNotBlank()) { "${spec.shader.name} needs vertex and fragment stages" }
        val uniforms = LinkedHashMap<String, String>()
        val vs = convertSlangStage(shared.toString() + vertex, vertexStage = true, uniforms)
        val fs = convertSlangStage(shared.toString() + fragment, vertexStage = false, uniforms)
        // Untouched Vulkan GLSL per stage (RetroArch pragmas removed) for the glslang + SPIRV-Cross path.
        fun vulkan(stage: CharSequence) = (shared.toString() + stage).lineSequence()
            .filterNot { PRAGMA.containsMatchIn(it.trim()) }.joinToString("\n")
        return CompiledPass(spec, vs, fs, spec.alias ?: name, format, uniforms, parameters, name,
            vulkanVertex = vulkan(vertex), vulkanFragment = vulkan(fragment))
    }

    private fun convertSlangStage(source: String, vertexStage: Boolean, uniforms: MutableMap<String, String>): String {
        var text = stripComments(source)
        text = text.lineSequence().filterNot { line ->
            val trimmed = line.trim()
            trimmed.startsWith("#version") || trimmed.startsWith("#extension") || PRAGMA.containsMatchIn(trimmed)
        }.joinToString("\n")

        // Named uniform blocks (push constants, UBOs) become uniform structs, so `params.X` keeps
        // working and #if / macros inside the block still go through the preprocessor. GL reports
        // their members as "params.X"; the renderer maps them by the part after the dot.
        text = UNIFORM_BLOCK.replace(text) { match ->
            val block = match.groupValues[1]
            val body = match.groupValues[2].replace(Regex("""layout\s*\([^)]*\)"""), "")
            val instance = match.groupValues[3]
            if (instance.isNotEmpty()) "\nstruct _retra_$block {\n$body\n};\nuniform _retra_$block $instance;\n"
            else body.lines().joinToString("\n") { line ->
                // Anonymous block: members become plain uniforms; preprocessor lines stay as they are.
                if (line.trim().startsWith("#") || line.isBlank()) line
                else line.split(';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ") { member ->
                    member.substringAfterLast(' ').split(',').forEach { uniforms[it.trim().substringBefore('[')] = it.trim().substringBefore('[') }
                    "uniform $member;"
                }
            }
        }

        // Varyings are matched by location in Vulkan and by name in GLES: give both stages the same name.
        text = VARYING.replace(text) { match ->
            val location = match.groupValues[1]
            val interpolation = match.groupValues[2].replace("noperspective", "").trim()
            val direction = match.groupValues[3]
            val type = match.groupValues[4]
            val name = match.groupValues[5]
            val array = match.groupValues[6]
            val isVarying = (vertexStage && direction == "out") || (!vertexStage && direction == "in")
            if (!isVarying) match.value.replace("noperspective", "")
            else "${if (interpolation.isNotEmpty()) "$interpolation " else ""}$direction $type _retra_v$location$array;\n#define $name _retra_v$location\n"
        }
        // Remaining layouts (set/binding on samplers, std140, push_constant) are Vulkan-only.
        text = Regex("""layout\s*\(\s*(?:std140|std430|push_constant|set\s*=\s*\d+|binding\s*=\s*\d+|\s|,)+\)\s*""").replace(text, "")
        text = text.replace(Regex("""\bgl_VertexIndex\b"""), "gl_VertexID").replace(Regex("""\bgl_InstanceIndex\b"""), "gl_InstanceID")
        text = dropLocalFloatConst(text)
        return ES3_HEADER + asciiOnly(text)
    }

    // ---------------------------------------------------------------- legacy glsl

    fun compileGlsl(spec: PassSpec, root: File): CompiledPass {
        val full = inlineIncludes(spec.shader, root)
        require(isRetroArchGlsl(full)) { "${spec.shader.name} is not a RetroArch GLSL shader" }
        val parameters = parameters(full)
        val body = asciiOnly(stripComments(full)).lineSequence().filterNot { line ->
            val trimmed = line.trim()
            trimmed.startsWith("#version") || PRAGMA.containsMatchIn(trimmed)
        }.joinToString("\n")
        fun stage(define: String, precision: String) = "#define $define\n#define PARAMETER_UNIFORM\n$precision$body\n"
        val vertex = stage("VERTEX", "")
        val fragment = stage("FRAGMENT", "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n")
        return CompiledPass(spec, vertex, fragment, spec.alias, null, emptyMap(), parameters)
    }

    /** Wraps a single shader file as a one-pass preset. */
    fun singlePassPreset(shader: File): String {
        require(shader.extension.lowercase(Locale.US) in setOf("slang", "glsl")) { "Not a RetroArch shader" }
        return "shaders = 1\nshader0 = \"${shader.name}\"\nscale_type0 = viewport\n"
    }

    // ---------------------------------------------------------------- text helpers

    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (c == '/' && i + 1 < source.length && source[i + 1] == '/') {
                while (i < source.length && source[i] != '\n') i++
            } else if (c == '/' && i + 1 < source.length && source[i + 1] == '*') {
                val end = source.indexOf("*/", i + 2)
                val stop = if (end < 0) source.length else end + 2
                // Keep line structure so preprocessor lines stay on their own lines.
                for (k in i until stop) if (source[k] == '\n') out.append('\n')
                out.append(' ')
                i = stop
            } else { out.append(c); i++ }
        }
        return out.toString()
    }

    /** GLSL ES rejects characters outside its source character set even in unused code. */
    private fun asciiOnly(source: String): String {
        fun allowed(c: Char) = c == '\n' || c == '\t' || c.code in 32..126
        if (source.all(::allowed)) return source
        val out = StringBuilder(source.length)
        source.forEach { out.append(if (allowed(it)) it else ' ') }
        return out.toString()
    }

    /**
     * GLSL 4.20+ allows `const` locals with run-time initializers; GLSL ES does not. Dropping
     * `const` from float/vector/matrix locals keeps the meaning (ints are left alone because
     * they may size arrays).
     */
    private fun dropLocalFloatConst(source: String): String {
        val out = StringBuilder(source.length)
        var depth = 0
        var i = 0
        // Anchored match at i only (lookingAt), so the scan stays linear in the source length.
        val local = LOCAL_CONST.matcher(source)
        while (i < source.length) {
            val c = source[i]
            when (c) {
                '{' -> depth++
                '}' -> depth = (depth - 1).coerceAtLeast(0)
            }
            if (depth > 0 && c == 'c' && (i == 0 || !source[i - 1].isLetterOrDigit() && source[i - 1] != '_')) {
                local.region(i, source.length)
                if (local.lookingAt()) {
                    out.append(local.group(1)); i = local.end(); continue
                }
            }
            out.append(c); i++
        }
        return out.toString()
    }

    private val LOCAL_CONST = java.util.regex.Pattern.compile("""const\s+((?:(?:lowp|mediump|highp)\s+)?(?:float|vec[234]|mat[234](?:x[234])?)\b)""")
    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
    private val HISTORY_REF = Regex("""\bOriginalHistory(?:Size)?(\d+)\b""")
    private val PREV_REF = Regex("""\bPrev(\d?)(?:Texture|TextureSize|InputSize|TexCoord)\b""")
    private val FEEDBACK_INDEX_REF = Regex("""\bPassFeedback(?:Size)?(\d+)\b""")
    private val FEEDBACK_NAME_REF = Regex("""\b(\w+?)Feedback(?:Size)?\b""")
    private val INCLUDE = Regex("""^\s*#\s*include\s+["<]([^">]+)[">]""")
    private val PRAGMA_ONCE = Regex("""^\s*#\s*pragma\s+once\s*$""")
    private val STAGE = Regex("""^\s*#\s*pragma\s+stage\s+(vertex|fragment)\b""")
    private val PRAGMA = Regex("""^#\s*pragma\b""")
    private val PARAMETER = Regex("""(?m)^\s*#\s*pragma\s+parameter\s+(\w+)\s+"([^"]*)"\s+([-+0-9.eE \t]+)$""")
    private val UNIFORM_BLOCK = Regex("""(?:layout\s*\([^)]*\)\s*)?uniform\s+(\w+)\s*\{([^}]*)\}\s*(\w*)\s*;""")
    private val VARYING = Regex(
        """layout\s*\(\s*location\s*=\s*(\d+)\s*\)\s*((?:(?:flat|noperspective|smooth|centroid)\s+)*)(in|out)\s+((?:(?:lowp|mediump|highp)\s+)?\w+)\s+(\w+)\s*(\[[^\]]*\])?\s*;"""
    )
    private const val ES3_HEADER = "#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\n"
}
