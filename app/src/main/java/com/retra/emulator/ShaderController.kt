package com.retra.emulator

import android.app.Activity
import android.net.Uri
import android.view.View
import android.widget.ImageView
import org.json.JSONArray

/** Coordinates optional shader install/selection without putting GL policy in MainActivity. */
class ShaderController(
    private val activity: Activity,
    private val repository: ShaderRepository,
    private val normalView: ImageView,
    private val shaderView: ShaderGameView,
    private val onSettingsChanged: () -> Unit,
    private val latestFrame: () -> FrameSnapshot?,
    private val isGameplayVisible: () -> Boolean
) {
    data class FrameSnapshot(val pixels: IntArray, val width: Int, val height: Int)

    fun selectedId(): String = repository.selectedId()
    fun optionsJson(): JSONArray = repository.optionsJson()

    /** Call off the UI thread: imports can extract and check thousands of RetroArch files. */
    fun installAndSelect(uri: Uri) = finishInstall { repository.install(uri) }

    /** Imports a picked folder of RetroArch shaders. Call off the UI thread. */
    fun installFolderAndSelect(treeUri: Uri) = finishInstall { repository.installFolder(treeUri) }

    private fun finishInstall(install: () -> ShaderRepository.InstallResult) {
        activity.runOnUiThread { RetraNotice.makeText(activity, "Importing shaders…", RetraNotice.LENGTH_SHORT).show() }
        val result = runCatching(install)
        activity.runOnUiThread {
            result.onSuccess { installed ->
                if (installed.options.size == 1) {
                    select(installed.options.first().id, showToast = true)
                } else {
                    val skipped = if (installed.skipped > 0) " (${installed.skipped} unsupported skipped)" else ""
                    RetraNotice.makeText(activity, "Imported ${installed.options.size} shaders from ${installed.packName}$skipped. " +
                        "Choose one in Video shader.", RetraNotice.LENGTH_LONG).show()
                }
                onSettingsChanged()
            }.onFailure { error ->
                RetraNotice.makeText(activity, "Could not install shader: ${error.message ?: "unsupported file"}", RetraNotice.LENGTH_LONG).show()
            }
        }
    }

    /** Call off the UI thread: a pack can hold many files. */
    fun removePack(packId: String) {
        val wasSelected = repository.selectedId()
        val removed = repository.removePack(packId)
        activity.runOnUiThread {
            if (removed) {
                if (wasSelected != repository.selectedId()) applySelection(showToast = false)
                RetraNotice.makeText(activity, "Shader pack removed", RetraNotice.LENGTH_SHORT).show()
            }
            onSettingsChanged()
        }
    }

    fun select(id: String, showToast: Boolean = true) {
        repository.select(id)
        onSettingsChanged()

        // Built-in shaders ship with Retra; there is nothing to install. When
        // selection happens from the normal Settings page the GLSurfaceView is
        // intentionally hidden and may not own a current EGL context yet. Store
        // the choice now and compile lazily when native gameplay becomes visible.
        if (!isGameplayVisible()) {
            normalView.visibility = View.VISIBLE
            shaderView.visibility = View.GONE
            if (showToast) {
                val label = repository.optionFor(repository.selectedId())?.label ?: "Off"
                val message = if (repository.selectedId() == "none") {
                    "Video shader: Off"
                } else {
                    "$label selected • applies when game starts"
                }
                RetraNotice.makeText(activity, message, RetraNotice.LENGTH_SHORT).show()
            }
            return
        }

        applySelection(showToast)
    }

    fun configure(stretch: Boolean, linearFiltering: Boolean) {
        shaderView.configure(stretch, linearFiltering)
    }

    @Suppress("UNUSED_PARAMETER")
    fun presentIfActive(pixels: IntArray, width: Int, height: Int): Boolean {
        if (shaderView.visibility != View.VISIBLE) return false
        // The active gameplay path is mailbox-backed. Keep the parameters for
        // source compatibility with older callers, but avoid copying the frame
        // on the UI thread; the GL thread acquires it directly.
        shaderView.requestLatestFrame()
        return true
    }

    fun requestPresentLatest(): Boolean {
        if (shaderView.visibility != View.VISIBLE) return false
        shaderView.requestLatestFrame()
        return true
    }

    fun onPause() = runCatching { shaderView.onPause() }.getOrNull()
    fun onResume() = runCatching { shaderView.onResume() }.getOrNull()

    fun applySelection(showToast: Boolean) {
        val selected = repository.selectedId()

        // Do not force a shader compile while Retra is on Home/Settings. Some
        // OEMs destroy the SurfaceView EGL surface while its parent is hidden,
        // and compiling there can return shader=0 with an empty info log.
        if (!isGameplayVisible()) {
            normalView.visibility = View.VISIBLE
            shaderView.visibility = View.GONE
            return
        }
        if (repository.isRetroArch(selected)) {
            applyRetroArch(selected, showToast)
            return
        }
        val source = repository.sourceFor(selected)
        if (selected == "none" || source == null) {
            // Normal gameplay also uses the dedicated GL surface. The ImageView is
            // retained only as an OEM compatibility fallback, so the UI thread no
            // longer performs Bitmap.setPixels() on every visible game frame.
            normalView.visibility = View.INVISIBLE
            shaderView.visibility = View.VISIBLE
            if (selected != "none") repository.select("none")
            shaderView.usePassthrough { ok, _ ->
                if (!ok) {
                    shaderView.visibility = View.GONE
                    normalView.visibility = View.VISIBLE
                }
            }
            if (showToast) RetraNotice.makeText(activity, "GLSL shader: None", RetraNotice.LENGTH_SHORT).show()
            return
        }

        normalView.visibility = View.INVISIBLE
        shaderView.visibility = View.VISIBLE
        shaderView.setFragmentShader(source) { ok, error ->
            if (!ok) {
                repository.select("none")
                val detail = error?.trim().takeUnless { it.isNullOrEmpty() } ?: "unsupported by this GPU"
                // Fall back to the same GPU presenter rather than moving gameplay
                // back onto the UI-thread ImageView path.
                shaderView.usePassthrough { passthroughOk, _ ->
                    if (!passthroughOk) {
                        shaderView.visibility = View.GONE
                        normalView.visibility = View.VISIBLE
                    }
                }
                RetraNotice.makeText(activity, "Shader unavailable on this device: $detail", RetraNotice.LENGTH_LONG).show()
                onSettingsChanged()
            } else {
                latestFrame()?.let { shaderView.submitFrame(it.pixels, it.width, it.height) }
                if (showToast) {
                    val label = repository.optionFor(selected)?.label ?: "Custom"

                    val suffix = if (error == "compatibility-mode") " • compatibility mode" else ""
                    RetraNotice.makeText(activity, "Video shader: $label$suffix", RetraNotice.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Parses the preset off the UI thread, then lets the GL thread compile and run it. */
    private fun applyRetroArch(selected: String, showToast: Boolean) {
        Thread({
            val pipeline = runCatching { repository.pipelineFor(selected) }
            activity.runOnUiThread {
                if (repository.selectedId() != selected || !isGameplayVisible()) return@runOnUiThread
                pipeline.onFailure { error -> fallBack(error.message ?: "unsupported preset") }
                pipeline.onSuccess { compiled ->
                    normalView.visibility = View.INVISIBLE
                    shaderView.visibility = View.VISIBLE
                    shaderView.setRetroArchPipeline(compiled) { ok, error ->
                        if (!ok) fallBack(error ?: "unsupported by this GPU")
                        else {
                            latestFrame()?.let { shaderView.submitFrame(it.pixels, it.width, it.height) }
                            if (showToast) {
                                val label = repository.optionFor(selected)?.label ?: "RetroArch shader"
                                RetraNotice.makeText(activity, "Video shader: $label", RetraNotice.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }, "retra-shader-preset").start()
    }

    private fun fallBack(detail: String) {
        repository.select("none")
        shaderView.usePassthrough { ok, _ ->
            if (!ok) {
                shaderView.visibility = View.GONE
                normalView.visibility = View.VISIBLE
            }
        }
        RetraNotice.makeText(activity, "Shader unavailable: $detail", RetraNotice.LENGTH_LONG).show()
        onSettingsChanged()
    }
}
