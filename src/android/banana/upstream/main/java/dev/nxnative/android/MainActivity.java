package dev.nxnative.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The launcher. Two pages in one activity (so every picker's result comes back here): Home, the short path to playing (pick your dump, prepare it, Play), and Settings
 * (the defaults of every game, GPU driver, folders, the Readiness check, the self-test, logs, the build), opened with the gear in Home's header; Back returns to Home.
 * <p>
 * Home: the All files access card while it is missing, a game being prepared (Ondevice: shown while there is a job), then the games, each a square tile (TileGrid) with its letter or icon, name,
 * state chip and warning badge, no button: a tap opens the game's page (GameSettings: its settings, saves, folders, Prepare again, removal; Play in its header);
 * a state that needs an action (Prepare, Pick it again) is done by tapping its chip. A controller works as in the in-game panel (PadFocus). With no game, a single card says what to do and has Add game. "Add game" takes a dump folder or .nup of any game this APK has a profile for
 * (Profile: one host per game), or a folder that holds several (all added, one summary: addAll): the best pack PackFind finds (the picked pack folder, the app's,
 * beside the game, &lt;id&gt;-work/&lt;any&gt; up to three folders above it) is used when it fits or only needs the automatic shader update, else the app offers to
 * prepare one on the phone (or a card with Prepare, from a folder of games). A game without a profile in this APK is refused with a sentence (Profile.missing),
 * never started.
 * <p>
 * Each page sits in one ScrollView over the drifting bananas, so it scrolls on any screen (the folded phone's outer display, landscape, a split window), at a
 * readable width, centered on a wide window. The activity handles rotation itself (configChanges), so a size change builds the page again (onConfigurationChanged).
 */
public final class MainActivity extends Activity {
    private static final int PICK_GAME = 1, PICK_PACK = 2, PICK_DLL = 3, PICK_DRIVER = 4, PICK_NUP = 5, PICK_NUP_HERE = 6, PICK_BG = 7, PICK_SND = 8;

    private final List<Game> games = new ArrayList<>();
    private LinearLayout gameBox;   // the game cards (refresh)
    private View accessCard;
    private BananaBackgroundView bananas;   // the drifting bananas behind the screen
    private TextView poppedText;   // Settings › About: "Bananas Popped: n" (null when Settings is not shown)
    private Ui.Setting overlayRow, resRow, capRow, upsRow, sharpRow, drsRow, lsfgRow, gpuRow, updateRow, timeRow;
    private boolean resumed, settingsPage;
    private boolean usersPage;   // the Users page (UsersUi) in place of Home
    private String usersEdit;   // the player whose own page it shows, else the page of all players
    private boolean advancedOpen;   // the Advanced page (AdvancedPage) is open over Settings or a game's page
    private String advancedTitleId, advancedName;   // its game (null: Settings' page for every game)
    private Game settingsGame;   // the game whose settings page shows (GameSettings), else null
    private boolean merged;   // the list's duplicates were merged (Game.collapse) since this activity started
    private File pendingGame;   // a game folder (or .nup) waiting for its pack folder
    private String pendingId;
    private File pendingPackPick;   // the pack folder the user just picked (remembered in Prefs once it is accepted)

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) settingsPage = saved.getBoolean("settings");
        if (saved != null && saved.getString("game_settings") != null)   // a fold or unfold makes the activity again: the game's settings page stays open
            for (Game g : Game.load(this)) if (g.gameDir.equals(saved.getString("game_settings"))) { settingsGame = g; break; }
        FrameLayout frame = new FrameLayout(this);
        bananas = new BananaBackgroundView(this);
        frame.addView(bananas, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bananas.setOnPop(() -> {   // a popped banana: counted, a light tick, and Settings' counter if it shows
            long n = Prefs.addBananaPopped(this);
            bananas.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            if (poppedText != null) poppedText.setText(poppedLine(n));
        });
        bananas.setLook(Prefs.background(this));
        setContentView(frame);
        frame.getViewTreeObserver().addOnGlobalFocusChangeListener((o, n) -> {
            if (n == null) return;
            if (!n.isInTouchMode()) UiSound.tick(this);   // a controller or keyboard moves the focus
            if (n.getTag() instanceof TileInfo) selectTile(n);   // the focused tile grows and the dock says what it is
            hintUpdate();
        });   // a controller or keyboard moves the focus
        PadFocus.watch(getWindow());   // a controller: the focus frame (PadFocus)
        build();
        if (saved == null) { setIntent(gated(getIntent())); fromIntent(getIntent()); GameReturn.back(this, getIntent()); }   // start() reads no_pause from getIntent() too; GameReturn: Home made anew by the app's icon while a game runs
        GpuGl.probe(this, () -> { if (!isFinishing() && settingsGame != null) gameSettingsAgain(); });   // the GPU's name and, before any readiness check, the system driver's facts for the recommendations
        Updater.onStart(this);   // the release app: a newer release on GitHub opens the update popup (once per process start: not on a resume or a rotation)
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("settings", settingsPage);
        if (settingsGame != null) out.putString("game_settings", settingsGame.gameDir);
    }

    /** Rotation, a window resize: the same page again */
    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        int y = settingsGame != null ? scrollY() : 0;
        build();
        if (settingsGame != null) scrollTo(y);
        if (resumed) { onResumeViews(); if (onHome()) Ondevice.resume(this); }
    }

    /** Home shows (not Settings, not a game's settings page) */
    private boolean onHome() { return !settingsPage && !usersPage && settingsGame == null; }

    /** Home ↔ Settings */
    /** The Advanced page in place of the current one (AdvancedPage); Back returns to it */
    void openAdvanced(String titleId, String name) {
        advancedOpen = true;
        advancedTitleId = titleId;
        advancedName = name;
        build();
    }

    private void closeAdvanced() {
        advancedOpen = false;
        build();
        if (resumed) onResumeViews();
    }

    /** The Users page: the players as round pictures (UsersUi); Back returns to Home */
    private void openUsers() {
        advancedOpen = false;
        settingsPage = false;
        settingsGame = null;
        usersPage = true;
        usersEdit = null;
        Ondevice.pause(this);
        build();
        if (resumed) onResumeViews();
    }

    /** UsersUi: a player's own page, back to all players, a changed player (the dock's picture), the page built again */
    void usersEdit(String uid) { usersEdit = uid; build(); }
    void usersBack() { usersEdit = null; build(); }
    void userChanged() { styleUserButton(); }
    void rebuildUsers() { build(); }

    void page(boolean settings) {
        advancedOpen = false;
        settingsOpen = -1;
        boolean wasUsers = usersPage;
        usersPage = false;
        usersEdit = null;
        if (!wasUsers && settingsPage == settings && settingsGame == null) return;
        settingsPage = settings;
        settingsGame = null;
        if (settings) Ondevice.pause(this);   // the job's tile is on Home only: no updates while Settings shows
        build();
        if (resumed) { onResumeViews(); if (!settings) Ondevice.resume(this); }
    }

    /** A game's settings page (a tap on its tile, GameSettings): a full page in place of Home; Back returns to Home */
    void gameSettings(Game g) {
        advancedOpen = false;
        settingsGame = g;
        settingsPage = false;
        Ondevice.pause(this);
        build();
        if (resumed) onResumeViews();
    }

    /** The game's settings page again after a choice changed its rows, scrolled where it was */
    private void gameSettingsAgain() {
        if (settingsGame == null) return;
        int y = scrollY();
        View f = getCurrentFocus();
        int at = f == null ? -1 : PadFocus.focusables(pageRoot()).indexOf(f);   // a controller's focus stays on the same row after the page is built again
        build();
        scrollTo(y);
        if (at >= 0 && !pageRoot().isInTouchMode()) pageRoot().post(() -> { java.util.ArrayList<View> l = PadFocus.focusables(pageRoot()); if (at < l.size()) l.get(at).requestFocus(); });
    }

    private View pageRoot() { return bananas.getParent() instanceof View ? (View) bananas.getParent() : bananas; }

    /** A controller: B goes back one level (a game's page or Settings to Home), L1 / R1 switch a game page's tabs; the rest is Android's focus (D-pad, A) */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (PadFocus.isBack(k) && (settingsPage || usersPage || settingsGame != null || advancedOpen)) {
            if (e.getAction() == KeyEvent.ACTION_UP) onBackPressed();
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && (k == KeyEvent.KEYCODE_BUTTON_A || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)) UiSound.select(this);
        if (moveId != null && onHome() && moveKey(e)) return true;
        if (k == KeyEvent.KEYCODE_BUTTON_X && onHome() && !advancedOpen) {   // Hold X: the focused game's options, as a long press
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof TileInfo && !((TileInfo) f.getTag()).work) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 1) f.performLongClick();
                return true;
            }
        }
        if (k == KeyEvent.KEYCODE_BUTTON_Y && !settingsPage && !usersPage && settingsGame == null && !advancedOpen) {   // Home: the focused game's reorder menu, as a long press
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof TileInfo && !((TileInfo) f.getTag()).work) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) f.performLongClick();
                return true;
            }
        }
        int step = PadFocus.tabStep(k);
        if (step != 0 && settingsGame != null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && GameSettings.stepTab(settingsGame, step)) gameSettingsTop();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private float tapX, tapY;
    private long tapAt;

    /** The select sound (UiSound) for a tap that lands on something with a click action: a touch that goes nowhere, or a scroll, is silent */
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        int a = e.getActionMasked();
        if (a == android.view.MotionEvent.ACTION_DOWN) { tapX = e.getRawX(); tapY = e.getRawY(); tapAt = e.getEventTime(); }
        else if (a == android.view.MotionEvent.ACTION_UP) {
            float slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
            boolean tap = Math.abs(e.getRawX() - tapX) <= slop && Math.abs(e.getRawY() - tapY) <= slop && e.getEventTime() - tapAt < android.view.ViewConfiguration.getLongPressTimeout();
            if (tap && clickableAt(getWindow().getDecorView(), e.getRawX(), e.getRawY())) UiSound.select(this);
        }
        return super.dispatchTouchEvent(e);
    }

    private static boolean clickableAt(View v, float x, float y) {
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        if (v.getVisibility() != View.VISIBLE || x < at[0] || y < at[1] || x >= at[0] + v.getWidth() || y >= at[1] + v.getHeight()) return false;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) if (clickableAt(g.getChildAt(i), x, y)) return true;
        }
        return v.hasOnClickListeners() && v.isEnabled();   // a view that is only "clickable" (a card with no action) is silent
    }

    /** The game's page at its top (another tab) */
    void gameSettingsTop() { if (settingsGame != null) build(); }

    /** The page's ScrollView (Ui.page: the first view above the bananas) */
    private android.widget.ScrollView pageScroll() {
        FrameLayout frame = (FrameLayout) bananas.getParent();
        return frame.getChildCount() > 1 && frame.getChildAt(1) instanceof android.widget.ScrollView ? (android.widget.ScrollView) frame.getChildAt(1) : null;
    }
    private android.widget.ScrollView splitScroll;   // the right pane of a split page (newPane), else null
    private int scrollY() { android.widget.ScrollView s = splitScroll != null ? splitScroll : pageScroll(); return s == null ? 0 : s.getScrollY(); }
    private void scrollTo(int y) { android.widget.ScrollView s = splitScroll != null ? splitScroll : pageScroll(); if (s != null && y > 0) s.post(() -> s.scrollTo(0, y)); }

    /** The right pane of a split page: a column in its own scroll view beside the left column in body; returns the column */
    LinearLayout newPane(LinearLayout body) {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setPadding(0, dp(4), dp(4), dp(16));
        sv.addView(pane, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMarginStart(dp(16));
        body.addView(sv, lp);
        splitScroll = sv;
        return pane;
    }

    private int settingsOpen = -1;   // a narrow window: the section of Settings that is open, -1 for the list of sections
    private static int settingsGroup;   // the section of Settings the wide page shows

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (ReleasePage.back(this)) return;   // the release page over Home
        if (moveId != null) { endMove(false); return; }   // a lifted game goes back to its place
        if (advancedOpen) closeAdvanced();
        else if (usersPage) { if (usersEdit != null) usersBack(); else page(false); }
        else if (settingsPage && settingsGame == null && settingsOpen >= 0 && getResources().getConfiguration().screenWidthDp < 600) { settingsOpen = -1; build(); }   // a section back to the list
        else if (settingsPage || settingsGame != null) page(false);
        else super.onBackPressed();
    }

    /** (Re)builds the current page above the bananas (Ui.page: the same gutter, width and header spacing as every screen; a game's settings page wider on a wide window, for its rail) */
    private void build() {
        FrameLayout frame = (FrameLayout) bananas.getParent();
        while (frame.getChildCount() > 1) frame.removeViewAt(1);
        if (!onHome()) { moveId = null; moveOrig = null; }   // a lifted game is put back when Home is left
        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        boolean home = settingsGame == null && !settingsPage && !usersPage;   // Home never scrolls up or down: its games are one row that scrolls sideways
        splitScroll = null;
        // A wide window (a phone sideways, a tablet): Home, a game's page and Settings are full width and the page itself does not scroll; the two pages are a left column
        // (back, the game or "Settings", the list of sections) and a right pane that scrolls on its own (newPane). The Advanced switches page keeps the scrolling page
        LinearLayout content = home || wide && !advancedOpen && !usersPage ? Ui.page(this, frame, 4000, false, true) : Ui.page(this, frame, 640);
        poppedText = null;
        if (settingsGame != null) for (Game x : Game.load(this)) if (x.gameDir.equals(settingsGame.gameDir)) { settingsGame = x; break; }   // its folders as they are now (Folders, Import pack)
        if (advancedOpen) { accessCard = null; gameBox = null; overlayRow = null; AdvancedPage.build(this, content, advancedTitleId, advancedName, this::closeAdvanced); }
        else if (settingsGame != null) { accessCard = null; gameBox = null; overlayRow = null; GameSettings.build(this, content, settingsGame, this::gameSettingsAgain, () -> page(false)); }
        else if (usersPage) { accessCard = null; gameBox = null; overlayRow = null; UsersUi.build(this, content, usersEdit); }
        else if (settingsPage) buildSettings(content);
        else buildHome(content);
        hint = new HintBar(this);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hl.bottomMargin = dp(12);
        frame.addView(hint, hl);
        hintUpdate();
        bananaTaps(frame);
        noFocusTint(frame);
        if (settingsGame != null || settingsPage || usersPage || advancedOpen) frame.post(() -> {   // a controller (not touch mode): the focus starts on the page's selected tab, else its first row (Home: refresh puts it on the first tile)
            if (frame.isInTouchMode() || getCurrentFocus() != null && frame.hasFocus()) return;
            View v = PadFocus.first(PadFocus.focusables(frame));
            if (v != null) v.requestFocus();
        });
    }

    /**
     * A controller or keyboard gives the focus to the outermost view that takes it (a page's scroll view), and Android paints the whole screen with its focus
     * highlight: a yellow tint. The pages draw their own banana ring on tiles and rows, so the default highlight is off everywhere, and the scroll views that
     * only hold the page (they never take a tap) leave the focus to the tiles and rows
     */
    private static void noFocusTint(View v) {
        v.setDefaultFocusHighlightEnabled(false);
        if (v instanceof android.widget.ScrollView || v instanceof android.widget.HorizontalScrollView) v.setFocusable(false);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) noFocusTint(((ViewGroup) v).getChildAt(i));
    }

    /**
     * A tap on the page where nothing is (no button, no card, no text row with a background) pops the banana behind it (BananaBackgroundView.popAt). The page's
     * ScrollView gets only the touches no child took, and this never takes one: scrolling goes on, and a drag is not a tap.
     */
    private void bananaTaps(FrameLayout frame) {
        android.widget.ScrollView scroll = pageScroll();
        if (scroll == null) return;
        scroll.setOnTouchListener(popListener(scroll));
    }

    /**
     * The touch listener that pops a banana at a tap where nothing is: on the page's scroll view, and on Home's sideways row, which covers the page and takes
     * every touch (it is a scroll view too). It never takes a touch itself: scrolling goes on, and a drag is not a tap
     */
    private View.OnTouchListener popListener(ViewGroup root) {
        final float slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        final float[] down = new float[3];   // x, y, whether a tap may still follow (1)
        return (v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    down[0] = e.getX(); down[1] = e.getY(); down[2] = emptyAt(root, e.getX(), e.getY()) ? 1 : 0;
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getX() - down[0]) > slop || Math.abs(e.getY() - down[1]) > slop) down[2] = 0;
                    break;
                case android.view.MotionEvent.ACTION_UP:
                    if (down[2] == 1 && e.getEventTime() - e.getDownTime() < android.view.ViewConfiguration.getLongPressTimeout()) {
                        int[] a = new int[2], b = new int[2];
                        v.getLocationInWindow(a);
                        bananas.getLocationInWindow(b);
                        bananas.popAt(e.getX() + a[0] - b[0], e.getY() + a[1] - b[1]);
                    }
                    down[2] = 0;
                    break;
                default:
                    down[2] = 0;
            }
            return false;
        };
    }

    /** No view with a background (a card, a row, a button) is at this point of the scroll view: the bananas show there */
    private static boolean emptyAt(ViewGroup root, float x, float y) {
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            View c = root.getChildAt(i);
            float cx = x + root.getScrollX() - c.getLeft(), cy = y + root.getScrollY() - c.getTop();
            if (cx < 0 || cy < 0 || cx >= c.getWidth() || cy >= c.getHeight() || c.getVisibility() != View.VISIBLE) continue;
            if (c.getBackground() != null) return false;
            if (c instanceof ViewGroup && !emptyAt((ViewGroup) c, cx, cy)) return false;
        }
        return true;
    }

    private String poppedLine(long n) { return "Bananas Popped: " + java.text.NumberFormat.getIntegerInstance().format(n); }

    /** The player wiki (its pages are written and kept up to date on the project's site, so the app links to it and keeps no copy) */
    static final String WIKI_URL = "https://git.eden-emu.dev/moceure/BananaNative/wiki";

    // ---- Home: the games as one row of artwork tiles, a dock of round buttons under it, and under the dock what is selected (as the console's home screen) ----

    /** What a tile says to the dock's label: the game's name, its state (a TextView kept out of sight, the check writes it) and what the state's chip does */
    static final class TileInfo {
        final String name;
        final TextView st;
        final View.OnClickListener open;
        View ov;   // the state over the picture
        TextView ot;   // its words
        android.widget.ProgressBar pb;   // its moving bar
        ProgressRing badge, ring;   // the small corner ring (something checked or updated in the background), the big ring (the game is being prepared)
        boolean work;   // the game is being prepared (workTile): the ring shows the job's progress
        TextView sub;   // the grid's and the list's second line (HomeLayout.caption)
        String version, workLine;   // the game's version once read; the step of the job being prepared
        TileInfo(String name, TextView st, View.OnClickListener open) { this.name = name; this.st = st; this.open = open; }
    }

    private View selectedTile;
    private View addFab;   // Add game, a round banana button at the bottom right of Home
    private LinearLayout dockRow;
    private TextView userButton;

    private void buildHome(LinearLayout content) {
        Ondevice.closeSheet();   // its card goes back to the page being built
        overlayRow = null;
        selectedTile = null;
        LinearLayout top = Ui.row(this);   // the logo alone, small, at the left; the dock (the user, Add game, the wiki, Settings) in the top right corner
        ImageView logo = Ui.badge(this, 36);   // as big as the dock's buttons
        logo.setContentDescription(Tx.s(R.string.home_banana_native));
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        top.addView(logo);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        top.addView(dock(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        content.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        accessCard = accessCard();
        Ui.add(content, accessCard, 8);
        Ondevice.attach(this);   // a game being prepared: its tile carries the ring, the card is only in the job's sheet (never added to Home)
        fixGap(content, Ui.GAP);
        gameBox = new LinearLayout(this);
        gameBox.setOrientation(LinearLayout.VERTICAL);
        FrameLayout holder = new FrameLayout(this);   // the games take all the height under the top row; Add game floats at its bottom right
        holder.setClipChildren(false);
        holder.addView(gameBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addFab = addButton();
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.END | Gravity.BOTTOM);
        fl.setMarginEnd(dp(4));
        fl.bottomMargin = dp(10);
        holder.addView(addFab, fl);
        content.addView(holder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        refresh();
    }

    /** The dock: round buttons in a pill. The first is the user (the console's users, what games start with): its letter is the current user's, as the profile icon of a console's home */
    private View dock() {
        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER_VERTICAL);
        // Light glass: the card colour a little see-through, a soft highlight at its top and a light edge, so the bananas or the picture show faintly through
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
        fill.setColor((Ui.col(this, R.color.bn_card) & 0x00FFFFFF) | 0xC8000000);
        fill.setCornerRadius(dp(999));
        android.graphics.drawable.GradientDrawable gloss = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x30FFFFFF, 0x00FFFFFF});
        gloss.setCornerRadius(dp(999));
        gloss.setStroke(dp(1), 0x40FFFFFF);
        dockRow.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{fill, gloss}));
        dockRow.setPadding(dp(6), dp(4), dp(6), dp(4));
        userButton = Ui.text(this, "", 16, R.color.bn_on_leaf, 800);
        userButton.setGravity(Gravity.CENTER);
        userButton.setIncludeFontPadding(false);
        styleUserButton();
        userButton.setForeground(ring(999, false));
        userButton.setContentDescription(Tx.s(R.string.common_users));
        userButton.setTooltipText(Tx.s(R.string.common_users));
        userButton.setFocusable(true);
        userButton.setClickable(true);
        userButton.setOnClickListener(v -> openUsers());
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(dp(36), dp(36));
        up.leftMargin = up.rightMargin = dp(3);
        dockRow.addView(userButton, up);
        dockRow.addView(dockButton(R.drawable.ic_wiki, Tx.s(R.string.settings_player_wiki), false, v -> openLink(WIKI_URL)));
        dockRow.addView(dockButton(R.drawable.ic_settings, Tx.s(R.string.home_settings), false, v -> page(true)));
        return dockRow;
    }

    /** The first letter of the user games start with */

    /** The dock's user button: the picture of the user games start with (an emoji on its colour) */
    private void styleUserButton() {
        if (userButton != null) UsersUi.styleButton(this, userButton);
    }

    private View dockButton(int drawable, String name, boolean accent, View.OnClickListener l) {
        ImageView b = new ImageView(this);
        b.setImageDrawable(Ui.icon(this, drawable, accent ? R.color.bn_on_banana : R.color.bn_text));
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setBackground(Ui.round(accent ? Ui.col(this, R.color.bn_banana) : Ui.col(this, R.color.bn_tint), dp(999), 0, 0));
        b.setForeground(ring(999, false));
        b.setContentDescription(name);
        b.setTooltipText(name);
        b.setTag(name);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.leftMargin = lp.rightMargin = dp(3);
        b.setLayoutParams(lp);
        return b;
    }

    /** A banana ring (focused, or selected when withSelected) over a view, and a dim wash while it is pressed */
    android.graphics.drawable.Drawable ring(int radiusDp, boolean withSelected) {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.Drawable on = Ui.round(0, dp(radiusDp), Ui.col(this, R.color.bn_banana_deep), Ui.dp(this, 3.5f));
        s.addState(new int[]{android.R.attr.state_focused}, on);
        if (withSelected) s.addState(new int[]{android.R.attr.state_selected}, on);
        s.addState(new int[]{android.R.attr.state_pressed}, Ui.round(0x22000000, dp(radiusDp), 0, 0));
        return s;
    }

    /** The tile the focus or a touch is on: it grows */
    private void selectTile(View t) { selectTile(t, true); }

    /** The tile the focus or a swipe is on grows; with centre, a sideways row also moves so it is in the middle (not just near the edge, then jumping to the next) */
    void selectTile(View t, boolean centre) {
        if (t != null && centre && t.getParent() instanceof LinearLayout && t.getParent().getParent() instanceof android.widget.HorizontalScrollView && t.getTag() instanceof TileInfo) {
            android.widget.HorizontalScrollView hs = (android.widget.HorizontalScrollView) t.getParent().getParent();
            int to = Math.max(0, t.getLeft() + t.getWidth() / 2 - hs.getWidth() / 2);
            if (hs.getWidth() > 0 && Math.abs(to - hs.getScrollX()) > dp(2)) hs.smoothScrollTo(to, 0);
        }
        if (t == selectedTile) return;
        View old = selectedTile;
        if (old != null) old.setSelected(false);
        selectedTile = t;
        if (t != null) t.setSelected(true);
        // The selected tile grows
        ViewGroup row = (ViewGroup) (t != null ? t.getParent() : old != null ? old.getParent() : null);
        if (row == null || layoutNow != HomeLayout.ROW) return;   // only the sideways row grows the selected tile; the grid and the list have the ring
        int sel = t == null ? -1 : row.indexOfChild(t);
        final float[] grow = {1.2f};   // only the selected tile is big, the others stay as they are
        for (int k = 0; k < row.getChildCount(); k++) {
            View c = row.getChildAt(k);
            if (!(c.getTag() instanceof TileInfo)) continue;
            int d = sel < 0 ? 99 : Math.abs(k - sel);
            float f = d < grow.length ? grow[d] : 1f;
            c.animate().scaleX(f).scaleY(f).translationZ(d < grow.length ? dp(10) : 0).setDuration(140).start();
        }
    }

    /**
     * What a tile says about its game while it is not simply ready, over the picture as an install does: a dim veil with the words (Needs preparing, Preparing…, a problem)
     * and, while something is being made, a thin moving bar. "Ready" shows nothing; an optional update ("Ready · update") is a light veil with its words
     */
    // What a tile's state is, as a value the launcher compares (the words are in the app's language and never matched): ST_SOFT "Ready · note" (a light veil),
    // ST_WORKING something is being made (the moving bar), ST_HIDDEN nothing to say (Ready, Checking…). No flag: the veil with its words (needs preparing, a problem)
    static final int ST_SOFT = 1, ST_WORKING = 2, ST_HIDDEN = 4;

    /** Sets a tile's state words and colour and remembers the kind of state it is (ST_*) */
    private static void state(TextView t, CharSequence words, int colorRes, int kind) {
        Ui.status(t, words, colorRes);
        t.setTag(kind);
    }

    static int kindOf(TextView t) { return t.getTag() instanceof Integer ? (Integer) t.getTag() : 0; }

    private final java.util.Map<TextView, Float> shaderProgress = new java.util.WeakHashMap<>();   // a card's background shader update: done / total (none: not known)

    private void overlay(View ov, TextView ot, android.widget.ProgressBar pb, ProgressRing badge, TextView st) {
        if (ov.getTag() != null) return;   // a game that is being prepared: workTick draws it
        int kind = kindOf(st);
        badge.setVisibility(View.GONE);
        String s = st.getText().toString();
        s = s.replaceFirst("^[\\u25CF\\u2022\\s]+", "").trim();   // the state's dot (Ui.status) is not part of the words
        if (s.isEmpty() || (kind & ST_HIDDEN) != 0) { ov.setVisibility(View.GONE); return; }
        boolean soft = (kind & ST_SOFT) != 0, working = (kind & ST_WORKING) != 0;
        if (soft && working) {   // still playable, something goes on in the background (a quick check of the shaders, an update): the picture stays, a small ring says it
            ov.setVisibility(View.GONE);
            Float p = shaderProgress.get(st);
            badge.set(p == null ? -1f : p);
            badge.setContentDescription(s);
            badge.setVisibility(View.VISIBLE);
            return;
        }
        ov.setBackground(Ui.round(soft ? 0x66000000 : 0xB3000000, dp(22), 0, 0));
        ot.setText(soft ? s.replaceFirst("^[^\\u00b7]*\\u00b7\\s*", "") : s);   // "Ready · note": the note alone
        pb.setVisibility(working ? View.VISIBLE : View.GONE);
        ov.setVisibility(View.VISIBLE);
    }

    /** The Ondevice card is added without a margin: give it the cards' gap */
    private void fixGap(LinearLayout col, int gapDp) {
        for (int k = 1; k < col.getChildCount(); k++) {
            View v = col.getChildAt(k);
            ViewGroup.LayoutParams p = v.getLayoutParams();
            if (p instanceof LinearLayout.LayoutParams && ((LinearLayout.LayoutParams) p).topMargin == 0) ((LinearLayout.LayoutParams) p).topMargin = dp(gapDp);
        }
    }

    private View accessCard() {
        LinearLayout c = Ui.card(this);
        c.setBackground(Ui.round(Ui.col(this, R.color.bn_warn_tint), dp(20), Ui.col(this, R.color.bn_banana_deep), dp(1)));
        c.addView(Ui.heading(this, Tx.s(R.string.home_allow_file_access)));
        Ui.add(c, Ui.body(this, Tx.s(R.string.home_needed_to_read_a_dump)), 4);
        Ui.add(c, Ui.button(this, Tx.s(R.string.home_allow), Ui.Kind.PRIMARY, v -> openAccessSettings()), 14, Ui.wrap());
        return c;
    }

    // ---- Settings page ----

    private boolean defaultsOpen;   // "Defaults for all games" open (closed at first; kept while the page is rebuilt)

    private void buildSettings(LinearLayout target) {
        boolean narrow = getResources().getConfiguration().screenWidthDp < 600;
        // Wide: the page is built as one column as always, then cut at its section labels: the labels become the left list, one section's rows show at the right
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        buildSettingsInto(col);
        java.util.List<String> names = new ArrayList<>();
        java.util.List<java.util.List<View>> groups = new ArrayList<>();
        java.util.List<View> before = new ArrayList<>();
        for (int k = 1; k < col.getChildCount(); k++) {   // child 0 is the header: replaced by the left column's back button and title
            View v = col.getChildAt(k);
            TextView label = sectionLabel(v);
            if (label != null) { names.add(label.getText().toString()); groups.add(new ArrayList<>()); }
            if (groups.isEmpty()) before.add(v); else groups.get(groups.size() - 1).add(v);
        }
        if (groups.isEmpty()) { buildSettingsInto(target); return; }
        if (narrow) { narrowSettings(target, col, names, groups, before); return; }
        settingsGroup = Math.max(0, Math.min(settingsGroup, groups.size() - 1));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        target.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView ls = new android.widget.ScrollView(this);
        ls.setVerticalScrollBarEnabled(false);
        ls.addView(left);
        body.addView(ls, new LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout top = Ui.row(this);
        top.addView(Ui.back(this, v -> page(false)));
        TextView title = Ui.text(this, Tx.s(R.string.home_settings), 20, R.color.bn_text, 800);
        title.setAccessibilityHeading(true);
        title.setPadding(dp(10), 0, 0, 0);
        top.addView(title);
        left.addView(top);
        for (int k = 0; k < names.size(); k++) {
            int i = k;
            String e = sectionEmoji(names.get(k));
            TextView n = Ui.navItem(this, e, names.get(k).substring(0, 1).toUpperCase() + names.get(k).substring(1), k == settingsGroup, v -> { settingsGroup = i; build(); });
            n.setMinHeight(dp(38));
            Ui.add(left, n, k == 0 ? 8 : 0);
        }
        LinearLayout pane = newPane(body);
        for (View v : before) { col.removeView(v); pane.addView(v); }
        for (View v : new ArrayList<>(groups.get(settingsGroup))) { col.removeView(v); pane.addView(v); }
    }

    /** The emoji of a Settings section, by its (translated) label */
    private static String sectionEmoji(String label) {
        String[][] map = {{Tx.s(R.string.settings_updates), "\uD83D\uDD04"}, {Tx.s(R.string.common_appearance), "\uD83C\uDF17"}, {Tx.s(R.string.common_display), "\uD83D\uDDA5\uFE0F"},
                {Tx.s(R.string.common_controls), "\uD83D\uDD79\uFE0F"}, {Tx.s(R.string.common_defaults_for_all_games), "\uD83C\uDF9B\uFE0F"}, {Tx.s(R.string.common_system), "\u2699\uFE0F"},
                {Tx.s(R.string.common_help), "\uD83D\uDEDF"}, {Tx.s(R.string.common_about), "\u2139\uFE0F"}};
        for (String[] m : map) if (m[0].equals(label)) return m[1];
        return "\u2022";
    }

    /** A narrow window: first the list of sections (one tap opens one), then the section's rows under a header whose Back returns to the list */
    private void narrowSettings(LinearLayout target, LinearLayout col, java.util.List<String> names, java.util.List<java.util.List<View>> groups, java.util.List<View> before) {
        if (settingsOpen >= groups.size()) settingsOpen = -1;
        if (settingsOpen < 0) {
            target.addView(Ui.header(this, Ui.back(this, v -> page(false)), Tx.s(R.string.settings_settings), null));
            for (View v : before) { col.removeView(v); target.addView(v); }
            LinearLayout list = Ui.add(target, Ui.listCard(this), 10);
            for (int k = 0; k < names.size(); k++) {
                int i = k;
                String e = sectionEmoji(names.get(k));
                TextView n = Ui.navItem(this, e, names.get(k).substring(0, 1).toUpperCase() + names.get(k).substring(1), false, v -> { settingsOpen = i; build(); });
                n.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, Ui.icon(this, R.drawable.ic_chevron, R.color.bn_text_2), null);
                list.addView(n);
            }
            return;
        }
        String name = names.get(settingsOpen);
        target.addView(Ui.header(this, Ui.back(this, v -> { settingsOpen = -1; build(); }), name.substring(0, 1).toUpperCase() + name.substring(1), null));
        for (View v : before) { col.removeView(v); target.addView(v); }
        for (View v : new ArrayList<>(groups.get(settingsOpen))) {
            col.removeView(v);
            if (!"section".equals(v.getTag())) target.addView(v);   // the section's own label repeats the header
        }
    }

    /** The section label a view is or starts with (Ui.section's tag; "Defaults for all games" has it inside its head row), else null */
    private static TextView sectionLabel(View v) {
        if ("section".equals(v.getTag()) && v instanceof TextView) return (TextView) v;
        if (v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 0) return sectionLabel(((ViewGroup) v).getChildAt(0));
        return null;
    }

    /** Settings › Appearance › Language: System default first, then each language by its own name (Languages); the system recreates the screens after a pick */
    private Ui.Setting languageRow() {
        Ui.Setting row = new Ui.Setting(this, "🌐", Tx.s(R.string.settings_language_title), null).chevron(this);
        row.value(Languages.current(this));
        row.view.setOnClickListener(v -> {
            String[] names = new String[Languages.LIST.length + 1];
            names[0] = Languages.systemLabel();
            for (int i = 0; i < Languages.LIST.length; i++) names[i + 1] = Languages.LIST[i].label();
            Languages.Lang cur = Languages.picked(this);
            int sel = 0;
            for (int i = 0; i < Languages.LIST.length; i++) if (Languages.LIST[i] == cur) sel = i + 1;
            new PadFocus.Builder(this).setTitle(Tx.s(R.string.settings_language_title))
                    .setSingleChoiceItems(names, sel, (d, w) -> { d.dismiss(); Languages.apply(this, w == 0 ? null : Languages.LIST[w - 1]); })
                    .setNegativeButton(Tx.s(R.string.common_cancel), null).show();
        });
        return row;
    }

    private void buildSettingsInto(LinearLayout content) {
        accessCard = null;
        gameBox = null;
        content.addView(Ui.header(this, Ui.back(this, v -> page(false)), Tx.s(R.string.settings_settings), null));

        // Updates first, so the version and the check are easy to find (Updater: the release app also checks at every fresh start)
        Ui.add(content, Ui.section(this, Tx.s(R.string.settings_updates)), 16);
        LinearLayout upd = settingsCard(content);
        updateRow = new Ui.Setting(this, "🔄", Tx.s(R.string.common_check_for_updates), v -> Updater.checkNow(this)).chevron(this);
        upd.addView(updateRow.view);
        upd.addView(new Ui.Setting(this, "🩹", Tx.s(R.string.settings_small_fix_updates), null)
                .toggle(this, Prefs.hotfixUpdates(this), (b, on) -> Prefs.hotfixUpdates(this, on))
                .value(Tx.s(R.string.settings_small_fix_updates_help)).view);
        upd.addView(new Ui.Setting(this, "📖", Tx.s(R.string.settings_player_wiki), v -> openLink(WIKI_URL)).chevron(this)
                .value(Tx.s(R.string.settings_guides_what_every_setting_does)).view);

        Ui.add(content, Ui.section(this, Tx.s(R.string.common_appearance)), Ui.SECTION_GAP);
        LinearLayout look = settingsCard(content);
        look.addView(languageRow().view);
        Ui.Setting theme = new Ui.Setting(this, "🌗", Tx.s(R.string.settings_theme), null).chevron(this);
        theme.view.setOnClickListener(v -> new PadFocus.Builder(this).setTitle(Tx.s(R.string.settings_theme))
                .setSingleChoiceItems(Tx.arr(R.array.arr_theme), Prefs.theme(this), (d, w) -> { Prefs.theme(this, w); d.dismiss(); NxApp.applyTheme(this); })
                .setNegativeButton(Tx.s(R.string.common_cancel), null).show());
        theme.value(Tx.arr(R.array.arr_theme)[Prefs.theme(this)]);
        look.addView(theme.view);
        Ui.Setting bg = new Ui.Setting(this, "🖼️", Tx.s(R.string.settings_background), null).chevron(this);
        bg.view.setOnClickListener(v -> new PadFocus.Builder(this).setTitle(Tx.s(R.string.settings_background))
                .setSingleChoiceItems(Tx.arr(R.array.arr_background), Prefs.background(this), (d, w) -> {
                    d.dismiss();
                    if (w == 2) {   // a picture: pick it first, the choice is kept once it is read
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
                        startActivityForResult(i, PICK_BG);
                        return;
                    }
                    Prefs.background(this, w);
                    bananas.setLook(w);
                    bg.value(Tx.arr(R.array.arr_background)[w]);
                }).setNegativeButton(Tx.s(R.string.common_cancel), null).show());
        bg.value(Tx.arr(R.array.arr_background)[Prefs.background(this)]);
        look.addView(bg.view);
        Ui.Setting lay = new Ui.Setting(this, "\uD83D\uDDC2\uFE0F", Tx.s(R.string.settings_home_layout), null).chevron(this);
        lay.view.setOnClickListener(v -> new PadFocus.Builder(this).setTitle(Tx.s(R.string.settings_home_layout))
                .setSingleChoiceItems(Tx.arr(R.array.arr_home_layout), HomeLayout.mode(this), (d, w) -> {
                    d.dismiss();
                    Prefs.homeLayout(this, w);
                    lay.value(Tx.arr(R.array.arr_home_layout)[w]);   // Home is built again when Settings closes
                }).setNegativeButton(Tx.s(R.string.common_cancel), null).show());
        lay.value(Tx.arr(R.array.arr_home_layout)[HomeLayout.mode(this)]);
        look.addView(lay.view);
        look.addView(new Ui.Setting(this, "🔊", Tx.s(R.string.settings_ui_sounds), null).toggle(this, Prefs.uiSounds(this), (b, on) -> { Prefs.uiSounds(this, on); if (on) UiSound.select(this); })
                .value(Tx.s(R.string.settings_a_tick_when_the_focus)).view);
        Ui.Setting snd = new Ui.Setting(this, "🎵", Tx.s(R.string.settings_my_sound), null).chevron(this);
        snd.view.setOnClickListener(v -> {
            if (UiSound.custom(this).isFile()) new PadFocus.Builder(this).setTitle(Tx.s(R.string.settings_my_sound)).setItems(new String[]{Tx.s(R.string.settings_pick_another_sound), Tx.s(R.string.settings_use_the_built_in_sounds)}, (d, w) -> {
                if (w == 0) pickSound(); else { UiSound.clearCustom(this); snd.value("Built-in"); }
            }).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
            else pickSound();
        });
        snd.value(UiSound.custom(this).isFile() ? Tx.s(R.string.settings_your_sound) : "Built-in");
        look.addView(snd.view);
        if (BuildConfig.DEBUG) look.addView(new Ui.Setting(this, "🧪", Tx.s(R.string.settings_sample_games), null).toggle(this, Prefs.demoGames(this), (b, on) -> { Prefs.demoGames(this, on); refresh(); })
                .value(Tx.s(R.string.settings_test_builds_shows_sample_tiles)).view);

        Ui.add(content, Ui.section(this, Tx.s(R.string.common_display)), Ui.SECTION_GAP);
        LinearLayout play = settingsCard(content);
        Ui.add(content, Ui.section(this, Tx.s(R.string.common_controls)), Ui.SECTION_GAP);
        LinearLayout ctl = settingsCard(content);
        Ui.add(content, Ui.section(this, Tx.s(R.string.common_system)), Ui.SECTION_GAP);
        LinearLayout sys = settingsCard(content);
        // Every default a game inherits and its own page can override: one collapsed group, after the settings that only exist here
        LinearLayout perf = defaultsGroup(content);
        overlayRow = new Ui.Setting(this, "👀", Tx.s(R.string.settings_overlay), v -> overlayItems())
                .toggle(this, Prefs.overlay(this), (b, on) -> { Prefs.overlay(this, on); refreshSettings(); });
        play.addView(overlayRow.view);
        Ui.Setting labelRow = new Ui.Setting(this, "🔨", Tx.s(R.string.settings_building_shaders_label), null).chevron(this);   // the small count over a game that builds shaders while it plays
        labelRow.value(Overlay.labelChoices()[Prefs.layerLabel(this)]).note(Tx.s(R.string.settings_building_shaders_label_note));
        labelRow.view.setOnClickListener(v -> new PadFocus.Builder(this).setTitle(Tx.s(R.string.settings_building_shaders_label))
                .setSingleChoiceItems(Overlay.labelChoices(), Prefs.layerLabel(this), (d, w) -> { Prefs.layerLabel(this, w); labelRow.value(Overlay.labelChoices()[w]); d.dismiss(); })
                .setNegativeButton(Tx.s(R.string.common_cancel), null).show());
        play.addView(labelRow.view);
        Ui.Setting touch = new Ui.Setting(this, "🎮", Tx.s(R.string.common_on_screen_controls), null).chevron(this);
        touch.view.setOnClickListener(v -> new PadFocus.Builder(this).setTitle(Tx.s(R.string.common_on_screen_controls))
                .setSingleChoiceItems(Tx.arr(R.array.arr_touch_mode), TouchControls.mode(this), (d, w) -> { TouchControls.mode(this, w); touch.value(touchLabel(w)); d.dismiss(); })
                .setNegativeButton(Tx.s(R.string.common_cancel), null).show());
        touch.value(touchLabel(TouchControls.mode(this)));
        ctl.addView(touch.view);
        Ui.Setting rot = new Ui.Setting(this, "🔄", Tx.s(R.string.common_screen_rotation), null).chevron(this);
        rot.view.setOnClickListener(v -> new PadFocus.Builder(this).setTitle(Tx.s(R.string.common_screen_rotation))
                .setSingleChoiceItems(Tx.arr(R.array.arr_rotation), Prefs.rotationAuto(this) ? 1 : 0, (d, w) -> { Prefs.rotationAuto(this, w == 1); rot.value(rotationLabel(w == 1)); d.dismiss(); })
                .setNegativeButton(Tx.s(R.string.common_cancel), null).show());
        rot.value(rotationLabel(Prefs.rotationAuto(this)));
        play.addView(rot.view);
        ctl.addView(new Ui.Setting(this, "🔘", Tx.s(R.string.settings_show_hide_button), null).toggle(this, TouchControls.toggleButton(this), (b, on) -> TouchControls.toggleButton(this, on))
                .value(Tx.s(R.string.settings_the_small_button_at_the)).view);
        // How a controller opens the in-game panel (PadPanel); the panel opens by touch with a swipe in from the right edge or Back
        Ui.Setting padPanel = new Ui.Setting(this, "🎛️", Tx.s(R.string.settings_controller_in_game_panel), null).chevron(this);
        padPanel.view.setOnClickListener(v -> new PadFocus.Builder(this).setTitle(Tx.s(R.string.common_open_the_in_game_panel))
                .setSingleChoiceItems(Tx.arr(R.array.arr_pad_open), Prefs.panelPad(this), (d, w) -> { Prefs.panelPad(this, w); padPanel.value(padPanelLabel(w)); d.dismiss(); })
                .setNegativeButton(Tx.s(R.string.common_cancel), null).show());
        padPanel.value(padPanelLabel(Prefs.panelPad(this)));
        ctl.addView(padPanel.view);
        resRow = new Ui.Setting(this, "🖼️", Tx.s(R.string.common_resolution), v -> gameRes()).chevron(this);
        perf.addView(resRow.view);
        if (!Profile.anyResolution()) resRow.view.setVisibility(View.GONE);   // no game of this APK has a window_fn or graphics_scale lever: the default would do nothing
        capRow = new Ui.Setting(this, "⏱️", Tx.s(R.string.common_frame_rate_cap), v -> fpsCap()).chevron(this);
        perf.addView(capRow.view);
        ctl.addView(new Ui.Setting(this, "📳", Tx.s(R.string.common_rumble), null).toggle(this, Prefs.rumble(this), (b, on) -> Prefs.rumbleDefault(this, on))
                .value(Tx.s(R.string.settings_on_the_controller_or_the)).view);
        perf.addView(new Ui.Setting(this, "🚀", Tx.s(R.string.common_use_fast_cores), null).toggle(this, Prefs.pinThreads(this), (b, on) -> Prefs.pinThreadsDefault(this, on))
                .value(Tx.s(R.string.settings_keeps_the_busiest_threads_off)).view);
        Ui.Setting xbox = new Ui.Setting(this, "🅰️", Tx.s(R.string.settings_xbox_button_label_swap), null).chevron(this);
        xbox.view.setOnClickListener(v -> new PadFocus.Builder(this).setTitle(Tx.s(R.string.settings_xbox_button_label_swap))
                .setSingleChoiceItems(Tx.arr(R.array.arr_xbox), Prefs.xboxButtons(this), (d, w) -> { Prefs.xboxButtonsDefault(this, w); xbox.value(Prefs.xboxLabel(w)); d.dismiss(); })
                .setNegativeButton(Tx.s(R.string.common_cancel), null).show());
        xbox.value(Prefs.xboxLabel(Prefs.xboxButtons(this)));
        ctl.addView(xbox.view);
        Ui.Setting padMap = new Ui.Setting(this, "🕹️", Tx.s(R.string.common_button_mapping), null).chevron(this);
        padMap.view.setOnClickListener(v -> PadMapUi.forDefault(this, () -> padMap.value(PadMapUi.label(this, null))));
        padMap.value(PadMapUi.label(this, null));
        ctl.addView(padMap.view);
        upsRow = new Ui.Setting(this, "🔍", Tx.s(R.string.common_upscaler), v -> pick(Tx.s(R.string.common_upscaler), Tx.arr(R.array.arr_upscaler_modes), GameUpscaler.mode(this), w -> GameUpscaler.modeDefault(this, w))).chevron(this);
        perf.addView(upsRow.view);
        sharpRow = new Ui.Setting(this, "✨", Tx.s(R.string.common_fsr_sharpness), v -> pick(Tx.s(R.string.common_fsr_sharpness), Tx.arr(R.array.arr_fsr_sharp), GameUpscaler.sharpIndex(GameUpscaler.sharp(this)),
                w -> GameUpscaler.sharpDefault(this, GameUpscaler.SHARPS[w]))).chevron(this);
        perf.addView(sharpRow.view);
        drsRow = new Ui.Setting(this, "📐", Tx.s(R.string.common_dynamic_resolution), v -> DrsSlider.dialog(this, "", Prefs.drs(this), null, true, w -> Prefs.drsDefault(this, w),
                this::refreshSettings)).chevron(this);
        perf.addView(drsRow.view);
        lsfgRow = new Ui.Setting(this, "✨", Tx.s(R.string.settings_frame_generation), v -> frameGen()).chevron(this);
        play.addView(lsfgRow.view);   // no per-game twin (the in-game panel has it): not a default, so it stays on the page
        gpuRow = new Ui.Setting(this, "🎮", Tx.s(R.string.common_gpu_driver), v -> GpuDriver.dialog(this, PICK_DRIVER)).chevron(this);   // custom GPU drivers (GpuDriver.java)
        sys.addView(gpuRow.view);
        timeRow = new Ui.Setting(this, "🕒", Tx.s(R.string.common_time_of_day), v -> TimeOfDay.dialog(this, this::refreshSettings)).chevron(this);   // the clock the next start gives the game (Prefs.timeOverride → DM_TIME)
        sys.addView(timeRow.view);
        Ui.Setting advRow = new Ui.Setting(this, "🛠️", Tx.s(R.string.settings_advanced), null).chevron(this);   // every switch the host offers, by area, with search (AdvancedPage.java)
        advRow.view.setOnClickListener(v -> AdvancedPage.show(this, null, null, () -> advRow.value(AdvancedSettings.count(this, null) == 0 ? Tx.s(R.string.common_search_every_switch_each_starts) : Tx.n(R.plurals.common_n_set, AdvancedSettings.count(this, null), AdvancedSettings.count(this, null)))));
        advRow.value(AdvancedSettings.count(this, null) == 0 ? Tx.s(R.string.common_search_every_switch_each_starts) : Tx.n(R.plurals.common_n_set, AdvancedSettings.count(this, null), AdvancedSettings.count(this, null)));
        sys.addView(advRow.view);
        sys.addView(new Ui.Setting(this, "📁", Tx.s(R.string.common_folders), v -> Folders.dialog(this)).value(Tx.s(R.string.common_game_pack_and_save_folders)).chevron(this).view);
        sys.addView(new Ui.Setting(this, "🧹", Tx.s(R.string.settings_storage), v -> StorageUi.dialog(this)).value(Tx.s(R.string.settings_remove_leftovers_and_old_files)).chevron(this).view);   // (StorageUi.java)
        // some phones stop or slow a background job once the screen is off (GitHub discussion 58): Home keeps the screen on while preparing (Ondevice), the service its wake lock
        sys.addView(new Ui.Setting(this, "🔆", Tx.s(R.string.settings_keep_the_screen_on_while), null).toggle(this, Prefs.keepScreenPreparing(this), (b, on) -> Prefs.keepScreenPreparing(this, on))
                .value(Tx.s(R.string.settings_while_home_shows_a_game)).view);   // the game, pack and save folders, kept across updates (Folders.java)

        Ui.add(content, Ui.section(this, Tx.s(R.string.common_help)), Ui.SECTION_GAP);
        LinearLayout help = settingsCard(content);
        help.addView(new Ui.Setting(this, "📋", Tx.s(R.string.common_readiness_check), v -> startActivityForResult(new Intent(this, ReadinessActivity.class), ReadinessActivity.REQUEST))
                .value(Tx.s(R.string.settings_can_this_phone_run_your)).chevron(this).view);   // (ReadinessActivity.java)
        help.addView(new Ui.Setting(this, "🧪", Tx.s(R.string.settings_graphics_self_test), v -> startActivity(new Intent(this, SelftestActivity.class))).value(Tx.s(R.string.settings_needs_no_game)).chevron(this).view);
        help.addView(new Ui.Setting(this, "📨", Tx.s(R.string.common_share_log), v -> shareLog()).value(Tx.s(R.string.settings_the_newest_log)).chevron(this).view);
        help.addView(new Ui.Setting(this, "🧰", Tx.s(R.string.settings_tester_settings), null).toggle(this, Prefs.testerRows(this), (b, on) -> Prefs.testerRows(this, on))
                .says(Tx.s(R.string.settings_showing_the_rows_made_for), Tx.s(R.string.settings_hidden_a_game_with_resolution)).view);   // (Prefs.simpleRes)
        if (BuildConfig.NX_PLAY) {   // a test build always takes adb's commands (Prefs.adbControl)
            Ui.Setting adb = new Ui.Setting(this, "🛠", Tx.s(R.string.settings_adb_control_debug), null)
                    .value(Tx.s(R.string.settings_lets_adb_commands_add_games));
            adb.toggle(this, Prefs.adbControl(this), (b, on) -> {
                if (on == Prefs.adbControl(this)) return;   // the switch set back to what is stored (below): nothing to do
                if (!on) { Prefs.adbControl(this, false); return; }
                b.setChecked(false);   // stays off until confirmed
                Ui.confirmDanger(this, Tx.s(R.string.settings_turn_adb_control_on), Tx.s(R.string.settings_adb_control_warning),
                        Tx.s(R.string.settings_turn_on), () -> { Prefs.adbControl(this, true); b.setChecked(true); });
            });
            help.addView(adb.view);
        }

        Ui.add(content, Ui.section(this, Tx.s(R.string.common_about)), Ui.SECTION_GAP);
        LinearLayout about = Ui.add(content, Ui.card(this), 8);
        TextView tag = Ui.chip(this, Prefs.buildTag(this));   // which build this is (also the first line of every log)
        tag.setTextIsSelectable(true);
        about.addView(tag, Ui.wrap());
        poppedText = Ui.small(this, poppedLine(Prefs.bananasPopped(this)));   // the background's bananas pop when tapped (BananaBackgroundView)
        Ui.add(about, poppedText, 10);
        StringBuilder ids = new StringBuilder();   // the games this build includes, so a player can tell whether it has theirs (Profile.missing points here)
        for (Profile p : Profile.all()) ids.append("\n· ").append(p.name).append(" (").append(p.titleId).append(")");   // names and title IDs, not text   // names and title IDs, not text
        TextView t = Ui.small(this, Tx.s(R.string.settings_about_games, ids) + (BuildConfig.NX_LSFG ? "" : Tx.s(R.string.settings_built_without_lsfg)));
        t.setTextIsSelectable(true);
        Ui.add(about, t, 10);
        refreshSettings();
    }

    /** "Defaults for all games": a closed group (its head is one focusable line, like Advanced on a game's page) whose card keeps its rows as views while closed,
     *  so refreshSettings and the row fields work the same. Returns the card to add the rows to */
    private LinearLayout defaultsGroup(LinearLayout content) {
        LinearLayout head = Ui.row(this);
        head.setMinimumHeight(dp(44));
        head.setBackground(Ui.ripple(this, Ui.round(android.graphics.Color.TRANSPARENT, dp(12), 0, 0)));
        head.addView(Ui.section(this, Tx.s(R.string.common_defaults_for_all_games)), Ui.weight());
        TextView act = Ui.text(this, "", 13, R.color.bn_accent, 700);
        act.setPadding(dp(8), 0, dp(4), 0);
        head.addView(act);
        ImageView ch = new ImageView(this);
        ch.setImageDrawable(Ui.icon(this, R.drawable.ic_chevron, R.color.bn_accent));
        ch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        head.addView(ch, new LinearLayout.LayoutParams(dp(20), dp(20)));
        head.setClickable(true);
        head.setFocusable(true);
        Ui.add(content, head, Ui.SECTION_GAP);
        TextView note = Ui.small(this, Tx.s(R.string.settings_each_game_s_page_can));
        note.setPadding(dp(4), 0, dp(4), 0);
        Ui.add(content, note, 4);
        LinearLayout card = settingsCard(content);
        Runnable show = () -> {
            card.setVisibility(defaultsOpen ? View.VISIBLE : View.GONE);
            act.setText(defaultsOpen ? Tx.s(R.string.common_hide) : Tx.s(R.string.settings_show));
            ch.setRotation(defaultsOpen ? -90 : 90);
            head.setContentDescription(defaultsOpen ? Tx.s(R.string.home_hide_defaults_for_all_games) : Tx.s(R.string.home_show_defaults_for_all_games));
        };
        head.setOnClickListener(v -> { defaultsOpen = !defaultsOpen; show.run(); });
        show.run();
        return card;
    }

    /** A settings group's card of rows, under its section label */
    private LinearLayout settingsCard(LinearLayout content) { return Ui.add(content, Ui.listCard(this), 8); }

    /** The settings rows' current values (after a dialog, on resume) */
    private void refreshSettings() {
        if (overlayRow == null) return;
        updateRow.value(Tx.s(R.string.settings_banana_native_version, Updater.version(this)) + (Updater.available(this) ? "" : Tx.s(R.string.home_release_app_only)));
        boolean ov = Prefs.overlay(this);
        if (overlayRow.toggle.isChecked() != ov) overlayRow.toggle.setChecked(ov);
        int n = 0;
        for (String[] it : Overlay.ITEMS) if (Prefs.overlayItem(this, it[0])) n++;
        overlayRow.value(ov ? Tx.s(R.string.home_on_n_of_m_items, n, Overlay.ITEMS.length) : Tx.s(R.string.game_off));
        resRow.value(resLabel());
        capRow.value(capLabel(Prefs.fpsCap(this)));
        upsRow.value(Tx.arr(R.array.arr_upscaler_modes)[GameUpscaler.mode(this)]);
        sharpRow.value(GameUpscaler.sharpName(GameUpscaler.sharp(this)));
        sharpRow.view.setVisibility(GameUpscaler.mode(this) == GameUpscaler.MODE_FSR ? View.VISIBLE : View.GONE);
        drsRow.value(Prefs.DRS_NAMES[Prefs.drs(this)]);
        lsfgRow.value(!BuildConfig.NX_LSFG ? Tx.s(R.string.home_not_in_this_build) : Lsfg.on(this) ? Tx.s(R.string.home_on_multiplier, Lsfg.multiplier(this)) + (Lsfg.find(this) == null ? Tx.s(R.string.home_lossless_dll_missing) : "") : Tx.s(R.string.game_off));
        GpuDriver.Info d = GpuDriver.selected(this);
        gpuRow.value(d == null ? Tx.s(R.string.common_system) : d.label());
        timeRow.value(TimeOfDay.label(this));
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) refreshSettings();   // back from a dialog (GPU driver, Folders, …)
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (GameReturn.back(this, i)) return;   // the app's icon while a game runs: back to the game, not Home (the launcher's start closed the game's screen above this singleTask root)
        fromIntent(i);
    }

    /** adb: am start -n dev.nxnative.android/.MainActivity --es game <game folder> [--es pack <pack folder>] [--es save <save folder>] [--ez run true] adds the game without
     *  the pickers (android/TESTING.md); --es add_game <folder or .nup> takes Add game's own path instead (gamePicked: all of a folder's games added, Prepare asked); save is the save folder Folders would pick for it (test runs of a play or release app keep away from the user's saves/<id>) */
    private void fromIntent(Intent i) {
        i = gated(i);
        if ((settingsPage || settingsGame != null) && (i.hasExtra("game") || i.hasExtra("add_game") || i.hasExtra("ondevice_dump") || i.hasExtra("ondevice_pause"))) page(false);   // these act on Home's cards
        Ondevice.fromIntent(this, i);   // --es ondevice_dump <dump folder> [--ez ondevice_start true] (Ondevice)
        GpuDriver.fromIntent(this, i);   // --es gpu_driver_zip <zip> / --es gpu_driver <name | system>
        if (!BuildConfig.NX_LIVE && i.getBooleanExtra("release_page_sample", false)) Updater.popup(this, ReleasePage.sample(this));   // test builds: --ez release_page_sample true shows the release page with a sample body
        PackUi.fromIntent(this, i);   // test app: --es pack_title <id> --es pack_export <folder> | --es pack_import <zip or folder>
        String fg = i.getStringExtra("frontend_game");   // FrontendLaunch: a game it could not start at once
        if (fg != null) { fromFrontend(new File(fg)); return; }
        String ag = i.getStringExtra("add_game");   // Add game's own path for a picked folder or .nup (gamePicked), without the picker
        if (ag != null) { if (!needAccess(new File(ag))) gamePicked(new File(ag)); return; }
        String game = i.getStringExtra("game");
        if (game == null) return;
        File dir = new File(game);   // a game folder or a .nup (NUP.md)
        String id;
        try { id = Pack.playableIdOf(dir); } catch (IOException e) { error(e.getMessage()); return; }   // (a Switch 2 Edition this APK runs beside a game it does not)
        String p = i.getStringExtra("pack");
        File pack = p != null ? new File(p) : Folders.packFor(this, dir, id);
        if (pack == null) { error(Tx.s(R.string.err_no_pack_found_next_to, id, game)); return; }
        if (!addWithPack(dir, id, pack)) return;
        String save = i.getStringExtra("save");
        if (save != null) Prefs.folder(this, "save_" + id, new File(save).getPath());
        if (i.getBooleanExtra("run", false)) for (Game g : games) if (GameList.samePath(g.gameDir, dir.getPath())) { launch(g); break; }
    }

    /** The intent without its adb control extras (AdbControl) while Prefs.adbControl refuses them: MainActivity is exported, so any app may send them (a toast and a
     *  log line say that some were dropped). Ported from Anka Native 0.2.2 (EddizEge, MIT; its issue #7) */
    private Intent gated(Intent i) {
        if (i == null || Prefs.adbControl(this)) return i;
        List<String> asked = AdbControl.asked(i::hasExtra);
        if (asked.isEmpty()) return i;
        android.util.Log.w("nxnative", "launcher intent from " + getReferrer() + " with " + asked + " ignored: ADB control is off (Settings › Help › ADB control)");
        Toast.makeText(this, Tx.s(R.string.home_a_command_from_another_app), Toast.LENGTH_LONG).show();
        Intent out = new Intent(i);
        for (String k : asked) out.removeExtra(k);
        return out;
    }

    /** A game a frontend sent that needs the user (FrontendLaunch.ready): its card's Play says what is missing and offers the fix; a new game takes the steps of a picked one */
    private void fromFrontend(File game) {
        if (!onHome()) page(false);
        refresh();   // FrontendLaunch may have added its card
        if (needAccess(game)) return;
        for (Game g : games) if (GameList.samePath(g.gameDir, game.getPath())) { launch(g); return; }
        gamePicked(game);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (gameBox != null && Game.load(this).size() != games.size()) refresh();   // a card FrontendLaunch added while Home was in the background
        onResumeViews();
        Ondevice.resume(this);
        Updater.resume(this);   // back from "Install unknown apps" with an update download waiting
        ExitWatch.resume(this);
        sampleTick();   // the shader check's cards (the line goes on by itself in the background)
        updateTick();   // a game that ended unexpectedly: "The game stopped unexpectedly", once (and every 2 s while a game runs and Home shows)
    }

    private void onResumeViews() {
        if (accessCard != null) accessCard.setVisibility(Environment.isExternalStorageManager() || !listNeedsAccess() ? View.GONE : View.VISIBLE);
        bananas.setRunning(true);
        refreshSettings();
        workTick();
    }

    /** The bananas stop while the screen is covered (a game, the self-test, settings) or off */
    @Override protected void onPause() {
        resumed = false;
        UsersUi.commitName(this);
        bananas.setRunning(false);
        Ondevice.pause(this);
        ExitWatch.pause();
        updateUi.removeCallbacks(updateRun);
        super.onPause();
    }

    // ---- A game card whose shaders are updated in the background (Ondevice.autoUpdate) while it stays playable: its state line carries the update's count and time left,
    // every 2 s while Home shows ("Ready · updating shaders 1,234 / 22,852 · about 12 min left"); when the job is done the cards are checked again ----
    private final java.util.Map<TextView, Game> updating = new java.util.HashMap<>();
    private final android.os.Handler updateUi = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable updateRun = this::updateTick;
    private String updateDone;
    // ---- The quick shader check (Ondevice.sampledKeep, SampleQueue): on Home its pack's tile shows the corner ring with the check's progress (updateTick; its words are the ring's
    // description), on the game's page a card with the bar and Skip (GameSettings.shaderCheckCard). Home is rebuilt when the line changes, a check starts, ends or is skipped ----
    private final java.util.Set<TextView> sampleSeen = new java.util.HashSet<>();   // tiles that showed the quick check of the shaders
    private String sampleSig = "";
    private final Runnable sampleRun = this::sampleTick;

    private void sampleTick() {
        updateUi.removeCallbacks(sampleRun);
        if (!resumed) return;
        if (!Ondevice.sampleSignature().equals(sampleSig)) { refresh(); return; }   // a check started, ended, was skipped, or the line moved: the tiles are built again
        if (Ondevice.sampleAny()) updateUi.postDelayed(sampleRun, 2000);
    }

    private void updateTick() {
        updateUi.removeCallbacks(updateRun);
        if (!resumed || updating.isEmpty()) return;
        boolean done = false;
        for (java.util.Map.Entry<TextView, Game> e : updating.entrySet()) {
            if (e.getKey().getParent() == null) continue;   // a card of an older list
            Game g = e.getValue();
            shaderProgress.remove(e.getKey());
            Job j = Ondevice.cardJob(this, g);
            if (j == null && Ondevice.sampling(new File(g.packDir))) {   // the quick check: its words and its bar on the tile's ring
                Ondevice.SampleView sv = Ondevice.sampleView(new File(g.packDir));
                state(e.getKey(), sv == null ? PrepareText.CHECKING_SHADERS() : sv.text, R.color.bn_leaf, ST_SOFT | ST_WORKING);
                if (sv != null && sv.bar > 0) shaderProgress.put(e.getKey(), sv.bar / 1000f);
                sampleSeen.add(e.getKey());
                continue;
            }
            if (j == null && sampleSeen.remove(e.getKey())) {   // the quick check ended: kept (the library is this version's: the card is checked again, "Ready") or not (the update goes on as before)
                if (Pack.libraryOf(new File(g.packDir)) != null) Toast.makeText(this, PrepareText.SHADERS_UP_TO_DATE(), Toast.LENGTH_SHORT).show();
                done = true;
                continue;
            }
            if (j == null) {   // no update runs for this card: say why instead of "updating" with no count
                String why = Ondevice.busy(this) ? Tx.s(R.string.home_ready_shader_update_waits)
                        : Ondevice.deltaShaders(new File(g.packDir), g.titleId) < 0 ? Tx.s(R.string.home_ready_update) : Tx.s(R.string.home_ready_shader_update_starting);
                state(e.getKey(), why, R.color.bn_leaf, ST_SOFT);
                continue;
            }
            String s = j.state();
            long shTotal = j.shadersTotal();
            if (shTotal > 0) shaderProgress.put(e.getKey(), (float) j.shadersDone() / shTotal); else shaderProgress.remove(e.getKey());
            if (s.equals("done")) {   // once per finished job: a card that still reads as updating after the check is not checked again and again
                String key = j.file.getPath() + ":" + j.file.lastModified();
                if (!key.equals(updateDone)) { updateDone = key; done = true; }
                continue;
            }
            if (j.currentStep().equals("rewrite")) { state(e.getKey(), s.equals("running") ? Tx.s(R.string.home_ready_updating_game_code) : Tx.s(R.string.home_ready_game_code_update_paused), R.color.bn_leaf, s.equals("running") ? ST_SOFT | ST_WORKING : ST_SOFT); continue; }   // (its progress once the rewrite step reports one)
            state(e.getKey(), ShaderLibs.updatingText(java.util.Locale.getDefault(), j.shadersDone(), j.shadersTotal(), Job.etaText(j.shadersEta()), !s.equals("running")), R.color.bn_leaf, s.equals("running") ? ST_SOFT | ST_WORKING : ST_SOFT);
        }
        if (done) { refresh(); return; }   // the new library is in: the cards' states are checked again ("Ready")
        updateUi.postDelayed(updateRun, 2000);
    }

    private void pickSound() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), PICK_SND);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** Home's games: one artwork tile per game in a row that scrolls sideways (also a progress tile while a game is being prepared); with none, a single welcome */
    void refresh() {
        updating.clear();
        sampleSig = Ondevice.sampleSignature();
        updateUi.removeCallbacks(sampleRun);
        if (Ondevice.sampleAny()) updateUi.postDelayed(sampleRun, 2000);
        games.clear();
        games.addAll(Game.load(this));
        if (gameBox == null) return;
        if (BuildConfig.DEBUG && Prefs.demoGames(this)) { refreshDemo(); return; }   // test builds only: sample tiles to judge the look
        if (!merged && games.size() > 1) {   // a list an older version left with two entries of one game: one card each (GameList.collapse; reads the dumps: off the main thread)
            merged = true;
            new Thread(() -> { if (Game.collapse(this)) runOnUiThread(() -> { if (!isFinishing()) refresh(); }); }, "nx-list-merge").start();
        }
        gameBox.removeAllViews();
        selectedTile = null;
        if (addFab != null) addFab.setVisibility(View.VISIBLE);   // Add game is always there: with no game it is the only way in (#381: it was hidden and the welcome card had no button)
        if (games.isEmpty() && !Ondevice.showing()) {   // no game: one line, the button has the focus
            gameBox.addView(emptyHint(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            noFocusTint(gameBox);
            if (addFab != null) addFab.post(() -> { if (addFab.isAttachedToWindow() && !addFab.isInTouchMode() && getCurrentFocus() == null) addFab.requestFocus(); });
            hintUpdate();
            return;
        }
        // One tile per game: while a game is being prepared its tile carries the ring (workTile) and a tap opens the job's sheet (Ondevice.sheet); a game not listed yet gets its tile first
        workTiles.clear();
        ViewGroup row = homeBox();
        boolean prep = Ondevice.showing(), placed = false;
        if (prep) for (Game g : games) placed |= Ondevice.shows(g);
        if (prep && !placed) { View t = prepTile(); addTile(row, t); workTile(t); }   // a game not listed yet: its tile first
        View keep = null;
        for (Game g : shownGames()) {
            View t = gameCard(g);
            addTile(row, t);
            if (prep && Ondevice.shows(g)) workTile(t);
            if (g.orderId().equals(keepSelected)) keep = layoutNow == HomeLayout.ROW ? t : (View) t.getParent();   // the grid's and the list's item holds the tile
        }
        startSelection(row);
        if (keep != null) {   // after a move the selection stays on the moved game and the row scrolls to it (once the tiles are laid out)
            final View moved = keep;
            if (!moved.isInTouchMode()) moved.requestFocus();
            selectTile(moved, false);
            gameBox.postDelayed(() -> { if (moved.isAttachedToWindow()) selectTile(moved, true); }, 250);
        }
        keepSelected = null;
        if (moveId != null && keep != null) { keep.setTranslationY(-dp(10)); keep.setScaleX(1.06f); keep.setScaleY(1.06f); keep.setTranslationZ(dp(12)); }   // the lifted game
        hintUpdate();
    }

    // ---- Reorder games: a long press (or Y on a controller) on a tile opens Move left / Move right; the order is kept as game ids (GameOrder, Prefs.gameOrder) ----
    private String keepSelected;

    /** Home's games in the player's order; a game not in it goes last */
    private List<Game> shownGames() { return GameOrder.sort(games, Prefs.gameOrder(this), Game::orderId); }

    /**
     * A game's options, from a long press on its tile (Hold X or Y on a controller): Move (a reorder mode, below), Set cover image (and Reset cover when it has one: CoverUi)
     * and Remove game (GameDelete). A tile being prepared keeps its job sheet on a long press (workTile)
     */
    private void optionsMenu(Game g) {
        if (moveId != null) return;
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (shownGames().size() > 1) { labels.add(Tx.s(R.string.home_move_game)); acts.add(() -> startMove(g)); }
        labels.add(Tx.s(R.string.game_cover_set)); acts.add(() -> CoverUi.start(this, g));
        if (CoverUi.has(this, g)) { labels.add(Tx.s(R.string.game_cover_reset)); acts.add(() -> CoverUi.reset(this, g)); }
        labels.add(Tx.s(R.string.home_delete_game_menu)); acts.add(() -> GameDelete.ask(this, g));
        new PadFocus.Builder(this).setTitle(g.name).setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run()).show();
    }

    // ---- Move: a reorder mode. The game is lifted; D-pad (the grid: all four directions) or a tap on another game's place moves it, A or a tap on it puts it down, B cancels ----
    private String moveId;
    private List<String> moveOrig;

    private void startMove(Game g) {
        moveId = g.orderId();
        moveOrig = new ArrayList<>();
        for (Game x : shownGames()) moveOrig.add(x.orderId());
        keepSelected = moveId;
        refresh();
        hintUpdate();
    }

    /** Moves the lifted game by delta places (clamped at the ends) */
    private void stepMove(int delta) {
        List<String> ids = new ArrayList<>();
        for (Game x : shownGames()) ids.add(x.orderId());
        List<String> now = GameOrder.move(ids, moveId, delta);
        if (now.equals(ids)) return;
        Prefs.gameOrder(this, GameOrder.write(now));
        keepSelected = moveId;
        refresh();
    }

    private void endMove(boolean keep) {
        if (moveId == null) return;
        if (!keep) Prefs.gameOrder(this, GameOrder.write(moveOrig));
        keepSelected = moveId;
        moveId = null;
        moveOrig = null;
        refresh();
        hintUpdate();
    }

    /** A tap in move mode: on the lifted game puts it down, on another moves the lifted one to that place */
    private void tapMove(Game g) {
        if (g.orderId().equals(moveId)) { endMove(true); return; }
        List<Game> shown = shownGames();
        int from = -1, to = -1;
        for (int k = 0; k < shown.size(); k++) { if (shown.get(k).orderId().equals(moveId)) from = k; if (shown.get(k).orderId().equals(g.orderId())) to = k; }
        if (from >= 0 && to >= 0) stepMove(to - from);
    }

    /** A key while a game is lifted: true when it was used (the others are swallowed so nothing else on Home moves) */
    private boolean moveKey(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (PadFocus.isBack(k) || k == KeyEvent.KEYCODE_BACK) { if (!down) endMove(false); return true; }
        if (k == KeyEvent.KEYCODE_BUTTON_A || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) { if (down && e.getRepeatCount() == 0) endMove(true); return true; }
        boolean rtl = layoutNow == HomeLayout.ROW && getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int cols = layoutNow == HomeLayout.GRID ? HomeLayout.columns(gameBox) : 1;
        int d = 0;
        if (k == KeyEvent.KEYCODE_DPAD_LEFT) d = rtl ? 1 : -1;
        else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) d = rtl ? -1 : 1;
        else if (k == KeyEvent.KEYCODE_DPAD_UP) d = layoutNow == HomeLayout.GRID ? -cols : layoutNow == HomeLayout.LIST ? -1 : 0;
        else if (k == KeyEvent.KEYCODE_DPAD_DOWN) d = layoutNow == HomeLayout.GRID ? cols : layoutNow == HomeLayout.LIST ? 1 : 0;
        else return false;
        if (down && d != 0) stepMove(d);
        return true;
    }

    // ---- The hint bar: the buttons that work now (HintBar), at the bottom of every page ----
    private HintBar hint;

    void hintUpdate() {
        if (hint == null) return;
        boolean pad = HintBar.hasPad(this);
        if (moveId != null) {
            if (pad) hint.set(new String[] {"\u2194", Tx.s(R.string.hint_move)}, new String[] {"A", Tx.s(R.string.hint_place)}, new String[] {"B", Tx.s(R.string.hint_cancel)});
            else hint.set(new String[] {null, Tx.s(R.string.hint_tap_move)});
        } else if (onHome()) {
            View f = getCurrentFocus();
            boolean onTile = f != null && f.getTag() instanceof TileInfo;
            if (games.isEmpty()) { if (pad) hint.set(new String[] {"A", Tx.s(R.string.home_add_game)}); else hint.set(); }
            else if (pad && onTile) hint.set(new String[] {"A", Tx.s(R.string.hint_open)}, new String[] {"X", Tx.s(R.string.hint_options_hold)});
            else if (!pad) hint.set(new String[] {null, Tx.s(R.string.hint_hold_for_options)});
            else hint.set();
        } else if (pad) hint.set(new String[] {"A", Tx.s(R.string.hint_select)}, new String[] {"B", Tx.s(R.string.hint_back)});
        else hint.set();
    }

    // ---- A game being prepared: its tile dims and shows a ring with the job's progress and a line on the step, as an install on an icon; a tap opens the job's sheet ----
    private final java.util.List<TileInfo> workTiles = new java.util.ArrayList<>();
    private final android.os.Handler workUi = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable workRun = this::workTick;

    /** The tile of a game that is not listed yet, made from the job (its name, its picture from the .nup when it has one) */
    private View prepTile() {
        TextView st = Ui.status(this, "", R.color.bn_text_2);
        st.setTag(ST_HIDDEN);
        FrameLayout c = artTile(Ondevice.jobName(), st, new LinearLayout(this), v -> Ondevice.sheet(this));
        TextView tile = (TextView) c.getChildAt(0);
        File dump = Ondevice.jobDump();
        if (dump != null) GameMeta.load(dump, m -> { if (m.icon != null) Ui.tileIcon(tile, m.icon); });
        return c;
    }

    private void workTile(View c) {
        TileInfo ti = (TileInfo) c.getTag();
        ti.work = true;
        ti.ov.setTag(Boolean.TRUE);
        View.OnClickListener sheet = v -> Ondevice.sheet(this);
        c.setOnClickListener(sheet);
        c.setOnLongClickListener(v -> { sheet.onClick(v); return true; });
        ti.ov.setBackground(Ui.round(0xB3000000, dp(22), 0, 0));
        ti.ov.setVisibility(View.VISIBLE);
        ti.ring.setVisibility(View.VISIBLE);
        ti.pb.setVisibility(View.GONE);
        ti.badge.setVisibility(View.GONE);
        ti.ot.setMaxLines(3);
        ti.ot.setEllipsize(android.text.TextUtils.TruncateAt.END);
        ti.ot.setPadding(dp(12), dp(8), dp(12), 0);
        workTiles.add(ti);
        workTick();
    }

    private void workTick() {
        workUi.removeCallbacks(workRun);
        if (!resumed || workTiles.isEmpty()) return;
        boolean demo = BuildConfig.DEBUG && Prefs.demoGames(this);   // the sample tiles: a made-up progress
        float p = demo ? (System.currentTimeMillis() / 150 % 100) / 100f : Ondevice.progress();
        String line = demo ? Tx.s(R.string.common_preparing) : Ondevice.progressLine();
        int state = demo ? Ondevice.TILE_RUNNING : Ondevice.tileState();
        int detail = line.indexOf(": ");   // the tile names the stage only; its detail (the files, the counts) is in the job's sheet and the log
        if (detail > 0) line = line.substring(0, detail);
        for (TileInfo ti : workTiles) {   // the job's state on the ring: running (the arc), waiting (it goes round), paused (the arc stays, II), failed (full, red, !), done
            ti.ring.set(state == Ondevice.TILE_QUEUED ? -1f : state == Ondevice.TILE_FAILED || state == Ondevice.TILE_DONE ? 1f : p);
            ti.ring.look(state == Ondevice.TILE_FAILED ? Ui.col(this, R.color.bn_danger) : state == Ondevice.TILE_PAUSED ? 0xFFFFFFFF : ti.ring.baseColor(),
                    state == Ondevice.TILE_FAILED ? "!" : state == Ondevice.TILE_PAUSED ? "II" : null);
            ti.workLine = line;
            HomeLayout.caption(ti);
            ti.ot.setText(line + "\n" + Tx.s(R.string.home_tile_details));   // a hint that the tile opens the job's sheet
            ti.ring.setContentDescription(null);
        }
        workUi.postDelayed(workRun, 700);
    }

    /** A controller or keyboard starts on the first game's tile (nothing happens in touch mode, where tiles take no focus); the first game is the selected one either way */
    private void startSelection(ViewGroup row) {
        if (gameBox != null) noFocusTint(gameBox);   // the tiles were made after the page was
        for (int k = 0; k < row.getChildCount(); k++)
            if (row.getChildAt(k).getTag() instanceof TileInfo) {
                View t = row.getChildAt(k);
                selectTile(t, false);
                if (getCurrentFocus() == null) t.requestFocus();
                return;
            }
    }

    /** Home's row: a sideways scroll over a line of tiles, in gameBox; returns the line. The tiles are squares as high as the row leaves room for (the selected one grows) */
    private LinearLayout tileRow() {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setClipChildren(false);
        hs.setOnTouchListener(popListener(hs));   // a tap on the empty part of the row pops a banana
        hs.setHorizontalFadingEdgeEnabled(true);   // a soft edge where more tiles are off screen
        hs.setFadingEdgeLength(dp(40));
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // A tall window (a phone upright): one big tile in the middle, the next peeking in, the row centred in the space above the bottom bar (no text under the tiles; the tile's content description carries the name), and a swipe settles on a tile
        boolean tall = getResources().getConfiguration().screenWidthDp < 600 && getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        final int tallSide = dp(Math.round(getResources().getConfiguration().screenWidthDp * 0.56f));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        int edge = tall ? (getResources().getDisplayMetrics().widthPixels - tallSide) / 2 : dp(14);
        row.setPadding(tall ? Math.min(dp(14), edge) : dp(14), 0, edge, 0);
        hs.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (tall) {
            gameBox.setGravity(Gravity.CENTER_VERTICAL);
            gameBox.setPadding(0, 0, 0, dp(64));
            gameBox.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(tallSide * 1.3f)));
            final Runnable[] snap = new Runnable[1];
            snap[0] = () -> {   // the tile nearest the middle is the selected one, and the row settles on it
                int mid = hs.getScrollX() + hs.getWidth() / 2, best = Integer.MAX_VALUE, to = hs.getScrollX();
                View pick = null;
                for (int k = 0; k < row.getChildCount(); k++) {
                    View c = row.getChildAt(k);
                    if (!(c.getTag() instanceof TileInfo)) continue;
                    int cc = c.getLeft() + c.getWidth() / 2, d = Math.abs(cc - mid);
                    if (d < best) { best = d; pick = c; to = cc - hs.getWidth() / 2; }
                }
                if (pick == null) return;
                selectTile(pick, false);
                if (Math.abs(to - hs.getScrollX()) > dp(2)) hs.smoothScrollTo(Math.max(0, to), 0);
            };
            hs.setOnScrollChangeListener((v, x, y, ox, oy) -> { hs.removeCallbacks(snap[0]); hs.postDelayed(snap[0], 140); });
        } else gameBox.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        hs.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t, side = tall ? tallSide : Math.min(dp(180), Math.round(h * 0.68f));   // room above and below for the selected tile's growth
            for (int k = 0; k < row.getChildCount(); k++) {
                View c = row.getChildAt(k);
                if (!(c.getTag() instanceof TileInfo)) continue;
                ViewGroup.LayoutParams p = c.getLayoutParams();
                if (p.width != side || p.height != side) { p.width = side; p.height = side; c.setLayoutParams(p); }
            }
            int pad = Math.max(dp(14), (r - l - side) / 2);   // room at both ends so the first and the last tile can stand in the middle
            if (row.getPaddingLeft() != pad || row.getPaddingRight() != pad) row.setPadding(pad, 0, pad, 0);
        });
        return row;
    }

    /** Add game: a round banana-yellow button (the plus of the dock's yellow), floating at the bottom right of Home */
    private View addButton() {
        ImageView b = new ImageView(this);
        b.setImageDrawable(Ui.icon(this, R.drawable.ic_add, R.color.bn_on_banana));
        b.setPadding(dp(13), dp(13), dp(13), dp(13));
        b.setBackground(Ui.round(Ui.col(this, R.color.bn_banana), dp(999), 0, 0));
        b.setForeground(ring(999, false));
        b.setElevation(dp(4));
        b.setContentDescription(Tx.s(R.string.home_add_game));
        b.setTooltipText(Tx.s(R.string.home_add_game));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(v -> addGame());
        return b;
    }

    private int layoutNow = HomeLayout.ROW;   // the layout Home was built in (Prefs.homeLayout, read at each refresh)

    /** The box Home's tiles go into, for the chosen layout: the sideways row, or the grid or the list (HomeLayout) */
    private ViewGroup homeBox() {
        layoutNow = HomeLayout.mode(this);
        return layoutNow == HomeLayout.ROW ? tileRow() : HomeLayout.container(this, gameBox, layoutNow);
    }

    private void addTile(ViewGroup row, View v) {
        if (layoutNow != HomeLayout.ROW) { HomeLayout.add(this, row, layoutNow, (FrameLayout) v); return; }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(150), dp(150));
        if (row.getChildCount() > 0) lp.setMarginStart(dp(16));
        row.addView(v, lp);
    }

    /**
     * A game's artwork tile: its picture (or its letter on a colour) over the whole tile, rounded, no text. The name and state are in the dock's label (TileInfo);
     * a tap opens the game's page (GameSettings: settings, saves, storage, Play in its header), as does a long press. The view that gets the state (st) is kept
     * out of sight, with the "Why?" badge the check adds: Home shows neither
     */
    private FrameLayout artTile(String name, TextView st, LinearLayout meta, View.OnClickListener open) {
        FrameLayout c = new FrameLayout(this);
        TextView tile = Ui.tile(this, name, 64);
        tile.setTextSize(48);
        c.addView(tile, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout ov = new LinearLayout(this);   // the state over the picture (overlay)
        ov.setOrientation(LinearLayout.VERTICAL);
        ov.setGravity(Gravity.CENTER);
        ov.setVisibility(View.GONE);
        ProgressRing ring = new ProgressRing(this, Ui.col(this, R.color.bn_banana), true);   // while the game is being prepared: its progress on the dimmed picture, as an install on an icon
        ring.setVisibility(View.GONE);
        ov.addView(ring, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView ot = Ui.text(this, "", 12, R.color.bn_on_leaf, 700);
        ot.setTextColor(android.graphics.Color.WHITE);
        ot.setGravity(Gravity.CENTER);
        ot.setPadding(dp(10), 0, dp(10), 0);
        ov.addView(ot);
        android.widget.ProgressBar pb = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(true);
        pb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.col(this, R.color.bn_banana)));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        pl.setMargins(dp(14), dp(8), dp(14), 0);
        ov.addView(pb, pl);
        c.addView(ov, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ProgressRing badge = new ProgressRing(this, Ui.col(this, R.color.bn_banana), false);   // a game that stays playable while something is checked or updated: a small ring in the corner
        badge.setBackground(Ui.round(0x99000000, dp(999), 0, 0));
        badge.setPadding(dp(4), dp(4), dp(4), dp(4));
        badge.setVisibility(View.GONE);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP | Gravity.END);
        bl.setMargins(dp(10), dp(10), dp(10), dp(10));
        c.addView(badge, bl);
        meta.setVisibility(View.GONE);
        meta.addView(st);
        c.addView(meta);
        TileInfo info = new TileInfo(name, st, open);
        info.ov = ov; info.ot = ot; info.pb = pb; info.badge = badge; info.ring = ring;
        ov.setTag(null);
        c.setTag(info);
        c.setForeground(ring(22, true));
        c.setFocusable(true);
        c.setClickable(true);
        c.setContentDescription(name);
        c.setOnClickListener(v -> { if (moveId == null && ov.getVisibility() == View.VISIBLE && st.hasOnClickListeners()) st.performClick(); else open.onClick(v); });   // a tile with something to do (Prepare, Fix the save folder) does it; a long press opens the page
        c.setOnLongClickListener(v -> { open.onClick(v); return true; });   // a game's tile: gameCard makes this the reorder menu
        c.setOnTouchListener((v, e) -> { if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) selectTile(c, false); return false; });   // a touch selects it (it grows) before the tap
        st.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence a, int b, int d, int e) { }
            @Override public void onTextChanged(CharSequence a, int b, int d, int e) { }
            @Override public void afterTextChanged(android.text.Editable e) { c.post(() -> overlay(ov, ot, pb, badge, st)); }
        });
        overlay(ov, ot, pb, badge, st);
        return c;
    }

    private View gameCard(Game g) {
        TextView st = Ui.status(this, Tx.s(R.string.home_checking), R.color.bn_text_2);
        st.setTag(ST_HIDDEN);
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        FrameLayout c = artTile(g.name, st, meta, v -> { if (moveId != null) tapMove(g); else manage(g); });   // in move mode a tap places the lifted game
        c.setOnLongClickListener(v -> { optionsMenu(g); return true; });   // the game's options (workTile puts the job's sheet back on the tile being prepared)
        TextView tile = (TextView) c.getChildAt(0);
        boolean ownCover = Covers.show(tile, g);   // the player's own cover wins over the game's icon
        GameMeta.loadControl(new File(g.gameDir), Edition.control(this, g), m -> {
            if (m.icon != null && !ownCover) Ui.tileIcon(tile, m.icon);
            TileInfo ti = (TileInfo) c.getTag();
            ti.version = m.version;
            HomeLayout.caption(ti);   // the grid's and the list's second line
        });   // with the edition switch on, program 1's icon
        if (Profile.of(g.titleId) == null) {   // no profile in this APK: it cannot run here (Profile.missing says why); the page still has its folders, saves and Remove
            state(st, Tx.s(R.string.common_not_in_this_app_version), R.color.bn_danger, 0);
            return c;
        }
        // The action a state needs (check sets its label and click); it is never shown as a button: the state chip, in the dock's label, runs it
        Ui.Action act = Ui.action(this, 0, "", Ui.Kind.PRIMARY, v -> launch(g));
        check(g, st, act, meta);
        return c;
    }

    /** No game yet: one line over the plain page; the round Add game button (always on Home) is how a game comes in */
    private View emptyHint() {
        TextView t = Ui.small(this, Tx.s(R.string.home_add_a_game_to_start));
        t.setGravity(Gravity.CENTER);
        t.setTextSize(15);
        return t;
    }

    // ---- Sample games (test builds): what Home looks like with games, without any ----

    private static final String[][] DEMO = {
            {"Mario Kart World", "Ready", "#2B6FD1"}, {"Donkey Kong Bananza", "Ready", "#E8A317"}, {"Zelda: Ocarina of Time", "Needs preparing", "#2E9D5B"},
            {"Pok\u00e9mon Pokopia", "Ready \u00b7 update", "#C0392B"}, {"Star Fox", "Preparing\u2026", "#6C5CE7"}, {"Mario Tennis Fever", "Not in this app version", "#8A5A2B"},
            {"Splatoon Raiders", "Ready \u00b7 updating shaders", "#D35400"}, {"Metroid Prime 4", "Ready \u00b7 checking shaders", "#16A085"}};

    private void refreshDemo() {
        gameBox.removeAllViews();
        selectedTile = null;
        workTiles.clear();
        ViewGroup row = homeBox();
        for (String[] d : DEMO) {
            boolean bad = d[1].startsWith("Not"), ready = d[1].startsWith("Ready");
            TextView st = Ui.status(this, d[1], bad ? R.color.bn_danger : ready && !d[1].contains("update") ? R.color.bn_leaf : R.color.bn_accent);
            st.setTag(d[1].contains("ing shaders") ? ST_SOFT | ST_WORKING : ready && !d[1].contains("update") ? ST_HIDDEN : ready ? ST_SOFT : 0);   // sample tiles: the kind of state, as the real ones
            if (d[1].contains("updating shaders")) shaderProgress.put(st, 0.4f);
            FrameLayout c = artTile(d[0], st, new LinearLayout(this), v -> gameSettings(new Game(d[0].startsWith("Donkey") ? "040074A01BF12000" : "0000000000000000", d[0], "/sdcard/Download/demo/" + d[0], "/sdcard/Download/demo/pack")));   // the sample opens a real game page (the first sample has the APK's profile, so every tab shows)
            TextView tile = (TextView) c.getChildAt(0);
            tile.setBackground(Ui.round(android.graphics.Color.parseColor(d[2]), dp(22), 0, 0));
            tile.setTextColor(android.graphics.Color.WHITE);
            addTile(row, c);
            if (d[1].startsWith("Prepar")) workTile(c);   // the sample of a game being prepared: a ring that goes round
        }
        startSelection(row);
    }

    /** Why a game cannot start as it is, in a few words (unfinished or busy pack code, another game build, no current shader library), and whether Prepare fixes it */
    private static final class Problem {
        final String label;
        final boolean prepare;   // Prepare (on the phone, from the dump) makes what is missing
        boolean working;       // something is being made now: the tile shows its moving bar
        boolean shadersOnly;   // only the shader library is missing
        boolean playable;      // ... and an older library of the same ABI plays while the update makes it (Pack.playableLibrary)
        boolean legacy;        // ... or only one made before versioned updates, played with a warning (Pack.legacyLibrary)
        boolean onDemand;      // no library to play on at all, but the shader layer covers the game: Play starts it and the shaders are built as it runs (Pack.shadersOnDemand); Prepare shaders is optional
        boolean anyway;        // Play still starts it after a warning (an untested game version, another version's RomFS: MainActivity.launch)
        boolean resume;        // a Prepare beside the dump stopped part way: the button continues it
        boolean repick;        // the game's file is gone (GameFile): the button picks it again
        File saveGone;         // the picked save folder is gone: the button asks (the app's save, or pick the folder again)
        ShaderState.Off off = ShaderState.Off.NONE;   // "Shaders needed" with no library to play on: why the shader layer does not let it play now (the switch, a line, the folder)
        String reason = "";    // one short line under the state: why, and what the button does
        String detail = "";    // the technical part (what was compared: build IDs, the pack's error, its folder), in the card's Details and the log

        Problem(String label, boolean prepare) { this.label = label; this.prepare = prepare; }
    }

    /** The card's short state when the game cannot start as it is; null when it can */
    private Problem problem(Game g) {
        File pd = new File(g.packDir), gd = new File(g.gameDir);
        File saveGone = Folders.goneSave(this, g);
        if (saveGone != null) {   // a picked save folder that is gone: the start would make a new empty save there, so it asks first
            Problem s = new Problem(Tx.s(R.string.prepare_save_folder_gone), false);
            s.saveGone = saveGone;
            s.reason = GameFile.saveGoneText(saveGone.getName());
            s.detail = saveGone.getPath();
            return s;
        }
        String gone = gone(g);
        if (gone != null) {   // deleted, moved, renamed (or, for a .nup used where it is, its permission taken back): nothing else can be told
            Problem s = new Problem(Tx.s(R.string.home_game_file_gone), false);
            s.repick = true;
            s.reason = GameFile.goneText(gd.getName());
            s.detail = gone;
            return s;
        }
        boolean dump = Ondevice.isDump(gd);
        Pack p;
        String readError = null;
        try { p = Pack.read(pd); } catch (IOException e) { p = null; readError = e.getMessage(); }
        String unfinished = p == null ? null : p.unfinishedModule();
        if (p == null || unfinished != null) {
            // Why it needs preparing, so the card does not only say "Needs preparing": no pack yet, one that cannot be read, code left unfinished, or a Prepare that
            // stopped part way (its job beside the dump: the button continues it rather than asking to start over)
            Job j = stoppedJob(gd, g.titleId);
            Problem s = new Problem(j != null ? Tx.s(R.string.home_preparing_not_finished) : dump ? Tx.s(R.string.home_needs_preparing) : p == null ? Tx.s(R.string.home_no_pack) : Tx.s(R.string.home_pack_unfinished), dump || j != null);
            s.resume = j != null;
            s.reason = dump || j != null ? PrepareText.needsPreparing(pd.isDirectory() && readError != null && new File(pd, "pack.tsv").isFile(), unfinished != null, j != null)
                    : Tx.s(R.string.home_this_game_has_no_pack_3);
            s.detail = (j != null ? j.statusText(false) + "\n\n" : "") + (readError != null ? readError : unfinished + ".android.key is missing: the rewrite of " + unfinished + " did not finish.")
                    + "\nPack: " + pd + (j != null ? "\n" + j.errorDetail() : "");
            return s;
        }
        if (PackCheck.rewriting(this, pd, gd, p.titleId) != null) { Problem w = new Problem(Tx.s(R.string.home_preparing), false); w.working = true; return w; }
        BuildCheck.Result b = BuildCheck.build(p, gd);
        if (!b.ok()) {
            android.util.Log.w("nxnative", g.titleId + " " + g.gameDir + " with pack " + g.packDir + ": " + b.label() + ": " + b.detail);
            Problem s = new Problem(b.label(), dump && (b.kind == BuildCheck.Kind.OTHER_BUILD || b.kind == BuildCheck.Kind.MISSING));   // Prepare makes a pack of the dump's build; it cannot fix unreadable files, an unsupported build or another version's data
            s.anyway = b.kind == BuildCheck.Kind.UNSUPPORTED || b.kind == BuildCheck.Kind.ROMFS;
            s.reason = s.anyway ? Tx.s(R.string.home_unsupported_version_parts_of_the) : s.prepare ? Tx.s(R.string.home_the_pack_is_for_another) : "";
            s.detail = b.message + "\n\n" + b.detail + "\nPack: " + pd;
            return s;
        }
        if (Pack.libraryProblem(pd, g.titleId) != null && Pack.shadersOnDemand(pd, g.titleId, Prefs.layerEnv(this, g.titleId))) {
            // Never blocks Play: the layer builds what the game asks for while it runs (LayerWatch), and the game page offers Prepare shaders for the whole set (GameSettings)
            Problem s = new Problem(Tx.s(R.string.home_ready), false);
            s.onDemand = true;
            s.shadersOnly = true;
            s.reason = Tx.s(R.string.prepare_no_shaders_yet) + " " + Tx.s(dump ? R.string.prepare_build_all_makes_set : R.string.prepare_import_library_makes_set);
            s.detail = Pack.libraryProblem(pd, g.titleId);
            return s;
        }
        if (Pack.libraryProblem(pd, g.titleId) != null) {
            Problem s = new Problem(Tx.s(R.string.game_shaders_needed), dump);
            s.shadersOnly = true;
            s.playable = Pack.playableLibrary(pd, g.titleId) != null;
            s.legacy = !s.playable && Pack.legacyLibrary(pd, g.titleId) != null;
            if (!s.playable && !s.legacy) {
                // why the game cannot build them as it plays (the switch, a line of its own, no folder to build in, not a Switch 2 title) and what the player can do: ShaderState
                s.off = GameSettings.layerOff(this, pd, g.titleId);
                s.reason = GameSettings.neededText(pd, g.titleId, s.off, dump);
                s.detail = Pack.libraryProblem(pd, g.titleId);
            }
            return s;
        }
        return null;
    }

    /** Why a game's file or folder is gone, for Details; null when it is there (or cannot be told: no All files access for a game read by its path) */
    String gone(Game g) {
        File gd = new File(g.gameDir);
        if (PickedNup.is(this, g.gameDir)) return PickedNup.problem(this, gd);
        boolean readable = Environment.isExternalStorageManager() || CopyPlan.inApp(AppStorage.root(this), g.gameDir);
        return GameFile.gone(gd.exists(), readable) ? Tx.s(R.string.err_path_is_not_there, gd.getPath()) : null;
    }

    /** A Prepare of this dump that stopped part way (its job.json beside the dump, not done), else null */
    private static Job stoppedJob(File dump, String titleId) {
        File f = Job.fileOf(DumpCheck.workOf(dump, titleId));
        try {
            Job j = f.isFile() ? Job.read(f) : null;
            return j != null && !"done".equals(j.state()) && !j.state().isEmpty() ? j : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** The card's state, checked off the main thread (NSO headers, the stamp, and for a ready pack the rewriter's key, which hashes the modules) */
    private void check(Game card, TextView st, Ui.Action play, LinearLayout info) {
        new Thread(() -> {
            Game g = Folders.repack(this, card);   // a remembered pack folder that is gone: the usual search instead (app storage first)
            // An APK update whose shaders did not change: the pack's older library becomes this version's by its header (Pack.restamp), before anything else is decided
            String restamped = Pack.restamp(new File(g.packDir), g.titleId);
            if (restamped != null) android.util.Log.i("nxnative", g.titleId + ": shader library v-" + restamped + " taken over as v-" + BuildConfig.NX_DMSH_VK_CHAIN + " (restamp)");
            Problem problem = problem(g);
            Boolean ok = null;
            if (problem == null || problem.onDemand) try { ok = PackCheck.code(this, Pack.read(new File(g.packDir)), new File(g.gameDir)); } catch (IOException e) { ok = null; }
            Boolean code = ok;
            boolean own = Boolean.FALSE.equals(ok) && Ondevice.ownPack(new File(g.gameDir), new File(g.packDir), g.titleId);   // the app's own pack, its code from older tools
            runOnUiThread(() -> {
                if (info.getParent() == null) return;
                // An APK update's new shaders: made in the background with no question when the pack's older library is one the delta manifest knows (Ondevice.autoUpdate)
                if (problem != null && problem.shadersOnly && problem.prepare) Ondevice.autoUpdate(this, new File(g.gameDir), new File(g.packDir), g.titleId);
                if (problem == null) state(st, Tx.s(R.string.home_ready), R.color.bn_leaf, ST_HIDDEN);
                else if (problem.onDemand) state(st, problem.label, R.color.bn_leaf, ST_HIDDEN);   // Play starts it; the layer builds the shaders as it runs
                else if (problem.playable && !problem.prepare) state(st, PrepareText.READY_OLDER(), R.color.bn_leaf, ST_SOFT);   // not a dump: nothing is updated on the phone (the page says where new ones come from)
                else if (problem.playable && Ondevice.sampling(new File(g.packDir))) { state(st, PrepareText.CHECKING_SHADERS(), R.color.bn_leaf, ST_SOFT | ST_WORKING); updating.put(st, g); updateTick(); updateUi.removeCallbacks(sampleRun); updateUi.postDelayed(sampleRun, 2000); }   // the quick check of the older library (Ondevice.sampledKeep): the card follows it
                else if (problem.playable && Pack.shaderLayerFills(new File(g.packDir), g.titleId, Prefs.layerEnv(this, g.titleId))) state(st, Tx.s(R.string.home_ready), R.color.bn_leaf, ST_HIDDEN);   // the layer builds what the older library lacks: no "update" to nag about
                else if (problem.playable && Pack.deltaShaders(new File(g.packDir), g.titleId) < 0) state(st, Tx.s(R.string.home_ready_update), R.color.bn_accent, ST_SOFT);   // an optional shader update: the game's page offers it (GameSettings.shaderUpdateCard)
                else if (problem.playable) { state(st, Tx.s(R.string.home_ready_updating_shaders), R.color.bn_leaf, ST_SOFT | ST_WORKING); updating.put(st, g); updateTick(); }   // the count follows (updateTick)
                else if (problem.legacy) state(st, Tx.s(R.string.home_ready_shaders_need_one_update), R.color.bn_accent, ST_SOFT);
                else state(st, problem.label, problem.prepare || problem.anyway ? R.color.bn_accent : R.color.bn_danger, problem.working ? ST_WORKING : 0);
                if (problem != null && !problem.playable && !problem.legacy && !problem.onDemand && (!problem.reason.isEmpty() || !problem.detail.isEmpty())) {   // why, and what the chip does, behind a badge
                    TextView why = Ui.warnChip(this, Tx.s(R.string.home_why), problem.label, problem.reason);
                    why.setOnClickListener(v -> Ui.explain(this, problem.label, problem.reason.isEmpty() ? problem.label : problem.reason, problem.detail).setPositiveButton(android.R.string.ok, null).show());
                    LinearLayout.LayoutParams wl = Ui.wrap();
                    wl.topMargin = dp(6);
                    info.addView(why, wl);
                }
                // an untested build: the tile opens the page; Play there asks first (launch)
                if (problem != null && problem.saveGone != null) {
                    play.setText(Tx.s(R.string.home_fix_the_save_folder));
                    play.setOnClickListener(v -> Folders.saveGoneDialog(this, g, problem.saveGone));
                    st.setOnClickListener(v -> play.performClick());   // the state chip does what the state needs
                    st.setContentDescription(play.label.getText() + ": " + st.getText());
                }
                if (problem != null && problem.repick) {
                    play.setText(Tx.s(R.string.home_pick_it_again));
                    play.setIcon(R.drawable.ic_add);
                    play.setOnClickListener(v -> relink(g));
                    st.setOnClickListener(v -> play.performClick());   // the state chip does what the state needs
                    st.setContentDescription(play.label.getText() + ": " + st.getText());
                }
                if (problem != null && problem.legacy) {   // Play stays Play; a chip says why the shaders want one more update
                    TextView n = Ui.warnChip(this, Tx.s(R.string.home_shaders_need_one_update), Tx.s(R.string.home_shaders_need_one_update), ShaderLibs.legacyWarning(problem.prepare));
                    LinearLayout.LayoutParams wl = Ui.wrap();
                    wl.topMargin = dp(6);
                    info.addView(n, wl);
                }
                if (problem != null && problem.prepare && !problem.playable && !problem.legacy) {   // the big button prepares (Ondevice asks first and says what it will make)
                    play.setText(problem.resume ? Tx.s(R.string.home_continue_preparing) : problem.shadersOnly ? Tx.s(R.string.common_prepare_shaders) : Tx.s(R.string.common_prepare));
                    play.setIcon(R.drawable.ic_build);
                    play.setOnClickListener(v -> { if (!needAccess(new File(g.gameDir))) Ondevice.prepare(this, new File(g.gameDir), false); });
                    st.setOnClickListener(v -> play.performClick());   // the state chip does what the state needs
                    st.setContentDescription(play.label.getText() + ": " + st.getText());
                }
                if (!Boolean.FALSE.equals(code)) return;
                // The app's own pack whose code older tools made (an APK update changed the rewriter or the profile's patches): updated in the background, no question;
                // the card says so like a shader update. Play stays on: the new code is swapped in only when the rewrite is complete
                if (own) {
                    if (Ondevice.autoCodeUpdate(this, new File(g.gameDir), new File(g.packDir))) {
                        state(st, Tx.s(R.string.home_ready_updating_game_code), R.color.bn_leaf, ST_SOFT | ST_WORKING);
                        updating.put(st, g); updateTick();
                    } else {
                        boolean stopped = Ondevice.codeUpdateTried(new File(g.gameDir)) && !Ondevice.busy(this);   // started this run and the code is still not new (stopped, failed): nothing starts it again by itself
                        state(st, Tx.s(stopped ? R.string.home_ready_game_code_update_stopped : R.string.home_ready_game_code_update_waits), stopped ? R.color.bn_accent : R.color.bn_leaf, ST_SOFT);
                        // a tap runs Prepare again either way (it asks first): the chip may wait for a game or a job that is stuck, and a chip that does nothing when tapped is the report this answers
                        st.setOnClickListener(v -> { if (!needAccess(new File(g.gameDir))) Ondevice.prepare(this, new File(g.gameDir), true); });
                    }
                    return;
                }
                // A pack whose code other tools made shows no chip: it usually runs; the game page offers Prepare again
            });
        }, "nx-pack-check").start();
    }

    /** The game's Manage sheet, also from its progress card while it is being prepared (Ondevice) */
    void manageGame(Game g) { manage(g); }

    /** The optional shader update from the game's page: Home, then Prepare (Ondevice asks first and says what it will make) */
    void updateShaders(Game g) { page(false); Ondevice.prepare(this, new File(g.gameDir), true); }

    /** The optional "Prepare shaders" of a game that starts with no library (GameSettings): Home, then Prepare shaders for the whole set (Ondevice asks first and says what it will make) */
    void prepareShaders(Game g) { page(false); Ondevice.prepare(this, new File(g.gameDir), false); }

    /** A game's page (a tap or long press on its tile; GameSettings): its settings, saves and storage under tabs, Play in its header */
    private void manage(Game g) { GameSettings.open(this, g); }

    /**
     * A game's page, its Saves tab (save backups in &lt;Switch2&gt;/save-backups/&lt;id&gt;/, verified; a restore backs up the current save first: SaveUi, SaveStore) or its
     * Storage tab: Prepare again from a dump, the pack as one .zip out and in (PackUi, PackZip: an import goes into the app's packs/&lt;id&gt; and is checked before
     * it replaces anything), its folders, the home screen shortcut, its log, its facts (title ID, folders) under a closed Details, and Remove from list apart at the
     * bottom (asked first; it deletes nothing on the storage). An action that works on Home's card (Prepare, Remove) goes back to Home first
     */
    void gameTab(LinearLayout content, Game g, boolean savesTab) {
        String noProfile = Profile.missing(g.titleId, g.name);
        if (savesTab) {
            LinearLayout saves = Ui.add(content, Ui.listCard(this), 16);
            saves.addView(new Ui.Setting(this, "💾", Tx.s(R.string.home_back_up_saves), v -> SaveUi.backup(this, g)).value(SaveUi.backupLine(this, g)).chevron(this).view);
            saves.addView(new Ui.Setting(this, "♻️", Tx.s(R.string.home_restore_saves), v -> SaveUi.restoreList(this, g)).value(Tx.s(R.string.home_from_a_backup_the_current)).chevron(this).view);
            saves.addView(new Ui.Setting(this, "📥", Tx.s(R.string.home_import_save), v -> SaveUi.importPick(this, g)).value(Tx.s(R.string.home_a_zip_or_a_save)).chevron(this).view);
            File save = Folders.userSave(this, g);   // the save of the user games start with (Back up / Restore / Import act on it)
            TextView where = Ui.add(content, Ui.small(this, Tx.s(R.string.home_the_save_at, save != null ? save.getPath() : Tx.s(R.string.home_not_set))), 6);
            where.setTextSize(12);
            where.setTextIsSelectable(true);
            where.setPadding(dp(4), 0, dp(4), 0);
            return;
        }
        // What the dump holds: base, updates, DLC (DumpLayout.contents, read off the main thread: a .nup's TOC is hashed)
        Ui.add(content, Ui.section(this, Tx.s(R.string.home_game_files)), 16);
        LinearLayout files = Ui.add(content, Ui.listCard(this), 6);
        TextView reading = Ui.small(this, Tx.s(R.string.home_reading_the_game_files));
        reading.setPadding(dp(10), dp(10), dp(10), dp(10));
        files.addView(reading);
        new Thread(() -> {
            List<DumpLayout.Item> items = DumpLayout.contents(new File(g.gameDir), Profile.of(g.titleId));
            runOnUiThread(() -> {
                if (isFinishing()) return;
                files.removeAllViews();
                for (DumpLayout.Item it : items) {
                    String emoji = it.kind.equals("base") ? "🎮" : it.kind.equals("update") ? "⬆️" : it.kind.equals("dlc") ? "🧩" : "⚠️";
                    Ui.Setting s = new Ui.Setting(this, emoji, it.title, null).value(it.detail);
                    if (it.kind.equals("problem")) s.status(it.title);
                    s.view.setClickable(false);
                    s.view.setFocusable(false);
                    files.addView(s.view);
                }
            });
        }, "nx-game-files").start();
        Ui.add(content, Ui.section(this, Tx.s(R.string.home_pack)), 16);
        LinearLayout pack = Ui.add(content, Ui.listCard(this), 6);
        if (Ondevice.isDump(new File(g.gameDir)) && noProfile == null)
            pack.addView(new Ui.Setting(this, "🔁", Tx.s(R.string.common_prepare_again), v -> { page(false); Ondevice.prepare(this, new File(g.gameDir), true); }).value(Tx.s(R.string.home_remakes_what_does_not_fit)).chevron(this).view);
        if (Ondevice.shows(g))   // its card is the progress card: the job's own Manage (Forget, the deletes)
            pack.addView(new Ui.Setting(this, "⏳", Tx.s(R.string.common_preparing), v -> { page(false); Ondevice.manageJob(this); }).value(Tx.s(R.string.home_forget_the_job_or_delete)).chevron(this).view);
        pack.addView(new Ui.Setting(this, "📦", Tx.s(R.string.home_export_pack), v -> PackUi.exportPick(this, g)).value(Tx.s(R.string.home_one_zip_into_a_folder)).chevron(this).view);
        if (noProfile == null)
            pack.addView(new Ui.Setting(this, "📲", Tx.s(R.string.common_import_pack), v -> PackUi.importPick(this, g)).value(Tx.s(R.string.home_a_zip_or_a_pack)).chevron(this).view);
        pack.addView(new Ui.Setting(this, "📁", Tx.s(R.string.common_folders), v -> Folders.dialog(this, g)).value(Tx.s(R.string.common_game_pack_and_save_folders)).chevron(this).view);
        Ui.add(content, Ui.section(this, Tx.s(R.string.home_more)), 16);
        LinearLayout more = Ui.add(content, Ui.listCard(this), 6);
        if (noProfile == null && Shortcuts.supported(this))
            more.addView(new Ui.Setting(this, "🏠", Tx.s(R.string.home_add_to_home_screen), v -> Shortcuts.pin(this, g)).value(Tx.s(R.string.home_a_shortcut_that_starts_this)).chevron(this).view);
        more.addView(new Ui.Setting(this, "📨", Tx.s(R.string.common_share_log), v -> shareLog(g.titleId)).value(Tx.s(R.string.home_this_game_s_newest_log)).chevron(this).view);
        String lp = Pack.libraryProblem(new File(g.packDir), g.titleId);
        File save = Folders.save(this, g);
        Ui.Details info = Ui.details(this, Tx.s(R.string.home_game_details, g.titleId,
                PickedNup.is(this, g.gameDir) ? Tx.s(R.string.home_nup_in_place, new File(g.gameDir).getName()) : g.gameDir + (Nup.isNup(new File(g.gameDir)) ? Tx.s(R.string.home_nup) : ""),
                g.packDir, lp == null ? Tx.s(R.string.home_shaders_present, BuildConfig.NX_DMSH_VK_CHAIN) : lp, save != null ? save.getPath() : Tx.s(R.string.home_not_set)));
        info.text.setTextSize(12);
        Ui.add(content, info, 8);
        Ui.add(content, Ui.divider(this), 12, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        Ui.add(content, Ui.button(this, Tx.s(R.string.home_remove_from_list), Ui.Kind.DANGER, v -> { page(false); confirmRemove(g); }), 12, Ui.wrap());
        Ui.add(content, Ui.small(this, Tx.s(R.string.home_delete_game_hint)), 6);
        Ui.add(content, Ui.button(this, Tx.s(R.string.home_delete_game), Ui.Kind.DANGER, v -> GameDelete.ask(this, g)), 8, Ui.wrap());
    }

    private void openAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    /** A game in app storage (Copy into the app, GameCopy) or a .nup picked in place (PickedNup: its stand-in path is in app storage) is read without All files access */
    boolean needAccess(File game) { return (game == null || !CopyPlan.inApp(AppStorage.root(this), game.getPath())) && needAccess(); }

    /** Whether Home's access card is worth showing: no game yet, or one that is read where it is by its path */
    private boolean listNeedsAccess() {
        List<Game> l = Game.load(this);
        if (l.isEmpty()) return true;
        for (Game g : l) if (!CopyPlan.inApp(AppStorage.root(this), g.gameDir)) return true;
        return false;
    }

    /** A game copied into the app (Add game › Copy into the app): it is added as a picked one */
    void copied(File game) {
        Toast.makeText(this, Tx.s(R.string.home_copied_into_the_app, game.getName()), Toast.LENGTH_LONG).show();
        gamePicked(game);
    }

    boolean needAccess() {
        if (Environment.isExternalStorageManager()) return false;
        new PadFocus.Builder(this).setTitle(Tx.s(R.string.home_allow_file_access)).setMessage(Tx.s(R.string.home_the_app_needs_all_files))
                .setPositiveButton(Tx.s(R.string.common_open_settings), (d, w) -> openAccessSettings()).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
        return true;
    }

    void pickFolder(int request, String title) {
        Toast.makeText(this, title, Toast.LENGTH_LONG).show();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"));
        startActivityForResult(i, request);
    }

    /** Add game: the dump is a folder (base/, updates/) or one .nup file (NUP.md); the user says which, then the system picker opens for it */
    void addGame() {
        if (!Environment.isExternalStorageManager()) { addWithoutAccess(); return; }
        new PadFocus.Builder(this).setTitle(Tx.s(R.string.home_add_game_your_dump_is))
                .setItems(new String[] {Tx.s(R.string.home_a_folder_with_base_and), Tx.s(R.string.home_a_nup_file_or_an)}, (d, w) -> {
                    if (w == 0) pickFolder(PICK_GAME, Tx.s(R.string.home_pick_your_dump_folder_with));
                    else pickNup();
                })
                .setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }

    /**
     * Add game without All files access: a .nup is used where it is (PickedNup: the picker's read permission, kept; nothing copied); a dump folder is copied into the
     * app's own storage (GameCopy: the game opens its RomFS files by path, and the picker gives no path); or the user turns All files access on to read dumps where
     * they are
     */
    private void addWithoutAccess() {
        new PadFocus.Builder(this).setTitle(Tx.s(R.string.home_add_game_your_dump_is))
                .setItems(new String[] {Tx.s(R.string.home_a_nup_or_nsp_file), Tx.s(R.string.home_a_dump_folder_copied_into),
                        Tx.s(R.string.home_allow_all_files_access)}, (d, w) -> {
                    if (w == 0) pickNupHere();
                    else if (w == 1) { Toast.makeText(this, Tx.s(R.string.home_pick_your_dump_folder_with), Toast.LENGTH_LONG).show(); GameCopy.pickTree(this); }
                    else openAccessSettings();
                })
                .setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }

    /** The file picker for a .nup used where it is (PickedNup) */
    void pickNupHere() {
        Toast.makeText(this, Tx.s(R.string.home_pick_your_game_s_nup), Toast.LENGTH_LONG).show();
        startActivityForResult(PickedNup.pickIntent(), PICK_NUP_HERE);
    }

    /** The card whose gone file is being picked again (relink): the next picked game goes to it */
    private Game relinkFor;

    /**
     * Pick the file again for a card whose file is gone (GameFile): a .nup with the file picker (used where it is without All files access, by its path with it), a
     * dump folder with the folder picker (All files access). The picked file takes the card's place when it holds the same game (relinkTo)
     */
    void relink(Game g) {
        boolean nup = Nup.extOf(g.gameDir) != null;
        if (!nup && needAccess()) return;
        relinkFor = g;
        if (!nup) pickFolder(PICK_GAME, Tx.s(R.string.home_pick_dump_folder_again, g.name));
        else if (Environment.isExternalStorageManager()) pickNup();
        else pickNupHere();
    }

    /** The picked file in the card's place (its pack, settings and saves stay: they belong to the card and its title ID), when it holds the same game */
    private void relinkTo(Game g, File game) {
        new Thread(() -> {
            String id = null, err;
            try { id = Pack.playableIdOf(game); err = GameFile.relinkProblem(g.titleId, g.name, id, game.getName()); } catch (IOException e) { err = e.getMessage(); }
            if (err == null) {
                synchronized (Game.class) {
                    List<Game> list = Game.load(this);
                    int i = GameFile.index(list, g.gameDir);
                    if (i >= 0) list.set(i, new Game(g.titleId, g.name, game.getPath(), g.packDir, list.get(i).used));
                    else list.add(new Game(g.titleId, g.name, game.getPath(), g.packDir, g.used));
                    Game.save(this, list);
                }
                if (!game.getPath().equals(g.gameDir)) PickedNup.forget(this, new File(g.gameDir));   // the old .nup's permission, when it was one used where it is
            } else if (!game.getPath().equals(g.gameDir)) PickedNup.forget(this, game);   // a picked file of another game: not kept
            String fe = err;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (fe != null) { error(fe); return; }
                Toast.makeText(this, Tx.s(R.string.home_game_now_uses, g.name, game.getName()), Toast.LENGTH_LONG).show();
                refresh();
            });
        }, "nx-relink").start();
    }

    /** A .nup picked to use where it is: its permission kept and the file opened off the main thread, then added as any picked game */
    private void nupHere(Uri u) {
        new Thread(() -> {
            File f = null;
            String err = null;
            boolean streamed = false;
            try { f = PickedNup.picked(this, u); } catch (PickedNup.Streamed e) { err = e.getMessage(); streamed = true; } catch (IOException e) { err = e.getMessage(); }
            File ff = f;
            String fe = err;
            boolean fs = streamed;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (ff == null) {
                    relinkFor = null;
                    AlertDialog.Builder b = Ui.explain(this, Tx.s(R.string.home_cannot_use_this_file), fe, u.toString()).setNegativeButton(android.R.string.ok, null);
                    if (fs) b.setPositiveButton(Tx.s(R.string.home_copy_into_the_app), (d, w) -> GameCopy.copyNup(this, u));
                    b.show();
                    return;
                }
                if (relinkFor != null) { Game g = relinkFor; relinkFor = null; relinkTo(g, ff); return; }
                addAll(List.of(ff), true);
            });
        }, "nx-pick-nup").start();
    }

    /**
     * The system file picker for a .nup; its document is turned into its /storage path (PathUtil.documentToPath), as a picked folder is. A document without such a path
     * (Recent, Downloads) is used where it is (PickedNup), so the picker asks for a lasting read permission too
     */
    private void pickNup() {
        Toast.makeText(this, Tx.s(R.string.home_pick_your_game_s_nup), Toast.LENGTH_LONG).show();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"));
        startActivityForResult(i, PICK_NUP);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (Ondevice.activityResult(this, request, result, data)) return;
        if (Covers.activityResult(this, request, result, data)) return;
        if (request == ReadinessActivity.REQUEST) { if (result == ReadinessActivity.FIX_DRIVER) GpuDriver.dialog(this, PICK_DRIVER); return; }   // its "Pick a GPU driver"
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == PICK_BG) {
            if (UiLook.pickBackground(this, data.getData())) { Prefs.background(this, 2); bananas.setLook(2); }
            else Toast.makeText(this, Tx.s(R.string.home_that_file_is_not_a), Toast.LENGTH_LONG).show();
            if (settingsPage) build();
            return;
        }
        if (request == UsersUi.PICK_PHOTO) {   // a player's own picture
            if (usersEdit != null && !UsersUi.setPhoto(this, usersEdit, data.getData())) Toast.makeText(this, Tx.s(R.string.settings_that_picture_could_not_be_read), Toast.LENGTH_LONG).show();
            userChanged();
            if (usersPage) build();
            return;
        }
        if (request == PICK_SND) {
            if (UiSound.pick(this, data.getData())) UiSound.select(this);
            else Toast.makeText(this, Tx.s(R.string.home_that_sound_could_not_be), Toast.LENGTH_LONG).show();
            if (settingsPage) build();
            return;
        }
        if (request == PICK_DRIVER) { GpuDriver.picked(this, data.getData()); return; }
        if (GameCopy.activityResult(this, request, data)) return;
        if (PackUi.documentResult(this, request, data.getData())) return;
        if (SaveUi.documentResult(this, request, data.getData())) return;
        if (CheatsUi.documentResult(this, request, data.getData())) return;
        if (ModsUi.documentResult(this, request, data.getData())) return;
        if (request == PICK_NUP_HERE) { nupHere(data.getData()); return; }
        if (request == PICK_NUP) {
            String p = PathUtil.documentToPath(data.getData());
            if (p == null) { nupHere(data.getData()); return; }   // Recent, Downloads: no path, but the file can be used where it is (PickedNup)
            gamePicked(new File(p));
            return;
        }
        if (request == PICK_DLL) {
            String err = Lsfg.install(this, data.getData());
            Toast.makeText(this, err == null ? Tx.s(R.string.home_lossless_dll_installed) : Tx.s(R.string.home_lossless_dll_not_installed, err), Toast.LENGTH_LONG).show();
            return;
        }
        String path = PathUtil.treeToPath(data.getData());
        if (path == null) { error(Tx.s(R.string.err_folder_not_on_storage, data.getData())); return; }
        File dir = new File(path);
        if (Folders.activityResult(this, request, dir)) return;
        if (PackUi.activityResult(this, request, dir)) return;
        if (request == PICK_GAME) gamePicked(dir);
        else if (request == PICK_PACK && pendingGame != null) { pendingPackPick = dir; addWithPack(pendingGame, pendingId, dir); }
    }

    /**
     * A picked game: one .nup, one dump folder (base/exefs/main.npdm or exefs/main.npdm), or a folder that holds games (PackFind.gamesIn: its .nup files and
     * dump subfolders), all of which are added (addAll); a folder with one game is that game
     */
    private void gamePicked(File game) {
        if (relinkFor != null) { Game g = relinkFor; relinkFor = null; relinkTo(g, game); return; }
        if (Nup.isNup(game) || Nup.wrongCase(game)) { nupPicked(game); return; }
        if (Pack.npdmOf(game) != null) { addAll(List.of(game), true); return; }
        List<File> found = PackFind.gamesIn(game);
        if (found.size() == 1) { gamePicked(found.get(0)); return; }
        if (found.isEmpty()) {
            error(Tx.s(R.string.err_no_game_in_folder, game));
            return;
        }
        addAll(found, false);
    }

    /** A single .nup (NUP.md): its name and access checked here, the rest as every game (addAll) */
    private void nupPicked(File nup) {
        if (Nup.wrongCase(nup)) { error(Tx.s(R.string.err_nup_rename, nup.getName())); return; }
        if (!Nup.picked(nup) && !nup.canRead()) { error(Tx.s(R.string.err_cannot_be_read, nup.toString())); return; }
        addAll(List.of(nup), true);
    }

    /** What the background check of Add game found for one game */
    private static final class Pick {
        final File game;
        String id, name, error;   // error: why it cannot be added (unreadable, no profile in this APK), in a sentence
        String plain;             // an NSP this app does not read (Nsp.Refused): what to tell the player; error is then the technical detail
        boolean unsupported;      // no profile in this APK
        boolean dump;             // a dump Prepare starts from (Pack.isDump)
        PackFind.Found pack;      // a pack that plays now or after the automatic shader update (Folders.find), null: none
        DumpLayout.Found found;   // without a pack: the version Prepare would start from, or why it cannot (DumpLayout.find)
        String key;               // its game build (Game.key: title, version, main's build ID), null when it cannot be told
        final List<Game> others = new ArrayList<>();   // Home's entries of the same title that are another dump (GameList.others): the user decides

        Pick(File game) { this.game = game; name = game.getName(); }
    }

    /**
     * Add game for one or more games: each is identified off the main thread (a .nup's TOC is hashed) with its best pack (Folders.find: the picked folder,
     * the app's, beside the game, &lt;id&gt;-work/&lt;any&gt; above it) and checked (PackCheck.blocking), then added on the main thread. A game with a pack that plays
     * now or after the automatic shader update is added with it, with no question. single: one picked game, as before: a dump without such a pack goes to Prepare
     * (Ondevice asks first; it offers an existing pack folder or Import pack… too), a one-version folder to the pack folder picker, and a problem is one message.
     * Several (a picked folder's games): a dump without a usable pack gets a card with its Prepare button (one job at a time is the user's to start), and one
     * message sums up what was added, what this app cannot run and what needs Prepare.
     * One card per game (GameList): the same file or the same title and build replaces its entry; another dump of a title on the list is asked about (single:
     * replace it or keep both) or kept beside it (several, said in the summary)
     */
    private void addAll(List<File> picked, boolean single) {
        Toast.makeText(this, single ? Tx.s(R.string.home_checking_name, picked.get(0).getName()) : Tx.n(R.plurals.home_checking_games, picked.size(), picked.size()), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<Pick> out = new ArrayList<>();
            for (File g : picked) {
                Pick k = new Pick(g);
                out.add(k);
                if (Nup.wrongCase(g)) { k.error = Tx.s(R.string.err_nup_rename, g.getName()); continue; }
                try { k.id = Pack.playableIdOf(g); } catch (IOException e) {   // playableIdOf: program 0, else a program n > 0 this APK runs (a Switch 2 Edition)
                    k.error = e.getMessage();
                    if (e instanceof Nsp.Refused) k.plain = ((Nsp.Refused) e).plain;
                    android.util.Log.w("nxnative", "[RED] Add game: " + g.getName() + ": " + e);
                    continue;
                }
                String why = Profile.missing(k.id, single ? null : g.getName());
                if (why != null) { k.unsupported = true; k.error = why; continue; }
                k.name = Profile.of(k.id).name;
                k.dump = Pack.isDump(g);
                String pp = Folders.pickedPack(this, k.id);
                File pd = pp == null ? null : new File(pp);
                try { if (pd != null && !Pack.read(pd).titleId.equals(k.id)) pd = null; } catch (IOException e) { pd = null; }
                k.pack = Folders.find(this, pd, g, k.id);
                if (k.pack == null) k.found = DumpLayout.find(g);   // no pack: whether Prepare can start from it (any layout), or why not (another build, incomplete)
                else if (!k.pack.pack.gameVersion(g).equals(k.pack.pack.version))   // the pack's build under another version number of this dump: the same code, played as it is
                    android.util.Log.i("nxnative", "Add game: " + g.getName() + " keeps the pack's build (version " + k.pack.pack.version + ") as version " + k.pack.pack.gameVersion(g) + ": same build IDs, same code");
                k.key = Game.key(g.getPath(), k.id);   // read here, off the main thread: the add takes the keys from the cache
                List<Game> list = Game.load(this);
                for (int i : GameList.others(list, k.id, g.getPath(), k.key, Game::key)) k.others.add(list.get(i));
                android.util.Log.i("nxnative", "Add game: " + g + " (" + k.id + ", " + k.key + "): " + (k.pack == null ? "no usable pack" : "pack " + k.pack.dir + (k.pack.tier == PackFind.READY ? " (ready)" : k.pack.tier == PackFind.ON_DEMAND ? " (shaders on demand)" : " (shader update)"))
                        + (k.others.isEmpty() ? "" : "; " + k.others.size() + " other dump(s) of it on the list"));
            }
            runOnUiThread(() -> { if (!isFinishing()) added(out, single); });
        }, "nx-add-game").start();
    }

    private void added(List<Pick> picks, boolean single) {
        if (single) {
            Pick k = picks.get(0);
            if (k.error != null) {
                if (k.plain != null) Ui.explain(this, Tx.s(R.string.home_cannot_use_this_nsp), k.plain, k.error).setPositiveButton(android.R.string.ok, null).show();
                else error(k.error);
                return;
            }
            askReplace(k.name, k.game, k.others, replace -> {
                if (k.pack != null) { addWithPack(k.game, k.id, k.pack.dir, replace); return; }
                if (replace != null) removeEntries(replace);   // the user chose this dump: the other's card goes now (nothing is deleted), this one's comes with its pack
                if (k.found != null && k.found.error == null) {
                    if (k.found.build >= 0 && k.found.build != k.found.version)
                        android.util.Log.i("nxnative", "Add game: " + k.game.getName() + " " + DumpLayout.name(k.found.version) + " is " + Profile.of(k.id).buildName(k.found.build) + " by its build IDs");
                    Ondevice.prepare(this, k.game, false);
                    return;
                }   // a dump of a supported build: Prepare makes its pack
                if (k.found != null && k.found.otherBuild) { Ondevice.prepare(this, k.game, false); return; }   // a build this app does not list: Prepare says so and offers Try anyway
                pendingGame = k.game;
                pendingId = k.id;
                String what = Nup.isNup(k.game) ? ".nup" : "folder";
                // Why Prepare cannot start from it (another build than this app's, an incomplete dump), plainly; a pack made elsewhere can still be picked
                String why = k.found != null && k.found.error != null ? k.found.error
                        : Tx.s(R.string.home_no_pack_found_for_this, what);
                Ui.explain(this, Tx.s(R.string.home_no_pack_for_this_what, what), Tx.s(R.string.home_pick_a_pack_folder_hint, why, what),
                                k.found != null ? k.found.detail : "")
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(Tx.s(R.string.home_pick_a_pack_folder), (d, w) -> pickFolder(PICK_PACK, Tx.s(R.string.home_pick_the_pack_folder_android))).show();
            });
            return;
        }
        List<String> ok = new ArrayList<>(), unsupported = new ArrayList<>(), prepare = new ArrayList<>(), noPack = new ArrayList<>(), failed = new ArrayList<>(), twice = new ArrayList<>();
        for (Pick k : picks) {
            if (k.unsupported) { unsupported.add(k.game.getName() + " (" + k.id + ")"); continue; }
            if (k.error != null) { failed.add(k.plain != null ? k.game.getName() + ": " + k.plain : k.error); continue; }
            if (k.pack != null) {
                String err = add(k.game, k.id, k.pack.dir, null);
                if (err == null) ok.add(k.pack.pack.name.isEmpty() ? k.name : k.pack.pack.name);
                else failed.add(k.game.getName() + ": " + err);
            } else if (k.dump) {
                cardFor(k.game, k.id);
                prepare.add(k.name);
            } else noPack.add(k.game.getName());
            if (!k.others.isEmpty() && (k.pack != null || k.dump)) twice.add(k.game.getName());
        }
        refresh();
        StringBuilder m = new StringBuilder();
        if (!ok.isEmpty()) m.append(Tx.s(R.string.home_added_list, TextUtils.join(", ", ok)));
        if (!prepare.isEmpty()) m.append(m.length() > 0 ? "\n\n" : "").append(Tx.s(R.string.home_needs_prepare_list, TextUtils.join(", ", prepare)));
        if (!unsupported.isEmpty()) m.append(m.length() > 0 ? "\n\n" : "").append(Tx.s(R.string.home_not_in_version_list, TextUtils.join(", ", unsupported)));
        if (!noPack.isEmpty()) m.append(m.length() > 0 ? "\n\n" : "").append(Tx.s(R.string.home_no_pack_found_list, TextUtils.join(", ", noPack)));
        if (!failed.isEmpty()) m.append(m.length() > 0 ? "\n\n" : "").append(Tx.s(R.string.home_not_added_list, TextUtils.join(" ", failed)));
        if (!twice.isEmpty()) m.append(m.length() > 0 ? "\n\n" : "").append(Tx.s(R.string.home_both_cards_kept, TextUtils.join(", ", twice)));
        android.util.Log.i("nxnative", "Add game: " + m.toString().replace("\n\n", " | "));
        new PadFocus.Builder(this).setTitle(ok.isEmpty() && prepare.isEmpty() ? Tx.s(R.string.home_no_game_added) : Tx.s(R.string.home_games_added)).setMessage(m).setPositiveButton(android.R.string.ok, null).show();
    }

    /**
     * Another dump of a game that is on the list (another file, another build or one that cannot be told): asks whether this one replaces it (its list entry
     * only; nothing is deleted) or both are kept. then gets the entries' game paths to replace, or null to keep them; nothing to ask: null at once
     */
    private void askReplace(String name, File game, List<Game> others, java.util.function.Consumer<List<String>> then) {
        if (others.isEmpty()) { then.accept(null); return; }
        List<String> dirs = new ArrayList<>();
        StringBuilder s = new StringBuilder();
        for (Game o : others) { dirs.add(o.gameDir); s.append("\n• ").append(o.gameDir); }
        new PadFocus.Builder(this).setTitle(Tx.s(R.string.home_replace_name, name))
                .setMessage(Tx.s(R.string.home_another_dump_message, name, s, game.getName()))
                .setPositiveButton(Tx.s(R.string.home_replace), (d, w) -> then.accept(dirs))
                .setNeutralButton(Tx.s(R.string.home_keep_both), (d, w) -> then.accept(null))
                .setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }

    /** These game paths' list entries go (exact paths, as the list keeps them); nothing on the storage changes */
    private void removeEntries(List<String> gameDirs) {
        synchronized (Game.class) {
            List<Game> list = Game.load(this);
            list.removeIf(x -> gameDirs.contains(x.gameDir));
            Game.save(this, list);
        }
        refresh();
    }

    /**
     * The list entry of a game, made when it has none: a game that still needs its pack gets a card whose pack folder is the app's packs/&lt;id&gt;, where Prepare
     * and Import pack… put it (the card says "Needs preparing" and its button prepares). An existing entry of the same game (the same file, or the same title and
     * build: GameList.matches) is kept as it is
     */
    Game cardFor(File game, String id) {
        List<Game> list = Game.load(this);
        List<Integer> same = GameList.matches(list, id, game.getPath(), Game.key(game.getPath(), id), Game::key);
        if (!same.isEmpty()) return list.get(same.get(0));
        Profile p = Profile.of(id);
        Game g = Game.put(this, new Game(id, p == null ? id : p.name, game.getPath(), AppStorage.pack(this, id).getPath()));
        refresh();
        return g;
    }

    /** Prepare's "Use an existing pack…": the picked pack folder becomes the game's (addWithPack; remembered for this title like a pack picked in Folders) */
    void pickPackFor(File game, String id) {
        pendingGame = game;
        pendingId = id;
        pickFolder(PICK_PACK, Tx.s(R.string.common_pick_the_pack_folder_the));
    }

    private boolean addWithPack(File game, String id, File packDir) { return addWithPack(game, id, packDir, null); }

    private boolean addWithPack(File game, String id, File packDir, List<String> replace) {
        String err = add(game, id, packDir, replace);
        if (err != null) { error(err); return false; }
        refresh();
        if (packDir.equals(pendingPackPick)) Folders.pickedPack(this, id, packDir.getPath());   // a pack folder the user picked is remembered for this game's next pick and updates
        String note = "";
        String lp = Pack.libraryProblem(packDir, id);
        if (lp != null) note += Pack.shadersOnDemand(packDir, id, Prefs.layerEnv(this, id)) ? " " + ShaderState.onDemandNote(Pack.isDump(game)) : " " + lp;
        String name;
        try { name = Pack.read(packDir).name; } catch (IOException e) { name = id; }
        Toast.makeText(this, Tx.s(R.string.home_added_name, name.isEmpty() ? id : name) + note, Toast.LENGTH_LONG).show();
        return true;
    }

    /**
     * The game into the list with this pack folder (Game.put: replacing the entries of the same file or the same title and build, and those in replace, which
     * the user chose to replace); null when added, else why not in a sentence
     */
    private String add(File game, String id, File packDir, List<String> replace) {
        Pack pack;
        try { pack = Pack.read(packDir); } catch (IOException e) { return e.getMessage(); }
        if (!pack.titleId.equals(id)) return "The pack is for " + pack.titleId + ", the game " + (Nup.isNup(game) ? ".nup" : "folder") + " holds " + id + ".";
        String why = Profile.missing(id, pack.name.isEmpty() ? null : pack.name);
        if (why != null) return why;
        if (Nup.isNup(game)) {
            String romfs;
            try { romfs = pack.romfsPath(game); } catch (IOException e) { return Tx.s(R.string.err_game_cannot_be_used, game.getName(), e.getMessage()); }
            if (romfs == null) return Tx.s(R.string.err_game_has_no_romfs, game.getName(), pack.romfsSelector(), pack.version);
        } else if (pack.romfs(game) == null) return Tx.s(R.string.err_no_romfs_in, game.toString(), pack.romfsRel);
        Game.put(this, new Game(id, pack.name.isEmpty() ? id : pack.name, game.getPath(), packDir.getPath()), replace);
        return null;
    }

    /** Remove from the list (the Manage sheet): says exactly what goes (the list entry) and what stays (the folders and the saves) */
    private void confirmRemove(Game g) {
        File save = Folders.save(this, g);
        if (CopyPlan.isCopy(AppStorage.root(this), g.gameDir)) { confirmRemoveCopy(g, save); return; }
        Ui.confirmDanger(this, Tx.s(R.string.home_remove_name, g.name),
                save != null ? Tx.s(R.string.home_remove_entry_message_save, save.getPath()) : Tx.s(R.string.home_remove_entry_message),
                Tx.s(R.string.common_remove), () -> {
                    List<Game> list = Game.load(this);
                    list.removeIf(x -> x.gameDir.equals(g.gameDir));
                    Game.save(this, list);
                    PickedNup.forget(this, new File(g.gameDir));   // a .nup used where it is: its read permission goes back (another entry of it keeps it)
                    refresh();
                });
    }

    /** A game copied into the app: Remove can take the copy too (it is the app's own, and only Remove can free its space short of uninstalling) */
    private void confirmRemoveCopy(Game g, File save) {
        Runnable drop = () -> { List<Game> list = Game.load(this); list.removeIf(x -> x.gameDir.equals(g.gameDir)); Game.save(this, list); refresh(); };
        new PadFocus.Builder(this).setTitle(Tx.s(R.string.home_remove_name, g.name))
                .setMessage(save != null ? Tx.s(R.string.home_remove_copy_message_save, g.gameDir, save.getPath()) : Tx.s(R.string.home_remove_copy_message, g.gameDir))
                .setPositiveButton(Tx.s(R.string.home_remove_only), (d, w) -> drop.run())
                .setNeutralButton(Tx.s(R.string.home_delete_the_copy), (d, w) -> Ui.confirmDanger(this, Tx.s(R.string.home_delete_the_copy_2), Tx.s(R.string.home_delete_copy_confirm, g.gameDir), Tx.s(R.string.home_delete), () -> {
                    drop.run();
                    if (!GameCopy.deleteCopy(this, new File(g.gameDir))) error(Tx.s(R.string.err_not_all_deleted, g.gameDir));
                }))
                .setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }

    void launch(Game card) {
        if (needAccess(new File(card.gameDir))) return;
        if (Edition.on(this, card.titleId) && Edition.offered(card)) {   // the card's edition switch: program 1 of its set, as a game of its own (Edition.forPlay)
            Game e = Edition.forPlay(this, card);
            if (e == null) {
                Ui.explain(this, Tx.s(R.string.home_no_pack_for_the_switch), Tx.s(R.string.home_edition_needs_pack, Edition.name(card), Edition.program1(new File(card.gameDir))),
                        "Game: " + card.gameDir).setPositiveButton(android.R.string.ok, null).show();
                return;
            }
            card = e;
        }
        Game g = Folders.repack(this, card);   // a remembered pack folder that is gone: the usual search instead
        File saveGone = Folders.goneSave(this, g);
        if (saveGone != null) { Folders.saveGoneDialog(this, g, saveGone); return; }
        String gone = gone(g);
        if (gone != null) {
            Ui.explain(this, Tx.s(R.string.home_game_file_gone), GameFile.goneText(new File(g.gameDir).getName()), gone)
                    .setPositiveButton(Tx.s(R.string.home_pick_it_again), (d, w) -> relink(g)).setNeutralButton(Tx.s(R.string.home_remove), (d, w) -> confirmRemove(g)).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
            return;
        }
        String why = Profile.missing(g.titleId, g.name);
        if (why != null) { error(why); return; }
        if (SaveUi.busy(g.titleId)) { error(Tx.s(R.string.err_save_busy, g.name)); return; }
        if (PackUi.busy(g.titleId)) { error(Tx.s(R.string.err_pack_busy, g.name)); return; }
        File gd = new File(g.gameDir);
        boolean dump = Ondevice.isDump(gd);
        Runnable pickPack = () -> { pendingGame = gd; pendingId = g.titleId; pickFolder(PICK_PACK, Tx.s(R.string.common_pick_the_pack_folder_the)); };
        Pack p;
        try {
            p = Pack.read(new File(g.packDir));
        } catch (IOException e) {
            if (dump) packProblem(g, Tx.s(R.string.home_this_game_has_no_pack_2), e.getMessage(), pickPack);
            else Ui.explain(this, Tx.s(R.string.home_no_pack_for_this_game), Tx.s(R.string.home_this_game_has_no_pack), e.getMessage() + "\nPack: " + g.packDir)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        // The pack's code must be of the game build in the folder (else the game crashes at once): a plain message, and Prepare again from the dump, instead of a
        // start that cannot work. A build this app has not been tested with (a pack made with Try anyway, or elsewhere) or another version's RomFS can still start:
        // Play anyway, after a warning
        String bad = PackCheck.blocking(this, p, gd);
        if (bad != null) {
            BuildCheck.Result b = BuildCheck.build(p, gd);
            boolean untested = p.unfinishedModule() == null && PackCheck.rewriting(this, p.dir, gd, p.titleId) == null
                    && (b.kind == BuildCheck.Kind.UNSUPPORTED || b.kind == BuildCheck.Kind.ROMFS);
            if (!untested) {
                int cut = bad.indexOf("\n\n");   // BuildCheck: the build IDs that differ follow the sentence
                packProblem(g, cut > 0 ? bad.substring(0, cut) : bad, cut > 0 ? bad.substring(cut + 2) : "", pickPack);
                return;
            }
            if (!playAnyway.contains(g.gameDir)) {
                Ui.explain(this, Tx.s(R.string.home_unsupported_game_version), b.message + "\n\n" + (b.kind == BuildCheck.Kind.UNSUPPORTED ? PrepareText.UNSUPPORTED_WARNING() : PrepareText.ANYWAY_WARNING()), b.detail + "\nPack: " + g.packDir)
                        .setPositiveButton(Tx.s(R.string.home_play_anyway), (d, w) -> { playAnyway.add(g.gameDir); launch(g); })
                        .setNegativeButton(Tx.s(R.string.common_cancel), null).show();
                return;
            }
            android.util.Log.w("nxnative", g.titleId + ": Play anyway: " + b.kind + ": " + b.detail);
        }
        // The host loads <pack>/shaders/v-<its DMSH_VK_CHAIN>/dmsh_vk.lib: without it the game runs but draws nothing. Say so, and offer to prepare the shaders
        // (a dump) or another pack folder
        String lp = Pack.libraryProblem(new File(g.packDir), g.titleId);
        if (lp != null && Pack.shadersOnDemand(new File(g.packDir), g.titleId, Prefs.layerEnv(this, g.titleId))) {   // no library to play on, the shader layer builds what the game asks for as it runs: no question
            if (!Prefs.layerExplained(this, g.titleId)) {   // the first start of this game on demand: once, what to expect (Prefs.layerExplained)
                Prefs.markLayerExplained(this, g.titleId);
                new PadFocus.Builder(this).setTitle(Tx.s(R.string.home_shaders_build_as_you_play)).setMessage(PrepareText.LAYER_FIRST_RUN())
                        .setPositiveButton(Tx.s(R.string.common_play), (d, w) -> start(g)).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
                return;
            }
            start(g);
            return;
        }
        if (lp != null && Pack.playableLibrary(new File(g.packDir), g.titleId) != null) {   // the older library plays while the update makes this version's (vka_shader.c lib_load)
            if (dump) Ondevice.autoUpdate(this, gd, new File(g.packDir), g.titleId);
            start(g);
            return;
        }
        if (lp != null && Pack.legacyLibrary(new File(g.packDir), g.titleId) != null) {   // made before versioned updates: never blocks Play, says so (the host warns in its log too)
            if (dump) Ondevice.autoUpdate(this, gd, new File(g.packDir), g.titleId);
            Toast.makeText(this, ShaderLibs.legacyWarning(dump), Toast.LENGTH_LONG).show();
            start(g);
            return;
        }
        if (lp != null && GameSettings.layerOff(this, new File(g.packDir), g.titleId) == ShaderState.Off.SWITCH
                && Pack.libraryOf(new File(g.packDir)) == null) {   // only the player's switch keeps it from playing now: say so, and offer to turn it on
            AlertDialog.Builder b = new PadFocus.Builder(this).setTitle(Tx.s(R.string.home_shaders_missing))
                    .setMessage(GameSettings.neededText(new File(g.packDir), g.titleId, ShaderState.Off.SWITCH, dump))
                    .setPositiveButton(Tx.s(R.string.home_turn_on_and_play), (d, w) -> { Prefs.layerBuild(this, g.titleId, true); refresh(); launch(g); })
                    .setNegativeButton(Tx.s(R.string.common_cancel), null);
            if (dump) b.setNeutralButton(Tx.s(R.string.common_prepare), (d, w) -> Ondevice.prepare(this, gd, false));
            else b.setNeutralButton(Tx.s(R.string.home_pick_pack_folder), (d, w) -> pickPack.run());
            b.show();
            return;
        }
        if (lp != null) {
            AlertDialog.Builder b = new PadFocus.Builder(this).setTitle(Tx.s(R.string.home_shaders_missing))
                    .setMessage((dump ? Ondevice.shadersText(new File(g.packDir), g.titleId) : Tx.s(R.string.home_without_them_the_game_draws))
                            + "\n\n" + lp)
                    .setNeutralButton(Tx.s(R.string.home_pick_pack_folder), (d, w) -> pickPack.run()).setNegativeButton(Tx.s(R.string.common_cancel), null);
            if (dump) b.setPositiveButton(Tx.s(R.string.common_prepare), (d, w) -> Ondevice.prepare(this, gd, false));
            else b.setPositiveButton(Tx.s(R.string.home_start_anyway), (d, w) -> start(g));
            b.show();
            return;
        }
        start(g);
    }

    /** The games Play anyway was chosen for in this process (an untested game version): asked once per launcher start, not at every Play */
    private static final java.util.Set<String> playAnyway = new java.util.HashSet<>();

    /** A pack that cannot run with this game folder: why (its technical part in Details), then Prepare again from the dump (the default), or another pack folder */
    private void packProblem(Game g, String why, String detail, Runnable pickPack) {
        File gd = new File(g.gameDir);
        boolean dump = Ondevice.isDump(gd);
        AlertDialog.Builder b = Ui.explain(this, Tx.s(R.string.home_the_pack_cannot_start_this), why + (dump ? Tx.s(R.string.home_n_nprepare_again_rewrites_the) : ""),
                        (detail.isEmpty() ? "" : detail + "\n") + "Pack: " + g.packDir)
                .setNegativeButton(Tx.s(R.string.common_cancel), null).setNeutralButton(Tx.s(R.string.home_pick_pack_folder), (d, w) -> pickPack.run());
        if (dump) b.setPositiveButton(Tx.s(R.string.common_prepare_again), (d, w) -> Ondevice.prepare(this, gd, false));
        b.show();
    }

    private void start(Game g) {
        Ondevice.playTakesLead(this);   // a running shader check stops and waits at the front of the line; the game does not wait for it
        Game.touch(this, g);   // the entry played last is the one kept if the list ever holds this game twice
        Intent intent = new Intent(this, GameActivity.class).putExtra("title_id", g.titleId).putExtra("name", g.name).putExtra("game", g.gameDir).putExtra("pack", g.packDir);
        if (getIntent().getBooleanExtra("no_pause", false)) intent.putExtra("no_pause", true);
        GameReturn.remember(this, g.titleId, g.name, g.gameDir, g.packDir);   // the app's icon goes back to this game while it runs
        startActivity(intent);
    }

    /** The overlay's master switch and its items (Overlay.rows); the main screen's switch follows the master switch */
    private void overlayItems() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        Overlay.rows(this, box, null);
        Ui.sheet(this, Tx.s(R.string.home_overlay), box).setPositiveButton(Tx.s(R.string.common_done), null).setOnDismissListener(d -> refreshSettings()).show();
    }

    private String resLabel() { return Prefs.windowName(Prefs.window(this), true); }

    /** Game resolution: the window size the host gives the game (DM_WINDOW), from the next start */
    private void gameRes() {
        int i = Math.max(0, java.util.Arrays.asList(Prefs.WINDOWS).indexOf(Prefs.window(this)));
        new PadFocus.Builder(this).setTitle(Tx.s(R.string.home_game_resolution)).setSingleChoiceItems(Prefs.WINDOW_NAMES, i, (d, w) -> { Prefs.window(this, Prefs.WINDOWS[w]); refreshSettings(); d.dismiss(); })
                .setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }

    static String capLabel(int v) { return v < 0 ? Tx.s(R.string.home_default) : v == 0 ? Tx.s(R.string.game_the_game_s_own) : v + " fps"; }

    /** The frame rate cap the next start uses (Prefs.fpsCap, DM_FPS_TARGET); the in-game panel changes it while the game runs */
    private void fpsCap() {
        int cur = Prefs.fpsCap(this), n = Prefs.FPS_CAPS.length;
        String[] labels = new String[n + 1];
        labels[0] = capLabel(-1);
        int checked = 0;
        for (int k = 0; k < n; k++) {
            int v = Prefs.FPS_CAPS[k];
            labels[k + 1] = capLabel(v);
            if (v == cur) checked = k + 1;
        }
        new PadFocus.Builder(this).setTitle(Tx.s(R.string.common_frame_rate_cap))
                .setSingleChoiceItems(labels, checked, (d, w) -> { Prefs.fpsCap(this, w == 0 ? -1 : Prefs.FPS_CAPS[w - 1]); refreshSettings(); d.dismiss(); })
                .setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }

    /** Frame generation's settings and the Lossless.dll found (or a manual pick when it is in none of the known places) */
    private void frameGen() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        File dll = Lsfg.find(this);
        Lsfg.rows(this, box, Lsfg.note + (dll != null ? "" : Tx.s(R.string.game_put_dll_in_documents)), () -> {});
        Ui.add(box, Ui.small(this, Tx.s(R.string.home_ported_from_eden_gpl_3)), 10);
        Ui.sheet(this, Tx.s(R.string.home_frame_generation), box).setPositiveButton(Tx.s(R.string.common_done), null).setOnDismissListener(d -> refreshSettings())
                .setNeutralButton(Tx.s(R.string.home_pick_lossless_dll), (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                    startActivityForResult(i, PICK_DLL);
                }).show();
    }

    private void shareLog() { shareLog((String) null); }

    /** The newest log of a game (titleId), or of any run (null) */
    private void shareLog(String titleId) {
        File log = LogProvider.newest(this, titleId);
        if (log == null) { error(titleId == null ? Tx.s(R.string.home_no_log_yet) : Tx.s(R.string.home_no_log_of_this_game)); return; }
        shareLog(log);
    }

    /** One log file */
    void shareLog(File log) { shareLog(log, null); }

    /** One log file, with its digest beside it when there is one (ExitWatch's notice: the log of the run that ended and LogDigest's text of it) */
    void shareLog(File log, File digest) {
        Uri uri = LogProvider.uri(log);
        Intent send;
        if (digest == null) send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri);
        else {
            Uri du = LogProvider.uri(digest);
            send = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain").putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(List.of(uri, du)));
        }
        send.putExtra(Intent.EXTRA_SUBJECT, "nxnative log " + log.getName());
        ClipData clip = ClipData.newRawUri(log.getName(), uri);
        if (digest != null) clip.addItem(new ClipData.Item(LogProvider.uri(digest)));
        send.setClipData(clip);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share " + log.getName()));
    }

    /** A web page (a rule's doc from ExitWatch's notice) in the phone's browser */
    void openLink(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (android.content.ActivityNotFoundException e) { error(Tx.s(R.string.err_no_app_can_open, url)); }
    }

    void error(String msg) { new PadFocus.Builder(this).setMessage(msg).setPositiveButton(android.R.string.ok, null).show(); }

    private static String padPanelLabel(int v) {
        return Tx.arr(R.array.arr_pad_open)[v] + Tx.s(R.string.game_by_touch_swipe_or_back);
    }
    private static String touchLabel(int m) { return m == TouchControls.MODE_AUTO ? Tx.s(R.string.home_auto_hidden_while_a_controller) : Tx.arr(R.array.arr_touch_mode)[m]; }
    private static String rotationLabel(boolean auto) { return auto ? Tx.s(R.string.home_automatic_the_game_follows_the) : Tx.s(R.string.home_landscape_the_game_window_stays); }
    /** A default's choice in Settings (no "Default" entry: this is the default) */
    private void pick(String title, String[] items, int cur, java.util.function.IntConsumer set) {
        new PadFocus.Builder(this).setTitle(title).setSingleChoiceItems(items, cur, (d, w) -> { set.accept(w); refreshSettings(); d.dismiss(); }).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }
}
