package dev.nxnative.android;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.Collections;
import java.util.List;

/** Opt-in phone-to-PC Link Play. Applied at the next start, before the game's typed environment lines (which win). */
final class LinkPlay {
    private LinkPlay() {}
    static boolean has(String titleId) { Profile p = Profile.of(titleId); return p != null && p.linkPlay; }

    private static SharedPreferences p(Context c) { return Prefs.of(c); }
    private static String k(String key, String id) { return Prefs.gameKey("lp_" + key, id); }
    private static String get(Context c, String key, String id) { return p(c).getString(k(key, id), ""); }

    static String mode(Context c, String id) { return "visit".equals(get(c, "mode", id)) ? "visit" : "off"; }

    // Preserve the join details from earlier test builds without shipping a list of private peers.
    private static String room(Context c, String id) {
        if (p(c).contains(k("room", id))) return get(c, "room", id);
        return get(c, "code_" + get(c, "friend", id), id);
    }
    private static boolean internet(Context c, String id) {
        return p(c).getBoolean(k("internet", id), !room(c, id).isEmpty());
    }

    static List<String> env(Context c, String id) {
        if (!has(id)) return Collections.emptyList();
        return LinkPlayConfig.env("visit".equals(mode(c, id)), get(c, "addr", id), internet(c, id), room(c, id));
    }

    static String label(Context c, String id) {
        if (!"visit".equals(mode(c, id))) return "Off";
        if (!LinkPlayConfig.valid(get(c, "addr", id), internet(c, id), room(c, id))) return Tx.s(R.string.game_join_pc_host_needs_setup);
        return internet(c, id) ? Tx.s(R.string.game_join_pc_host_internet_tailscale) : Tx.s(R.string.game_join_pc_host_same_wi);
    }

    static void dialog(MainActivity a, Game g, Runnable done) {
        String id = g.titleId;
        new PadFocus.Builder(a).setTitle(Tx.s(R.string.game_link_play))
                .setSingleChoiceItems(new String[] {Tx.s(R.string.game_off), Tx.s(R.string.game_join_pc_host)}, "visit".equals(mode(a, id)) ? 1 : 0, (x, w) -> {
                    x.dismiss();
                    if (w == 0) {
                        if (p(a).edit().putString(k("mode", id), "off").commit()) done.run();
                        else Toast.makeText(a, Tx.s(R.string.game_could_not_save_link_play), Toast.LENGTH_LONG).show();
                    } else visit(a, g, done);
                }).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }

    private static void visit(MainActivity a, Game g, Runnable done) {
        String id = g.titleId;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.small(a, Tx.s(R.string.game_join_a_world_hosted_in)));
        EditText addr = new EditText(a);
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addr.setSingleLine(true);
        addr.setHint(Tx.s(R.string.game_host_ipv4_address_optional_port));
        addr.setText(get(a, "addr", id));
        Ui.add(box, addr, 8);

        CheckBox inet = new CheckBox(a);
        inet.setText(Tx.s(R.string.game_internet_tailscale));
        inet.setChecked(internet(a, id));
        Ui.add(box, inet, 8);
        box.addView(Ui.small(a, Tx.s(R.string.game_use_the_same_internet_setting)));
        EditText code = new EditText(a);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setSingleLine(true);
        code.setTypeface(android.graphics.Typeface.MONOSPACE);
        code.setHint(Tx.s(R.string.game_host_room_code_xxxx_xxxx));
        code.setText(room(a, id));
        code.setEnabled(inet.isChecked());
        inet.setOnCheckedChangeListener((v, on) -> { code.setEnabled(on); code.setError(null); addr.setError(null); });
        Ui.add(box, code, 8);
        box.addView(Ui.small(a, Tx.s(R.string.game_saved_for_next_time_including)));
        AlertDialog d = Ui.sheet(a, Tx.s(R.string.game_join_pc_host), box).setPositiveButton(Tx.s(R.string.game_save), null).setNegativeButton(Tx.s(R.string.common_cancel), null).create();
        d.setOnShowListener(ignored -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String address = addr.getText().toString(), rawCode = code.getText().toString();
            String error = LinkPlayConfig.addressError(address, inet.isChecked());
            if (error != null) { addr.setError(error); addr.requestFocus(); return; }
            String normalizedCode = LinkPlayConfig.room(rawCode);
            if (inet.isChecked() && normalizedCode == null) {
                code.setError("Enter the host's 16-letter/digit room code."); code.requestFocus(); return;
            }
            boolean saved = p(a).edit().putString(k("addr", id), LinkPlayConfig.address(address))
                    .putString(k("room", id), normalizedCode != null ? normalizedCode : rawCode.trim())
                    .putBoolean(k("internet", id), inet.isChecked()).putString(k("mode", id), "visit").commit();
            if (!saved) { Toast.makeText(a, Tx.s(R.string.game_could_not_save_link_play), Toast.LENGTH_LONG).show(); return; }
            d.dismiss();
            done.run();
        }));
        d.show();
    }
}
