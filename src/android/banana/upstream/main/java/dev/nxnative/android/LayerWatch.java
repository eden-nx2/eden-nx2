package dev.nxnative.android;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.io.File;

/**
 * The run-time shader layer's side in the game's process (android/SHADERS.md, "Shaders the library lacks"): the host writes a request for each shader the game asked for and the
 * prepared library lacks (<code>files/temp/&lt;id&gt;/shader_layer/req/&lt;key&gt;.ctl</code>); this class counts the requests the packer has not settled yet (the keys of
 * <code>built.tsv</code> are settled), shows "Building shaders (N)" while there are some, and starts {@link LayerService} (the packer's process: it has the Python tools) to build
 * them. Nothing else leaves the game's process: no upload, no network.
 */
final class LayerWatch {
    /** The service's class, by name: it is in every APK, but this file stays free of a build-time link to the packer's source set */
    static final String SERVICE = "dev.nxnative.android.LayerService";
    /** A run is asked for every RETRY_MS while requests are pending; after a run that settled none, every RETRY_SLOW_MS (android/SHADERS.md "Build order and cost") */
    static final long TICK_MS = 2000, RETRY_MS = 5000, RETRY_SLOW_MS = 30000, QUOTA_EVERY_MS = 60000;
    /** Runs that ended with requests still pending, one after the other, before the watcher stops starting them (a shader the tools cannot build ends in built.tsv as a failure; this is for a packer that cannot run at all) */
    static final int MAX_EMPTY_RUNS = 5;
    /** The intent extras: the layer's quota (LayerGuard.quota) and the packer's workers (LayerGuard.procs) */
    static final String EXTRA_MAX_BYTES = "max_bytes", EXTRA_PROCS = "procs", EXTRA_WARM_OK = "warm_ok";
    /** The game file and version the packer seeds the layer from at a first start with no library (LayerService.seed) */
    static final String EXTRA_SEED_DUMP = "seed_dump", EXTRA_SEED_VERSION = "seed_version";
    /** The kept library's folder (a sample kept it: ShaderLibs.kept) the service's builds leave the verification of to verify runs, and whether this run is one (shader_layer.py --kept, --verify) */
    static final String EXTRA_KEPT = "kept", EXTRA_VERIFY = "verify";
    /** A verify run starts at most this often, and not before the game has run this long (the verification is the background's: after the picture has settled) */
    static final long VERIFY_EVERY_MS = 60000, VERIFY_AFTER_MS = 120000;

    interface Shown { void show(String text); }

    private final Context ctx;
    private final String titleId, version;
    private final File root;
    private final Shown shown;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean on, quotaNoted;
    private long startedAt;
    private String nNote;
    private long lastStart, quotaAt = -QUOTA_EVERY_MS;
    private int lastSettled = -1, emptyRuns;
    private volatile long used = 0, quota = LayerGuard.QUOTA_MAX;
    private volatile boolean measuring;
    /** What the last tick saw, for the in-game panel's line (LayerStatus.panelLine); read on the main thread, like the tick */
    private int nPending, nBuilt;
    private String nPaused;
    private boolean nGaveUp;

    /** root: the layer folder (LayerDir) */
    LayerWatch(Context c, String titleId, File root, Shown shown) {
        this.ctx = c.getApplicationContext(); this.titleId = titleId; this.root = root; this.shown = shown; this.version = BuildConfig.NX_DMSH_VK_CHAIN;
    }

    private File keptFolder;
    private LayerKept kept;
    private long lastVerify;
    private int nVerify;

    /** The library the game plays is one a sample kept (ShaderLibs.kept): the shaders it covers are verified in the background, not counted as missing (LayerKept) */
    void keep(File lib, String titleId) {
        if (kept != null) kept.close();
        kept = lib == null ? null : new LayerKept(lib, titleId);
        if (kept != null && !kept.ok()) { kept.close(); kept = null; }
        keptFolder = kept == null ? null : lib.getParentFile();
    }

    /** The phone is calm enough for the background's verification: the pace of a game that has run long and has nothing missing (LayerGuard.pace) neither pauses nor notes warmth */
    static boolean verifyCalm(Context c) {
        LayerGuard.Pace p = pace(c, 0, Long.MAX_VALUE);
        return p.pause == null && p.note == null;
    }

    /** The phone may take a background job with no game in front (the sampled check, Prepare): only a low battery holds it; a warm, severe or critical phone runs it with one worker (LayerGuard.pace with no uptime) */
    static boolean backgroundCalm(Context c) { return backgroundPause(c) == null; }

    /** Why a background job with no game in front waits (a low battery), null when it may run: the launcher's line and LayerService's own look at a sampled check use the same rule */
    static String backgroundPause(Context c) { return pace(c, 0, Long.MAX_VALUE).pause; }

    /** Requests that only verify the kept library's entries, as the last tick saw them (0 without a kept library) */
    int verifying() { return nVerify; }

    int pending() { return nPending; }
    int built() { return nBuilt; }
    /** Why the build waits (LayerGuard.pauseReason, or "storage"), null when it may run or nothing is pending */
    String pausedReason() { return nPaused; }
    boolean gaveUp() { return nGaveUp; }

    private boolean seedAsked;

    /**
     * The first start of a game with no shader library: ask the packer once to write the requests of the game's starter set (dm/games/<id>/shader_first.tsv) from the game's own
     * file before the game asks for them, so they are built while its title loads. Nothing happens when the profile has no list or the layer was seeded already (LayerService)
     */
    void seed(String dump, String gameVersion) {
        if (seedAsked || dump == null) return;
        seedAsked = true;
        try {
            ctx.startService(new Intent().setComponent(new ComponentName(ctx, SERVICE)).putExtra(LayerPending.EXTRA_GAME, titleId).putExtra(LayerPending.EXTRA_ROOT, root.getPath())
                    .putExtra(EXTRA_MAX_BYTES, quota).putExtra(EXTRA_PROCS, LayerGuard.procs(Runtime.getRuntime().availableProcessors(), thermal(ctx), LayerGuard.MANY_PENDING, headroom(ctx)))
                    .putExtra(EXTRA_WARM_OK, true).putExtra(EXTRA_SEED_DUMP, dump).putExtra(EXTRA_SEED_VERSION, gameVersion));
            lastStart = android.os.SystemClock.elapsedRealtime();   // (the clock tick() compares it with: the wall clock here kept every later run from ever being due)
        } catch (RuntimeException e) {
            Native.note("shader layer: the starter set cannot be asked for: " + e);
        }
    }

    void start() {
        if (on) return;
        on = true;
        startedAt = android.os.SystemClock.elapsedRealtime();
        LayerPending.selfPackage = ctx.getPackageName();
        if (LayerPending.clearStale(root, System.currentTimeMillis())) Native.note("shader layer: the build marker of a stopped build is removed, builds start at once");
        ui.postDelayed(this::tick, TICK_MS);
    }

    void stop() { on = false; ui.removeCallbacksAndMessages(null); shown.show(null); if (kept != null) { kept.close(); kept = null; } }

    /** PowerManager's thermal status (0 none … 6 shutdown) */
    static int thermal(Context c) {
        android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
        return pm == null ? 0 : pm.getCurrentThermalStatus();
    }

    private static float lastHeadroom = Float.NaN;
    private static long headroomAt = -1;

    /** PowerManager.getThermalHeadroom(10 s): 1.0 is the severe limit; NaN when the phone gives none. Asked every 10 s at most (the system answers NaN to more than 10 calls a minute) */
    static synchronized float headroom(Context c) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (headroomAt >= 0 && now - headroomAt < 10000) return lastHeadroom;
        headroomAt = now;
        android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
        if (pm == null || android.os.Build.VERSION.SDK_INT < 30) return lastHeadroom = Float.NaN;
        try { return lastHeadroom = pm.getThermalHeadroom(10); } catch (RuntimeException e) { return lastHeadroom = Float.NaN; }
    }

    /** The build's pace now (LayerGuard.pace: heat, battery under 15 % and not charging; pending requests and the game's age let a warm phone build with one worker) */
    static LayerGuard.Pace pace(Context c, int pending, long uptimeMs) {
        android.os.BatteryManager bm = c.getSystemService(android.os.BatteryManager.class);
        int pct = bm == null ? -1 : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);   // (Integer.MIN_VALUE when unknown)
        return LayerGuard.pace(thermal(c), headroom(c), pct, bm != null && bm.isCharging(), pending, uptimeMs);
    }

    /** Why a build waits now when the watcher allows a warm phone (warmOk) or not (LayerService's own look: the watcher decided), null when it may run */
    static String pauseReason(Context c, boolean warmOk) {
        return pace(c, warmOk ? LayerGuard.MANY_PENDING : 0, warmOk ? 0 : Long.MAX_VALUE).pause;
    }

    /**
     * The layer's size against its quota, measured off the main thread at most once a minute (it walks the requests and the results): this version's part only
     * (LayerGuard.usage: other versions' folders are not this game's to answer for). Past the quota the folder gets the note LayerGuard.FULL, which the game's page reads
     * to say why no shaders are built and what to do (GameSettings); it goes when the layer is back under its quota
     */
    private void measure(long now) {
        if (measuring || now - quotaAt < QUOTA_EVERY_MS) return;
        quotaAt = now; measuring = true;
        new Thread(() -> {
            try {
                long u = LayerGuard.usage(root, version), q = LayerGuard.quota(root, version, u);
                used = u; quota = q;
                LayerGuard.noteFull(root, u, q);
            } finally { measuring = false; }
        }, "nxlayer-quota").start();
    }

    private void tick() {
        if (!on) return;
        long now = android.os.SystemClock.elapsedRealtime();
        java.util.Set<String> pend = LayerPending.pending(root, version);
        int n = pend.size();
        nVerify = 0;
        if (kept != null && n > 0) {   // requests only to verify a kept library's entries are not missing shaders: no label, no early build (verify runs below)
            java.util.Set<String> vo = kept.verifyOnly(new File(root, "req"), LayerPending.requested(root), LayerPending.builtKeys(root, version));
            vo.retainAll(pend);
            nVerify = vo.size(); n -= nVerify;
        }
        if (n > 0) measure(now);
        LayerPending.clearStale(root, System.currentTimeMillis());   // (a build marker of a process that is gone)
        LayerGuard.Pace pace = n > 0 ? pace(ctx, n, now - startedAt) : null;
        String paused = pace == null ? null : pace.pause;
        nNote = pace == null ? null : pace.note;
        boolean full = n > 0 && used > quota;
        if (full && !quotaNoted) {   // once a run: the request files stay (they are small); Storage › Shader cache built while playing frees the folder
            quotaNoted = true;
            Native.note("[RED] shader layer: it holds " + (used >> 20) + " MB, past its quota of " + (quota >> 20) + " MB (at least 2 GB, room for its whole library; never less than 1 GB free on the phone): no more shaders are built this run. Free space, or Storage > Shader cache built while playing, or Import a library");
        }
        boolean gaveUp = emptyRuns >= MAX_EMPTY_RUNS;   // (a watcher that gave up, e.g. the packer was killed by battery or RAM-saver limits, no longer claims shaders are being built)
        boolean busy = LayerPending.building(root, System.currentTimeMillis());   // the packer is still on the last run: no new start, and no empty run counted
        nPending = n; nBuilt = LayerPending.built(root, version); nGaveUp = gaveUp; nPaused = n == 0 ? null : full ? LayerStatus.STORAGE : paused;
        long wait = emptyRuns > 0 ? RETRY_SLOW_MS : RETRY_MS;
        int retryIn = busy || lastStart == 0 || emptyRuns == 0 ? 0 : (int) Math.max(1, (wait - (now - lastStart) + 999) / 1000);
        // (Off hides the label only: the build below goes on; a screen that throws while it draws the label must not take the tick, and so the build, with it)
        try { shown.show(gaveUp ? null : LayerStatus.shown(Prefs.layerLabel(ctx), n, nPaused, lastStart != 0 || busy, busy, retryIn, nNote)); } catch (RuntimeException e) { android.util.Log.w("nxnative", "shader label: " + e); }
        if (LayerStatus.buildDue(n, gaveUp, full, paused, busy, now, lastStart, wait)) {
            int s = LayerPending.settled(root, version);
            if (lastStart != 0 && s <= lastSettled) emptyRuns++; else emptyRuns = 0;   // the last run settled none
            lastStart = now; lastSettled = s;
            if (emptyRuns < MAX_EMPTY_RUNS) {
                int procs = LayerGuard.procs(Runtime.getRuntime().availableProcessors(), thermal(ctx), n, headroom(ctx));
                try {
                    ctx.startService(new Intent().setComponent(new ComponentName(ctx, SERVICE)).putExtra(LayerPending.EXTRA_GAME, titleId).putExtra(LayerPending.EXTRA_ROOT, root.getPath())
                            .putExtra(EXTRA_MAX_BYTES, quota).putExtra(EXTRA_PROCS, procs).putExtra(EXTRA_WARM_OK, nNote != null)
                            .putExtra(EXTRA_KEPT, keptFolder == null ? null : keptFolder.getPath()));
                    Native.note("shader layer: " + n + " requests, the packer is asked to build them (" + procs + (procs == 1 ? " worker)" : " workers)"));
                } catch (RuntimeException e) {   // no service (a build without the packer), or the system refuses it: the list stays, the game goes on
                    Native.note("shader layer: the packer cannot be started: " + e);
                    emptyRuns = MAX_EMPTY_RUNS;
                }
            }
        } else if (n == 0) { emptyRuns = 0; lastSettled = -1; }
        // The verification of a kept library: only when nothing is missing, the phone is calm (no pause, no warm note) and the game has run a while; a few shaders a run
        if (nVerify > 0 && n == 0 && keptFolder != null && !busy && now - startedAt >= VERIFY_AFTER_MS && now - lastVerify >= VERIFY_EVERY_MS && verifyCalm(ctx)) {
            lastVerify = now;
            try {
                ctx.startService(new Intent().setComponent(new ComponentName(ctx, SERVICE)).putExtra(LayerPending.EXTRA_GAME, titleId).putExtra(LayerPending.EXTRA_ROOT, root.getPath())
                        .putExtra(EXTRA_MAX_BYTES, quota).putExtra(EXTRA_PROCS, 1).putExtra(EXTRA_WARM_OK, false).putExtra(EXTRA_KEPT, keptFolder.getPath()).putExtra(EXTRA_VERIFY, true));
                Native.note("shader layer: " + nVerify + " played shaders of the kept library to verify, the packer is asked for a few");
            } catch (RuntimeException e) { /* no packer: the library plays as it is */ }
        }
        ui.postDelayed(this::tick, TICK_MS);
    }
}
