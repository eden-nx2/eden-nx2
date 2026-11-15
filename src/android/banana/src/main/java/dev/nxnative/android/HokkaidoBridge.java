// SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.nxnative.android;

import android.content.Context;

/** Hokkaido embeds Banana Native: the setup its NxApp ran in every process, and its updater kept off. */
public final class HokkaidoBridge {
    private HokkaidoBridge() {}

    /** NxApp.onCreate's work: Hokkaido's application runs it in every process. */
    public static void install(Context context) {
        PickedNup.install(context);
    }

    /** Embedded, Banana Native never updates itself: Hokkaido's releases carry it (Updater.sources). */
    static boolean embedded() {
        return true;
    }
}
