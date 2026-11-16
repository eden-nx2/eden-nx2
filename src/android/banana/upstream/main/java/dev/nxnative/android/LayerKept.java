package dev.nxnative.android;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A library a sample kept as this version's (ShaderLibs.kept, android/SHADERS.md "Sampled keep") seen from the layer's folder: which requests are only to verify. The host asks
 * for every shader a game plays while such a library is the one it plays (vka_shader.c g_kept), so most requests are shaders the library has; those are not "missing": they
 * are not counted by the watcher (no "Building shaders (N)"), not built by an ordinary run (shader_layer.py split_requests) and built in verify runs of their own, in the
 * background, when nothing is missing and the phone is calm. A request whose program has a stage the library lacks stays an ordinary one (the host takes that program's
 * stages from the layer). Plain Java (JVM test: LayerPendingJvmTest). The library is read through one open file and what was asked is remembered
 */
final class LayerKept implements Closeable {
    private final RandomAccessFile f;
    private final long count, off;
    private final Map<String, Boolean> memo = new HashMap<>();

    /** lib: the kept library, titleId: the game it must be of; {@link #ok} is false when it cannot be read as the host reads it */
    LayerKept(File lib, String titleId) {
        RandomAccessFile r = null; long[] t = null;
        try {
            r = new RandomAccessFile(lib, "r");
            t = LayerGuard.entries(r, titleId);
        } catch (IOException e) {
            t = null;
        }
        if (t == null && r != null) { try { r.close(); } catch (IOException e) { /* nothing to read */ } r = null; }
        f = r; count = t == null ? 0 : t[0]; off = t == null ? 0 : t[1];
    }

    boolean ok() { return f != null; }

    /** Whether the library has the key (asked once per key) */
    synchronized boolean covers(String key) {
        if (f == null) return false;
        Boolean b = memo.get(key);
        if (b != null) return b;
        boolean has;
        try { has = LayerGuard.has(f, count, off, Long.parseUnsignedLong(key, 16)); } catch (IOException | NumberFormatException e) { has = false; }
        memo.put(key, has);
        return has;
    }

    /**
     * The requests of the layer folder that only verify (shader_layer.py split_requests): the library has the key, the layer has not built it, and no request paired with it
     * (req/pairs.tsv) is one the library lacks. all: every request key; built: those the layer built (built.tsv ok rows)
     */
    Set<String> verifyOnly(File req, Set<String> all, Set<String> built) {
        Set<String> out = new HashSet<>();
        if (f == null) return out;
        Set<String> missing = new HashSet<>();
        for (String k : all) if (!covers(k)) missing.add(k);
        Set<String> blocked = new HashSet<>();   // keys that have a mate the library lacks
        File pairs = new File(req, "pairs.tsv");
        if (!missing.isEmpty() && pairs.isFile()) {
            try (BufferedReader r = new BufferedReader(new FileReader(pairs))) {
                for (String l; (l = r.readLine()) != null; ) {
                    String[] c = l.split("\t");
                    if (c.length != 2 || !all.contains(c[0]) || !all.contains(c[1])) continue;
                    if (missing.contains(c[0])) blocked.add(c[1]);
                    if (missing.contains(c[1])) blocked.add(c[0]);
                }
            } catch (IOException e) {
                return out;   // (which program a stage belongs to is not known: nothing counts as verify-only)
            }
        }
        for (String k : all) if (covers(k) && !built.contains(k) && !blocked.contains(k)) out.add(k);
        return out;
    }

    @Override public void close() {
        try { if (f != null) f.close(); } catch (IOException e) { /* closed */ }
    }
}
