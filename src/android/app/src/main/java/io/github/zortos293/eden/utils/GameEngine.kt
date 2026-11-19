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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * Which engine runs a game: Hokkaido's emulator, or Banana Native (game code natively, NVN on Vulkan),
 * built into the mainline flavor (the :banana module) behind Hokkaido's own screens (NativeEngineUi).
 */
object GameEngine {
    enum class Choice { AUTO, EMULATE, NATIVE }

    /** Banana Native's own processes (its manifest): they run none of the emulator's setup. */
    private val embeddedProcesses = listOf(":game", ":pack", ":readiness", ":selftest")

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

    /** Banana Native's status for the game ("playable", "bring-up"), null when it cannot run it. */
    private fun status(game: Game): String? =
        runCatching { ui?.status(titleIdOf(game)) }.getOrNull()

    /** Whether Banana Native has a profile for the game: only then is it a choice. */
    fun supports(game: Game): Boolean = status(game) != null

    /** Banana Native's game options Hokkaido shows ([id]: HokkaidoBridge.OPTION_*); they apply at the next start. */
    enum class GameOption(val id: Int, val title: Int, val description: Int) {
        FULL_VEGETATION(0, R.string.banana_option_vegetation, R.string.banana_option_vegetation_description),
        RAM_SAVER(1, R.string.banana_option_ram_saver, R.string.banana_option_ram_saver_description),
        RAM_SAVER_STRONG(3, R.string.banana_option_ram_saver_strong, R.string.banana_option_ram_saver_strong_description),
        SOFT_TEXTURES(2, R.string.banana_option_soft_textures, R.string.banana_option_soft_textures_description)
    }

    private fun titleIdOf(game: Game) = game.programIdHex.uppercase().padStart(16, '0')

    fun gameOptions(game: Game): List<GameOption> {
        val engine = ui ?: return emptyList()
        return GameOption.entries.filter {
            runCatching { engine.hasOption(titleIdOf(game), it.id) }.getOrDefault(false)
        }
    }

    fun gameOption(context: Context, game: Game, option: GameOption): Boolean =
        runCatching { ui?.option(context, titleIdOf(game), option.id) }.getOrNull() ?: false

    fun setGameOption(context: Context, game: Game, option: GameOption, on: Boolean) {
        ui?.setOption(context, titleIdOf(game), option.id, on)
    }

    /**
     * Imports a pack that Banana Native's own app exported (Manage › Export pack…) for this game, from the zip
     * the user picked. Null when it is in, else why not. Blocks while it copies: call it off the main thread.
     */
    fun importPack(context: Context, game: Game, zip: Uri): String? {
        val engine = ui ?: return context.getString(R.string.banana_pack_import_unavailable)
        val titleId = titleIdOf(game)
        // A file on shared storage is read in place (Hokkaido has all files access for Banana Native);
        // any other document through its descriptor.
        storagePath(zip)?.let { File(it) }?.takeIf { it.isFile }?.let {
            return engine.importPack(context, it, titleId, filePath(game))
        }
        val descriptor = runCatching { context.contentResolver.openFileDescriptor(zip, "r") }.getOrNull()
            ?: return context.getString(R.string.banana_pack_import_unreadable)
        return descriptor.use {
            engine.importPack(context, File("/proc/self/fd/${it.fd}"), titleId, filePath(game))
        }
    }

    /** What Auto picks: Banana Native for the games its profiles call playable, Hokkaido for the rest. */
    fun autoIsNative(game: Game): Boolean = status(game) == "playable"

    fun usesNative(context: Context, game: Game): Boolean = when (choice(context, game)) {
        Choice.NATIVE -> supports(game)
        Choice.EMULATE -> false
        Choice.AUTO -> autoIsNative(game)
    }

    /** Banana Native's name for this game: marked experimental while its profile is in bring-up. */
    fun nativeName(context: Context, game: Game): String = context.getString(
        if (autoIsNative(game)) R.string.game_engine_native else R.string.game_engine_native_experimental
    )

    fun describe(context: Context, game: Game): String = when (choice(context, game)) {
        Choice.EMULATE -> context.getString(R.string.game_engine_emulate)
        Choice.NATIVE -> nativeName(context, game)
        Choice.AUTO -> context.getString(
            R.string.game_engine_auto_now,
            if (autoIsNative(game)) nativeName(context, game)
            else context.getString(R.string.game_engine_emulate)
        )
    }

    /**
     * The game's path for Banana Native, which opens games by path: a storage document URI becomes
     * /storage/<volume>/<path>. A loose game (Hokkaido lists its exefs/main) is its dump folder,
     * the one holding base/ and updates/<version>/ (or exefs/), as Banana Native expects.
     */
    private fun filePath(game: Game): String? {
        val path = storagePath(Uri.parse(game.path)) ?: return null
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

    /** A file URI's path, or a shared storage document's /storage/<volume>/<path>; null for other documents. */
    private fun storagePath(uri: Uri): String? {
        return if (uri.scheme == null || uri.scheme == "file") {
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
    }

    /**
     * Starts the game in Banana Native, through Hokkaido's screens: at once when it is prepared, else
     * after preparing it. False when this build has no Banana Native or the game has no path it reads.
     */
    private fun launchNative(
        activity: FragmentActivity,
        game: Game,
        useCustom: Boolean,
        emulate: () -> Unit
    ): Boolean {
        val engine = ui ?: return false
        val path = filePath(game) ?: return false
        val (driver, window) = gameSettings(game, useCustom)
        engine.launch(activity, path, game.title, driver, game.saveDir, window, emulate)
        return true
    }

    /**
     * Translates every shader of the game for Banana Native now (Game properties), with the game's own settings for
     * the start offered after. False when this build has no Banana Native or the game has no path it reads.
     */
    fun prepareNativeShaders(activity: FragmentActivity, game: Game, emulate: () -> Unit): Boolean {
        val engine = ui ?: return false
        val path = filePath(game) ?: return false
        val (driver, window) = gameSettings(game, true)
        engine.prepareShaders(activity, path, game.title, driver, game.saveDir, window, emulate)
        return true
    }

    /**
     * Starts a game in its engine (Game properties › Engine): every way of starting a game comes here.
     * [useCustom] is the game's own settings rather than the global ones (Start › Global or Custom);
     * [emulate] starts it in Hokkaido.
     */
    fun start(activity: FragmentActivity, game: Game, useCustom: Boolean = true, emulate: () -> Unit) {
        if (!usesNative(activity, game)) {
            emulate()
        } else if (!launchNative(activity, game, useCustom, emulate)) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.game_engine_banana_missing)
                .setMessage(R.string.game_engine_banana_missing_description)
                .setPositiveButton(R.string.game_engine_play_here) { _, _ -> emulate() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /**
     * What Hokkaido would start the game with: the GPU driver (a zip; empty: the system's) and the render
     * size its resolution scale gives, 1x being 1920x1080. With [useCustom] the game's own settings,
     * else the global ones.
     */
    private fun gameSettings(game: Game, useCustom: Boolean): Pair<String, String> {
        val loaded = NativeConfig.isPerGameConfigLoaded()
        if (useCustom && !loaded) {
            SettingsFile.loadCustomConfig(game)
        }
        val needsGlobal = !useCustom
        val driver = StringSetting.DRIVER_PATH.getString(needsGlobal)
        val window = windowOf(IntSetting.RENDERER_RESOLUTION.getInt(needsGlobal))
        if (useCustom && !loaded) {
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
