package dev.nxnative.android;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Game settings' Mods row for a game whose profile has the mods key: a sheet that lists the game's mods (a tap turns one on or off or removes it) and imports a .zip as a
 * new one. The folders and the checks are Mods.java's; this class is the picker and the dialogs. Only &lt;files&gt;/mods/&lt;title ID&gt;/ is written, never a dump or a save.
 */
final class ModsUi {
    private ModsUi() {}

    static final int PICK_MOD = 63;
    private static Game pendingFor;

    static File root(Context c, String titleId) { return Mods.root(c.getFilesDir(), titleId); }

    /** The row, into Game settings' Game options */
    static void row(MainActivity a, Game g, java.util.function.Consumer<Ui.Setting> add, Runnable again) {
        File root = root(a, g.titleId);
        int on = Mods.on(root, Prefs.modsOff(a, g.titleId)), all = Mods.list(root).size();
        add.accept(new Ui.Setting(a, "🧩", Tx.s(R.string.game_mods), v -> sheet(a, g, again))
                .value(all == 0 ? Tx.s(R.string.game_mods_none) : Tx.s(R.string.game_mods_on_of, on, all))
                .badge(Tx.s(R.string.game_experimental)).note(Tx.s(R.string.game_mods_note)).chevron(a));
    }

    /** The Mods sheet: one line per mod, then Import */
    static void sheet(MainActivity a, Game g, Runnable again) {
        File root = root(a, g.titleId);
        Set<String> off = Prefs.modsOff(a, g.titleId);
        List<File> mods = Mods.list(root);
        List<String> items = new ArrayList<>();
        for (File f : mods) items.add(f.getName() + "  ·  " + Tx.s(off.contains(f.getName()) ? R.string.game_off : R.string.game_mods_on));
        items.add(Tx.s(R.string.game_mods_import));
        new PadFocus.Builder(a).setTitle(Tx.s(R.string.game_mods_title, g.name)).setItems(items.toArray(new String[0]), (d, w) -> {
            if (w == mods.size()) {
                pendingFor = g;
                a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_MOD);
                return;
            }
            File f = mods.get(w);
            boolean isOff = off.contains(f.getName());
            new PadFocus.Builder(a).setTitle(f.getName() + " · " + Tx.s(R.string.game_mods_files, Mods.countFiles(f)))
                    .setItems(new String[] {Tx.s(isOff ? R.string.game_mods_turn_on : R.string.game_mods_turn_off), Tx.s(R.string.game_mods_remove)}, (x, k) -> {
                        if (k == 0) { Prefs.modOff(a, g.titleId, f.getName(), !isOff); again.run(); return; }
                        new PadFocus.Builder(a).setTitle(Tx.s(R.string.game_mods_remove_q, f.getName())).setMessage(Tx.s(R.string.game_mods_remove_note))
                                .setPositiveButton(Tx.s(R.string.game_mods_remove), (y, z) -> { Mods.remove(root, f.getName()); Prefs.modOff(a, g.titleId, f.getName(), false); again.run(); })
                                .setNegativeButton(Tx.s(R.string.common_cancel), null).show();
                    }).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
        }).setNegativeButton(Tx.s(R.string.common_close), null).show();
    }

    /** MainActivity.onActivityResult: the zip of Import mod… */
    static boolean documentResult(MainActivity a, int request, Uri doc) {
        if (request != PICK_MOD) return false;
        Game g = pendingFor;
        pendingFor = null;
        if (g == null || doc == null) return true;
        ContentResolver cr = a.getContentResolver();
        try (InputStream in = cr.openInputStream(doc)) {
            if (in == null) throw new IOException("the file cannot be opened");
            Mods.Imported r = Mods.importZip(in, root(a, g.titleId), name(cr, doc));
            Toast.makeText(a, Tx.s(R.string.game_mods_imported, r.dir.getName(), r.files), Toast.LENGTH_LONG).show();
        } catch (IOException | RuntimeException e) {
            a.error(Tx.s(R.string.game_mods_refused, String.valueOf(e.getMessage())));
        }
        a.gameSettingsTop();
        return true;
    }

    private static String name(ContentResolver cr, Uri doc) {
        try (Cursor c = cr.query(doc, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) { }
        String p = doc.getLastPathSegment();
        return p == null ? "mod" : p;
    }

    /** The overlay folders for a start: the mods that are on, "" when the game has none or the profile has no mods key */
    static String overlay(Context c, String titleId) {
        Profile p = Profile.of(titleId);
        return p == null || !p.mods ? "" : Mods.overlay(root(c, titleId), Prefs.modsOff(c, titleId));
    }
}
