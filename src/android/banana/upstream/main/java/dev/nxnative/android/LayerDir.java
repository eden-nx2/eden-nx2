package dev.nxnative.android;

import java.io.File;

/**
 * Where the run-time shader layer lives (android/SHADERS.md, "Shaders the library lacks"): the folder <code>&lt;pack&gt;/shaders/layer</code>, which the host gets as
 * <code>DM_VK_LAYER_DIR</code> and the game's watcher and the packer's service work in. It belongs to the pack, so it stays between runs (a game with no shader library
 * plays on what it has built so far) and is not a "temporary" folder: Storage lists it on its own (StorageScan.Kind.LAYER) and never with the game's temporary files.
 * Where the pack folder cannot take it (read-only), the layer stays in the game's temporary folder as before (<code>DM_TEMP/shader_layer</code>, the host's own default).
 * No Android classes: the JVM test (LayerDirJvmTest, dm/tools/tests/test_android_layer.py) runs it on the Mac.
 */
final class LayerDir {
    private LayerDir() {}

    /** The folder's name in the pack's shaders/ folder, and in the game's temporary folder (the older place) */
    static final String NAME = "layer", TEMP_NAME = "shader_layer";

    /**
     * The layer folder of a game: the pack's, when it can be made and written, else the temporary one. A layer folder an earlier version kept in the game's temporary
     * folder moves to the pack's when the pack has none yet (a rename: both are in the app's storage; when it does not work the older one stays where it is and the
     * pack's starts empty)
     */
    static File resolve(File packDir, File temp) {
        File old = new File(temp, TEMP_NAME), mine = new File(new File(packDir, "shaders"), NAME);
        if (!mine.isDirectory() && !mine.mkdirs() && !mine.isDirectory()) return old;
        if (!mine.canWrite()) return old;
        if (old.isDirectory()) migrate(old, mine);
        return mine;
    }

    /** Whether the layer folder is the pack's (the host is then told with DM_VK_LAYER_DIR), not the temporary one */
    static boolean inPack(File layer, File temp) { return !layer.equals(new File(temp, TEMP_NAME)); }

    /** Moves the older layer folder into the new, empty one (nothing is overwritten); false when it stayed */
    static boolean migrate(File old, File mine) {
        String[] have = mine.list();
        if (have != null && have.length > 0) return false;   // the pack's has its own: the older one is not mixed in (Storage's Game temporary files take it)
        if (!old.isDirectory()) return false;
        if (have != null && !mine.delete()) return false;   // an empty folder: the rename replaces it
        if (old.renameTo(mine)) return true;
        mine.mkdirs();
        return false;
    }

    /** An other version's folder (lib/v-&lt;other&gt;) not touched for this long goes at a game's start: no app of that version has built in it for a month */
    static final long OTHER_KEEP_MS = 30L * 24 * 3600 * 1000;

    /**
     * Tidies the layer folder when a game starts (current: this APK's DMSH_VK_CHAIN). The host maps only lib/v-&lt;current&gt;, and each version keeps its own build list there
     * (LayerPending.builtFile), so another version's folder (an older APK's, or the other app's that shares a picked pack folder) is neither counted against this version's
     * quota (LayerGuard.usage) nor in the way of its build. It is removed whole (library, results, build list) only when no app of its version has touched it for
     * {@link #OTHER_KEEP_MS}, or when the whole folder is past this version's quota (LayerGuard.quota; usable: the volume's free bytes, negative: asked of the folder), the
     * oldest first until it fits; never while a build runs (LayerPending.building: its marker is the folder's, whichever app builds). What a stopped build left is removed
     * when no build runs: a library never renamed into place (dmsh_vk.lib.tmp: the host maps only dmsh_vk.lib) and result files never renamed (results/*.tmp).
     * → what was done, "" when nothing
     */
    static String tidy(File layer, String current) { return tidy(layer, current, System.currentTimeMillis(), -1); }

    static String tidy(File layer, String current, long nowMs, long usable) {
        if (LayerPending.building(layer, nowMs)) return "";   // a build runs (this app's or the other's): its files and folders are left alone
        File lib = new File(layer, "lib");
        File[] all = lib.listFiles(f -> f.isDirectory() && f.getName().startsWith("v-"));
        if (all == null) return "";
        int tmps = 0;
        for (File v : all) {
            if (new File(v, "dmsh_vk.lib.tmp").delete()) tmps++;
            File[] rt = new File(v, "results").listFiles((d, n) -> n.endsWith(".tmp"));
            if (rt != null) for (File f : rt) if (f.delete()) tmps++;
        }
        java.util.List<File> others = new java.util.ArrayList<>();
        for (File v : all) if (!v.getName().equals("v-" + current)) others.add(v);
        others.sort(java.util.Comparator.comparingLong(LayerDir::touched));
        int old = 0, full = 0;
        for (java.util.Iterator<File> it = others.iterator(); it.hasNext(); ) {
            File v = it.next();
            if (nowMs - touched(v) > OTHER_KEEP_MS) { deleteTree(v); it.remove(); old++; }
        }
        if (!others.isEmpty()) {
            long mine = LayerGuard.usage(layer, current), total = LayerGuard.usage(layer, null);
            long q = LayerGuard.quota(mine, usable >= 0 ? usable : layer.getUsableSpace(), new File(new File(lib, "v-" + current), "dmsh_vk.lib").length());
            for (File v : others) {
                if (total <= q) break;
                total -= LayerGuard.usage(v);
                deleteTree(v);
                full++;
            }
        }
        StringBuilder r = new StringBuilder();
        if (tmps > 0) r.append("removed ").append(tmps).append(" unfinished file").append(tmps == 1 ? "" : "s").append(" of a stopped build");
        if (old > 0) r.append(r.length() > 0 ? "; " : "").append("removed ").append(old).append(" other version").append(old == 1 ? "'s" : "s'").append(" layer, unused for 30 days");
        if (full > 0) r.append(r.length() > 0 ? "; " : "").append("removed ").append(full).append(" other version").append(full == 1 ? "'s" : "s'").append(" layer: the folder was past its quota");
        return r.toString();
    }

    /** When an app of a version last wrote in its folder: the newest of the folder and its library, build list and state (a build renames each into place) */
    static long touched(File v) {
        long t = v.lastModified();
        for (String n : new String[] {"dmsh_vk.lib", "built.tsv", "layer_state.json", "results"}) t = Math.max(t, new File(v, n).lastModified());
        return t;
    }

    private static void deleteTree(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File x : l) deleteTree(x);
        f.delete();
    }
}
