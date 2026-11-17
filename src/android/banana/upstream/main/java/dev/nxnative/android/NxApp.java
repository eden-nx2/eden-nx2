package dev.nxnative.android;

import android.app.Application;

/** Runs first in every process of the app (the launcher, :game, :pack, :readiness, :selftest): a .nup picked in place opens there as any .nup (PickedNup); the shader layer's fallback folder is known (ShaderLibs.appFiles) */
public final class NxApp extends Application {
    /** The Application, for Tx (text from resources where no Context is at hand) */
    static Application app;

    @Override public void onCreate() {
        super.onCreate();
        app = this;
        Words.source = new Words.Source() {   // the pure classes' text (Words) reads the same resources as Tx
            @Override public String string(String key) {
                int id = getResources().getIdentifier(key, "string", getPackageName());
                return id == 0 ? null : getResources().getString(id);
            }
            @Override public String plural(String key, int count) {
                int id = getResources().getIdentifier(key, "plurals", getPackageName());
                return id == 0 ? null : getResources().getQuantityText(id, count).toString();
            }
        };
        PickedNup.install(this);
        ShaderLibs.appFiles = getExternalFilesDir(null);   // where the games' temporary folders are: the shader layer's fallback (ShaderLibs.layerWritable)
        Readiness.minSdk = BuildConfig.NX_MIN_SDK;
        UpdateCheck.android12 = BuildConfig.NX_MIN_SDK < 33;
        applyTheme(this);
    }

    /** The theme the user picked (Prefs.theme): the app's night mode, for every screen of the launcher (minSdk 33: UiModeManager.setApplicationNightMode) */
    static void applyTheme(android.content.Context c) {
        android.app.UiModeManager m = c.getSystemService(android.app.UiModeManager.class);
        if (m == null) return;
        int t = Prefs.theme(c);
        m.setApplicationNightMode(t == 1 ? android.app.UiModeManager.MODE_NIGHT_NO : t == 2 ? android.app.UiModeManager.MODE_NIGHT_YES : android.app.UiModeManager.MODE_NIGHT_AUTO);
    }
}
