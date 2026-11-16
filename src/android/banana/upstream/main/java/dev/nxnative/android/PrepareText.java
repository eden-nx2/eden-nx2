package dev.nxnative.android;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The words of Prepare (the on-device job's card and Home's game cards) for the player: what happened and what to do, in one or two short sentences, with the
 * technical part (file paths, a tool's last [RED] line, exit codes, build IDs) kept apart for the card's Details, which the player can open and copy. Also what a
 * failure offers: Retry when running the step again can help, Try anyway when the job can go on with a check relaxed (with an honest warning), nothing when this app
 * version does not include the game, and an update when this app build itself is broken (its files do not match each other). No Android classes: LauncherJvmTest
 * runs it on the Mac.
 */
final class PrepareText {
    private PrepareText() {}

    /**
     * What a failed job's card offers: run the step again, go on with the check relaxed (Job "try_anyway"), nothing (this app cannot do it), or another app build
     * (this build is broken: no Retry; the release app offers Check for updates)
     */
    static final String FIX_RETRY = "retry", FIX_ANYWAY = "anyway", FIX_NONE = "none", FIX_UPDATE = "update";

    /**
     * The start of vk_android_build.py check_translator's refusal after "[RED] ": the APK's translator library is not the one its own tools expect (a library from
     * another source tree, an embedded source that is not the lock's, a library without --tree-sha). Nothing on the phone can mend it; Gradle's nxPackXlateCheck
     * stops such a build (android/ONDEVICE.md, In the APK). test_android_preparetext.py checks the tool still prints this text
     */
    static final String XLATE_MISMATCH = "translator does not match the lock file: ";   // the tool's own words (vk_android_build.py check_translator): matched, never translated

    /** The card's text for an APK whose translator does not match its tools (XLATE_MISMATCH) */
    static String XLATE_MISMATCH_TEXT() { return Words.s("prepare_this_app_build_s_shader_transl", "This app build's shader translator does not match its own tools, so retrying will not help. Install the latest release ")
            + Words.s("prepare_of_the_app_or_report", "of the app, or report this with the log from Details."); }

    /** A failure: text for the card (short, plain), detail for Details (may be long), fix: FIX_* */
    static final class Failure {
        final String text, detail, fix;

        Failure(String text, String detail, String fix) { this.text = text; this.detail = detail == null ? "" : detail; this.fix = fix; }
    }

    /** The warning Try anyway shows before it relaxes a check: what may go wrong, plainly */
    static String ANYWAY_WARNING() { return Words.s("prepare_this_goes_on_without_the_check", "This goes on without the check that stopped it. The game may not start, may crash, or may draw parts wrongly or not at all. ")
            + Words.s("prepare_it_has_not_been_tested", "It has not been tested this way. Your dump and saves are not changed."); }

    /** The warning of a build the profile does not list (Try anyway / Play anyway on it): the app has the shaders and fixes of the listed builds only, so it is likely, not just possible, that parts are missing (Forgejo #258, #281) */
    static String UNSUPPORTED_WARNING() { return Words.s("prepare_this_version_is_not_supported", "This version is not supported: the app has the shaders and fixes of the supported version only, so parts of the picture are likely to be missing, ")
            + Words.s("prepare_and_the_game_may_not", "and the game may not start or may crash. Bug reports for it can't be fixed. Your dump and saves are not changed."); }

    static String lower(String s) { return s.isEmpty() ? s : s.substring(0, 1).toLowerCase(Locale.ROOT) + s.substring(1); }

    static String capital(String s) { return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1); }

    /**
     * A tool step (step: Job.STEP_TEXT's words) that ended with a non-zero exit code; lastRed: its log's last [RED] line (or null), log: the step's log file. The
     * kinds the tools report most (memory, storage, shaders that did not translate, a dump whose shaders are not the profile's) get a sentence of their own
     */
    static Failure toolStopped(String step, int code, String lastRed, String log) {
        String red = lastRed == null ? "" : lastRed.replaceFirst("^\\[RED\\]\\s*", "").trim();
        String detail = step + ": exit code " + code + (red.isEmpty() ? "" : "\n" + red) + (log == null ? "" : "\nLog: " + log);
        String what = step + " stopped.";
        if (red.startsWith(XLATE_MISMATCH))
            return new Failure(what + " " + XLATE_MISMATCH_TEXT(), detail, FIX_UPDATE);
        if (red.contains("MemoryError") || red.contains("out of memory") || code == 137)
            return new Failure(what + Words.s("prepare_the_phone_ran_out_of", " The phone ran out of memory. Close other apps, then tap Retry."), detail, FIX_RETRY);
        if (red.contains("No space left") || red.contains("ENOSPC"))
            return new Failure(what + Words.s("prepare_the_storage_is_full_free", " The storage is full. Free some space, then tap Retry."), detail, FIX_RETRY);
        if (red.contains("did not build: no library written"))
            return new Failure(what + Words.s("prepare_some_shaders_could_not_be", " Some shaders could not be translated, so no shader library was made. Try anyway makes it without them (those parts may not show)."), detail, FIX_ANYWAY);
        if (red.contains("are not in the package"))
            return new Failure(what + Words.s("prepare_your_dump_s_shaders_are_2", " Your dump's shaders are not the ones this app expects for this game version. If you dumped the game again, tap Retry."), detail, FIX_RETRY);
        return new Failure(what + Words.s("prepare_tap_retry_if_it_stops", " Tap Retry. If it stops again, Details has the log."), detail, FIX_RETRY);
    }

    /**
     * A step's log read for the reason of a stop: the last [RED] line, or a Python traceback's last line ("KeyError: 'x'") when it comes later. The packer's runner
     * (android/ondevice/dm_pack.c) writes an uncaught exception as a [RED] line of its own; the traceback is read too, so a tool run without it (or whose [RED] line
     * could not be written) still gives its card a reason. Lines as PackService's Tail hands them over: trimmed, one at a time
     */
    static final class StopReason {
        private static final Pattern EXCEPTION = Pattern.compile("^(?:[A-Za-z_][A-Za-z0-9_]*\\.)*[A-Za-z_][A-Za-z0-9_]*(?:Error|Exception|Exit|Interrupt)(?::\\s.*)?$");
        private boolean inTrace;
        volatile String reason;   // read by the step once its Tail thread is done

        /** line: one trimmed log line; true when it is a [RED] line */
        boolean line(String line) {
            if (line.startsWith("[RED] ")) { reason = line; inTrace = false; return true; }
            if (line.startsWith("Traceback (most recent call last):")) inTrace = true;
            else if (inTrace && EXCEPTION.matcher(line).matches()) { reason = line; inTrace = false; }   // frames and code lines before it never match: "File …", "raise …"
            return false;
        }
    }

    /** The job itself failed (an exception in the service): plain words, the exception in Details */
    static Failure crashed(String step, String exception) {
        return new Failure(step + Words.s("prepare_stopped_because_of_an_error", " stopped because of an error in the app. Tap Retry. If it stops again, Details has the error."), exception, FIX_RETRY);
    }

    /** A step that Android ended several times (most likely for memory) */
    static Failure killed(String step, int times, String gb) {
        return new Failure(step + Words.s("prepare_was_stopped_by_android", " was stopped by Android ") + times + Words.s("prepare_times_most_likely_for_lack", " times, most likely for lack of memory (it needs up to ") + gb + Words.s("prepare_gb_close_other_apps_then", " GB). Close other apps, then tap Retry."),
                "", FIX_RETRY);
    }

    /** The split's shaders differ from the profile's list: a dump of another version, modified or incomplete; Try anyway builds what the profile's list finds in it */
    static Failure shadersDiffer(String name, String version, String detail) {
        return new Failure(Words.s("prepare_your_dump_s_shaders_are", "Your dump's shaders are not the ones this app knows for ") + name + " (" + version + Words.s("prepare_the_dump_may_be_incomplete", "). The dump may be incomplete or modified: dump the game again ")
                + Words.s("prepare_and_tap_retry_or_try", "and tap Retry, or Try anyway."), detail, FIX_ANYWAY);
    }

    /** A job for a game this app version does not include (Profile.missing): nothing to retry */
    static Failure notIncluded(String sentence) { return new Failure(sentence, "", FIX_NONE); }

    /**
     * The card's main text for a failed job. A job of this version keeps its short text in "error" (structured) and the rest in "error_detail"; an older job kept
     * everything in "error": then the card says only where it stopped, and the old text goes to Details (failedDetail)
     */
    static String failedText(String step, String error, boolean structured) {
        if (structured && !error.isEmpty()) return error;
        return Words.s("prepare_preparing_stopped_while", "Preparing stopped while ") + lower(step) + Words.s("prepare_tap_retry_details_says_why", ". Tap Retry. Details says why.");
    }

    static String failedDetail(String error, String detail, boolean structured) { return structured ? detail : error; }

    /**
     * The line under the progress bar: "37 %", then the shaders when the job knows how many there are ("21,548 shaders left" during the step, "1,234 of 22,852
     * shaders" otherwise), the time left and the unchanged ones a shader update keeps. Never "0 of 0": without a total the shaders are left out
     */
    static String progressLine(long percent, boolean during, long done, long total, String eta, long kept) {
        StringBuilder s = new StringBuilder(String.format(Locale.getDefault(), "%d %%", percent));
        if (total > 0) {
            if (during) s.append(String.format(Locale.getDefault(), "  ·  %,d shaders left", Math.max(0, total - done)));
            else if (done > 0) s.append(String.format(Locale.getDefault(), "  ·  %,d of %,d shaders", done, total));
            else s.append(String.format(Locale.getDefault(), "  ·  %,d shaders to make", total));
        } else if (kept > 0) {
            s.append(Words.s("prepare_shaders_up_to_date_nothing", "  ·  shaders up to date: nothing to translate"));
        }
        if (during && eta != null && !eta.isEmpty()) s.append("  ·  ").append(eta);
        if (kept > 0 && total > 0) s.append(String.format(Locale.getDefault(), "  ·  the other %,d are reused", kept));
        return s.toString();
    }

    /**
     * A step and what its tool says it is doing now (a "stage: <text>" line), with a count when there is one: "Finding the shaders: reading the game's program",
     * "Rewriting the game's code: rewriting main (1 of 3)". The tools write the text in lower case after the colon
     */
    static String withNote(String step, String note, String count) {
        return step + (note == null || note.isEmpty() ? "" : ": " + note) + (count == null || count.isEmpty() ? "" : " · " + count);
    }

    /**
     * " · still working, last update 3 min ago" after a minute or more without a line from the running step's tool, else "": a phase that prints nothing for long
     * (a large file read, a storage flush) shows that the app has not stopped watching it, and how long it has been
     */
    static String quietText(long seconds) {
        if (seconds < 60) return "";
        long m = seconds / 60;
        return m < 60 ? Words.s("prepare_still_working_last_update", " · still working, last update ") + m + " min ago"
                : String.format(Locale.getDefault(), " · still working, last update %d h %02d min ago", m / 60, m % 60);
    }

    /**
     * The shaders step while a delta update writes out the shaders it keeps from the older library (vk_android_build.py's "keep" stage, before it translates the
     * others): "Keeping the shaders already translated: 12,000 of 42,800"
     */
    static String keepText(long done, long total) {
        return Words.s("prepare_keeping_the_shaders_already_transl", "Keeping the shaders already translated") + (total > 0 ? String.format(Locale.getDefault(), ": %,d of %,d", Math.min(done, total), total) : "");
    }

    /**
     * A step and its count from the tool's progress lines (Job.stepText; not the shaders step's, which counts shaders). The split counts the dump's files it
     * reads, not shaders (shader_split.py's "progress: <files done>/<files>"), so its count says so: "Finding the shaders: 1,203 of 1,890 files". part: the
     * tool's "<n>%" inside a large file, added as "(40 % of a large file)"
     */
    /** A step's count alone, for after its stage text (withNote): "203 of 1,442 files", "40 % of a large file"; "" while it has none */
    static String countText(boolean files, long done, long total, String part) {
        String c = total > 0 ? String.format(Locale.getDefault(), files ? "%,d of %,d " + (total == 1 ? "file" : "files") : "%,d / %,d", done, total) : "";
        String p = part != null && part.matches("\\d+%") ? part.replace("%", " %") + " of a large file" : "";
        return c.isEmpty() ? p : p.isEmpty() ? c : c + " (" + p + ")";
    }

    static String stepCountText(String step, boolean files, long done, long total, String part) {
        String count = files ? (total > 0 ? String.format(Locale.getDefault(), ": %,d of %,d %s", done, total, total == 1 ? "file" : "files") : "")
                : total > 1 ? String.format(Locale.getDefault(), " %,d / %,d", done, total) : total == 1 || done == 0 ? "" : String.format(Locale.getDefault(), " %,d", done);
        return step + count + (part != null && part.matches("\\d+%") ? " (" + part.replace("%", " %") + " of a large file)" : "");
    }

    /**
     * Why a game card needs preparing, in a few words for the card (the full reason goes to its Details): no pack yet, a pack that cannot be read, a pack whose code
     * was left unfinished, or a Prepare that stopped part way (a job beside the dump that is not done)
     */
    static String needsPreparing(boolean packThere, boolean unfinished, boolean jobStopped) {
        if (jobStopped) return Words.s("prepare_preparing_was_not_finished_tap", "Preparing was not finished. Tap Continue to go on where it stopped.");
        if (unfinished) return Words.s("prepare_the_pack_s_game_code", "The pack's game code was left unfinished. Prepare finishes it (a few minutes; shaders are kept).");
        if (packThere) return Words.s("prepare_the_pack_cannot_be_read", "The pack cannot be read. Prepare makes it again.");
        return Words.s("prepare_no_pack_yet_prepare_makes", "No pack yet. Prepare makes one from your dump.");
    }

    /** vk_android_build.py open_packs under Try anyway (DMSH_VK_REQUIRE_ALL=0): "[RED] <n> of the <m> shaders to build are not in the package …" */
    private static final Pattern MISSING = Pattern.compile("^\\[RED\\] (\\d+) of the (\\d+) shaders to build are not in the package .*library is written without them");

    /** {n, m} from the shaders step's line naming the shaders this game version lacks (Try anyway), else null */
    static long[] missingShaders(String line) {
        Matcher m = MISSING.matcher(line);
        return m.find() ? new long[] {Long.parseLong(m.group(1)), Long.parseLong(m.group(2))} : null;
    }

    /** One sentence for the player after a Try anyway whose game version lacks some of the profile's shaders; supported: Profile.supportedShort */
    static String missingShadersText(long n, long of, String supported) {
        return String.format(Locale.US, "%,d of its %,d shaders are not in this game version, so some things may not draw. All of them are in %s.", n, of, supported);
    }

    // ---- Prepare without shaders (a game the shader layer covers, ShaderLibs.layerCovers; android/SHADERS.md "Prepare without shaders") ----

    /** The done line of a code-only job (Job.codeOnly) */
    static String READY_CODE_ONLY() { return Words.s("prepare_ready_to_play_it_builds", "Ready to play. It builds shaders while you play."); }

    /** A whole Prepare of a game the layer covers that stopped in its shader translation (Ondevice: the resume question, the job's Manage): finish with the code only */
    static String SKIP_SHADERS() { return Words.s("prepare_finish_without_shaders", "Finish without shaders"); }
    static String SKIP_SHADERS_TEXT() { return Words.s("prepare_or_finish_with_the_game", "Or finish with the game's code only: the game then plays and builds its shaders while you play; Build all on its page makes the whole set later."); }

    /** The checkbox of Prepare's dialog: the whole shader set in the same job */
    static String ALSO_SHADERS() { return Words.s("prepare_also_build_all_shaders_now", "Also build all shaders now"); }

    /** Prepare's text for a game the layer covers: the code only, then the game plays; the shaders are the player's later, optional step */
    static String codeOnlyText(String timeText) {
        return Words.s("prepare_the_app_rewrites_the_game", "The app rewrites the game's code (a few minutes), then the game plays. It builds shaders while you play, so the first minutes in a new place may stutter.\n\n")
                + Words.s("prepare_the_whole_set_of_shaders", "The whole set of shaders is optional: tick \"") + ALSO_SHADERS() + Words.s("prepare_to_translate_it_in_the", "\" to translate it in the same job (") + timeText + Words.s("prepare_best_on_the_charger_or", ", best on the charger), or do it later with Build all on the game's page.");
    }

    /** The one-time explanation at the first start of a game with no shader library (MainActivity.launch, Prefs.layerExplained) */
    static String LAYER_FIRST_RUN() { return Words.s("prepare_shaders_build_as_you_play_the", "Shaders build as you play. The first start can show a black or half-empty picture for several minutes, ")
            + Words.s("prepare_longest_in_a_big_game", "longest in a big game (it starts with the title and intro, then each new place): the phone is making the first shaders and gets warm, so keep it charging. ")
            + Words.s("prepare_a_label_at_the_top", "A label at the top shows the progress, and later starts are much faster because the shaders are kept. ")
            + Words.s("prepare_build_all_on_the_game_2", "Build all on the game's page makes the whole set now, and Import a library there takes one built on a computer."); }

    /** The game page's shaders row for a game that builds its shaders as it plays (GameSettings): its title, with the count built so far (LayerPending.built) */
    static String layerRowTitle(int built) { return String.format(Locale.US, "Shaders: built while playing (%,d built)", built); }

    /** The game page's row title for a game on an older library that the layer fills (Pack.shaderLayerFills) */
    static String OLDER_LAYER_TITLE() { return Words.s("prepare_shaders_older_built_while_playing", "Shaders: older, built while playing"); }

    /**
     * Its words: the game plays now and what the older library lacks is built while it runs. same: the library is of the profile's own inputs (its shaders are as this version
     * makes them); otherwise they may differ, and only Update shaders makes them all again. dump: Prepare can run on the phone
     */
    static String olderLayerText(boolean same, boolean dump) {
        return Words.s("prepare_the_game_plays_now_on", "The game plays now on its older shaders; those it needs and the library lacks are built while you play. ")
                + (same ? "" : Words.s("prepare_the_ones_it_has_stay", "The ones it has stay as they were made; Update shaders makes them all again with this version. "))
                + (dump ? Words.s("prepare_update_shaders_builds_the_whole", "Update shaders builds the whole set now, which takes a while: keep the phone charging. Later: the game page \u203a Storage \u203a Prepare again.")
                        : Words.s("prepare_the_whole_set_can_come", "The whole set can come from a computer with Import a library."));
    }

    /** Its Build all button; minutes: ShaderLibs.minutes of the game's shader count */
    static String buildAllLabel(int[] minutes) { return String.format(Locale.US, "Build all (about %d-%d min)", minutes[0], minutes[1]); }

    /** The game page's row for a game the phone cannot prepare that plays on an older shader library (GameSettings.olderShadersRow) */
    static String OLDER_NO_DUMP() { return Words.s("prepare_the_game_plays_with_the_shader", "The game plays with the shaders it has, of an older version. This game's file is not one the phone can prepare, so the new ones come from a computer: ")
            + Words.s("prepare_build_them_there_scripts_pack", "build them there (scripts/pack_android.py) and add the library with Import a library."); }

    /** The game card's state for that game (MainActivity.check): it plays; nothing is updated on the phone */
    static String READY_OLDER() { return Words.s("prepare_ready_u00b7_older_shaders", "Ready \u00b7 older shaders"); }
    /** The card's state while a pack's shaders are checked in the background (Ondevice.sampledKeep), and what the page says once they are found current */
    static String CHECKING_SHADERS() { return Words.s("prepare_ready_u00b7_checking_shaders_u2026", "Ready \u00b7 checking shaders\u2026"); }
    static String SHADERS_UP_TO_DATE() { return Words.s("prepare_shaders_up_to_date", "Shaders up to date"); }

    /** The dialog of the optional shaders step (Prepare shaders for a pack that has code only); minutes: ShaderLibs.minutesText */
    static String optionalShadersText(String minutes) {
        return Words.s("prepare_the_game_plays_now_and", "The game plays now and builds shaders while you play; this builds the whole set now (") + minutes + Words.s("prepare_in_the_background_best_on", ", in the background, best on the charger). You can pause any time.");
    }
}
