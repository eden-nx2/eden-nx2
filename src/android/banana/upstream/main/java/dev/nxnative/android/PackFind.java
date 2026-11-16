package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Add game's two searches, with no Android classes (the JVM test, android/app/src/jvmtest, runs them on the Mac):
 * <ul>
 * <li>{@link #candidates} and {@link #best}: the pack folders that may hold a game's pack, and the best of them. The candidates, in this order: the picked pack
 *     folder (Folders), the app's packs/&lt;id&gt; (AppStorage), the places beside and inside the game as before (Pack.find), then, for the folder holding the game
 *     (the game folder itself for a dump folder, the .nup's folder for a .nup) and its ancestors up to {@link #LEVELS} levels and never above the storage volume's
 *     root: every &lt;dir&gt;/&lt;id&gt;-work/&lt;any name&gt;/ with a pack.tsv (a pack made on the Mac and copied as xlate2-pack, android-pack-1.8.0, …) and
 *     &lt;dir&gt;/android-pack. Only a stat per folder; at most {@link #MAX_PACKS} pack.tsv are read. {@link #best} keeps the packs of this title whose code runs
 *     with the game (the Judge: PackCheck.blocking on the phone), and of those one with this APK's shader library ({@link #READY}) before one that the automatic
 *     restamp or delta shader update brings current ({@link #UPDATE}); within a tier the first candidate wins (the picked folder, then the app's, then the nearest).
 *     A pack whose code is of another game build is never chosen.</li>
 * <li>{@link #gamesIn}: the games a picked folder holds (Add game on a folder adds all of them): each .nup in it and each dump subfolder (base/exefs/main.npdm
 *     or exefs/main.npdm) one level deep, not work or pack folders.</li>
 * </ul>
 */
final class PackFind {
    static final int LEVELS = 3;        // ancestors of the game's folder searched (the folder itself, then up to three above it)
    static final int MAX_PACKS = 48;    // pack.tsv files read for one game at most
    static final int READY = 0, UPDATE = 1, ON_DEMAND = 2;

    private PackFind() {}

    /** Whether a pack's code runs with the game: null when it does, else why not (PackCheck.blocking: unfinished, being rewritten, another game build) */
    interface Judge { String code(Pack p); }

    /** The chosen pack folder, its pack, and its tier (READY: plays now; UPDATE: its shaders need the automatic restamp or delta update; ON_DEMAND: plays now, its shaders are built as it runs) */
    static final class Found {
        final File dir;
        final Pack pack;
        final int tier;

        Found(File dir, Pack pack, int tier) { this.dir = dir; this.pack = pack; this.tier = tier; }
    }

    /**
     * The folders that may hold the game's pack, in the order best() prefers them, each once; only those with a pack.tsv (a stat each), at most MAX_PACKS.
     * picked, app: the picked pack folder and the app's packs/&lt;id&gt; (null: none). root: the storage volume's root (volumeRoot), never searched above
     */
    static List<File> candidates(File picked, File app, File game, String id, File root) {
        Set<File> c = new LinkedHashSet<>();
        if (picked != null) c.add(picked.getAbsoluteFile());
        if (app != null) c.add(app.getAbsoluteFile());
        game = game.getAbsoluteFile();
        File old = Pack.find(game, id);   // the places of before (beside and inside the game) keep their place in the order
        if (old != null) c.add(old.getAbsoluteFile());
        if (game.isDirectory()) {
            c.add(new File(game, "android-pack"));
            addWork(c, new File(game, id + "-work"));
        }
        File dir = game.isDirectory() ? game : game.getParentFile();
        File top = root == null ? null : root.getAbsoluteFile();
        for (int level = 0; dir != null && level <= LEVELS; level++) {
            addWork(c, new File(dir, id + "-work"));
            c.add(new File(dir, "android-pack"));
            if (dir.equals(top)) break;
            dir = dir.getParentFile();
        }
        List<File> out = new ArrayList<>();
        for (File f : c) {
            if (out.size() >= MAX_PACKS) break;
            if (new File(f, "pack.tsv").isFile()) out.add(f);
        }
        return out;
    }

    /** Every subfolder of an &lt;id&gt;-work folder, sorted by name (a pack there may have any name); not the half-written ones (.partial, .replaced, .restamp-…) */
    private static void addWork(Set<File> c, File work) {
        File[] subs = work.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
        if (subs == null) return;
        Arrays.sort(subs);
        for (File s : subs) c.add(s);
    }

    /**
     * The best pack of the candidates for the game of title id, null when none plays or can be brought current automatically. dump: the game is a dump Prepare
     * starts from (Pack.isDump), so a delta shader update can run from it (Ondevice.autoUpdate); a restamp needs no dump
     */
    static Found best(List<File> candidates, String id, boolean dump, Judge judge) { return best(candidates, id, dump, judge, false); }

    /** As above; layer: the shader layer covers the game (ShaderLibs.layerCovers), so a pack with no library of this version still plays (ON_DEMAND) and is chosen when no better one is there */
    static Found best(List<File> candidates, String id, boolean dump, Judge judge, boolean layer) {
        Found update = null, demand = null;
        for (File d : candidates) {
            Pack p;
            try { p = Pack.read(d); } catch (IOException e) { continue; }
            if (p.simulated || !p.titleId.equals(id)) continue;
            int tier = shaderTier(d, id, dump, layer);
            if (tier < 0 || tier == UPDATE && update != null || tier == ON_DEMAND && demand != null) continue;
            if (judge.code(p) != null) continue;   // its code would not run with this game (another build, unfinished): never chosen
            if (tier == READY) return new Found(d, p, READY);
            if (tier == UPDATE) update = new Found(d, p, UPDATE);
            else demand = new Found(d, p, ON_DEMAND);
        }
        return update != null ? update : demand;
    }

    /**
     * READY when the pack has this APK's shader library (Pack.libraryProblem null), UPDATE when it has an older one of the game that becomes this version's by
     * its header alone (a "stamp" row of the profile names it: Pack.restamp) or, for a dump, that the profile's delta manifest knows (Pack.deltaShaders), else -1
     */
    static int shaderTier(File packDir, String id, boolean dump) { return shaderTier(packDir, id, dump, false); }

    /**
     * As above; layer: the shader layer covers the game, so a pack that has no library of this version at all (one of other tables is not a gap the layer fills) is
     * ON_DEMAND where it would be -1: it plays, with the shaders it has and the ones the layer builds as the game runs
     */
    static int shaderTier(File packDir, String id, boolean dump, boolean layer) {
        int t = shaderTierBlocking(packDir, id, dump);
        return t < 0 && layer && new File(packDir, "pack.tsv").isFile() && Pack.libraryOf(packDir) == null && Profile.of(id) != null ? ON_DEMAND : t;
    }

    private static int shaderTierBlocking(File packDir, String id, boolean dump) {
        if (Pack.libraryProblem(packDir, id) == null) return READY;
        Profile prof = Profile.of(id);
        if (prof == null) return -1;
        List<String> older = ShaderLibs.older(packDir, id, BuildConfig.NX_DMSH_VK_CHAIN);
        for (String row : prof.stamp.split(";")) {   // only named here: Pack.restamp checks the hashes when the card is shown (no big file is read now)
            String[] c = row.trim().split(":");
            if (c.length == 3 && older.contains(c[0])) return UPDATE;
        }
        return dump && Pack.deltaShaders(packDir, id) >= 0 ? UPDATE : -1;
    }

    /** The root of the storage volume a path is on: /storage/emulated/&lt;user&gt; or /storage/&lt;volume ID&gt;; null for a path elsewhere (then only LEVELS bounds the search) */
    static File volumeRoot(File f) {
        for (File d = f.getAbsoluteFile(); d != null; d = d.getParentFile()) {
            File p = d.getParentFile();
            if (p == null) return null;
            String pp = p.getPath();
            if (pp.equals("/storage/emulated") || pp.equals("/storage") && !d.getName().equals("emulated") && !d.getName().equals("self")) return d;
        }
        return null;
    }

    /**
     * The games a picked folder holds, sorted by name: its .nup files (and files named .NUP, which Add game then asks to rename), and its subfolders with
     * base/exefs/main.npdm or exefs/main.npdm, one level deep; not an &lt;id&gt;-work folder (extraction and work files) and not a pack folder (pack.tsv)
     */
    static List<File> gamesIn(File dir) {
        List<File> out = new ArrayList<>();
        File[] l = dir.listFiles();
        if (l == null) return out;
        Arrays.sort(l);
        for (File f : l) {
            if (f.getName().startsWith(".")) continue;
            if (f.isFile() && (Nup.isNup(f) || Nup.wrongCase(f))) {
                if (!companion(f, l)) out.add(f);
            }
            else if (f.isDirectory() && !f.getName().matches("(?i)[0-9a-f]{16}-work") && !new File(f, "pack.tsv").isFile() && Pack.npdmOf(f) != null) out.add(f);
        }
        return out;
    }

    /**
     * Whether a file of the list is a part of a game that another file of the list stands for, so that Add game lists that one only and it finds the rest beside
     * it (Nup.companions): a DLC NSP; an NSP of a version that a newer NSP of the game outranks, or of a game with a .nup beside it that has its base; a .nup with no
     * base package (an update or DLC of its own) beside a .nup of its game that has one. A .nup with a base package stands for itself, whatever else is beside it
     */
    private static boolean companion(File f, File[] folder) {
        boolean nup = f.getName().endsWith(Nup.EXT);
        if (!nup && !f.getName().endsWith(Nup.EXT_NSP)) return false;
        try {
            Nup me = Nup.peek(f);
            if (nup && me.base() != null) return false;
            Nup.Pkg mine = me.newest();
            if (mine == null && !nup) return true;   // a DLC NSP: it belongs to a game beside it, or to none (then nothing is lost: it is no game)
            for (File g : folder) {
                if (g.equals(f) || g.getName().startsWith(".") || !g.isFile()) continue;
                boolean gnup = g.getName().endsWith(Nup.EXT);
                if (!gnup && !g.getName().endsWith(Nup.EXT_NSP)) continue;
                Nup o;
                try { o = Nup.peek(g); } catch (IOException | RuntimeException e) { continue; }
                Nup.Pkg p = o.newest();
                if (p == null) continue;
                if (mine == null) {   // a .nup of DLC only: hidden when a game beside it has that DLC, else listed (Add game says why it is no game)
                    for (Nup.Pkg a : me.packages) {
                        long idx = a.titleId - p.titleId - 0x1000;
                        if (a.kind == Nup.ADDON && idx > 0 && idx <= 2000) return true;
                    }
                    continue;
                }
                if (p.titleId != mine.titleId) continue;   // another game
                if (gnup || nup) { if (gnup && o.base() != null) return true; continue; }
                if (p.version > mine.version || p.version == mine.version && g.getName().compareTo(f.getName()) < 0) return true;
            }
        } catch (IOException | RuntimeException e) {
            return false;   // unreadable: listed, so that Add game says why
        }
        return false;
    }
}
