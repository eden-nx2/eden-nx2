package dev.nxnative.android;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The hint bar along the bottom of a screen, as the console's launchers have: small button chips with their words ("A Open   X Options (hold)"). It takes no touch
 * and no focus. A hint is a button label (the chip: A, B, X, a D-pad arrow) and its words; a hint with no chip is a line of plain words (the touch hint). The chips
 * are letters in rounded plates: the button-icon font (nintendo_ext.ttf) belongs to the games' packs and is not in the launcher.
 */
final class HintBar extends LinearLayout {
    HintBar(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        int d = Ui.dp(c, 1);
        setPadding(d * 12, d * 6, d * 12, d * 6);
        android.graphics.drawable.GradientDrawable fill = Ui.round((Ui.col(c, R.color.bn_card) & 0x00FFFFFF) | 0xD8000000, Ui.dp(c, 999), 0, 0);
        setBackground(fill);
        setVisibility(GONE);
    }

    /** Shows these hints, each {chip or null, words}; none hides the bar */
    void set(String[]... hints) {
        removeAllViews();
        if (hints.length == 0) { setVisibility(GONE); return; }
        Context c = getContext();
        int d = Ui.dp(c, 1);
        for (int k = 0; k < hints.length; k++) {
            String chip = hints[k][0], words = hints[k][1];
            if (chip != null) {
                TextView t = Ui.text(c, chip, 11, R.color.bn_on_banana, 800);
                t.setGravity(Gravity.CENTER);
                t.setIncludeFontPadding(false);
                t.setBackground(Ui.round(Ui.col(c, R.color.bn_banana), Ui.dp(c, 999), 0, 0));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, d * 20);
                t.setMinWidth(d * 20);
                t.setPadding(d * 5, 0, d * 5, 0);
                addView(t, lp);
            }
            TextView w = Ui.text(c, words, 12, R.color.bn_text_2, 600);
            w.setMaxLines(1);
            w.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wp.setMarginStart(chip != null ? d * 5 : 0);
            if (k < hints.length - 1) wp.setMarginEnd(d * 14);
            addView(w, wp);
        }
        setVisibility(VISIBLE);
    }

    /** Whether a game controller is connected (the controller's hints say buttons, the touch hint says "hold") */
    static boolean hasPad(Context c) {
        for (int id : android.view.InputDevice.getDeviceIds()) {
            android.view.InputDevice d = android.view.InputDevice.getDevice(id);
            if (d != null && (d.getSources() & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD) return true;
        }
        return false;
    }
}
