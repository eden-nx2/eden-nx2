package dev.nxnative.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * The entry point for emulator frontends (FRONTENDS.md), reached by the exported alias <application ID>.Launch: an ACTION_VIEW of a game (a .nup, or a game
 * folder) by a file:// or content:// URI, or the extras {@code game} (a path) or {@code title_id} (a game already on Home). Draws nothing of its own.
 * <p>
 * A game whose card and pack are ready to play starts at once (its card is added first when the frontend's file is new and its pack is found and fits), and when
 * it stops the frontend comes back (GameActivity's "frontend" extra). Anything that needs the user (no pack, Prepare, shaders to make or update, a pack that does
 * not fit, All files access) goes to Home with the game, which asks as it does for a picked game. A frontend never chooses the pack or the save folder: those
 * extras stay adb's (MainActivity.fromIntent).
 */
public final class FrontendLaunch extends Activity {
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) { finish(); return; }   // recreated after it handed the game on
        Intent i = getIntent();
        String tid = Frontend.titleId(i.getStringExtra("title_id"));
        Game pg = tid == null ? pickedGame(i) : null;   // a .nup used where it is, named by its document or by a path the app cannot open
        if (pg != null) { byGame(pg); return; }
        if (!Environment.isExternalStorageManager() && !copiedGame(tid)) {   // a game copied into the app (GameCopy) needs no All files access
            Toast.makeText(this, Tx.s(R.string.err_turn_on_all_files_access, getString(R.string.app_name)), Toast.LENGTH_LONG).show();
            home(null);
            return;
        }
        if (tid != null) { byTitleId(tid); return; }
        String path = pathOf(i);
        if (path == null) {
            fail(i.getData() != null
                    ? Tx.s(R.string.err_the_frontend_sent) + i.getData() + Tx.s(R.string.err_which_is_not_a_file)
                    : Tx.s(R.string.err_the_frontend_sent_no_game));
            return;
        }
        File game = new File(Frontend.normal(path));
        if (Nup.wrongCase(game)) { fail(game.getName() + Tx.s(R.string.err_rename_it_so_that_its)); return; }
        if (!game.exists()) { fail(game + Tx.s(R.string.err_is_not_there_the_frontend) + path + ")."); return; }
        if (!game.canRead()) { fail(game + Tx.s(R.string.err_cannot_be_read_check_that)); return; }
        new Thread(() -> {
            String id = null, err = null;
            try { id = Pack.playableIdOf(game); } catch (IOException e) { err = e.getMessage(); }   // a .nup's TOC is hashed: off the main thread
            String fid = id, ferr = err;
            Game g = ferr == null ? ready(game, fid) : null;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (ferr != null) fail(ferr);
                else if (g != null) play(g);
                else home(game);
            });
        }, "nx-frontend").start();
    }

    /**
     * The card of a .nup used where it is (PickedNup) that the intent names: its document URI as the picker gave it, or a path or URI ending in its file name that
     * the app cannot open itself (no All files access); null when none, or when two picked games have that name
     */
    private Game pickedGame(Intent i) {
        Uri u = i.getData();
        if (u != null && "content".equalsIgnoreCase(u.getScheme()))
            for (Game g : Game.load(this)) if (u.toString().equals(PickedNup.uriOf(this, new File(g.gameDir)))) return g;
        String p = pathOf(i);
        if (p == null && u != null) p = u.getLastPathSegment();
        if (p == null) return null;
        File f = new File(Frontend.normal(p));
        if (Environment.isExternalStorageManager() && f.canRead()) return null;   // a path the app reads: as any game
        return PickedNup.byName(this, f.getName());
    }

    /** Whether the game of this title ID on the list lies in app storage (Copy into the app) */
    private boolean copiedGame(String tid) {
        if (tid == null) return false;
        for (Game g : Game.load(this)) if (g.titleId.equals(tid) && CopyPlan.inApp(AppStorage.root(this), g.gameDir)) return true;
        return false;
    }

    /** The game's path: the intent's data (file://, a storage document, or a provider URI that carries a /storage path), else the extra "game" */
    private String pathOf(Intent i) {
        Uri u = i.getData();
        if (u != null) {
            if ("file".equalsIgnoreCase(u.getScheme())) return Frontend.fileUri(u.toString());
            if ("content".equalsIgnoreCase(u.getScheme())) {
                String p = PathUtil.documentToPath(u);   // ES-DE's %ROMSAF%: a document of a picked tree
                if (p != null) return p;
                p = Frontend.embeddedPath(u.getPath());
                return p != null && new File(Frontend.normal(p)).exists() ? p : null;
            }
            return null;
        }
        String p = i.getStringExtra("game");
        return p != null && p.startsWith("/") ? p : null;
    }

    /**
     * The game's card when it can start now: its card (or a new one, saved, when its pack is found), a game this APK has, no save backup or restore working, a pack
     * that reads, is of this title and of this game build (PackCheck.blocking), has a RomFS for it and this version's shader library. null: Home asks (launch)
     */
    private Game ready(File game, String id) {
        if (Profile.missing(id, "This game") != null) return null;
        List<Game> list = Game.load(this);
        List<Integer> same = GameList.matches(list, id, game.getPath(), Game.key(game.getPath(), id), Game::key);   // its card: the same file (any spelling), or the same title and build
        Game card = same.isEmpty() ? null : list.get(same.get(0));
        File packDir = card != null ? new File(card.packDir) : Folders.packFor(this, game, id);
        if (packDir == null || SaveUi.busy(id)) return null;
        Pack p;
        try {
            p = Pack.read(packDir);
            if (!p.titleId.equalsIgnoreCase(id)) return null;
            if (Nup.isNup(game) ? p.romfsPath(game) == null : p.romfs(game) == null) return null;
        } catch (IOException e) {
            return null;
        }
        if (PackCheck.blocking(this, p, game) != null || Pack.libraryBlock(packDir, id, Prefs.layerEnv(this, id)) != null) return null;
        if (card != null) return card;
        Game g = new Game(id, p.name.isEmpty() ? id : p.name, game.getPath(), packDir.getPath());
        return Game.put(this, g);
    }

    private void byTitleId(String tid) {
        for (Game g : Game.load(this)) {
            if (!g.titleId.equalsIgnoreCase(tid)) continue;
            byGame(g);
            return;
        }
        fail(Tx.s(R.string.err_no_game_with_title_id) + tid + Tx.s(R.string.err_is_on_home_add_it) + getString(R.string.app_name) + " first, or send the game's file instead.");
    }

    /** A card on the list: played when it can start now, else Home shows it (its Play says what is missing: a lost .nup offers to pick it again) */
    private void byGame(Game g) {
        File game = new File(g.gameDir);
        new Thread(() -> {
            Game r = PickedNup.is(this, g.gameDir) && PickedNup.problem(this, game) != null ? null : ready(game, g.titleId);
            runOnUiThread(() -> { if (isFinishing()) return; if (r != null) play(r); else home(game); });
        }, "nx-frontend").start();
    }

    private void play(Game g) {
        startActivity(new Intent(this, GameActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("title_id", g.titleId).putExtra("name", g.name).putExtra("game", g.gameDir).putExtra("pack", g.packDir).putExtra("frontend", true));
        GameReturn.remember(this, g.titleId, g.name, g.gameDir, g.packDir);   // the app's icon goes back to this game while it runs
        finish();
    }

    /** Home, with the game when there is one (MainActivity.fromFrontend: its card's Play, or the steps a picked game takes) */
    private void home(File game) {
        Intent h = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (game != null) h.putExtra("frontend_game", game.getPath());
        startActivity(h);
        finish();
    }

    private void fail(String why) {
        new PadFocus.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(Tx.s(R.string.err_cannot_start_the_game)).setMessage(why)
                .setPositiveButton(android.R.string.ok, null).setOnDismissListener(d -> finish()).show();
    }
}
