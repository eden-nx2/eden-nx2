package dev.nxnative.android;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

/** What the shader layer still has to build, from its folder (LayerWatch; plain Java, tested on the JVM by dm/tools/tests/test_android_layer.py) */
final class LayerPending {
    private LayerPending() {}

    /** The extras of the intent that starts LayerService (the packer's process, which has its own class for them) */
    static final String EXTRA_GAME = "game", EXTRA_ROOT = "root";

    static String text(int n) { return "Building shaders (" + n + ")"; }

    /**
     * The marker LayerService keeps in the layer folder while it builds (made at its start, touched every {@link #BEAT_MS} by a thread of its own while the build runs, removed at
     * its end), and how long one counts after its last touch: a packer killed mid-build leaves it, and it stops counting within minutes, not after the longest build
     */
    static final String BUILDING = ".building";
    static final long BEAT_MS = 30 * 1000L, BUILDING_MS = 3 * 60 * 1000L;

    /** Where a version's build list is: lib/v-&lt;version&gt;/built.tsv (shader_layer.py write_built), one per version, so two apps of other versions sharing a pack folder keep theirs */
    static File builtFile(File root, String version) { return new File(new File(new File(root, "lib"), "v-" + version), "built.tsv"); }

    /**
     * The requested keys that this version's built.tsv does not settle (sorted); empty when there is no request folder. A row settles its key when the shader was built (ok), or
     * when it failed with this version of the tools (version: this APK's DMSH_VK_CHAIN). Each version has its own list ({@link #builtFile}), so a new version builds every
     * request again into its own library, and tries a failure once more (a translator fix gets it). Without its own list, the rows of this version in the older shared
     * built.tsv at the folder's top count (as shader_layer.py read_built). version null: the shared list, every row settles (the older rule)
     */
    static Set<String> pending(File root, String version) {
        Set<String> req = new TreeSet<>();
        File[] fs = new File(root, "req").listFiles((d, name) -> name.endsWith(".ctl") && name.length() == 20);
        if (fs == null) return req;
        for (File f : fs) req.add(f.getName().substring(0, 16));
        forRows(root, version, (k, st, v) -> { if (version == null || "ok".equals(st) || version.equals(v)) req.remove(k); });
        return req;
    }

    static Set<String> pending(File root) { return pending(root, null); }

    /** Every requested key (req/&lt;key&gt;.ctl), sorted */
    static Set<String> requested(File root) {
        Set<String> req = new TreeSet<>();
        File[] fs = new File(root, "req").listFiles((d, name) -> name.endsWith(".ctl") && name.length() == 20);
        if (fs != null) for (File f : fs) req.add(f.getName().substring(0, 16));
        return req;
    }

    /** The keys this version's built.tsv has as built (ok) */
    static Set<String> builtKeys(File root, String version) {
        Set<String> out = new TreeSet<>();
        forRows(root, version, (k, st, v) -> { if ("ok".equals(st)) out.add(k); });
        return out;
    }

    /** The shaders the layer of this version has built (its built.tsv's ok rows): the game page's "N built"; version null: the shared list's */
    static int built(File root, String version) {
        int[] n = {0};
        forRows(root, version, (k, st, v) -> { if ("ok".equals(st)) n[0]++; });
        return n[0];
    }

    /** built.tsv's rows that settle a request (as {@link #pending}): the watcher's measure of whether a run did anything */
    static int settled(File root, String version) {
        int[] n = {0};
        forRows(root, version, (k, st, v) -> { if (version == null || "ok".equals(st) || version.equals(v)) n[0]++; });
        return n[0];
    }

    /** This app's package and the test for a live process of its own (/proc/&lt;pid&gt;), set by the game's start (LayerWatch.start); null package: the time rule alone */
    static volatile String selfPackage;
    static volatile java.util.function.IntPredicate alive = pid -> new File("/proc/" + pid).isDirectory();

    /** What LayerService writes into the marker: "&lt;pid&gt;\t&lt;package&gt;", so a marker of a process that no longer exists is told from a build that runs */
    static String markerText(int pid, String pkg) { return pid + "\t" + pkg + "\n"; }

    /** {pid} and package of the marker's text, null when it has none (an older marker, or an other app's way of writing it) */
    private static Object[] owner(File m) {
        try {
            String[] c = new String(java.nio.file.Files.readAllBytes(m.toPath()), java.nio.charset.StandardCharsets.US_ASCII).trim().split("\t");
            return c.length == 2 ? new Object[] {Integer.parseInt(c[0]), c[1]} : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Whether LayerService is building in this folder now: its marker was touched less than {@link #BUILDING_MS} ago, and, when the marker is this app's own (its package, its
     * pid), that process still lives. A force-stopped app leaves a fresh marker with a dead pid: it does not count, so the next start builds at once and does not wait minutes
     * for it to age. An other app's marker (pid not visible to this one) follows the time rule alone
     */
    static boolean building(File root, long nowMs) {
        File m = new File(root, BUILDING);
        long t = m.lastModified();
        if (t <= 0 || nowMs - t >= BUILDING_MS) return false;
        String self = selfPackage;
        Object[] o = self == null ? null : owner(m);
        return o == null || !self.equals(o[1]) || alive.test((Integer) o[0]);
    }

    /** Removes the marker when it is this app's and its process is gone (a force-stopped build); → whether it did. LayerDir.tidy then treats the folder as that of a stopped build */
    static boolean clearStale(File root, long nowMs) {
        File m = new File(root, BUILDING);
        if (!m.isFile() || building(root, nowMs)) return false;
        return m.delete();
    }

    /** The last "shader_layer timing: …" line of a run's log (shader_layer.py PhaseClock: where the run's time went), looking only at the last 64 KB; null when there is none */
    static String lastTiming(File log) {
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(log, "r")) {
            long n = Math.min(f.length(), 64 * 1024);
            byte[] b = new byte[(int) n];
            f.seek(f.length() - n);
            f.readFully(b);
            String t = null;
            for (String l : new String(b, java.nio.charset.StandardCharsets.UTF_8).split("\n")) if (l.startsWith("shader_layer timing: ")) t = l.trim();
            return t;
        } catch (IOException e) {
            return null;
        }
    }

    /** The last line of a log that starts with prefix (looking at its last 64 KB), null when none */
    static String lastLineWith(File log, String prefix) {
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(log, "r")) {
            long n = Math.min(f.length(), 64 * 1024);
            byte[] b = new byte[(int) n];
            f.seek(f.length() - n);
            f.readFully(b);
            String t = null;
            for (String l : new String(b, java.nio.charset.StandardCharsets.UTF_8).split("\n")) if (l.startsWith(prefix)) t = l.trim();
            return t;
        } catch (IOException e) {
            return null;
        }
    }

    private interface Row { void row(String key, String status, String version); }

    /**
     * The version's built.tsv rows: key (16 hex), status, version; written by rename (shader_layer.py write_built), so it is whole or the older one. Without the version's own
     * file, the rows of that version in the folder's older shared built.tsv ("" in a row of an older tool); version null: the shared file, every row
     */
    private static void forRows(File root, String version, Row r) {
        File own = version == null ? null : builtFile(root, version), built = own != null && own.isFile() ? own : new File(root, "built.tsv");
        boolean all = version == null || built == own;
        if (!built.isFile()) return;
        try (BufferedReader b = new BufferedReader(new FileReader(built))) {
            for (String l; (l = b.readLine()) != null; ) {
                String[] c = l.split("\t", -1);
                String v = c.length > 2 ? c[2] : "";
                if (c.length >= 2 && c[0].length() == 16 && (all || v.equals(version))) r.row(c[0], c[1], v);
            }
        } catch (IOException e) { /* the next tick reads it again */ }
    }
}
