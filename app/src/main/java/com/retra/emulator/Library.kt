package com.retra.emulator

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import java.io.File

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

/** A game in the library is either a ROM/archive file or an RPG Maker game folder. */
fun coreFor(game: File): Core =
    if (game.extension.lowercase() in GBA_EXTENSIONS) Core.MGBA else Core.MKXPZ

/** What we hand the core: the file itself, or the Game.ini / mkxp.json inside a game folder. */
fun contentPath(game: File): File? =
    if (!game.isDirectory) game
    else game.listFiles().orEmpty().let { files ->
        files.firstOrNull { it.name.equals("Game.ini", ignoreCase = true) }
            ?: files.firstOrNull { it.name.equals("mkxp.json", ignoreCase = true) }
    }

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
