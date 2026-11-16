// SPDX-License-Identifier: GPL-2.0-or-later

package io.github.zortos293.eden.disk_shader_cache

import androidx.annotation.Keep
import androidx.lifecycle.ViewModelProvider
import io.github.zortos293.eden.NativeLibrary
import io.github.zortos293.eden.R
import io.github.zortos293.eden.model.EmulationViewModel
import io.github.zortos293.eden.utils.Log
import io.github.zortos293.eden.activities.EmulationActivity

@Keep
object DiskShaderCacheProgress {
    private var emulationViewModel: EmulationViewModel? = null

    @JvmStatic
    fun loadProgress(stage: Int, progress: Int, max: Int) {
        val emulationActivity = NativeLibrary.sEmulationActivity.get()
        if (emulationActivity == null) {
            Log.error("[DiskShaderCacheProgress] EmulationActivity not present")
            return
        }

        emulationActivity.runOnUiThread {
            // Leaving the game can close the activity before this runs (the weak reference is
            // empty by then, which crashed the app): use the activity captured above, if alive.
            if (emulationActivity.isFinishing || emulationActivity.isDestroyed) {
                return@runOnUiThread
            }
            when (LoadCallbackStage.values()[stage]) {
                LoadCallbackStage.Prepare -> emulationViewModel =
                    ViewModelProvider(emulationActivity)[EmulationViewModel::class.java]
                LoadCallbackStage.Build -> emulationViewModel?.updateProgress(
                    emulationActivity.getString(R.string.building_shaders),
                    progress,
                    max
                )

                LoadCallbackStage.Complete -> {}
            }
        }
    }

    // Equivalent to VideoCore::LoadCallbackStage
    enum class LoadCallbackStage {
        Prepare, Build, Complete
    }
}
