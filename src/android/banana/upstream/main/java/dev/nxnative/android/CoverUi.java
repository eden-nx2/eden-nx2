package dev.nxnative.android;

/**
 * The one entry point for the menus that offer a cover: the game page's row (Covers.row) and Home's per-game options menu. start opens the system picker and then
 * the crop screen; Home redraws by itself when the cover was saved (Covers.activityResult). Removing a game's entry calls Covers.delete(game.orderId()).
 */
final class CoverUi {
    private CoverUi() {}

    /** "Set cover image" for a game: picker, crop, save */
    static void start(MainActivity a, Game g) { Covers.pick(a, g, () -> { }); }

    /** "Reset cover": the game's own picture again; Home redraws */
    static void reset(MainActivity a, Game g) { Covers.delete(a, g); a.refresh(); }

    static boolean has(MainActivity a, Game g) { return Covers.has(a, g); }
}
