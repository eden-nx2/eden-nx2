// SPDX-License-Identifier: GPL-2.0-or-later

package io.github.zortos293.eden.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import io.github.zortos293.eden.HomeNavigationDirections
import io.github.zortos293.eden.NativeLibrary
import io.github.zortos293.eden.R
import io.github.zortos293.eden.EdenApplication
import io.github.zortos293.eden.databinding.DialogListItemBinding
import io.github.zortos293.eden.model.CabinetMode
import io.github.zortos293.eden.adapters.CabinetLauncherDialogAdapter.CabinetModeViewHolder
import io.github.zortos293.eden.model.AppletInfo
import io.github.zortos293.eden.model.Game
import io.github.zortos293.eden.viewholder.AbstractViewHolder

class CabinetLauncherDialogAdapter(val fragment: Fragment) :
    AbstractListAdapter<CabinetMode, CabinetModeViewHolder>(
        CabinetMode.values().copyOfRange(1, CabinetMode.entries.size).toList()
    ) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CabinetModeViewHolder {
        DialogListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            .also { return CabinetModeViewHolder(it) }
    }

    inner class CabinetModeViewHolder(val binding: DialogListItemBinding) :
        AbstractViewHolder<CabinetMode>(binding) {
        override fun bind(model: CabinetMode) {
            binding.icon.setImageDrawable(
                ResourcesCompat.getDrawable(
                    binding.icon.context.resources,
                    model.iconId,
                    binding.icon.context.theme
                )
            )
            binding.title.setText(model.titleId)

            binding.root.setOnClickListener { onClick(model) }
        }

        private fun onClick(mode: CabinetMode) {
            val appletPath = NativeLibrary.getAppletLaunchPath(AppletInfo.Cabinet.entryId)
            NativeLibrary.setCurrentAppletId(AppletInfo.Cabinet.appletId)
            NativeLibrary.setCabinetMode(mode.id)
            val appletGame = Game(
                title = EdenApplication.appContext.getString(R.string.cabinet_applet),
                path = appletPath
            )
            val action = HomeNavigationDirections.actionGlobalEmulationActivity(appletGame)
            fragment.findNavController().navigate(action)
        }
    }
}
