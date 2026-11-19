package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Whether a dump's RomFS is the data of the version its code is (game.tsv romfs_check rows, Profile.romfsChecks): a RomFS file line that differs between versions,
 * where romfs_marker cannot tell (a file every version has). Donkey Kong Bananza: line 4 of System/RegionLangMask.txt is the build ID of the main its RomFS was made
 * for, so an update folder that holds the base's data (or an unpatched update RomFS) is found before the game boots into the wrong bundle and crashes.
 * A row's line may instead be key:&lt;name&gt;: the file is a MessagePack map (decompressed first when it is a zstd frame), and the string under that top-level key is
 * compared. Fire Emblem Fortune's Weave: Fs.bin.zst, the game's own index of its files, names its release (ReleaseName "002" in 131072's RomFS).
 * Read by Prepare (DumpLayout.find) and by the card and Play (BuildCheck.build), for folders and .nup files in any layout. No Android classes: GameListJvmTest runs it.
 */
final class RomfsCheck {
    private RomfsCheck() {}

    /**
     * null when every check of the profile holds for this RomFS, else a sentence the user can act on.
     * romfs: a RomFS folder, or a .nup's &lt;file&gt;.nup:&lt;selector&gt;/romfs (NUP.md naming); mainId: the build ID of the version's main (hex, 32 or 64 digits;
     * null: "main" checks are skipped); version: the version the code is (0: the base game)
     */
    static String check(Profile prof, String romfs, String mainId, long version) {
        if (prof == null || prof.romfsChecks.isEmpty() || romfs == null) return null;
        for (String row : prof.romfsChecks.split(";")) {
            String[] c = row.split("\\|", -1);
            if (c.length != 3) continue;
            String key = c[1].startsWith("key:") ? c[1].substring(4) : null;
            int line = 0;
            if (key == null) try { line = Integer.parseInt(c[1]); } catch (NumberFormatException e) { continue; }
            boolean main = c[2].equals("main");
            if (main && mainId == null) continue;
            String text;
            try {
                text = key != null ? key(romfs, c[0], key) : line(romfs, c[0], line);
            } catch (IOException e) {
                text = null;
            }
            boolean ok = text != null && (main ? sameBuild(text, mainId) : text.equals(c[2]));
            if (ok) continue;
            boolean nup = Nup.parse(romfs) != null;
            String what = version == 0 ? "the base game's RomFS" : "version " + version + "'s full update RomFS";
            String where = key != null ? key : "line " + line;
            String found = text == null ? "missing" : where + " is \"" + (text.length() > 48 ? text.substring(0, 48) + "…" : text) + "\"";
            String want = main ? "main's build ID " + mainId.substring(0, Math.min(16, mainId.length())) + "…" : "\"" + c[2] + "\"";
            return "This game " + (nup ? ".nup" : "folder") + "'s " + (version == 0 ? "" : "update ") + "data is not " + what + " (" + c[0] + ": " + found + ", expected " + want + (text == null ? (key != null ? " under " : " on ") + where : "")
                    + "). Dump or extract the " + (version == 0 ? "game" : "update") + " again as the patched RomFS" + (nup ? "." : ", or use the .nup.");
        }
        return null;
    }

    /** Whether a line names a build ID: either is the start of the other, with 16 hex digits or more */
    static boolean sameBuild(String text, String id) {
        String t = text.trim().toLowerCase(Locale.ROOT), i = id.toLowerCase(Locale.ROOT);
        if (t.length() < 16 || !t.matches("[0-9a-f]+")) return false;
        return i.startsWith(t) || t.startsWith(i);
    }

    /** Line n (from 1, trimmed) of a RomFS file, null when the file has fewer lines; IOException when the file is not there */
    static String line(String romfs, String path, int n) throws IOException {
        String[] lines = new String(bytes(romfs, path), StandardCharsets.UTF_8).split("\r?\n", -1);
        return n <= lines.length ? lines[n - 1].trim() : null;
    }

    /**
     * The string under a top-level key of a RomFS file that is a MessagePack map (decompressed first when it begins with a zstd frame); null when the map has no such
     * key or its value is not a string; IOException when the file is not there or is not such a map
     */
    static String key(String romfs, String path, String name) throws IOException {
        byte[] b = bytes(romfs, path);
        if (b.length >= 4 && Zstd.le32(b, 0) == 0xFD2FB528) b = Zstd.decode(b, 0, b.length, -1);
        MsgPack m = new MsgPack(b);
        long n = m.mapHeader();
        if (n < 0) throw new IOException(romfs + "/" + path + " is not a MessagePack map");
        for (long i = 0; i < n; i++) {
            String k = m.string();
            if (name.equals(k)) return m.string();
            m.skip();
        }
        return null;
    }

    /** The few MessagePack forms key() needs (msgpack.org spec): a map's size, a string, and skipping any value; IOException past the end or on a reserved byte */
    private static final class MsgPack {
        private final byte[] b;
        private int p;

        MsgPack(byte[] b) { this.b = b; }

        private int u8() throws IOException { if (p >= b.length) throw new IOException("MessagePack: cut short"); return b[p++] & 0xff; }
        private long un(int n) throws IOException { long v = 0; for (int i = 0; i < n; i++) v = v << 8 | u8(); return v; }
        private void over(long n) throws IOException { if (n < 0 || n > b.length - p) throw new IOException("MessagePack: cut short"); p += (int) n; }

        /** The size of the map that starts here (and moves past its header), -1 when no map starts here (nothing is read) */
        long mapHeader() throws IOException {
            if (p >= b.length) return -1;
            int t = b[p] & 0xff;
            if (t >= 0x80 && t <= 0x8f) { p++; return t & 15; }
            if (t == 0xde) { p++; return un(2); }
            if (t == 0xdf) { p++; return un(4); }
            return -1;
        }

        /** The string that starts here, or null (the value is skipped) when it is not a string */
        String string() throws IOException {
            if (p >= b.length) throw new IOException("MessagePack: cut short");
            int t = b[p] & 0xff;
            long n = t >= 0xa0 && t <= 0xbf ? t & 31 : t == 0xd9 ? -2 : t == 0xda ? -3 : t == 0xdb ? -5 : -1;
            if (n == -1) { skip(); return null; }
            p++;
            if (n < -1) n = un((int) -n - 1);
            int at = p;
            over(n);
            return new String(b, at, (int) n, StandardCharsets.UTF_8);
        }

        /** Moves past the value that starts here */
        void skip() throws IOException {
            int t = u8();
            if (t <= 0x7f || t >= 0xe0 || t == 0xc0 || t == 0xc2 || t == 0xc3) return;
            if (t <= 0x8f) { for (long i = 0, n = 2L * (t & 15); i < n; i++) skip(); return; }
            if (t <= 0x9f) { for (long i = 0, n = t & 15; i < n; i++) skip(); return; }
            if (t <= 0xbf) { over(t & 31); return; }
            switch (t) {
                case 0xc4: case 0xd9: over(un(1)); return;
                case 0xc5: case 0xda: over(un(2)); return;
                case 0xc6: case 0xdb: over(un(4)); return;
                case 0xc7: over(un(1) + 1); return;
                case 0xc8: over(un(2) + 1); return;
                case 0xc9: over(un(4) + 1); return;
                case 0xca: case 0xce: case 0xd2: over(4); return;
                case 0xcb: case 0xcf: case 0xd3: over(8); return;
                case 0xcc: case 0xd0: over(1); return;
                case 0xcd: case 0xd1: over(2); return;
                case 0xd4: over(2); return;
                case 0xd5: over(3); return;
                case 0xd6: over(5); return;
                case 0xd7: over(9); return;
                case 0xd8: over(17); return;
                case 0xdc: for (long i = 0, n = un(2); i < n; i++) skip(); return;
                case 0xdd: for (long i = 0, n = un(4); i < n; i++) skip(); return;
                case 0xde: for (long i = 0, n = 2 * un(2); i < n; i++) skip(); return;
                case 0xdf: for (long i = 0, n = 2 * un(4); i < n; i++) skip(); return;
                default: throw new IOException("MessagePack: reserved byte " + Integer.toHexString(t));
            }
        }
    }

    /** A RomFS file's bytes (of a .nup: its first MiB at most, enough for the small files the checks read); IOException when the file is not there */
    private static byte[] bytes(String romfs, String path) throws IOException {
        byte[] b;
        Nup.Ref r = Nup.parse(romfs);
        if (r != null) {
            Nup nup = Nup.open(new File(r.path));
            Nup.Pkg p = r.selector == null ? nup.newest() : nup.find(r.selector);
            if (p == null) throw new IOException(romfs + ": no such package");
            Nup.Node x = nup.lookup(p, (r.sub.isEmpty() ? "romfs" : r.sub) + "/" + path);
            if (x == null || x.dir) throw new IOException(romfs + "/" + path + " is not there");
            b = nup.read(x, 1 << 20);   // a text file's first lines; not a whole big file
        } else {
            File f = new File(romfs, path);
            if (!f.isFile()) throw new IOException(f + " is not there");
            b = Files.readAllBytes(f.toPath());
        }
        return b;
    }
}
