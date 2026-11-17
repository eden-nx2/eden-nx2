package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Data mods for a game whose profile has the mods key (game.tsv): folders of replacement files laid over the game's RomFS by the host (DM_ROMFS_OVERLAY, a colon list:
 * the first folder that has a file wins, then the dump). Each mod is a folder of the player's own, &lt;files&gt;/mods/&lt;title ID&gt;/&lt;name&gt;/, made from a .zip
 * ({@link #importZip}); the dump and the save are never touched. No Android classes: ModsJvmTest runs it on the JVM.
 * <ul>
 * <li>A zip is laid out as the game's RomFS. One outer folder (the zip's own name, a version such as 2.0.1, or "romfs") is dropped, so the folder that holds the game's
 *     files is the mod's root; a zip with several top-level entries is taken as it is.</li>
 * <li>Entries are written only inside the mod's folder (a path with "..", an absolute path or a drive is refused), at most {@link #MAX_FILES} files and {@link #MAX_BYTES} bytes.</li>
 * <li>A mod the player turned off stays on disk and out of the overlay; the order is by name.</li>
 * </ul>
 */
final class Mods {
    private Mods() {}

    static final int MAX_FILES = 200_000;
    static final long MAX_BYTES = 6L << 30;

    /** The game's mods folder in the app's own storage */
    static File root(File filesDir, String titleId) { return new File(new File(filesDir, "mods"), titleId); }

    /** A mod's folder name from a file name: no extension, no path or list separators (the overlay is a ':' list), never empty, never hidden */
    static String safeName(String fileName) {
        String n = fileName == null ? "" : fileName;
        n = n.substring(n.lastIndexOf('/') + 1);
        if (n.toLowerCase(Locale.ROOT).endsWith(".zip")) n = n.substring(0, n.length() - 4);
        n = n.replaceAll("[^A-Za-z0-9 ._()+\\-]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.length() > 60) n = n.substring(0, 60).trim();
        return n.isEmpty() ? "mod" : n;
    }

    /** The mods on disk, by name (a hidden or temporary folder is not one) */
    static List<File> list(File root) {
        File[] fs = root.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
        if (fs == null) return Collections.emptyList();
        Arrays.sort(fs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return Arrays.asList(fs);
    }

    /** The overlay's folders: the mods that are not turned off, in order; "" when none */
    static String overlay(File root, Set<String> off) {
        StringBuilder out = new StringBuilder();
        for (File f : list(root)) {
            if (off.contains(f.getName())) continue;
            if (out.length() > 0) out.append(':');
            out.append(f.getPath());
        }
        return out.toString();
    }

    /** A DM_ROMFS_OVERLAY line with the mods' folders after those already in the env (the Resolution choice's), or a line of their own; env is changed */
    static void addTo(List<String> env, String mods) {
        if (mods.isEmpty()) return;
        String key = "DM_ROMFS_OVERLAY=";
        for (int i = env.size() - 1; i >= 0; i--)
            if (env.get(i).startsWith(key)) { env.set(i, env.get(i) + ":" + mods); return; }
        env.add(key + mods);
    }

    /** How many mods are on */
    static int on(File root, Set<String> off) {
        int n = 0;
        for (File f : list(root)) if (!off.contains(f.getName())) n++;
        return n;
    }

    /** The result of an import */
    static final class Imported {
        final File dir;
        final int files;
        Imported(File dir, int files) { this.dir = dir; this.files = files; }
    }

    /** Whether a single top-level folder is only a wrapper: the zip's own name, a version number, or "romfs" */
    private static boolean wrapper(String dir, String zipBase) {
        return dir.equalsIgnoreCase("romfs") || dir.matches("[0-9][0-9.]*") || dir.equalsIgnoreCase(zipBase);
    }

    /** Extract a zip into root/<name> (a name already taken gets " 2", " 3" …); nothing stays behind when it fails */
    static Imported importZip(InputStream in, File root, String fileName) throws IOException {
        String base = safeName(fileName);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("the mods folder cannot be made");
        File tmp = new File(root, ".import-" + System.nanoTime());
        if (!tmp.mkdir()) throw new IOException("the mods folder cannot be written");
        try {
            int files = extract(in, tmp);
            if (files == 0) throw new IOException("the zip has no files");
            File top = tmp;
            for (int i = 0; i < 4; i++) {
                File[] kids = top.listFiles(f -> !f.getName().equals("__MACOSX") && !f.getName().equals(".DS_Store"));
                if (kids != null && kids.length == 1 && kids[0].isDirectory() && wrapper(kids[0].getName(), base)) top = kids[0]; else break;
            }
            String name = base;
            for (int n = 2; new File(root, name).exists(); n++) name = base + " " + n;
            File dest = new File(root, name);
            if (!top.renameTo(dest)) throw new IOException("the mod cannot be placed");
            return new Imported(dest, files);
        } finally {
            deleteTree(tmp);
        }
    }

    private static int extract(InputStream in, File dir) throws IOException {
        int files = 0;
        long bytes = 0;
        byte[] buf = new byte[1 << 16];
        String canon = dir.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(in)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName().replace('\\', '/');
                if (n.startsWith("__MACOSX/") || n.endsWith(".DS_Store")) continue;
                if (n.startsWith("/") || n.matches("^[A-Za-z]:.*")) throw new IOException("a path outside the mod: " + e.getName());
                File out = new File(dir, n);
                if (!out.getCanonicalPath().startsWith(canon)) throw new IOException("a path outside the mod: " + e.getName());
                if (e.isDirectory()) { out.mkdirs(); continue; }
                if (++files > MAX_FILES) throw new IOException("more than " + MAX_FILES + " files");
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("a folder cannot be made");
                try (OutputStream o = Files.newOutputStream(out.toPath())) {
                    for (int k; (k = z.read(buf)) > 0; ) {
                        bytes += k;
                        if (bytes > MAX_BYTES) throw new IOException("more than " + (MAX_BYTES >> 30) + " GB");
                        o.write(buf, 0, k);
                    }
                }
            }
        }
        return files;
    }

    /** Remove a mod's folder */
    static void remove(File root, String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.startsWith(".")) return;
        deleteTree(new File(root, name));
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /** The names turned off, kept as one string in the settings: names never hold '\n' */
    static String encodeOff(Set<String> off) { return String.join("\n", new java.util.TreeSet<>(off)); }

    static Set<String> decodeOff(String s) {
        Set<String> out = new java.util.TreeSet<>();
        if (s != null) for (String p : s.split("\n")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    /** The file count of a mod, for its row */
    static int countFiles(File dir) {
        int n = 0;
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) n += k.isDirectory() ? countFiles(k) : 1;
        return n;
    }

    static List<String> names(File root) {
        List<String> out = new ArrayList<>();
        for (File f : list(root)) out.add(f.getName());
        return out;
    }
}
