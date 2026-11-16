// SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.zortos293.eden.utils

import android.app.Application
import android.content.Context
import androidx.fragment.app.FragmentActivity
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import io.github.zortos293.eden.R
import io.github.zortos293.eden.model.Game
import io.github.zortos293.eden.features.settings.model.StringSetting
import io.github.zortos293.eden.features.settings.utils.SettingsFile
import io.github.zortos293.eden.features.settings.model.IntSetting
import java.io.File

/**
 * Which engine runs a game: Hokkaido's emulator, or Banana Native (game code natively, NVN on Vulkan),
 * built into the mainline flavor (the :banana module) behind Hokkaido's own screens (NativeEngineUi).
 */
object GameEngine {
    enum class Choice { AUTO, EMULATE, NATIVE }

    /** Banana Native's own processes (its manifest): they run none of the emulator's setup. */
    private val embeddedProcesses = listOf(":game", ":pack", ":readiness", ":selftest")

    /** Games Auto gives to Banana Native: it runs them better than the emulator. */
    private val autoNative = setOf(
        "040074A01BF12000", // Donkey Kong Bananza
        "0400A3A01EE4C000" // Pokemon Pokopia
    )

    /** Banana Native's UI, in the mainline flavor only. */
    private val ui: NativeEngineUi? by lazy {
        runCatching {
            Class.forName("io.github.zortos293.eden.banana.BananaLauncher").getDeclaredConstructor()
                .newInstance() as NativeEngineUi
        }.getOrNull()
    }

    /** Whether this build carries Banana Native (the mainline flavor). */
    val isBananaAvailable: Boolean get() = ui != null

    /** What Banana Native's application class did in every process (PickedNup). */
    fun installEmbedded(context: Context) {
        runCatching { ui?.install(context) }
            .onFailure { android.util.Log.e("GameEngine", "Banana Native setup failed", it) }
    }

    fun isEmbeddedProcess(): Boolean {
        val name = Application.getProcessName() ?: return false
        return embeddedProcesses.any { name.endsWith(it) }
    }

    private fun key(game: Game) = "game_engine_" + game.programIdHex.uppercase()

    fun choice(context: Context, game: Game): Choice {
        val stored = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(key(game), null)
        return Choice.entries.firstOrNull { it.name == stored } ?: Choice.AUTO
    }

    fun setChoice(context: Context, game: Game, choice: Choice) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            if (choice == Choice.AUTO) remove(key(game)) else putString(key(game), choice.name)
        }
    }

    /** What Auto picks for this game. */
    fun autoIsNative(game: Game): Boolean =
        isBananaAvailable && game.programIdHex.uppercase().padStart(16, '0') in autoNative

    fun usesNative(context: Context, game: Game): Boolean = when (choice(context, game)) {
        Choice.NATIVE -> true
        Choice.EMULATE -> false
        Choice.AUTO -> autoIsNative(game)
    }

    fun describe(context: Context, game: Game): String = when (choice(context, game)) {
        Choice.EMULATE -> context.getString(R.string.game_engine_emulate)
        Choice.NATIVE -> context.getString(R.string.game_engine_native)
        Choice.AUTO -> context.getString(
            R.string.game_engine_auto_now,
            context.getString(
                if (autoIsNative(game)) R.string.game_engine_native else R.string.game_engine_emulate
            )
        )
    }

    /**
     * The game's path for Banana Native, which opens games by path: a storage document URI becomes
     * /storage/<volume>/<path>. A loose game (Hokkaido lists its exefs/main) is its dump folder,
     * the one holding base/ and updates/<version>/ (or exefs/), as Banana Native expects.
     */
    private fun filePath(game: Game): String? {
        val uri = Uri.parse(game.path)
        val path = if (uri.scheme == null || uri.scheme == "file") {
            uri.path ?: return null
        } else {
            if (uri.authority != "com.android.externalstorage.documents") {
                return null
            }
            val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
            val volume = id.substringBefore(':')
            val relative = id.substringAfter(':', "")
            val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
            if (relative.isEmpty()) root else "$root/$relative"
        }
        if (!path.endsWith("/exefs/main")) {
            return path
        }
        val folder = path.removeSuffix("/exefs/main")
        return when {
            Regex(".*/updates/[0-9]+$").matches(folder) -> folder.replace(Regex("/updates/[0-9]+$"), "")
            folder.endsWith("/base") -> folder.removeSuffix("/base")
            else -> folder
        }
    }

    /**
     * Starts the game in Banana Native, through Hokkaido's screens: at once when it is prepared, else
     * after preparing it. False when this build has no Banana Native or the game has no path it reads.
     */
    fun launchNative(activity: FragmentActivity, game: Game, emulate: () -> Unit): Boolean {
        val engine = ui ?: return false
        val path = filePath(game) ?: return false
        val (driver, window) = gameSettings(game)
        engine.launch(activity, path, game.title, driver, game.saveDir, window, emulate)
        return true
    }

    /**
     * What Hokkaido would start the game with, its own settings else the global ones: the GPU driver (a zip;
     * empty: the system's) and the render size its resolution scale gives, 1x being 1920x1080.
     */
    private fun gameSettings(game: Game): Pair<String, String> {
        val loaded = NativeConfig.isPerGameConfigLoaded()
        if (!loaded) {
            SettingsFile.loadCustomConfig(game)
        }
        val driver = StringSetting.DRIVER_PATH.getString()
        val window = windowOf(IntSetting.RENDERER_RESOLUTION.getInt())
        if (!loaded) {
            NativeConfig.unloadPerGameConfig()
            NativeConfig.reloadGlobalConfig()
        }
        return driver to window
    }

    /**
     * Removes the game's save folder and what Banana Native keeps beside it, which would bring the save back:
     * its transaction and journal (<save>.work, .new, .re, .old, .restore, .pend1, .pend2, .restore-set), its
     * lock, and its device save (<save>_device and that one's). Banana Native's game process is closed first
     * when it holds the game. Blocks: call it off the main thread. False when something stayed.
     */
    fun deleteSave(context: Context, game: Game): Boolean {
        val save = File(game.saveDir)
        runCatching { ui?.closeGame(context, game.programIdHex) }
        val parent = save.parentFile ?: return !save.exists() || save.deleteRecursively()
        var ok = true
        parent.listFiles()?.filter {
            it.name == save.name || it.name.startsWith(save.name + ".") ||
                it.name.startsWith(save.name + "_device")
        }?.forEach { ok = it.deleteRecursively() && ok }
        return ok
    }

    /** ResolutionSetup (Res1_4X … Res8X) as Banana Native's Game resolution; above 2x the game's own size. */
    private fun windowOf(setup: Int): String {
        val scale = listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0).getOrNull(setup) ?: return "original"
        return "${(1920 * scale).toInt()}x${(1080 * scale).toInt()}"
    }
}
