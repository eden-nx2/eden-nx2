// SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.zortos293.eden.banana

import android.graphics.Color
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import dev.nxnative.android.HokkaidoInGame
import io.github.zortos293.eden.R
import kotlin.math.roundToInt

/** Hokkaido's look over a Banana Native game, in its game process (HokkaidoInGame). */
object InGameUi : HokkaidoInGame.Ui {
    override fun create(screen: HokkaidoInGame.Screen): HokkaidoInGame.Menu = InGameMenu(screen)

    /** As Hokkaido's stats overlay: small white text with a shadow, no background. */
    override fun styleOverlay(view: TextView) {
        val dp = view.resources.displayMetrics.density
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        view.setTextColor(Color.WHITE)
        view.setShadowLayer(3f, 0f, 0f, Color.BLACK)
        view.background = null
        view.alpha = 1f
        val pad = (8 * dp).roundToInt()
        view.setPadding(pad, pad / 2, pad, pad / 2)
    }

    /** As Hokkaido's paused screen: the play button in a white circle in the middle. */
    override fun stylePaused(view: TextView) {
        val context = view.context
        val dp = context.resources.displayMetrics.density
        val circle = ContextCompat.getDrawable(context, R.drawable.circle_white) ?: return
        val play = ContextCompat.getDrawable(context, R.drawable.ic_play)?.mutate() ?: return
        DrawableCompat.setTint(play, Color.BLACK)
        val size = (64 * dp).roundToInt()
        val inset = (14 * dp).roundToInt()
        val icon = LayerDrawable(arrayOf(circle, play)).apply {
            setLayerInset(1, inset, inset, inset, inset)
            setBounds(0, 0, size, size)
        }
        view.text = ""
        view.setCompoundDrawables(null, icon, null, null)
        view.gravity = Gravity.CENTER
        view.setBackgroundColor(0x40000000)
        view.contentDescription = context.getString(R.string.emulation_unpause)
    }
}
