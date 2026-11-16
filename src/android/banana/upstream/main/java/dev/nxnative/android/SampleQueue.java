package dev.nxnative.android;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The launcher's queue of sampled-keep checks (android/SHADERS.md "Sampled keep", Ondevice.sampledKeep): one process-wide line of the packs whose older shader library
 * wants the quick check, one check at a time. A pack enters when its card is evaluated and has a candidate; the front one runs when the launcher says nothing holds it
 * ({@link #holdReason}: a game, a Prepare job, the first seconds of the app, a hot or low phone); a check that ends with any verdict, or that failed to start
 * {@link #MAX_START_FAILURES} times, marks the pack as tried for this process; a start the lock refused ("busy": another job or build holds it) marks nothing. The player
 * taking Play stops the running check, which goes back to the FRONT ({@link #playTakesLead}); a check the player skips ({@link #cancel}) leaves and is not asked again.
 * What outlives the process (Ondevice keeps it on disk with the helpers below): a skip, or a check given up, is a record (shaders/.sample-skip-&lt;V&gt;.json, {@link #skipHolds}:
 * this APK, {@link #SKIP_MS}); an interrupted check (Play, the app closed or killed) resumes from what it translated (shader_sample.py) but waits {@link #BACKOFF_MS} after the
 * interruption ({@link #interruptedAt}, {@link #backoffLeft}); the line meanwhile starts the next pack that is ready ({@link #startNext(java.util.function.Predicate)}).
 * <p>
 * Keys are opaque strings (Ondevice uses the pack folder's path). No Android classes: SampleQueueJvmTest (dm/tools/tests/test_android_samplequeue.py) runs it on the JVM.
 */
final class SampleQueue {
    /** A check that cannot start (the service did not run it, not for the lock) this many times is given up for this process */
    static final int MAX_START_FAILURES = 3;
    /** The app has run this long before the first check starts, so opening the launcher does not spin up work at once */
    static final long SETTLE_MS = 20_000;
    /** Wait before a refused or failed start is tried again */
    static final long RETRY_MS = 30_000;

    private final Deque<String> queue = new ArrayDeque<>();
    private String running;
    private final Set<String> tried = new HashSet<>(), cancelled = new HashSet<>();
    private final Map<String, Integer> failures = new HashMap<>();
    private boolean paused;

    /** A pack needs the check: queued at the end unless it is queued or running, tried, or skipped. → whether it was added */
    synchronized boolean enqueue(String key) {
        if (key == null || tried.contains(key) || cancelled.contains(key) || key.equals(running) || queue.contains(key)) return false;
        queue.addLast(key);
        return true;
    }

    /** Whether the pack is waiting or running (its card is the check's card; the shader update waits for the verdict) */
    synchronized boolean active(String key) { return key != null && (key.equals(running) || queue.contains(key)); }

    synchronized boolean isRunning(String key) { return key != null && key.equals(running); }

    synchronized String running() { return running; }

    synchronized boolean tried(String key) { return tried.contains(key); }

    synchronized boolean cancelled(String key) { return cancelled.contains(key); }

    /** Whether any check waits or runs */
    synchronized boolean any() { return running != null || !queue.isEmpty(); }

    /** The queue, the running check first, then the waiting ones in order */
    synchronized List<String> order() {
        List<String> l = new ArrayList<>();
        if (running != null) l.add(running);
        l.addAll(queue);
        return l;
    }

    /** Checks ahead of this pack (the running one and the waiting ones before it); -1 when it is not waiting */
    synchronized int ahead(String key) {
        if (key == null || key.equals(running)) return -1;
        int i = 0;
        for (String k : queue) { if (k.equals(key)) return i + (running != null ? 1 : 0); i++; }
        return -1;
    }

    /** A line that changes whenever a card's state changes (who runs, who waits in what order): the launcher rebuilds the cards when it differs */
    synchronized String signature() { return (running == null ? "-" : running) + "|" + String.join(",", queue); }

    /** The pack at the front starts now: it leaves the line and runs. → its key, null while paused, while one runs or when none waits. The caller starts it ({@link #startFailed} when it cannot) */
    synchronized String startNext() { return startNext(k -> true); }

    /**
     * The first pack of the line that is ready (not held back after an interruption, {@link #backoffLeft}) starts now; the ones held keep their places. → its key, null while
     * paused, while one runs or when none is ready
     */
    synchronized String startNext(java.util.function.Predicate<String> ready) {
        if (paused || running != null || queue.isEmpty()) return null;
        for (String k : queue) {
            if (!ready.test(k)) continue;
            queue.remove(k);
            running = k;
            return k;
        }
        return null;
    }

    /**
     * The running check did not start (the service refused or never ran it): back to the front. counts false when the lock or the phone refused it (busy, hot): no mark, no count.
     * counts true: after {@link #MAX_START_FAILURES} the pack is marked tried (given up for this process). → whether it was given up
     */
    synchronized boolean startFailed(String key, boolean counts) {
        if (key == null || !key.equals(running)) return false;
        running = null;
        if (counts) {
            int n = failures.merge(key, 1, Integer::sum);
            if (n >= MAX_START_FAILURES) { tried.add(key); return true; }
        }
        queue.addFirst(key);
        return false;
    }

    /** The running check ran and ended (any verdict): the pack is tried for this process */
    synchronized void ended(String key) {
        if (key != null && key.equals(running)) running = null;
        if (key != null) { tried.add(key); queue.remove(key); }
    }

    /**
     * The player starts a game: the running check (any pack's) is to stop and waits again at the FRONT, no mark. → the key to stop, null when none runs. While a game runs no check starts
     * (the launcher does not ask {@link #startNext}); after it the queue resumes by itself
     */
    synchronized String playTakesLead() {
        String r = running;
        if (r == null) return null;
        running = null;
        queue.addFirst(r);
        return r;
    }

    /** The player skips this pack's check: it leaves the line and is not asked again in this process. → whether it was the running one (the caller stops it) */
    synchronized boolean cancel(String key) {
        if (key == null) return false;
        cancelled.add(key);
        queue.remove(key);
        boolean was = key.equals(running);
        if (was) running = null;
        return was;
    }

    /** Holds the line: nothing starts until {@link #resume} (a running check is not touched) */
    synchronized void pause() { paused = true; }

    synchronized void resume() { paused = false; }

    synchronized boolean paused() { return paused; }

    /**
     * Why no check may start now, null when one may: the app is young (sinceStartMs under {@link #SETTLE_MS}), a game runs, a Prepare job or another build holds the packer, the
     * battery low (not calm: heat does not hold it, it runs with one worker), or an earlier refusal is still cooling (nowMs before notBeforeMs)
     */
    static String holdReason(long sinceStartMs, boolean gameRunning, boolean packerBusy, boolean calm, long nowMs, long notBeforeMs) {
        if (gameRunning) return "a game runs";
        if (packerBusy) return "the packer is busy";
        if (sinceStartMs < SETTLE_MS) return "the app has just started";
        if (!calm) return "the battery is low";
        if (nowMs < notBeforeMs) return "waiting to try again";
        return null;
    }

    // ---- what outlives the process (android/SHADERS.md "Sampled keep", Resume, skip and backoff) ----

    /** A check that was interrupted (Play stopped it, the app was closed or killed) waits this long after the interruption before it runs again (it resumes: shader_sample.py) */
    static final long BACKOFF_MS = 10 * 60_000L;
    /** A pack the player skipped, or whose check failed to start {@link #MAX_START_FAILURES} times, is not queued again for this long (or until the APK or the tools change) */
    static final long SKIP_MS = 7L * 24 * 3600_000L;
    /** In the check's work folder (shader_sample.py RESUME_FILE, STOPPED_FILE, PROGRESS_FILE) */
    static final String RESUME = "resume.json", STOPPED = "stopped.json", PROGRESS = "progress.json";
    static final String SKIPPED = "skip", FAILED = "failed";
    /** The running check's marker (LayerPending.BUILDING; spelled here so the queue builds on its own on the JVM) */
    static final String MARKER = ".building";

    /** The pack's record that it is not to be checked for a while: shaders/.sample-skip-&lt;version&gt;.json (version: this APK's DMSH_VK_CHAIN) */
    static java.io.File skipFile(java.io.File packDir, String version) { return new java.io.File(packDir, "shaders/.sample-skip-" + version + ".json"); }

    /** The record's text: why, when (ms), which APK, and the counts of the check when known (no keys) */
    static String skipText(String why, long atMs, String apk, int done, int total) {
        return "{\"kind\": \"sample-skip\", \"why\": \"" + why + "\", \"at_ms\": " + atMs + ", \"apk\": \"" + apk.replaceAll("[^0-9A-Za-z._+-]", "") + "\", \"done\": " + done + ", \"total\": " + total + "}\n";
    }

    /** Whether a record still holds: of this APK, at most {@link #SKIP_MS} old (one dated more than a day ahead of the clock holds nothing) */
    static boolean skipHolds(String json, String apk, long nowMs) {
        if (json == null || apk == null) return false;
        java.util.regex.Matcher a = java.util.regex.Pattern.compile("\"apk\"\\s*:\\s*\"([^\"]*)\"").matcher(json), t = java.util.regex.Pattern.compile("\"at_ms\"\\s*:\\s*(\\d{1,18})").matcher(json);
        if (!a.find() || !t.find() || !a.group(1).equals(apk.replaceAll("[^0-9A-Za-z._+-]", ""))) return false;
        long at = Long.parseLong(t.group(1));
        return at <= nowMs + 24 * 3600_000L && nowMs - at < SKIP_MS;
    }

    /** Writes the record (by rename); a failure is ignored (the pack is then asked again at the next start) */
    static void writeSkip(java.io.File packDir, String version, String why, long atMs, String apk, int done, int total) {
        java.io.File f = skipFile(packDir, version), tmp = new java.io.File(f.getPath() + ".tmp");
        try {
            java.nio.file.Files.write(tmp.toPath(), skipText(why, atMs, apk, done, total).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException | RuntimeException e) { tmp.delete(); }
    }

    /** Whether the pack's record holds now (skipped or given up for this APK and tools) */
    static boolean skipped(java.io.File packDir, String version, String apk, long nowMs) {
        java.io.File f = skipFile(packDir, version);
        if (!f.isFile() || f.length() > 4096) return false;
        try { return skipHolds(new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8), apk, nowMs); } catch (java.io.IOException e) { return false; }
    }

    /**
     * When the check in this work folder was last interrupted (ms), 0 when it has no history (no resume.json: never ran, or a verdict cleared it): the newest of the stop's time
     * (stopped.json), and the times the running check last wrote its progress, its key or its marker (a check killed with the app leaves those)
     */
    static long interruptedAt(java.io.File work) {
        if (work == null || !new java.io.File(work, RESUME).isFile()) return 0;
        long at = 0;
        for (String n : new String[] {RESUME, STOPPED, PROGRESS, MARKER}) at = Math.max(at, new java.io.File(work, n).lastModified());
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"at_ms\"\\s*:\\s*(\\d{1,18})").matcher(new String(java.nio.file.Files.readAllBytes(new java.io.File(work, STOPPED).toPath()), java.nio.charset.StandardCharsets.UTF_8));
            if (m.find()) at = Math.max(at, Long.parseLong(m.group(1)));
        } catch (java.io.IOException | RuntimeException e) { /* no stop recorded */ }
        return at;
    }

    /** How long a check interrupted at interruptedAtMs still waits (0: it may run); a check with no history waits for nothing */
    static long backoffLeft(long interruptedAtMs, long nowMs) {
        if (interruptedAtMs <= 0 || interruptedAtMs > nowMs + BACKOFF_MS) return 0;   // (a time ahead of the clock: not held)
        return Math.max(0, interruptedAtMs + BACKOFF_MS - nowMs);
    }

    // ---- the card's words ----

    static final String CHECKING = "Checking shaders", WAITING = "Waiting to check shaders", UP_TO_DATE = "Shaders up to date";
    /** The words of a check that goes on from what an earlier, interrupted run translated */
    static final String RESUMING = "Resuming shader check", WAITING_RESUME = "Waiting to resume shader check";

    /** "Waiting to check shaders (3 ahead)", "(next)" for the one after the running check, "(1 ahead)" ... */
    static String waitingText(int ahead) { return waitingText(ahead, false); }

    /** As above; resuming: "Waiting to resume shader check (next)" for a check an earlier run began */
    static String waitingText(int ahead, boolean resuming) {
        String w = resuming ? WAITING_RESUME : WAITING;
        return ahead <= 0 ? w + " (next)" : w + " (" + ahead + " ahead)";
    }

    /** The small line of a check held back after an interruption: "Resumes in about 7 min" (at least 1), "" when it is not held */
    static String backoffText(long leftMs) { return leftMs <= 0 ? "" : "Resumes in about " + Math.max(1, (leftMs + 59_999) / 60_000) + " min"; }

    /** Phases of the check's progress: the requests are written from the game's files, then translated */
    static final String SEED = "seed", TRANSLATE = "translate";

    /** A reading of the check's progress: phase, done, total (total 0: not known yet) */
    static final class Progress {
        final String phase;
        final int done, total, reused;
        Progress(String phase, int done, int total) { this(phase, done, total, 0); }
        Progress(String phase, int done, int total, int reused) { this.phase = phase; this.done = done; this.total = total; this.reused = reused; }
    }

    /** The progress.json shader_sample.py writes ({"phase": "seed", "done": 12, "total": 300}); null when it is not that */
    static Progress parse(String json) {
        if (json == null) return null;
        java.util.regex.Matcher p = java.util.regex.Pattern.compile("\"phase\"\\s*:\\s*\"(\\w+)\"").matcher(json),
                d = java.util.regex.Pattern.compile("\"done\"\\s*:\\s*(\\d+)").matcher(json), t = java.util.regex.Pattern.compile("\"total\"\\s*:\\s*(\\d+)").matcher(json);
        if (!p.find() || !d.find() || !t.find()) return null;
        java.util.regex.Matcher r = java.util.regex.Pattern.compile("\"reused\"\\s*:\\s*(\\d+)").matcher(json);
        try { return new Progress(p.group(1), Integer.parseInt(d.group(1)), Integer.parseInt(t.group(1)), r.find() ? Integer.parseInt(r.group(1)) : 0); } catch (NumberFormatException e) { return null; }
    }

    /** The log line "seed N M" (shader_seed.py) as a progress, null for any other line: the reading while no progress file exists yet */
    static Progress parseSeedLine(String line) {
        if (line == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^seed (\\d+) (\\d+)$").matcher(line.trim());
        return m.find() ? new Progress(SEED, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))) : null;
    }

    /** The bar, 0 to 1000: the requests written take the first fifth, the translation the rest; 0 when nothing is known */
    static int bar(Progress p) {
        if (p == null || p.total <= 0) return 0;
        int share = Math.min(1000, (int) ((long) p.done * 1000 / p.total));
        return SEED.equals(p.phase) ? share / 5 : 200 + share * 4 / 5;
    }

    /** The card's line while the check runs: "Checking shaders", with what is done when known ("Checking shaders · reading the game's files 120 of 400", "· translating 33 of 400") */
    static String runningText(Progress p) { return runningText(p, false); }

    /** As above; a check that goes on from an earlier run's translations (resuming, or the progress counts some reused) says "Resuming shader check" */
    static String runningText(Progress p, boolean resuming) {
        String head = resuming || (p != null && p.reused > 0) ? RESUMING : CHECKING;
        if (p == null || p.total <= 0) return head + "…";
        return head + " · " + (SEED.equals(p.phase) ? "reading the game's files " : "translating ") + p.done + " of " + p.total;
    }
}
