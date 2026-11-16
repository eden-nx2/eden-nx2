package dev.nxnative.android;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * The shader layer's safeguards (android/SHADERS.md, "Shaders the library lacks", Safeguards), the parts that need no Android classes, so the JVM test
 * (LayerPendingJvmTest, dm/tools/tests/test_android_layer.py) runs them on the Mac:
 * <ul>
 * <li>requests a prepared library already has are dropped at a game's start ({@link #dropCovered}): a library built on a computer was moved into the pack, or Prepare finished;</li>
 * <li>a disk quota for the layer folder ({@link #quota}), of what this version's build answers for ({@link #usage(File, String)}): beyond it no more builds are asked for;</li>
 * <li>no build while the phone is hot or its battery is low and not charging ({@link #pauseReason}), and the packer's workers by cores and heat ({@link #procs}).</li>
 * </ul>
 */
final class LayerGuard {
    private LayerGuard() {}

    /**
     * The layer's least quota: 2 GiB, so a game with no library can build a good part of its set; a layer whose library is already large gets room for its whole self
     * ({@link #quota}). QUOTA_MAX is the older name, the intent extra's default
     */
    static final long QUOTA_MIN = 2L << 30, QUOTA_MAX = QUOTA_MIN;
    /** What a layer holds for each byte of its library: the library, the results it is written from (about 2.2 times its size), and the next library written beside it (.tmp) */
    static final long FULL_NUM = 9, FULL_DEN = 2;
    /** Free space the layer always leaves on its volume: 1 GiB, or a twentieth of the space it can have, whichever is more */
    static final long RESERVE_MIN = 1L << 30, RESERVE_DIV = 20;
    /** PowerManager.THERMAL_STATUS_LIGHT and _MODERATE (API 29): light keeps one worker, moderate and severe run one worker ({@link #pace}), critical pauses */
    static final int THERMAL_LIGHT = 1, THERMAL_MODERATE = 2;
    /** Battery percent under which a build waits while the phone is not charging */
    static final int BATTERY_MIN = 15;

    /**
     * The quota of a layer that holds used bytes ({@link #usage(File, String)}: this version's library, results and the requests) on a volume with usable bytes free, whose
     * library (lib/v-&lt;V&gt;/dmsh_vk.lib) has libBytes: room for a whole layer of that library (library, results and the next library beside it: 4.5 times it), at least 2 GiB,
     * and never so much that the volume keeps less than its reserve free (1 GiB, or a twentieth of what the layer can have). The quota grows with the library, so a game with no
     * prepared library is not stopped by the size of what it already plays from; only the phone's free space stops it, and the label and the game page then say so
     */
    static long quota(long used, long usable, long libBytes) {
        long u = Math.max(0, used), free = Math.max(0, usable), all = u + free;
        long want = Math.max(QUOTA_MIN, Math.max(0, libBytes) / FULL_DEN * FULL_NUM);
        long room = all - Math.max(RESERVE_MIN, all / RESERVE_DIV);
        return Math.max(0, Math.min(want, room));
    }

    /** As above, with the layer's own library read from its folder */
    static long quota(File layer, String version, long used) {
        return quota(used, layer.getUsableSpace(), new File(new File(new File(layer, "lib"), "v-" + version), "dmsh_vk.lib").length());
    }

    /**
     * Bytes the layer of this version answers for: everything in the folder (the requests, its own files) but the folders of other versions (lib/v-&lt;other&gt;: an older
     * APK's, or the other app's that shares the pack folder), which the host of this version never maps and LayerDir.tidy removes on its own rules. version null: everything
     */
    static long usage(File layer, String version) {
        File[] l = layer.listFiles();
        if (l == null) return 0;
        long n = 0;
        for (File f : l) {
            if (version != null && f.getName().equals("lib") && f.isDirectory()) {
                File[] vs = f.listFiles();
                if (vs != null) for (File v : vs) if (!v.getName().startsWith("v-") || v.getName().equals("v-" + version)) n += v.isDirectory() ? usage(v) : v.length();
            } else n += f.isDirectory() && !java.nio.file.Files.isSymbolicLink(f.toPath()) ? usage(f) : f.length();
        }
        return n;
    }

    /** The note a layer past its quota keeps in its folder (LayerWatch writes it, the game's page reads it): "&lt;used bytes&gt;\t&lt;quota bytes&gt;" */
    static final String FULL = ".quota";

    /** Writes the note when used is past quota, removes it when not */
    static void noteFull(File layer, long used, long quota) {
        File f = new File(layer, FULL);
        if (used <= quota) { f.delete(); return; }
        try { java.nio.file.Files.write(f.toPath(), (used + "\t" + quota + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII)); } catch (IOException e) { /* the label says it in the game */ }
    }

    /**
     * {used, quota} of the note when the layer is still past its quota now (usable: the volume's free bytes now, negative: asked of the folder; libBytes: its library's), null
     * when there is no note or the phone has since freed space enough. No walk of the folder: the game's page calls it
     */
    static long[] full(File layer, String version, long usable) {
        File f = new File(layer, FULL);
        if (!f.isFile()) return null;
        try {
            String[] c = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.US_ASCII).trim().split("\t");
            long used = Long.parseLong(c[0]);
            long q = quota(used, usable >= 0 ? usable : layer.getUsableSpace(), new File(new File(new File(layer, "lib"), "v-" + version), "dmsh_vk.lib").length());
            return used > q ? new long[] {used, q} : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Bytes under a folder (files only; links are not followed) */
    static long usage(File dir) {
        File[] l = dir.listFiles();
        if (l == null) return 0;
        long n = 0;
        for (File f : l) n += f.isDirectory() && !java.nio.file.Files.isSymbolicLink(f.toPath()) ? usage(f) : f.length();
        return n;
    }

    /** PowerManager.THERMAL_STATUS_SEVERE: with a game in front the build waits from here; with no game it runs with one worker (the OS flags severe from the skin sensor at about 44 C on the Honor test phone, which is not hot to touch while the SoC has room) */
    static final int THERMAL_SEVERE = 3;
    /** PowerManager.THERMAL_STATUS_CRITICAL: the build always waits from here */
    static final int THERMAL_CRITICAL = 4;
    /** getThermalHeadroom(10) at or over this: the phone is at its severe limit within seconds, the build waits even at status 2 */
    static final float HEADROOM_HOT = 1.0f;
    /** The notes on the label while a build runs reduced to one worker by heat (never a pause: {@link #pace}) */
    static String WARM_NOTE() { return Words.s("game_layer_phone_is_warm", "phone is warm"); }
    static String HOT_NOTE() { return Words.s("game_layer_phone_is_hot_1_worker", "phone is hot: 1 worker"); }
    /** getThermalHeadroom(10) from which a build with many requests waiting drops back to one worker (it still runs until {@link #HEADROOM_HOT}). Below it, status 2 keeps every worker: the phone reaches status 2 within 2 minutes of the game alone, and one worker left a blank scene for many minutes (phone test 2026-11-16) */
    static final float HEADROOM_WARM = 0.85f;

    /** What the build may do now: pause (why it waits, null when it may run), note (what the label adds while it runs reduced, null otherwise) */
    static final class Pace {
        final String pause, note;
        Pace(String pause, String note) { this.pause = pause; this.note = note; }
    }

    /**
     * The build's pace. thermal: PowerManager.getCurrentThermalStatus() (0 none ... 6 shutdown); headroom: getThermalHeadroom(10) (NaN when unknown); battery the percent (negative:
     * unknown), charging whether the phone charges; pending, uptimeMs: kept for callers (the policy reads neither: a game in front or not, many shaders waiting or few, the answer is the
     * same). Heat never stops the build: from status 2 it runs with ONE worker ({@link #procs}) and a note on the label, "phone is warm" (status 2) or "phone is hot: 1 worker" (status 3 and
     * above, or status 2 with the headroom at {@link #HEADROOM_HOT}). The OS's skin status says "severe" at about 44 C on the Honor test phone while the SoC has room, and a paused build left
     * players with a blank scene. At critical status Android itself may close the app; the next start goes on from built.tsv. Only a battery under {@link #BATTERY_MIN} that does not
     * charge waits. Nothing of this goes to the host: the game plays on what is built
     */
    static Pace pace(int thermal, float headroom, int battery, boolean charging, int pending, long uptimeMs) {
        if (battery >= 0 && battery < BATTERY_MIN && !charging) return new Pace("battery low", null);
        if (thermal >= THERMAL_SEVERE) return new Pace(null, HOT_NOTE());   // (a game in front or not, critical or not: one worker, never none)
        if (thermal >= THERMAL_MODERATE) return new Pace(null, headroom >= HEADROOM_HOT ? HOT_NOTE() : WARM_NOTE());   // (NaN headroom: unknown, compares false)
        return new Pace(null, null);
    }

    /** Why the build waits with no game in front (uptime unknown): only a low battery. null when it may run (a warm or severe phone runs with one worker) */
    static String pauseReason(int thermal, int battery, boolean charging) { return pace(thermal, Float.NaN, battery, charging, 0, Long.MAX_VALUE).pause; }

    /** The watcher's label while a build waits */
    static String pausedText(String reason) { return "Shader build paused (" + reason + ")"; }

    /** Requests waiting from which the build takes more workers (the scene is mostly missing then: a blank picture is worth more heat than a slow fill) */
    static final int MANY_PENDING = 150, MAX_PROCS = 3;

    /**
     * The packer's workers and translator calls at once (DMSH_VK_PROCS, DMSH_VK_XLATE_J). Few waiting: 2 on a phone with 6 or more cores that is not warm, else 1 (the game
     * runs beside it). Many waiting ({@link #MANY_PENDING}) and the phone not yet at the thermal pause ({@link #THERMAL_MODERATE}): half the cores, at most {@link #MAX_PROCS}
     * (measured on the Mac: 4 workers gave 1.8x to 3.2x, 1.5 GB peak; the game holds 5-7 GB of the phone's RAM). The game starts with an empty library and plays blank
     * until its first shaders exist, so the first minutes are what the player waits for
     */
    static int procs(int cores, int thermal, int pending, float headroom) {
        if (cores >= 6 && pending >= MANY_PENDING && thermal < THERMAL_SEVERE && !(headroom >= HEADROOM_WARM)) return Math.max(2, Math.min(MAX_PROCS, cores / 2));
        return cores >= 6 && thermal < THERMAL_LIGHT ? 2 : 1;
    }

    // ---- requests a prepared library already has ----

    /**
     * Removes the requests of the layer folder whose key the prepared library has (lib: the library the host plays, null: none), so the packer does not build them for
     * nothing. A request stays when one of its program's other stages is missing from the library (req/pairs.tsv): the host then takes that program's stages from the layer,
     * its vertex shader padded for the missing pixel shader. The control file goes first (the host skips a key whose .ctl exists; LayerPending counts .ctl files).
     * pairs.tsv is left as it is (the host appends to it; shader_layer.py reads only the pairs of requests that are there). titleId: the library must be this game's.
     * → the requests removed; 0 when the library cannot be read
     */
    static int dropCovered(File layer, File lib, String titleId) {
        if (lib == null || ShaderLibs.kept(lib)) return 0;   // (a library a sample kept: its requests are the layer's verification of what is played, LayerKept)
        File req = new File(layer, "req");
        File[] fs = req.listFiles((d, name) -> name.endsWith(".ctl") && name.length() == 20);
        if (fs == null || fs.length == 0) return 0;
        Set<String> keys = new TreeSet<>();
        for (File f : fs) keys.add(f.getName().substring(0, 16));
        Set<String> covered = new HashSet<>();
        try (RandomAccessFile f = new RandomAccessFile(lib, "r")) {
            long[] t = entries(f, titleId);
            if (t == null) return 0;
            for (String k : keys) {
                long v;
                try { v = Long.parseUnsignedLong(k, 16); } catch (NumberFormatException e) { continue; }
                if (has(f, t[0], t[1], v)) covered.add(k);
            }
        } catch (IOException e) {
            return 0;
        }
        if (covered.isEmpty()) return 0;
        Set<String> keep = new HashSet<>();
        File pairs = new File(req, "pairs.tsv");
        if (pairs.isFile()) {
            try (BufferedReader r = new BufferedReader(new FileReader(pairs))) {
                for (String l; (l = r.readLine()) != null; ) {
                    String[] c = l.split("\t");
                    if (c.length != 2) continue;
                    boolean a = keys.contains(c[0]) && !covered.contains(c[0]), b = keys.contains(c[1]) && !covered.contains(c[1]);
                    if (a || b) { keep.add(c[0]); keep.add(c[1]); }
                }
            } catch (IOException e) {
                return 0;   // (which program a stage belongs to is not known: nothing is removed)
            }
        }
        int n = 0;
        for (String k : covered) {
            if (keep.contains(k)) continue;
            if (new File(req, k + ".ctl").delete()) { n++; new File(req, k + ".code").delete(); }
        }
        return n;
    }

    /** {entry count, entry offset} of a library file of titleId (dmsh_vk_header: magic, version, game at 40, entry_count at 72, entry_off at 76), null when it is none */
    static long[] entries(RandomAccessFile f, String titleId) throws IOException {
        if (f.length() < 128) return null;
        byte[] h = new byte[128];
        f.seek(0);
        f.readFully(h);
        if (u32(h, 0) != ShaderLibs.MAGIC || u32(h, 4) != ShaderLibs.VERSION) return null;
        int e = 40;
        while (e < 56 && h[e] != 0) e++;
        if (titleId != null && !titleId.equals(new String(h, 40, e - 40, java.nio.charset.StandardCharsets.US_ASCII))) return null;
        long count = u32(h, 72), off = u32(h, 76);
        if (off < 128 || off + count * 64 > f.length()) return null;
        ShaderLibs.Header sh = new ShaderLibs.Header();   // the other tables and the blob as the host checks them: a library cut short is not the host's, its keys are not taken
        sh.entryCount = count; sh.entryOff = off; sh.bindCount = u32(h, 80); sh.bindOff = u32(h, 84); sh.modCount = u32(h, 88); sh.modOff = u32(h, 92);
        sh.blobOff = u32(h, 96) | u32(h, 100) << 32; sh.blobSize = u32(h, 104) | u32(h, 108) << 32;
        if (ShaderLibs.tableProblem(sh, f.length()) != null) return null;
        return new long[] {count, off};
    }

    /** Whether the library's entries (sorted by key as an unsigned number, 64 bytes each, the key first, little-endian) hold key: a binary search */
    static boolean has(RandomAccessFile f, long count, long off, long key) throws IOException {
        long lo = 0, hi = count - 1;
        byte[] b = new byte[8];
        while (lo <= hi) {
            long mid = (lo + hi) >>> 1;
            f.seek(off + mid * 64);
            f.readFully(b);
            long k = 0;
            for (int i = 7; i >= 0; i--) k = k << 8 | (b[i] & 0xff);
            int c = Long.compareUnsigned(k, key);
            if (c == 0) return true;
            if (c < 0) lo = mid + 1; else hi = mid - 1;
        }
        return false;
    }

    private static long u32(byte[] h, int o) { return (h[o] & 0xffL) | (h[o + 1] & 0xffL) << 8 | (h[o + 2] & 0xffL) << 16 | (h[o + 3] & 0xffL) << 24; }
}
