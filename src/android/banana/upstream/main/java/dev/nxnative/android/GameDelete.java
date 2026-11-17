package dev.nxnative.android;

import android.app.AlertDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Remove game (the game's page › Storage, and the long press menu on Home): the game leaves the list and what the app made for it goes, after one question that
 * names every folder first. It takes (1) the list entry, (2) the game's pack and shader libraries when they are in the app's storage, its temporary (Prepare's work)
 * folder, its logs and its DLC links (StorageScan.gameData, the same set as Settings › Storage › Remove game data), (3) the copy of the game in the app's own
 * storage when it is one (GameCopy, Copy into the app) unless a save folder lies inside it, and (4) the read permission of a .nup used in place. It never takes
 * the player's own dump folder or a pack folder outside the app, and never a save folder: StorageScan.delete refuses every folder in Roots.saves.
 */
final class GameDelete {
    private GameDelete() {}

    static void ask(MainActivity a, Game g) {
        List<Game> games = Game.load(a);
        String why = StorageUi.busy(a, games);
        if (why == null && Ondevice.shows(g)) why = Tx.s(R.string.game_delete_prepare_running, g.name);
        if (why != null) { a.error(why); return; }
        AlertDialog wait = new PadFocus.Builder(a).setMessage(Tx.s(R.string.game_looking_at_the_app_s)).setCancelable(false).show();
        android.content.Context app = a.getApplicationContext();
        new Thread(() -> {
            StorageScan.Roots r = StorageUi.roots(app, games);
            List<StorageScan.Item> items = StorageScan.distinct(StorageScan.gameData(r, new StorageScan.Listed(g.titleId, g.gameDir, g.packDir), StorageUi.listed(games)));
            File copy = new File(g.gameDir);
            boolean isCopy = CopyPlan.isCopy(AppStorage.root(app), g.gameDir);
            boolean saveInside = false;
            if (isCopy) for (File s : r.saves) if (s.getPath().equals(copy.getPath()) || s.getPath().startsWith(copy.getPath() + "/")) saveInside = true;
            final boolean takeCopy = isCopy && !saveInside, keptCopy = isCopy && saveInside;
            a.runOnUiThread(() -> {
                wait.dismiss();
                if (a.isFinishing() || a.isDestroyed()) return;
                StringBuilder m = new StringBuilder();
                if (items.isEmpty() && !takeCopy) m.append(Tx.s(R.string.game_delete_list_only));
                else {
                    m.append(Tx.s(R.string.game_delete_what)).append("\n");
                    for (StorageScan.Item it : items) m.append("· ").append(it.label).append(" (").append(StorageScan.bytes(it.bytes)).append(")\n");
                    if (takeCopy) m.append("· ").append(Tx.s(R.string.game_delete_copy_line, g.gameDir)).append("\n");
                    m.append("\n").append(Tx.s(R.string.game_in_all_cannot_be_undone, StorageScan.bytes(StorageScan.total(items))));
                }
                if (keptCopy) m.append(" ").append(Tx.s(R.string.game_delete_copy_kept));
                m.append(" ").append(Tx.s(R.string.game_delete_not_touched));
                Ui.confirmDanger(a, Tx.s(R.string.game_delete_title, g.name), m, Tx.s(R.string.common_remove), () -> run(a, g, items, takeCopy));
            });
        }, "nx-delete-scan").start();
    }

    private static void run(MainActivity a, Game g, List<StorageScan.Item> items, boolean takeCopy) {
        String why = StorageUi.busy(a, Game.load(a));   // something may have started while the question showed
        if (why != null) { a.error(why); return; }
        List<Game> before = Game.load(a);   // with this game: its save folders stay protected for the deletion
        synchronized (Game.class) {
            List<Game> list = Game.load(a);
            list.removeIf(x -> x.gameDir.equals(g.gameDir));
            Game.save(a, list);
        }
        Covers.delete(g.orderId());   // the player's own cover picture goes with the game
        PickedNup.forget(a, new File(g.gameDir));   // a .nup used where it is: its read permission goes back (another entry of it keeps it)
        a.refresh();
        AlertDialog wait = new PadFocus.Builder(a).setMessage(Tx.s(R.string.game_removing)).setCancelable(false).show();
        android.content.Context app = a.getApplicationContext();
        new Thread(() -> {
            StorageScan.Roots r = StorageUi.roots(app, before);
            StorageScan.Result res = StorageScan.delete(r, items);
            List<String> problems = new ArrayList<>(res.problems);
            long freed = res.freed;
            if (takeCopy && !GameCopy.deleteCopy(app, new File(g.gameDir))) problems.add(Tx.s(R.string.err_not_all_deleted, g.gameDir));
            final long done = freed;
            a.runOnUiThread(() -> {
                wait.dismiss();
                if (a.isFinishing() || a.isDestroyed()) return;
                a.refresh();
                String text = Tx.s(R.string.game_n_freed, StorageScan.bytes(done));
                if (problems.isEmpty()) new PadFocus.Builder(a).setMessage(Tx.s(R.string.game_delete_done, g.name) + " " + text).setPositiveButton(android.R.string.ok, null).show();
                else Ui.explain(a, Tx.s(R.string.game_not_everything_was_removed), text, String.join("\n", problems)).setPositiveButton(android.R.string.ok, null).show();
            });
        }, "nx-delete-game").start();
    }
}
