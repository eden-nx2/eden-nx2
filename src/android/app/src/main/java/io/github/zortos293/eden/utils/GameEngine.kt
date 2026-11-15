// SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.zortos293.eden.utils

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import io.github.zortos293.eden.R
import io.github.zortos293.eden.model.Game

/**
 * Which engine runs a game: Hokkaido's emulator, or Banana Native (game code natively, NVN on Vulkan),
 * built into the mainline flavor (the :banana module) and started through its FrontendLaunch activity
 * (BananaNative android/FRONTENDS.md).
 */
object GameEngine {
    enum class Choice { AUTO, EMULATE, NATIVE }

    private const val BANANA_PACKAGE = "dev.nxnative.android"
    private const val FRONTEND_LAUNCH = "$BANANA_PACKAGE.FrontendLaunch"
    private const val BRIDGE = "$BANANA_PACKAGE.HokkaidoBridge"

    /** Banana Native's own processes (its manifest): they run none of the emulator's setup. */
    private val embeddedProcesses = listOf(":game", ":pack", ":readiness", ":selftest")

    /** Games Auto gives to Banana Native: it runs them better than the emulator. */
    private val autoNative = setOf(
        "040074A01BF12000" // Donkey Kong Bananza
    )

    /** Whether this build carries Banana Native (the mainline flavor). */
    val isBananaAvailable: Boolean by lazy {
        runCatching { Class.forName(FRONTEND_LAUNCH) }.isSuccess
    }

    /** What Banana Native's application class did in every process (PickedNup). */
    fun installEmbedded(context: Context) {
        if (!isBananaAvailable) {
            return
        }
        runCatching {
            Class.forName(BRIDGE).getMethod("install", Context::class.java).invoke(null, context)
        }.onFailure { android.util.Log.e("GameEngine", "Banana Native setup failed", it) }
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
     * The game's file path for Banana Native, which opens games by path: a storage document URI
     * becomes /storage/<volume>/<path>; a path is kept.
     */
    private fun filePath(game: Game): String? {
        val uri = Uri.parse(game.path)
        if (uri.scheme == null || uri.scheme == "file") {
            return uri.path
        }
        if (uri.authority != "com.android.externalstorage.documents") {
            return null
        }
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val volume = id.substringBefore(':')
        val relative = id.substringAfter(':', "")
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return if (relative.isEmpty()) root else "$root/$relative"
    }

    /**
     * Starts the game in Banana Native: at once when it is ready there, else Banana Native's Home
     * opens with it (pack, shaders). False when this build has no Banana Native.
     */
    fun launchNative(context: Context, game: Game): Boolean {
        if (!isBananaAvailable) {
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName(context.packageName, FRONTEND_LAUNCH)
            val uri = Uri.parse(game.path)
            if (uri.scheme == "content") {
                data = uri
            }
            filePath(game)?.let { putExtra("game", it) }
        }
        return runCatching { context.startActivity(intent) }
            .onFailure { android.util.Log.e("GameEngine", "Banana Native did not start", it) }
            .isSuccess
    }
}
