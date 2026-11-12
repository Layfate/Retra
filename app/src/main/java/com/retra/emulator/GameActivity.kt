package com.retra.emulator

import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class GameActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ROM = "rom"
        private val SPEEDS = listOf(1, 2, 4)
        // same order as Settings.SCREEN_FILTER: Sharp (crisp, no shimmer), Smooth (edge-aware upscaler), LCD, CRT
        private val SHADERS = listOf(ShaderConfig.Sharp, ShaderConfig.CUT2(), ShaderConfig.LCD, ShaderConfig.CRT)
    }

    private lateinit var retroView: GLRetroView
    private lateinit var sramFile: File
    private lateinit var romFile: File
    private var stateExtension = "state"
    private val stateFile: File get() = SaveFiles.file(Library.savesDir(this), romFile, stateExtension)
    private val dpad = mutableSetOf<Int>()
    private var speed by mutableIntStateOf(1)
    private var filter by mutableIntStateOf(0)
    private var resumedAt = 0L // elapsedRealtime when the game screen came to the foreground, 0 when not counting
    @Volatile private var running = false // true once the core has loaded the game and drawn a frame

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rom = File(intent.getStringExtra(EXTRA_ROM) ?: return finish())
        val content = contentPath(rom) ?: return finish()
        val core = File(applicationInfo.nativeLibraryDir, coreFor(rom).libName)
        if (!core.exists()) {
            // cores live in jniLibs (mkxp-z comes from .github/workflows/mkxp-z-core.yml); fail gracefully if one is missing
            Toast.makeText(this, "The ${coreFor(rom).name} core isn't in this build yet", Toast.LENGTH_LONG).show()
            return finish()
        }
        History.record(this, rom)
        val saves = Library.savesDir(this)
        romFile = rom
        SaveFiles.migrate(saves, rom)
        sramFile = SaveFiles.file(saves, rom, "srm")
        stateExtension = savedInstanceState?.getString("stateExtension") ?: "state"

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // steady clocks instead of boost-then-throttle, so long sessions keep a stable frame rate
        if (getSystemService(PowerManager::class.java).isSustainedPerformanceModeSupported) window.setSustainedPerformanceMode(true)
        filter = Settings.get(this, Settings.SCREEN_FILTER).toInt()
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())

        retroView = GLRetroView(this, GLRetroViewData(this).apply {
            coreFilePath = core.absolutePath
            gameFilePath = content.absolutePath
            systemDirectory = Library.systemDir(this@GameActivity).absolutePath // mkxp-z reads RTP/fonts from here
            savesDirectory = saves.absolutePath // mkxp-z keeps RPG Maker saves under mkxp-z/Saves/ here
            saveRAMState = sramFile.takeIf { it.exists() }?.readBytes()
            shader = SHADERS[filter]
            rumbleEventsEnabled = false // nothing consumes rumble events
            variables = Settings.coreVariables(this@GameActivity, coreFor(rom)) // More → Settings
        })
        lifecycle.addObserver(retroView)
        lifecycleScope.launch {
            retroView.getGLRetroEvents().first { it is GLRetroView.GLRetroEvents.FrameRendered }
            running = true
        }

        setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(factory = { retroView }, modifier = Modifier.fillMaxSize())
                Gamepad(
                    rpgButtons = coreFor(rom) == Core.MKXPZ,
                    speedLabel = "⏩ ${speed}x",
                    filterLabel = Settings.SCREEN_FILTER.choices[filter].label,
                    onKey = ::key, onDpad = ::dpad, onSaveState = ::saveState, onLoadState = ::loadState, onSlot = ::chooseSlot,
                    onSpeed = ::cycleSpeed, onFilter = ::cycleFilter,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumedAt = SystemClock.elapsedRealtime()
    }

    override fun onPause() {
        super.onPause()
        if (resumedAt > 0) Playtime.add(this, romFile, SystemClock.elapsedRealtime() - resumedAt)
        resumedAt = 0
        if (!running) return
        // ON_PAUSE already reached retroView before this method runs, so emulation is paused and we can read SRAM directly
        runCatching { retroView.serializeSRAM(useEmulationThread = false) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { sramFile.writeAtomic(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("stateExtension", stateExtension)
        super.onSaveInstanceState(outState)
    }

    private fun chooseSlot() {
        val slots = listOf("state") + (0..9).map { "ss$it" }
        android.app.AlertDialog.Builder(this)
            .setTitle("Save-state slot (current: .$stateExtension)")
            .setSingleChoiceItems(slots.map { ".$it" }.toTypedArray(), slots.indexOf(stateExtension)) { dialog, index ->
                stateExtension = slots[index]
                dialog.dismiss()
            }.show()
    }

    private fun cycleSpeed() {
        speed = SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.size]
        retroView.frameSpeed = speed
    }

    private fun cycleFilter() {
        filter = (filter + 1) % SHADERS.size
        retroView.shader = SHADERS[filter]
        Settings.set(this, Settings.SCREEN_FILTER, filter.toString())
    }

    private fun key(code: Int, down: Boolean) =
        retroView.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code)

    private fun dpad(code: Int, down: Boolean) {
        if (down) dpad += code else dpad -= code
        val x = (if (KeyEvent.KEYCODE_DPAD_RIGHT in dpad) 1f else 0f) - (if (KeyEvent.KEYCODE_DPAD_LEFT in dpad) 1f else 0f)
        val y = (if (KeyEvent.KEYCODE_DPAD_DOWN in dpad) 1f else 0f) - (if (KeyEvent.KEYCODE_DPAD_UP in dpad) 1f else 0f)
        retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x, y)
    }

    private fun saveState() = lifecycleScope.launch(Dispatchers.IO) {
        val ok = running && runCatching { stateFile.writeAtomic(retroView.serializeState()) }.isSuccess
        toast(if (ok) "State saved" else "Couldn't save state")
    }

    private fun loadState() = lifecycleScope.launch(Dispatchers.IO) {
        val ok = running && stateFile.exists() && runCatching { retroView.unserializeState(stateFile.readBytes()) }.getOrDefault(false)
        toast(if (ok) "State loaded" else "No state in this slot, or incompatible mGBA state")
    }

    private suspend fun toast(text: String) = withContext(Dispatchers.Main) {
        Toast.makeText(this@GameActivity, text, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun Gamepad(
    rpgButtons: Boolean,
    speedLabel: String,
    filterLabel: String,
    onKey: (Int, Boolean) -> Unit,
    onDpad: (Int, Boolean) -> Unit,
    onSaveState: () -> Unit,
    onLoadState: () -> Unit,
    onSlot: () -> Unit,
    onSpeed: () -> Unit,
    onFilter: () -> Unit,
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PadButton("L", RoundedCornerShape(8.dp), 72.dp, 40.dp) { onKey(KeyEvent.KEYCODE_BUTTON_L1, it) }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row {
                    TextButton(onClick = onSlot) { Text("Slot", color = Color.White) }
                    TextButton(onClick = onSaveState) { Text("Save", color = Color.White) }
                    TextButton(onClick = onLoadState) { Text("Load", color = Color.White) }
                }
                Row {
                    TextButton(onClick = onSpeed) { Text(speedLabel, color = Color.White) }
                    TextButton(onClick = onFilter) { Text(filterLabel, color = Color.White) }
                }
            }
            Spacer(Modifier.weight(1f))
            PadButton("R", RoundedCornerShape(8.dp), 72.dp, 40.dp) { onKey(KeyEvent.KEYCODE_BUTTON_R1, it) }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PadButton("▲") { onDpad(KeyEvent.KEYCODE_DPAD_UP, it) }
                Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    PadButton("◀") { onDpad(KeyEvent.KEYCODE_DPAD_LEFT, it) }
                    PadButton("▶") { onDpad(KeyEvent.KEYCODE_DPAD_RIGHT, it) }
                }
                PadButton("▼") { onDpad(KeyEvent.KEYCODE_DPAD_DOWN, it) }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PadButton("SELECT", RoundedCornerShape(50), 64.dp, 28.dp) { onKey(KeyEvent.KEYCODE_BUTTON_SELECT, it) }
                PadButton("START", RoundedCornerShape(50), 64.dp, 28.dp) { onKey(KeyEvent.KEYCODE_BUTTON_START, it) }
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // mkxp-z maps RetroPad A→C (confirm), B→B (cancel), X→A (dash in many games), Y→X
                if (rpgButtons) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PadButton("Y", CircleShape, 48.dp, 48.dp) { onKey(KeyEvent.KEYCODE_BUTTON_Y, it) }
                    PadButton("X", CircleShape, 48.dp, 48.dp) { onKey(KeyEvent.KEYCODE_BUTTON_X, it) }
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PadButton("B", CircleShape, 60.dp, 60.dp) { onKey(KeyEvent.KEYCODE_BUTTON_B, it) }
                    Column {
                        PadButton("A", CircleShape, 60.dp, 60.dp) { onKey(KeyEvent.KEYCODE_BUTTON_A, it) }
                        Spacer(Modifier.size(40.dp))
                    }
                }
            }
        }
    }
}

/** A button that reports press and release, so held buttons stay held while other fingers press others. */
@Composable
private fun PadButton(
    label: String,
    shape: Shape = RoundedCornerShape(8.dp),
    width: Dp = 48.dp,
    height: Dp = 48.dp,
    onPress: (down: Boolean) -> Unit,
) {
    Box(
        Modifier.size(width, height).clip(shape).background(Color.White.copy(alpha = 0.25f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onPress(true)
                    waitForUpOrCancellation()
                    onPress(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White) }
}
