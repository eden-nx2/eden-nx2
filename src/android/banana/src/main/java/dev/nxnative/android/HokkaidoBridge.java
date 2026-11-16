// SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.nxnative.android;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Hokkaido runs Banana Native as an engine behind its own UI: what a game needs before it plays, preparing it
 * (PackService's job, as Ondevice starts it), its progress, and starting it (GameActivity, as FrontendLaunch does).
 * Banana Native's own screens stay out of the player's way. Its checks and job code are used as they are, so a
 * sync of upstream/ brings their changes; keep this in step with FrontendLaunch.ready and Ondevice.Panel.
 */
public final class HokkaidoBridge {
    private HokkaidoBridge() {}

    /** NxApp.onCreate's work: Hokkaido's application runs it in every process. */
    public static void install(Context context) {
        PickedNup.install(context);
    }

    /**
     * Banana Native's status for a game (its profile's readiness.tsv status row: "playable", "bring-up"), or
     * null when this Banana Native has no profile for it and cannot run it.
     */
    public static String status(String titleId) {
        Profile p = Profile.of(titleId == null ? "" : titleId.toUpperCase(Locale.ROOT));
        return p == null ? null : p.status;
    }

    /** Embedded, Banana Native never updates itself: Hokkaido's releases carry it (Updater.sources). */
    static boolean embedded() {
        return true;
    }

    /** What a game needs before it plays in Banana Native. */
    public static final class Plan {
        public static final int READY = 0, PREPARE = 1, RESUME = 2, RUNNING = 3, PROBLEM = 4;

        public final int kind;
        public final String title;
        public final String message;
        public final String detail;
        /** A build Banana Native does not list: preparing it anyway is possible (PrepareText.ANYWAY_WARNING). */
        public final boolean canTryAnyway;

        final String dump;
        final Game game;
        final DumpCheck.Result result;
        final Job job;
        final File packDir;
        final boolean code, shaders;

        Plan(int kind, String title, String message, String detail, boolean canTryAnyway, String dump, Game game,
             DumpCheck.Result result, Job job, File packDir, boolean code, boolean shaders) {
            this.kind = kind;
            this.title = title;
            this.message = message;
            this.detail = detail == null ? "" : detail;
            this.canTryAnyway = canTryAnyway;
            this.dump = dump;
            this.game = game;
            this.result = result;
            this.job = job;
            this.packDir = packDir;
            this.code = code;
            this.shaders = shaders;
        }

        /** The ready game's title ID (READY), else empty. */
        public String titleId() {
            return game != null ? game.titleId : "";
        }

        static Plan problem(String title, String message, String detail) {
            return new Plan(PROBLEM, title, message, detail, false, null, null, null, null, null, false, false);
        }
    }

    /** The plan for a game folder or file. Reads files: call it off the main thread. */
    public static Plan plan(Context c, String dumpPath, boolean tryAnyway) {
        File dump = new File(Frontend.normal(dumpPath));
        if (!dump.exists()) return Plan.problem("Game not found", dump + " is not there.", null);
        if (!dump.canRead()) return Plan.problem("Cannot read the game", dump + " cannot be read. Check that All files access is on.", null);

        String id;
        try {
            id = Pack.playableIdOf(dump);
        } catch (IOException e) {
            return Plan.problem("Cannot read the game", e.getMessage(), null);
        }
        String missing = Profile.missing(id, "This game");
        if (missing != null) return Plan.problem("Not supported by Banana Native", missing, null);

        Game ready = ready(c, dump, id);
        if (ready != null) return new Plan(Plan.READY, ready.name, "", null, false, dump.getPath(), ready, null, null, null, false, false);

        // A job for this dump: running, or one to resume (Ondevice.Panel.checked).
        File existing = null;
        boolean any = tryAnyway;
        try {
            File jf = Job.fileOf(DumpCheck.workOf(dump, Pack.titleIdOf(dump)));
            if (jf.isFile()) {
                existing = jf;
                try { any |= Job.read(jf).tryAnyway(); } catch (IOException e) { /* another format: a new job replaces it */ }
            }
        } catch (IOException e) {
            // DumpCheck says what is wrong
        }
        DumpCheck.Result r = DumpCheck.check(dump, existing == null, any);
        if (!r.ok()) {
            Profile p = Profile.of(id);
            String title = r.otherBuild ? "Untested version" + (p != null ? " of " + p.name : "") : "Cannot prepare this game";
            String message = r.otherBuild ? r.error + "\n\n" + PrepareText.ANYWAY_WARNING : r.error;
            return new Plan(Plan.PROBLEM, title, message, r.detail, r.otherBuild, dump.getPath(), null, null, null, null, false, false);
        }
        Profile prof = Profile.of(r.titleId);
        String name = prof != null ? prof.name : r.titleId;
        if (existing != null) {
            try {
                Job j = Job.read(existing);
                String s = j.state();
                boolean same = j.dump().getPath().equals(r.dump.getPath()) && j.version() == r.version;
                if (same && "running".equals(s) && PackServiceWatch.alive(c)) {
                    return new Plan(Plan.RUNNING, name, j.statusText(true), null, false, dump.getPath(), null, r, j, null, false, false);
                }
                if (same && (s.equals("running") || s.equals("paused") || s.equals("failed") || s.equals("pending"))) {
                    return new Plan(Plan.RESUME, name, j.statusText(false) + "\n\nIt goes on from where it stopped.", j.errorDetail(),
                                    false, dump.getPath(), null, r, j, null, false, false);
                }
            } catch (IOException e) {
                // a damaged job.json: a new job replaces it
            }
        }

        // A pack that is already there: what fits is kept (Ondevice.Panel.checked).
        File packDir = Folders.packFor(c, r.dump, r.titleId);
        try {
            Pack p = packDir == null ? null : Pack.read(packDir);
            if (p != null && !p.simulated && p.titleId.equals(r.titleId)) {
                String build = PackCheck.blocking(c, p, r.dump);
                String library = PackCheck.shaders(packDir, r.titleId);
                boolean code = build != null;
                boolean shaders = library != null;
                String what = code && shaders
                        ? "Rewrites the game's code (a few minutes) and " + Ondevice.shadersText(packDir, r.titleId)
                        : code ? "Rewrites the game's code (a few minutes, in the background)." : Ondevice.shadersText(packDir, r.titleId);
                String why = (build != null ? build + "\n\n" : "") + (library != null ? library : "");
                if (!code && !shaders) what = "Its pack fits: nothing needs preparing.";
                return new Plan(Plan.PREPARE, name, what, why.trim(), false, dump.getPath(), null, r, null, packDir, code, shaders);
            }
        } catch (IOException e) {
            // no usable pack: the whole job
        }
        String text = String.format(Locale.getDefault(),
                "%s, %d DLC.\n\nBanana Native makes the game's pack from your dump: it rewrites the game's code (a few minutes) and translates %,d shaders (%s in the background, best on the charger). You can pause any time.\n\nNeeds about 1.5 GB of free space (%.1f GB free now).",
                (r.version == 0 ? "Base game" : "Update " + r.version) + (r.untested ? " (untested version)" : ""), r.dlc,
                prof != null ? prof.shaderTotal : 0, ShaderLibs.jobMinutesText(prof != null ? prof.shaderTotal : 0), r.free / 1073741824.0);
        String where = "Game: " + r.dump + "\nWorking files: " + r.work + "\nPack: " + AppStorage.pack(c, r.titleId);
        return new Plan(Plan.PREPARE, name, text, where, false, dump.getPath(), null, r, null, null, true, true);
    }

    /**
     * Starts preparing: a new job, one that redoes what an existing pack lacks, or the stopped job resumed. False
     * when nothing needed redoing (a pack that fits is adopted at once): plan again, it is ready.
     */
    public static boolean prepare(Context c, Plan plan) throws Exception {
        Job j;
        if (plan.kind == Plan.RESUME || plan.kind == Plan.RUNNING) {
            j = plan.job;
        } else if (plan.kind == Plan.PREPARE && plan.packDir != null) {
            j = Job.adopt(plan.result, plan.packDir, plan.code, plan.shaders);
            j.save();
            if (!plan.code && !plan.shaders) {
                Job.remember(c, j.file);
                addGame(c, j);
                return false;
            }
        } else if (plan.kind == Plan.PREPARE) {
            j = Job.create(plan.result, AppStorage.pack(c, plan.result.titleId));
            j.save();
        } else {
            throw new IllegalStateException("nothing to prepare");
        }
        Job.remember(c, j.file);
        c.startForegroundService(new Intent(c, PackService.class).setAction(PackService.ACTION_START)
                .putExtra(PackService.EXTRA_JOB, j.file.getPath()));
        return true;
    }

    /** Pauses the job that runs now (PackService.ACTION_PAUSE). */
    public static void pause(Context c) {
        c.startService(new Intent(c, PackService.class).setAction(PackService.ACTION_PAUSE).putExtra(PackService.EXTRA_FROM, "hokkaido"));
    }

    /**
     * Before a ready game starts: Hokkaido's driver for it (a driver zip, installed into Banana Native's own
     * gpu_drivers/ when it is new or changed; empty: the system's) and Hokkaido's save folder for it, which both
     * engines then use. A save Banana Native kept in its own folder moves in once, when Hokkaido's is still empty
     * (it stays where it was too). Reads and writes files: call it off the main thread.
     */
    public static void prepareStart(Context c, Plan plan, String driverZip, String saveDir, String window) throws IOException {
        String id = plan.game.titleId;
        // The render size Hokkaido's resolution scale gives (Game resolution: a "<width>x<height>" or "original"),
        // when it changed since the last start: a size picked in the game's quick settings stays until then.
        String given = "hokkaido_window_" + id;
        if (window != null && !window.isEmpty() && !window.equals(Prefs.of(c).getString(given, null))) {
            Prefs.gameWindow(c, id, window);
            Prefs.of(c).edit().putString(given, window).commit();
        }
        // Banana Native's RAM saver (its Game settings, which Hokkaido does not show) for a game that runs out of memory
        // on a phone of 12 GB or less, unless it was set: Ocarina of Time swapped 4.7 GB out on the Galaxy S26.
        if (RAM_SAVER_LOW_RAM.contains(id) && Prefs.lowRam(c) && !Prefs.of(c).contains(Prefs.gameKey("ram_saver", id))) {
            Prefs.ramSaver(c, id, true);
        }
        // Full vegetation (its Game settings, which Hokkaido does not show) unless it was set: the vegetation it leaves out
        // otherwise looked missing in Ocarina of Time.
        if (!Prefs.of(c).contains(Prefs.gameKey("full_vegetation", id))) Prefs.fullVegetation(c, id, true);
        if (driverZip == null || driverZip.isEmpty()) {
            GpuDriver.choice(c, id, GpuDriver.SYSTEM);
        } else {
            GpuDriver.choice(c, id, installedDriver(c, new File(driverZip)).dir.getName());
        }
        if (saveDir == null || saveDir.isEmpty()) return;
        File shared = new File(saveDir);
        if (!shared.isDirectory() && !shared.mkdirs()) throw new IOException("cannot create the save folder " + shared);
        // Only the first time the game uses Hokkaido's folder: later an empty one is a deleted save, never refilled.
        File own = AppStorage.save(c, id);
        boolean first = Prefs.folder(c, "save_" + id) == null;
        if (first && isEmpty(shared) && !own.getAbsoluteFile().equals(shared.getAbsoluteFile()) && !isEmpty(own)) copyTree(own, shared);
        Prefs.folder(c, "save_" + id, shared.getPath());
    }

    /** The games that take Banana Native's RAM saver by default on a phone of 12 GB or less (prepareStart). */
    private static final List<String> RAM_SAVER_LOW_RAM = List.of("0400486024DEC000");

    /** The driver unpacked from a zip, unpacked again only when the zip changed since (by its size and time). */
    private static GpuDriver.Info installedDriver(Context c, File zip) throws IOException {
        if (!zip.isFile()) throw new IOException("The GPU driver " + zip + " is not there: pick another one in Hokkaido.");
        String key = "hokkaido_driver_" + zip.getPath(), stamp = zip.length() + ":" + zip.lastModified();
        String known = Prefs.of(c).getString(key, "");
        if (known.startsWith(stamp + ":")) {
            String name = known.substring(stamp.length() + 1);
            for (GpuDriver.Info i : GpuDriver.installed(c)) if (i.dir.getName().equals(name)) return i;
        }
        GpuDriver.Info i = GpuDriver.install(c, zip);
        Prefs.of(c).edit().putString(key, stamp + ":" + i.dir.getName()).commit();
        return i;
    }

    private static boolean isEmpty(File dir) {
        String[] l = dir.list();
        return l == null || l.length == 0;
    }

    private static void copyTree(File from, File to) throws IOException {
        File[] l = from.listFiles();
        if (l == null) return;
        if (!to.isDirectory() && !to.mkdirs()) throw new IOException("cannot create " + to);
        for (File f : l) {
            File t = new File(to, f.getName());
            if (f.isDirectory()) {
                copyTree(f, t);
            } else {
                java.nio.file.Files.copy(f.toPath(), t.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * The game Banana Native's game process still holds ({title ID, name}), or null. Each game has a host of its own
     * and the process holds one: a game left without Stop the game keeps it, and another game's start is refused
     * there ("is running, stop it first").
     */
    public static String[] runningGame(Context c) {
        if (gameProcess(c) == null) return null;
        String title = null, name = null;
        try {
            for (String line : java.nio.file.Files.readAllLines(ExitWatch.record(c).toPath())) {
                if (line.startsWith("title=")) title = line.substring(6);
                else if (line.startsWith("name=")) name = line.substring(5);
            }
        } catch (IOException e) {
            // no record: an unknown game
        }
        return new String[] {title == null ? "" : title, name == null ? "" : name};
    }

    /** Closes Banana Native's game process when it holds this game (its save is about to change under it). */
    public static void closeGame(Context c, String titleId) {
        String[] running = runningGame(c);
        if (running != null && running[0].equalsIgnoreCase(titleId)) stopRunningGame(c);
    }

    /** Closes Banana Native's game process (what a game there has not saved is lost); waits up to 3 s for it to go. */
    public static void stopRunningGame(Context c) {
        Integer pid = gameProcess(c);
        if (pid == null) return;
        android.os.Process.killProcess(pid);
        for (int i = 0; i < 30 && gameProcess(c) != null; i++) {
            try { Thread.sleep(100); } catch (InterruptedException e) { return; }
        }
    }

    private static Integer gameProcess(Context c) {
        android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        List<android.app.ActivityManager.RunningAppProcessInfo> ps = am == null ? null : am.getRunningAppProcesses();
        if (ps != null) for (android.app.ActivityManager.RunningAppProcessInfo p : ps) if (p.processName.endsWith(":game")) return p.pid;
        return null;
    }

    /** Hokkaido's theme for the game screen's menu (HokkaidoInGame.Screen): its style resources, and its dark mode. */
    static final String EXTRA_THEMES = "hokkaido_themes", EXTRA_NIGHT = "hokkaido_night";

    /**
     * Starts a ready game (Plan.READY) in Banana Native's game process, as FrontendLaunch.play does, with Hokkaido's
     * theme (its style resources in order, and its dark mode) for the in-game menu there. Not as a frontend's game: the
     * game screen joins Hokkaido's own task, so its end shows Hokkaido (a frontend's would send that task back).
     */
    public static void play(Context c, Plan plan, int[] themes, boolean night) {
        Game g = plan.game;
        c.startActivity(new Intent(c, GameActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("title_id", g.titleId).putExtra("name", g.name).putExtra("game", g.gameDir)
                .putExtra("pack", g.packDir)
                .putExtra(EXTRA_THEMES, themes).putExtra(EXTRA_NIGHT, night));
    }

    /** A job's progress, for Hokkaido's progress screen. */
    public static final class Progress {
        public final String name;
        public final String state;
        public final String text;
        public final double overall;
        public final boolean alive;
        public final boolean ready;
        public final String error;

        Progress(Job j, boolean alive, boolean ready) {
            this.name = j.name();
            this.state = j.state();
            this.text = j.statusText(alive);
            this.overall = j.overall();
            this.alive = alive;
            this.ready = ready;
            this.error = "failed".equals(j.state()) ? j.errorDetail() : "";
        }
    }

    public interface Listener {
        void onProgress(Progress progress);
    }

    /**
     * Follows the remembered job: PackService's state messages while it runs (Ondevice.Panel), else job.json once a
     * second. A finished job's game goes into Banana Native's game list (Ondevice.Panel.addGame), so it plays next time.
     */
    public static final class Watch implements ServiceConnection {
        private final Context context;
        private final Listener listener;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Messenger client = new Messenger(new Handler(Looper.getMainLooper(), this::onMessage));
        private Messenger service;
        private boolean bound;
        private boolean alive;
        private boolean stopped;
        private Job job;

        public Watch(Context context, Listener listener) {
            this.context = context.getApplicationContext();
            this.listener = listener;
        }

        public void start() {
            stopped = false;
            bound = context.bindService(new Intent(context, PackService.class), this, 0);
            tick();
        }

        public void stop() {
            stopped = true;
            handler.removeCallbacksAndMessages(null);
            if (service != null) {
                Message m = Message.obtain(null, PackService.MSG_UNREGISTER);
                m.replyTo = client;
                try { service.send(m); } catch (RemoteException e) { /* the service is gone */ }
            }
            if (bound) {
                context.unbindService(this);
                bound = false;
            }
            service = null;
        }

        private void tick() {
            if (stopped) return;
            if (service == null) {
                File f = Job.remembered(context);
                try {
                    if (f != null && f.isFile()) job = Job.read(f);
                } catch (IOException e) {
                    // being renamed over: keep the last one
                }
                if (!bound) bound = context.bindService(new Intent(context, PackService.class), this, 0);
                report();
            }
            handler.postDelayed(this::tick, 1000);
        }

        private boolean onMessage(Message m) {
            if (m.what != PackService.MSG_STATE) return false;
            Bundle d = m.getData();
            Job j = Job.of(new File(d.getString("file", "")), d.getString("job", "{}"));
            if (j != null) job = j;
            alive = d.getBoolean("alive");
            report();
            return true;
        }

        private void report() {
            if (job == null || stopped) return;
            boolean ready = ready(job);
            if (ready && !alive) addGame(context, job);
            listener.onProgress(new Progress(job, alive, ready));
        }

        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            service = new Messenger(b);
            Message m = Message.obtain(null, PackService.MSG_REGISTER);
            m.replyTo = client;
            try { service.send(m); } catch (RemoteException e) { service = null; }
        }

        @Override public void onServiceDisconnected(ComponentName n) {
            service = null;
            alive = false;
            report();
        }
    }

    /** Whether PackService's process runs (a job that says running without it was stopped). */
    private static final class PackServiceWatch {
        static boolean alive(Context c) {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<android.app.ActivityManager.RunningAppProcessInfo> ps = am == null ? null : am.getRunningAppProcesses();
            if (ps != null) for (android.app.ActivityManager.RunningAppProcessInfo p : ps) if (p.processName.endsWith(":pack")) return true;
            return false;
        }
    }

    /** Play needs the pack and its shader library of this APK's version (Ondevice.Panel.ready). */
    private static boolean ready(Job j) {
        if (!"done".equals(j.state()) || j.apkChanged()) return false;
        try {
            Pack p = Pack.read(j.pack());
            return Pack.libraryProblem(j.pack(), j.titleId()) == null && p.unfinishedModule() == null && p.titleId.equals(j.titleId());
        } catch (IOException e) {
            return false;
        }
    }

    /** The finished job's game into the list with its pack (Ondevice.Panel.addGame). */
    private static void addGame(Context c, Job j) {
        for (Game g : Game.load(c)) {
            if (GameList.samePath(g.gameDir, j.dump().getPath()) && GameList.samePath(g.packDir, j.pack().getPath())) return;
        }
        Pack p;
        try { p = Pack.read(j.pack()); } catch (IOException e) { return; }
        if (p.simulated) return;
        Game g = new Game(j.titleId(), p.name.isEmpty() ? j.name() : p.name, j.dump().getPath(), j.pack().getPath());
        Game.put(c, g);
        String picked = Folders.pickedPack(c, g.titleId);
        if (picked != null && !picked.equals(g.packDir)) Folders.pickedPack(c, g.titleId, g.packDir);
    }

    /** FrontendLaunch.ready: the game's card when it can start now, else null. */
    private static Game ready(Context c, File game, String id) {
        List<Game> list = Game.load(c);
        List<Integer> same = GameList.matches(list, id, game.getPath(), Game.key(game.getPath(), id), Game::key);
        Game card = same.isEmpty() ? null : list.get(same.get(0));
        File packDir = card != null ? new File(card.packDir) : Folders.packFor(c, game, id);
        if (packDir == null || SaveUi.busy(id)) return null;
        Pack p;
        try {
            p = Pack.read(packDir);
            if (!p.titleId.equalsIgnoreCase(id)) return null;
            if (Nup.isNup(game) ? p.romfsPath(game) == null : p.romfs(game) == null) return null;
        } catch (IOException e) {
            return null;
        }
        if (PackCheck.blocking(c, p, game) != null || Pack.libraryProblem(packDir, id) != null) return null;
        if (card != null) return card;
        Game g = new Game(id, p.name.isEmpty() ? id : p.name, game.getPath(), packDir.getPath());
        return Game.put(c, g);
    }
}
