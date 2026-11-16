// SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.nxnative.android;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Hokkaido's in-game screens over a Banana Native game: Hokkaido's menu and quick settings in place of the in-game
 * panel's look, and Hokkaido's look for the overlay and the paused screen. The panel (SidePanel) still builds each
 * tab's rows with its own logic; the menu shows them (patches/0002-hokkaido-in-game.patch asks for it).
 */
public final class HokkaidoInGame {
    private HokkaidoInGame() {}

    /** Hokkaido's side: set in every process at the start (BananaLauncher.install). */
    public interface Ui {
        /** The menu of one game screen. */
        Menu create(Screen screen);

        /** One of the overlay's texts (the items over the game), in Hokkaido's look. */
        void styleOverlay(TextView view);

        /** The paused screen (a tap continues), in Hokkaido's look. */
        void stylePaused(TextView view);
    }

    /** One game screen's menu, in the panel's place over the game. */
    public interface Menu {
        View view();

        /** Opened (Back, a controller): the menu shows. */
        void open();

        /** Closed (the game's layout editor, a controller): everything hides. */
        void close();

        /** Opened with a controller: the focus on its first item. */
        void focus();

        /** A tab's rows, as the panel built them (Screen.showTab, again after a change); rows is never shown. */
        void built(int tab, LinearLayout rows);

        /** A controller key while open: confirm and back are the game's own A and B. False lets Back through. */
        boolean padKey(KeyEvent e, int confirm, int back);

        boolean padMotion(MotionEvent e);
    }

    private static Ui ui;

    public static void set(Ui u) {
        ui = u;
    }

    static boolean on() {
        return ui != null;
    }

    static Menu menu(GameActivity a, SidePanel panel) {
        if (ui == null) return null;
        // Back opens the menu (GameActivity.onBackPressed): Hokkaido's app takes back through OnBackInvokedCallback
        // (enableOnBackInvokedCallback), which need not call onBackPressed, and the menu has no edge handle
        a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, a::onBackPressed);
        return ui.create(new Screen(a, panel));
    }

    static void styleOverlay(TextView view) {
        if (ui != null) ui.styleOverlay(view);
    }

    static void stylePaused(TextView view) {
        if (ui != null) ui.stylePaused(view);
    }

    /** The running game, for the menu. */
    public static final class Screen {
        private final GameActivity act;
        private final SidePanel panel;

        Screen(GameActivity act, SidePanel panel) {
            this.act = act;
            this.panel = panel;
        }

        public Activity activity() {
            return act;
        }

        public String name() {
            String n = act.getIntent().getStringExtra("name");
            return n != null ? n : "";
        }

        /** Hokkaido's theme for this screen (HokkaidoBridge.play): the base theme and its overlays; empty when not given. */
        public int[] themes() {
            int[] t = act.getIntent().getIntArrayExtra(HokkaidoBridge.EXTRA_THEMES);
            return t != null ? t : new int[0];
        }

        /** Whether Hokkaido is in its dark mode (HokkaidoBridge.play), else the phone's. */
        public boolean night() {
            int mode = act.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return act.getIntent().getBooleanExtra(HokkaidoBridge.EXTRA_NIGHT, mode == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        }

        /** The GPU driver the game runs on: its folder's name, or null for the system's. */
        public String driver() {
            String d = GpuDriver.choice(act, String.valueOf(act.getIntent().getStringExtra("title_id")));
            return d == null || d.equals(GpuDriver.SYSTEM) ? null : d;
        }

        /** The panel's tabs (SidePanel.TAB_NAMES). */
        public String[] tabs() {
            return SidePanel.TAB_NAMES.clone();
        }

        /** Builds a tab's rows for the menu (Menu.built). */
        public void showTab(int tab) {
            panel.hokkaidoTab(tab);
        }

        /** Closes the menu: the game takes the controller again. */
        public void close() {
            panel.close();
        }

        public boolean paused() {
            return act.paused();
        }

        public void pause() {
            act.pause();
        }

        public void resume() {
            act.unpause();
        }

        /** Whether the on-screen controls show (On, or Auto without a gamepad). */
        public boolean controlsShown() {
            int m = TouchControls.mode(act);
            return m == TouchControls.MODE_ON || m == TouchControls.MODE_AUTO && !act.gamepadConnected();
        }

        /** The on-screen controls On or Off, kept for every game (the panel's On-screen controls). */
        public void showControls(boolean on) {
            int m = on ? TouchControls.MODE_ON : TouchControls.MODE_OFF;
            TouchControls.mode(act, m);
            act.touchControls().setMode(m);
        }

        /**
         * Ends the game, as the panel's Stop the game does, and goes back to Hokkaido's screen under it: the game
         * screen shares Hokkaido's task, so it finishes (backToFrontend would send that whole task to the background).
         * The process ends once the screen has gone.
         */
        public void stop() {
            Native.note("stopped by the user");
            ExitWatch.stopped();
            act.finish();
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 300);
        }
    }
}
