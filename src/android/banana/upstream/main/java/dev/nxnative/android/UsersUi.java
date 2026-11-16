package dev.nxnative.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * The users (Users), as a page of Home like Settings: the players as round pictures with their names, a round + to add one, and on each a pencil. A tap on a picture
 * makes games start with that player (the yellow ring); the pencil opens the player's own page: its name, a picture from the phone's photos or one of the icons,
 * its ID, and Remove (not for the first player). A player is its ID (a random 128-bit Uid made once, Users): the name and the picture are labels, so a rename never
 * moves or mixes saves, which are kept by ID. A game that keeps a save per user (Super Mario Bros. Wonder's save files) offers them in its user selector (UserPicker).
 */
final class UsersUi {
    private UsersUi() {}

    // The icons: an emoji on a colour (Users.AVATARS of them; the emoji stay in code)
    private static final String[] EMOJI = {"🍌", "🦊", "🐸", "🐙", "🐼", "🦄", "🚀", "🎮", "🔥", "🌈", "🍄", "🐱"};
    private static final int[] COLOR = {0xFFF7D154, 0xFFF28C38, 0xFF5BB85D, 0xFF8E6BD8, 0xFF7C8798, 0xFFEC6FB0, 0xFF3B82F6, 0xFF14B8A6, 0xFFEF4444, 0xFF38BDF8, 0xFFE11D48, 0xFFA78BFA};
    static final int PICK_PHOTO = 9;

    private static EditText nameField;   // the open player page's name field, kept when the page closes
    private static String nameUid;
    private static final java.util.Map<String, Bitmap> PHOTOS = new java.util.HashMap<>();

    /** The row's value: how many users and who games start with */
    static String summary(Activity a) {
        List<Users.User> l = Users.list(a); String def = Users.defaultUid(a);
        String name = l.get(0).name; for (Users.User u : l) if (u.uid.equals(def)) name = u.name;
        return l.size() == 1 ? name : Tx.s(R.string.settings_n_users_games_start_with, l.size(), name);
    }

    // ---- Pictures: the player's own (a square of 256 px made from a photo, in the app's files) or an icon ----

    private static File photoFile(Activity a, String uid) { return new File(new File(a.getFilesDir(), "users"), uid + ".png"); }

    /** The player's own picture, or null (the icon is shown) */
    static Bitmap photo(Activity a, String uid) {
        synchronized (PHOTOS) {
            if (PHOTOS.containsKey(uid)) return PHOTOS.get(uid);
            File f = photoFile(a, uid);
            Bitmap b = f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
            PHOTOS.put(uid, b);
            return b;
        }
    }

    static void removePhoto(Activity a, String uid) {
        //noinspection ResultOfMethodCallIgnored
        photoFile(a, uid).delete();
        synchronized (PHOTOS) { PHOTOS.remove(uid); }
    }

    /** A photo from the picker: its middle square, 256 px, kept for the player; false when it cannot be read */
    static boolean setPhoto(Activity a, String uid, Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = a.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
            if (o.outWidth <= 0) return false;
            int s = 1;
            while (Math.min(o.outWidth, o.outHeight) / (s * 2) >= 512) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            Bitmap b;
            try (InputStream in = a.getContentResolver().openInputStream(uri)) { b = BitmapFactory.decodeStream(in, null, o); }
            if (b == null) return false;
            int side = Math.min(b.getWidth(), b.getHeight());
            Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - side) / 2, (b.getHeight() - side) / 2, side, side);
            Bitmap out = Bitmap.createScaledBitmap(sq, 256, 256, true);
            File f = photoFile(a, uid);
            //noinspection ResultOfMethodCallIgnored
            f.getParentFile().mkdirs();
            try (FileOutputStream os = new FileOutputStream(f)) { out.compress(Bitmap.CompressFormat.PNG, 100, os); }
            synchronized (PHOTOS) { PHOTOS.put(uid, out); }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void roundClip(View v) {
        v.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline o) { o.setOval(0, 0, view.getWidth(), view.getHeight()); }
        });
        v.setClipToOutline(true);
    }

    /** The dock's user button: the picture of the user games start with (its own, or its icon on its colour) */
    static void styleButton(Activity a, TextView t) {
        String uid = Users.defaultUid(a);
        Bitmap b = photo(a, uid);
        int idx = Users.avatar(a, uid);
        if (b != null) { t.setText(""); t.setBackground(new BitmapDrawable(a.getResources(), b)); }
        else { t.setText(EMOJI[idx]); t.setBackground(Ui.round(COLOR[idx], Ui.dp(a, 999), 0, 0)); }
        roundClip(t);
    }

    /** The ring that marks the chosen picture (and the one the focus is on) */
    private static Drawable ring(Activity a) {
        StateListDrawable s = new StateListDrawable();
        Drawable on = Ui.round(0, Ui.dp(a, 999), Ui.col(a, R.color.bn_banana_deep), Ui.dp(a, 4));
        s.addState(new int[] {android.R.attr.state_selected}, on);
        s.addState(new int[] {android.R.attr.state_focused}, on);
        s.addState(new int[] {android.R.attr.state_pressed}, Ui.round(0x22000000, Ui.dp(a, 999), 0, 0));
        return s;
    }

    /** A round picture, size dp wide: the player's own, or the icon index on its colour; the ring when chosen */
    private static FrameLayout face(Activity a, String uid, int index, int size, boolean chosen) {
        FrameLayout f = new FrameLayout(a);
        int in = Ui.dp(a, 5);   // the ring has its own room around the picture
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(a, size) - 2 * in, Ui.dp(a, size) - 2 * in, Gravity.CENTER);
        Bitmap photo = uid == null ? null : photo(a, uid);
        if (photo != null) {
            ImageView v = new ImageView(a);
            v.setImageBitmap(photo);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            roundClip(v);
            f.addView(v, lp);
        } else {
            TextView t = new TextView(a);
            t.setText(EMOJI[index]);
            t.setTextSize(size * 0.45f);
            t.setGravity(Gravity.CENTER);
            t.setIncludeFontPadding(false);
            t.setBackground(Ui.round(COLOR[index], Ui.dp(a, 999), 0, 0));
            f.addView(t, lp);
        }
        f.setForeground(ring(a));
        f.setSelected(chosen);
        f.setFocusable(true);
        f.setClickable(true);
        f.setDefaultFocusHighlightEnabled(false);
        return f;
    }

    // ---- The pages ----

    /** The players' page (editing null) or one player's page */
    static void build(MainActivity a, LinearLayout content, String editing) {
        commitName(a);
        nameField = null;
        nameUid = null;
        List<Users.User> l = Users.list(a);
        Users.User edit = null;
        for (Users.User u : l) if (u.uid.equals(editing)) edit = u;
        if (edit != null) player(a, content, edit); else players(a, content, l);
    }

    private static void players(MainActivity a, LinearLayout content, List<Users.User> l) {
        content.addView(Ui.header(a, Ui.back(a, v -> a.page(false)), Tx.s(R.string.common_users), null));
        LinearLayout card = Ui.add(content, Ui.card(a), 10);
        String def = Users.defaultUid(a);
        GridLayout grid = new GridLayout(a);
        grid.setColumnCount(a.getResources().getConfiguration().screenWidthDp >= 600 ? 5 : 3);
        for (Users.User u : l) {
            boolean on = u.uid.equals(def);
            LinearLayout cell = new LinearLayout(a);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout holder = new FrameLayout(a);
            FrameLayout f = face(a, u.uid, Users.avatar(a, u.uid), 96, on);
            f.setContentDescription(u.name);
            f.setOnClickListener(v -> { Users.setDefault(a, u.uid); a.userChanged(); a.rebuildUsers(); });
            holder.addView(f, new FrameLayout.LayoutParams(Ui.dp(a, 96), Ui.dp(a, 96)));
            ImageView pen = new ImageView(a);   // the pencil: this player's page
            pen.setImageDrawable(Ui.icon(a, R.drawable.ic_edit, R.color.bn_on_banana));
            pen.setPadding(Ui.dp(a, 7), Ui.dp(a, 7), Ui.dp(a, 7), Ui.dp(a, 7));
            pen.setBackground(Ui.round(Ui.col(a, R.color.bn_banana), Ui.dp(a, 999), Ui.col(a, R.color.bn_card), Ui.dp(a, 2)));
            pen.setContentDescription(Tx.s(R.string.settings_edit_player));
            pen.setTooltipText(Tx.s(R.string.settings_edit_player));
            pen.setFocusable(true);
            pen.setClickable(true);
            pen.setOnClickListener(v -> a.usersEdit(u.uid));
            holder.addView(pen, new FrameLayout.LayoutParams(Ui.dp(a, 34), Ui.dp(a, 34), Gravity.BOTTOM | Gravity.END));
            cell.addView(holder, new LinearLayout.LayoutParams(Ui.dp(a, 96), Ui.dp(a, 96)));
            TextView n = Ui.text(a, u.name, 14, on ? R.color.bn_accent : R.color.bn_text, on ? 700 : 600);
            n.setGravity(Gravity.CENTER);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            n.setPadding(0, Ui.dp(a, 6), 0, 0);
            cell.addView(n, new LinearLayout.LayoutParams(Ui.dp(a, 96), ViewGroup.LayoutParams.WRAP_CONTENT));
            GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
            gp.setMargins(Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 10));
            grid.addView(cell, gp);
        }
        if (l.size() < Users.MAX) {   // + : a new player, made and opened at once
            LinearLayout cell = new LinearLayout(a);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout plus = new FrameLayout(a);
            TextView sign = new TextView(a);
            sign.setText("+");
            sign.setTextSize(40);
            sign.setGravity(Gravity.CENTER);
            sign.setIncludeFontPadding(false);
            sign.setTextColor(Ui.col(a, R.color.bn_text_2));
            sign.setBackground(Ui.round(0, Ui.dp(a, 999), Ui.col(a, R.color.bn_text_2), Ui.dp(a, 2)));
            int in = Ui.dp(a, 5);
            plus.addView(sign, new FrameLayout.LayoutParams(Ui.dp(a, 96) - 2 * in, Ui.dp(a, 96) - 2 * in, Gravity.CENTER));
            plus.setForeground(ring(a));
            plus.setFocusable(true);
            plus.setClickable(true);
            plus.setDefaultFocusHighlightEnabled(false);
            plus.setContentDescription(Tx.s(R.string.settings_add_user));
            plus.setOnClickListener(v -> {
                Users.User u = Users.add(a, Tx.s(R.string.settings_new_player));
                if (u == null) return;
                Users.setAvatar(a, u.uid, Math.floorMod(l.size(), Users.AVATARS));
                a.userChanged();
                a.usersEdit(u.uid);
            });
            cell.addView(plus, new LinearLayout.LayoutParams(Ui.dp(a, 96), Ui.dp(a, 96)));
            TextView n = Ui.text(a, Tx.s(R.string.settings_add_user), 14, R.color.bn_text_2, 600);
            n.setGravity(Gravity.CENTER);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            n.setPadding(0, Ui.dp(a, 6), 0, 0);
            cell.addView(n, new LinearLayout.LayoutParams(Ui.dp(a, 96), ViewGroup.LayoutParams.WRAP_CONTENT));
            GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
            gp.setMargins(Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 10));
            grid.addView(cell, gp);
        }
        card.addView(grid);
        TextView hint = Ui.small(a, Tx.s(R.string.settings_users_hint));
        hint.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0);
        Ui.add(content, hint, 10);
    }

    private static void player(MainActivity a, LinearLayout content, Users.User user) {
        content.addView(Ui.header(a, Ui.back(a, v -> a.usersBack()), user.name, null));
        // The picture and the name
        LinearLayout card = Ui.add(content, Ui.card(a), 10);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout big = face(a, user.uid, Users.avatar(a, user.uid), 120, false);
        big.setFocusable(false);
        big.setClickable(false);
        card.addView(big, new LinearLayout.LayoutParams(Ui.dp(a, 120), Ui.dp(a, 120)));
        EditText f = new EditText(a);
        f.setSingleLine(true);
        f.setGravity(Gravity.CENTER);
        f.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        f.setImeOptions(EditorInfo.IME_ACTION_DONE);
        f.setText(user.name);
        f.setSelection(f.getText().length());
        f.setHint(Tx.s(R.string.settings_name));
        f.setOnEditorActionListener((v, action, ev) -> { commitName(a); v.clearFocus(); return false; });
        nameField = f;
        nameUid = user.uid;
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.topMargin = Ui.dp(a, 12);
        card.addView(f, fp);
        // The picture: from the phone's photos, or one of the icons
        Ui.add(content, Ui.section(a, Tx.s(R.string.settings_choose_an_icon)), 16);
        LinearLayout icons = Ui.add(content, Ui.card(a), 6);
        GridLayout grid = new GridLayout(a);
        grid.setColumnCount(a.getResources().getConfiguration().screenWidthDp >= 600 ? 12 : 4);
        boolean own = photo(a, user.uid) != null;
        int mine = Users.avatar(a, user.uid);
        for (int i = 0; i < Users.AVATARS; i++) {
            int idx = i;
            FrameLayout c = face(a, null, i, 60, !own && i == mine);
            c.setContentDescription(EMOJI[i]);
            c.setOnClickListener(v -> { commitName(a); removePhoto(a, user.uid); Users.setAvatar(a, user.uid, idx); a.userChanged(); a.rebuildUsers(); });
            GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
            gp.setMargins(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
            grid.addView(c, gp);
        }
        icons.addView(grid);
        LinearLayout list = Ui.add(content, Ui.listCard(a), 12);
        list.addView(new Ui.Setting(a, "🖼️", Tx.s(R.string.settings_choose_a_picture), v -> {
            commitName(a);
            a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICK_PHOTO);
        }).value(Tx.s(R.string.settings_from_your_photos)).chevron(a).view);
        if (own) list.addView(new Ui.Setting(a, "🔄", Tx.s(R.string.settings_use_an_icon_instead), v -> { removePhoto(a, user.uid); a.userChanged(); a.rebuildUsers(); }).chevron(a).view);
        // Its ID: the name and the picture are labels, the saves follow the ID
        LinearLayout ids = Ui.add(content, Ui.listCard(a), 12);
        ids.addView(new Ui.Setting(a, "🆔", Tx.s(R.string.settings_player_id), null).value(user.uid.substring(0, 8) + "…").view);
        TextView note = Ui.small(a, Tx.s(R.string.settings_player_id_hint));
        note.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0);
        Ui.add(content, note, 6);
        if (!user.uid.equals(Users.FIRST)) {   // the first player is the host's own: it stays
            android.widget.Button rm = Ui.button(a, Tx.s(R.string.common_remove), Ui.Kind.SOFT, v -> Ui.confirmDanger(a, Tx.s(R.string.settings_remove_user_question, user.name),
                    Tx.s(R.string.settings_remove_user_message, user.uid.substring(0, 8)), Tx.s(R.string.common_remove), () -> { nameField = null; Users.remove(a, user.uid); removePhoto(a, user.uid); a.userChanged(); a.usersBack(); }));
            Ui.add(content, rm, 18, Ui.wrap());
        }
    }

    /** The name in the open player page's field is kept (a name that is empty is refused) */
    static void commitName(Activity a) {
        if (nameField == null || nameUid == null) return;
        String n = Users.clean(nameField.getText().toString());
        if (n.isEmpty()) { Toast.makeText(a, Tx.s(R.string.settings_a_user_needs_a_name), Toast.LENGTH_SHORT).show(); return; }
        for (Users.User u : Users.list(a)) if (u.uid.equals(nameUid) && !u.name.equals(n)) Users.rename(a, u.uid, n);
    }
}
