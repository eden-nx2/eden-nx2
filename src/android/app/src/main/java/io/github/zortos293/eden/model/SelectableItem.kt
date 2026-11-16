// SPDX-License-Identifier: GPL-2.0-or-later

package io.github.zortos293.eden.model

interface SelectableItem {
    var selected: Boolean
    fun onSelectionStateChanged(selected: Boolean)
}
