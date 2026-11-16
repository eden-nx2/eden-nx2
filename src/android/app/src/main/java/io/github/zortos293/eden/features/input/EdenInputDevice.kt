// SPDX-License-Identifier: GPL-2.0-or-later

package io.github.zortos293.eden.features.input

import android.view.InputDevice
import androidx.annotation.Keep
import io.github.zortos293.eden.EdenApplication
import io.github.zortos293.eden.R
import io.github.zortos293.eden.utils.InputHandler.getGUID

@Keep
interface EdenInputDevice {
    fun getName(): String

    fun getGUID(): String

    fun getPort(): Int

    fun getSupportsVibration(): Boolean

    fun vibrate(intensity: Float)

    fun getAxes(): Array<Int> = arrayOf()
    fun hasKeys(keys: IntArray): BooleanArray = BooleanArray(0)
}

class EdenPhysicalDevice(
    private val device: InputDevice,
    private val port: Int,
    useSystemVibrator: Boolean
) : EdenInputDevice {
    private val vibrator = if (useSystemVibrator) {
        EdenVibrator.getSystemVibrator()
    } else {
        EdenVibrator.getControllerVibrator(device)
    }

    override fun getName(): String {
        return device.name
    }

    override fun getGUID(): String {
        return device.getGUID()
    }

    override fun getPort(): Int {
        return port
    }

    override fun getSupportsVibration(): Boolean {
        return vibrator.supportsVibration()
    }

    override fun vibrate(intensity: Float) {
        vibrator.vibrate(intensity)
    }

    override fun getAxes(): Array<Int> = device.motionRanges.map { it.axis }.toTypedArray()
    override fun hasKeys(keys: IntArray): BooleanArray = device.hasKeys(*keys)
}

class EdenInputOverlayDevice(
    private val vibration: Boolean,
    private val port: Int
) : EdenInputDevice {
    private val vibrator = EdenVibrator.getSystemVibrator()

    override fun getName(): String {
        return EdenApplication.appContext.getString(R.string.input_overlay)
    }

    override fun getGUID(): String {
        return "00000000000000000000000000000000"
    }

    override fun getPort(): Int {
        return port
    }

    override fun getSupportsVibration(): Boolean {
        if (vibration) {
            return vibrator.supportsVibration()
        }
        return false
    }

    override fun vibrate(intensity: Float) {
        if (vibration) {
            vibrator.vibrate(intensity)
        }
    }
}
