// SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.zortos293.eden.banana

import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.slider.Slider
import com.google.android.material.textview.MaterialTextView
import io.github.zortos293.eden.R
import kotlin.math.roundToInt

/**
 * Banana Native's in-game panel rows, which its panel builds and never shows, as Hokkaido's quick settings rows: a
 * label before a choice or a slider is that row's title, a check box is a switch, a row button opens what it opened
 * there, and anything else is shown as it is. Every change goes through Banana Native's own widget, so its logic
 * stays its own; sync() copies its state back (texts, what is checked, enabled or hidden).
 */
internal class PanelRows(
    private val context: Context,
    private val container: LinearLayout,
    /** Keeps the drawer from taking a slider's drag. */
    private val holdDrawer: (Boolean) -> Unit
) {
    private val inflater = LayoutInflater.from(context)
    private val dp = context.resources.displayMetrics.density
    private val syncs = mutableListOf<() -> Unit>()
    private var source: View? = null
    private var syncing = false

    fun render(rows: LinearLayout) {
        container.removeAllViews()
        syncs.clear()
        source = rows
        val items = mutableListOf<View>()
        flatten(rows, items)
        var i = 0
        while (i < items.size) {
            val v = items[i]
            val next = items.getOrNull(i + 1)
            when {
                v is RadioGroup -> choice(null, v)
                v is SeekBar -> slider(null, v)
                v is CompoundButton -> switch(v)
                v is Button -> button(v)
                v is TextView && next is RadioGroup -> { choice(v, next); i++ }
                v is TextView && next is SeekBar -> { slider(v, next); i++ }
                v is TextView -> if (v.typeface?.isBold == true) header(v) else note(v)
                v is LinearLayout && v.orientation == LinearLayout.HORIZONTAL -> textRow(v)
                else -> adopt(v)
            }
            i++
        }
        sync()
    }

    fun sync() {
        syncing = true
        syncs.forEach { it() }
        syncing = false
    }

    /** The panel's rows in order: containers opened up, except a choice and a line of labels (a slider's ends). */
    private fun flatten(view: View, out: MutableList<View>) {
        when {
            view is RadioGroup || view is TextView || view is SeekBar -> out.add(view)
            view is LinearLayout && view.orientation == LinearLayout.HORIZONTAL && view.childCount > 0 &&
                view.children.all { it is TextView && it !is Button } -> out.add(view)
            view is ViewGroup && view.javaClass in plainGroups -> view.children.forEach { flatten(it, out) }
            else -> out.add(view)
        }
    }

    /** Shown in the panel: it and every parent up to the rows visible. */
    private fun shown(view: View): Boolean {
        var v: View? = view
        while (v != null && v !== source) {
            if (v.visibility != View.VISIBLE) return false
            v = v.parent as? View
        }
        return true
    }

    private fun item(): View = inflater.inflate(R.layout.item_quick_settings_menu, container, false)

    private fun choice(label: TextView?, group: RadioGroup) {
        val item = item()
        val header = item.findViewById<ViewGroup>(R.id.setting_header)
        val title = item.findViewById<TextView>(R.id.setting_title)
        val value = item.findViewById<TextView>(R.id.setting_value)
        val expand = item.findViewById<ImageView>(R.id.expand_icon)
        val radios = item.findViewById<RadioGroup>(R.id.radio_group)
        header.isVisible = true
        val pairs = group.children.filterIsInstance<RadioButton>().map { theirs ->
            val ours = MaterialRadioButton(context).apply {
                id = View.generateViewId()
                text = theirs.text
                setPadding(16, 8, 16, 8)
            }
            ours.setOnCheckedChangeListener { _, checked ->
                if (checked && !syncing && !theirs.isChecked) {
                    theirs.performClick()
                    sync()
                }
            }
            radios.addView(ours)
            theirs to ours
        }.toList()
        var expanded = false
        header.setOnClickListener {
            expanded = !expanded
            radios.isVisible = expanded
            expand.animate().rotation(if (expanded) 180f else 0f).setDuration(200).start()
        }
        syncs += {
            val checked = pairs.firstOrNull { it.first.isChecked }?.first
            title.text = label?.text ?: checked?.text ?: ""
            value.text = if (label != null) checked?.text ?: "" else ""
            value.isVisible = value.text.isNotEmpty()
            for ((theirs, ours) in pairs) {
                ours.text = theirs.text
                ours.isEnabled = theirs.isEnabled
                ours.isVisible = theirs.visibility == View.VISIBLE
                if (ours.isChecked != theirs.isChecked) ours.isChecked = theirs.isChecked
            }
            item.isVisible = shown(group)
        }
        container.addView(item)
    }

    private fun slider(label: TextView?, bar: SeekBar) {
        val item = item()
        val box = item.findViewById<ViewGroup>(R.id.slider_container)
        val title = item.findViewById<TextView>(R.id.slider_title)
        val value = item.findViewById<TextView>(R.id.slider_value_display)
        val slider = item.findViewById<Slider>(R.id.setting_slider)
        box.isVisible = true
        slider.valueFrom = 0f
        slider.valueTo = bar.max.coerceAtLeast(1).toFloat()
        slider.stepSize = 1f
        // Each step as a D-pad press on Banana Native's slider: its listeners take only the user's changes
        slider.addOnChangeListener { _, v, fromUser ->
            if (fromUser && !syncing) {
                bar.keyProgressIncrement = 1
                val target = v.roundToInt()
                while (bar.progress != target) {
                    val code = if (target > bar.progress) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                    val before = bar.progress
                    bar.onKeyDown(code, KeyEvent(KeyEvent.ACTION_DOWN, code))
                    if (bar.progress == before) break
                }
            }
        }
        slider.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> holdDrawer(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    holdDrawer(false)
                    sync()
                }
            }
            false
        }
        syncs += {
            title.text = label?.text ?: ""
            value.isVisible = false
            slider.isEnabled = bar.isEnabled
            val v = bar.progress.coerceIn(0, bar.max.coerceAtLeast(1)).toFloat()
            if (slider.value != v) slider.value = v
            item.isVisible = shown(bar)
        }
        container.addView(item)
    }

    private fun switch(box: CompoundButton) {
        val item = item()
        val row = item.findViewById<ViewGroup>(R.id.switch_container)
        val title = item.findViewById<TextView>(R.id.switch_title)
        val toggle = item.findViewById<MaterialSwitch>(R.id.setting_switch)
        row.isVisible = true
        toggle.setOnCheckedChangeListener { _, on ->
            if (!syncing && box.isChecked != on) {
                box.isChecked = on
                sync()
            }
        }
        row.setOnClickListener { if (toggle.isEnabled) toggle.toggle() }
        syncs += {
            title.text = box.text
            toggle.isEnabled = box.isEnabled
            row.isEnabled = box.isEnabled
            title.isEnabled = box.isEnabled
            if (toggle.isChecked != box.isChecked) toggle.isChecked = box.isChecked
            item.isVisible = shown(box)
        }
        container.addView(item)
    }

    /** A row of the panel that opens a choice or a screen: "label  value  ›", or a plain button. */
    private fun button(button: Button) {
        val item = item()
        val header = item.findViewById<ViewGroup>(R.id.setting_header)
        val title = item.findViewById<TextView>(R.id.setting_title)
        val value = item.findViewById<TextView>(R.id.setting_value)
        val expand = item.findViewById<ImageView>(R.id.expand_icon)
        header.isVisible = true
        expand.rotation = -90f
        header.setOnClickListener {
            button.performClick()
            sync()
        }
        syncs += {
            val parts = button.text.toString().removeSuffix("›").trim().split("  ", limit = 2)
            title.text = parts[0].trim()
            value.text = parts.getOrNull(1)?.trim() ?: ""
            value.isVisible = value.text.isNotEmpty()
            header.isEnabled = button.isEnabled
            item.isVisible = shown(button)
        }
        container.addView(item)
    }

    private fun header(source: TextView) {
        val divider = if (container.childCount > 0) {
            inflater.inflate(R.layout.item_quick_settings_divider, container, false).also { container.addView(it) }
        } else {
            null
        }
        val view = text(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall, 16, 4)
        view.setTextColor(color(com.google.android.material.R.attr.colorPrimary))
        view.setTypeface(view.typeface, Typeface.BOLD)
        syncs += {
            view.text = source.text
            view.isVisible = shown(source) && source.text.isNotEmpty()
            divider?.isVisible = view.isVisible
        }
        container.addView(view)
    }

    private fun note(source: TextView) {
        val view = text(com.google.android.material.R.style.TextAppearance_Material3_BodySmall, 4, 4)
        view.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
        syncs += {
            view.text = source.text
            view.isVisible = shown(source) && source.text.isNotEmpty()
        }
        container.addView(view)
    }

    /** Labels side by side (a slider's two ends): the first at the start, the last at the end. */
    private fun textRow(source: LinearLayout) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val pad = (24 * dp).roundToInt()
        row.setPadding(pad, 0, pad, (4 * dp).roundToInt())
        val labels = source.children.filterIsInstance<TextView>().toList()
        val ours = labels.mapIndexed { i, _ ->
            MaterialTextView(context).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                textAlignment = if (i == labels.lastIndex && i > 0) View.TEXT_ALIGNMENT_VIEW_END else View.TEXT_ALIGNMENT_VIEW_START
                row.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        syncs += {
            labels.forEachIndexed { i, l -> ours[i].text = l.text }
            row.isVisible = shown(source)
        }
        container.addView(row)
    }

    /** A row this has no Hokkaido look for: Banana Native's own view, moved here. */
    private fun adopt(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
        val frame = LinearLayout(context)
        val pad = (24 * dp).roundToInt()
        frame.setPadding(pad, (4 * dp).roundToInt(), pad, (4 * dp).roundToInt())
        frame.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        syncs += { frame.isVisible = view.visibility == View.VISIBLE }
        container.addView(frame)
    }

    private fun text(appearance: Int, top: Int, bottom: Int): TextView {
        val view = MaterialTextView(context)
        view.setTextAppearance(appearance)
        val pad = (24 * dp).roundToInt()
        view.setPadding(pad, (top * dp).roundToInt(), pad, (bottom * dp).roundToInt())
        return view
    }

    private fun color(attr: Int): Int {
        val a = context.obtainStyledAttributes(intArrayOf(attr))
        val c = a.getColor(0, 0)
        a.recycle()
        return c
    }

    private companion object {
        /** The containers the panel's builders use for grouping (opened up), not custom views. */
        val plainGroups = setOf(LinearLayout::class.java, android.widget.FrameLayout::class.java)
    }
}
