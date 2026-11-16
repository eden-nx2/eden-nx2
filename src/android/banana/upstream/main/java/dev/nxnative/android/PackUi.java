package dev.nxnative.android;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * A game's Export pack… and Import pack… (its Manage sheet), over PackZip. Export writes the game's pack as one stored zip (banana-pack-&lt;id&gt;-&lt;version&gt;.zip)
 * into a folder the user picks; Import takes such a zip, or a pack folder, checks it (its title is this game's, its code fits the game build in the game folder:
 * PackCheck.blocking, a shader library is in it) and puts it into the app's packs/&lt;id&gt; (AppStorage), keeping the pack that was there until the new one is in.
 * The game's card then uses it (a picked pack folder is let go). An older library goes through the usual restamp or shader update at Play. Both refuse while the
 * game runs; Play waits while one works (busy).
 */
final class PackUi {
    static final int PICK_EXPORT = 40, PICK_IMPORT_ZIP = 41, PICK_IMPORT_DIR = 42, PICK_IMPORT_LIB = 43;
    private static final Set<String> busy = new HashSet<>();
    private static Game pending;
    private static Runnable pendingAgain;   // Import a library: the game's page, built again once the library is in

    private PackUi() {}

    /** Whether an export or import of this game's pack is working (MainActivity.launch waits) */
    static synchronized boolean busy(String titleId) { return busy.contains(titleId); }

    private static synchronized boolean take(String titleId) { return busy.add(titleId); }

    private static synchronized void give(String titleId) { busy.remove(titleId); }

    private static boolean ready(MainActivity a, Game g) {
        if (a.needAccess()) return false;
        if (busy(g.titleId)) { a.error(Tx.s(R.string.err_pack_still_working, g.name)); return false; }
        if (SaveUi.gameRunning(a, g)) { a.error(Tx.s(R.string.err_game_running_stop_first, g.name)); return false; }
        return true;
    }

    static void exportPick(MainActivity a, Game g) {
        if (!ready(a, g)) return;
        try {
            Pack.read(new File(g.packDir));
        } catch (IOException e) {
            a.error(Tx.s(R.string.err_no_pack_to_export, e.getMessage()));
            return;
        }
        pending = g;
        a.pickFolder(PICK_EXPORT, Tx.s(R.string.prepare_pick_the_folder_for_the));
    }

    static void importPick(MainActivity a, Game g) {
        if (!ready(a, g)) return;
        new PadFocus.Builder(a).setTitle(Tx.s(R.string.prepare_import_pack_from, g.name))
                .setItems(new String[] {Tx.s(R.string.prepare_a_zip_file_made_by), Tx.s(R.string.prepare_a_pack_folder_with_pack)}, (d, w) -> {
                    pending = g;
                    if (w == 1) { a.pickFolder(PICK_IMPORT_DIR, Tx.s(R.string.common_pick_the_pack_folder_the)); return; }
                    Toast.makeText(a, Tx.s(R.string.prepare_pick_the_pack_s_zip), Toast.LENGTH_LONG).show();
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                    i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"));
                    a.startActivityForResult(i, PICK_IMPORT_ZIP);
                })
                .setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }

    /**
     * adb, test app only (android/TESTING.md "Packs in app storage"): am start -n dev.nxnative.android/.MainActivity --es pack_title &lt;title ID&gt;
     * [--es pack_game &lt;game folder or .nup&gt;] (--es pack_export &lt;folder&gt; | --es pack_import &lt;.zip or pack folder&gt;) runs Export pack… / Import pack…
     * of the listed game of that title (the entry of that game path, else the first) without the pickers, with the same checks and dialogs
     */
    static void fromIntent(MainActivity a, Intent i) {
        String id = i.getStringExtra("pack_title"), gp = i.getStringExtra("pack_game"), ex = i.getStringExtra("pack_export"), im = i.getStringExtra("pack_import");
        if (BuildConfig.NX_PLAY || id == null || ex == null && im == null) return;
        for (Game g : Game.load(a)) {
            if (!g.titleId.equals(id) || gp != null && !g.gameDir.equals(gp)) continue;
            if (ex != null) exportRun(a, g, new File(ex));
            else importRun(a, g, new File(im));
            return;
        }
        a.error(Tx.s(R.string.err_no_game_of_id, id));
    }

    /**
     * Import a library (the game page's shader rows): the file picker for one dmsh_vk.lib built on a computer (scripts/pack_android.py), read through its document (no
     * path needed), checked (ShaderLibs.importProblem: this game, this APK's ABI or one the host also plays, a version, its size) and moved into the pack's
     * shaders/v-&lt;its version&gt;/ by rename; again: the page, built again after
     */
    static void importLibraryPick(MainActivity a, Game g, Runnable again) {
        if (!ready(a, g)) return;
        pending = g;
        pendingAgain = again;
        Toast.makeText(a, Tx.s(R.string.prepare_pick_the_game_s_dmsh), Toast.LENGTH_LONG).show();
        a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_IMPORT_LIB);
    }

    /** The picked library into the pack, off the main thread (a library is hundreds of MB): copied under a temporary name in the pack's shaders/, checked, renamed in */
    private static void importLibraryRun(MainActivity a, Game g, Uri doc, Runnable again) {
        if (!ready(a, g)) return;
        File pack = new File(g.packDir), shaders = new File(pack, "shaders"), tmp = new File(shaders, ".import-dmsh_vk.lib.tmp");
        Profile prof = Profile.of(g.titleId);
        run(a, g, Tx.s(R.string.prepare_importing_the_shader_library), () -> {
            if (!pack.isDirectory()) throw new IOException("the game has no pack folder yet (" + pack + "): prepare it, or import a whole pack, first");
            if (!shaders.isDirectory() && !shaders.mkdirs()) throw new IOException("the pack's shaders folder cannot be made (" + shaders + ")");
            long size = -1;
            try (android.database.Cursor c = a.getContentResolver().query(doc, new String[] {android.provider.OpenableColumns.SIZE}, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) size = c.getLong(0);
            } catch (RuntimeException e) { /* not told: checked after the copy */ }
            if (size >= 0 && shaders.getUsableSpace() < size + (64L << 20))
                throw new IOException("the library needs " + SaveStore.size(size) + ", and the pack's storage has " + SaveStore.size(shaders.getUsableSpace()) + " free");
            tmp.delete();   // one an earlier import left
            try (java.io.InputStream in = a.getContentResolver().openInputStream(doc); java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                if (in == null) throw new IOException("the file cannot be opened");
                byte[] buf = new byte[1 << 20];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                out.getFD().sync();
            }
            try {
                ShaderLibs.Header h = ShaderLibs.header(tmp);
                String bad = ShaderLibs.importProblem(h, tmp.length(), g.titleId, BuildConfig.NX_DMSH_VK_CHAIN, BuildConfig.NX_DMSH_VK_ABI, BuildConfig.NX_DMSH_VK_ABI_COMPAT,
                        prof == null ? null : prof.shaderInputs);
                if (bad != null) throw new IOException(bad);
                File dir = new File(shaders, "v-" + h.vkChain), lib = new File(dir, "dmsh_vk.lib");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("the folder " + dir + " cannot be made");
                if (SaveUi.gameRunning(a, g)) throw new IOException(g.name + " was started meanwhile");
                // its stamp first (dmsh_chain.py write_stamp's form): a stamp of the library this one replaces would name other inputs (Pack.libraryProblem reads it)
                File st = new File(dir, "inputs.tsv"), stTmp = new File(dir, "inputs.tsv.tmp");
                java.nio.file.Files.write(stTmp.toPath(), ("# the game and the profile inputs this library was built from (dm/gfx/tools/dmsh_chain.py inputs)\ngame\t" + h.game + "\ninputs\t" + h.inputs + "\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (!stTmp.renameTo(st)) throw new IOException("the library's stamp cannot be written in " + dir);
                if (!tmp.renameTo(lib)) throw new IOException("the library cannot be moved into " + dir);
                return h.vkChain;
            } finally {
                tmp.delete();
            }
        }, (r, err) -> {
            if (err != null) {
                Ui.explain(a, Tx.s(R.string.prepare_shader_library_not_imported), Tx.s(R.string.prepare_sentence_nothing_in_pack_changed, PrepareText.capital(err) + (err.endsWith(".") ? "" : ".")), "Pack folder: " + pack)
                        .setPositiveButton(android.R.string.ok, null).show();
                return;
            }
            String v = (String) r;
            String what = Pack.libraryProblem(pack, g.titleId) == null ? Tx.s(R.string.prepare_it_is_this_app_version)
                    : Tx.s(R.string.prepare_library_of_version, v);
            Ui.explain(a, Tx.s(R.string.prepare_shader_library_imported), Tx.s(R.string.prepare_library_imported_note, what), "Into: " + new File(shaders, "v-" + v + "/dmsh_vk.lib"))
                    .setPositiveButton(android.R.string.ok, null).show();
            if (again != null) again.run();
            a.refresh();
        });
    }

    /** MainActivity.onActivityResult, before a folder's path is read: the .zip picked for Import pack…, the library picked for Import a library */
    static boolean documentResult(MainActivity a, int request, Uri doc) {
        if (request == PICK_IMPORT_LIB) {
            Game g = pending;
            Runnable again = pendingAgain;
            pending = null;
            pendingAgain = null;
            if (g != null) importLibraryRun(a, g, doc, again);
            return true;
        }
        if (request != PICK_IMPORT_ZIP) return false;
        Game g = pending;
        pending = null;
        if (g == null) return true;
        String p = PathUtil.documentToPath(doc);
        if (p == null) { a.error(Tx.s(R.string.err_file_not_on_storage_path, doc)); return true; }
        importRun(a, g, new File(p));
        return true;
    }

    /** MainActivity.onActivityResult: the folder picked for Export pack… or Import pack… */
    static boolean activityResult(MainActivity a, int request, File dir) {
        if (request != PICK_EXPORT && request != PICK_IMPORT_DIR) return false;
        Game g = pending;
        pending = null;
        if (g == null) return true;
        if (request == PICK_EXPORT) exportRun(a, g, dir);
        else importRun(a, g, dir);
        return true;
    }

    private static long size(File f) {
        if (!f.isDirectory()) return f.length();
        long n = 0;
        File[] l = f.listFiles();
        if (l != null) for (File x : l) n += size(x);
        return n;
    }

    private static void exportRun(MainActivity a, Game g, File dir) {
        if (!ready(a, g)) return;
        File pack = new File(g.packDir);
        run(a, g, "Exporting " + g.name + "'s pack…", () -> {
            long need = size(pack);
            if (dir.isDirectory() && dir.getUsableSpace() < need + (64L << 20))
                throw new IOException("the zip needs " + SaveStore.size(need) + ", and " + dir + " has " + SaveStore.size(dir.getUsableSpace()) + " free");
            return PackZip.export(pack, dir);
        }, (r, err) -> {
            if (err != null) { a.error(Tx.s(R.string.err_no_pack_exported, err, pack)); return; }
            File z = (File) r;
            new PadFocus.Builder(a).setTitle(Tx.s(R.string.prepare_pack_exported)).setMessage(Tx.s(R.string.prepare_pack_exported_message, z.toString(), SaveStore.size(z.length()))).setPositiveButton(android.R.string.ok, null).show();
        });
    }

    private static void importRun(MainActivity a, Game g, File source) {
        if (!ready(a, g)) return;
        File packs = AppStorage.packs(a), target = AppStorage.pack(a, g.titleId);
        run(a, g, "Importing " + g.name + "'s pack…", () -> {
            long need = size(source);
            if (packs.getParentFile() != null && packs.getParentFile().getUsableSpace() < need + (256L << 20))
                throw new IOException("the pack needs " + SaveStore.size(need) + ", and the app's storage has " + SaveStore.size(packs.getParentFile().getUsableSpace()) + " free");
            File remembered = Job.remembered(a);
            if (remembered != null && remembered.isFile()) {
                Job j = Job.read(remembered);
                if (SaveStore.same(j.pack(), target) && !"done".equals(j.state()))
                    throw new IOException("a Prepare job of this game writes to " + target + " (" + j.statusText(false) + Tx.s(R.string.prepare_let_it_finish_or_forget));
            }
            Pack p = PackZip.stage(source, packs, g.titleId);
            try {
                String bad = PackCheck.blocking(a, p, new File(g.gameDir));
                if (bad != null) throw new IOException(bad);
                if (SaveUi.gameRunning(a, g)) throw new IOException(g.name + " was started meanwhile");
                PackZip.swapIn(p.dir, target);
            } finally {
                if (p.dir.exists()) SaveStore.deleteTree(p.dir);
            }
            return target;
        }, (r, err) -> {
            if (err != null) {
                int cut = err.indexOf("\n\n");   // a build check's sentence, then the build IDs
                Ui.explain(a, Tx.s(R.string.prepare_pack_not_imported), Tx.s(R.string.prepare_sentence_nothing_changed, PrepareText.capital(cut > 0 ? err.substring(0, cut) : err) + (err.endsWith(".") ? "" : ".")),
                        (cut > 0 ? err.substring(cut + 2) + "\n" : "") + "Pack folder: " + target).setPositiveButton(android.R.string.ok, null).show();
                return;
            }
            String old = Folders.pickedPack(a, g.titleId);
            Folders.pickedPack(a, g.titleId, null);   // the card plays the imported pack; a picked folder would win over it at the next Add game
            if (old != null && old.equals(Prefs.folder(a, "pack"))) Prefs.folder(a, "pack", null);
            Folders.setPack(a, g.titleId, target);
            String lp = Pack.libraryProblem(target, g.titleId);
            String shaders = lp == null ? Tx.s(R.string.prepare_has_this_apps_library, BuildConfig.NX_DMSH_VK_CHAIN)
                    : Pack.playableLibrary(target, g.titleId) != null || Pack.legacyLibrary(target, g.titleId) != null
                    ? Tx.s(R.string.prepare_its_shaders_are_of_an)
                    : Pack.shadersOnDemand(target, g.titleId, Prefs.layerEnv(a, g.titleId)) ? ShaderState.onDemandNote(Pack.isDump(new File(g.gameDir)))
                    : lp + " " + Tx.s(R.string.prepare_card_offers_fix);
            Ui.explain(a, Tx.s(R.string.prepare_pack_imported), shaders, "Pack folder: " + target + (old != null ? "\n" + Tx.s(R.string.prepare_old_pack_folder_kept, old.toString()) : ""))
                    .setPositiveButton(android.R.string.ok, null).show();
        });
    }

    interface Work { Object run() throws IOException; }
    interface Done { void done(Object result, String error); }

    private static void run(MainActivity a, Game g, String wait, Work work, Done done) {
        if (!take(g.titleId)) return;
        AlertDialog w = new PadFocus.Builder(a).setMessage(wait).setCancelable(false).show();
        new Thread(() -> {
            String err = null;
            Object r = null;
            try { r = work.run(); } catch (IOException | RuntimeException e) { err = e.getMessage(); }
            String fe = err;
            Object fr = r;
            a.runOnUiThread(() -> {
                give(g.titleId);
                w.dismiss();
                done.done(fr, fe);
            });
        }, "nx-pack-zip").start();
    }
}
