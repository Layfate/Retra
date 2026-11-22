package com.retra.emulator

import androidx.activity.result.contract.ActivityResultContracts

/**
 * Video shader import pickers. Created as a MainActivity field so both launchers are registered
 * before the activity starts; imports run on the IO executor (they can unpack large packs).
 */
internal class ShaderImportLaunchers(private val activity: MainActivity) {
    private val file = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) activity.ioExecutor.execute { activity.shaderController.installAndSelect(uri) }
    }

    /** A RetroArch shader folder: presets together with their includes and textures. */
    private val folder = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) activity.ioExecutor.execute { activity.shaderController.installFolderAndSelect(uri) }
    }

    fun pickFile() = activity.runOnUiThread { file.launch(arrayOf("*/*")) }
    fun pickFolder() = activity.runOnUiThread { folder.launch(null) }

    fun removePack(packId: String) {
        if (packId.length <= 80) activity.ioExecutor.execute { activity.shaderController.removePack(packId) }
    }
}
