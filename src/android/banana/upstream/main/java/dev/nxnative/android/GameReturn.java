package dev.nxnative.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Opening the app from its icon while a game runs goes back to the game, not to Home.
 *
 * Home (MainActivity) is singleTask and the task's root, so the launcher's intent brings Home itself forward and Android closes the game's screen above it. The
 * game is not stopped by that (its process and its host go on, suspended), but the player landed on Home and had to tap the game's cover to get back. Here the
 * last game started is remembered, and a plain launcher start while the run's exit record (ExitWatch, last-exit.txt) says that very game runs, in a :game process
 * that is alive, starts its screen again: the game shows its Paused screen as after any return.
 *
 * Only the launcher's own intent does this (MAIN + LAUNCHER, no extras): a shortcut, a frontend or an adb start that names what it wants is left alone.
 */
final class GameReturn {
    private static final String TAG = "nxnative", PREFS = "game_return";
    private static final String[] KEYS = {"title_id", "name", "game", "pack"};

    private GameReturn() {}

    /** A game's screen is being started with these extras (Home's Play, a frontend): kept for {@link #back} */
    static void remember(Context c, String titleId, String name, String game, String pack) {
        if (titleId == null || game == null || pack == null) return;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("title_id", titleId).putString("name", name == null ? "" : name).putString("game", game).putString("pack", pack).apply();
    }

    /** True when launch is the launcher's plain start and the game started last runs: its screen was started again, and Home has nothing more to do with this intent */
    static boolean back(Activity home, Intent launch) {
        if (launch == null || !Intent.ACTION_MAIN.equals(launch.getAction()) || !launch.hasCategory(Intent.CATEGORY_LAUNCHER)) return false;
        if (launch.getExtras() != null && !launch.getExtras().isEmpty()) return false;
        SharedPreferences p = home.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int pid = ExitNotice.runningPid(ExitWatch.read(home), p.getString("title_id", null));   // the run's own record names the game and its process
        if (pid <= 0 || !ExitWatch.alive(home, pid)) return false;
        Intent i = new Intent(home, GameActivity.class);
        for (String k : KEYS) {
            String v = p.getString(k, null);
            if (v == null) return false;
            i.putExtra(k, v);
        }
        try {
            home.startActivity(i);
            Log.i(TAG, "opened from the launcher while " + p.getString("name", "a game") + " runs: back to the game");
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "could not go back to the running game: " + e);
            return false;
        }
    }
}
