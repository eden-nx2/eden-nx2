// SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.zortos293.eden.banana

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import dev.nxnative.android.HokkaidoBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.zortos293.eden.R
import io.github.zortos293.eden.utils.NativeEngineUi

/**
 * Hokkaido's screens for the Banana Native engine: whether the game is ready, preparing it (Banana Native's
 * packer, in its own process) with Hokkaido's progress dialog, and starting it with Hokkaido's GPU driver and
 * save folder. Banana Native's own screens are never shown.
 */
class BananaLauncher : NativeEngineUi {
    /** One start of one game: what it plays and with what. */
    private class Start(
        val activity: FragmentActivity,
        val path: String,
        val title: String,
        val driverZip: String,
        val saveDir: String,
        val window: String,
        val emulate: () -> Unit
    )

    override fun install(context: Context) = HokkaidoBridge.install(context)

    override fun launch(
        activity: FragmentActivity,
        gamePath: String,
        title: String,
        driverZip: String,
        saveDir: String,
        window: String,
        emulate: () -> Unit
    ) = check(Start(activity, gamePath, title, driverZip, saveDir, window, emulate), false)

    private fun check(start: Start, tryAnyway: Boolean) {
        val activity = start.activity
        val (box, _, _) = progressView(activity, true)
        val checking = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.banana_checking, start.title))
            .setView(box)
            .setCancelable(false)
            .show()
        activity.lifecycleScope.launch {
            val plan = withContext(Dispatchers.IO) {
                runCatching {
                    HokkaidoBridge.plan(activity, start.path, tryAnyway).also {
                        if (it.kind == HokkaidoBridge.Plan.READY) {
                            HokkaidoBridge.prepareStart(
                                activity,
                                it,
                                start.driverZip,
                                start.saveDir,
                                start.window
                            )
                        }
                    }
                }
            }
            checking.dismiss()
            plan.onSuccess { show(start, it) }
                .onFailure { problem(start, start.title, it.message ?: it.toString()) }
        }
    }

    private fun show(start: Start, plan: HokkaidoBridge.Plan) {
        val activity = start.activity
        when (plan.kind) {
            HokkaidoBridge.Plan.READY -> play(start, plan)
            HokkaidoBridge.Plan.PREPARE -> MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.banana_prepare_title, plan.title))
                .setMessage(plan.message)
                .setPositiveButton(R.string.banana_prepare) { _, _ -> prepare(start, plan) }
                .setNeutralButton(R.string.game_engine_play_here) { _, _ -> start.emulate() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            HokkaidoBridge.Plan.RESUME -> MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.banana_resume_title, plan.title))
                .setMessage(plan.message)
                .setPositiveButton(R.string.banana_resume) { _, _ -> prepare(start, plan) }
                .setNeutralButton(R.string.game_engine_play_here) { _, _ -> start.emulate() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            HokkaidoBridge.Plan.RUNNING -> progress(start, plan.title)
            else -> {
                val builder = MaterialAlertDialogBuilder(activity)
                    .setTitle(plan.title)
                    .setMessage(
                        if (plan.detail.isEmpty()) plan.message else plan.message + "\n\n" + plan.detail
                    )
                    .setNeutralButton(R.string.game_engine_play_here) { _, _ -> start.emulate() }
                    .setNegativeButton(android.R.string.ok, null)
                if (plan.canTryAnyway) {
                    builder.setPositiveButton(R.string.banana_try_anyway) { _, _ -> check(start, true) }
                }
                builder.show()
            }
        }
    }

    /**
     * Starts a ready game. Banana Native's game process holds one game: another one still in it (left without
     * Stop the game) is closed first, when the player says so.
     */
    private fun play(start: Start, plan: HokkaidoBridge.Plan) {
        val activity = start.activity
        activity.lifecycleScope.launch {
            val running = withContext(Dispatchers.IO) { HokkaidoBridge.runningGame(activity) }
            if (running == null || running[0].equals(plan.game.titleId, ignoreCase = true)) {
                HokkaidoBridge.play(activity, plan)
                return@launch
            }
            val other = running[1].ifEmpty { activity.getString(R.string.banana_another_game) }
            MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.banana_running_title, other))
                .setMessage(activity.getString(R.string.banana_running_description, start.title, other))
                .setPositiveButton(R.string.banana_close_and_start) { _, _ ->
                    activity.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { HokkaidoBridge.stopRunningGame(activity) }
                        HokkaidoBridge.play(activity, plan)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun prepare(start: Start, plan: HokkaidoBridge.Plan) {
        val activity = start.activity
        // The progress notification needs the player's yes on Android 13+; the job runs either way.
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        activity.lifecycleScope.launch {
            val started = withContext(Dispatchers.IO) {
                runCatching { HokkaidoBridge.prepare(activity, plan) }
            }
            started.onSuccess { running ->
                if (running) progress(start, plan.title) else check(start, false)
            }.onFailure { problem(start, plan.title, it.message ?: it.toString()) }
        }
    }

    private fun progress(start: Start, title: String) {
        val activity = start.activity
        val (box, bar, text) = progressView(activity, false)
        var watch: HokkaidoBridge.Watch? = null
        var finished = false
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.banana_preparing_title, title))
            .setView(box)
            .setPositiveButton(R.string.banana_hide, null)
            .setNegativeButton(R.string.banana_pause) { _, _ -> HokkaidoBridge.pause(activity) }
            .setOnDismissListener { watch?.stop() }
            .show()
        watch = HokkaidoBridge.Watch(activity) { p ->
            bar.setProgressCompat((p.overall * 1000).toInt().coerceIn(0, 1000), true)
            text.text = if (p.error.isEmpty()) p.text else p.text + "\n\n" + p.error
            if (p.ready && !finished) {
                finished = true
                dialog.dismiss()
                MaterialAlertDialogBuilder(activity)
                    .setTitle(activity.getString(R.string.banana_ready_title, title))
                    .setMessage(R.string.banana_ready_description)
                    .setPositiveButton(R.string.banana_play) { _, _ -> check(start, false) }
                    .setNegativeButton(R.string.banana_later, null)
                    .show()
            }
        }.also { it.start() }
    }

    private fun problem(start: Start, title: String, message: String) {
        MaterialAlertDialogBuilder(start.activity)
            .setTitle(title)
            .setMessage(message)
            .setNeutralButton(R.string.game_engine_play_here) { _, _ -> start.emulate() }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun progressView(
        context: Context,
        indeterminate: Boolean
    ): Triple<LinearLayout, LinearProgressIndicator, TextView> {
        val pad = (24 * context.resources.displayMetrics.density).toInt()
        val bar = LinearProgressIndicator(context).apply {
            isIndeterminate = indeterminate
            max = 1000
        }
        val text = TextView(context).apply { setPadding(0, pad / 2, 0, 0) }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(bar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(text, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return Triple(box, bar, text)
    }
}
