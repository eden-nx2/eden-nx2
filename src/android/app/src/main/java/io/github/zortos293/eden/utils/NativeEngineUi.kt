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

    /** Starts the game in Banana Native, preparing it first when it needs that; [emulate] plays it in Hokkaido instead. */
    fun launch(activity: FragmentActivity, gamePath: String, title: String, emulate: () -> Unit)
}
