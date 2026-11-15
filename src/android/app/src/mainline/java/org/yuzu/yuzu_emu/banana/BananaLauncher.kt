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
 * packer, in its own process) with Hokkaido's progress dialog, and starting it. Banana Native's own screens are
 * never shown.
 */
class BananaLauncher : NativeEngineUi {
    override fun install(context: Context) = HokkaidoBridge.install(context)

    override fun launch(activity: FragmentActivity, gamePath: String, title: String, emulate: () -> Unit) =
        check(activity, gamePath, title, false, emulate)

    private fun check(
        activity: FragmentActivity,
        path: String,
        title: String,
        tryAnyway: Boolean,
        emulate: () -> Unit
    ) {
        val (box, _, _) = progressView(activity, true)
        val checking = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.banana_checking, title))
            .setView(box)
            .setCancelable(false)
            .show()
        activity.lifecycleScope.launch {
            val plan = withContext(Dispatchers.IO) {
                runCatching { HokkaidoBridge.plan(activity, path, tryAnyway) }
            }
            checking.dismiss()
            plan.onSuccess { show(activity, it, path, emulate) }
                .onFailure { problem(activity, title, it.message ?: it.toString(), emulate) }
        }
    }

    private fun show(
        activity: FragmentActivity,
        plan: HokkaidoBridge.Plan,
        path: String,
        emulate: () -> Unit
    ) {
        when (plan.kind) {
            HokkaidoBridge.Plan.READY -> HokkaidoBridge.play(activity, plan)
            HokkaidoBridge.Plan.PREPARE -> MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.banana_prepare_title, plan.title))
                .setMessage(plan.message)
                .setPositiveButton(R.string.banana_prepare) { _, _ -> start(activity, plan, path, emulate) }
                .setNeutralButton(R.string.game_engine_play_here) { _, _ -> emulate() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            HokkaidoBridge.Plan.RESUME -> MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.banana_resume_title, plan.title))
                .setMessage(plan.message)
                .setPositiveButton(R.string.banana_resume) { _, _ -> start(activity, plan, path, emulate) }
                .setNeutralButton(R.string.game_engine_play_here) { _, _ -> emulate() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            HokkaidoBridge.Plan.RUNNING -> progress(activity, path, plan.title, emulate)
            else -> {
                val builder = MaterialAlertDialogBuilder(activity)
                    .setTitle(plan.title)
                    .setMessage(
                        if (plan.detail.isEmpty()) plan.message else plan.message + "\n\n" + plan.detail
                    )
                    .setNeutralButton(R.string.game_engine_play_here) { _, _ -> emulate() }
                    .setNegativeButton(android.R.string.ok, null)
                if (plan.canTryAnyway) {
                    builder.setPositiveButton(R.string.banana_try_anyway) { _, _ ->
                        check(activity, path, plan.title, true, emulate)
                    }
                }
                builder.show()
            }
        }
    }

    private fun start(
        activity: FragmentActivity,
        plan: HokkaidoBridge.Plan,
        path: String,
        emulate: () -> Unit
    ) {
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
                if (running) {
                    progress(activity, path, plan.title, emulate)
                } else {
                    check(activity, path, plan.title, false, emulate)
                }
            }.onFailure { problem(activity, plan.title, it.message ?: it.toString(), emulate) }
        }
    }

    private fun progress(activity: FragmentActivity, path: String, title: String, emulate: () -> Unit) {
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
                    .setPositiveButton(R.string.banana_play) { _, _ ->
                        check(activity, path, title, false, emulate)
                    }
                    .setNegativeButton(R.string.banana_later, null)
                    .show()
            }
        }.also { it.start() }
    }

    private fun problem(activity: FragmentActivity, title: String, message: String, emulate: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setNeutralButton(R.string.game_engine_play_here) { _, _ -> emulate() }
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
