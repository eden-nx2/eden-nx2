// SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.zortos293.eden.utils

import android.content.Context
import androidx.fragment.app.FragmentActivity

/**
 * Hokkaido's UI for the Banana Native engine (GameEngine): checking, preparing and starting a game. The mainline
 * flavor, which carries Banana Native, implements it (io.github.zortos293.eden.banana.BananaLauncher).
 */
interface NativeEngineUi {
    /** What Banana Native's application class did in every process. */
    fun install(context: Context)

    /** Banana Native's status for a game ("playable", "bring-up"), or null when it has no profile for it. */
    fun status(titleId: String): String?

    /** Closes Banana Native's game process when it holds this game. Blocks up to 3 s: call it off the main thread. */
    fun closeGame(context: Context, titleId: String)

    /**
     * Puts a pack that Banana Native's own app exported ([source]: its zip) into Hokkaido's packs for [titleId],
     * checked against the game in [dumpPath]. Null when it is in, else why not. Blocks: call it off the main thread.
     */
    fun importPack(context: Context, source: java.io.File, titleId: String, dumpPath: String?): String?

    /** Whether the game has one of Banana Native's game options ([option]: a GameEngine.GameOption's ordinal). */
    fun hasOption(titleId: String, option: Int): Boolean

    fun option(context: Context, titleId: String, option: Int): Boolean

    /** Sets a game option; it takes effect at the game's next start. */
    fun setOption(context: Context, titleId: String, option: Int, on: Boolean)

    /**
     * Starts the game in Banana Native, preparing it first when it needs that, with Hokkaido's GPU driver for it
     * ([driverZip]: a driver zip, empty for the system's), Hokkaido's save folder ([saveDir], shared by both
     * engines) and the render size of Hokkaido's resolution scale ([window]: "<width>x<height>" or "original");
     * [emulate] plays it in Hokkaido instead.
     */
    fun launch(
        activity: FragmentActivity,
        gamePath: String,
        title: String,
        driverZip: String,
        saveDir: String,
        window: String,
        emulate: () -> Unit
    )
}
