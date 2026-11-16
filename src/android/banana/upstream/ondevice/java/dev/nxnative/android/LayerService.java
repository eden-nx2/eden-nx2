package dev.nxnative.android;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.io.File;

/**
 * Builds the shaders a running game asked for and its prepared library lacks (android/SHADERS.md, "Shaders the library lacks"): started by {@link LayerWatch} in the game's process,
 * it runs in the packer's process (:pack) and calls the tools with the same engine the Prepare job uses: <code>dm/gfx/tools/shader_layer.py --game &lt;id&gt; --root
 * &lt;layer folder&gt; --build</code>, which writes the layer library the host maps and its <code>built.tsv</code>. The prepared library is never touched. One build at a
 * time, and none while a Prepare job exists ({@link PackLock}: the process's one lock over its Python; the watcher asks again); a job that starts while a build runs stops it
 * after the shader in hand and waits for it. Runs on a thread of its own and ends when the build ends; while it builds, the folder holds the marker
 * {@link LayerPending#BUILDING}, touched every {@link LayerPending#BEAT_MS} by a thread of its own, so the watcher neither asks again nor counts the run as one that settled
 * nothing, and a game's start does not take the build's half-written library for a stopped build's (LayerDir.tidy); a killed process leaves a marker whose pid is gone: the game's watcher removes it at its start, and it counts at most
 * {@link LayerPending#BUILDING_MS} for an other app's. No build while the phone
 * has its battery under 15 % and not charging (LayerGuard.pauseReason, checked here too: the watcher's last look may be seconds old), and none past the layer's quota
 * (--max-bytes). Workers: the watcher's (LayerGuard.procs: 2 on 6 or more cores when the phone is not warm, else 1).
 * A sampled-keep check ({@link #EXTRA_SAMPLE_OLD}) runs with no game in front, so it runs as a foreground service (dataSync, as PackService; Ondevice starts it with
 * startForegroundService): a plain started service of an app in the background is stopped by Android about a minute later ("Stopping service due to app idle"), the
 * :pack process is then cached and the freezer stops it with the tool mid-run, holding the packer's lock (2026-11-16 phone test: the check "hung" with its CPU time frozen
 * and no stack dump as soon as the launcher left the screen). A layer build runs while its game is in front, which keeps the app's uid active.
 */
public final class LayerService extends Service {
    static final String EXTRA_GAME = LayerPending.EXTRA_GAME, EXTRA_ROOT = LayerPending.EXTRA_ROOT;
    /** A sampled keep's check instead of a layer build (shader_sample.py): the older version, the pack, the game file and its version, the sample size; the root is its work folder */
    static final String EXTRA_SAMPLE_OLD = "sample_old", EXTRA_SAMPLE_PACK = "sample_pack", EXTRA_SAMPLE_DUMP = "sample_dump", EXTRA_SAMPLE_VERSION = "sample_version", EXTRA_SAMPLE_N = "sample_n";
    /** How many of the sample come from the game's starter set (shader_sample.py --first) */
    static final String EXTRA_SAMPLE_FIRST = "sample_first";
    /** The launcher asks the running sampled-keep check to stop (the player starts a game, or skips the check): it stops after the shader in hand and writes no verdict (shader_sample.py EXIT_STOPPED) */
    static final String EXTRA_STOP = "sample_stop";
    /** A file in the check's work folder that says why the service did not run a check it was asked for: "busy" (a Prepare job or another build holds the packer), "calm" (the battery is low), else "other". The launcher reads it: a refusal is no try ({@link SampleQueue}) */
    static final String REFUSED = "refused.txt";
    private static final String TAG = "nxlayer";
    private static volatile PackEngine sampleEngine;
    private static volatile boolean sampleStop;
    /** A build or check thread runs: a start this service refuses meanwhile must not stopSelf(startId), which would end the service (and its foreground) under that thread */
    private static volatile boolean working;
    private static final String CHANNEL = "sample";
    private static final int NOTE = 0x5a3;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra(EXTRA_STOP, false)) {   // (while a check runs, its own thread ends the service)
            sampleStop = true;
            PackEngine e = sampleEngine;
            if (e != null) e.interrupt();
            if (!working) stopSelf(startId);   // (nothing runs: the service this intent created ends)
            return START_NOT_STICKY;
        }
        boolean isSample = intent != null && intent.getStringExtra(EXTRA_SAMPLE_OLD) != null;
        // startForegroundService must be answered by startForeground, whatever follows; refused (the app went to the background meanwhile): the check does not run, as a busy packer
        if (isSample && !foreground()) { refused(intent, intent.getStringExtra(EXTRA_ROOT), "other"); end(startId); return START_NOT_STICKY; }
        String game = intent == null ? null : intent.getStringExtra(EXTRA_GAME), root = intent == null ? null : intent.getStringExtra(EXTRA_ROOT);
        if (game == null || root == null || !game.matches("[0-9A-F]{16}")) { end(startId); return START_NOT_STICKY; }
        // a sampled check runs with no game in front: the background rule (LayerWatch.backgroundCalm: only a low battery holds it). With the watcher's warm rule
        // (uptime 0: a game in front) a severe phone refused every check the launcher's line had let start (phone test 2026-11-16, status 3: "layer build waits: phone is hot")
        String paused = isSample ? LayerWatch.backgroundPause(this) : LayerWatch.pauseReason(this, intent.getBooleanExtra(LayerWatch.EXTRA_WARM_OK, false));
        if (paused != null) { refused(intent, root, "calm"); Log.i(TAG, "layer build waits: " + paused); end(startId); return START_NOT_STICKY; }
        if (!PackLock.tryLayer()) { refused(intent, root, "busy"); Log.i(TAG, "layer build waits: " + (PackLock.jobRunning() ? "a Prepare job runs" : "a build runs")); end(startId); return START_NOT_STICKY; }
        working = true;
        sampleStop = false;
        final String seedDump = intent.getStringExtra(LayerWatch.EXTRA_SEED_DUMP), seedVersion = intent.getStringExtra(LayerWatch.EXTRA_SEED_VERSION);
        final String keptDir = intent.getStringExtra(LayerWatch.EXTRA_KEPT), sampleOld = intent.getStringExtra(EXTRA_SAMPLE_OLD);
        final boolean verify = intent.getBooleanExtra(LayerWatch.EXTRA_VERIFY, false);
        final String samplePack = intent.getStringExtra(EXTRA_SAMPLE_PACK), sampleDump = intent.getStringExtra(EXTRA_SAMPLE_DUMP), sampleVersion = intent.getStringExtra(EXTRA_SAMPLE_VERSION);
        final int sampleN = intent.getIntExtra(EXTRA_SAMPLE_N, ShaderLibs.SAMPLE_DEFAULT), sampleFirst = intent.getIntExtra(EXTRA_SAMPLE_FIRST, ShaderLibs.SAMPLE_FIRST_DEFAULT);
        long maxBytes = intent.getLongExtra(LayerWatch.EXTRA_MAX_BYTES, LayerGuard.QUOTA_MIN);
        int procs = Math.max(1, Math.min(LayerGuard.MAX_PROCS, intent.getIntExtra(LayerWatch.EXTRA_PROCS, 1)));
        File dir = new File(root), mark = new File(dir, LayerPending.BUILDING);
        Thread t = new Thread(() -> {
            Thread beat = null;
            try {
                dir.mkdirs();
                try { java.nio.file.Files.write(mark.toPath(), LayerPending.markerText(android.os.Process.myPid(), getPackageName()).getBytes(java.nio.charset.StandardCharsets.US_ASCII)); } catch (java.io.IOException e) { Log.w(TAG, "no build marker: " + e); }
                beat = new Thread(() -> {   // the marker says "a build runs" only while it is fresh: touched for as long as the build runs, however long that is
                    try {
                        while (true) { Thread.sleep(LayerPending.BEAT_MS); mark.setLastModified(System.currentTimeMillis()); }
                    } catch (InterruptedException e) { /* the build ended */ }
                }, "nxlayer-beat");
                beat.setDaemon(true);
                beat.start();
                if (sampleOld != null && samplePack != null && sampleDump != null) {
                    // the quick check runs with no game in front: the CPU stays awake for it (a stopped screen froze the process at its first minute, 2026-11-16 phone test)
                    android.os.PowerManager.WakeLock wl = null;
                    try {
                        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                        if (pm != null) { wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "nxnative:sample"); wl.acquire(30 * 60 * 1000L); }
                    } catch (RuntimeException e) { Log.w(TAG, "sampled keep: no wake lock: " + e); }
                    try { sample(game, dir, new File(samplePack), new File(sampleDump), sampleVersion, sampleOld, sampleN, sampleFirst, procs); } finally { if (wl != null && wl.isHeld()) wl.release(); }
                    return;
                }
                if (seedDump != null) { try { seed(game, dir, new File(seedDump), seedVersion, procs); } catch (java.io.IOException | RuntimeException e) { Log.w(TAG, "starter set not seeded", e); } }   // (the build below goes on without it)
                build(game, dir, maxBytes, procs, keptDir, verify);
            } catch (RuntimeException | java.io.IOException e) { Log.e(TAG, "layer build failed", e); }
            finally {
                if (beat != null) beat.interrupt();
                mark.delete();
                working = false;
                PackLock.endLayer();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();   // (not stopSelf(startId): a start refused meanwhile has a later id)
            }
        }, "nxlayer");
        t.start();
        return START_NOT_STICKY;
    }

    /** A start this service does not run: the service ends unless a build or check runs in it (then its own thread ends it) */
    private void end(int startId) {
        if (working) return;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(startId);
    }

    /** The check's foreground (dataSync): false when Android refuses it (a start from the background, or the type's daily time used up) */
    private boolean foreground() {
        try {
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            nm.createNotificationChannel(new android.app.NotificationChannel(CHANNEL, "Checking shaders", android.app.NotificationManager.IMPORTANCE_LOW));
            android.app.Notification n = new android.app.Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_banana_mono).setOnlyAlertOnce(true)
                    .setCategory(android.app.Notification.CATEGORY_PROGRESS).setContentTitle("Checking shaders").setContentText("A quick check of the shaders after an update").build();
            startForeground(NOTE, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            return true;
        } catch (RuntimeException e) {   // ForegroundServiceStartNotAllowedException, or the type's time limit used up
            Log.w(TAG, "sampled keep: no foreground: " + e);
            return false;
        }
    }

    /** Android 14+'s daily dataSync limit: the check stops after the shader in hand (no verdict, asked again later) and the service ends within the seconds Android gives */
    @Override public void onTimeout(int startId, int fgsType) {
        sampleStop = true;
        PackEngine e = sampleEngine;
        if (e != null) e.interrupt();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** A sampled-keep request this service did not run: the reason goes into the work folder for the launcher ({@link #REFUSED}) */
    private static void refused(Intent intent, String root, String why) {
        if (intent.getStringExtra(EXTRA_SAMPLE_OLD) == null) return;
        try {
            File d = new File(root);
            d.mkdirs();
            java.nio.file.Files.write(new File(d, REFUSED).toPath(), why.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (java.io.IOException | RuntimeException e) { Log.w(TAG, "no refusal note: " + e); }
    }

    private static final java.util.Map<String, android.os.ParcelFileDescriptor> nupFds = new java.util.HashMap<>();

    /**
     * The starter set (dm/games/<id>/shader_first.tsv, the shaders a first start asks for first): their requests written from the game's own file by shader_seed.py, once for a layer
     * and a tools version (the marker .seeded-&lt;DMSH_VK_CHAIN&gt;), so the build below starts on them while the title loads. A game with no list, no readable file or a failing tool
     * is just not seeded: its requests then come from play as before
     */
    private void seed(String game, File root, File dump, String gameVersion, int procs) throws java.io.IOException {
        PackEngine engine = PackEngine.create(this);   // (first: it sets PackNative's context, which repo() needs)
        if (engine.simulated()) return;
        File list = new File(PackNative.repo(), "dm/games/" + game + "/shader_first.tsv"), done = new File(root, ".seeded-" + BuildConfig.NX_DMSH_VK_CHAIN);
        if (!list.isFile() || done.exists() || PackLock.layerStopAsked()) return;
        long version;
        try { version = Long.parseLong(gameVersion == null ? "0" : gameVersion.trim()); } catch (NumberFormatException e) { return; }
        String nupFd = "";
        if (Nup.picked(dump)) {
            try {
                synchronized (nupFds) {
                    android.os.ParcelFileDescriptor p = nupFds.get(dump.getAbsolutePath());
                    if (p == null) nupFds.put(dump.getAbsolutePath(), p = PickedNup.descriptor(this, dump));
                    nupFd = p.getFd() + ":" + dump.getAbsolutePath();
                }
            } catch (java.io.IOException e) { Log.w(TAG, "starter set: " + e.getMessage()); return; }
        }
        PackEngine.setenv("DM_GAME", game);
        PackEngine.setenv("J", "1");
        PackEngine.setenv("DM_NUP_FD", nupFd);
        PackEngine.setenv("TMPDIR", getCacheDir().getPath());
        PackEngine.setenv("DM_PACKER_COMMIT", BuildConfig.NX_COMMIT);
        root.mkdirs();
        long t0 = System.currentTimeMillis();
        int code = engine.run("dm/gfx/tools/shader_seed.py", new String[] {"--game", game, "--root", root.getPath(), "--first", list.getPath(),
                "--romfs", DumpCheck.versionPath(dump, version, "romfs"), "--main", DumpCheck.versionPath(dump, version, "exefs/main")}, new File(root, "seed.log").getPath());
        Log.i(TAG, "shader_seed.py: exit " + code + " in " + (System.currentTimeMillis() - t0) / 1000 + " s (log " + new File(root, "seed.log") + ")");
        if (code == 0) { try { done.createNewFile(); } catch (java.io.IOException e) { /* seeded again next start: the requests that exist are skipped */ } }
    }

    /**
     * The quick check of a sampled keep (android/SHADERS.md "Sampled keep"): <code>shader_sample.py</code> draws a sample of the older library's shaders, has these tools translate them
     * from the game's own file and takes the older library over as this version's when every entry is the same. One run, in the work folder (removed after it); its result is
     * the pack's <code>shaders/.sample-&lt;V&gt;.json</code> and, when accepted, the library <code>shaders/v-&lt;V&gt;/</code> with its <code>sampled-keep.json</code>. Nothing is
     * deleted from the pack; a difference leaves the pack as it was and the update goes on the other ways
     */
    private void sample(String game, File work, File pack, File dump, String gameVersion, String old, int n, int first, int procs) throws java.io.IOException {
        PackEngine engine = PackEngine.create(this);
        if (engine.simulated()) return;
        long version;
        try { version = Long.parseLong(gameVersion == null ? "0" : gameVersion.trim()); } catch (NumberFormatException e) { return; }
        Profile prof = Profile.of(game);
        if (prof == null || PackLock.layerStopAsked()) return;
        String nupFd = "";
        if (Nup.picked(dump)) {
            synchronized (nupFds) {
                android.os.ParcelFileDescriptor p = nupFds.get(dump.getAbsolutePath());
                if (p == null) nupFds.put(dump.getAbsolutePath(), p = PickedNup.descriptor(this, dump));
                nupFd = p.getFd() + ":" + dump.getAbsolutePath();
            }
        }
        PackEngine.setenv("DM_GAME", game);
        PackEngine.setenv("J", "1");
        PackEngine.setenv("DM_NUP_FD", nupFd);
        PackEngine.setenv("XLATE", "libdmsh_xlate.so");
        PackEngine.setenv("SPIRV_VAL", "libSPIRV-Tools-shared.so");
        PackEngine.setenv("DMSH_VK_XLATE_J", Integer.toString(procs));
        PackEngine.setenv("DMSH_VK_PROCS", Integer.toString(procs));
        PackEngine.setenv("TMPDIR", getCacheDir().getPath());
        PackEngine.setenv("DM_PACKER_COMMIT", BuildConfig.NX_COMMIT);
        work.mkdirs();
        if (sampleStop) { Log.i(TAG, "sampled keep: stopped before it started"); return; }
        long t0 = System.currentTimeMillis();
        sampleEngine = engine;
        int code;
        try {
            code = engine.run("dm/gfx/tools/shader_sample.py", new String[] {"--game", game, "--pack", pack.getPath(), "--old", old, "--abi", BuildConfig.NX_DMSH_VK_ABI, "--same-abis", BuildConfig.NX_DMSH_VK_ABI_SAME, "--inputs", prof.shaderInputs,
                "--work", work.getPath(), "--romfs", DumpCheck.versionPath(dump, version, "romfs"), "--main", DumpCheck.versionPath(dump, version, "exefs/main"), "--n", Integer.toString(n), "--first", Integer.toString(first)},
                new File(work, "sample.log").getPath());
        } finally { sampleEngine = null; }
        Log.i(TAG, "shader_sample.py: exit " + code + " in " + (System.currentTimeMillis() - t0) / 1000 + " s (" + (code == 0 ? "accepted" : code == 10 ? "differences, not accepted" : code == 12 || code == PackEngine.INTERRUPTED ? "stopped, no verdict" : "not kept") + ")");
        String line = LayerPending.lastLineWith(new File(work, "sample.log"), "sampled keep: ");
        if (line != null) Log.i(TAG, line);
        // a verdict (the result file this run wrote): nothing of the check is kept, the work folder goes (shader_sample.py removed its translations already). A stop or a failure keeps
        // it: the next run resumes from its translations (resume.json), and its times hold that run back (SampleQueue.interruptedAt)
        File result = ShaderLibs.sampleResultFile(pack, BuildConfig.NX_DMSH_VK_CHAIN);
        if (result.isFile() && result.lastModified() >= t0 - 2000) deleteTree(work);
    }

    private static void deleteTree(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File x : l) deleteTree(x);
        f.delete();
    }

    private void build(String game, File root, long maxBytes, int procs, String keptDir, boolean verify) throws java.io.IOException {
        PackEngine engine = PackEngine.create(this);
        if (engine.simulated()) return;
        PackEngine.setenv("DM_GAME", game);   // (shader_split.py reads the profile when it is imported)
        PackEngine.setenv("XLATE", "libdmsh_xlate.so");
        PackEngine.setenv("SPIRV_VAL", "libSPIRV-Tools-shared.so");
        PackEngine.setenv("DMSH_VK_XLATE_J", Integer.toString(procs));     // the game is running: one or two translator calls at a time and as many workers (LayerGuard.procs)
        PackEngine.setenv("DMSH_VK_PROCS", Integer.toString(procs));
        PackEngine.setenv("TMPDIR", getCacheDir().getPath());
        PackEngine.setenv("DM_PACKER_COMMIT", BuildConfig.NX_COMMIT);
        File log = new File(root, "layer.log");
        root.mkdirs();
        if (PackLock.layerStopAsked()) { Log.i(TAG, "layer build not started: a Prepare job starts"); return; }   // (a job that claimed the lock since: it waits for this run to end)
        long t0 = System.currentTimeMillis();
        java.util.List<String> argv = new java.util.ArrayList<>(java.util.Arrays.asList("--game", game, "--root", root.getPath(), "--build", "--max-bytes", Long.toString(maxBytes)));
        if (keptDir != null) { argv.add("--kept"); argv.add(keptDir); if (verify) argv.add("--verify"); }   // (a kept library: its verification runs apart from the missing shaders' builds)
        int code = engine.run("dm/gfx/tools/shader_layer.py", argv.toArray(new String[0]), log.getPath());
        Log.i(TAG, "shader_layer.py: exit " + code + " in " + (System.currentTimeMillis() - t0) / 1000 + " s (log " + log + ")");
        String timing = LayerPending.lastTiming(log);   // the run's own split (shader_layer.py PhaseClock): one line in logcat per run, so a phone run shows where the time goes
        if (timing != null) Log.i(TAG, timing);
    }
}
