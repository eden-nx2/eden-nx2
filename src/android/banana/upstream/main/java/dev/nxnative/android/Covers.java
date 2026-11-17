package dev.nxnative.android;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * A game's own cover picture, chosen by the player (Game page › Cover image): the picture they picked, cropped to the tile's square on the crop screen
 * (CropActivity) and kept downscaled (at most {@link #MAX} px) as a JPEG in the app's private files, one per game id (GameOrder: title ID and pack). Nothing is
 * written into the game's dump, pack or save folders, and the picked picture is read once, by the crop screen. Every place that draws a game's art asks {@link #show}
 * (Home's tile, the game's page); a game without a cover keeps its own icon (GameMeta). Deleting a game's entry calls {@link #delete}.
 */
final class Covers {
    private Covers() {}

    /** The longest side of a kept cover, in pixels */
    static final int MAX = 1024;
    /** Longest side a cover is decoded at for a tile (a tile is at most about 600 px on the screen) */
    private static final int TILE_PX = 640;
    static final int PICK = 70, CROP = 71;

    private static final LruCache<String, Bitmap> cache = new LruCache<>(6);
    private static Game pendingGame;
    private static Runnable pendingAgain;

    /** The file name for a game id: letters and digits of the id and a hash of it, so two ids never share a file and the name is always a legal one */
    static String key(String id) {
        String clean = id.replaceAll("[^A-Za-z0-9]", "_");
        if (clean.length() > 40) clean = clean.substring(0, 40);
        return clean + "-" + Integer.toHexString(id.hashCode()) + ".jpg";
    }

    private static File dir(Context c) { return new File(c.getFilesDir(), "covers"); }
    private static File file(Context c, String id) { return new File(dir(c), key(id)); }

    static boolean has(Context c, Game g) { return file(c, g.orderId()).isFile(); }

    /** Removes a game's cover (nothing if it has none). For the code that removes a game's entry from Home */
    static void delete(Context c, String id) {
        File f = file(c, id);
        if (f.exists() && !f.delete()) f.deleteOnExit();
        synchronized (cache) { cache.evictAll(); }
    }

    static void delete(Context c, Game g) { delete(c, g.orderId()); }

    /** As delete(Context, id) with the app's own context: for Remove game, which has the game's orderId() at hand */
    static void delete(String id) { delete(NxApp.app, id); }

    /** Keeps the cropped picture as the game's cover; false if it could not be written (the old cover stays) */
    static boolean save(Context c, String id, Bitmap b) {
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) return false;
        File tmp = new File(d, key(id) + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            if (!b.compress(Bitmap.CompressFormat.JPEG, 90, o)) return false;
        } catch (IOException e) {
            return false;
        }
        File f = file(c, id);
        if (!tmp.renameTo(f)) { tmp.delete(); return false; }
        synchronized (cache) { cache.evictAll(); }
        return true;
    }

    /** The cover, decoded for a tile (cached), or null when the game has none or the file is unreadable */
    static Bitmap bitmap(Context c, Game g) {
        File f = file(c, g.orderId());
        if (!f.isFile()) return null;
        String k = f.getPath() + ":" + f.lastModified() + ":" + f.length();
        synchronized (cache) { Bitmap b = cache.get(k); if (b != null) return b; }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int n = 1;
        while (Math.max(o.outWidth, o.outHeight) / (n * 2) >= TILE_PX) n *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = n;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b != null) synchronized (cache) { cache.put(k, b); }
        return b;
    }

    /** Puts the game's cover on a tile made by Ui.tile; false (the tile is left as it is) when it has none. The callers skip the game's own icon when this says true */
    static boolean show(TextView tile, Game g) {
        Bitmap b = bitmap(tile.getContext(), g);
        if (b == null) return false;
        Ui.tileIcon(tile, b);
        return true;
    }

    // ---- The game page's row, the picker and the crop screen ----

    /** The row of the game page: opens a small menu with Set cover image and, when there is one, Reset cover. again redraws the page after a change */
    static Ui.Setting row(MainActivity a, Game g, Runnable again) {
        boolean has = has(a, g);
        return new Ui.Setting(a, "🖼️", Tx.s(R.string.game_cover_image), v -> menu(a, g, again)).value(Tx.s(has ? R.string.game_cover_own : R.string.game_cover_default)).chevron(a);
    }

    private static void menu(MainActivity a, Game g, Runnable again) {
        boolean has = has(a, g);
        String[] items = has ? new String[] {Tx.s(R.string.game_cover_set), Tx.s(R.string.game_cover_reset)} : new String[] {Tx.s(R.string.game_cover_set)};
        new PadFocus.Builder(a).setTitle(Tx.s(R.string.game_cover_image)).setItems(items, (d, w) -> {
            if (w == 0) pick(a, g, again);
            else { delete(a, g); again.run(); a.refresh(); }
        }).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }

    /** The system picker (no storage permission: the grant is for the one picture), then the crop screen */
    static void pick(MainActivity a, Game g, Runnable again) {
        pendingGame = g;
        pendingAgain = again;
        a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICK);
    }

    /** MainActivity.onActivityResult: true when the request was one of these */
    static boolean activityResult(MainActivity a, int request, int result, Intent data) {
        if (request != PICK && request != CROP) return false;
        Game g = pendingGame;
        if (request == PICK) {
            Uri u = result == MainActivity.RESULT_OK && data != null ? data.getData() : null;
            if (g == null || u == null) { pendingGame = null; return true; }
            a.startActivityForResult(new Intent(a, CropActivity.class).setData(u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(CropActivity.EXTRA_ID, g.orderId()), CROP);
            return true;
        }
        Runnable again = pendingAgain;
        pendingGame = null;
        pendingAgain = null;
        if (result == CropActivity.SAVED) { a.refresh(); if (again != null) again.run(); }
        else if (result == CropActivity.FAILED) android.widget.Toast.makeText(a, Tx.s(R.string.game_cover_could_not_be_read), android.widget.Toast.LENGTH_LONG).show();
        return true;
    }
}
