package dev.nxnative.android;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Preparing a game on the phone. "+ Add game" (MainActivity) on a dump without a usable pack calls {@link #prepare}: the dump is checked (DumpCheck), a pack
 * beside it is checked against it (PackCheck: game build, shader library, rewriter) and what does not fit is redone, else the whole job runs; PackService runs it,
 * and this card shows it on the main screen while there is one: its state (needs preparing, preparing N of 22,852 shaders, paused and why, stopped with an error
 * and what to do), a progress bar, Pause / Resume / Retry, and ⋯ Manage (forget, delete) away from them. When the job is done the game goes into the game list
 * with its pack and the card goes away: Play is on the game's card.
 * <p>
 * The folder is picked with the Storage Access Framework's tree picker and turned into its /storage path (PathUtil); everything after that is read by path with All
 * files access, as the host does. The SAF picker is the familiar system UI and needs no path typed; reading through the SAF (DocumentFile / ContentResolver) instead
 * would be one binder call per file for a RomFS of 158,000 files, and the tools (CPython, the translator) need POSIX paths in any case.
 * <p>
 * Progress comes from the service over a bound Messenger while it runs, otherwise from job.json (read every second while the screen shows).
 */
final class Ondevice {
    static final int PICK_DUMP = 100, ASK_NOTIFY = 101;
    private static Panel panel;

    private Ondevice() {}

    /** The job's card is built for this Home but never added to it: it shows only in the job's sheet (sheet), opened by the game's tile or by itself when the job needs a tap (needsTap) */
    static void attach(MainActivity a) {
        if (panel != null) panel.detach();
        panel = new Panel(a);
    }

    /** Prepare the dump (MainActivity's Add game, a game card's Prepare again): checked off the main thread, then asked before anything is written */
    static void prepare(MainActivity a, File dump, boolean again) { if (panel != null && panel.a == a) panel.picked(dump, false, null, again); }

    /** Shaders a delta update of this pack translates (the profile's manifest rows for the pack's older libraries, ShaderLibs.toTranslate), -1 when the manifest has none of them */
    static int deltaShaders(File packDir, String titleId) { return Pack.deltaShaders(packDir, titleId); }

    /** What making a pack's shaders does and takes, for Prepare's dialogs: an update of the changed ones when the manifest knows the pack's older library, else all of them */
    static String shadersText(File packDir, String titleId) {
        int n = deltaShaders(packDir, titleId);
        Profile p = Profile.of(titleId);
        if (n == 0) return Tx.s(R.string.prepare_shaders_up_to_date_nothing_2);
        if (n > 0 && p != null) return String.format(Locale.getDefault(), "Translating %,d new shaders (the other %,d are reused; %s, in the background).",
                n, p.shaderTotal - n, ShaderLibs.minutesText(n));
        return Tx.s(R.string.prepare_translating_shaders_from_your_dump) + (p != null ? ShaderLibs.minutesText(p.shaderTotal) : Tx.s(R.string.prepare_about_20_35_minutes)) + Tx.s(R.string.prepare_in_the_background);
    }

    private static final java.util.Set<String> autoTried = new java.util.HashSet<>();

    /**
     * A shader update the user need not ask for: a dump whose pack has an older library the profile's delta manifest knows (deltaShaders) is updated in the background
     * with no question, once per app process (a failed or paused job is resumed once). The game card then shows it, and Play stays on when that library is playable
     * (Pack.playableLibrary); otherwise Play waits for it as before. Only the card's own pack is updated (card = true): never the pack Folders.packFor would pick
     * (a game added with another pack, e.g. the play app's beside the dump), never a whole job, never a job for another pack folder
     */
    static void autoUpdate(MainActivity a, File dump, File packDir, String titleId) {
        if (panel == null || panel.a != a || panel.alive()) return;
        if (sampledKeep(a, dump, packDir, titleId)) return;   // the quick check first (below): the older library may stand as this version's; a difference goes on to the update as before
        if (deltaShaders(packDir, titleId) < 0 || !autoTried.add(dump.getPath())) return;
        // the update is optional when the layer builds what the older library lacks (Pack.shaderUpdateOptional): the game's page offers it, nothing starts by itself
        if (Pack.shaderUpdateOptional(packDir, titleId, Prefs.layerEnv(a, titleId))) return;
        panel.picked(dump, true, packDir, false, true);
    }

    /** The checks wait in one process-wide line (SampleQueue), one at a time; a pack is keyed by its folder */
    private static final SampleQueue SAMPLES = new SampleQueue();
    private static final java.util.Map<String, SampleItem> sampleItems = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Handler sampleUi = new Handler(Looper.getMainLooper());
    private static final long SAMPLE_BEAT_MS = 2000, SAMPLE_START_GRACE_MS = 15000, SAMPLE_PLAY_HOLD_MS = 20000;
    private static Context sampleCtx;
    private static long sampleStartedAt, sampleStartedWall, sampleNotBefore;   // (elapsedRealtime, wall clock, elapsedRealtime)
    private static boolean sampleSeen;          // the running check's marker was seen
    private static String sampleStopping;       // a stopped (or skipped) check whose process has not ended yet: nothing starts meanwhile
    private static String sampleHold = "";      // why the line waits now, for the card
    private static boolean sampleResuming;      // the running check goes on from an earlier run's translations (its work folder had a key when it started)

    /** What a queued check needs to start: the pack's game file, its pack folder, its title and the older library's version */
    private static final class SampleItem {
        final File dump, pack;
        final String titleId;
        SampleItem(File dump, File pack, String titleId) { this.dump = dump; this.pack = pack; this.titleId = titleId; }
    }

    /** The folder a pack's sampled-keep check works in (LayerService.sample): its marker says the check runs */
    static File sampleWork(File packDir) { return new File(packDir, "shaders/.sample-work"); }

    /** Whether the pack's check runs now or waits in the line (its card is the check's card, a shader update waits for the verdict) */
    static boolean sampling(File packDir) {
        return SAMPLES.active(packDir.getPath()) || knownRunning.contains(packDir.getPath()) || LayerPending.building(sampleWork(packDir), System.currentTimeMillis());
    }

    /** Whether any check runs or waits (the launcher's cards are rebuilt when {@link #sampleSignature} changes) */
    static boolean sampleAny() { return SAMPLES.any(); }

    /** Changes whenever a check starts, ends, is skipped or moves in the line */
    static String sampleSignature() { return SAMPLES.signature(); }

    /** A check card's words: the state line, the bar (0 to 1000) while it runs, a small line on why the line waits */
    static final class SampleView {
        final String text, detail;
        final int bar;
        final boolean running;
        SampleView(String text, String detail, int bar, boolean running) { this.text = text; this.detail = detail; this.bar = bar; this.running = running; }
    }

    /** The card of a pack in the line, null when its check neither runs nor waits */
    static SampleView sampleView(File packDir) {
        String key = packDir.getPath();
        if (SAMPLES.isRunning(key)) {
            SampleQueue.Progress p = sampleProgress(packDir);
            if (p == null) p = SampleQueue.parseSeedLine(LayerPending.lastLineWith(new File(sampleWork(packDir), "sample.log"), "seed "));
            return new SampleView(SampleQueue.runningText(p, sampleResuming), "", SampleQueue.bar(p), true);
        }
        int ahead = SAMPLES.ahead(key);
        if (ahead < 0) return null;
        File work = sampleWork(packDir);
        String held = SampleQueue.backoffText(SampleQueue.backoffLeft(SampleQueue.interruptedAt(work), System.currentTimeMillis()));
        return new SampleView(SampleQueue.waitingText(ahead, new File(work, SampleQueue.RESUME).isFile()), !held.isEmpty() ? held : ahead == 0 || SAMPLES.running() == null ? sampleHold : "", 0, false);
    }

    /** The pack's check's progress.json, null when there is none */
    private static SampleQueue.Progress sampleProgress(File packDir) {
        try { return SampleQueue.parse(new String(java.nio.file.Files.readAllBytes(new File(sampleWork(packDir), SampleQueue.PROGRESS).toPath()), java.nio.charset.StandardCharsets.UTF_8)); } catch (IOException | RuntimeException e) { return null; }
    }

    /** Which APK a skip record is of (SampleQueue.skipHolds): another build of the app asks again */
    private static String sampleApk() { return BuildConfig.VERSION_NAME + "-" + BuildConfig.NX_COMMIT; }

    /** A pack the player skipped, or whose check could not start: recorded on disk (SampleQueue.writeSkip), so it is not queued again by the next app start either */
    private static void recordSkip(File packDir, String why) {
        SampleQueue.Progress p = sampleProgress(packDir);
        SampleQueue.writeSkip(packDir, BuildConfig.NX_DMSH_VK_CHAIN, why, System.currentTimeMillis(), sampleApk(), p == null ? 0 : p.done, p == null ? 0 : p.total);
    }

    /**
     * Sampled keep (android/SHADERS.md "Sampled keep"): a pack that has an older library the host plays, of this APK's ABI and the profile's inputs, and its dump, is asked once
     * whether that library would be the same with the new tools: LayerService translates a random sample of its shaders and compares them with the library's entries; all the same
     * and the library becomes this version's (marker sampled-keep.json), any difference and nothing changes (the result file keeps it from being asked again) and the update goes on
     * the other ways (the delta with a manifest, else the layer). The pack joins the line ({@link SampleQueue}): one check at a time, never while a game, a Prepare job or a layer build
     * runs, only while the phone is calm and not in the app's first seconds; Play never waits for it. → true while the pack's check runs or waits (the other update waits for its
     * verdict), false when there is none to make (done, skipped, or given up)
     */
    static boolean sampledKeep(Context c, File dump, File packDir, String titleId) {
        Profile p = Profile.of(titleId);
        if (p == null || !isDump(dump)) return false;
        if (new File(packDir, "shaders/" + ShaderLibs.KNOWN_DIR + BuildConfig.NX_DMSH_VK_CHAIN).isDirectory())   // a known-transition move a crash left half done: finished, or put back
            android.util.Log.i("nxnative", "sampled keep: known transition: " + (ShaderLibs.knownRecover(packDir, BuildConfig.NX_DMSH_VK_CHAIN, System.currentTimeMillis()) ? "a move left by a stop is finished" : ShaderLibs.knownWhy));
        File old = Pack.sampledKeepCandidate(packDir, titleId, Prefs.layerEnv(c, titleId));
        if (old == null) return false;
        String key = packDir.getPath();
        if (knownKeep(c, packDir, old)) return true;   // a known transition of the tools (vka_known_transitions.tsv): kept with no check, no card
        // a check's marker counts only while its process lives: a force-stopped app left one that would hold the pack as "checking" for minutes (LayerPending.building, own pid)
        if (LayerPending.selfPackage == null) LayerPending.selfPackage = c.getPackageName();
        if (LayerPending.building(sampleWork(packDir), System.currentTimeMillis()) && !SAMPLES.active(key)) return true;   // (a run of an earlier process of the app)
        if (SAMPLES.tried(key) || SAMPLES.cancelled(key)) return false;
        if (SampleQueue.skipped(packDir, BuildConfig.NX_DMSH_VK_CHAIN, sampleApk(), System.currentTimeMillis())) return false;   // (skipped, or given up, by an earlier start: for 7 days or until the APK changes)
        sampleItems.put(key, new SampleItem(dump, packDir, titleId));
        SAMPLES.enqueue(key);
        samplePump(c);
        return SAMPLES.active(key);
    }

    private static final java.util.Set<String> knownRunning = java.util.concurrent.ConcurrentHashMap.newKeySet(), knownTried = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The older library is one this APK's tools are known to make the same in effect (its header's VK chain is a row of dm/gfx/vk_android/vka_known_transitions.tsv for this chain,
     * BuildConfig.NX_KNOWN_FROM): it is moved in as this version's on a thread of its own (ShaderLibs.knownKeep: two renames and a header rewrite, no copy), with no sampled check and no card; once per pack
     * and process. → true while the copy runs (the card says "checking"; the other updates wait for it)
     */
    private static boolean knownKeep(Context c, File packDir, File old) {
        String key = packDir.getPath();
        if (knownRunning.contains(key)) return true;
        ShaderLibs.Header h = ShaderLibs.header(old);
        if (h == null || !ShaderLibs.knownTransition(BuildConfig.NX_KNOWN_FROM, h.vkChain) || !knownTried.add(key)) return false;
        knownRunning.add(key);
        Context app = c.getApplicationContext();
        new Thread(() -> {
            String took = null;
            try { took = ShaderLibs.knownKeep(packDir, old, BuildConfig.NX_DMSH_VK_CHAIN, BuildConfig.NX_DMSH_VK_ABI, BuildConfig.NX_DMSH_CHAIN, BuildConfig.NX_KNOWN_FROM, System.currentTimeMillis()); }
            finally { knownRunning.remove(key); }
            android.util.Log.i("nxnative", "sampled keep: known transition " + h.vkChain + " to " + BuildConfig.NX_DMSH_VK_CHAIN + (took != null ? ": library v-" + took + " moved in, no check" : ": not moved (" + ShaderLibs.knownWhy + "; the sampled check decides)"));
            if (took != null) sampleUi.post(() -> android.widget.Toast.makeText(app, SampleQueue.UP_TO_DATE, android.widget.Toast.LENGTH_SHORT).show());
        }, "nxknown").start();
        return true;
    }

    private static void samplePump(Context c) {
        sampleCtx = c.getApplicationContext();
        sampleUi.removeCallbacks(SAMPLE_RUN);
        if (!SAMPLES.any() && sampleStopping == null) return;
        try { sampleStep(sampleCtx); } catch (RuntimeException e) { android.util.Log.w("nxnative", "sampled keep queue: " + e); }
        sampleUi.postDelayed(SAMPLE_RUN, SAMPLE_BEAT_MS);
    }

    private static final Runnable SAMPLE_RUN = () -> samplePump(sampleCtx);

    private static boolean sampleAlive(SampleItem it) {
        return LayerPending.building(sampleWork(it.pack), System.currentTimeMillis()) || SystemClock.elapsedRealtime() - sampleStartedAt < SAMPLE_START_GRACE_MS;
    }

    /** One look at the line: finish a check that ended, stop one a game is to pass, or start the next when nothing holds it */
    private static void sampleStep(Context c) {
        boolean game = gameRunning(c);
        if (sampleStopping != null) {   // a stopped check's process ends first
            SampleItem it = sampleItems.get(sampleStopping);
            if (it != null && LayerPending.building(sampleWork(it.pack), System.currentTimeMillis())) return;
            sampleStopping = null;
        }
        String run = SAMPLES.running();
        if (run != null) {
            SampleItem it = sampleItems.get(run);
            if (game) { stopSample(c, SAMPLES.playTakesLead()); return; }   // (a game started another way: the check stops and waits at the front)
            if (it == null) { SAMPLES.ended(run); return; }
            if (LayerPending.building(sampleWork(it.pack), System.currentTimeMillis())) sampleSeen = true;
            if (sampleAlive(it)) return;
            sampleEnded(c, run, it);
            return;
        }
        boolean foreign = false;   // a run of an earlier process of the app still holds the packer
        for (SampleItem it : sampleItems.values()) foreign |= LayerPending.building(sampleWork(it.pack), System.currentTimeMillis());
        long sinceStart = SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis();
        String why = SampleQueue.holdReason(sinceStart, game, foreign || sampleStopping != null || busyNow(), LayerWatch.backgroundCalm(c), SystemClock.elapsedRealtime(), sampleNotBefore);
        sampleHold = why == null ? "" : holdText(why);
        if (why != null) return;
        long now = System.currentTimeMillis();
        String key = SAMPLES.startNext(k -> {   // a check interrupted (Play, the app closed) waits BACKOFF_MS after it; the others of the line go on meanwhile
            SampleItem x = sampleItems.get(k);
            return x == null || SampleQueue.backoffLeft(SampleQueue.interruptedAt(sampleWork(x.pack)), now) == 0;
        });
        if (key == null) return;
        SampleItem it = sampleItems.get(key);
        File old = it == null ? null : Pack.sampledKeepCandidate(it.pack, it.titleId, Prefs.layerEnv(c, it.titleId));
        if (old == null) { SAMPLES.ended(key); return; }   // (the pack has a library of this version meanwhile, or the file is gone: nothing to check)
        startSample(c, key, it, old);
    }

    private static boolean busyNow() { return panel != null && panel.alive(); }

    private static String holdText(String why) {
        return why.equals("a game runs") ? "Waits for the game to end" : why.equals("the battery is low") ? "Waits for the battery" : why.equals("the packer is busy") ? "Waits for the other job" : "";
    }

    private static void startSample(Context c, String key, SampleItem it, File old) {
        File work = sampleWork(it.pack);
        work.mkdirs();
        new File(work, "progress.json").delete();
        new File(work, LayerService.REFUSED).delete();
        try {
            Intent i = new Intent(c, LayerService.class).putExtra(LayerService.EXTRA_GAME, it.titleId).putExtra(LayerService.EXTRA_ROOT, work.getPath())
                    .putExtra(LayerService.EXTRA_SAMPLE_OLD, old.getParentFile().getName().substring(2)).putExtra(LayerService.EXTRA_SAMPLE_PACK, it.pack.getPath())
                    .putExtra(LayerService.EXTRA_SAMPLE_DUMP, it.dump.getPath()).putExtra(LayerService.EXTRA_SAMPLE_VERSION, Pack.read(it.pack).version)
                    .putExtra(LayerService.EXTRA_SAMPLE_N, ShaderLibs.sampleSize(Prefs.layerEnv(c, it.titleId))).putExtra(LayerService.EXTRA_SAMPLE_FIRST, ShaderLibs.sampleFirst(Prefs.layerEnv(c, it.titleId))).putExtra(LayerWatch.EXTRA_PROCS, 1).putExtra(LayerWatch.EXTRA_WARM_OK, true);
            sampleStartedAt = SystemClock.elapsedRealtime();
            sampleStartedWall = System.currentTimeMillis();
            sampleSeen = false;
            sampleResuming = new File(work, SampleQueue.RESUME).isFile();
            c.startForegroundService(i);   // a foreground service (LayerService): a plain one is stopped a minute after the app leaves the screen and its process frozen mid-check
        } catch (IllegalStateException e) {   // (the app is in the background: the system refuses the service for now; not a failure of the check)
            SAMPLES.startFailed(key, false);
            sampleNotBefore = SystemClock.elapsedRealtime() + SampleQueue.RETRY_MS;
        } catch (IOException | RuntimeException e) {
            if (SAMPLES.startFailed(key, true)) recordSkip(it.pack, SampleQueue.FAILED);   // (given up: recorded, so the next app start does not try again either)
            sampleNotBefore = SystemClock.elapsedRealtime() + SampleQueue.RETRY_MS;
        }
    }

    /** The check's process is gone: it ran (its marker was seen, or it left a verdict) and is tried for this process; else it did not run: the lock or the phone refused it (no try) or it failed to start (a try) */
    private static void sampleEnded(Context c, String key, SampleItem it) {
        boolean verdict = Pack.libraryOf(it.pack) != null;
        File[] results = new File(it.pack, "shaders").listFiles((d, n) -> n.startsWith(".sample-") && n.endsWith(".json"));
        if (results != null) for (File r : results) verdict |= r.lastModified() >= sampleStartedWall - 2000;
        if (sampleSeen || verdict) {
            SAMPLES.ended(key);
            if (Pack.libraryOf(it.pack) != null) android.widget.Toast.makeText(c, SampleQueue.UP_TO_DATE, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String why = null;
        try { why = new String(java.nio.file.Files.readAllBytes(new File(sampleWork(it.pack), LayerService.REFUSED).toPath()), java.nio.charset.StandardCharsets.US_ASCII).trim(); } catch (IOException | RuntimeException e) { /* none: it never ran */ }
        if (SAMPLES.startFailed(key, why == null || !(why.equals("busy") || why.equals("calm")))) recordSkip(it.pack, SampleQueue.FAILED);
        sampleNotBefore = SystemClock.elapsedRealtime() + SampleQueue.RETRY_MS;
    }

    /** The running check is to stop at its next safe point (LayerService.EXTRA_STOP: the shader in hand), writing no verdict */
    private static void stopSample(Context c, String key) {
        if (key == null) return;
        sampleStopping = key;
        try { c.startService(new Intent(c, LayerService.class).putExtra(LayerService.EXTRA_STOP, true)); } catch (RuntimeException e) { /* the check ends by itself */ }
    }

    /**
     * The player starts a game (MainActivity.start): the running check stops and waits at the front of the line, the game starts without waiting, no check starts while it runs
     * (and for a few seconds before its process exists), and the line goes on by itself after it
     */
    static void playTakesLead(Context c) {
        sampleCtx = c.getApplicationContext();
        String key = SAMPLES.playTakesLead();
        sampleNotBefore = SystemClock.elapsedRealtime() + SAMPLE_PLAY_HOLD_MS;
        if (key != null) stopSample(sampleCtx, key);
        if (SAMPLES.any()) samplePump(sampleCtx);
    }

    /** Whether the player's skip (or a give-up) of this pack's check still holds: 7 days or until the APK changes (the game's page offers Check again) */
    static boolean sampleSkipHolds(File packDir) { return SampleQueue.skipped(packDir, BuildConfig.NX_DMSH_VK_CHAIN, sampleApk(), System.currentTimeMillis()); }

    /** Lifts the skip record and puts the pack in the line again (what the check translated before stays: it resumes) */
    static void sampleAgain(Context c, File dump, File packDir, String titleId) {
        SampleQueue.skipFile(packDir, BuildConfig.NX_DMSH_VK_CHAIN).delete();
        sampledKeep(c, dump, packDir, titleId);
    }

    /**
     * The player skips this pack's check: it leaves the line, and a record on disk (shaders/.sample-skip-&lt;V&gt;.json) keeps it out for 7 days or until the APK or the tools
     * change; the others go on. What it translated so far stays for a later run (shader_sample.py resume)
     */
    static void sampleSkip(Context c, File packDir) {
        String key = packDir.getPath();
        recordSkip(packDir, SampleQueue.SKIPPED);
        if (SAMPLES.cancel(key)) stopSample(c.getApplicationContext(), key);
        sampleCtx = c.getApplicationContext();
        samplePump(sampleCtx);
    }

    /**
     * A code update the user need not ask for: when the pack is this app's own (ownPack) and its code key no longer matches because the app's tools changed
     * (a profile's patches.py, the rewriter), its code is rewritten in the background, as Prepare again would, keeping the shaders. Only with the dump there
     * (the card's check found no problem with it), its build one the profile knows (checked by picked: a build mismatch stops it), and no game or other job
     * running; once per dump per app process. The rewrite goes through the job's staging and swap, so a stopped update never leaves new code beside old tables
     */
    static boolean autoCodeUpdate(MainActivity a, File dump, File packDir) {
        if (panel == null || panel.a != a || panel.alive() || gameRunning(a) || !autoTried.add("code:" + dump.getPath())) return false;
        panel.picked(dump, true, packDir, true, true);
        return true;
    }

    /** Whether the background code update of this dump was started in this process already (autoCodeUpdate tries once): the card then offers a tap to retry instead of waiting for ever */
    static boolean codeUpdateTried(File dump) { return autoTried.contains("code:" + dump.getPath()); }

    /**
     * Whether this app made the pack, so the launcher may update its code by itself: its pack.tsv's made_by row (written by the phone's pack step), else, for a pack
     * made before that row, the job beside the dump that says it made this pack folder (job.json own_pack). Without either, the pack is treated as other tools'.
     * Reads files: call it off the main thread
     */
    static boolean ownPack(File dump, File packDir, String titleId) {
        try {
            if (Pack.read(packDir).madeByApp()) return true;
            File jf = Job.fileOf(DumpCheck.workOf(dump, titleId));
            if (!jf.isFile()) return false;
            Job j = Job.read(jf);
            return j.ownsPack() && GameList.samePath(j.pack().getPath(), packDir.getPath());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Whether a game runs now (the :game process): the background code update waits for it, as the pack's code must not change under a running game */
    private static boolean gameRunning(Context c) {
        android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        List<android.app.ActivityManager.RunningAppProcessInfo> ps = am == null ? null : am.getRunningAppProcesses();
        if (ps != null) for (android.app.ActivityManager.RunningAppProcessInfo p : ps) if (p.processName.endsWith(":game")) return true;
        return false;
    }

    /** Whether a job (any game's) runs now, so a background shader update of another card waits for it (autoUpdate starts none while one is alive) */
    static boolean busy(MainActivity a) { return panel != null && panel.a == a && panel.alive(); }

    /** The shader update job of this game's card (its dump and pack): the card's job when it has one, else the remembered job.json; null when none is for this game */
    static Job cardJob(MainActivity a, Game g) {
        Job j = panel != null && panel.a == a ? panel.job : null;
        if (j == null) {
            File f = Job.remembered(a);
            try { j = f != null && f.isFile() ? Job.read(f) : null; } catch (IOException e) { j = null; }
        }
        return j != null && GameList.samePath(g.gameDir, j.dump().getPath()) && GameList.samePath(g.packDir, j.pack().getPath()) ? j : null;
    }

    /** Whether a folder or .nup is a dump Prepare can start from: an update, or a base game with its code and RomFS (Pack.isDump; DumpCheck checks it fully) */
    static boolean isDump(File dir) { return Pack.isDump(dir); }
    static void resume(MainActivity a) { if (panel != null && panel.a == a) panel.resume(); }

    static void pause(MainActivity a) { if (panel != null && panel.a == a) panel.pause(); }

    /** Whether a Prepare job is shown now (its game's tile carries the ring; the card itself is only in the sheet) */
    static boolean showing() { return panel != null && panel.view.getVisibility() == View.VISIBLE; }
    /** The job's own Manage sheet (Forget, the deletes), from the game's ⋯ while its card is the progress card */
    static void manageJob(MainActivity a) { if (panel != null && panel.a == a) panel.manage(); }
    /** The progress of the job the card shows, 0..1, or -1 when no card shows (a tile's ring; MainActivity.workTick) */
    static float progress() {
        Job j = panel != null && panel.view.getVisibility() == View.VISIBLE ? panel.job : null;
        return j == null ? -1f : (float) j.overall();
    }

    /** The tile ring's states (MainActivity.workTick): the job runs, waits to start (the ring goes round), is paused (heat, battery, the user), failed, or done */
    static final int TILE_RUNNING = 0, TILE_QUEUED = 1, TILE_PAUSED = 2, TILE_FAILED = 3, TILE_DONE = 4;

    /** Which state the job's tile shows: from the job's own state, never from the words */
    static int tileState() {
        Job j = panel != null && panel.view.getVisibility() == View.VISIBLE ? panel.job : null;
        if (j == null) return TILE_RUNNING;
        String st = j.state();
        boolean alive = panel.alive();
        if ("failed".equals(st)) return TILE_FAILED;
        if ("done".equals(st)) return TILE_DONE;
        if ("paused".equals(st) && !alive) return TILE_PAUSED;
        if ("pending".equals(st) || !alive && "running".equals(st)) return TILE_QUEUED;
        return TILE_RUNNING;
    }

    /** What the job is doing, in words (the card's state line) */
    static String progressLine() { return panel == null ? "" : panel.status.getText().toString(); }

    /** The job's game name */
    static String jobName() { return panel != null && panel.job != null ? panel.job.name() : ""; }

    /** The job's dump folder or .nup (a tile of a game not listed yet loads its picture from it), or null */
    static File jobDump() { return panel != null && panel.job != null ? panel.job.dump() : null; }

    private static android.app.AlertDialog sheetDialog;

    /** Whether the job's sheet is open (the list must not take its card away) */
    static boolean sheetShowing() { return sheetDialog != null && sheetDialog.isShowing(); }

    static void closeSheet() { if (sheetDialog != null && sheetDialog.isShowing()) sheetDialog.dismiss(); }

    /** The progress card in a sheet over Home, the only place the card is shown: a game's tile that is being prepared opens it (Pause, Retry, Play, Manage and the rest of what the card has) */
    static void sheet(MainActivity a) {
        if (panel == null || panel.a != a || panel.view.getVisibility() != View.VISIBLE) return;
        closeSheet();
        View v = panel.view;
        if (v.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) v.getParent()).removeView(v);
        // A rounded sheet at the bottom over Home: the card (game, one progress bar, the step in words, Pause / Resume / Retry / Play, ⋯ Manage, details smaller below) and a Close button.
        // A tap outside, Back (B on a controller) or Close closes it; the job keeps running behind it
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(v);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        AlertDialog[] dlg = new AlertDialog[1];
        Button close = Ui.button(a, Tx.s(R.string.common_close), Ui.Kind.QUIET, x -> dlg[0].dismiss());
        Ui.add(box, close, 8);
        dlg[0] = sheetDialog = new PadFocus.Builder(a).setView(box).create();
        sheetDialog.setCanceledOnTouchOutside(true);
        sheetDialog.setOnDismissListener(d -> { sv.removeAllViews(); box.removeAllViews(); });
        android.view.Window w = sheetDialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));   // the card brings its own rounded corners
            w.setGravity(android.view.Gravity.BOTTOM);
        }
        sheetDialog.show();
    }

    /** Whether the progress card shows this game (its dump and pack): its card in the list is then the progress card, one card per game */
    static boolean shows(Game g) {
        Job j = panel != null && panel.view.getVisibility() == View.VISIBLE ? panel.job : null;
        return j != null && GameList.samePath(g.gameDir, j.dump().getPath()) && GameList.samePath(g.packDir, j.pack().getPath());
    }

    static boolean activityResult(MainActivity a, int request, int result, Intent data) {
        if (request != PICK_DUMP) return false;
        if (result != MainActivity.RESULT_OK || data == null || data.getData() == null || panel == null || panel.a != a) return true;
        String path = PathUtil.treeToPath(data.getData());
        if (path == null) a.error(Tx.s(R.string.err_folder_not_on_storage, data.getData()));
        else panel.picked(new File(path), false, null);
        return true;
    }

    /** adb: am start -n dev.nxnative.android/.MainActivity (play app: dev.nxnative.android.play/dev.nxnative.android.MainActivity) --es ondevice_dump <dump folder> [--ez ondevice_start true] [--ez ondevice_pause true] [--ez ondevice_go_on true]
     *  [--es ondevice_pack <pack folder>] [--ez show_when_locked true] (TESTING.md). ondevice_pack: the job writes its pack there instead of the app's
     *  packs/<id> (AppStorage.pack), and a pack already there or beside the dump is not adopted (agents' test runs: <work>/ondevice/test-pack, so the user's pack is never written) */
    static void fromIntent(MainActivity a, Intent i) {
        if (panel != null && panel.a != a) return;   // a panel of an activity gone (the launcher was recreated on its Settings page)
        // Tests over adb on a locked phone: the launcher shows over the lock screen (it stays locked; only this screen shows), so its screen can be checked and captured
        if (i.getBooleanExtra("show_when_locked", false)) { a.setShowWhenLocked(true); a.setTurnScreenOn(true); a.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
        String dump = i.getStringExtra("ondevice_dump");
        String pack = i.getStringExtra("ondevice_pack");
        if (dump != null && panel != null) panel.picked(new File(dump), i.getBooleanExtra("ondevice_start", false), pack == null ? null : new File(pack));
        if (i.getBooleanExtra("ondevice_pause", false) && panel != null) panel.pauseJob("adb");
        if (i.getBooleanExtra("ondevice_go_on", false) && panel != null) panel.goOnAnyway("adb");
    }

    private static final class Panel implements ServiceConnection {
        final MainActivity a;
        final LinearLayout view, card;
        final TextView head, title, status, detail, tile;
        final ProgressBar bar;
        final Button go, goOn, anyway, update, skip;
        final Ui.Details why;   // a failed job's technical part (the tool's error, the folders, the logs), closed by default
        final Ui.Action play;
        final View more;
        /** The job (file and creation time) whose game this app put into the list ("added" in the ondevice prefs): once per job, so a game the user removed stays removed */
        static String addedKey(Job j) { return j.file.getPath() + "@" + j.created(); }
        final Handler h = new Handler(Looper.getMainLooper());
        final Messenger client = new Messenger(new Handler(Looper.getMainLooper(), this::onMessage));
        Messenger service;
        boolean bound, resumed;
        long resumedAt;
        Job job;
        private String placedFor;   // which job (dump and pack) the list was last built for
        private String metaLine;   // the game's version and publisher from its .nup, shown before the state
        boolean jobAlive;   // the service said its worker runs this job

        Panel(MainActivity a) {
            this.a = a;
            // A card while there is a job: its heading, a line on what preparing is, then the job in a soft inner card: its name and ⋯ Manage at the top right,
            // the state, the progress, then Pause / Resume / Retry and Play. Forget and the deletes are in Manage, never beside Play. No job, no card: "+ Add game"
            // is where preparing starts
            view = Ui.card(a);
            card = view;   // one card: the heading and ⋯ Manage, the state, the progress, the buttons
            LinearLayout top = Ui.row(a);
            top.setGravity(android.view.Gravity.TOP);
            // laid out as a game card (MainActivity.gameCard): the game's tile, its name and state line, ⋯; MainActivity puts it into the game list
            tile = Ui.tile(a, "", 52);
            top.addView(tile);
            head = Ui.text(a, "", 17, R.color.bn_text, 700);
            head.setMaxLines(2);
            head.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title = Ui.small(a, "");
            LinearLayout tc = new LinearLayout(a);   // in a column of its own, as the game cards: a bare TextView with weight kept its first (one-line) height
            tc.setOrientation(LinearLayout.VERTICAL);
            tc.setPadding(dp(14), 0, dp(4), 0);
            tc.addView(head);
            tc.addView(title);
            top.addView(tc, Ui.weight());
            // ⋯: a listed game's own Manage (Game settings, saves, packs ... and Preparing, which leads here: manageJob); a game not listed yet: the job's
            more = Ui.more(a, Tx.s(R.string.prepare_manage), v -> { Game g = job != null ? listedGame(job) : null; if (g != null) a.manageGame(g); else manage(); });
            LinearLayout.LayoutParams mp = (LinearLayout.LayoutParams) more.getLayoutParams();
            mp.leftMargin = dp(8);
            top.addView(more, mp);
            card.addView(top);
            status = Ui.body(a, "");
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);   // what the job does, said when it changes (step, pause, stop)
            Ui.add(card, status, 10);
            bar = Ui.progress(a);
            bar.setMax(1000);
            Ui.add(card, bar, 10);
            detail = Ui.small(a, "");
            Ui.add(card, detail, 8);
            why = Ui.details(a, "");
            Ui.add(card, why, 4);
            go = Ui.button(a, "", Ui.Kind.TONAL, v -> goOrPause());
            go.setPadding(dp(12), 0, dp(12), 0);   // "Prepare again" fits half a narrow card
            go.setMinHeight(dp(56));
            go.setMinimumHeight(dp(56));
            play = Ui.action(a, R.drawable.ic_play, Tx.s(R.string.common_play), Ui.Kind.PRIMARY, v -> play());
            Ui.add(card, Ui.pair(a, go, play), 16);
            // a heat pause (PackPolicy): the phone may report heat it does not have (a thermal HAL that says SEVERE on a cool handheld, GitHub #38)
            goOn = Ui.button(a, Tx.s(R.string.prepare_go_on_anyway), Ui.Kind.SOFT, v -> goOnAnyway("launcher"));
            goOn.setVisibility(View.GONE);
            Ui.add(card, goOn, 8);
            // a failed job whose check can be relaxed (PrepareText.FIX_ANYWAY): asked first, with what may go wrong
            anyway = Ui.button(a, Tx.s(R.string.prepare_try_anyway), Ui.Kind.SOFT, v -> tryAnyway());
            anyway.setVisibility(View.GONE);
            Ui.add(card, anyway, 8);
            // a whole Prepare of a game the layer covers that stopped in its shader translation: finish with the code only and play (canSkip)
            skip = Ui.button(a, PrepareText.SKIP_SHADERS(), Ui.Kind.SOFT, v -> { if (canSkip(job)) skipShaders(job); });
            skip.setVisibility(View.GONE);
            Ui.add(card, skip, 8);
            // a failed job that this app build cannot finish (PrepareText.FIX_UPDATE): the release app's updater, where it has one
            update = Ui.button(a, Tx.s(R.string.common_check_for_updates), Ui.Kind.SOFT, v -> Updater.checkNow(a));
            update.setVisibility(View.GONE);
            Ui.add(card, update, 8);
            load();
            show();
        }

        int dp(int v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }

        void detach() {
            keepScreen(false);
            pause();
        }

        /** Settings › Keep the screen on while preparing: the window's FLAG_KEEP_SCREEN_ON while the job runs (or waits to go on by itself) and Home shows its card */
        private boolean screenKept;
        void keepScreen(boolean on) {
            if (on == screenKept) return;
            screenKept = on;
            if (on) a.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else a.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            android.util.Log.i("nxpack", "keep screen on while preparing: " + (on ? "on" : "off"));
        }

        void resume() {
            resumed = true;
            resumedAt = SystemClock.elapsedRealtime();
            if (!bound) bound = a.bindService(new Intent(a, PackService.class), this, 0);   // 0: only while the service runs; never starts it
            tick();
        }

        void pause() {
            resumed = false;
            h.removeCallbacksAndMessages(null);
            if (bound) {
                if (service != null) {
                    Message m = Message.obtain(null, PackService.MSG_UNREGISTER);
                    m.replyTo = client;
                    try { service.send(m); } catch (RemoteException e) { /* gone */ }
                }
                a.unbindService(this);
                bound = false;
                service = null;
                jobAlive = false;
            }
        }

        /** Every second while shown: job.json when the service is not telling us */
        void tick() {
            if (!resumed) return;
            if (service == null || !jobAlive) load();
            show();
            h.postDelayed(this::tick, 1000);
        }

        void load() {
            File f = Job.remembered(a);
            if (f == null || !f.isFile()) { job = null; return; }
            try {
                job = Job.read(f);
            } catch (IOException e) {
                // being renamed over, or damaged: keep the last one
            }
        }

        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            service = new Messenger(b);
            Message m = Message.obtain(null, PackService.MSG_REGISTER);
            m.replyTo = client;
            try { service.send(m); } catch (RemoteException e) { service = null; }
        }

        @Override public void onServiceDisconnected(ComponentName n) {   // the :pack process died
            service = null;
            jobAlive = false;
            show();
        }

        @Override public void onBindingDied(ComponentName n) {
            service = null;
            jobAlive = false;
            if (bound) { a.unbindService(this); bound = false; }
            if (resumed) bound = a.bindService(new Intent(a, PackService.class), this, 0);
        }

        boolean onMessage(Message m) {
            if (m.what != PackService.MSG_STATE) return false;
            Bundle d = m.getData();
            Job j = Job.of(new File(d.getString("file", "")), d.getString("job", "{}"));
            if (j != null) job = j;
            jobAlive = d.getBoolean("alive");
            show();
            return true;
        }

        /** Whether the service's worker is running the job now (or may be: just resumed, the binding not yet answered) */
        boolean alive() { return jobAlive || (service == null && SystemClock.elapsedRealtime() - resumedAt < 2500 && job != null && "running".equals(job.state())); }

        private String needSeen;   // the last "needs a tap" kind of the job shown (null: none); needBase: a job has been shown already, so a change is news
        private boolean needBase;

        /**
         * What the job waits on the player for, in its state and never its words: it failed (Retry, Try anyway, Skip, Check for updates) or a heat pause asks Go on anyway.
         * A running or deliberately paused job needs nothing: the tile's ring is enough
         */
        static String needsTap(Job j, boolean alive) {
            String st = j.state();
            if ("failed".equals(st)) return "failed";
            if (alive && j.canGoOn()) return "goon";
            return null;
        }

        /** A job that newly needs a tap while Home shows opens its sheet by itself (once per change; a job already waiting when Home was built does not, its tile shows the ring) */
        private void autoSheet(String need) {
            boolean news = needBase && need != null && !need.equals(needSeen);
            needSeen = need;
            needBase = true;
            if (news && resumed && !sheetShowing()) sheet(a);
        }

        void show() {
            boolean alive = job != null && alive();
            boolean ready = job != null && ready(job);
            // A finished job: its game goes into the list with its pack (once), and the card goes: Play is on the game's card
            if (ready && !alive && !addedKey(job).equals(Prefs.open(a, "ondevice").getString("added", ""))) addGame(job);
            boolean was = view.getVisibility() == View.VISIBLE;
            if (job == null || ready && listed(job)) { keepScreen(false); view.setVisibility(View.GONE); placedFor = null; needSeen = null; needBase = false; closeSheet(); if (was) a.refresh(); return; }
            view.setVisibility(View.VISIBLE);
            // The list puts this card where its game's card would be. When the card switches to another game's job, the list has to be rebuilt too.
            // That happens after an APK update: the card first shows another game's finished job, then a background shader update takes it over,
            // and without a rebuild the card stays in the first game's spot, hiding that game's card while the updated game shows twice
            String key = job.dump().getPath() + "\n" + job.pack().getPath();
            if (!was || !key.equals(placedFor)) {
                placedFor = key;
                a.refresh();   // this also hides the "no games yet" card while this one shows
                Ui.tileIcon(tile, null);   // like the game card: the icon from the .nup if it has one, and its version and publisher before the state
                metaLine = null;
                GameMeta.load(job.dump(), m -> {
                    if (!key.equals(placedFor)) return;
                    if (m.icon != null) Ui.tileIcon(tile, m.icon);
                    metaLine = m.line();
                    if (metaLine != null) show();
                });
            }
            String st = job.state();
            String sim = "fake".equals(BuildConfig.NX_PACK_ENGINE) ? "  (simulated engine)" : "";
            head.setText(job.name());
            String n = job.name().trim();
            tile.setText(n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT));
            // A job of a game this app version does not include (made by an APK that had it): one sentence, nothing to press but ⋯ (Forget, the deletes)
            String notIncluded = Profile.missing(job.titleId(), job.name());
            boolean failed = "failed".equals(st);
            title.setText((metaLine != null ? metaLine + " \u00b7 " : "") + (notIncluded != null ? Tx.s(R.string.common_not_in_this_app_version) : "done".equals(st) ? Tx.s(R.string.prepare_prepared) : "pending".equals(st) ? Tx.s(R.string.prepare_ready_to_prepare) : failed ? Tx.s(R.string.prepare_could_not_prepare)
                    : "paused".equals(st) ? Tx.s(R.string.prepare_paused) : Tx.s(R.string.common_preparing)) + (job.tryAnyway() ? Tx.s(R.string.prepare_tried_anyway) : "") + sim);
            status.setText(notIncluded != null ? notIncluded : job.statusText(alive));
            int pct = (int) Math.round(job.overall() * 1000);
            bar.setProgress(pct);
            bar.setStateDescription((pct / 10) + " percent");
            detail.setText(job.detailText());
            bar.setVisibility(notIncluded != null ? View.GONE : View.VISIBLE);
            detail.setVisibility(notIncluded != null ? View.GONE : View.VISIBLE);
            why.set(failed || notIncluded != null ? job.errorDetail() : "");
            boolean running = alive && ("running".equals(st) || "paused".equals(st) && !"user".equals(job.reason()) && !"timeout".equals(job.reason()));
            boolean noFix = notIncluded != null || failed && PrepareText.FIX_NONE.equals(job.fix());   // nothing in this app can make it work: no Retry that cannot succeed
            boolean broken = failed && !alive && job.brokenApk(Job.installed(a));   // this app build cannot do it: no Retry until another install; an older pack may still play
            go.setText(running ? Tx.s(R.string.prepare_pause) : "done".equals(st) && !job.apkChanged() ? Tx.s(R.string.common_prepare_again) : failed ? Tx.s(R.string.prepare_retry) : "pending".equals(st) ? Tx.s(R.string.prepare_start) : Tx.s(R.string.prepare_resume));
            go.setEnabled(!"done".equals(st) || job.apkChanged());
            go.setVisibility(noFix || broken ? View.GONE : View.VISIBLE);
            update.setVisibility(broken && Updater.available(a) ? View.VISIBLE : View.GONE);
            goOn.setVisibility(alive && job.canGoOn() ? View.VISIBLE : View.GONE);
            anyway.setVisibility(!alive && failed && !noFix && PrepareText.FIX_ANYWAY.equals(job.fix()) && !job.tryAnyway() ? View.VISIBLE : View.GONE);
            skip.setVisibility(notIncluded == null && !broken && canSkip(job) ? View.VISIBLE : View.GONE);
            keepScreen(running && Prefs.keepScreenPreparing(a));
            play.setEnabled(ready && !running || !ready && playsOld(job));
            play.setVisibility(noFix ? View.GONE : View.VISIBLE);   // Play stays (disabled while it cannot start) so the card's layout does not jump; gone only when it never can
            autoSheet(needsTap(job, alive));
        }

        /** Try anyway…: the warning first, then the job goes on with its checks relaxed (Job.tryAnyway) from where it stopped */
        void tryAnyway() {
            Job j = job;
            if (j == null || alive()) return;
            new PadFocus.Builder(a).setTitle(Tx.s(R.string.prepare_try_anyway_2)).setMessage(PrepareText.ANYWAY_WARNING())
                    .setPositiveButton(Tx.s(R.string.prepare_try_anyway_3), (d, w) -> {
                        if (a.needAccess(j.dump())) return;
                        try {
                            j.tryAnyway(true);
                            j.save();
                        } catch (IOException e) {
                            a.error(Tx.s(R.string.err_could_not_save_job, e.getMessage()));
                            return;
                        }
                        show();
                        startService(j);
                    })
                    .setNegativeButton(Tx.s(R.string.common_cancel), null).show();
        }

        // A listed game whose shaders are being updated plays with its older library of the same ABI meanwhile (Pack.playableLibrary), as its own card did: the list
        // leaves that card out while this one shows (shows). Checked at most every 5 s: show runs every second and both read files
        private long oldAt; private boolean oldOk;
        boolean playsOld(Job j) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - oldAt > 5000) { oldAt = now; oldOk = listedGame(j) != null && (Pack.playableLibrary(j.pack(), j.titleId()) != null || Pack.legacyLibrary(j.pack(), j.titleId()) != null || playsOnLayer(j)); }
            return oldOk;
        }

        /**
         * The job's pack plays as it is with no library, the shader layer building what the game asks for (Pack.shadersOnDemand): a Build all (Prepare shaders for the
         * whole set) of a game prepared without shaders, while it runs, waits or after it failed. Its code is complete, of this title
         */
        boolean playsOnLayer(Job j) {
            try {
                Pack p = Pack.read(j.pack());
                return p.unfinishedModule() == null && p.titleId.equals(j.titleId()) && Pack.shadersOnDemand(j.pack(), j.titleId(), Prefs.layerEnv(a, j.titleId()));
            } catch (IOException e) {
                return false;
            }
        }

        /** A job of a game the layer covers that stopped in its shader translation: it may finish without it (Job.canSkipShaders) unless its pack already plays */
        boolean canSkip(Job j) {
            return j != null && j.canSkipShaders() && !alive() && ShaderLibs.layerCovers(j.titleId(), Prefs.layerEnv(a, j.titleId())) && !playsOld(j);
        }

        /** Finish without the shader set: the job goes on with its pack step, packing with no library (Job.codeOnly); the game then builds its shaders as it plays */
        void skipShaders(Job j) {
            if (a.needAccess(j.dump())) return;
            try {
                j.codeOnly(true);
                j.save();
            } catch (IOException | RuntimeException e) {
                a.error(Tx.s(R.string.err_could_not_save_job, e.getMessage()));
                return;
            }
            show();
            startService(j);
        }
        Game listedGame(Job j) {
            for (Game g : Game.load(a)) if (GameList.samePath(g.gameDir, j.dump().getPath()) && GameList.samePath(g.packDir, j.pack().getPath())) return g;
            return null;
        }

        /** Whether the list has this job's game with its pack */
        boolean listed(Job j) {
            for (Game g : Game.load(a)) if (GameList.samePath(g.gameDir, j.dump().getPath()) && GameList.samePath(g.packDir, j.pack().getPath())) return true;
            return false;
        }

        /** The finished job's game into the list (its dump and pack); a picked pack folder (Folders) follows to the new pack */
        void addGame(Job j) {
            Prefs.open(a, "ondevice").edit().putString("added", addedKey(j)).apply();
            if (listed(j)) return;
            Pack p;
            try { p = Pack.read(j.pack()); } catch (IOException e) { return; }
            if (p.simulated) return;
            Game g = new Game(j.titleId(), p.name.isEmpty() ? j.name() : p.name, j.dump().getPath(), j.pack().getPath());
            Game.put(a, g);   // one card per game: replaces the entries of the same file or the same title and build (GameList)
            String picked = Folders.pickedPack(a, g.titleId);
            if (picked != null && !picked.equals(g.packDir)) Folders.pickedPack(a, g.titleId, g.packDir);
            a.refresh();
            long[] miss = j.shadersMissing();   // Try anyway on a game version the profile was not made from: say briefly what it lacks and which version has it all
            Profile prof = Profile.of(j.titleId());
            android.widget.Toast.makeText(a, Tx.s(R.string.prepare_name_is_ready, g.name) + (j.codeOnly() ? Tx.s(R.string.prepare_it_builds_shaders_while_you) : "") + (miss[0] > 0 && prof != null ? " " + PrepareText.missingShadersText(miss[0], miss[1], prof.supportedShort()) : ""),
                    android.widget.Toast.LENGTH_LONG).show();
        }

        /** Play needs the pack (Pack.read: pack.tsv and its modules) and its shader library of this APK's version; a code-only job (Job.codeOnly) needs no library: the game builds its shaders while it plays */
        boolean ready(Job j) {
            if (!"done".equals(j.state()) || j.apkChanged()) return false;
            try {
                Pack p = Pack.read(j.pack());
                return (j.codeOnly() || Pack.libraryProblem(j.pack(), j.titleId()) == null) && p.unfinishedModule() == null && p.titleId.equals(j.titleId());
            } catch (IOException e) {
                return false;
            }
        }

        /** What a background check found beside a picked dump */
        static final class Found {
            DumpCheck.Result r;
            File existing;   // a job.json beside the dump
            File packDir;    // the pack Folders.packFor finds for it (null: none)
            Pack pack;       // that pack, when it reads as this game's pack
            String build;    // PackCheck.blocking: why its code cannot run with this dump (unfinished, another game build), null when it fits
            Boolean code;    // PackCheck.code: its code is this APK's rewriter's (null: cannot tell)
        }

        /**
         * A picked dump: checked off the main thread (the dump, the pack beside it), then the job: a job in progress for it is resumed; a pack that fits is used
         * as it is; a pack that fits in part has only the rest redone; else the whole job. again: the user asked to prepare again (a game card's Manage): a pack
         * whose code another rewriter made has its code redone too
         */
        void picked(File dump, boolean start, File packOverride) { picked(dump, start, packOverride, false); }

        void picked(File dump, boolean start, File packOverride, boolean again) { picked(dump, start, packOverride, again, false); }

        /** card: an automatic update of a game card's pack (autoUpdate): packOverride is that pack, checked as the pack to keep; nothing else is started or asked */
        void picked(File dump, boolean start, File packOverride, boolean again, boolean card) { picked(dump, start, packOverride, again, card, false); }

        /** anyBuild: Try anyway for a dump of a build this app does not list (asked first: untested); a job beside the dump that was tried anyway keeps it */
        void picked(File dump, boolean start, File packOverride, boolean again, boolean card, boolean anyBuild) {
            if (a.needAccess(dump)) return;
            userAsked = !card;
            android.widget.Toast.makeText(a, Tx.s(R.string.home_checking_name, dump.getName()), android.widget.Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                Found f = new Found();
                boolean any = anyBuild;
                try {
                    String id = Pack.titleIdOf(dump);   // a .nup, a folder's base, or an update-only dump's newest update
                    File jf = Job.fileOf(DumpCheck.workOf(dump, id));
                    if (jf.isFile()) {
                        f.existing = jf;
                        try { any |= Job.read(jf).tryAnyway(); } catch (IOException e) { /* another format: a new job replaces it */ }
                    }
                } catch (IOException e) {
                    // DumpCheck says what is wrong
                }
                f.r = DumpCheck.check(dump, f.existing == null, any);
                if (f.r.ok() && (packOverride == null || card)) {
                    f.packDir = card ? packOverride : Folders.packFor(a, f.r.dump, f.r.titleId);
                    try {
                        Pack p = f.packDir == null ? null : Pack.read(f.packDir);
                        if (p != null && !p.simulated && p.titleId.equals(f.r.titleId)) {
                            f.pack = p;
                            f.build = PackCheck.blocking(a, p, f.r.dump);
                            if (f.build == null) f.code = PackCheck.code(a, p, f.r.dump);
                        }
                    } catch (IOException e) {
                        // no usable pack: the whole job
                    }
                }
                h.post(() -> checked(f, start, packOverride, again, card));
            }, "nxpack-check").start();
        }

        void checked(Found f, boolean start, File packOverride, boolean again, boolean card) {
            DumpCheck.Result r = f.r;
            if (!r.ok()) {
                if (card) return;
                if (r.otherBuild) untested(r, start, packOverride, again);   // a build this app does not list: say so, and offer Try anyway
                else Ui.explain(a, Tx.s(R.string.prepare_cannot_prepare_this_dump), r.error, r.detail).setPositiveButton(android.R.string.ok, null).show();
                show();
                return;
            }
            // A job in progress for this dump is resumed
            if (f.existing != null) {
                try {
                    Job j = Job.read(f.existing);
                    boolean same = j.dump().getPath().equals(r.dump.getPath()) && j.version() == r.version && (packOverride == null || packOverride.equals(j.pack()));
                    String s = j.state();
                    if (same && (s.equals("running") || s.equals("paused") || s.equals("failed") || s.equals("pending"))) {
                        Job.remember(a, f.existing);
                        job = j;
                        show();
                        if (start) startService(j);
                        else if (canSkip(j))   // stopped in the shader translation: it can also finish with the code only and play now
                            new PadFocus.Builder(a).setTitle(Tx.s(R.string.prepare_resume_preparing)).setMessage(j.statusText(false) + "\n\n" + Tx.s(R.string.prepare_it_goes_on) + " " + PrepareText.SKIP_SHADERS_TEXT())
                                    .setPositiveButton(Tx.s(R.string.prepare_resume), (d, w) -> startService(j)).setNeutralButton(PrepareText.SKIP_SHADERS(), (d, w) -> skipShaders(j))
                                    .setNegativeButton(Tx.s(R.string.common_cancel), null).show();
                        else confirm(Tx.s(R.string.prepare_resume_preparing), j.statusText(false) + Tx.s(R.string.prepare_n_nit_goes_on_from), Tx.s(R.string.prepare_resume), () -> startService(j));
                        return;
                    }
                    if (!s.equals("done") && !s.isEmpty()) {   // unfinished, for another pack folder: never written over (its job.json is this dump's)
                        if (card) return;   // an automatic update leaves it to the user
                        Ui.explain(a, Tx.s(R.string.prepare_preparing_is_not_finished), Tx.s(R.string.prepare_already_being_prepared), j.statusText(false) + "\n\n" + j.errorDetail())
                                .setPositiveButton(android.R.string.ok, null).show();
                        Job.remember(a, f.existing);
                        job = j;
                        show();
                        return;
                    }
                } catch (IOException e) {
                    // a damaged job.json or one of another format: a new job replaces it
                }
            }
            // One job at a time: the service runs only the job it has, so a new one is not written or remembered while it runs
            if (alive() && job != null && !"done".equals(job.state())) {
                a.error(Tx.s(R.string.err_being_prepared_now, job.name(), job.statusText(true)));
                return;
            }
            if (card && (f.pack == null || f.build != null)) return;   // an automatic update only updates the shaders of the card's own pack, when its code fits
            // A pack already there (the picked pack folder, else beside the dump): what fits is kept
            if (f.pack != null) {
                File pd = f.packDir;
                String lp = PackCheck.shaders(pd, r.titleId);
                boolean covered = ShaderLibs.layerCovers(r.titleId, Prefs.layerEnv(a, r.titleId));   // the layer builds the shaders while the game plays: the whole set is optional
                boolean code = f.build != null || again && !Boolean.TRUE.equals(f.code), shaders = lp != null;
                if (!code && !shaders) { adopt(r, pd, false, false); return; }
                String why = (f.build != null ? f.build + "\n\n" : again && code ? Tx.s(R.string.prepare_the_pack_s_game_code_2) : "") + (lp != null ? lp + "\n\n" : "");
                if (covered && code && shaders && Pack.libraryOf(pd) == null) {
                    // a code update of a pack that has no library: code only, unless the player ticks the whole set
                    if (start) { adopt(r, pd, true, false); return; }
                    askCode(r, pd, why, Profile.of(r.titleId));
                    return;
                }
                Runnable redo = () -> adopt(r, pd, code, shaders);
                if (start) { redo.run(); return; }
                int delta = shaders ? deltaShaders(pd, r.titleId) : -1;
                boolean optional = covered && shaders && !code;   // Prepare shaders for a pack with code only (or one whose older library plays): optional
                String what = optional ? PrepareText.optionalShadersText(ShaderLibs.minutesText(delta >= 0 ? delta : Profile.of(r.titleId).shaderTotal))
                        : code && shaders ? (delta >= 0 ? Tx.s(R.string.prepare_rewriting_the_game_s_code_3) + shadersText(pd, r.titleId)
                                : Tx.s(R.string.prepare_rewriting_the_game_s_code_2) + ShaderLibs.jobMinutesText(Profile.of(r.titleId).shaderTotal) + Tx.s(R.string.prepare_in_the_background))
                        : code ? Tx.s(R.string.prepare_rewriting_the_game_s_code)
                        : shadersText(pd, r.titleId);
                Ui.explain(a, code ? Tx.s(R.string.prepare_update_the_pack) : optional ? Tx.s(R.string.prepare_build_all_shaders_now) : Tx.s(R.string.prepare_prepare_shaders), what, why + "Pack: " + pd)
                        .setPositiveButton(code ? Tx.s(R.string.common_prepare_again) : optional ? Tx.s(R.string.prepare_build_shaders) : Tx.s(R.string.common_prepare), (d, w) -> redo.run())
                        .setNeutralButton(Tx.s(R.string.prepare_pick_another_pack_folder), (d, w) -> Folders.pickPack(a))
                        .setNegativeButton(Tx.s(R.string.common_cancel), null).show();
                return;
            }
            Profile prof = Profile.of(r.titleId);   // DumpCheck refused a dump without one
            // A game the shader layer covers (ShaderLibs.layerCovers) is prepared without shaders: it plays when its code is done and builds shaders while it runs; the whole set
            // is a later, optional step (or "Also build all shaders now"). Any other game keeps the whole Prepare
            boolean covered = ShaderLibs.layerCovers(r.titleId, Prefs.layerEnv(a, r.titleId));
            // what it makes, how long, and the space it needs, plainly; where the files go is in Details
            String head = (r.version == 0 ? Tx.s(R.string.prepare_base_game) : "Update " + r.version) + (r.untested ? Tx.s(R.string.prepare_untested_version_try_anyway) : "") + ", " + r.dlc + " DLC.\n\n";
            String space = Tx.s(R.string.prepare_n_nneeds_about_1_5) + String.format(Locale.getDefault(), "%.1f", r.free / 1073741824.0) + Tx.s(R.string.prepare_gb_free_now);
            String text = covered ? head + PrepareText.codeOnlyText(ShaderLibs.minutesText(prof.shaderTotal)) + space
                    : String.format(Locale.getDefault(), "%s, %d DLC.\n\nThe app makes the game's pack from your dump: rewrites the game's code (a few minutes) and translates %,d shaders (%s in the background, best on the charger). You can pause any time.\n\nNeeds about 1.5 GB of free space (%.1f GB free now).",
                    (r.version == 0 ? Tx.s(R.string.prepare_base_game) : "Update " + r.version) + (r.untested ? Tx.s(R.string.prepare_untested_version_try_anyway) : ""), r.dlc, prof.shaderTotal,
                    ShaderLibs.jobMinutesText(prof.shaderTotal), r.free / 1073741824.0);
            String where = "Dump: " + r.dump + "\nWorking files: " + r.work + "\nPack: " + AppStorage.pack(a, r.titleId)
                    + (r.build >= 0 && r.build != r.version ? Tx.s(R.string.prepare_nits_code_is) + prof.buildName(r.build) : "") + (r.buildIds.isEmpty() ? "" : "\nBuild IDs: " + r.buildIds);
            java.util.function.Consumer<Boolean> go = all -> {   // all: the whole shader set in the same job (the checkbox)
                try {
                    Job j = Job.create(r, AppStorage.pack(a, r.titleId), covered && !all);
                    if (packOverride != null) j.pack(packOverride);
                    j.save();
                    Job.remember(a, j.file);
                    job = j;
                    show();
                    startService(j);
                } catch (Exception e) {
                    a.error(Tx.s(R.string.err_cannot_start_reason, e.getMessage()));
                }
            };
            if (start) go.accept(false);
            else askPrepare(r, prof, text, where, covered, go);
        }

        /**
         * Prepare's question for a dump with no pack that fits: Prepare or Cancel, and the two ways to a pack made elsewhere: "Use an existing pack…" (the folder
         * picker; the picked folder becomes the game's, MainActivity.pickPackFor) and "Import pack…" (a .zip from Export pack… or a pack folder into the app's
         * storage, PackUi; the game gets its card first, so the import has a game to go to)
         */
        void askPrepare(DumpCheck.Result r, Profile prof, String text, String where, boolean covered, java.util.function.Consumer<Boolean> go) {
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            Ui.add(box, Ui.body(a, text), 0);
            Ui.Setting all = alsoShaders(covered);
            if (all != null) Ui.add(box, all.view, 8);
            Ui.add(box, Ui.details(a, where), 8);
            Ui.add(box, Ui.small(a, Tx.s(R.string.prepare_have_a_pack_made_elsewhere)), 16);
            AlertDialog[] d = new AlertDialog[1];
            Ui.add(box, Ui.button(a, Tx.s(R.string.prepare_use_an_existing_pack), Ui.Kind.SOFT, v -> { d[0].dismiss(); a.pickPackFor(r.dump, r.titleId); }), 8);
            Ui.add(box, Ui.button(a, Tx.s(R.string.common_import_pack), Ui.Kind.SOFT, v -> { d[0].dismiss(); PackUi.importPick(a, a.cardFor(r.dump, r.titleId)); }), 8);
            d[0] = Ui.sheet(a, Tx.s(R.string.prepare_prepare_name, prof.name), box).setPositiveButton(Tx.s(R.string.common_prepare), (x, w) -> go.accept(all != null && all.toggle.isChecked())).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
        }

        /** Prepare's "Also build all shaders now" box (PrepareText.ALSO_SHADERS()), unticked; null for a game the layer does not cover (its Prepare has the shaders in it) */
        Ui.Setting alsoShaders(boolean covered) {
            if (!covered) return null;
            return new Ui.Setting(a, "\uD83D\uDD28", PrepareText.ALSO_SHADERS(), null).toggle(a, false, null);   // the launcher's switch row (ring, pad focus), not a stock check box
        }

        /** A code update of a pack with no shader library (a game the layer covers): code only, or with the whole shader set when the box is ticked */
        void askCode(DumpCheck.Result r, File pd, String why, Profile prof) {
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            Ui.add(box, Ui.body(a, Tx.s(R.string.prepare_rewriting_code_few_minutes) + " " + PrepareText.optionalShadersText(ShaderLibs.minutesText(prof.shaderTotal))), 0);
            Ui.Setting all = alsoShaders(true);
            Ui.add(box, all.view, 8);
            Ui.add(box, Ui.details(a, why + "Pack: " + pd), 8);
            Ui.sheet(a, Tx.s(R.string.prepare_update_the_pack), box)
                    .setPositiveButton(Tx.s(R.string.common_prepare_again), (x, w) -> adopt(r, pd, true, all.toggle.isChecked()))
                    .setNeutralButton(Tx.s(R.string.prepare_pick_another_pack_folder), (x, w) -> Folders.pickPack(a))
                    .setNegativeButton(Tx.s(R.string.common_cancel), null).show();
        }

        /** A pack that is already there (PackCheck): ready (a done job pointing at it, the game added to the list), or a job that redoes its code, its shaders or both */
        void adopt(DumpCheck.Result r, File packDir, boolean code, boolean shaders) {
            try {
                Job j = Job.adopt(r, packDir, code, shaders);
                j.save();
                Job.remember(a, j.file);
                job = j;
                if (code || shaders) { show(); startService(j); return; }
                Prefs.open(a, "ondevice").edit().putString("added", addedKey(j)).apply();
                Pack p = Pack.read(packDir);
                Game g = new Game(r.titleId, p.name.isEmpty() ? j.name() : p.name, r.dump.getPath(), packDir.getPath());
                Game.put(a, g);   // one card per game (GameList)
                a.refresh();
                show();
                new PadFocus.Builder(a).setTitle(Tx.s(R.string.prepare_ready_to_play)).setMessage(Tx.s(R.string.prepare_pack_fits, packDir.toString())).setPositiveButton(android.R.string.ok, null).show();
            } catch (Exception e) {
                a.error(Tx.s(R.string.err_cannot_use_pack, packDir.toString(), e.getMessage()));
            }
        }

        /**
         * A dump of a build this app does not list (DumpCheck otherBuild): what it holds and what this app supports, plainly, with the build IDs in Details; then
         * Try anyway (the warning says what may go wrong; the job prepares its newest version untested), a pack made elsewhere, or Cancel
         */
        void untested(DumpCheck.Result r, boolean start, File packOverride, boolean again) {
            Profile p = Profile.of(r.titleId);
            Ui.explain(a, p != null ? Tx.s(R.string.prepare_unsupported_version_of, p.name) : Tx.s(R.string.prepare_unsupported_version), r.error + "\n\n" + PrepareText.UNSUPPORTED_WARNING(), r.detail)
                    .setPositiveButton(Tx.s(R.string.prepare_try_anyway_3), (d, w) -> picked(r.dump, start, packOverride, again, false, true))
                    .setNeutralButton(Tx.s(R.string.prepare_use_a_pack), (d, w) -> a.pickPackFor(r.dump, r.titleId))
                    .setNegativeButton(Tx.s(R.string.common_cancel), null).show();
        }

        void confirm(String t, String msg, String yes, Runnable r) {
            new PadFocus.Builder(a).setTitle(t).setMessage(msg).setPositiveButton(yes, (d, w) -> r.run()).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
        }

        private boolean userAsked;   // the player started this Prepare (Add game, Prepare again): its sheet opens when the job starts, an automatic update never opens it

        void startService(Job j) {
            if (userAsked) { userAsked = false; if (!sheetShowing()) h.post(() -> sheet(a)); }
            // Android 13+: the progress notification needs the user's yes; the job runs either way (without it the notification shows only in the task manager)
            if (android.os.Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                a.requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, ASK_NOTIFY);
            a.startForegroundService(new Intent(a, PackService.class).setAction(PackService.ACTION_START).putExtra(PackService.EXTRA_JOB, j.file.getPath()));
            if (!bound) bound = a.bindService(new Intent(a, PackService.class), this, 0);
            resumedAt = SystemClock.elapsedRealtime();
        }

        /** from: who asked (PackService.EXTRA_FROM: launcher or adb), for the logs */
        void pauseJob(String from) {
            a.startService(new Intent(a, PackService.class).setAction(PackService.ACTION_PAUSE).putExtra(PackService.EXTRA_FROM, from));
        }

        /** Go on anyway (PackService.ACTION_GO_ON): the heat pause is lifted while the service runs this job; from: launcher or adb, for the logs */
        void goOnAnyway(String from) {
            a.startService(new Intent(a, PackService.class).setAction(PackService.ACTION_GO_ON).putExtra(PackService.EXTRA_FROM, from));
        }

        void goOrPause() {
            if (job == null) return;
            if (go.getText().toString().equals(Tx.s(R.string.prepare_pause))) { pauseJob("launcher"); return; }
            String missing = Profile.missing(job.titleId(), job.name());
            if (missing != null) { a.error(missing); return; }   // the button is hidden then; a click queued before it was is answered the same way
            if (a.needAccess(job.dump())) return;
            startService(job);
        }

        void play() {
            if (job == null) return;
            if (!ready(job)) { Game g = playsOld(job) ? listedGame(job) : null; if (g != null) a.launch(g); return; }   // the older library while the update runs
            Pack p;
            try { p = Pack.read(job.pack()); } catch (IOException e) { a.error(e.getMessage()); return; }
            if (p.simulated) {
                a.error(Tx.s(R.string.prepare_this_pack_was_made_by));
                return;
            }
            Game g = new Game(job.titleId(), p.name.isEmpty() ? job.name() : p.name, job.dump().getPath(), job.pack().getPath());
            Game.put(a, g);   // one card per game: replaces the entries of the same file or the same title and build (GameList)
            a.refresh();
            a.launch(g);
        }

        /**
         * The job's Manage sheet (⋯): Forget (nothing deleted), Delete working files (the job's own ondevice/ folder), and Delete the prepared pack as well (only
         * a pack this job made, never one it adopted). Each asks first and names every folder that goes; saves are never deleted. While the job runs, all wait.
         */
        void manage() {
            if (job == null) return;
            Job j = job;
            boolean running = alive() && ("running".equals(j.state()) || "paused".equals(j.state()) && !"user".equals(j.reason()) && !"timeout".equals(j.reason()));
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            TextView info = Ui.small(a, j.statusText(alive()) + "\n\nDump: " + j.dump() + "\nWorking files: " + j.dir() + "\nPack: " + j.pack() + "\nLogs: " + new File(j.dir(), "logs"));
            info.setTextIsSelectable(true);
            box.addView(info);
            android.app.AlertDialog[] d = new android.app.AlertDialog[1];
            View line = new View(a);
            line.setBackgroundColor(Ui.col(a, R.color.bn_card_stroke));
            Ui.add(box, line, 20, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
            if (running) Ui.add(box, Ui.small(a, Tx.s(R.string.prepare_pause_first_to_forget_or)), 14);
            if (canSkip(j)) {   // stopped in the shader translation of a game the layer covers: finish with the code only, play now
                Ui.add(box, Ui.small(a, PrepareText.SKIP_SHADERS_TEXT()), 14);
                Ui.add(box, Ui.button(a, PrepareText.SKIP_SHADERS(), Ui.Kind.TONAL, v -> { d[0].dismiss(); skipShaders(j); }), 8, Ui.wrap());
            }
            Button f = Ui.button(a, Tx.s(R.string.prepare_forget_this_job), Ui.Kind.QUIET, v -> { d[0].dismiss(); forget(); });
            f.setEnabled(!running);
            Ui.add(box, f, 10, Ui.wrap());
            Button w = Ui.button(a, Tx.s(R.string.prepare_delete_working_files), Ui.Kind.DANGER, v -> { d[0].dismiss(); deleteWork(j, false); });
            w.setEnabled(!running);
            Ui.add(box, w, 8, Ui.wrap());
            if (ownPack(j) != null) {
                Button pk = Ui.button(a, Tx.s(R.string.prepare_delete_the_prepared_pack_too), Ui.Kind.DANGER, v -> { d[0].dismiss(); deleteWork(j, true); });
                pk.setEnabled(!running);
                Ui.add(box, pk, 8, Ui.wrap());
            }
            d[0] = Ui.sheet(a, Tx.s(R.string.prepare_preparing_name, j.name()), box).setPositiveButton(Tx.s(R.string.common_done), null).show();
        }

        void forget() {
            if (job == null) return;
            Ui.confirmDanger(a, Tx.s(R.string.prepare_forget_this_job_2), Tx.s(R.string.prepare_forget_message, job.dir().toString()), Tx.s(R.string.prepare_forget), () -> {
                Job.remember(a, null);
                job = null;
                show();
            });
        }

        /** The pack folder this job made itself (the app's packs/<id>, an older job's <work>/android-pack, or the simulated engine's ondevice/fake-pack), else null: an adopted pack is the user's */
        File ownPack(Job j) {
            if (!j.ownsPack()) return null;   // a folder that was there before the job (a Mac pack, an adopted one) is the user's
            File p = j.pack(), w = j.work();
            boolean ours = p.equals(AppStorage.pack(a, j.titleId())) || p.getParentFile() != null && (p.getParentFile().equals(w) || p.getParentFile().equals(j.dir()));
            return ours && new File(p, "pack.tsv").isFile() ? p : null;
        }

        static boolean inside(File f, File dir) {
            String fp = f.getAbsolutePath() + "/", dp = dir.getAbsolutePath() + "/";
            return fp.startsWith(dp) || dp.startsWith(fp);
        }

        /** Delete working files (and, when asked, the pack this job made): asked first, never a folder that holds or is inside the dump or the save folder */
        void deleteWork(Job j, boolean pack) {
            File dir = j.dir(), pk = pack ? ownPack(j) : null;
            File save = Folders.save(a, new Game(j.titleId(), j.name(), j.dump().getPath(), j.pack().getPath()));
            java.util.List<File> gone = new java.util.ArrayList<>();
            gone.add(dir);
            if (pk != null && !inside(pk, dir)) gone.add(pk);
            for (File g : gone) {
                if (inside(g, j.dump()) || save != null && inside(g, save)) {
                    a.error(Tx.s(R.string.err_not_deleted_holds, g.toString(), inside(g, j.dump()) ? Tx.s(R.string.prepare_your_dump) : Tx.s(R.string.prepare_your_save_folder)));
                    return;
                }
            }
            StringBuilder m = new StringBuilder(Tx.s(R.string.prepare_deleted_n_the_working_files) + dir + Tx.s(R.string.prepare_rewritten_code_split_and_translate)
                    + Tx.s(R.string.prepare_n_the_split_s_kept));
            if (pk != null) m.append(inside(pk, dir) ? Tx.s(R.string.prepare_with_the_pack_inside_it) : Tx.s(R.string.prepare_n_the_prepared_pack_and) + pk);
            m.append(Tx.s(R.string.prepare_n_nkept_your_dump) + j.dump() + Tx.s(R.string.prepare_and_your_saves)).append(save != null ? " (" + save + ")" : "")
                    .append(pk == null ? Tx.s(R.string.prepare_and_the_pack) + j.pack() + ")" : "").append(Tx.s(R.string.prepare_saves_are_never_deleted_here));
            m.append(pk != null ? Tx.s(R.string.prepare_n_nthe_game_will_not) : Tx.s(R.string.prepare_n_na_new_prepare_starts));
            Ui.confirmDanger(a, pk != null ? Tx.s(R.string.prepare_delete_the_prepared_pack) : Tx.s(R.string.prepare_delete_working_files_2), m, Tx.s(R.string.prepare_delete), () -> {
                android.app.AlertDialog wait = new android.app.AlertDialog.Builder(a).setMessage(Tx.s(R.string.prepare_deleting)).setCancelable(false).show();
                new Thread(() -> {
                    for (File g : gone) Job.deleteTree(g);
                    Job.deleteTree(j.splitScan(a));   // the split's kept scan results, in the app's own storage
                    j.deleteResults(a);   // the shaders step's results, in the app's own storage
                    h.post(() -> {
                        wait.dismiss();
                        Job.remember(a, null);
                        job = null;
                        show();
                        a.refresh();
                        android.widget.Toast.makeText(a, Tx.s(R.string.prepare_deleted_your_dump_and_saves), android.widget.Toast.LENGTH_LONG).show();
                    });
                }, "nxpack-delete").start();
            });
        }
    }
}
