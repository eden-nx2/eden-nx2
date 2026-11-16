package dev.nxnative.android;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The launcher's words for a game whose pack lacks this APK's shader library and has none it can play on (android/SHADERS.md "Launcher states for shaders"): why the
 * shader layer does not let it play now ({@link #layerOff}) and the "Shaders needed" text of its card's Why? badge and of its page's row ({@link #neededText}), each
 * with the way forward the player has (turn the switch on, remove the line, Prepare shaders, Import a library). No Android classes: ShaderStateJvmTest
 * (dm/tools/tests/test_android_shader_state.py) runs it on the Mac.
 */
final class ShaderState {
    private ShaderState() {}

    /** Why the shader layer does not build a game's shaders while it plays: it does (NONE), not a Switch 2 title, the player's switch, a line of the game's own, or no folder to build in */
    enum Off { NONE, NOT_SWITCH2, SWITCH, ENV, UNWRITABLE }

    private static final Pattern ENV_OFF = Pattern.compile("(^|[\\s;,])DM_VK_LAYER\\s*=\\s*0\\s*($|[\\s;,])");

    /**
     * titleId: the game's; switchOn: Game settings › Shaders while playing (Prefs.layerBuild); ownEnv: the game's own lines (Prefs.gameEnv, without what layerEnv adds);
     * writable: a folder the layer can be built in (ShaderLibs.layerWritable). The order is the order the player meets them: the title first (nothing to change), then the switch,
     * the line, the folder
     */
    static Off layerOff(String titleId, boolean switchOn, String ownEnv, boolean writable) {
        if (titleId == null || !titleId.startsWith("0400")) return Off.NOT_SWITCH2;
        if (!switchOn) return Off.SWITCH;
        if (ownEnv != null && ENV_OFF.matcher(ownEnv).find()) return Off.ENV;
        if (!writable) return Off.UNWRITABLE;
        return Off.NONE;
    }

    /** The switch's place on the game's page */
    static String SWITCH_PATH() { return Words.s("prepare_the_game_page_performance_advanced_2", "the game page › Performance › Advanced › Shaders while playing"); }
    static String ENV_PATH() { return Words.s("prepare_the_game_page_performance_advanced", "the game page › Performance › Advanced › Extra environment"); }

    /**
     * The "Shaders needed" text (the card's Why? and the page's row): what is missing, why the game cannot build it while it plays (off), and what the player can do.
     * otherInputs: the pack has this version's library made from other shader tables (the host would load it: the layer cannot fill it); dump: Prepare can start from the
     * game's file (Pack.isDump); delta: shaders a delta update translates (-1: none known, Pack.deltaShaders); total: the profile's shader count; minutes: Prepare's time
     * for the whole set ("about 9-40 minutes", ShaderLibs.minutesText)
     */
    static String neededText(Off off, boolean otherInputs, boolean dump, int delta, int total, String minutes) {
        StringBuilder s = new StringBuilder(otherInputs ? Words.s("prepare_the_pack_s_shaders_were", "The pack's shaders were made from other shader tables than this app version's, so they need making again.")
                : Words.s("prepare_the_pack_has_no_shaders", "The pack has no shaders for this app version."));
        if (!otherInputs) switch (off) {
            case SWITCH: s.append(" Shaders while playing is off for this game, so it waits for them. Turn it on (" + SWITCH_PATH() + Words.s("prepare_to_play_now_and_build", ") to play now and build them as you play.")); break;
            case ENV: s.append(" The line DM_VK_LAYER=0 in " + ENV_PATH() + Words.s("prepare_turns_off_building_them_while", " turns off building them while you play. Remove it to play now and build them as you play.")); break;
            case UNWRITABLE: s.append(" The game cannot build them while you play: neither the pack folder nor the app's temporary folder can be written. "
                    + Words.s("prepare_free_some_space_or_pick", "Free some space, or pick a pack folder the app can write (the game page › Storage › Folders).")); break;
            default: break;
        }
        if (dump) {
            if (delta == 0) s.append(Words.s("prepare_prepare_shaders_updates_them_none", " Prepare shaders updates them; none needs translating again (the game's code is kept)."));
            else if (delta > 0) s.append(String.format(Locale.US, " Prepare shaders translates the %,d new ones (the other %,d are reused; the game's code is kept).", delta, Math.max(0, total - delta)));
            else s.append(Words.s("prepare_prepare_shaders_makes_them_from", " Prepare shaders makes them from your dump (") + minutes + Words.s("prepare_the_game_s_code_is", "; the game's code is kept)."));
        } else {
            s.append(Words.s("prepare_this_game_s_file_is_2", " This game's file is not one the phone can prepare: build the library on a computer (scripts/pack_android.py) and add it with Import a library on the game's page, ")
                    + Words.s("prepare_or_import_a_whole_pack", "or import a whole pack (the game page › Storage › Import pack…)."));
        }
        return s.toString();
    }

    /** The on-demand row's text on the game's page (GameSettings.shadersOnDemandRow): what happens, and the way to the whole set for this game's file */
    static String onDemandText(boolean dump) {
        return Words.s("prepare_the_game_plays_now_it", "The game plays now. It builds the shaders it needs while you play (Settings › Storage lists them as the shader cache); the first start can be black or half-empty for several minutes and objects pop in for a while. ")
                + (dump ? Words.s("prepare_build_all_makes_the_whole", "Build all makes the whole set now, which takes a while: keep the phone charging.")
                        : Words.s("prepare_this_game_s_file_is", "This game's file is not one the phone can prepare, so the whole set comes from a computer: build it there (scripts/pack_android.py) and add it with Import a library."));
    }

    /** One sentence after a pack was imported, picked or added that has no shaders for this app version and plays on the layer (PackUi, Folders, MainActivity) */
    static String onDemandNote(boolean dump) {
        return Words.s("prepare_it_has_no_shaders_for", "It has no shaders for this app version: the game plays and builds them as you play. ")
                + (dump ? Words.s("prepare_build_all_on_the_game", "Build all on the game's page makes the whole set now.") : Words.s("prepare_import_a_library_on_the", "Import a library on the game's page takes a library built on a computer."));
    }
}
