package com.retra.emulator

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Owns Retra's optional GLSL fragment shaders.
 *
 * Built-in presets use the same small OpenGL ES 2.0 interface as user shaders:
 *   uniform sampler2D uTexture;
 *   uniform vec2 uTextureSize;
 *   uniform vec2 uOutputSize;
 *   uniform float uTime;
 *   varying vec2 vTexCoord;
 *
 * The normal renderer remains active when "none" is selected, so the feature
 * adds no shader-view GPU/CPU cost unless the user explicitly enables a preset.
 */
class ShaderRepository(
    context: Context,
    private val prefs: RetraPreferences
) {
    data class Option(
        val id: String,
        val label: String,
        val builtIn: Boolean,
        val impact: String,
        val description: String,
        /** Imported RetroArch pack name and id (null for built-in and Retra GLSL shaders). */
        val group: String? = null,
        val packId: String? = null
    )

    /** What an import added: one option is selected right away, several are listed for the user. */
    data class InstallResult(val options: List<Option>, val packName: String, val skipped: Int)

    private data class PackPreset(val path: String, val passes: Int, val kind: String)
    private data class Pack(val id: String, val name: String, val presets: List<PackPreset>)

    private data class BuiltInShader(
        val id: String,
        val label: String,
        val impact: String,
        val description: String,
        val source: String?
    )

    private val appContext = context.applicationContext
    private val shaderDir = File(appContext.filesDir, "Shaders").apply { mkdirs() }
    /** Imported RetroArch packs: one folder per import, with a manifest of its working presets. */
    private val packsDir = File(shaderDir, "RetroArch").apply { mkdirs() }
    @Volatile private var packCache: List<Pack>? = null
    private val pipelineCache = java.util.concurrent.ConcurrentHashMap<String, RetroArchShaders.Pipeline>()

    fun selectedId(): String {
        val stored = prefs.getString(SELECTED_PREF, "none") ?: "none"
        if (BUILT_IN_BY_ID.containsKey(stored)) return stored
        if (stored.startsWith("custom:")) {
            val safe = sanitizeName(stored.removePrefix("custom:"))
            if (File(shaderDir, "$safe.$STORED_EXTENSION").exists()) return stored
        }
        if (stored.startsWith(RA_PREFIX) && retroArchOption(stored) != null) return stored
        // Unknown/stale selections are intentionally reset instead of trying to
        // compile an ID that no longer exists in this build.
        prefs.edit().putString(SELECTED_PREF, "none").apply()
        return "none"
    }

    fun select(id: String) {
        val resolved = optionFor(id)?.id ?: "none"
        prefs.edit().putString(SELECTED_PREF, resolved).apply()
    }

    fun options(): List<Option> = buildList {
        BUILT_INS.forEach { shader ->
            add(
                Option(
                    id = shader.id,
                    label = shader.label,
                    builtIn = true,
                    impact = shader.impact,
                    description = shader.description
                )
            )
        }
        shaderDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(Locale.US) == STORED_EXTENSION }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?.forEach { file ->
                val id = "custom:${file.nameWithoutExtension}"
                val label = file.nameWithoutExtension.replace('_', ' ').replace('-', ' ').trim()
                    .ifBlank { "Custom shader" }
                add(
                    Option(
                        id = id,
                        label = label,
                        builtIn = false,
                        impact = "Custom",
                        description = "Installed GLSL shader"
                    )
                )
            }
        packs().forEach { pack -> pack.presets.forEach { add(presetOption(pack, it)) } }
    }

    private fun presetOption(pack: Pack, preset: PackPreset): Option {
        val folder = preset.path.substringBeforeLast('/', "").substringAfterLast('/')
        val passes = if (preset.passes == 1) "1 pass" else "${preset.passes} passes"
        return Option(
            id = "$RA_PREFIX${pack.id}/${preset.path}",
            label = preset.path.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').trim().ifBlank { "Shader" },
            builtIn = false,
            impact = when { preset.passes <= 1 -> "Low"; preset.passes <= 3 -> "Medium"; else -> "High" },
            description = listOf(folder, passes, if (preset.kind == "slang") "Slang" else "GLSL").filter { it.isNotEmpty() }.joinToString(" • "),
            group = pack.name,
            packId = pack.id
        )
    }

    private fun retroArchOption(id: String): Pair<Pack, PackPreset>? {
        val rest = id.removePrefix(RA_PREFIX)
        val pack = packs().firstOrNull { rest.startsWith(it.id + "/") } ?: return null
        val preset = pack.presets.firstOrNull { it.path == rest.removePrefix(pack.id + "/") } ?: return null
        return pack to preset
    }

    fun isRetroArch(id: String) = id.startsWith(RA_PREFIX)

    /** Parses and converts a RetroArch preset (text only; GL compilation happens on the render thread). */
    internal fun pipelineFor(id: String): RetroArchShaders.Pipeline = pipelineCache.getOrPut(id) {
        val (pack, preset) = retroArchOption(id) ?: error("Shader is no longer installed")
        val root = File(packsDir, pack.id)
        val file = RetroArchShaders.resolve(root, preset.path, root)
        SlangNative.upgrade(RetroArchShaders.buildPipeline(RetroArchShaders.parsePreset(file, root), root))
    }

    private fun packs(): List<Pack> = packCache ?: (packsDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
        runCatching {
            val json = JSONObject(File(dir, MANIFEST).readText())
            val presets = json.getJSONArray("presets")
            Pack(dir.name, json.getString("name"), (0 until presets.length()).map { i ->
                val row = presets.getJSONObject(i)
                PackPreset(row.getString("path"), row.optInt("passes", 1), row.optString("kind", "slang"))
            })
        }.getOrNull()
    }?.sortedBy { it.name.lowercase(Locale.US) } ?: emptyList()).also { packCache = it }

    fun optionsJson(): JSONArray = JSONArray().apply {
        options().forEach { option ->
            put(JSONObject().apply {
                put("id", option.id)
                put("label", option.label)
                put("builtIn", option.builtIn)
                put("impact", option.impact)
                put("description", option.description)
                option.group?.let { put("group", it) }
                option.packId?.let { put("packId", it) }
            })
        }
    }

    fun sourceFor(id: String): String? {
        val builtIn = BUILT_IN_BY_ID[id]
        if (builtIn != null) return builtIn.source
        if (!id.startsWith("custom:")) return null
        val safe = sanitizeName(id.removePrefix("custom:"))
        return File(shaderDir, "$safe.$STORED_EXTENSION").takeIf { it.exists() }?.readText()
    }

    fun optionFor(id: String): Option? = options().firstOrNull { it.id == id }

    /**
     * Installs a picked file: a .zip of RetroArch shaders, a single .slang or RetroArch .glsl
     * shader, or a Retra GLSL fragment shader (.glsl/.frag/.fs/.fsh).
     */
    fun install(uri: Uri): InstallResult {
        val displayName = queryDisplayName(uri) ?: "custom_shader.glsl"
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.US)
        val baseName = displayName.substringBeforeLast('.', displayName)
        when (extension) {
            "zip" -> return installPack(baseName) { dir -> extractZip(uri, dir) }
            "slangp", "glslp" -> error("Presets use other shader files. Import the folder that contains it, or a .zip of that folder")
        }
        require(extension in ALLOWED_EXTENSIONS || extension == "slang") {
            "Choose a RetroArch .zip, .slang or .glsl shader, or a .frag/.fs/.fsh shader"
        }
        val source = appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            val text = reader.readText()
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_SHADER_BYTES) { "Shader is too large" }
            text
        } ?: error("Could not read shader")

        if (extension == "slang" || (extension == "glsl" && RetroArchShaders.isRetroArchGlsl(source))) {
            require(!INCLUDE_LINE.containsMatchIn(source)) { "This shader includes other files. Import its folder, or a .zip of it" }
            return installPack(baseName) { dir ->
                File(dir, sanitizeName(baseName).ifBlank { "shader" } + "." + extension).writeText(source)
            }
        }
        return InstallResult(listOf(installRetraShader(displayName, source)), "Custom shader", 0)
    }

    /** Copies a picked folder (Storage Access Framework tree) as a RetroArch shader pack. */
    fun installFolder(treeUri: Uri): InstallResult {
        val tree = DocumentFile.fromTreeUri(appContext, treeUri) ?: error("Could not open folder")
        return installPack(tree.name ?: "Shaders") { dir ->
            var total = 0L
            var files = 0
            fun copy(node: DocumentFile, target: File, depth: Int) {
                require(depth < 16) { "Folder is nested too deeply" }
                node.listFiles().forEach { child ->
                    val name = child.name?.let(::safeEntryName) ?: return@forEach
                    if (child.isDirectory) copy(child, File(target, name).apply { mkdirs() }, depth + 1)
                    else if (packFileAllowed(name)) {
                        require(++files <= MAX_PACK_FILES) { "Folder has too many files" }
                        val input = appContext.contentResolver.openInputStream(child.uri) ?: return@forEach
                        total += copyLimited(input, File(target, name))
                        require(total <= MAX_PACK_BYTES) { "Shader folder is too large" }
                    }
                }
            }
            copy(tree, dir, 0)
        }
    }

    fun removePack(packId: String): Boolean {
        val dir = File(packsDir, sanitizeName(packId))
        if (!dir.isDirectory || dir.parentFile?.canonicalPath != packsDir.canonicalPath) return false
        val selected = prefs.getString(SELECTED_PREF, "none").orEmpty()
        val removed = dir.deleteRecursively()
        packCache = null
        pipelineCache.clear()
        if (selected.startsWith("$RA_PREFIX$packId/")) select("none")
        return removed
    }

    /** Fills a fresh pack folder, keeps the presets that parse and convert, and writes the manifest. */
    private fun installPack(rawName: String, fill: (File) -> Unit): InstallResult {
        val name = rawName.replace(PACK_SUFFIX, "").replace('_', ' ').trim().ifBlank { "Shaders" }
        val id = uniquePackId(name)
        val dir = File(packsDir, id).apply { mkdirs() }
        try {
            fill(dir)
            val presets = dir.walkTopDown().maxDepth(16).filter { it.isFile && RetroArchShaders.isPreset(it.name) }.toMutableList()
            if (presets.isEmpty()) {
                // Loose shaders without presets: wrap each standalone RetroArch shader as a one-pass preset.
                dir.walkTopDown().maxDepth(16).filter { it.isFile && it.extension.lowercase(Locale.US) in setOf("slang", "glsl") }.toList().forEach { shader ->
                    val text = runCatching { shader.readText() }.getOrDefault("")
                    val standalone = if (shader.extension.equals("slang", true)) RetroArchShaders.isSlang(text) else RetroArchShaders.isRetroArchGlsl(text)
                    val preset = File(shader.parentFile, "${shader.nameWithoutExtension}.${shader.extension.lowercase(Locale.US)}p")
                    if (standalone && !preset.exists()) { preset.writeText(RetroArchShaders.singlePassPreset(shader)); presets += preset }
                }
            }
            require(presets.size <= MAX_PRESETS) { "This pack has more than $MAX_PRESETS presets" }
            var firstError: String? = null
            val converted = HashMap<String, RetroArchShaders.CompiledPass>()
            val valid = presets.sortedBy { it.path.lowercase(Locale.US) }.mapNotNull { file ->
                try {
                    val pipeline = RetroArchShaders.buildPipeline(RetroArchShaders.parsePreset(file, dir), dir, converted)
                    PackPreset(file.relativeTo(dir).invariantSeparatorsPath, pipeline.passes.size, if (pipeline.needsEs3) "slang" else "glsl")
                } catch (error: Exception) {
                    if (firstError == null) firstError = "${file.name}: ${error.message}"
                    null
                }
            }
            require(valid.isNotEmpty()) { firstError ?: "No RetroArch shaders or presets found" }
            File(dir, MANIFEST).writeText(JSONObject().put("name", name).put("presets", JSONArray().apply {
                valid.forEach { put(JSONObject().put("path", it.path).put("passes", it.passes).put("kind", it.kind)) }
            }).toString())
            packCache = null
            val pack = Pack(id, name, valid)
            return InstallResult(valid.map { presetOption(pack, it) }, name, presets.size - valid.size)
        } catch (error: Throwable) {
            dir.deleteRecursively()
            packCache = null
            throw error
        }
    }

    private fun extractZip(uri: Uri, dir: File) {
        val input = appContext.contentResolver.openInputStream(uri) ?: error("Could not read the .zip")
        ZipInputStream(input.buffered()).use { zip ->
            var total = 0L
            var files = 0
            val root = dir.canonicalPath + File.separator
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !packFileAllowed(entry.name)) continue
                val parts = entry.name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
                if (parts.isEmpty() || parts.size > 16 || parts.any { it == ".." }) continue
                val names = parts.map { safeEntryName(it) ?: return@map null }
                if (names.any { it == null }) continue
                val target = File(dir, names.joinToString(File.separator))
                if (!target.canonicalPath.startsWith(root)) continue
                require(++files <= MAX_PACK_FILES) { "The .zip has too many files" }
                target.parentFile?.mkdirs()
                total += copyLimited(zip, target, closeInput = false)
                require(total <= MAX_PACK_BYTES) { "The .zip is too large once extracted" }
            }
        }
    }

    private fun copyLimited(input: InputStream, target: File, closeInput: Boolean = true): Long {
        var count = 0L
        try {
            target.outputStream().use { output ->
                val buffer = ByteArray(32768)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    require(count <= MAX_PACK_FILE_BYTES) { "${target.name} is too large" }
                    output.write(buffer, 0, read)
                }
            }
        } finally { if (closeInput) input.close() }
        return count
    }

    private fun packFileAllowed(name: String) = name.substringAfterLast('.', "").lowercase(Locale.US) in PACK_EXTENSIONS

    private fun safeEntryName(name: String): String? = name.replace(UNSAFE_NAME_CHARS, "_").trim()
        .takeIf { it.isNotEmpty() && it != "." && it != ".." }?.take(120)

    private fun uniquePackId(name: String): String {
        val base = sanitizeName(name).ifBlank { "shaders" }.take(40)
        if (!File(packsDir, base).exists()) return base
        for (index in 2..999) if (!File(packsDir, "${base}_$index").exists()) return "${base}_$index"
        return "${base}_${System.currentTimeMillis()}"
    }

    private fun installRetraShader(displayName: String, source: String): Option {
        validateSource(source)
        val safeBase = uniqueSafeBase(displayName.substringBeforeLast('.', displayName))
        val target = File(shaderDir, "$safeBase.$STORED_EXTENSION")
        target.writeText(source)
        return Option(
            id = "custom:$safeBase",
            label = safeBase.replace('_', ' ').replace('-', ' '),
            builtIn = false,
            impact = "Custom",
            description = "Installed GLSL shader"
        )
    }

    fun remove(id: String): Boolean {
        if (!id.startsWith("custom:")) return false
        val safe = sanitizeName(id.removePrefix("custom:"))
        val deleted = File(shaderDir, "$safe.$STORED_EXTENSION").delete()
        if (selectedId() == id) select("none")
        return deleted
    }

    private fun validateSource(source: String) {
        require(!RetroArchShaders.isSlang(source)) { "This is a RetroArch slang shader: save it as .slang and import again" }
        val lowered = source.lowercase(Locale.US)
        require("void main" in lowered) { "Shader must contain void main()" }
        require("#include" !in lowered) { "#include is not supported" }
        require("gl_fragcolor" in lowered) { "Shader must write gl_FragColor" }
        require("utexture" in lowered) {
            "Shader must sample uniform sampler2D uTexture"
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) null
                    else cursor.getString(0)
                }
        }.getOrNull()
    }

    private fun uniqueSafeBase(raw: String): String {
        val initial = sanitizeName(raw).ifBlank { "custom_shader" }
        if (!File(shaderDir, "$initial.$STORED_EXTENSION").exists()) return initial
        for (index in 2..999) {
            val candidate = "${initial}_$index"
            if (!File(shaderDir, "$candidate.$STORED_EXTENSION").exists()) return candidate
        }
        return "${initial}_${System.currentTimeMillis()}"
    }

    private fun sanitizeName(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_', '.', '-')
        .take(64)

    companion object {
        const val SELECTED_PREF = "glsl_shader_selected_v1"
        private const val RA_PREFIX = "ra:"
        private const val MANIFEST = ".retra-pack.json"
        private val INCLUDE_LINE = Regex("""(?m)^\s*#\s*include\b""")
        private val PACK_SUFFIX = Regex("[-_]+(master|main)$", RegexOption.IGNORE_CASE)
        private val UNSAFE_NAME_CHARS = Regex("""[\u0000-\u001f/\\:*?"<>|]""")
        private const val MAX_PRESETS = 4000
        private const val MAX_PACK_FILES = 20000
        private const val MAX_PACK_FILE_BYTES = 32L * 1024 * 1024
        private const val MAX_PACK_BYTES = 768L * 1024 * 1024
        private val PACK_EXTENSIONS = setOf("slang", "slangp", "glsl", "glslp", "inc", "h", "hlsl", "fx", "txt",
            "png", "jpg", "jpeg", "bmp", "webp")
        private const val STORED_EXTENSION = "glsl"
        private const val MAX_SHADER_BYTES = 128 * 1024
        private val ALLOWED_EXTENSIONS = setOf("glsl", "frag", "fs", "fsh")

        private val BUILT_INS = listOf(
            BuiltInShader(
                id = "none",
                label = "Off",
                impact = "None",
                description = "Default renderer • no extra GPU cost",
                source = null
            ),
            BuiltInShader(
                id = "gba-color",
                label = "GBA Color Corrected",
                impact = "Low",
                description = "Balanced handheld-style color response",
                source = GBA_COLOR_CORRECTED_SHADER
            ),
            BuiltInShader(
                id = "sharp",
                label = "Sharp",
                impact = "Low",
                description = "Crisp pixels with subtle edge detail",
                source = SHARP_SHADER
            ),
            BuiltInShader(
                id = "smooth",
                label = "Smooth",
                impact = "Low",
                description = "Soft multi-sample scaling for uneven sizes",
                source = SMOOTH_SHADER
            ),
            BuiltInShader(
                id = "pixel-perfect",
                label = "Pixel Perfect",
                impact = "Low",
                description = "Locks sampling to original pixel centers",
                source = PIXEL_PERFECT_SHADER
            ),
            BuiltInShader(
                id = "lcd-grid",
                label = "LCD Grid",
                impact = "Medium",
                description = "Subtle handheld LCD cell structure",
                source = LCD_GRID_SHADER
            ),
            BuiltInShader(
                id = "lcd-response",
                label = "LCD Response",
                impact = "Medium",
                description = "Gentle LCD-style pixel response softness",
                source = LCD_RESPONSE_SHADER
            ),
            BuiltInShader(
                id = "scanlines",
                label = "Scanlines",
                impact = "Low",
                description = "Light retro horizontal scanline texture",
                source = SCANLINES_SHADER
            ),
            BuiltInShader(
                id = "crt-lite",
                label = "CRT Lite",
                impact = "Medium",
                description = "Light curvature, scanlines and vignette",
                source = CRT_LITE_SHADER
            ),
            BuiltInShader(
                id = "retro-warm",
                label = "Retro Warm",
                impact = "Low",
                description = "Warm palette with restrained contrast",
                source = RETRO_WARM_SHADER
            )
        )

        private val BUILT_IN_BY_ID = BUILT_INS.associateBy { it.id }

        private const val GBA_COLOR_CORRECTED_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uTextureSize;
            uniform vec2 uOutputSize;
            uniform float uTime;
            varying vec2 vTexCoord;
            void main() {
                vec4 sampleColor = texture2D(uTexture, vTexCoord);
                vec3 c = sampleColor.rgb;
                vec3 corrected;
                corrected.r = 0.78 * c.r + 0.18 * c.g + 0.04 * c.b;
                corrected.g = 0.08 * c.r + 0.86 * c.g + 0.06 * c.b;
                corrected.b = 0.05 * c.r + 0.14 * c.g + 0.81 * c.b;
                float luma = dot(corrected, vec3(0.299, 0.587, 0.114));
                corrected = mix(corrected, vec3(luma), 0.05);
                corrected = pow(clamp(corrected, vec3(0.0), vec3(1.0)), vec3(0.94));
                gl_FragColor = vec4(corrected, sampleColor.a);
            }
        """

        private const val SHARP_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uTextureSize;
            uniform vec2 uOutputSize;
            uniform float uTime;
            varying vec2 vTexCoord;
            void main() {
                vec2 texel = 1.0 / max(uTextureSize, vec2(1.0));
                vec4 center = texture2D(uTexture, vTexCoord);
                vec4 neighbors = texture2D(uTexture, vTexCoord + vec2(texel.x, 0.0))
                               + texture2D(uTexture, vTexCoord - vec2(texel.x, 0.0))
                               + texture2D(uTexture, vTexCoord + vec2(0.0, texel.y))
                               + texture2D(uTexture, vTexCoord - vec2(0.0, texel.y));
                gl_FragColor = clamp(center * 1.32 - neighbors * 0.08, vec4(0.0), vec4(1.0));
            }
        """

        private const val SMOOTH_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uTextureSize;
            uniform vec2 uOutputSize;
            uniform float uTime;
            varying vec2 vTexCoord;
            void main() {
                vec2 texel = 1.0 / max(uTextureSize, vec2(1.0));
                vec4 center = texture2D(uTexture, vTexCoord) * 0.50;
                vec4 around = texture2D(uTexture, vTexCoord + vec2(texel.x, 0.0))
                            + texture2D(uTexture, vTexCoord - vec2(texel.x, 0.0))
                            + texture2D(uTexture, vTexCoord + vec2(0.0, texel.y))
                            + texture2D(uTexture, vTexCoord - vec2(0.0, texel.y));
                gl_FragColor = center + around * 0.125;
            }
        """

        private const val PIXEL_PERFECT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uTextureSize;
            uniform vec2 uOutputSize;
            uniform float uTime;
            varying vec2 vTexCoord;
            void main() {
                vec2 safeSize = max(uTextureSize, vec2(1.0));
                vec2 pixel = floor(vTexCoord * safeSize) + vec2(0.5);
                vec2 uv = pixel / safeSize;
                gl_FragColor = texture2D(uTexture, uv);
            }
        """

        private const val LCD_GRID_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uTextureSize;
            uniform vec2 uOutputSize;
            uniform float uTime;
            varying vec2 vTexCoord;
            void main() {
                vec4 c = texture2D(uTexture, vTexCoord);
                vec2 cell = fract(vTexCoord * max(uTextureSize, vec2(1.0)));
                float innerX = smoothstep(0.04, 0.18, cell.x) * smoothstep(0.04, 0.18, 1.0 - cell.x);
                float innerY = smoothstep(0.04, 0.18, cell.y) * smoothstep(0.04, 0.18, 1.0 - cell.y);
                float mask = 0.80 + 0.20 * innerX * innerY;
                gl_FragColor = vec4(c.rgb * mask, c.a);
            }
        """

        private const val LCD_RESPONSE_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uTextureSize;
            uniform vec2 uOutputSize;
            uniform float uTime;
            varying vec2 vTexCoord;
            void main() {
                vec2 texel = 1.0 / max(uTextureSize, vec2(1.0));
                vec4 c = texture2D(uTexture, vTexCoord);
                vec4 horizontal = texture2D(uTexture, vTexCoord - vec2(texel.x, 0.0))
                                + texture2D(uTexture, vTexCoord + vec2(texel.x, 0.0));
                vec4 vertical = texture2D(uTexture, vTexCoord - vec2(0.0, texel.y))
                              + texture2D(uTexture, vTexCoord + vec2(0.0, texel.y));
                vec4 response = c * 0.74 + horizontal * 0.075 + vertical * 0.055;
                gl_FragColor = clamp(response, vec4(0.0), vec4(1.0));
            }
        """

        private const val SCANLINES_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uTextureSize;
            uniform vec2 uOutputSize;
            uniform float uTime;
            varying vec2 vTexCoord;
            void main() {
                vec4 c = texture2D(uTexture, vTexCoord);
                float row = vTexCoord.y * max(uTextureSize.y, 1.0);
                float line = 0.94 + 0.06 * sin(row * 6.2831853);
                gl_FragColor = vec4(c.rgb * line, c.a);
            }
        """

        private const val CRT_LITE_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uTextureSize;
            uniform vec2 uOutputSize;
            uniform float uTime;
            varying vec2 vTexCoord;
            void main() {
                vec2 p = vTexCoord * 2.0 - 1.0;
                float r2 = dot(p, p);
                p *= 1.0 + r2 * 0.035;
                vec2 uv = p * 0.5 + 0.5;
                if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                vec4 c = texture2D(uTexture, uv);
                float row = uv.y * max(uOutputSize.y, 1.0);
                float scan = 0.94 + 0.06 * sin(row * 3.14159265);
                float vignette = 1.0 - 0.12 * clamp(r2, 0.0, 1.6);
                gl_FragColor = vec4(c.rgb * scan * vignette, c.a);
            }
        """

        private const val RETRO_WARM_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uTextureSize;
            uniform vec2 uOutputSize;
            uniform float uTime;
            varying vec2 vTexCoord;
            void main() {
                vec4 sampleColor = texture2D(uTexture, vTexCoord);
                vec3 c = sampleColor.rgb;
                vec3 warm;
                warm.r = c.r * 1.05 + c.g * 0.025;
                warm.g = c.g * 0.99 + c.r * 0.010;
                warm.b = c.b * 0.90 + c.g * 0.030;
                float luma = dot(warm, vec3(0.299, 0.587, 0.114));
                warm = mix(vec3(luma), warm, 1.06);
                gl_FragColor = vec4(clamp(warm, vec3(0.0), vec3(1.0)), sampleColor.a);
            }
        """
    }
}
