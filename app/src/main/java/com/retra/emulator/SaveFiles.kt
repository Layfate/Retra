package com.retra.emulator

import java.io.File
import java.io.InputStream

object SaveFiles {
    val extensions = listOf("sav", "srm", "state") + (0..9).map { "ss$it" }
    const val MAX_BYTES = 16 * 1024 * 1024
    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase().also {
        require(it in extensions) { "Choose .sav, .srm, .state, or .ss0–.ss9" }
    }
    fun file(directory: File, rom: File, extension: String): File {
        require(extension in extensions)
        return File(directory, rom.name + "." + if (extension == "sav") "srm" else extension)
    }
    fun read(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= MAX_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray().also { require(it.isNotEmpty() && it.size <= MAX_BYTES) { "Save is empty or too large" } }
    }
    fun validate(extension: String, bytes: ByteArray) {
        require(extension in extensions)
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Save is empty or too large" }
        if (extension == "sav" || extension == "srm") {
            require(listOf(256, 512, 2048, 8192, 32768, 65536, 131072).any {
                bytes.size == it || bytes.size == it + 44 || bytes.size == it + 48
            }) { "Unsupported battery save size" }
        } else {
            val raw = bytes.size >= 4 && bytes[1] == 0.toByte() &&
                ((bytes[2] == 0.toByte() && bytes[3] == 1.toByte()) ||
                 (bytes[2] == 64.toByte() && bytes[3] == 0.toByte()))
            val png = bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            require(raw || png) { "Not an mGBA state. Renaming another emulator's state cannot convert it." }
        }
    }
    fun migrate(directory: File, rom: File) {
        for (extension in listOf("srm", "state")) {
            val target = file(directory, rom, extension)
            val old = File(directory, rom.nameWithoutExtension + "." + extension)
            // Preserve legacy files because another ROM may have shared the basename.
            if (!target.exists() && old.exists()) target.writeAtomic(old.readBytes())
        }
    }
}

@Synchronized
fun File.writeAtomic(bytes: ByteArray) {
    val tmp = File.createTempFile("save-", ".tmp", parentFile)
    try {
        tmp.outputStream().use { it.write(bytes); it.fd.sync() }
        check(tmp.renameTo(this)) { "Could not replace save" }
    } finally { tmp.delete() }
}
