package dev.nxnative.android;

import java.util.Locale;

/**
 * The words of the shader layer's state, in the game: the label at the top middle (LayerWatch) and the Shaders line of the in-game panel (SidePanel › General).
 * No Android classes: the JVM test (LayerPendingJvmTest, dm/tools/tests/test_android_layer.py) runs it on the Mac.
 */
final class LayerStatus {
    private LayerStatus() {}

    /** The layer folder is full (LayerWatch passes this as the reason; LayerGuard.pauseReason gives "battery low") */
    static final String STORAGE = "storage";

    /**
     * The label over the game, null when nothing is pending (it clears when the requests are settled). pending: requests not settled; paused: why the build waits
     * (LayerGuard.pace or {@link #STORAGE}), null when it may run; started: a build was asked for since the game started; busy: the packer is building now; retryIn: seconds to the
     * next try while the watcher waits between runs that settled nothing (0: no wait to name); note: what the build runs under (LayerGuard.WARM_NOTE / HOT_NOTE), null when nothing. The
     * count is always the requests still waiting, never 0. "Building shaders" is said only while the packer builds (or in the few seconds between two runs that did settle some);
     * "Starting shader build" says that the first run has not begun; a watcher that waits says so and when it tries again
     */
    static String label(int pending, String paused, boolean started, boolean busy, int retryIn, String note) {
        if (pending <= 0) return null;
        if (paused != null) return LayerGuard.pausedText(paused) + " \u00b7 " + pending + " waiting";
        String n = note == null ? "" : " \u00b7 " + note;
        if (!busy && retryIn > 0) return Words.s("game_shader_build_waiting", "Shader build waiting (") + pending + Words.s("game_u00b7_next_try_in", ") \u00b7 next try in ") + retryIn + " s" + n;
        return (started ? LayerPending.text(pending) : Words.s("game_starting_shader_build", "Starting shader build (") + pending + ")") + n;
    }

    /** The label's three choices (Prefs.layerLabel): hidden, the count alone, the full words */
    static final int LABEL_OFF = 0, LABEL_NUMBER = 1, LABEL_FULL = 2;

    /**
     * What the label shows under the player's choice (mode: LABEL_*), null for nothing. The words of {@link #label} are the full text; the count alone is just N. A build that waits
     * for the phone (paused: hot, battery low, storage full) is said in every choice, Off included: the picture stays blank while it waits, and nothing else says why. The other
     * arguments are those of {@link #label}
     */
    static String shown(int mode, int pending, String paused, boolean started, boolean busy, int retryIn, String note) {
        String full = label(pending, paused, started, busy, retryIn, note);
        if (full == null || paused != null || mode == LABEL_FULL) return full;
        return mode == LABEL_NUMBER ? Integer.toString(pending) : null;
    }

    /**
     * Whether the watcher asks the packer for a build now: requests wait (n > 0), it has not given up, the layer is not full, nothing makes it wait (paused: only a low battery, never
     * heat), the packer is not on a run already, and the wait since the last ask is over. The label is not an argument: what is drawn (Off, the count, the words) never decides
     * whether shaders are built
     */
    static boolean buildDue(int n, boolean gaveUp, boolean full, String paused, boolean busy, long now, long lastStart, long wait) {
        return n > 0 && !gaveUp && !full && paused == null && !busy && now - lastStart >= wait;
    }

    /** The label of a build that runs at full pace */
    static String label(int pending, String paused, boolean started) { return label(pending, paused, started, started, 0, null); }

    /**
     * The panel's one line. on: the layer runs in this game (Shaders while playing was on at its start, a Switch 2 title); hasLibrary: the game started with a prepared library;
     * built: shaders built while playing (built.tsv's ok rows); pending: requests not settled; paused: as {@link #label}; gaveUp: the watcher stopped asking the packer
     */
    static String panelLine(boolean on, boolean hasLibrary, int built, int pending, String paused, boolean gaveUp) {
        if (!on) return hasLibrary ? Words.s("game_shaders_from_the_prepared_library", "Shaders: from the prepared library only (building while playing is off)") : Words.s("game_shaders_not_built_while_playing", "Shaders: not built while playing (off for this run)");
        if (pending <= 0) {
            if (hasLibrary) return Words.s("game_all_shaders_ready", "All shaders ready");
            return String.format(Locale.US, "Shaders: built while playing · %,d built · none waiting", built);
        }
        StringBuilder s = new StringBuilder(Words.s("game_shaders_built_while_playing", "Shaders: built while playing"));
        if (!hasLibrary || built > 0) s.append(String.format(Locale.US, " · %,d built", built));
        s.append(String.format(Locale.US, " · %,d waiting", pending));
        if (gaveUp) s.append(Words.s("game_stopped_the_shader_builder_could", " · stopped (the shader builder could not run)"));
        else if (STORAGE.equals(paused)) s.append(" · paused (storage: free space on the phone, or Import a library on the game's page)");
        else if (paused != null) s.append(" · paused (").append(paused).append(")");
        return s.toString();
    }

    /**
     * The game page's words for a layer past its quota (LayerGuard.full: used, quota bytes), with the ways forward: the game builds no more shaders until there is room.
     * library: the game has a prepared library to play meanwhile; dump: the phone can prepare the game (Build all)
     */
    static String storageText(long used, long quota, boolean library, boolean dump) {
        return String.format(Locale.US, "Shader build paused: the phone is low on storage. The shaders built while playing take %,d MB, and %,d MB is all the phone can spare now. ", used >> 20, quota >> 20)
                + (library ? Words.s("game_the_game_plays_with_its", "The game plays with its shader library meanwhile. ") : Words.s("game_until_there_is_room_what", "Until there is room, what the game asks for next is not built and may not show. "))
                + Words.s("game_free_space_on_the_phone", "Free space on the phone, or add the whole set with Import a library (one built on a computer)") + (dump ? Words.s("game_or_build_all", " or Build all") : "")
                + Words.s("game_storage_u203a_shader_cache_built", ". Storage \u203a Shader cache built while playing frees this cache; it is then built again as you play.");
    }

    /** What the switch says under it: the host reads the setting once, when the game starts */
    static String SWITCH_NOTE() { return Words.s("game_applies_at_the_next_start_3", "Applies at the next start."); }
}
