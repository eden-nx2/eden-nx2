package dev.nxnative.android;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The shader libraries of a pack folder (shaders/v-&lt;V&gt;/dmsh_vk.lib) and their delta update (android/SHADERS.md#delta-updates), as the launcher decides them:
 * <ul>
 * <li>{@link #playable}: a library of another version the host plays while this version's library is being made: its header names the game and carries this APK's
 * DMSH_VK_ABI, or else one of the older ABIs the host also plays (DMSH_VK_ABI_COMPAT, vka_abi_compat.h), the rule of the replay's own choice (vka_shader.c lib_load,
 * vka_shlib_check); the newest such one, as the replay takes it.</li>
 * <li>{@link #toTranslate}: how many shaders the update translates from the older libraries the pack has, from the profile's delta manifest "from" rows (Profile.delta),
 * and {@link #minutes} the time that takes on the phone.</li>
 * <li>{@link #restamp}: an older library that is this version's byte for byte but for the header's version and abi (the manifest's "stamp" rows, Profile.stamp) becomes
 * this version's library by rewriting those fields: no update job at all (android/SHADERS.md#restamp).</li>
 * <li>{@link #selection}: the shaders Prepare builds for a profile with a selected set (shader_seen.tsv), not every shader of its map.</li>
 * </ul>
 * No Android classes: the JVM test (android/app/src/jvmtest ShaderLibsJvmTest, dm/tools/tests/test_android_shader_delta.py) runs it on the Mac.
 */
final class ShaderLibs {
    private ShaderLibs() {}

    static final int MAGIC = 0x4B56534D;   // dmsh_abi_vk.h DMSH_VK_LIB_MAGIC, DMSH_VK_LIB_VERSION
    static final int VERSION = 1;

    /** What the launcher reads of a library's header (dmsh_vk_header: chain at 8, vk_chain at 24, game at 40, inputs at 56, abi at 116, 12 characters) */
    static final class Header {
        String chain, vkChain, game, inputs, abi;
        long entryCount, entryOff, blobOff, blobSize;   // dmsh_vk_header at 72, 76, 96, 104: where the entries and the blob end (importProblem: a file cut short)
        long bindCount, bindOff, modCount, modOff;      // at 80, 84, 88, 92: the binding and module tables (structureProblem)
    }

    private static long u32(byte[] h, int o) { return (h[o] & 0xffL) | (h[o + 1] & 0xffL) << 8 | (h[o + 2] & 0xffL) << 16 | (h[o + 3] & 0xffL) << 24; }
    private static long u64(byte[] h, int o) { return u32(h, o) | u32(h, o + 4) << 32; }

    private static String text(byte[] h, int off, int n) {
        int e = off;
        while (e < off + n && h[e] != 0) e++;
        return new String(h, off, e - off, StandardCharsets.US_ASCII);
    }

    /** The header of a library file; null when it is not one this app reads (short, another magic or format) */
    static Header header(File lib) {
        byte[] h = new byte[128];
        try (RandomAccessFile f = new RandomAccessFile(lib, "r")) {
            f.readFully(h);
        } catch (IOException e) {
            return null;
        }
        int magic = (h[0] & 0xff) | (h[1] & 0xff) << 8 | (h[2] & 0xff) << 16 | (h[3] & 0xff) << 24, version = (h[4] & 0xff) | (h[5] & 0xff) << 8 | (h[6] & 0xff) << 16 | (h[7] & 0xff) << 24;
        if (magic != MAGIC || version != VERSION) return null;
        Header r = new Header();
        r.chain = text(h, 8, 16);
        r.vkChain = text(h, 24, 16);
        r.game = text(h, 40, 16);
        r.inputs = text(h, 56, 16);
        r.entryCount = u32(h, 72);
        r.entryOff = u32(h, 76);
        r.bindCount = u32(h, 80);
        r.bindOff = u32(h, 84);
        r.modCount = u32(h, 88);
        r.modOff = u32(h, 92);
        r.blobOff = u64(h, 96);
        r.blobSize = u64(h, 104);
        String a = text(h, 116, 12);
        r.abi = a.matches("[0-9a-f]{12}") ? a : "";   // zero in a library made before the field: played by its own version only
        return r;
    }

    /** The v-&lt;V&gt; folders of a pack folder that hold a library */
    private static File[] libraries(File packDir) {
        File[] l = new File(packDir, "shaders").listFiles(f -> f.getName().startsWith("v-") && new File(f, "dmsh_vk.lib").isFile());
        return l == null ? new File[0] : l;
    }

    /**
     * The library of another version than current the host plays (the newest one whose header names titleId and carries abi; when there is none, the newest that carries
     * one of compat, the older ABIs this APK's host also plays), null when none. current: this APK's DMSH_VK_CHAIN (its own library is the launcher's usual check, not this
     * one); abi: its DMSH_VK_ABI; compat: its DMSH_VK_ABI_COMPAT (vka_abi_compat.h: space-separated, "" or null = none), the order of vka_shader.c lib_load
     */
    static File playable(File packDir, String titleId, String current, String abi, String compat) { return playable(packDir, titleId, current, abi, compat, false); }

    /** whole: only libraries the host takes as whole ({@link #structureProblem}), as lib_load goes past a damaged one */
    private static File playable(File packDir, String titleId, String current, String abi, String compat, boolean whole) {
        if (abi == null || !abi.matches("[0-9a-f]{12}")) return null;
        Set<String> older = new HashSet<>();
        if (compat != null) for (String c : compat.trim().split("\\s+")) if (c.matches("[0-9a-f]{12}") && !c.equals(abi)) older.add(c);
        File best = null, bestOlder = null;
        for (File d : libraries(packDir)) {
            if (d.getName().equals("v-" + current)) continue;
            File lib = new File(d, "dmsh_vk.lib");
            Header h = header(lib);
            if (h == null || !titleId.equals(h.game) || whole && structureProblem(lib) != null) continue;
            if (abi.equals(h.abi)) { if (best == null || lib.lastModified() > best.lastModified()) best = lib; }
            else if (older.contains(h.abi) && (bestOlder == null || lib.lastModified() > bestOlder.lastModified())) bestOlder = lib;
        }
        return best != null ? best : bestOlder;
    }

    /**
     * Whether the shader layer (LayerService, android/SHADERS.md "Shaders the library lacks") builds what an older library lacks while the game runs: the host copies only
     * NVN2 control sections (the Switch 2 titles, whose title IDs start 0400), a Maxwell game gets no layer; and the player's own lines for the game can turn it off
     * (DM_VK_LAYER=0)
     */
    static boolean layerCovers(String titleId, String gameEnv) {
        if (titleId == null || !titleId.startsWith("0400")) return false;
        return gameEnv == null || !java.util.regex.Pattern.compile("(^|[\\s;,])DM_VK_LAYER\\s*=\\s*0\\s*($|[\\s;,])").matcher(gameEnv).find();
    }

    /**
     * The older library whose missing update is optional, null when it is not (the update is then as it was: the card with its button, or the automatic delta update): the
     * library the host plays (the newest of this APK's own ABI, {@link #playable}, never an older ABI that vka_abi_compat.h follows) made from the profile's own inputs
     * (inputs: Profile.shaderInputs; other tables change what a key's entry holds, which the layer cannot see), and a layer that covers what it lacks ({@link #layerCovers}).
     * What the older library holds plays as it did; only a shader it lacks is built, while the game runs
     */
    static File optionalUpdate(File packDir, String titleId, String current, String abi, String inputs, String gameEnv) {
        if (inputs == null || !layerCovers(titleId, gameEnv)) return null;
        File f = playable(packDir, titleId, current, abi);
        Header h = f == null ? null : header(f);
        return h != null && abi.equals(h.abi) && inputs.equals(h.inputs) ? f : null;
    }

    /**
     * Whether the game starts with no shader library to play on and builds its shaders as it plays: this version's library is not in the pack (one made from other tables
     * is, and the host loads that one), no older library of the host's ABI plays ({@link #playable}) and none from before the ABI field ({@link #legacy}), and the shader
     * layer covers the game ({@link #layerCovers}). The launcher then does not hold Play back: the card says "Ready" and Prepare shaders is an
     * optional second action (android/SHADERS.md "Shaders the library lacks"). A game the layer does not cover keeps the blocking "Shaders needed". abi, compat: as {@link #playable}
     */
    static boolean onDemand(File packDir, String titleId, String current, String abi, String compat, String gameEnv) {
        if (!layerCovers(titleId, gameEnv) || new File(packDir, "shaders/v-" + current + "/dmsh_vk.lib").isFile()) return false;
        return playable(packDir, titleId, current, abi, compat) == null && legacy(packDir, titleId, current) == null;
    }

    /**
     * The library the host plays for a game (vka_shader.c lib_load's order): this version's, else the newest older one of the host's ABI or one it follows ({@link #playable}),
     * else one from before the ABI field ({@link #legacy}); null when there is none (the game plays on the shader layer alone). LayerGuard.dropCovered reads its keys, so a
     * library is taken only when the host would take it: its header of this game and its tables and blob inside the file ({@link #structureProblem}, vka_shlib_check). A library
     * cut short is refused by the host, which then goes on to the next one or plays an empty one with the layer: trusting it here would drop requests nothing builds
     */
    static File hostLibrary(File packDir, String titleId, String current, String abi, String compat) {
        File own = new File(packDir, "shaders/v-" + current + "/dmsh_vk.lib");
        if (own.isFile() && whole(own, titleId)) return own;
        File f = playable(packDir, titleId, current, abi, compat, true);
        return f != null ? f : legacy(packDir, titleId, current, true);
    }

    /** Whether a library file is of titleId and whole as the host checks it ({@link #structureProblem}) */
    static boolean whole(File lib, String titleId) {
        Header h = header(lib);
        return h != null && (titleId == null || titleId.equals(h.game)) && structureProblem(lib) == null;
    }

    /**
     * What makes the host refuse a library file as damaged (vka_shlib_check's structural checks, after the version and game: dmsh_abi_vk.h), null when it has none: the entry,
     * binding and module tables inside the file and 8-byte aligned, the blob inside the file, then each entry (sorted by key, its bindings and modules inside their tables, at
     * least one module, its c1 bytes inside the blob, a known stage) and each module (inside the blob, a known codec, a SPIR-V size the host takes). Reads the tables once,
     * front to back (a few MB for the biggest libraries)
     */
    static String structureProblem(File lib) {
        Header h = header(lib);
        if (h == null) return "not a shader library this app reads";
        long size = lib.length();
        String t = tableProblem(h, size);
        if (t != null) return t;
        try (RandomAccessFile f = new RandomAccessFile(lib, "r")) {
            byte[] e = new byte[64];
            java.io.DataInput in = new java.io.DataInputStream(new java.io.BufferedInputStream(new FileInputStream(f.getFD()), 1 << 16));
            f.seek(h.entryOff);
            long prev = 0;
            for (long i = 0; i < h.entryCount; i++) {
                in.readFully(e);
                long key = u64(e, 0);
                if (i > 0 && Long.compareUnsigned(key, prev) <= 0) return "entry " + i + " out of order";
                prev = key;
                int stage = e[16] & 0xff, mods = e[17] & 0xff;
                long mod = u32(e, 20), bind = u32(e, 24), binds = (e[28] & 0xff) | (e[29] & 0xff) << 8;
                long c1 = u32(e, 44) | u32(e, 56) << 32, c1len = u32(e, 48);
                if (bind + binds > h.bindCount) return "entry " + i + ": bindings past the table";
                if (mod + mods > h.modCount || mods == 0) return "entry " + i + ": modules past the table";
                if (c1 + c1len > h.blobSize || c1 < 0) return "entry " + i + ": c1 bytes past the blob";
                if (stage > STAGE_MS) return "entry " + i + ": stage " + stage;
            }
            byte[] m = new byte[16];
            f.seek(h.modOff);
            in = new java.io.DataInputStream(new java.io.BufferedInputStream(new FileInputStream(f.getFD()), 1 << 16));
            for (long i = 0; i < h.modCount; i++) {
                in.readFully(m);
                long off = u32(m, 0) | ((m[14] & 0xffL) | (m[15] & 0xffL) << 8) << 32, n = u32(m, 4), stored = u32(m, 8);
                int codec = m[13] & 0xff;
                if (off + stored > h.blobSize) return "module " + i + " past the blob";
                if (codec != CODEC_RAW && codec != CODEC_ZSTD) return "module " + i + ": codec " + codec;
                if ((n & 3) != 0 || n < 20 || codec == CODEC_RAW && stored != n) return "module " + i + ": " + n + " bytes of SPIR-V, " + stored + " stored";
            }
        } catch (IOException e) {
            return "cannot be read whole (" + e.getMessage() + ")";
        }
        return null;
    }

    /** dmsh_abi_vk.h DMSH_VK_STAGE_MS, DMSH_VK_CODEC_RAW, DMSH_VK_CODEC_ZSTD */
    static final int STAGE_MS = 7, CODEC_RAW = 0, CODEC_ZSTD = 1;

    /** The header's tables and blob against the file's size (vka_shlib_check): null when they are inside it */
    static String tableProblem(Header h, long size) {
        if (size < 128) return "shorter than the header";
        if (h.entryOff + h.entryCount * 64 > size || (h.entryOff & 7) != 0) return "entry table out of the file";
        if (h.bindOff + h.bindCount * 24 > size || (h.bindOff & 7) != 0) return "binding table out of the file";
        if (h.modOff + h.modCount * 16 > size || (h.modOff & 7) != 0) return "module table out of the file";
        if (h.blobOff > size || h.blobSize > size - h.blobOff) return "blob out of the file";
        return null;
    }

    /** As above with no older ABIs */
    static File playable(File packDir, String titleId, String current, String abi) { return playable(packDir, titleId, current, abi, null); }

    /**
     * A library of another version made before the header's abi field (no ABI: whether the host reads it the same is not known), the newest; null when none. The host plays
     * it as a last resort, after any library of its ABI (vka_shader.c lib_load), with a warning instead of drawing nothing; the launcher does not block Play on it and
     * suggests one more shader update, after which updates are deltas of the same ABI
     */
    static File legacy(File packDir, String titleId, String current) { return legacy(packDir, titleId, current, false); }

    private static File legacy(File packDir, String titleId, String current, boolean whole) {
        File best = null;
        for (File d : libraries(packDir)) {
            if (d.getName().equals("v-" + current)) continue;
            File lib = new File(d, "dmsh_vk.lib");
            Header h = header(lib);
            if (h == null || !titleId.equals(h.game) || !h.abi.isEmpty() || whole && structureProblem(lib) != null) continue;
            if (best == null || lib.lastModified() > best.lastModified()) best = lib;
        }
        return best;
    }

    /** The warning for a game played on such a library */
    static final String LEGACY_WARNING = "This game's shaders were made before versioned shader updates, so this app version cannot update them in part. "
            + "You can keep playing on them, but updating the shaders once more is suggested (the game page \u203a Storage \u203a Prepare again): after that, shader fixes arrive as small incremental updates.";

    /** {@link #LEGACY_WARNING} for a game the phone can prepare (dump), else the way a game file the phone cannot prepare gets new ones: a library built on a computer */
    static String legacyWarning(boolean dump) {
        return dump ? LEGACY_WARNING : "This game's shaders were made before versioned shader updates. You can keep playing on them; new ones come from a computer "
                + "(scripts/pack_android.py), added with Import a library on the game's page.";
    }

    /**
     * Why a file picked with Import a library (PackUi.importLibraryPick) cannot go into the game's pack, in a sentence; null when it can, into shaders/v-&lt;its vk_chain&gt;/.
     * It must be a library this app reads (h: {@link #header}, null when it is not one), of this game, of this APK's ABI or one the host also plays (compat:
     * DMSH_VK_ABI_COMPAT), with a version in its header, as long as its header says (size: the file's bytes), and, when it is of this APK's own version, made from the
     * profile's shader tables (inputs: Profile.shaderInputs; such a library would be loaded and draw wrong). A library made before the ABI field is refused: whether
     * the host reads it the same is not known (it plays only as a pack's last resort, never by the player's choice)
     */
    static String importProblem(Header h, long size, String titleId, String current, String abi, String compat, String inputs) {
        if (h == null || size < 128) return "That file is not a shader library this app reads (a dmsh_vk.lib).";
        if (!titleId.equals(h.game)) return "That shader library is for another game (" + (h.game.isEmpty() ? "none named" : h.game) + "), not this one (" + titleId + ").";
        if (!h.vkChain.matches("[0-9a-f]{12}")) return "That shader library has no version in its header.";
        boolean own = h.abi.equals(abi);
        boolean older = false;
        if (compat != null) for (String c : compat.trim().split("\\s+")) older |= !c.isEmpty() && c.equals(h.abi) && !h.abi.isEmpty();
        if (h.abi.isEmpty()) return "That shader library was made before versioned shader libraries, so this app version cannot tell whether it draws right. Build it again with this app version's tools (library version " + current + ").";
        if (!own && !older) return "That shader library was made by tools whose shaders this app version cannot play (ABI " + h.abi + "). Build it again with this app version's tools (library version " + current + ").";
        if (h.vkChain.equals(current) && inputs != null && !inputs.equals(h.inputs))
            return "That shader library was built from other shader tables (inputs " + h.inputs + ") than this app's (" + inputs + "). Build it again with this app version's tools.";
        if (h.entryCount == 0) return "That shader library has no shaders in it.";
        if (tableProblem(h, size) != null || h.blobOff < 128) return "That shader library is cut short or damaged (" + size + " bytes, its header says more).";
        return null;
    }

    /**
     * The app's external files folder (getExternalFilesDir, set by NxApp in every process): the games' temporary folders are its temp/&lt;title ID&gt; (DM_TEMP, as GameActivity
     * gives it to the host). null before NxApp ran (the JVM tests): the temporary folder is then not known
     */
    static volatile File appFiles;

    /** A game's temporary folder (DM_TEMP), null when {@link #appFiles} is not known */
    static File tempOf(String titleId) { File f = appFiles; return f == null ? null : new File(f, "temp/" + titleId); }

    /**
     * Whether the shader layer has a folder it can be built in, as LayerDir.resolve picks it and the host falls back: the pack's shaders/layer (LayerDir.NAME), else
     * &lt;temp&gt;/shader_layer (LayerDir.TEMP_NAME; temp null: not known, taken as writable, the host's own fallback). Nothing is made: a folder that is not there counts
     * when its nearest existing parent can be written. Without one the host has no layer, and a game with no library would draw nothing: the launcher then does not
     * treat it as one that builds its shaders as it plays (Pack.shadersOnDemand) and says why (ShaderState.Off.UNWRITABLE)
     */
    static boolean layerWritable(File packDir, File temp) {
        if (canMake(new File(new File(packDir, "shaders"), "layer"))) return true;
        return temp == null || canMake(new File(temp, "shader_layer"));
    }

    /** Whether a folder is there and writable, or can be made: its nearest existing parent is a writable folder */
    static boolean canMake(File d) {
        File f = d.getAbsoluteFile();
        while (f != null && !f.exists()) f = f.getParentFile();
        return f != null && f.isDirectory() && f.canWrite();
    }

    /** The versions of the libraries of titleId's game a pack folder has, other than current (what a delta update can start from) */
    static List<String> older(File packDir, String titleId, String current) {
        List<String> out = new java.util.ArrayList<>();
        for (File d : libraries(packDir)) {
            String v = d.getName().substring(2);
            Header h = header(new File(d, "dmsh_vk.lib"));
            if (!v.equals(current) && h != null && titleId.equals(h.game)) out.add(v);
        }
        return out;
    }

    /**
     * How many shaders a delta update translates when the pack has the older versions given: the fewest of the profile's "from" rows (delta: "version=count,…", from the
     * manifest's from rows) for those versions; -1 when the manifest has none of them (the update then translates as many as a full build, or more as the tool finds)
     */
    static int toTranslate(String delta, List<String> versions) {
        int best = -1;
        if (delta == null) return -1;
        for (String row : delta.split(",")) {
            int eq = row.indexOf('=');
            if (eq <= 0 || !versions.contains(row.substring(0, eq).trim())) continue;
            try {
                int n = Integer.parseInt(row.substring(eq + 1).trim());
                if (n >= 0 && (best < 0 || n < best)) best = n;
            } catch (NumberFormatException e) {
                // a malformed row is not a measurement
            }
        }
        return best;
    }

    /**
     * The phone's time for a shaders step that translates n shaders, as "a-b" minutes: the split of the dump (4-8 min, android/ONDEVICE.md "Time and power"), each shader
     * translated (45-80 ms: the step's 20-35 min for DKB's 22,852 less the split), and the library written and checked (about 1 min)
     */
    static int[] minutes(int n) {
        int lo = 4 + 1 + (int) Math.round(n * 0.045 / 60), hi = 8 + 1 + (int) Math.ceil(n * 0.080 / 60);
        return new int[] {lo, Math.max(hi, lo + 1)};
    }

    // ---- Sampled keep (android/SHADERS.md "Sampled keep"; dm/gfx/tools/shader_sample.py decides, this class says when to ask and what it left) ----

    /** The marker a sample's acceptance leaves in the kept library's folder (shader_sample.py KEEP_FILE); the host reads it as "a kept library" (vka_shader.c lib_load) */
    static final String KEEP_FILE = "sampled-keep.json";
    static final int SAMPLE_DEFAULT = 300;
    /** The quick check is ON unless the player's lines for the game switch it off (DM_SHADER_SAMPLE=0): a player's pack holds the whole library (some 40,000 shaders), so 300 sampled ones are cheap next to a full build or a layer that fills it again.
     *  (Its phone "hang" of 2026-11-16 was Android freezing a plain started service: it runs as a foreground service now, LayerService) */
    static final boolean SAMPLE_ON_BY_DEFAULT = true;

    /** Whether a library is one a sample kept (its folder holds the marker): its played shaders are verified by the layer, and requests it covers are not dropped (LayerGuard.dropCovered) */
    static boolean kept(File lib) { return lib != null && lib.getParentFile() != null && new File(lib.getParentFile(), KEEP_FILE).isFile(); }

    /** The sample size the player's lines for the game give (DM_SHADER_SAMPLE=&lt;n&gt;, 0 = no sampled keep), SAMPLE_DEFAULT without one */
    static int sampleSize(String gameEnv) { return envInt(gameEnv, "DM_SHADER_SAMPLE", SAMPLE_ON_BY_DEFAULT ? SAMPLE_DEFAULT : 0); }

    /** How many of the sample come from the game's starter set (shader_sample.py --first; DM_SHADER_SAMPLE_FIRST=&lt;n&gt; in the game's lines), SAMPLE_FIRST_DEFAULT without one */
    static final int SAMPLE_FIRST_DEFAULT = 200;
    static int sampleFirst(String gameEnv) { return envInt(gameEnv, "DM_SHADER_SAMPLE_FIRST", SAMPLE_FIRST_DEFAULT); }

    /** The last NAME=&lt;digits&gt; of the lines, dflt without one */
    private static int envInt(String gameEnv, String name, int dflt) {
        if (gameEnv == null) return dflt;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(^|[\\s;,])" + name + "\\s*=\\s*(\\d{1,9})\\s*($|[\\s;,])").matcher(gameEnv);
        int n = dflt;
        while (m.find()) n = Integer.parseInt(m.group(2));
        return n;
    }

    /** The result file of a sample for this version (shader_sample.py result_path): shaders/.sample-&lt;current&gt;.json */
    static File sampleResultFile(File packDir, String current) { return new File(packDir, "shaders/.sample-" + current + ".json"); }

    /** What the last sample for this version concluded about which older library: {"accepted"|"declined", the older version}, null when none ran. Plain reading of the small JSON file */
    static String[] sampleResult(File packDir, String current) {
        File f = sampleResultFile(packDir, current);
        if (!f.isFile() || f.length() > 4096) return null;
        try {
            String t = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            java.util.regex.Matcher o = java.util.regex.Pattern.compile("\"old\"\\s*:\\s*\"([0-9a-f]{12})\"").matcher(t);
            if (!o.find()) return null;
            return new String[] {t.matches("(?s).*\"accepted\"\\s*:\\s*true.*") ? "accepted" : "declined", o.group(1)};
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The older library a sample may keep as this version's, null when none (the update goes on as it did): the pack has no library of this version, the game's layer covers it
     * and an older library of this APK's own ABI and inputs plays ({@link #optionalUpdate}: the layer is what verifies the shaders played afterwards), the sample size is not 0
     * and no sample for this version has concluded about that older library yet (one that declined it is not asked again: the pack updates the other ways)
     */
    static File sampleCandidate(File packDir, String titleId, String current, String abi, String inputs, String gameEnv) { return sampleCandidate(packDir, titleId, current, abi, null, null, inputs, gameEnv); }

    /**
     * As above, and an older library of an ABI the host plays whose modules differ in nothing (compat: the host's list, same: those of its rows with no difference, vka_abi_compat.h)
     * is a candidate too: the sample compares the new tools' entries with its own, so identical entries are what a library of this ABI holds, and the stamp sets the ABI
     */
    static File sampleCandidate(File packDir, String titleId, String current, String abi, String compat, String same, String inputs, String gameEnv) {
        if (sampleSize(gameEnv) <= 0 || new File(packDir, "shaders/v-" + current + "/dmsh_vk.lib").isFile()) return null;
        File old = optionalUpdate(packDir, titleId, current, abi, inputs, gameEnv);
        if (old == null && inputs != null && layerCovers(titleId, gameEnv) && compat != null && same != null && !same.trim().isEmpty()) {
            File f = playable(packDir, titleId, current, abi, compat);
            Header h = f == null ? null : header(f);
            if (h != null && inputs.equals(h.inputs) && java.util.Arrays.asList(same.trim().split("\\s+")).contains(h.abi)) old = f;
        }
        if (old == null) return null;
        String[] r = sampleResult(packDir, current);
        return r != null && old.getParentFile().getName().equals("v-" + r[1]) ? null : old;
    }

    // ---- Known transitions (dm/gfx/vk_android/vka_known_transitions.tsv, BuildConfig.NX_KNOWN_FROM; shader_sample.py known_transition) ----

    static final int STAMP_CHAIN_AT = 8, STAMP_CHAIN_LEN = 16;

    /** Whether an older library of this VK chain (its header's version field) is kept as this APK's without a sampled check: known, the space-separated older chains of the table's rows for this chain */
    static boolean knownTransition(String known, String oldVkChain) {
        if (known == null || oldVkChain == null || !oldVkChain.matches("[0-9a-f]{12}")) return false;
        return java.util.Arrays.asList(known.trim().split("\\s+")).contains(oldVkChain);
    }

    /** The marker of a known transition (as shader_sample.py known_record): what was kept and why, no keys */
    static String knownRecord(String oldVersion, String fromChain, String current, long nowMs) {
        String date = java.time.Instant.ofEpochMilli(nowMs).toString().substring(0, 10);   // (UTC, as shader_sample.py's time.gmtime)
        return "{\"kind\": \"known-transition\", \"old\": \"" + oldVersion + "\", \"from_chain\": \"" + fromChain + "\", \"version\": \"" + current + "\", \"sample\": 0, \"mismatches\": 0, \"date\": \"" + date + "\"}\n";
    }

    /** The folder a known transition moves the older library through (hidden from the host and the launcher, which take only v-&lt;V&gt;), and its journal in it */
    static final String KNOWN_DIR = ".known-", KNOWN_JOURNAL = "known-move.txt";
    /** Why the last knownKeep or knownRecover left a library alone (for the log), null when it did not */
    static volatile String knownWhy;

    /**
     * An older library (a sample candidate: this game, this ABI or one that differs in nothing, the profile's inputs) whose header's VK chain the table knows for this chain is
     * MOVED to this version's folder with no sampled check and no copy (user decision 2026-11-16: no extra space): its folder shaders/v-&lt;old&gt;/ is renamed to
     * shaders/.known-&lt;current&gt;/ (one rename in the same folder, so the same file system), the header's version, abi and chain fields are rewritten in place (128 bytes, synced),
     * the marker {@link #KEEP_FILE} (kind known-transition) is written last, and the folder renamed to shaders/v-&lt;current&gt;/; its inputs.tsv moves with it unchanged (it holds
     * the game and the inputs, no version). A journal (known-move.txt: the older version and the three old header fields) is written into the older folder before the first
     * rename, so a crash at any step is finished at the next start ({@link #knownRecover}); a first rename that fails leaves the older library where it was (no copy), and a
     * failure after it puts the older library back. The older library is gone afterwards: an older APK of its chain finds no library of its own and updates it again. Only the
     * table's old VK chain qualifies (a library made by another chain is never moved). → the older version moved, null when nothing changed ({@link #knownWhy} says why)
     */
    static String knownKeep(File packDir, File oldLib, String current, String abi, String chain, String known, long nowMs) {
        knownWhy = null;
        if (oldLib == null || current == null || !current.matches("[0-9a-f]{12}") || abi == null || !abi.matches("[0-9a-f]{12}") || chain == null || !chain.matches("[0-9a-f]{12}")) { knownWhy = "bad arguments"; return null; }
        File sh = new File(packDir, "shaders"), want = new File(sh, "v-" + current), mid = new File(sh, KNOWN_DIR + current), from = oldLib.getParentFile();
        synchronized (RESTAMP) {
            knownRecover(packDir, current, nowMs);
            Header h = header(oldLib);
            if (h == null || !knownTransition(known, h.vkChain)) { knownWhy = "not a known chain"; return null; }
            if (from == null || !from.getName().startsWith("v-") || !sh.equals(from.getParentFile())) { knownWhy = "not a library folder of this pack"; return null; }
            if (new File(want, "dmsh_vk.lib").isFile()) { knownWhy = "the pack has a library of this version"; return null; }
            if (want.isDirectory() && !want.delete()) { knownWhy = "v-" + current + " is a folder that is not empty"; return null; }
            String old = from.getName().substring(2);
            File journal = new File(from, KNOWN_JOURNAL);
            try {
                Files.write(journal.toPath(), (old + "\t" + h.vkChain + "\t" + nn(h.abi) + "\t" + nn(h.chain) + "\t" + current + "\t" + abi + "\t" + chain + "\n").getBytes(StandardCharsets.US_ASCII));
            } catch (IOException e) { knownWhy = "no journal: " + e.getMessage(); return null; }
            if (!from.renameTo(mid)) { journal.delete(); knownWhy = "the rename failed (left where it was, nothing copied)"; return null; }
            return finishMove(packDir, mid, nowMs) ? old : null;
        }
    }

    private static String nn(String s) { return s == null || s.isEmpty() ? "-" : s; }

    /**
     * Finishes (or undoes) a known-transition move that a crash left in shaders/.known-&lt;current&gt;/: done with this APK's start, before any library is chosen. A pack that has
     * a library of this version meanwhile gets the older one back (header fields restored, renamed to v-&lt;old&gt;). → whether a move was finished
     */
    static boolean knownRecover(File packDir, String current, long nowMs) {
        File mid = new File(packDir, "shaders/" + KNOWN_DIR + current);
        synchronized (RESTAMP) {
            if (!mid.isDirectory()) return false;
            return finishMove(packDir, mid, nowMs);
        }
    }

    /** The steps after the first rename, each one safe to do again: rewrite the header in place, write the marker, rename to v-&lt;current&gt;, write the result file; on a failure, back to v-&lt;old&gt; */
    private static boolean finishMove(File packDir, File mid, long nowMs) {
        File sh = mid.getParentFile(), journal = new File(mid, KNOWN_JOURNAL), lib = new File(mid, "dmsh_vk.lib");
        String[] j;
        try { j = new String(Files.readAllBytes(journal.toPath()), StandardCharsets.US_ASCII).trim().split("\t"); } catch (IOException e) { j = null; }
        if (j == null || j.length != 7) { knownWhy = "a move folder without its journal: " + mid.getName() + " left as it is"; return false; }
        String old = j[0], from = j[1], oldAbi = j[2].equals("-") ? "" : j[2], oldChain = j[3].equals("-") ? "" : j[3], current = j[4], abi = j[5], chain = j[6];
        File want = new File(sh, "v-" + current), back = new File(sh, "v-" + old);
        try {
            if (new File(want, "dmsh_vk.lib").isFile()) throw new IOException("the pack has a library of this version");
            writeHeaderFields(lib, current, abi, chain);
            Files.write(new File(mid, KEEP_FILE).toPath(), knownRecord(old, from, current, nowMs).getBytes(StandardCharsets.UTF_8));   // the marker last: a kept library is whole
            if (want.isDirectory()) want.delete();
            journal.delete();
            if (!mid.renameTo(want)) throw new IOException("the last rename failed");
        } catch (IOException | RuntimeException e) {   // back as it was: the older library, its header and its folder
            knownWhy = "moved back: " + e.getMessage();
            try {
                writeHeaderFields(lib, old, oldAbi, oldChain);
                new File(mid, KEEP_FILE).delete();
                journal.delete();
                if (!back.exists()) mid.renameTo(back);
            } catch (IOException | RuntimeException e2) { knownWhy += "; and not back: " + e2.getMessage(); }
            return false;
        }
        File r = sampleResultFile(packDir, current), rt = new File(r.getPath() + ".tmp");
        try {
            Files.write(rt.toPath(), ("{\"kind\": \"known-transition\", \"accepted\": true, \"old\": \"" + old + "\", \"version\": \"" + current + "\", \"sample\": 0, \"mismatches\": 0, \"why\": \"known transition " + from + " to " + current + "\"}\n").getBytes(StandardCharsets.UTF_8));
            Files.move(rt.toPath(), r.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { rt.delete(); }   // (the library is in: the result file only records it)
        return true;
    }

    /** The header's version, abi and chain fields (zero-padded ASCII, as restamped_header) written in place, synced */
    private static void writeHeaderFields(File lib, String version, String abi, String chain) throws IOException {
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(lib, "rw")) {
            if (f.length() < 128) throw new IOException("short library");
            f.seek(STAMP_CHAIN_AT); f.write(field(chain, STAMP_CHAIN_LEN));
            f.seek(STAMP_VERSION_AT); f.write(field(version, STAMP_VERSION_LEN));
            f.seek(STAMP_ABI_AT); f.write(field(abi, STAMP_ABI_LEN));
            f.getFD().sync();
        }
    }

    private static byte[] field(String v, int len) {
        byte[] b = new byte[len], s = (v == null ? "" : v).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(s, 0, b, 0, Math.min(s.length, len));
        return b;
    }

    // ---- Restamp: the manifest's "stamp" rows (vk_android_delta.py STAMP_FIELDS, restamped_header) ----

    static final int STAMP_VERSION_AT = 24, STAMP_VERSION_LEN = 16, STAMP_ABI_AT = 116, STAMP_ABI_LEN = 12;
    private static final Object RESTAMP = new Object();   // one restamp at a time (two cards may name the same pack)

    /**
     * Writes this version's library (shaders/v-&lt;current&gt;/) from an older one of the pack that a "stamp" row names, when the pack has none of its own: the older file's
     * SHA-256 must be the row's first hash; it is copied with its header's version set to current and its abi to abi, into shaders/.restamp-&lt;current&gt;/ with the
     * older folder's inputs.tsv, and moved in by rename only when the copy's SHA-256 is the row's second hash (the Mac's library of this version, byte for byte). The
     * older library stays. stamps: "version:old SHA-256:new SHA-256", ';' between them. → the older version taken over, null when none was (nothing changed then)
     */
    static String restamp(File packDir, String titleId, String current, String abi, String stamps) {
        if (stamps == null || stamps.isEmpty() || current == null || !current.matches("[0-9a-f]{12}") || abi == null || !abi.matches("[0-9a-f]{12}")) return null;
        File sh = new File(packDir, "shaders"), want = new File(sh, "v-" + current), tmp = new File(sh, ".restamp-" + current);
        synchronized (RESTAMP) {
            if (new File(want, "dmsh_vk.lib").isFile()) return null;
            for (String row : stamps.split(";")) {
                String[] c = row.trim().split(":");
                if (c.length != 3 || !c[0].matches("[0-9a-f]{12}") || c[0].equals(current) || !c[1].matches("[0-9a-f]{64}") || !c[2].matches("[0-9a-f]{64}")) continue;
                File from = new File(sh, "v-" + c[0]), lib = new File(from, "dmsh_vk.lib");
                Header h = header(lib);
                if (h == null || !titleId.equals(h.game)) continue;
                try {
                    if (!c[1].equals(sha256(lib))) continue;   // not the library the Mac compared (damaged, or made by other tools)
                    if (sh.getUsableSpace() < lib.length() + (64L << 20)) return null;
                    deleteTree(tmp);
                    if (!tmp.mkdirs()) continue;
                    if (!c[2].equals(copyStamped(lib, new File(tmp, "dmsh_vk.lib"), current, abi))) { deleteTree(tmp); continue; }
                    File in = new File(from, "inputs.tsv");
                    if (in.isFile()) Files.copy(in.toPath(), new File(tmp, "inputs.tsv").toPath());
                    if (want.isDirectory()) want.delete();   // an empty folder of this version (a stopped job's): only an empty one goes
                    if (!tmp.renameTo(want)) { deleteTree(tmp); continue; }
                    return c[0];
                } catch (IOException | NoSuchAlgorithmException e) {
                    deleteTree(tmp);
                }
            }
        }
        return null;
    }

    private static String hex(byte[] d) {
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return b.toString();
    }

    static String sha256(File f) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[1 << 20];
        try (InputStream in = new FileInputStream(f)) {
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    /** Copies a library with its version and abi fields set (as vk_android_delta.py restamped_header), synced; → the copy's SHA-256 */
    private static String copyStamped(File src, File dst, String version, String abi) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[1 << 20];
        try (InputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] h = new byte[128];
            for (int got = 0, n; got < h.length; got += n) if ((n = in.read(h, got, h.length - got)) < 0) throw new IOException("short library");
            java.util.Arrays.fill(h, STAMP_VERSION_AT, STAMP_VERSION_AT + STAMP_VERSION_LEN, (byte) 0);
            byte[] v = version.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(v, 0, h, STAMP_VERSION_AT, Math.min(v.length, STAMP_VERSION_LEN));
            java.util.Arrays.fill(h, STAMP_ABI_AT, STAMP_ABI_AT + STAMP_ABI_LEN, (byte) 0);
            byte[] a = abi.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(a, 0, h, STAMP_ABI_AT, Math.min(a.length, STAMP_ABI_LEN));
            out.write(h); md.update(h);
            for (int n; (n = in.read(buf)) > 0; ) { out.write(buf, 0, n); md.update(buf, 0, n); }
            out.getFD().sync();
        }
        return hex(md.digest());
    }

    private static void deleteTree(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File x : l) deleteTree(x);
        f.delete();
    }

    /**
     * A game card's state while a background shader update runs (MainActivity's card, from the update's job): "Ready · updating shaders 1,234 / 22,852 · about 12 min
     * left", "Ready · shader update paused 1,234 / 22,852", or without the count while the job has none yet. eta: Job.etaText ("" while unknown)
     */
    static String updatingText(Locale l, long done, long total, String eta, boolean paused) {
        String count = total > 0 ? String.format(l, " %,d / %,d", Math.min(done, total), total) : "";
        return (paused ? Words.s("home_ready_shader_update_paused", "Ready · shader update paused") : Words.s("home_ready_updating_shaders", "Ready · updating shaders")) + count + (!paused && eta != null && !eta.isEmpty() ? " · " + eta : "");
    }

    static String minutesText(int n) {
        int[] m = minutes(n);
        return String.format(Locale.ROOT, "about %d-%d minutes", m[0], m[1]);
    }

    /**
     * A whole Prepare (the code rewritten, then every shader of the game's map, n: Profile.shaderTotal) as "a-b" minutes: the rewrite's 2-5 minutes on the phone
     * (Donkey Kong Bananza 74 s, ONDEVICE.md "Phone test status") and the shaders step as {@link #minutes}. DKB (22,852 shaders) 24-45, Mario Kart World (11,604) 16-30
     */
    static String jobMinutesText(int n) {
        int[] m = minutes(n);
        return String.format(Locale.ROOT, "about %d-%d minutes", m[0] + 2, m[1] + 5);
    }

    /** The profile's selected shader set: its file in dm/games/&lt;title ID&gt;/ (rows "key&lt;TAB&gt;stage"; android/SHADERS.md "Selected shaders") */
    static final String SELECTED = "shader_seen.tsv";

    /**
     * The shader keys the shaders step passes to vk_android_build.py for a profile with a selected set ({@link #SELECTED}): its keys that the profile's shader_map.tsv
     * has (column 4), sorted, each once; the tool adds the pixel shaders paired with them (dmsh_build.py build_keys). null when the profile has no selected set: the
     * step then builds every shader of the map. The same keys the Mac's selected build takes (docs/development.md, Building selected Mac shaders), so the phone's
     * library is the Mac's.
     */
    static List<String> selection(File profile) throws IOException {
        File seen = new File(profile, SELECTED);
        if (!seen.isFile()) return null;
        Set<String> map = new HashSet<>();
        for (String l : Files.readAllLines(new File(profile, "shader_map.tsv").toPath(), StandardCharsets.UTF_8)) {
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("package\t")) continue;
            String[] c = l.split("\t", 5);
            if (c.length > 3) map.add(c[3]);
        }
        TreeSet<String> keys = new TreeSet<>();
        for (String l : Files.readAllLines(seen.toPath(), StandardCharsets.UTF_8)) {
            if (l.length() < 16 || !l.substring(0, 16).matches("[0-9a-f]{16}")) continue;
            String k = l.substring(0, 16);
            if (map.contains(k)) keys.add(k);
        }
        return new ArrayList<>(keys);
    }

    /** The profile's keys that a dump of one of its registered builds may lack: column 1 of dm/games/&lt;title ID&gt;/shader_optional.tsv (scripts/pack_android.py optional_keys) */
    static final String OPTIONAL = "shader_optional.tsv";

    /** The keys of a shader_map.tsv (column 4 of its package rows, as pack_android.py map_keys reads them) */
    static Set<String> mapKeys(File map) throws IOException {
        Set<String> keys = new HashSet<>();
        for (String l : Files.readAllLines(map.toPath(), StandardCharsets.UTF_8)) {
            String[] c = l.split("\t", 6);
            if (c.length > 4 && c[0].endsWith(".bin")) keys.add(c[3]);
        }
        return keys;
    }

    /** A table's rows without its comment lines ("#"): the header prose is the tool's wording at the time, not the dump's (shader_split.py's changed to "no PC shader
     *  pairing supplied", and the profiles split before still carry the older line), so two maps of the same shaders differ only there */
    static List<String> tableRows(File f) throws IOException {
        List<String> out = new ArrayList<>();
        for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) if (!l.startsWith("#")) out.add(l);
        return out;
    }

    /** Whether a split's shader_map.tsv lists the shaders of the profile's: the same rows in the same order, comment lines left out ({@link #tableRows}) */
    static boolean sameMap(File profileMap, File splitMap) throws IOException { return tableRows(profileMap).equals(tableRows(splitMap)); }

    /**
     * The keys of the profile's shader_map.tsv that a split's map (splitMap) lacks, sorted, when the split is the profile's but for keys its {@link #OPTIONAL} names
     * (a registered build of the game without a few of its shaders: Mario Kart World's 1.8.0 as an update, docs/reference/packing.md "A game with several registered
     * builds", or another of the versions its shader_versions lists: {@link #otherVersionKeys}); null when the split has a key the profile's map does not, lacks one that is
     * not optional, or lacks none. The shaders step then builds the map's other keys.
     */
    static List<String> absentOptional(File profile, File splitMap) throws IOException { return absentOptional(profile, splitMap, -1); }

    // A profile whose shader tables are the union of several versions of the game (game.tsv shader_versions), prepared from a dump of one of them. shader_split.py
    // prefixes the source of a shader that first appears in a later version with that version (/v1507328/Common/..., main@v1507328; the first version's rows carry
    // none), so a dump of one listed version may lack exactly the rows tagged with the other listed versions: they count as optional (absentOptional below), with no
    // failure and no Try anyway. Mario Kart 8 Deluxe's tables are 4.0.0's and 4.0.1's: 135 of 29,785 rows are 4.0.1's own, and a dump of 4.0.0 was refused for them.
    // The tags do not say which untagged rows a later version dropped: such a dump is refused as before, unless shader_optional.tsv names them.
    // scripts/pack_android.py may_lack is the same rule on the PC
    /** game.tsv's shader_versions ("v0", "v327680"): the versions the profile's shader tables are the union of; empty when it names none or cannot be read */
    static List<String> shaderVersions(File profile) {
        List<String> out = new ArrayList<>();
        try {
            for (String l : Files.readAllLines(new File(profile, "game.tsv").toPath(), StandardCharsets.UTF_8)) {
                String[] c = l.split("\t", 2);
                if (c.length == 2 && c[0].trim().equals("shader_versions")) for (String v : c[1].trim().split("\\s+")) if (v.matches("v[0-9]+")) out.add(v);
            }
        } catch (IOException e) {
            out.clear();
        }
        return out;
    }

    /** The listed versions other than the dump's, when the dump's version is listed; else none (a single-version profile, a version the tables were not made from) */
    static List<String> otherVersions(File profile, long version) {
        List<String> listed = shaderVersions(profile);
        if (!listed.contains("v" + version)) return new ArrayList<>();
        listed.removeIf(v -> v.equals("v" + version));
        return listed;
    }

    /** The keys of the profile's map whose source is tagged with a listed version other than the dump's: shaders only those versions have */
    static Set<String> otherVersionKeys(File profile, long version) throws IOException {
        Set<String> keys = new HashSet<>();
        List<String> others = otherVersions(profile, version);
        if (others.isEmpty()) return keys;
        for (String l : Files.readAllLines(new File(profile, "shader_map.tsv").toPath(), StandardCharsets.UTF_8)) {
            String[] c = l.split("\t", 6);
            if (c.length <= 4 || !c[0].endsWith(".bin")) continue;
            for (String v : others) {
                int at = c[4].indexOf("@" + v), end = at + v.length() + 1;
                if (c[4].startsWith("/" + v + "/") || at >= 0 && (end >= c[4].length() || !Character.isDigit(c[4].charAt(end)))) { keys.add(c[3]); break; }
            }
        }
        return keys;
    }

    /** The one line for the log when a dump lacks only what other listed versions have (PackService), as scripts/pack_android.py prints it */
    static String otherVersionsLine(int theirs, int total, List<String> others, long version) {
        return String.format(Locale.US, "%,d of %,d shaders in this app's table belong to other versions of the game (%s): not needed for v%d", theirs, total,
                String.join(", ", others), version);
    }

    /** version: the dump's (-1: not given); the keys only another listed version has (otherVersionKeys) may be lacking too, with or without the optional file */
    static List<String> absentOptional(File profile, File splitMap, long version) throws IOException {
        File opt = new File(profile, OPTIONAL);
        Set<String> optional = otherVersionKeys(profile, version);
        if (!opt.isFile() && optional.isEmpty()) return null;
        Set<String> want = mapKeys(new File(profile, "shader_map.tsv")), have = mapKeys(splitMap);
        if (!want.containsAll(have)) return null;
        if (opt.isFile())
            for (String l : Files.readAllLines(opt.toPath(), StandardCharsets.UTF_8)) if (!l.isEmpty() && !l.startsWith("#")) optional.add(l.split("\t", 2)[0]);
        TreeSet<String> absent = new TreeSet<>(want);
        absent.removeAll(have);
        return !absent.isEmpty() && optional.containsAll(absent) ? new ArrayList<>(absent) : null;
    }
}
