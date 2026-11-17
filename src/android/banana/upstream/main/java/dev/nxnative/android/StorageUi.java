package dev.nxnative.android;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Settings › Storage: lists what the app wrote that can go (StorageScan), grouped by kind with each item's size and the total, and removes only what the player ticks
 * and confirms. A game's pack and shaders go only through its "Remove game data…", which names every folder first. Saves are never listed or removed (StorageScan's
 * guard; the save folders of every game, picked or default, are added to it here). Refused while a game, Prepare, a copy, a save backup or a pack export runs.
 */
final class StorageUi {
    private StorageUi() {}

    /** Why nothing may be scanned now, or null */
    static String busy(Context c, List<Game> games) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> l = am == null ? null : am.getRunningAppProcesses();
        if (l != null) for (ActivityManager.RunningAppProcessInfo p : l) {
            if (p.processName == null) continue;
            if (p.processName.endsWith(":game")) return "A game is running: stop it first (its in-game panel › Stop the game).";
            // :pack also hosts the shader build of the games played (LayerService), and Android keeps it cached after: only a process that runs something refuses
            if (p.processName.endsWith(":pack") && p.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED)
                return Tx.s(R.string.game_prepare_or_a_shader_build);
        }
        if (GameCopy.running()) return Tx.s(R.string.game_a_copy_into_the_app);
        for (Game g : games) if (SaveUi.busy(g.titleId) || PackUi.busy(g.titleId)) return Tx.s(R.string.game_backup_or_export_running, g.name);
        return null;
    }

    /** The app's folders, with every save folder the app knows of protected */
    static StorageScan.Roots roots(Context c, List<Game> games) {
        StorageScan.Roots r = new StorageScan.Roots(AppStorage.root(c), c.getFilesDir(), c.getCacheDir(), c.getNoBackupFilesDir());
        Set<String> ids = new LinkedHashSet<>();
        for (Profile p : Profile.all()) ids.add(p.titleId);
        for (Game g : games) {
            ids.add(g.titleId);
            r.save(Folders.save(c, g));
            r.save(Prefs.oldSaveDefault(new File(g.gameDir), g.titleId));
        }
        for (String id : ids) {
            String picked = Prefs.folder(c, "save_" + id);
            if (picked != null) r.save(new File(picked));
            r.save(AppStorage.save(c, id));
        }
        return r;
    }

    static List<StorageScan.Listed> listed(List<Game> games) {
        List<StorageScan.Listed> l = new ArrayList<>();
        for (Game g : games) l.add(new StorageScan.Listed(g.titleId, g.gameDir, g.packDir));
        return l;
    }

    static void dialog(MainActivity a) {
        List<Game> games = Game.load(a);
        String why = busy(a, games);
        if (why != null) { a.error(why); return; }
        AlertDialog wait = new PadFocus.Builder(a).setMessage(Tx.s(R.string.game_looking_at_the_app_s)).setCancelable(false).show();
        Context app = a.getApplicationContext();
        new Thread(() -> {
            StorageScan.Roots r = roots(app, games);
            List<StorageScan.Item> items = StorageScan.scan(r, listed(games), BuildConfig.NX_DMSH_VK_CHAIN, Updater.pending());
            a.runOnUiThread(() -> {
                wait.dismiss();
                if (!gone(a)) show(a, r, games, items);
            });
        }, "nxstorage-scan").start();
    }

    private static boolean gone(Activity a) { return a.isFinishing() || a.isDestroyed(); }

    private static void show(MainActivity a, StorageScan.Roots r, List<Game> games, List<StorageScan.Item> items) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView total = Ui.body(a, "");
        box.addView(total);
        List<CheckBox> boxes = new ArrayList<>();
        List<StorageScan.Item> order = new ArrayList<>();
        Runnable sum = () -> {
            long n = 0;
            for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isChecked()) n += order.get(i).bytes;
            total.setText(Tx.s(R.string.game_storage_found, StorageScan.bytes(StorageScan.total(items)), StorageScan.bytes(n)));
        };
        if (items.isEmpty()) Ui.add(box, Ui.small(a, Tx.s(R.string.game_nothing_to_clean_up)), 8);
        for (Map.Entry<StorageScan.Kind, List<StorageScan.Item>> e : StorageScan.byKind(items).entrySet()) {
            StorageScan.Kind k = e.getKey();
            Ui.add(box, Ui.section(a, k.title + " · " + StorageScan.bytes(StorageScan.total(e.getValue()))), 16);
            Ui.add(box, Ui.small(a, k.about), 2);
            for (StorageScan.Item it : e.getValue()) {
                CheckBox cb = new CheckBox(a);
                cb.setText(it.label + " · " + StorageScan.bytes(it.bytes));
                cb.setChecked(k.preselected);
                cb.setOnCheckedChangeListener((b, on) -> sum.run());
                Ui.add(box, cb, 2);
                boxes.add(cb);
                order.add(it);
            }
        }
        sum.run();
        if (!games.isEmpty()) {
            Ui.add(box, Ui.section(a, Tx.s(R.string.game_a_game_s_data)), 16);
            Ui.add(box, Ui.small(a, Tx.s(R.string.game_a_game_s_pack_and)), 2);
        }
        AlertDialog[] d = {null};
        for (Game g : games) Ui.add(box, Ui.button(a, Tx.s(R.string.game_remove_name_data, g.name), Ui.Kind.TONAL, v -> { d[0].dismiss(); gameData(a, r, games, g); }), 6);
        AlertDialog.Builder b = Ui.sheet(a, Tx.s(R.string.game_storage), box).setNegativeButton(Tx.s(R.string.common_close), null);
        if (!items.isEmpty()) b.setPositiveButton(Tx.s(R.string.game_remove_ticked), (x, w) -> {
            List<StorageScan.Item> pick = new ArrayList<>();
            for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isChecked()) pick.add(order.get(i));
            if (pick.isEmpty()) return;
            confirm(a, r, Tx.n(R.plurals.game_remove_n_items, pick.size(), pick.size()), pick, null);
        });
        d[0] = b.show();
    }

    private static void gameData(MainActivity a, StorageScan.Roots r, List<Game> games, Game g) {
        List<StorageScan.Item> l = StorageScan.gameData(r, listed(List.of(g)).get(0), listed(games));
        if (l.isEmpty()) { a.error(Tx.s(R.string.err_no_data_that_can_go, g.name)); return; }
        confirm(a, r, Tx.s(R.string.game_remove_name_data_question, g.name), l, Tx.s(R.string.game_the_game_stays_in_your));
    }

    /** Names exactly what goes, then removes it off the UI thread (each item checked by the guard again) */
    private static void confirm(MainActivity a, StorageScan.Roots r, String title, List<StorageScan.Item> pick, String note) {
        List<StorageScan.Item> l = StorageScan.distinct(pick);
        StringBuilder m = new StringBuilder();
        for (StorageScan.Item it : l) m.append("· ").append(it.label).append(" (").append(StorageScan.bytes(it.bytes)).append(")\n");
        m.append("\n").append(Tx.s(R.string.game_in_all_cannot_be_undone, StorageScan.bytes(StorageScan.total(l))));
        if (note != null) m.append(" ").append(note);
        else m.append(" ").append(Tx.s(R.string.game_saves_never_removed));
        Ui.confirmDanger(a, title, m, Tx.s(R.string.common_remove), () -> {
            String why = busy(a, Game.load(a));   // something may have started while the list showed
            if (why != null) { a.error(why); return; }
            AlertDialog wait = new PadFocus.Builder(a).setMessage(Tx.s(R.string.game_removing)).setCancelable(false).show();
            new Thread(() -> {
                StorageScan.Result res = StorageScan.delete(r, l);
                a.runOnUiThread(() -> {
                    wait.dismiss();
                    if (gone(a)) return;
                    a.refresh();
                    String done = Tx.s(R.string.game_n_freed, StorageScan.bytes(res.freed));
                    if (res.problems.isEmpty()) new PadFocus.Builder(a).setMessage(done).setPositiveButton(android.R.string.ok, null).show();
                    else Ui.explain(a, Tx.s(R.string.game_not_everything_was_removed), done, String.join("\n", res.problems)).setPositiveButton(android.R.string.ok, null).show();
                });
            }, "nxstorage-remove").start();
        });
    }
}
