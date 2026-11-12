package com.retra.emulator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

// mGBA plays GBA plus original GB/GBC
private val GBA_EXTENSIONS = setOf("gba", "gb", "gbc")
// mkxp-z loads RPG Maker XP/VX/VX Ace games packed as an archive
private val RGSS_ARCHIVE_EXTENSIONS = setOf("zip", "mkxpz", "7z")

enum class Core(val libName: String) {
    MGBA("libmgba_libretro_android.so"),
    MKXPZ("libmkxp-z_libretro_android.so"),
}

/** Safe file name for an imported game file, or null if it isn't a supported format. */
fun romFileName(displayName: String?): String? {
    val name = File(displayName ?: return null).name.trim() // strip any path parts
    val ext = name.substringAfterLast('.', "").lowercase()
    return name.takeIf { ext in GBA_EXTENSIONS || ext in RGSS_ARCHIVE_EXTENSIONS }
}

/** Display name. Decided from the name alone (no disk access), so lists can call it every frame. */
fun gameTitle(game: File): String =
    if (game.extension.lowercase() in GBA_EXTENSIONS + RGSS_ARCHIVE_EXTENSIONS + LINK_EXTENSION) game.nameWithoutExtension else game.name

/** A game in the library is either a ROM/archive file or an RPG Maker game folder. */
fun coreFor(game: File): Core =
    if (game.extension.lowercase() in GBA_EXTENSIONS) Core.MGBA else Core.MKXPZ

// a linked RPG Maker game: a tiny file in the library holding the path of the game folder, which stays where it is
const val LINK_EXTENSION = "rpglink"

/** The game folder a link points to. */
fun linkTarget(link: File): File? = runCatching { File(link.readText().trim()) }.getOrNull()

/** What we hand the core: the file itself, the Game.ini / mkxp.json inside a game folder, or the same for a linked folder. */
fun contentPath(game: File): File? =
    if (game.extension == LINK_EXTENSION) linkTarget(game)?.takeIf { it.isDirectory }?.let(::contentPath)
    else if (!game.isDirectory) game
    else game.listFiles().orEmpty().let { files ->
        files.firstOrNull { it.name.equals("Game.ini", ignoreCase = true) }
            ?: files.firstOrNull { it.name.equals("mkxp.json", ignoreCase = true) }
    }

/**
 * Real path of a file picked through the system file picker, for files on the phone's storage or an SD card.
 * [documentId] is the picker's document ID, e.g. "primary:Games/MyRPG/Game.exe" or "1A2B-3C4D:MyRPG/Game.exe".
 */
fun documentIdToFile(documentId: String, primaryRoot: File): File? {
    if (documentId.startsWith("raw:")) return File(documentId.removePrefix("raw:")) // Downloads provider
    val volume = documentId.substringBefore(':', "").ifEmpty { return null }
    val relative = documentId.substringAfter(':')
    return File(if (volume.equals("primary", ignoreCase = true)) primaryRoot else File("/storage/$volume"), relative)
}

/** "All files access" (Android 11+) or storage read permission (older), needed to play linked games in place. */
fun hasStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
    else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

// shortcut: games are just files/folders in app storage, move to a database when we need covers/metadata/extensions
object Library {
    fun romsDir(context: Context) = File(context.filesDir, "roms").apply { mkdirs() }
    fun savesDir(context: Context) = File(context.filesDir, "saves").apply { mkdirs() }
    fun systemDir(context: Context) = File(context.filesDir, "system").apply { mkdirs() }

    // where mkxp-z looks for the RPG Maker XP Run Time Package
    private fun rtpDir(context: Context) = File(systemDir(context), "mkxp-z/RTP/Standard")

    fun hasRtp(context: Context) = rtpDir(context).isDirectory

    fun games(context: Context): List<File> =
        romsDir(context).listFiles().orEmpty()
            .filterNot { it.name.startsWith(".") } // in-progress imports
            .sortedBy { it.name.lowercase() }

    /** Copies a picked ROM or game archive into the library. Returns null if it isn't supported. */
    fun importFile(context: Context, uri: Uri): File? {
        val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
        val name = romFileName(displayName) ?: return null
        return installAtomically(File(romsDir(context), name)) { tmp ->
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } != null
        }
    }

    /** Saves a downloaded game into the library. When the repo gives a [sha256] (hex), a mismatch discards the file. */
    fun importStream(context: Context, fileName: String, input: InputStream, sha256: String?): File? = input.use {
        val name = romFileName(fileName) ?: return null
        installAtomically(File(romsDir(context), name)) { tmp ->
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(it, digest).use { d -> tmp.outputStream().use { out -> d.copyTo(out) } }
            sha256 == null || digest.digest().joinToString("") { b -> "%02x".format(b) }.equals(sha256, ignoreCase = true)
        }
    }

    /**
     * Adds an RPG Maker game without copying it: [uri] is any file in the game folder (usually Game.exe).
     * Returns null if the file isn't on local storage or its folder isn't an RPG Maker game.
     */
    fun linkGame(context: Context, uri: Uri): File? {
        // only these providers' document IDs encode a real path; Drive, "Recent", etc. don't
        if (uri.authority != "com.android.externalstorage.documents" && uri.authority != "com.android.providers.downloads.documents") return null
        val picked = runCatching { documentIdToFile(DocumentsContract.getDocumentId(uri), Environment.getExternalStorageDirectory()) }.getOrNull()
        val folder = picked?.parentFile?.takeIf { contentPath(it) != null } ?: return null
        val link = File(romsDir(context), File(folder.name).name + "." + LINK_EXTENSION)
        return installAtomically(link) { tmp -> tmp.writeText(folder.absolutePath); true }
    }

    /** Copies a picked RPG Maker game folder (the one with Game.ini) into the library, like JoiPlay. */
    fun importGameFolder(context: Context, treeUri: Uri): File? {
        val name = File(treeName(context, treeUri) ?: return null).name
        if (name.isBlank() || name.startsWith(".")) return null
        return installAtomically(File(romsDir(context), name)) { tmp ->
            tmp.mkdirs()
            copyTree(context, treeUri, DocumentsContract.getTreeDocumentId(treeUri), tmp)
            contentPath(tmp) != null
        }
    }

    /** Copies a picked RTP folder (containing Graphics/ and Audio/) to where mkxp-z expects it. */
    fun importRtp(context: Context, treeUri: Uri): File? =
        installAtomically(rtpDir(context).apply { parentFile?.mkdirs() }) { tmp ->
            tmp.mkdirs()
            copyTree(context, treeUri, DocumentsContract.getTreeDocumentId(treeUri), tmp)
            File(tmp, "Graphics").isDirectory
        }

    /** Builds into a hidden temp path and only replaces [target] if [build] succeeds, so a failed import never leaves half a game. */
    private fun installAtomically(target: File, build: (File) -> Boolean): File? {
        val tmp = File(target.parentFile, ".import-" + target.name)
        tmp.deleteRecursively()
        val ok = try { build(tmp) } catch (e: Exception) { tmp.deleteRecursively(); throw e }
        if (!ok) { tmp.deleteRecursively(); return null }
        target.deleteRecursively()
        check(tmp.renameTo(target)) { "rename failed: $tmp" }
        return target
    }

    private fun treeName(context: Context, treeUri: Uri): String? {
        val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        return context.contentResolver.query(root, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    private fun copyTree(context: Context, treeUri: Uri, docId: String, dest: File) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        context.contentResolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = File(c.getString(1) ?: continue).name // never let a provider name escape dest
                if (name.isEmpty() || name == "." || name == "..") continue
                val out = File(dest, name)
                if (c.getString(2) == Document.MIME_TYPE_DIR) {
                    out.mkdirs()
                    copyTree(context, treeUri, c.getString(0), out)
                } else {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                    context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                }
            }
        }
    }
}
