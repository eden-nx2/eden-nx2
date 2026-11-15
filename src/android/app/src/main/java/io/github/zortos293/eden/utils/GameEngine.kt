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
        "040074A01BF12000" // Donkey Kong Bananza
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
        engine.launch(activity, path, game.title, emulate)
        return true
    }
}
