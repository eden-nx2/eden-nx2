package dev.nxnative.android;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Home's two scrolling layouts besides the sideways row (Settings › Appearance › Home layout, {@link Prefs#homeLayout}): a grid with the game's name under each
 * tile, and a list with one row per game (a small cover, the name, the version and state). The row itself stays in MainActivity.tileRow.
 *
 * A game's artwork tile (MainActivity.artTile: the picture, the state veil, the Prepare ring) is the same view in all three layouts. Here it sits in an {@link Item}
 * that takes the focus, the tap and the long press for it (so the focus ring, the controller and the reorder menu behave as on the row) and carries the caption.
 */
final class HomeLayout {
    private HomeLayout() {}

    static final int ROW = 0, GRID = 1, LIST = 2;

    static int mode(Context c) { return Math.max(ROW, Math.min(LIST, Prefs.homeLayout(c))); }

    /** One game in the grid or the list: takes every touch itself and passes a tap or a long press to its tile (a tile with something to do does it) */
    static final class Item extends LinearLayout {
        Item(Context c) { super(c); }
        @Override public boolean onInterceptTouchEvent(MotionEvent e) { return true; }
    }

    /** The box the tiles go into: a scrolling grid or list in gameBox (Home never scrolls as a page; this layout scrolls up and down by itself) */
    static ViewGroup container(MainActivity a, LinearLayout gameBox, int mode) {
        gameBox.setGravity(Gravity.TOP);
        gameBox.setPadding(0, 0, 0, 0);
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setClipToPadding(false);
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(Ui.dp(a, 32));
        gameBox.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        final int gap = Ui.dp(a, 12), bottom = Ui.dp(a, 90);   // room under the last game for the floating Add game button
        if (mode == GRID) {
            GridLayout g = new GridLayout(a);
            g.setColumnCount(2);
            g.setClipChildren(false);
            g.setPadding(Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 8), bottom);
            sv.addView(g, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP));
            sv.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                int avail = (r - l) - g.getPaddingLeft() - g.getPaddingRight();
                int cols = Math.max(2, (avail + gap) / (Ui.dp(a, 150) + gap));   // tiles about 150 dp: two across upright, more on a wide window
                int side = Math.min(Ui.dp(a, 190), (avail - gap * cols) / cols);
                if (g.getColumnCount() != cols) g.setColumnCount(cols);
                for (int k = 0; k < g.getChildCount(); k++) {
                    View it = g.getChildAt(k);
                    GridLayout.LayoutParams p = (GridLayout.LayoutParams) it.getLayoutParams();
                    View tile = ((ViewGroup) it).getChildAt(0);
                    ViewGroup.LayoutParams tp = tile.getLayoutParams();
                    if (p.width != side || tp.height != side) {
                        p.width = side;
                        p.setMargins(gap / 2, gap / 2, gap / 2, gap / 2);
                        it.setLayoutParams(p);
                        tp.height = side;
                        tile.setLayoutParams(tp);
                    }
                }
            });
            return g;
        }
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setPadding(Ui.dp(a, 12), Ui.dp(a, 4), Ui.dp(a, 12), bottom);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        sv.addView(list, lp);
        sv.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {   // a wide window: rows no wider than they read well
            int w = Math.min(r - l, Ui.dp(a, 640));
            if (lp.width != w) { lp.width = w; list.setLayoutParams(lp); }
        });
        return list;
    }

    /** Puts a game's artwork tile (MainActivity.artTile) into the grid or the list, with its name, version and state */
    static void add(MainActivity a, ViewGroup box, int mode, FrameLayout tile) {
        MainActivity.TileInfo ti = (MainActivity.TileInfo) tile.getTag();
        boolean list = mode == LIST;
        Item it = new Item(a);
        it.setOrientation(list ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        it.setTag(ti);   // the focus and the Y button look for the tile's info on the focused view
        it.setFocusable(true);
        it.setClickable(true);
        it.setLongClickable(true);
        it.setContentDescription(ti.name);
        it.setForeground(a.ring(18, true));
        if (list) {
            it.setBackground(Ui.round(Ui.col(a, R.color.bn_card), Ui.dp(a, 18), 0, 0));
            it.setGravity(Gravity.CENTER_VERTICAL);
            it.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8));
        } else it.setPadding(0, 0, 0, Ui.dp(a, 6));
        it.setOnClickListener(v -> tile.performClick());
        it.setOnLongClickListener(v -> tile.performLongClick());
        it.setOnTouchListener((v, e) -> { if (e.getActionMasked() == MotionEvent.ACTION_DOWN) a.selectTile(it, false); return false; });
        tile.setForeground(null);   // the item draws the ring
        tile.setFocusable(false);
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if (list) {
            compact(a, ti);
            it.addView(tile, new LinearLayout.LayoutParams(Ui.dp(a, 56), Ui.dp(a, 56)));
        } else it.addView(tile, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 140)));
        LinearLayout words = new LinearLayout(a);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(a, ti.name, list ? 16 : 14, R.color.bn_text, 700);
        name.setMaxLines(list ? 1 : 2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView sub = Ui.text(a, "", 12, R.color.bn_text_2, 500);
        sub.setMaxLines(list ? 2 : 1);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (!list) { name.setGravity(Gravity.CENTER); sub.setGravity(Gravity.CENTER); words.setPadding(Ui.dp(a, 4), Ui.dp(a, 6), Ui.dp(a, 4), 0); }
        words.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        words.addView(sub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams wp = list ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (list) wp.setMarginStart(Ui.dp(a, 14));
        it.addView(words, wp);
        ti.sub = sub;
        ti.st.addTextChangedListener(new android.text.TextWatcher() {   // the state words change as the check goes on: the caption follows
            @Override public void beforeTextChanged(CharSequence s, int b, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int b, int c, int d) { }
            @Override public void afterTextChanged(android.text.Editable e) { it.post(() -> caption(ti)); }
        });
        caption(ti);
        if (box instanceof GridLayout) box.addView(it, new GridLayout.LayoutParams());
        else box.addView(it, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (!list) return;
        ((LinearLayout.LayoutParams) it.getLayoutParams()).topMargin = box.getChildCount() > 1 ? Ui.dp(a, 8) : 0;
    }

    /** A 56 dp cover has no room for the veil's words or the bar: the ring and the small corner ring only; the state is in the row's second line */
    private static void compact(MainActivity a, MainActivity.TileInfo ti) {
        if (ti.ot.getParent() == ti.ov) ((ViewGroup) ti.ov).removeView(ti.ot);
        if (ti.pb.getParent() == ti.ov) ((ViewGroup) ti.ov).removeView(ti.pb);
        ViewGroup.LayoutParams rp = ti.ring.getLayoutParams();
        rp.width = rp.height = Ui.dp(a, 34);
        ti.ring.setLayoutParams(rp);
        ViewGroup.MarginLayoutParams bp = (ViewGroup.MarginLayoutParams) ti.badge.getLayoutParams();
        bp.width = bp.height = Ui.dp(a, 22);
        bp.setMargins(0, Ui.dp(a, 2), Ui.dp(a, 2), 0);
        ti.badge.setLayoutParams(bp);
        ti.badge.setPadding(Ui.dp(a, 2), Ui.dp(a, 2), Ui.dp(a, 2), Ui.dp(a, 2));
    }

    /** The caption's second line: the version, then the state when there is one to tell (Ready says nothing); while a game is prepared, the step */
    static void caption(MainActivity.TileInfo ti) {
        if (ti.sub == null) return;
        StringBuilder sb = new StringBuilder();
        if (ti.version != null && !ti.version.isEmpty()) sb.append(ti.version);
        String s = ti.work && ti.workLine != null ? ti.workLine : ti.st.getText().toString().replaceFirst("^[\\u25CF\\u2022\\s]+", "").trim();
        boolean hidden = !ti.work && (MainActivity.kindOf(ti.st) & MainActivity.ST_HIDDEN) != 0;
        if (!s.isEmpty() && !hidden) { if (sb.length() > 0) sb.append(" · "); sb.append(s); }
        ti.sub.setText(sb);
        ti.sub.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /** How many tiles are in a line of the grid (a step down is this many places); 1 in the row and the list */
    static int columns(LinearLayout gameBox) {
        if (gameBox == null || gameBox.getChildCount() == 0 || !(gameBox.getChildAt(0) instanceof ScrollView)) return 1;
        View in = ((ScrollView) gameBox.getChildAt(0)).getChildAt(0);
        return in instanceof GridLayout ? ((GridLayout) in).getColumnCount() : 1;
    }
}
