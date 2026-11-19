package dev.nxnative.android;

import java.util.Arrays;
import java.util.List;

/**
 * The pure part of the Upscaler row for games with game.tsv dlss_standin (Prefs.dlssGame; GameUpscaler keeps it per game, StepSlider draws the sliders,
 * dm/gfx/vk_android/vka_dlss.c and vka_present.c read the lines live). Such a game's DLSS runs on vka's stand-in, so its Upscaler row picks the stand-in (DM_VK_DLSS:
 * GSR 2, FSR 1 or bilinear, per game as dlss_mode); under GSR 2, Motion clarity (DM_VK_GSR2_CLARITY c0..c4: steadier on the left, less ghosting on the right) and Temporal
 * sharpness (DM_VK_DLSS_SHARP, 0..30 in steps of 5, default 15; under FSR 1 the same lever as "FSR sharpness", under FSR 3.1 as "Sharpness": DM_VK_FSR3_SHARP = step / 30,
 * so the default 15 is RCAS strength 0.5; still characters' outlines shimmer less than at 0.8, see vka_fsr3.c).
 *
 * The rule, for these games only:
 * <pre>
 *   Upscaler row (dlss_mode)                      DM_VK_DLSS
 *   0 GSR 2 (smooth images); unset or unknown     3
 *   1 FSR 1 (sharp, fast)                         2
 *   2 Bilinear (fastest, softest)                 1
 *   3 FSR 3.1 (sharp, steady)                     4      (vka_fsr3.c; its RCAS, DM_VK_FSR3_SHARP, from the sharpness slider)
 *
 *   stored ndsr_choice2 (Tx.arr(R.array.arr_upscaler_modes),      the present's scaling of the window to the screen, with Screen scaling unset
 *   the game's own, else the main screen's)       Screen scaling   DM_VK_FSR   DM_VK_PRESENT_NEAREST   DM_VK_PRESENT_SCALE
 *   0 Nearest neighbor                            Nearest          0           1                       (none)
 *   1 Bilinear                                    Bilinear         0           0                       (none)
 *   2 FSR 1                                       FSR 1            1           0                       (none)
 *   3 Native                                      Bilinear         0           0                       (none)    (the present has no counterpart)
 *   4 SGSR 1, also the unset default              SGSR 1           0           0                       sgsr1
 *   >= 5 or < 0 (unknown): as the default (4)     SGSR 1           0           0                       sgsr1
 * </pre>
 * The stored choice drives the present alone (with DM_VK_FSR_SHARP from the stored ndsr_sharp, as for any game); no DM_VK_NDSR line is sent for these games. Until the
 * present had an SGSR 1 pass, choice 4 (and the unset default) gave FSR 1 there (DM_VK_FSR=1, presentOf): it now gives SGSR 1, as the Upscaler's default does for the
 * other games (cheaper than FSR 1 at the present: a few watts less on a tablet-sized screen). Rows 0-3 are as before.
 *
 * Screen scaling (screen_scale, per game; GameUpscaler.screen): the present's scaling picked on its own. Unset, the table above (the row shows its Screen scaling
 * column as its default). Set, it adds DM_VK_PRESENT_SCALE, which the host takes over DM_VK_FSR and DM_VK_PRESENT_NEAREST (vka_present.c), and those two follow it
 * for a host without it:
 * <pre>
 *   screen_scale                   DM_VK_PRESENT_SCALE   DM_VK_FSR   DM_VK_PRESENT_NEAREST
 *   0 Nearest neighbor             nearest               0           1
 *   1 Bilinear                     bilinear              0           0
 *   2 Bicubic                      bicubic               0           0
 *   3 FSR 1 (+ Screen sharpness)   fsr1                  1           0      (DM_VK_FSR_SHARP from ndsr_sharp, as above)
 *   4 SGSR 1                       sgsr1                 0           0
 *   5 Device (power saving)        device                0           0      (the display hardware scales: a smaller swapchain)
 *   unset, < 0 or >= 6             as the table above
 * </pre>
 * New choices go at the end: the stored value is an index into SCREEN_NAMES. A live change of the row back to an unset default other than SGSR 1 sends
 * "DM_VK_PRESENT_SCALE=" (ctlLines), which unsets it
 */
final class DlssTune {
    private DlssTune() {}

    static final String[] CLARITY_NAMES = {"Steadiest", "Steady", "Balanced", "Clear", "Clearest"};
    static final String[] CLARITY_NOTES = {"", "", "", "", " (may flicker in combat)"};
    static final int CLARITY_DEFAULT = 2;
    static final int[] SHARPS = {0, 5, 10, 15, 20, 25, 30};
    static final int SHARP_DEFAULT = 3;   // 15

    static String clarityName(int i) {   // the words are read at each use (the language can change); CLARITY_NAMES / CLARITY_NOTES are the English the tests and logs see
        String[] n = {Words.s("game_clarity_steadiest", "Steadiest"), Words.s("game_clarity_steady", "Steady"), Words.s("game_clarity_balanced", "Balanced"),
                      Words.s("game_clarity_clear", "Clear"), Words.s("game_clarity_clearest", "Clearest")};
        return n[i] + (i == 4 ? Words.s("game_clarity_may_flicker", " (may flicker in combat)") : "");
    }
    static String[] clarityNames() { String[] n = new String[CLARITY_NAMES.length]; for (int i = 0; i < n.length; i++) n[i] = clarityName(i); return n; }
    static String sharpName(int i) { return SHARPS[i] == 0 ? Words.s("game_off", "Off") : String.valueOf(SHARPS[i]); }
    static String[] sharpNames() { String[] n = new String[SHARPS.length]; for (int i = 0; i < n.length; i++) n[i] = sharpName(i); return n; }
    static int clamp(int v, int n, int dflt) { return v >= 0 && v < n ? v : dflt; }

    /** The Upscaler row: the stand-in's modes in the order shown, and each one's DM_VK_DLSS (3 GSR2 3-pass, 2 FSR 1 with the jitter taken out, 1 bilinear, 4 FSR 3.1) */
    static final String[] MODE_NAMES = {"GSR 2 (smooth images)", "FSR 1 (sharp, fast)", "Bilinear (fastest, softest)", "FSR 3.1 (sharp, steady)"};
    static final int[] MODE_DLSS = {3, 2, 1, 4};
    static final int MODE_DEFAULT = 0, MODE_GSR2 = 0, MODE_FSR1 = 1, MODE_FSR3 = 3;
    /** Which sliders the mode shows: GSR 2 both, FSR 1 and FSR 3.1 the sharpness alone (their RCAS), bilinear none */
    static boolean showsClarity(int mode) { return clamp(mode, MODE_NAMES.length, MODE_DEFAULT) == MODE_GSR2; }
    static boolean showsSharp(int mode) { int m = clamp(mode, MODE_NAMES.length, MODE_DEFAULT); return m == MODE_GSR2 || m == MODE_FSR1 || m == MODE_FSR3; }
    static String sharpTitle(int mode) { int m = clamp(mode, MODE_NAMES.length, MODE_DEFAULT); return m == MODE_FSR1 ? Words.s("common_fsr_sharpness", "FSR sharpness") : m == MODE_FSR3 ? Words.s("game_sharpness", "Sharpness") : Words.s("game_temporal_sharpness", "Temporal sharpness"); }

    /** The present's scaling the old keys give for a stored ndsr_choice2 (FSR 1, bilinear or nearest; SGSR 1 as FSR 1): the lines without Screen scaling's own key */
    static final int PRESENT_FSR = 0, PRESENT_BILINEAR = 1, PRESENT_NEAREST = 2;
    /** Tx.arr(R.array.arr_upscaler_modes)' indices this rule reads: 0 Nearest neighbor, 1 Bilinear, 2 FSR 1, 3 Native, 4 SGSR 1 (GameUpscaler.MODE_DEFAULT) */
    static final int UPSCALER_COUNT = 5, UPSCALER_DEFAULT = 4;
    static int presentOf(int ndsrChoice) {
        switch (clamp(ndsrChoice, UPSCALER_COUNT, UPSCALER_DEFAULT)) {
            case 0: return PRESENT_NEAREST;
            case 1: case 3: return PRESENT_BILINEAR;
            default: return PRESENT_FSR;   // 2 FSR 1, 4 SGSR 1
        }
    }

    /** The Screen scaling row: its choices in the order shown (stored index), each one's DM_VK_PRESENT_SCALE */
    static final String[] SCREEN_NAMES = {   // the English the tests see; the launcher shows Tx.arr(R.array.arr_screen_scale), the same order
        "Nearest neighbor", "Bilinear", "Bicubic", "FSR 1", "SGSR 1", "Device (power saving)"};
    static final String[] SCREEN_KEYS = {"nearest", "bilinear", "bicubic", "fsr1", "sgsr1", "device"};
    static final int SCREEN_NEAREST = 0, SCREEN_BILINEAR = 1, SCREEN_BICUBIC = 2, SCREEN_FSR1 = 3, SCREEN_SGSR1 = 4, SCREEN_DEVICE = 5;
    static boolean screenValid(Integer s) { return s != null && s >= 0 && s < SCREEN_NAMES.length; }
    /** The row's value for a stored Upscaler choice when screen_scale is unset (the table above): Nearest, Bilinear (Bilinear and Native), FSR 1, or SGSR 1 (SGSR 1, the default, unknown) */
    static int screenOf(int ndsrChoice) {
        switch (clamp(ndsrChoice, UPSCALER_COUNT, UPSCALER_DEFAULT)) {
            case 0: return SCREEN_NEAREST;
            case 1: case 3: return SCREEN_BILINEAR;
            case 2: return SCREEN_FSR1;
            default: return SCREEN_SGSR1;
        }
    }
    /** The row's value: the stored screen_scale when valid, else screenOf the stored Upscaler choice */
    static int screen(Integer stored, int ndsrChoice) { return screenValid(stored) ? stored : screenOf(ndsrChoice); }
    /** Screen sharpness (ndsr_sharp: GameUpscaler.SHARPS) shows under FSR 1 */
    static boolean showsScreenSharp(int screen) { return screen == SCREEN_FSR1; }

    /**
     * The lines of such a game with Screen scaling: stored screenScale null or out of range = unset, the stored Upscaler choice's (screenOf): SGSR 1 as if picked, the others
     * the old keys alone (lines below, unchanged); set, the present's lines from it
     */
    static List<String> lines(int mode, int clarity, int sharpIndex, int ndsrChoice, float screenSharp, Integer screenScale) {
        if (!screenValid(screenScale) && screenOf(ndsrChoice) != SCREEN_SGSR1) return lines(mode, clarity, sharpIndex, ndsrChoice, screenSharp);
        int m = clamp(mode, MODE_NAMES.length, MODE_DEFAULT), s = screen(screenScale, ndsrChoice);
        List<String> l = new java.util.ArrayList<>();
        l.add("DM_VK_DLSS=" + MODE_DLSS[m]);
        l.addAll(lines(clarity, sharpIndex));
        l.add("DM_VK_FSR=" + (s == SCREEN_FSR1 ? 1 : 0));
        l.add("DM_VK_FSR_SHARP=" + screenSharp);
        l.add("DM_VK_PRESENT_NEAREST=" + (s == SCREEN_NEAREST ? 1 : 0));
        l.add("DM_VK_PRESENT_SCALE=" + SCREEN_KEYS[s]);
        return l;
    }
    /** The live control's lines (GameUpscaler.push): lines, and with no DM_VK_PRESENT_SCALE among them "DM_VK_PRESENT_SCALE=" so that a choice made earlier in this run is undone */
    static List<String> ctlLines(int mode, int clarity, int sharpIndex, int ndsrChoice, float screenSharp, Integer screenScale) {
        List<String> l = new java.util.ArrayList<>(lines(mode, clarity, sharpIndex, ndsrChoice, screenSharp, screenScale));
        if (l.stream().noneMatch(x -> x.startsWith("DM_VK_PRESENT_SCALE="))) l.add("DM_VK_PRESENT_SCALE=");
        return l;
    }

    /**
     * Every line of such a game: the stand-in's mode and sliders, then the present's scaling from the stored Upscaler choice (FSR 1 with its RCAS at screenSharp stops,
     * below 0 RCAS at strength 0: GameUpscaler.SHARPS), no DM_VK_NDSR
     */
    static List<String> lines(int mode, int clarity, int sharpIndex, int ndsrChoice, float screenSharp) {
        int m = clamp(mode, MODE_NAMES.length, MODE_DEFAULT), p = presentOf(ndsrChoice);
        List<String> l = new java.util.ArrayList<>();
        l.add("DM_VK_DLSS=" + MODE_DLSS[m]);
        l.addAll(lines(clarity, sharpIndex));
        l.add("DM_VK_FSR=" + (p == PRESENT_FSR ? 1 : 0));
        l.add("DM_VK_FSR_SHARP=" + screenSharp);
        l.add("DM_VK_PRESENT_NEAREST=" + (p == PRESENT_NEAREST ? 1 : 0));
        return l;
    }
    /** The host's lines for a clarity step and a sharpness index (FSR 3.1's RCAS strength is the step / 30: 0, 0.17, 0.33, 0.5, 0.67, 0.83, 1) */
    static List<String> lines(int clarity, int sharpIndex) {
        int sh = SHARPS[clamp(sharpIndex, SHARPS.length, SHARP_DEFAULT)];
        return Arrays.asList("DM_VK_GSR2_CLARITY=" + clamp(clarity, CLARITY_NAMES.length, CLARITY_DEFAULT), "DM_VK_DLSS_SHARP=" + sh,
                             "DM_VK_FSR3_SHARP=" + String.format(java.util.Locale.ROOT, "%.2f", sh / 30f));
    }
}
