package com.retra.emulator

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/**
 * Real Google Drive REST v3 backup transport.
 *
 * Retra stores complete, validated .retra snapshots in:
 *   My Drive / Retra Backups /
 *
 * This repository never uses Android's Storage Access Framework. OAuth access
 * tokens are supplied by Google Identity Services (AuthorizationClient).
 * Every upload creates a NEW snapshot; an existing backup is never patched.
 * Uploads are resumable and sent in chunks, so large save-state backups survive
 * flaky mobile connections, and transient Drive errors are retried with backoff.
 * Only after a verified upload may older automatic snapshots be pruned.
 */
class GoogleDriveApiRepository(
    private val cacheDir: File,
    private val deviceName: String = android.os.Build.MODEL.orEmpty()
) {
    data class CloudBackup(
        val id: String,
        val name: String,
        val createdAt: Long,
        val modifiedAt: Long,
        val size: Long,
        val md5: String,
        val sha256: String,
        /** BackupRepository.Result.contentFingerprint of the data inside, blank for older backups. */
        val contentFingerprint: String = "",
        val device: String = "",
        /** "auto", "manual", or "" for backups made before Retra recorded it. */
        val kind: String = "",
        /** Created by Retra (appProperties.retraBackup == "1"); only these are ever pruned. */
        val createdByRetra: Boolean = false
    ) {
        val time: Long get() = if (createdAt > 0L) createdAt else modifiedAt
    }

    data class UploadResult(
        val backup: CloudBackup,
        val deduplicated: Boolean = false
    )

    class AuthExpiredException : Exception("Google Drive authorization expired")

    /** A Drive API error. [transient] errors (network, 429, 5xx, rate limits) are worth retrying later. */
    class DriveException(message: String, val reason: String = "", val transient: Boolean = false) : IOException(message)

    /** Return backups (newest first) without creating a folder. Restore and fresh-install checks use this. */
    fun listBackups(accessToken: String): List<CloudBackup> {
        val folderId = findBackupFolder(accessToken) ?: return emptyList()
        return listBackups(accessToken, folderId)
    }

    /**
     * Upload a new immutable snapshot. Existing backups are never PATCHed.
     * When the newest backup already holds the same data (same archive hash or the same
     * content fingerprint), nothing is uploaded. A failed or unverifiable upload is removed
     * only if it is the newly-created file.
     */
    fun uploadBackup(
        accessToken: String,
        source: File,
        sha256: String,
        contentFingerprint: String = "",
        automatic: Boolean = false,
        onProgress: (Int) -> Unit = {}
    ): UploadResult {
        require(source.isFile && source.length() > 0L) { "Backup archive is empty" }
        val existing = listBackups(accessToken)
        val newest = existing.firstOrNull()
        if (newest != null && (newest.sha256.equals(sha256, ignoreCase = true) ||
                (contentFingerprint.isNotBlank() && newest.contentFingerprint.equals(contentFingerprint, ignoreCase = true)))) {
            onProgress(100)
            return UploadResult(newest, deduplicated = true)
        }

        val folderId = findOrCreateBackupFolder(accessToken)
        val localMd5 = digestFile(source, "MD5")
        val properties = JSONObject()
            .put("retraBackup", "1")
            .put("retraSchema", "2")
            .put("retraSha256", sha256.lowercase(Locale.US))
            .put("retraCreatedAt", System.currentTimeMillis().toString())
            .put("retraKind", if (automatic) KIND_AUTO else KIND_MANUAL)
        if (contentFingerprint.isNotBlank()) properties.put("retraContent", contentFingerprint.lowercase(Locale.US))
        // Drive limits each appProperty (key + value) to 124 bytes.
        deviceName.trim().take(60).takeIf { it.isNotBlank() }?.let { properties.put("retraDevice", it) }
        val metadata = JSONObject()
            .put("name", source.name)
            .put("parents", JSONArray().put(folderId))
            .put("mimeType", BACKUP_MIME)
            .put("appProperties", properties)

        var createdId: String? = null
        try {
            createdId = resumableUpload(accessToken, metadata, source, onProgress)
            // Read the stored file back instead of trusting the upload response, so size,
            // checksum and properties are verified against what Drive actually keeps.
            val uploaded = withRetry {
                jsonToBackup(requestJson("GET", "$DRIVE_API/files/$createdId?fields=${encode(FILE_FIELDS)}", accessToken))
            }

            if (uploaded.size != source.length()) {
                throw IllegalStateException("Drive upload size verification failed")
            }
            if (uploaded.md5.isBlank() || !uploaded.md5.equals(localMd5, ignoreCase = true)) {
                throw IllegalStateException("Drive upload checksum verification failed")
            }
            if (!uploaded.sha256.equals(sha256, ignoreCase = true)) {
                throw IllegalStateException("Drive backup metadata verification failed")
            }
            onProgress(100)
            return UploadResult(uploaded)
        } catch (error: Exception) {
            // Protect every prior good backup. At most, clean up the NEW failed object.
            createdId?.let { id -> runCatching { deleteFile(accessToken, id) } }
            throw error
        }
    }

    /**
     * Retention: keep the newest [keep] automatic snapshots of each device and delete older ones, so
     * phones sharing one Google account never prune each other's recent backups. Only files Retra
     * created are considered; manual "Backup now" snapshots and the newest backup are always kept.
     * Returns how many snapshots were removed.
     */
    fun pruneAutomaticBackups(accessToken: String, keep: Int): Int {
        val backups = listBackups(accessToken)
        val newestId = backups.firstOrNull()?.id
        var removed = 0
        backups.filter { it.createdByRetra && it.kind != KIND_MANUAL }
            .groupBy { it.device }
            .values
            .flatMap { perDevice -> perDevice.drop(keep.coerceAtLeast(1)) } // Each group is newest first.
            .forEach { backup ->
                if (backup.id == newestId) return@forEach
                if (runCatching { deleteFile(accessToken, backup.id) }.isSuccess) removed++
            }
        return removed
    }

    /** Download one chosen snapshot and verify Drive size/checksum before returning it. */
    fun downloadBackup(
        accessToken: String,
        backup: CloudBackup,
        target: File,
        onProgress: (Int) -> Unit = {}
    ): File {
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile ?: cacheDir, ".${target.name}.${System.nanoTime()}.part")
        try {
            withRetry {
                val connection = open("GET", "$DRIVE_API/files/${backup.id}?alt=media", accessToken)
                try {
                    ensureSuccess(connection)
                    val total = backup.size.takeIf { it > 0L } ?: connection.contentLengthLong.coerceAtLeast(0L)
                    var copied = 0L
                    connection.inputStream.use { input ->
                        FileOutputStream(partial).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                if (total > 0L) onProgress(((copied * 100L) / total).toInt().coerceIn(0, 99))
                            }
                            output.flush()
                            runCatching { output.fd.sync() }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }
            if (backup.size > 0L && partial.length() != backup.size) {
                throw IllegalStateException("Downloaded backup size does not match Google Drive")
            }
            if (backup.md5.isNotBlank()) {
                val actual = digestFile(partial, "MD5")
                if (!actual.equals(backup.md5, ignoreCase = true)) {
                    throw IllegalStateException("Downloaded backup checksum is invalid")
                }
            }
            if (target.exists() && !target.delete()) throw IllegalStateException("Could not replace temporary restore file")
            if (!partial.renameTo(target)) partial.copyTo(target, overwrite = true)
            onProgress(100)
            return target
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    /** No-op for snapshot storage: the next archive reflects deletions without mutating old backups. */
    fun queueDelete(paths: List<String>) = Unit
    fun deletePaths(accessToken: String, paths: List<String>) = Unit

    private fun findBackupFolder(token: String): String? {
        // Exact My Drive root + exact name prevents accidentally binding to a
        // same-name nested folder. If duplicates already exist, oldest is reused.
        val q = "'root' in parents and trashed = false and mimeType = '$FOLDER_MIME' and name = '$BACKUP_FOLDER_NAME'"
        val fields = "files(id,name,createdTime,appProperties)"
        val result = withRetry {
            requestJson("GET", "$DRIVE_API/files?q=${encode(q)}&spaces=drive&orderBy=createdTime&fields=${encode(fields)}&pageSize=100", token)
        }
        val files = result.optJSONArray("files") ?: JSONArray()
        if (files.length() == 0) return null

        var selectedId: String? = null
        var selectedCreated = Long.MAX_VALUE
        for (i in 0 until files.length()) {
            val item = files.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            val created = parseDriveTime(item.optString("createdTime"))
            if (selectedId == null || (created > 0L && created < selectedCreated)) {
                selectedId = id
                selectedCreated = if (created > 0L) created else selectedCreated
            }
        }
        return selectedId
    }

    private fun findOrCreateBackupFolder(token: String): String {
        findBackupFolder(token)?.let { return it }
        val body = JSONObject()
            .put("name", BACKUP_FOLDER_NAME)
            .put("mimeType", FOLDER_MIME)
            .put("parents", JSONArray().put("root"))
            .put("appProperties", JSONObject().put("retraBackupRoot", "1"))
        // Not retried: a retried create could leave two folders. A failed attempt is retried by the
        // next backup, which first looks for a folder the failed request may have created.
        val created = requestJson("POST", "$DRIVE_API/files?fields=id,name,parents,appProperties", token, body.toString().toByteArray(Charsets.UTF_8))
        return created.optString("id").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Could not create My Drive/$BACKUP_FOLDER_NAME")
    }

    private fun listBackups(token: String, folderId: String): List<CloudBackup> {
        val q = "'$folderId' in parents and trashed = false"
        val fields = "nextPageToken,files($FILE_FIELDS)"
        val backups = mutableListOf<CloudBackup>()
        var pageToken: String? = null
        do {
            val tokenPart = pageToken?.let { "&pageToken=${encode(it)}" }.orEmpty()
            val url = "$DRIVE_API/files?q=${encode(q)}&spaces=drive&orderBy=createdTime%20desc&fields=${encode(fields)}&pageSize=100$tokenPart"
            val result = withRetry { requestJson("GET", url, token) }
            val files = result.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) {
                val json = files.optJSONObject(i) ?: continue
                val name = json.optString("name")
                val props = json.optJSONObject("appProperties")
                val isRetra = props?.optString("retraBackup") == "1" || name.endsWith(".retra", ignoreCase = true)
                if (isRetra) backups += jsonToBackup(json)
            }
            pageToken = result.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null)
        return backups.sortedByDescending { it.time }
    }

    private fun jsonToBackup(json: JSONObject): CloudBackup {
        val props = json.optJSONObject("appProperties") ?: JSONObject()
        return CloudBackup(
            id = json.optString("id"),
            name = json.optString("name", "Retra backup.retra"),
            createdAt = parseDriveTime(json.optString("createdTime")),
            modifiedAt = parseDriveTime(json.optString("modifiedTime")),
            size = json.optString("size", "0").toLongOrNull() ?: 0L,
            md5 = json.optString("md5Checksum"),
            sha256 = props.optString("retraSha256"),
            contentFingerprint = props.optString("retraContent"),
            device = props.optString("retraDevice"),
            kind = props.optString("retraKind"),
            createdByRetra = props.optString("retraBackup") == "1"
        )
    }

    // ---------- Resumable upload ----------

    /** Uploads [source] through a resumable session and returns the new file ID. */
    private fun resumableUpload(token: String, metadata: JSONObject, source: File, onProgress: (Int) -> Unit): String {
        val total = source.length()
        var restarts = 0
        while (true) {
            val session = withRetry { startSession(token, metadata, total) }
            try {
                return uploadChunks(token, session, source, total, onProgress)
            } catch (expired: SessionExpiredException) {
                // Drive forgets sessions after about a week or on some server errors; start over once.
                if (++restarts > 1) throw DriveException("Google Drive upload session expired", transient = true)
            }
        }
    }

    private class SessionExpiredException : IOException("Upload session expired")

    private fun startSession(token: String, metadata: JSONObject, total: Long): String {
        val connection = open("POST", "$DRIVE_UPLOAD/files?uploadType=resumable&fields=id", token)
        try {
            val body = metadata.toString().toByteArray(Charsets.UTF_8)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("X-Upload-Content-Type", BACKUP_MIME)
            connection.setRequestProperty("X-Upload-Content-Length", total.toString())
            connection.outputStream.use { it.write(body) }
            ensureSuccess(connection)
            return connection.getHeaderField("Location")?.takeIf { it.startsWith("https://") }
                ?: throw DriveException("Google Drive did not start the upload", transient = true)
        } finally {
            connection.disconnect()
        }
    }

    private fun uploadChunks(token: String, session: String, source: File, total: Long, onProgress: (Int) -> Unit): String {
        var offset = 0L
        var failures = 0
        var probe = false
        while (true) {
            val outcome = try {
                // After a failed chunk, ask Drive how much it already stored and continue from there.
                if (probe) queryUploaded(token, session, total) else putChunk(token, session, source, offset, total)
            } catch (error: IOException) {
                if (error is SessionExpiredException || (error is DriveException && !error.transient)) throw error
                if (++failures > MAX_ATTEMPTS) throw error
                backoff(failures)
                probe = true
                continue
            }
            probe = false
            when (outcome) {
                is ChunkOutcome.Done -> return outcome.fileId
                is ChunkOutcome.Continue -> {
                    if (outcome.nextOffset > offset) failures = 0
                    offset = outcome.nextOffset
                    onProgress(((offset * 100L) / total.coerceAtLeast(1L)).toInt().coerceIn(0, 99))
                }
            }
        }
    }

    private sealed class ChunkOutcome {
        data class Continue(val nextOffset: Long) : ChunkOutcome()
        data class Done(val fileId: String) : ChunkOutcome()
    }

    private fun putChunk(token: String, session: String, source: File, offset: Long, total: Long): ChunkOutcome {
        val length = minOf(CHUNK_SIZE.toLong(), total - offset).toInt()
        val connection = open("PUT", session, token)
        try {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(length)
            connection.setRequestProperty("Content-Type", BACKUP_MIME)
            connection.setRequestProperty("Content-Range", "bytes $offset-${offset + length - 1}/$total")
            connection.outputStream.use { output ->
                FileInputStream(source).use { input ->
                    input.channel.position(offset)
                    val buffer = ByteArray(BUFFER_SIZE)
                    var remaining = length
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (read <= 0) throw IOException("Backup archive changed during upload")
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
            return chunkOutcome(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun queryUploaded(token: String, session: String, total: Long): ChunkOutcome {
        val connection = open("PUT", session, token)
        try {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(0)
            connection.setRequestProperty("Content-Range", "bytes */$total")
            connection.outputStream.use { }
            return chunkOutcome(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun chunkOutcome(connection: HttpURLConnection): ChunkOutcome {
        val code = connection.responseCode
        if (code == 308) {
            // "Range: bytes=0-N" lists what Drive stored; without it nothing was stored yet.
            val range = connection.getHeaderField("Range")
            val next = range?.substringAfterLast('-')?.toLongOrNull()?.plus(1) ?: 0L
            return ChunkOutcome.Continue(next)
        }
        if (code == 404 || code == 410) throw SessionExpiredException()
        ensureSuccess(connection)
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val id = json.optString("id").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Drive did not return the uploaded file ID")
        return ChunkOutcome.Done(id)
    }

    // ---------- HTTP ----------

    private fun deleteFile(token: String, id: String) {
        val connection = open("DELETE", "$DRIVE_API/files/$id", token)
        try {
            if (connection.responseCode == 404) return // Already gone.
            ensureSuccess(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestJson(method: String, url: String, token: String, body: ByteArray? = null): JSONObject {
        val connection = open(method, url, token)
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.outputStream.use { it.write(body) }
            }
            ensureSuccess(connection)
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    /** Runs an idempotent request, retrying network errors and transient Drive errors with backoff. */
    private fun <T> withRetry(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (error: IOException) {
                val retry = error !is SessionExpiredException && (error !is DriveException || error.transient)
                if (!retry || ++attempt >= MAX_ATTEMPTS) throw error
                backoff(attempt)
            }
        }
    }

    private fun backoff(attempt: Int) {
        try {
            Thread.sleep((1000L shl (attempt - 1).coerceIn(0, 4)) + (Math.random() * 400).toLong())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Google Drive transfer was interrupted")
        }
    }

    private fun open(method: String, url: String, token: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 120_000
            useCaches = false
            // A 308 from a resumable session means "resume incomplete", never a redirect.
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }

    private fun ensureSuccess(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code == 401) throw AuthExpiredException()
        if (code in 200..299) return
        val body = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val reason = error?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
        val message = error?.optString("message").orEmpty()
        val transient = code == 408 || code == 429 || code >= 500 ||
            reason in setOf("rateLimitExceeded", "userRateLimitExceeded", "backendError")
        val friendly = when {
            reason == "storageQuotaExceeded" -> "Your Google Drive storage is full. Free up space in Google Drive and try again."
            reason == "insufficientFilePermissions" || reason == "appNotAuthorizedToFile" ->
                "Retra no longer has access to this Drive file. Reconnect Google Drive and try again."
            code == 403 && reason.isBlank() && message.isBlank() -> "Google Drive refused the request (403)"
            transient -> "Google Drive is busy right now (${code}). Retra will try again."
            else -> "Google Drive API $code${if (message.isBlank()) "" else ": $message"}"
        }
        throw DriveException(friendly, reason, transient)
    }

    fun sha256(file: File): String = digestFile(file, "SHA-256")

    private fun digestFile(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it) }
    }

    private fun parseDriveTime(value: String): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    companion object {
        const val BACKUP_FOLDER_NAME = "Retra Backups"
        const val KIND_AUTO = "auto"
        const val KIND_MANUAL = "manual"
        private const val BACKUP_MIME = "application/x-retra-backup"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val DRIVE_API = "https://www.googleapis.com/drive/v3"
        private const val DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FILE_FIELDS = "id,name,size,createdTime,modifiedTime,md5Checksum,mimeType,appProperties"
        private const val BUFFER_SIZE = 64 * 1024
        /** Resumable chunks must be multiples of 256 KiB. */
        private const val CHUNK_SIZE = 16 * 256 * 1024
        private const val MAX_ATTEMPTS = 4
    }
}
