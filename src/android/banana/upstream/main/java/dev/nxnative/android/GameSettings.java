package dev.nxnative.android;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A game's page (MainActivity.gameSettings: ⋯ on its card, or a long press; Back returns to Home): the game's name with Play, then tabs. **Performance** is the game's
 * settings for speed and picture: resolution, upscaler, frame rate cap, GPU driver and the testers' settings; **Game** the profile's options, Game language and
 * controls. All are stored per title ID (Prefs.gameKey,
 * GpuDriver.choice). Each starts as "Default", which follows the main screen's Settings (global), or this phone's recommendation (Recommend); a value set here wins
 * for this game only. The in-game panel's frame rate cap is this game's too. GameActivity reads them at start. **Saves** and **Storage** are the game's
 * actions (MainActivity.gameTab).
 * <p>
 * Performance: a chip with the GPU and the driver the game starts with, Recommended / Custom where the profile recommends settings for this phone, then the
 * sections Picture, Speed and Advanced; Game has Game options and Controls. A phone shows them one under the other, Advanced folded; a wide window (an unfolded foldable, a
 * tablet, landscape) shows a rail of the sections at the left and the one picked at the right. A row is one line of title over a line that says its value (a
 * switch's line says what its state does: Ui.Setting.says).
 */
final class GameSettings {
    private GameSettings() {}

    static final String PERFORMANCE = "performance", GAME = "game", SAVES = "saves", STORAGE = "storage";

    /** The tab, the section the rail shows on a wide window, and whether Advanced is open on a narrow one: kept while the app runs, so the page built again after a choice keeps its place */
    private static String tab = PERFORMANCE;
    private static String shown = "picture";
    private static boolean advancedOpen;
    private static boolean custom;   // the player picked Custom while nothing was changed yet (else the choice follows the changed count)
    private static String customFor;

    /** Opens a game's page (a tap on its tile) at its first tab */
    static void open(MainActivity a, Game g) {
        tab = Profile.missing(g.titleId, g.name) == null ? PERFORMANCE : SAVES;   // a game this app has no profile for has no settings
        a.gameSettings(g);
    }

    /**
     * An optional shader update (Pack.shaderUpdateAvailable): this app version makes its games' shaders in a newer way; the game plays with the ones it has until the player
     * updates them. Shown on every tab of the game's page, under the tabs. When the older library is one the shader layer covers (Pack.shaderUpdateOptional) it is a small
     * row the player can dismiss (kept per game and shader library version, Prefs.shaderUpdateDismissed): the game plays now and what the library lacks is built while it
     * runs. Otherwise (an older ABI's library, other shader tables, a game the layer does not serve, the layer switched off) the card as before, with no way to hide it
     */
    private static void shaderUpdateCard(MainActivity a, LinearLayout content, Game g, Runnable again) {
        // (a .nup picked in place has a stand-in path with nothing at it: PickedNup; a game file that is gone shows as such on its card)
        if (Profile.of(g.titleId) == null || !PickedNup.is(a, g.gameDir) && !new File(g.gameDir).exists()) return;
        if (Ondevice.sampling(new File(g.packDir))) { shaderCheckCard(a, content, g, again); return; }   // the quick check runs or waits: its card (progress, Skip) is the page's shader state
        if (Pack.shadersOnDemand(new File(g.packDir), g.titleId, Prefs.layerEnv(a, g.titleId))) { shadersOnDemandRow(a, content, g, again); return; }
        if (shadersNeeded(new File(g.packDir), g.titleId)) { shadersNeededRow(a, content, g, again); return; }
        // an older library the layer fills: nothing is "old" to the player, the game plays and builds what it lacks; the same row as a game with no library (Build all, the switch)
        if (Pack.shaderLayerFills(new File(g.packDir), g.titleId, Prefs.layerEnv(a, g.titleId))) { shadersOnDemandRow(a, content, g, again); return; }
        if (!Pack.isDump(new File(g.gameDir))) { olderShadersRow(a, content, g, again); return; }   // the update is made on the phone from a dump only
        if (!Pack.shaderUpdateAvailable(new File(g.packDir), g.titleId)) return;
        if (Pack.isDump(new File(g.gameDir)) && Ondevice.sampleSkipHolds(new File(g.packDir)) && Pack.sampledKeepCandidate(new File(g.packDir), g.titleId, Prefs.layerEnv(a, g.titleId)) != null) shaderCheckSkippedRow(a, content, g, again);
        boolean optional = Pack.shaderUpdateOptional(new File(g.packDir), g.titleId, Prefs.layerEnv(a, g.titleId));
        // one small row for every older library the game plays on (same ABI and inputs, or another ABI or tables the host still plays): never a big card that stays on top.
        // Not now hides it until the shader library version changes (the card says "Ready · update"; the game page › Storage › Prepare again updates them)
        if (Prefs.shaderUpdateDismissed(a, g.titleId, BuildConfig.NX_DMSH_VK_CHAIN)) return;
        LinearLayout card = Ui.add(content, Ui.card(a), 12);
        card.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
        boolean fills = Pack.shaderLayerFills(new File(g.packDir), g.titleId, Prefs.layerEnv(a, g.titleId));   // the layer builds what the older library lacks: a small row, Play never held
        card.addView(Ui.text(a, fills ? PrepareText.OLDER_LAYER_TITLE() : Tx.s(R.string.game_shaders_can_be_updated), 14, R.color.bn_text, 700));
        TextView t = Ui.add(card, Ui.small(a, fills ? PrepareText.olderLayerText(optional, true)
                : Tx.s(R.string.game_older_shaders_update_text)), 4);
        t.setTextSize(12);
        // the buttons sit one under the other: side by side "Update shaders" was cut to "Update sh"
        LinearLayout b = Ui.add(card, new LinearLayout(a), 8);
        b.setOrientation(LinearLayout.VERTICAL);
        b.addView(Ui.action(a, 0, Tx.s(R.string.game_update_shaders), Ui.Kind.TONAL, v -> Ui.explain(a, Tx.s(R.string.game_update_shaders_2),
                Tx.s(R.string.game_this_makes_the_whole_shader), null)
                .setPositiveButton(Tx.s(R.string.game_update_shaders), (d, w) -> a.updateShaders(g)).setNegativeButton(Tx.s(R.string.common_cancel), null).show()),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, 6);
        b.addView(Ui.action(a, 0, Tx.s(R.string.game_not_now), Ui.Kind.QUIET, v -> { Prefs.dismissShaderUpdate(a, g.titleId, BuildConfig.NX_DMSH_VK_CHAIN); again.run(); }), lp);
    }

    /**
     * The quick shader check of this game (Ondevice.sampledKeep) runs or waits in the line: a card under the tabs with its state line (the same words as the tile's ring),
     * a bar, and Skip. The game is playable meanwhile. The card follows the check every 2 s while the page shows and builds the page again when the check ends or moves
     */
    private static void shaderCheckCard(MainActivity a, LinearLayout content, Game g, Runnable again) {
        File pack = new File(g.packDir);
        LinearLayout card = Ui.add(content, Ui.card(a), 12);
        card.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
        card.addView(Ui.text(a, Tx.s(R.string.game_check_title), 14, R.color.bn_text, 700));
        Ondevice.SampleView first = Ondevice.sampleView(pack);
        TextView line = Ui.add(card, Ui.small(a, first == null ? "" : first.text), 4);
        line.setTextSize(12);
        TextView detail = Ui.add(card, Ui.small(a, first == null ? "" : first.detail), 2);
        detail.setTextSize(12);
        detail.setVisibility(detail.getText().length() == 0 ? View.GONE : View.VISIBLE);
        ProgressBar bar = Ui.progress(a);
        bar.setMax(1000);
        Ui.add(card, bar, 8, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 8)));
        Ui.add(card, Ui.small(a, Tx.s(R.string.game_check_text)), 8).setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, 8);
        card.addView(Ui.action(a, 0, Tx.s(R.string.game_check_skip), Ui.Kind.QUIET, v -> { Ondevice.sampleSkip(a, pack); again.run(); }), lp);
        final String sig = Ondevice.sampleSignature();
        final boolean[] on = {true};
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (!on[0] || !card.isAttachedToWindow()) return;
                if (!Ondevice.sampleSignature().equals(sig) || !Ondevice.sampling(pack)) { again.run(); return; }   // it ended, was skipped or moved in the line
                Ondevice.SampleView v = Ondevice.sampleView(pack);
                if (v != null) {
                    line.setText(v.text);
                    detail.setText(v.detail);
                    detail.setVisibility(v.detail.isEmpty() ? View.GONE : View.VISIBLE);
                    bar.setIndeterminate(v.running && v.bar == 0);
                    bar.setProgress(v.bar);
                }
                card.postDelayed(this, 2000);
            }
        };
        card.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { on[0] = true; card.postDelayed(tick, 2000); }
            @Override public void onViewDetachedFromWindow(View v) { on[0] = false; card.removeCallbacks(tick); }
        });
        if (first != null) { bar.setIndeterminate(first.running && first.bar == 0); bar.setProgress(first.bar); }
    }

    /** A skipped check (Ondevice.sampleSkipHolds): one small row says so and offers Check again; the 7 days' memory is the check's own (SampleQueue.skipped) */
    private static void shaderCheckSkippedRow(MainActivity a, LinearLayout content, Game g, Runnable again) {
        LinearLayout one = Ui.add(content, Ui.listCard(a), 8);
        one.addView(new Ui.Setting(a, "🔎", Tx.s(R.string.game_check_skipped_title), v -> { Ondevice.sampleAgain(a, new File(g.gameDir), new File(g.packDir), g.titleId); again.run(); })
                .value(Tx.s(R.string.game_check_skipped_text)).chevron(a).view);
    }

    /**
     * The game starts with no shader library to play on and builds its shaders while it runs (Pack.shadersOnDemand): a small dismissible row, like the optional update's,
     * "Shaders: built while playing (N built)" (N: the layer's built.tsv ok rows, LayerPending.built), with the switch Shaders while playing, Build all (Prepare
     * shaders for the whole set, with its time; only for a game the app can prepare, a dump or .nup with its code) and Import a library (a dmsh_vk.lib built on a
     * computer, PackUi.importLibraryPick). Not now folds it into one line until the shader library version changes (Prefs.shaderUpdateDismissed): the state stays in view,
     * and a tap opens it again. Play is never held back
     */
    private static void shadersOnDemandRow(MainActivity a, LinearLayout content, Game g, Runnable again) {
        File layer = new File(new File(g.packDir, "shaders"), LayerDir.NAME);
        int built = LayerPending.built(layer, BuildConfig.NX_DMSH_VK_CHAIN);
        boolean dump = Pack.isDump(new File(g.gameDir));
        // the layer past its quota at the last run (LayerWatch's note, checked against the free space now): no more shaders are built until there is room; never folded away
        long[] full = LayerGuard.full(layer, BuildConfig.NX_DMSH_VK_CHAIN, -1);
        // The layer builds what a game lacks while it plays, so this is one small row (not a card on top of the page): its value says how many are built, a tap opens the
        // switch, Build all and Import a library
        LinearLayout one = Ui.add(content, Ui.listCard(a), 8);
        Ui.Setting row = new Ui.Setting(a, "🔨", Tx.s(R.string.game_shaders), v -> shadersDialog(a, g, dump, again)).chevron(a);
        row.value(full != null ? LayerStatus.storageText(full[0], full[1], false, dump) : Tx.n(R.plurals.game_n_built_while_you_play, (int) Math.min(built, Integer.MAX_VALUE), built));
        one.addView(row.view);
    }

    /** The shader row's choices: the Shaders while playing switch, Build all (a dump or .nup the phone can prepare) and Import a library */
    private static void shadersDialog(MainActivity a, Game g, boolean dump, Runnable again) {
        boolean on = Prefs.layerBuild(a, g.titleId);
        Profile prof = Profile.of(g.titleId);
        java.util.List<String> items = new java.util.ArrayList<>();
        items.add(on ? Tx.s(R.string.game_shaders_while_playing_on_turn) : Tx.s(R.string.game_shaders_while_playing_off_turn));
        if (dump) items.add(PrepareText.buildAllLabel(ShaderLibs.minutes(prof != null ? prof.shaderTotal : 0)));
        items.add(Tx.s(R.string.game_import_a_library));
        new PadFocus.Builder(a).setTitle(Tx.s(R.string.game_shaders)).setMessage(on ? Tx.s(R.string.game_shaders_the_game_lacks_are) : Tx.s(R.string.game_off_a_game_without_a))
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    if (w == 0) { Prefs.layerBuild(a, g.titleId, !on); again.run(); }
                    else if (dump && w == 1) a.prepareShaders(g);
                    else PackUi.importLibraryPick(a, g, again);
                }).setNegativeButton(Tx.s(R.string.common_close), null).show();
    }

    /**
     * A game the phone cannot prepare (not a dump or .nup with its code) that plays on an older shader library (ShaderLibs.playable, or one from before the ABI field): the
     * update cannot be made here, so the row says where it comes from (a computer) and offers Import a library; dismissible as the optional update's row
     */
    private static void olderShadersRow(MainActivity a, LinearLayout content, Game g, Runnable again) {
        File pd = new File(g.packDir);
        if (Pack.libraryProblem(pd, g.titleId) == null || Pack.playableLibrary(pd, g.titleId) == null && Pack.legacyLibrary(pd, g.titleId) == null) return;
        if (Prefs.shaderUpdateDismissed(a, g.titleId, BuildConfig.NX_DMSH_VK_CHAIN)) return;
        LinearLayout card = Ui.add(content, Ui.card(a), 12);
        card.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
        boolean fills = Pack.shaderLayerFills(pd, g.titleId, Prefs.layerEnv(a, g.titleId));
        card.addView(Ui.text(a, fills ? PrepareText.OLDER_LAYER_TITLE() : Tx.s(R.string.game_shaders_of_an_older_version), 14, R.color.bn_text, 700));
        TextView t = Ui.add(card, Ui.small(a, fills ? PrepareText.olderLayerText(Pack.shaderUpdateOptional(pd, g.titleId, Prefs.layerEnv(a, g.titleId)), false) : PrepareText.OLDER_NO_DUMP()), 4);
        t.setTextSize(12);
        LinearLayout b = Ui.add(card, Ui.row(a), 8);
        b.addView(Ui.action(a, 0, Tx.s(R.string.game_import_a_library), Ui.Kind.TONAL, v -> PackUi.importLibraryPick(a, g, again)), Ui.weight());
        LinearLayout.LayoutParams lp = Ui.weight();
        lp.setMarginStart(Ui.dp(a, 8));
        b.addView(Ui.action(a, 0, Tx.s(R.string.game_not_now), Ui.Kind.QUIET, v -> { Prefs.dismissShaderUpdate(a, g.titleId, BuildConfig.NX_DMSH_VK_CHAIN); again.run(); }), lp);
    }

    /**
     * Whether the game's pack is complete but lacks this APK's shader library and has none the host plays (no older one of a playable ABI, none from before the ABI
     * field), and the shader layer does not build them as it plays: Play then waits for shaders (MainActivity's "Shaders needed")
     */
    private static boolean shadersNeeded(File pd, String titleId) {
        try {
            if (Pack.read(pd).unfinishedModule() != null) return false;   // the card says the pack needs preparing: that comes first
        } catch (java.io.IOException e) {
            return false;
        }
        return Pack.libraryProblem(pd, titleId) != null && (Pack.libraryOf(pd) != null || Pack.playableLibrary(pd, titleId) == null && Pack.legacyLibrary(pd, titleId) == null);
    }

    /**
     * "Shaders needed" on the game's page (not dismissible: Play waits for it): why (ShaderState.neededText, the same words as the card's Why?), the switch Shaders while
     * playing when it is what keeps the game from playing now, Prepare shaders for a game the app can prepare, and Import a library
     */
    private static void shadersNeededRow(MainActivity a, LinearLayout content, Game g, Runnable again) {
        File pd = new File(g.packDir);
        boolean dump = Pack.isDump(new File(g.gameDir));
        ShaderState.Off off = layerOff(a, pd, g.titleId);
        LinearLayout card = Ui.add(content, Ui.card(a), 12);
        card.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
        card.addView(Ui.text(a, Tx.s(R.string.game_shaders_needed), 14, R.color.bn_text, 700));
        TextView t = Ui.add(card, Ui.small(a, neededText(pd, g.titleId, off, dump)), 4);
        t.setTextSize(12);
        if (off == ShaderState.Off.SWITCH && Pack.libraryOf(pd) == null) {
            LinearLayout sw = Ui.add(card, Ui.listCard(a), 8);
            sw.addView(layerSwitch(a, g, again).view);
        }
        LinearLayout b = Ui.add(card, Ui.row(a), 8);
        if (dump) b.addView(Ui.action(a, 0, Tx.s(R.string.common_prepare_shaders), Ui.Kind.TONAL, v -> a.prepareShaders(g)), Ui.weight());
        LinearLayout.LayoutParams lp = Ui.weight();
        if (b.getChildCount() > 0) lp.setMarginStart(Ui.dp(a, 8));
        b.addView(Ui.action(a, 0, Tx.s(R.string.game_import_a_library), dump ? Ui.Kind.QUIET : Ui.Kind.TONAL, v -> PackUi.importLibraryPick(a, g, again)), lp);
    }

    /** Game settings › Shaders while playing (Prefs.layerBuild), for a Switch 2 title (the layer serves NVN2 programs only): the page is built again, Play's state changes with it */
    private static Ui.Setting layerSwitch(MainActivity a, Game g, Runnable again) {
        return new Ui.Setting(a, "🔨", Tx.s(R.string.game_shaders_while_playing), null).toggle(a, Prefs.layerBuild(a, g.titleId), (x, on) -> { Prefs.layerBuild(a, g.titleId, on); again.run(); })
                .says(Tx.s(R.string.game_shaders_the_game_lacks_are_2), Tx.s(R.string.game_off_a_game_without_a_2));
    }

    /**
     * Why the shader layer does not let this game play with no library (ShaderState.layerOff): its title, the player's switch (Prefs.layerBuild), a line of its own
     * (Prefs.gameEnv), or no folder to build in (ShaderLibs.layerWritable)
     */
    static ShaderState.Off layerOff(android.content.Context c, File dir, String titleId) {
        return ShaderState.layerOff(titleId, Prefs.layerBuild(c, titleId), Prefs.gameEnv(c, titleId), ShaderLibs.layerWritable(dir, ShaderLibs.tempOf(titleId)));
    }

    /** The "Shaders needed" text of a pack with no library the game plays (ShaderState.neededText; the card's Why? and the page's row): dump: Prepare can start from the game's file */
    static String neededText(File dir, String titleId, ShaderState.Off off, boolean dump) {
        Profile p = Profile.of(titleId);
        int total = p == null ? 0 : p.shaderTotal;
        return ShaderState.neededText(off, Pack.libraryOf(dir) != null, dump, Pack.deltaShaders(dir, titleId), total, ShaderLibs.minutesText(total));
    }

    /** A controller's L1 / R1: the next (+1) or previous (-1) tab of the game's page, round; false when there is nothing to switch to */
    static boolean stepTab(Game g, int delta) {
        String[] keys = Profile.missing(g.titleId, g.name) == null ? new String[] {PERFORMANCE, GAME, SAVES, STORAGE} : new String[] {SAVES, STORAGE};
        int cur = Math.max(0, Arrays.asList(keys).indexOf(tab));
        int next = PadPanel.tab(cur, delta, keys.length);
        if (next == cur) return false;
        tab = keys[next];
        return true;
    }

    /** One section of the page: its rail entry and label, its card of rows, and a small line under the card */
    private static final class Section {
        final String key, emoji, label;
        final LinearLayout rows;
        final List<String> titles = new ArrayList<>();
        String info;

        Section(MainActivity a, String key, String emoji, String label) { this.key = key; this.emoji = emoji; this.label = label; rows = Ui.listCard(a); }

        boolean empty() { return rows.getChildCount() == 0; }

        Ui.Setting add(Ui.Setting s) { rows.addView(s.view); titles.add(s.title.getText().toString()); return s; }
    }

    /** Builds the page into content (Ui.page's column); again builds it once more after a choice (keeping the place), close returns to Home */
    static void build(MainActivity a, LinearLayout outer, Game g, Runnable again, Runnable close) {
        String id = g.titleId;
        if (!id.equals(customFor)) { customFor = id; custom = false; }
        boolean wide = a.getResources().getConfiguration().screenWidthDp >= 600;
        String missing = Profile.missing(id, g.name);
        String[] keys = missing == null ? new String[] {PERFORMANCE, GAME, SAVES, STORAGE} : new String[] {SAVES, STORAGE};
        String[] labels = missing == null ? new String[] {Tx.s(R.string.game_performance), Tx.s(R.string.game_game), Tx.s(R.string.game_saves), Tx.s(R.string.game_storage)} : new String[] {Tx.s(R.string.game_saves), Tx.s(R.string.game_storage)};
        int t = Math.max(0, Arrays.asList(keys).indexOf(tab));
        tab = keys[t];
        final LinearLayout content;
        if (wide) content = split(a, outer, g, missing == null, keys, labels, t, close);   // the game and its tabs at the left, the tab's page at the right
        else {
            topBar(a, outer, g, missing == null, close);
            Ui.add(outer, Ui.tabs(a, labels, t, k -> { tab = keys[k]; a.gameSettingsTop(); }), 4);
            content = outer;
        }
        if (missing != null) Ui.add(content, Ui.small(a, missing), 12);
        if (Edition.offered(g)) {   // a set with a Switch 2 Edition (program 1) whose profile this APK has: which one Play starts (Edition); one save for both
            LinearLayout ed = Ui.add(content, Ui.listCard(a), 12);
            boolean on = Edition.on(a, id);
            Ui.Setting s = new Ui.Setting(a, "🎮", Tx.s(R.string.game_switch_2_edition), null)
                    .value(on ? Tx.s(R.string.game_play_starts_the_switch_2) : Tx.s(R.string.game_play_starts_the_switch_game))
                    .toggle(a, on, (b, x) -> { Edition.set(a, id, x); again.run(); });
            ed.addView(s.view);
        }
        shaderUpdateCard(a, content, g, again);
        if (!tab.equals(PERFORMANCE) && !tab.equals(GAME)) { a.gameTab(content, g, tab.equals(SAVES)); return; }
        boolean perf = tab.equals(PERFORMANCE);
        if (perf) Ui.add(content, Ui.statusChip(a, chipText(a, id)), 12, Ui.wrap());
        TextView hint = Ui.add(content, Ui.small(a, Tx.s(R.string.game_for_this_game_only_default)), 6);
        hint.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0);

        // Recommended settings for this phone (game.tsv recommend rows: Recommend, Prefs.recommended): Recommended / Custom over the recommended values in a line, and on each
        // row of such a setting a tag (Recommended, or Changed with a "Recommended: …" line), kept current by refresh after every toggle. Without a recommendation, no choice
        java.util.Map<String, Ui.Setting> recRows = new java.util.HashMap<>();
        Runnable[] refresh = {() -> {}};
        List<Recommend.State> states = Prefs.recStates(a, id);
        String recLine = Recommend.line(states, (s, v) -> Prefs.recCardName(a, id, s, v));
        if (recLine != null) {
            // the card shows on Performance; on Game it is built off the page, so the rows it tags (Full vegetation) keep their Recommended / Changed tags
            LinearLayout card = Ui.add(perf ? content : new LinearLayout(a), Ui.card(a), 12);
            card.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
            card.addView(Ui.text(a, Tx.s(R.string.game_recommended_for_this_phone), 15, R.color.bn_text, 700));
            Ui.Segmented[] seg = new Ui.Segmented[1];
            seg[0] = Ui.add(card, new Ui.Segmented(a, new String[] {Tx.s(R.string.game_recommended), Tx.s(R.string.game_custom)}, 0, k -> {
                if (k == 1) { custom = true; refresh[0].run(); return; }   // Custom keeps the player's values: nothing to change
                if (Recommend.changedCount(Prefs.recStates(a, id)) == 0) { custom = false; refresh[0].run(); return; }
                new PadFocus.Builder(a).setTitle(Tx.s(R.string.game_title_for, Tx.s(R.string.game_use_recommended), g.name))
                        .setMessage(Tx.s(R.string.game_the_settings_you_changed_for))
                        .setPositiveButton(Tx.s(R.string.game_use_recommended), (x, w) -> { Prefs.backToRecommended(a, id); custom = false; again.run(); })
                        .setNegativeButton(Tx.s(R.string.common_cancel), (x, w) -> seg[0].select(1)).setOnCancelListener(x -> seg[0].select(1)).show();
            }), 10);
            TextView values = Ui.add(card, Ui.body(a, recLine), 8);
            values.setTextSize(14);
            TextView changed = Ui.add(card, Ui.small(a, ""), 2);
            refresh[0] = () -> {
                List<Recommend.State> now = Prefs.recStates(a, id);
                for (Recommend.State st : now) { Ui.Setting r = recRows.get(st.setting); if (r != null) decorate(a, id, r, st); }
                int n = Recommend.changedCount(now);
                String l = Recommend.line(now, (s, v) -> Prefs.recCardName(a, id, s, v)), c = Recommend.changedLine(n);
                if (n > 0) custom = false;   // the changed count decides from here on
                seg[0].select(n > 0 || custom ? 1 : 0);
                if (l != null) values.setText(l);
                changed.setText(c != null ? c + " " + Tx.s(R.string.game_recommended_puts_them_back) : custom ? Tx.s(R.string.game_your_own_values_stay_change) : "");
                changed.setVisibility(changed.length() == 0 ? View.GONE : View.VISIBLE);
            };
        }

        Section picture = new Section(a, "picture", "🖼️", Tx.s(R.string.game_picture)), speed = new Section(a, "speed", "⏱️", Tx.s(R.string.game_speed)),
                options = new Section(a, "options", "🌿", Tx.s(R.string.game_game_options)), controls = new Section(a, "controls", "🕹️", Tx.s(R.string.common_controls)),
                advanced = new Section(a, "advanced", "🧪", Tx.s(R.string.settings_advanced));
        picture.info = Tx.s(R.string.game_the_in_game_panel_changes_2);
        speed.info = Tx.s(R.string.game_the_in_game_panel_changes);
        Profile prof = Profile.of(id);
        if (prof == null || prof.options.stream().noneMatch(o -> o.live)) options.info = Tx.s(R.string.game_these_apply_at_the_next);
        advanced.info = Tx.s(R.string.game_for_testing_and_troubleshooting);

        // ---- Picture ----
        List<ResPresets.Preset> rps = ResPresets.presets(prof);
        boolean simple = Prefs.simpleRes(a, id);   // a game with Resolution presets and the tester rows off (Settings › Help): Resolution's choices alone, no Advanced, Upscaler or Dynamic resolution
        if (!rps.isEmpty()) {   // a profile with res_preset rows: one Resolution (window and 3D scene together, ResPresets); Advanced shows the two settings apart
            String rc = Prefs.resolutionChoice(a, id);
            picture.add(track(recRows, Recommend.RES_PRESET, prepared(new Ui.Setting(a, "🖼️", Tx.s(R.string.common_resolution), v -> presets(a, g, again)), presetValue(a, g, rps, rc, simple)).note(ResPresets.NOTE).chevron(a)));
            if (ResPresets.ADVANCED.equals(rc) && !simple) {
                if (prof.hasResolution()) picture.add(new Ui.Setting(a, "🪟", Tx.s(R.string.game_window), v -> resolution(a, g, again)).value(windowLabel(a, id)).chevron(a).sub());
                if (!SceneRes.presets(prof).isEmpty())
                    picture.add(prepared(new Ui.Setting(a, "🎬", Tx.s(R.string.game_3d_scene), v -> scene(a, g, again)), sceneValue(a, g, SceneRes.presets(prof))).chevron(a).sub());
            }
        } else {
            if (prof != null && prof.hasResolution())   // only where it does something: a profile with window_fn or graphics_scale rows
                picture.add(new Ui.Setting(a, "🖼️", Tx.s(R.string.common_resolution), v -> resolution(a, g, again)).value(windowLabel(a, id)).chevron(a));
            if (!SceneRes.presets(prof).isEmpty())   // a profile with scene_scale rows but no presets: the 3D scene below the window by the game's own palette (SceneRes)
                picture.add(prepared(new Ui.Setting(a, "🎬", Tx.s(R.string.game_3d_scene), v -> scene(a, g, again)), sceneValue(a, g, SceneRes.presets(prof))).chevron(a));
        }
        if (prof != null && prof.renderCfg()) renderRows(a, g, prof, picture, again);   // Resolution, Upscaling quality, Graphics quality instead of Resolution (a render_cfg row)
        if (simple) {   // the Upscaler stays as stored (GameActivity reads the preference); only testers change it for a game with Resolution presets
        } else if (Prefs.dlssGame(id)) {   // a game on the DLSS stand-in (game.tsv dlss_standin): its Upscaler is the stand-in (GSR2's sliders under GSR 2, the sharpness under FSR 1)
            int dm = GameUpscaler.dlssMode(a, id);
            picture.add(track(recRows, Recommend.UPSCALER, new Ui.Setting(a, "🔍", Tx.s(R.string.common_upscaler), v -> dlssMode(a, g, again)).value(shortName(Tx.arr(R.array.arr_dlss_modes)[dm])).chevron(a)));
            if (DlssTune.showsClarity(dm))
                picture.add(new Ui.Setting(a, "🌀", Tx.s(R.string.game_motion_clarity), v -> clarity(a, g, again)).value(dflt(GameUpscaler.ownClarity(a, id), DlssTune.clarityName(GameUpscaler.clarity(a, id)))).chevron(a).sub());
            if (DlssTune.showsSharp(dm))
                picture.add(new Ui.Setting(a, "✨", DlssTune.sharpTitle(dm), v -> dlssSharp(a, g, again)).value(dflt(GameUpscaler.ownDlssSharp(a, id), DlssTune.sharpName(GameUpscaler.dlssSharp(a, id)))).chevron(a).sub());
            // Screen scaling: the present's scaling of the window to the screen (GameUpscaler.screen, DlssTune.SCREEN_NAMES), with Screen sharpness under FSR 1; the in-game panel changes it live
            int sc = GameUpscaler.screen(a, id);
            picture.add(new Ui.Setting(a, "📐", Tx.s(R.string.common_screen_scaling), v -> screenScale(a, g, again)).value(shortName(Tx.arr(R.array.arr_screen_scale)[sc])).chevron(a));
            if (DlssTune.showsScreenSharp(sc))
                picture.add(new Ui.Setting(a, "✨", Tx.s(R.string.common_screen_sharpness), v -> screenSharp(a, g, again)).value(dflt(GameUpscaler.ownSharp(a, id), GameUpscaler.sharpName(a, id))).chevron(a).sub());
        } else {
            picture.add(track(recRows, Recommend.UPSCALER, new Ui.Setting(a, "🔍", Tx.s(R.string.common_upscaler), v -> upscaler(a, g, again)).value(shortName(Tx.arr(R.array.arr_upscaler_modes)[GameUpscaler.mode(a, id)])).chevron(a)));
            if (GameUpscaler.mode(a, id) == GameUpscaler.MODE_FSR)   // only with FSR 1 chosen
                picture.add(new Ui.Setting(a, "✨", Tx.s(R.string.common_fsr_sharpness), v -> sharpness(a, g, again)).value(dflt(GameUpscaler.ownSharp(a, id), GameUpscaler.sharpName(a, id))).chevron(a).sub());
        }
        picture.add(new Ui.Setting(a, "📺", Tx.s(R.string.common_display), v -> display(a, g, again)).value(Tx.arr(R.array.arr_display)[Prefs.stretch(a, id) ? 1 : 0]).chevron(a));
        if (!aspects(prof).isEmpty())   // a profile with a native aspect mod (game.tsv variant rows labelled w:h)
            picture.add(new Ui.Setting(a, "🔳", Tx.s(R.string.game_aspect_ratio), v -> aspect(a, g, again)).value(aspectLabel(a, id)).chevron(a));

        // ---- Speed ----
        if (!Prefs.limited(id) && Prefs.ownRate(a, id)) {   // the host found no limiter it can hold for this game: choices would do nothing (the host's frame limiter holds a limited game)
            Ui.Setting fixed = speed.add(new Ui.Setting(a, "⏱️", Tx.s(R.string.common_frame_rate_cap), null).value(Tx.s(R.string.game_this_game_picks_its_own)));
            fixed.view.setClickable(false);
            fixed.view.setAlpha(0.6f);
        } else {
            Ui.Setting cap = speed.add(track(recRows, Recommend.FPS_CAP, new Ui.Setting(a, "⏱️", Tx.s(R.string.common_frame_rate_cap), v -> cap(a, g, again)).value(capLabel(a, id)).chevron(a)));
            if (Prefs.limited(id) && Prefs.presentOnly(id)) { cap.badge(Tx.s(R.string.game_experimental)); speed.info += " " + Prefs.PRESENT_ONLY_NOTE(); }   // fps_limit_present: the limit paces the presents only
        }
        if (Prefs.steps(id)) {   // a framework of whole steps a frame (fps_steps): Hold 60 fps keeps one step at the cap of 60. The in-game panel changes it live
            Ui.Setting hold = new Ui.Setting(a, "🎯", Tx.s(R.string.game_hold_60_fps), null).toggle(a, Prefs.hold60(a, id), (b, on) -> { Prefs.hold60(a, id, on); refresh[0].run(); });
            hold.says(Tx.s(R.string.game_never_drops_to_30_less), Tx.s(R.string.game_drops_to_30_when_60));
            speed.add(track(recRows, Recommend.HOLD60, hold));
        }
        if (Prefs.fullSpeed(id)) {   // the game's speed follows its measured frame rate (fps_decouple sead), or late frames are skipped (fps_decouple skip); off: as on the console. The in-game panel changes it live
            Ui.Setting full = new Ui.Setting(a, "🏃", Tx.s(R.string.game_full_game_speed), null).toggle(a, Prefs.decouple(a, id), (b, on) -> Prefs.decouple(a, id, on));
            full.says(Tx.s(R.string.game_normal_speed_when_the_frame), Tx.s(R.string.game_slows_down_with_the_frame));
            speed.add(full);
        }
        if (Prefs.drsGame(id) && !simple)   // the host's dynamic resolution slider: testers only for a game with Resolution presets (Quality is the game's own dynamic resolution)
            speed.add(track(recRows, Recommend.DRS, new Ui.Setting(a, "📏", Tx.s(R.string.common_dynamic_resolution), v -> drs(a, g, again)).value(drsValue(Prefs.drs(a, id))).chevron(a)));
        speed.add(track(recRows, Recommend.DRIVER, new Ui.Setting(a, "🎮", Tx.s(R.string.common_gpu_driver), v -> driver(a, g, again)).value(driverShort(a, id)).chevron(a)));

        // ---- Game options: the profile's own switches ----
        if (prof != null && prof.vegetationDetail) {
            Ui.Setting vegetation = new Ui.Setting(a, "🌿", Tx.s(R.string.game_full_vegetation), null)
                    .toggle(a, Prefs.fullVegetation(a, id), (button, on) -> { Prefs.fullVegetation(a, id, on); refresh[0].run(); });
            vegetation.says(Tx.s(R.string.game_all_grass_and_foliage), Tx.s(R.string.game_less_grass_and_foliage_less));
            options.add(track(recRows, Recommend.VEGETATION, vegetation));
        }
        if (Prefs.skipLayersGame(id)) {   // a game whose profile names layers the picture covers anyway (game.tsv skip_layers)
            Ui.Setting hid = new Ui.Setting(a, "🚀", Tx.s(R.string.game_hidden_layers_off), null).toggle(a, Prefs.skipLayers(a, id), (b, on) -> { Prefs.skipLayers(a, id, on); refresh[0].run(); });
            hid.badge(Tx.s(R.string.game_experimental)).says(Tx.s(R.string.game_skips_layers_drawn_over_later), Tx.s(R.string.game_draws_every_layer));
            options.add(track(recRows, Recommend.SKIP_LAYERS, hid));
        }
        if (prof != null && !prof.options.isEmpty()) {
            File packDir = g.packDir != null ? new File(g.packDir) : null;
            java.util.Map<String, Ui.Setting> rowSettings = new java.util.LinkedHashMap<>();
            boolean[] updating = new boolean[1];

            Runnable updateRows = () -> {
                updating[0] = true;
                try {
                    for (Profile.Option opt : prof.options) {
                        Ui.Setting s = rowSettings.get(opt.env);
                        if (s == null) continue;
                        Profile.Option source = Profile.findImpliedBy(prof.options, opt, env -> Prefs.gameOption(a, id, env));
                        boolean supported = opt.isSupported(packDir);
                        s.value(opt.description);   // always what it does; what it needs, or what turned it on, on a line of its own
                        // needs=<marker>: the rewriter's patch of the game's code that Prepare makes (main.fixups.tsv), not the shaders
                        s.status(!supported ? Tx.s(R.string.game_needs_prepare_again_patches_the) : source != null ? Tx.s(R.string.game_turned_on_by, source.label) : null);
                        if (source != null) {
                            s.toggle.setChecked(true);
                            s.toggle.setEnabled(false);
                            s.view.setAlpha(0.6f);
                        } else {
                            s.toggle.setChecked(Prefs.gameOption(a, id, opt.env));
                            s.toggle.setEnabled(true);
                            s.view.setAlpha(1.0f);
                        }
                    }
                } finally {
                    updating[0] = false;
                }
            };

            for (Profile.Option opt : prof.options) {
                Ui.Setting setting = new Ui.Setting(a, optionIcon(opt.env), opt.label, null)
                        .toggle(a, Prefs.gameOption(a, id, opt.env), (button, checked) -> {
                            if (updating[0]) return;
                            Prefs.gameOption(a, id, opt.env, checked);
                            updateRows.run();
                            refresh[0].run();
                        });
                rowSettings.put(opt.env, setting);
                options.add(track(recRows, Recommend.OPTION + opt.env, setting));
            }
            updateRows.run();
        }

        if (Prefs.cheatsGame(id)) CheatsUi.rows(a, g, options::add, again);   // a profile with cheat files (game.tsv cheats): the switch, off by default, and the files
        { Profile mp = Profile.of(id); if (mp != null && mp.mods) ModsUi.row(a, g, options::add, again); }   // game.tsv mods 1: folders of replacement files over the RomFS
        if (LinkPlay.has(id))
            options.add(new Ui.Setting(a, "🔗", Tx.s(R.string.game_link_play), v -> LinkPlay.dialog(a, g, again)).value(LinkPlay.label(a, id)).chevron(a));
        options.add(Covers.row(a, g, again));
        options.add(new Ui.Setting(a, "🌐", Tx.s(R.string.game_game_language), v -> language(a, g, again)).value(GameLanguage.label(Prefs.gameLang(a, id), phoneTag())).chevron(a));   // not a tester's knob: with the game's options, never folded
        if (prof != null && prof.timeOfDay)   // the one global setting (Prefs.timeOverride → DM_TIME), here for a game whose day/night cycle follows the user clock
            options.add(new Ui.Setting(a, "🕒", Tx.s(R.string.common_time_of_day), v -> TimeOfDay.dialog(a, again)).value(TimeOfDay.label(a)).chevron(a));

        // ---- Controls ----
        controls.add(new Ui.Setting(a, "📳", Tx.s(R.string.common_rumble), v -> ownChoice(a, g, Tx.s(R.string.common_rumble), new String[] {Tx.s(R.string.game_on), Tx.s(R.string.game_off)}, Prefs.ownRumble(a, id) ? (Prefs.rumble(a, id) ? 0 : 1) : -1,
                Prefs.rumble(a) ? Tx.s(R.string.game_on) : Tx.s(R.string.game_off), w -> Prefs.rumbleOwn(a, id, w < 0 ? null : w == 0), again))
                .value(ownLabel(Prefs.ownRumble(a, id), Prefs.rumble(a, id) ? Tx.s(R.string.game_on) : Tx.s(R.string.game_off))).chevron(a));
        controls.add(new Ui.Setting(a, "🎮", Tx.s(R.string.common_on_screen_controls), v -> ownChoice(a, g, Tx.s(R.string.common_on_screen_controls), Tx.arr(R.array.arr_touch_mode), TouchControls.ownMode(a, id) ? TouchControls.mode(a, id) : -1,
                Tx.arr(R.array.arr_touch_mode)[TouchControls.mode(a)], w -> TouchControls.mode(a, id, w < 0 ? null : w), again))
                .value(ownLabel(TouchControls.ownMode(a, id), Tx.arr(R.array.arr_touch_mode)[TouchControls.mode(a, id)])).chevron(a));
        controls.add(new Ui.Setting(a, "🔄", Tx.s(R.string.common_screen_rotation), v -> ownChoice(a, g, Tx.s(R.string.common_screen_rotation), Tx.arr(R.array.arr_rotation), Prefs.ownRotationAuto(a, id) ? (Prefs.rotationAuto(a, id) ? 1 : 0) : -1,
                Tx.arr(R.array.arr_rotation)[Prefs.rotationAuto(a) ? 1 : 0], w -> Prefs.rotationAuto(a, id, w < 0 ? null : w == 1), again))
                .value(ownLabel(Prefs.ownRotationAuto(a, id), Tx.arr(R.array.arr_rotation)[Prefs.rotationAuto(a, id) ? 1 : 0])).chevron(a));
        controls.add(new Ui.Setting(a, "🎛️", Tx.s(R.string.game_controller_panel), v -> ownChoice(a, g, Tx.s(R.string.common_open_the_in_game_panel), Tx.arr(R.array.arr_pad_open), Prefs.ownPanelPad(a, id) ? Prefs.panelPad(a, id) : -1,
                Tx.arr(R.array.arr_pad_open)[Prefs.panelPad(a)], w -> Prefs.panelPad(a, id, w < 0 ? null : w), again))
                .value(ownLabel(Prefs.ownPanelPad(a, id), Tx.arr(R.array.arr_pad_open)[Prefs.panelPad(a, id)])).chevron(a));
        controls.add(new Ui.Setting(a, "🕹️", Tx.s(R.string.common_button_mapping), v -> PadMapUi.forGame(a, g, again)).value(PadMapUi.label(a, id)).chevron(a));
        controls.add(new Ui.Setting(a, "🎯", Tx.s(R.string.game_motion_sensitivity), v -> motionSens(a, g, again)).value(Prefs.MOTION_NAMES[Prefs.motionSens(a, id)]).note(Tx.s(R.string.game_how_far_the_phone_s)).chevron(a));
        controls.add(new Ui.Setting(a, "🅰️", Tx.s(R.string.game_xbox_button_labels), v -> xbox(a, g, again)).value(Prefs.xboxLabel(Prefs.xboxButtons(a, id))).chevron(a));

        // ---- Advanced: for testers ----
        advanced.add(new Ui.Setting(a, "⏲️", Tx.s(R.string.game_gpu_timing), v -> gpuTiming(a, g, again)).value(gpuTsLabel(a, id)).note(Tx.s(R.string.game_what_the_game_s_gpu)).chevron(a));
        // Speed-ups that pay off most on Qualcomm's driver (fewer draws for the GPU, less CPU a render pass); off by default (with either on, a tester lost the GPU on Adreno 830
        // with Turnip in 1-2 of 5 starts: open), each one can be turned on alone
        advanced.add(new Ui.Setting(a, "🧩", Tx.s(R.string.game_instanced_draws), null).toggle(a, Prefs.instancedDraws(a, id), (b, on) -> Prefs.instancedDraws(a, id, on))
                .says(Tx.s(R.string.game_repeated_meshes_drawn_as_one), Tx.s(R.string.game_one_draw_per_mesh)));
        advanced.add(new Ui.Setting(a, "🧱", Tx.s(R.string.game_classic_render_passes), null).toggle(a, Prefs.classicPasses(a, id), (b, on) -> Prefs.classicPasses(a, id, on))
                .says(Tx.s(R.string.game_less_cpu_a_pass_on), Tx.s(R.string.game_dynamic_rendering)));
        advanced.add(new Ui.Setting(a, "⚡", Tx.s(R.string.game_fast_fp16_shader_math), null).toggle(a, Prefs.fp16(a, id), (b, on) -> Prefs.fp16(a, id, on))
                .says(Tx.s(R.string.game_fp16_on_turnip_faster), Tx.s(R.string.game_fp16_default_precision)));
        advanced.add(new Ui.Setting(a, "🧵", Tx.s(R.string.game_background_pipelines), null).toggle(a, Prefs.psoAsync(a, id), (b, on) -> Prefs.psoAsync(a, id, on))
                .says(Tx.s(R.string.game_a_short_pop_in_not), Tx.s(R.string.game_made_when_first_drawn_may)));
        if (ShaderLibs.layerCovers(id, null)) advanced.add(layerSwitch(a, g, again));   // (a Switch 2 title: the layer serves NVN2 programs only)
        advanced.add(new Ui.Setting(a, "💾", Tx.s(R.string.game_ram_saver), null).toggle(a, Prefs.ramSaver(a, id), (b, on) -> { Prefs.ramSaver(a, id, on); if (!on) Prefs.ramSaverStrong(a, id, false); })
                .says(Tx.s(R.string.game_texture_data_in_a_file), Tx.s(R.string.game_texture_data_in_compressed_ram)));
        // The second level: the host reads DM_VK_RAM_SAVER=2 (GameActivity). It includes the first, so turning it on turns RAM saver on (the stored "ram_saver" stays a plain boolean)
        advanced.add(new Ui.Setting(a, "🗜️", Tx.s(R.string.game_ram_saver_strong), null).toggle(a, Prefs.ramSaverStrong(a, id), (b, on) -> { Prefs.ramSaverStrong(a, id, on); if (on) Prefs.ramSaver(a, id, true); })
                .says(Tx.s(R.string.game_ram_saver_strong_on), Tx.s(R.string.game_ram_saver_strong_off)));
        advanced.add(new Ui.Setting(a, "⚡", Tx.s(R.string.common_use_fast_cores), null).toggle(a, Prefs.pinThreads(a, id), (b, on) -> Prefs.pinThreads(a, id, on))
                .says(Tx.s(R.string.game_busy_threads_off_the_small), Tx.s(R.string.game_android_places_the_threads)));
        advanced.add(new Ui.Setting(a, "⏸️", Tx.s(R.string.game_pause_on_return), null).toggle(a, Prefs.pauseOnReturn(a, id), (b, on) -> Prefs.pauseOnReturn(a, id, on))
                .says(Tx.s(R.string.game_back_from_the_background_paused), Tx.s(R.string.game_back_from_the_background_runs)));
        Ui.Setting advRow = new Ui.Setting(a, "🛠️", Tx.s(R.string.game_advanced_switches), null).chevron(a);   // every switch for this game, by area, with search (AdvancedPage.java)
        advRow.view.setOnClickListener(v -> AdvancedPage.show(a, id, g.name, again));
        advRow.value(AdvancedSettings.count(a, id) == 0 ? Tx.s(R.string.common_search_every_switch_each_starts) : Tx.n(R.plurals.common_n_set, AdvancedSettings.count(a, id), AdvancedSettings.count(a, id)));
        advanced.add(advRow);
        String env = Prefs.gameEnv(a, id);
        int envLines = env.isEmpty() ? 0 : env.split("\n").length;
        advanced.add(new Ui.Setting(a, "📝", Tx.s(R.string.game_extra_environment), v -> env(a, g, again))
                .value(envLines == 0 ? Tx.s(R.string.game_none) : Tx.n(R.plurals.game_n_lines, envLines, envLines, env.replace('\n', ' '))).chevron(a));

        Section[] all = perf ? new Section[] {picture, speed, advanced} : new Section[] {options, controls};   // Performance: what changes speed and picture; Game: the game's own options, language and controls
        View reset = recLine != null ? null : Ui.button(a, Tx.s(R.string.game_reset_to_defaults), Ui.Kind.SOFT, v -> new PadFocus.Builder(a).setTitle(Tx.s(R.string.game_title_for, Tx.s(R.string.game_reset_to_defaults), g.name))
                .setMessage(Tx.s(R.string.game_the_game_will_follow_global))
                .setPositiveButton(Tx.s(R.string.game_reset), (x, w) -> { Prefs.resetGame(a, id); again.run(); }).setNegativeButton(Tx.s(R.string.common_cancel), null).show());
        column(a, content, all, reset);
        refresh[0].run();   // the tags, the Recommended: lines and the choice's changed line
    }

    /** A phone: the sections one under the other, each a label over its card and its line; Advanced folded under its label until opened (Show / Hide) */
    private static void column(MainActivity a, LinearLayout content, Section[] all, View reset) {
        for (Section s : all) {
            if (s.empty()) continue;
            if (!s.key.equals("advanced")) {
                Ui.add(content, Ui.section(a, s.label), 20);
                Ui.add(content, s.rows, 6);
                if (s.info != null) info(a, content, s.info);
                continue;
            }
            LinearLayout head = Ui.row(a);   // its label and Show / Hide: the whole line is the touch target
            head.setMinimumHeight(Ui.dp(a, 44));
            head.setBackground(Ui.ripple(a, Ui.round(android.graphics.Color.TRANSPARENT, Ui.dp(a, 12), 0, 0)));
            head.addView(Ui.section(a, s.label), Ui.weight());
            TextView act = Ui.text(a, "", 13, R.color.bn_accent, 700);
            act.setPadding(Ui.dp(a, 8), 0, Ui.dp(a, 4), 0);
            head.addView(act);
            ImageView ch = new ImageView(a);
            ch.setImageDrawable(Ui.icon(a, R.drawable.ic_chevron, R.color.bn_accent));
            ch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            head.addView(ch, new LinearLayout.LayoutParams(Ui.dp(a, 20), Ui.dp(a, 20)));
            head.setClickable(true);
            head.setFocusable(true);
            Ui.add(content, head, 12);
            TextView sum = info(a, content, s.info + " " + String.join(", ", s.titles) + ".");
            Ui.add(content, s.rows, 6);
            Runnable show = () -> {
                s.rows.setVisibility(advancedOpen ? View.VISIBLE : View.GONE);
                sum.setText(advancedOpen ? s.info : s.info + " " + String.join(", ", s.titles) + ".");
                act.setText(advancedOpen ? Tx.s(R.string.common_hide) : Tx.s(R.string.game_show_n, s.titles.size()));
                ch.setRotation(advancedOpen ? -90 : 90);
                head.setContentDescription(advancedOpen ? Tx.s(R.string.game_hide_advanced) : Tx.n(R.plurals.game_show_advanced_n_settings, s.titles.size(), s.titles.size()));
            };
            head.setOnClickListener(v -> { advancedOpen = !advancedOpen; show.run(); });
            show.run();
        }
        if (reset != null) Ui.add(content, reset, 20, Ui.wrap());
    }

    /**
     * The top bar of a game's page, wide or narrow: Back, the game's picture and its name (with its version and publisher once read) in 48 dp; on a narrow window
     * Play ends the bar (a wide window has it in the left column)
     */
    private static void topBar(MainActivity a, LinearLayout outer, Game g, boolean play, Runnable close) {
        LinearLayout bar = Ui.row(a);
        bar.addView(Ui.back(a, v -> close.run()), new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44)));
        TextView tile = Ui.tile(a, g.name, 40);
        tile.setTextSize(18);
        ((LinearLayout.LayoutParams) tile.getLayoutParams()).setMarginStart(Ui.dp(a, 12));
        bar.addView(tile);
        LinearLayout names = new LinearLayout(a);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(Ui.dp(a, 10), 0, 0, 0);
        TextView name = Ui.text(a, g.name, play ? 16 : 17, R.color.bn_text, 800);
        name.setMaxLines(play ? 2 : 1);   // Play ends a narrow bar: the name may take two lines
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setAccessibilityHeading(true);
        names.addView(name);
        TextView meta = Ui.small(a, "");
        meta.setTextSize(11);
        meta.setSingleLine(true);
        meta.setVisibility(View.GONE);
        names.addView(meta);
        bar.addView(names, Ui.weight());
        if (play) {   // Play: a round banana button, so the name keeps the room
            ImageView b = new ImageView(a);
            b.setImageDrawable(Ui.icon(a, R.drawable.ic_play, R.color.bn_on_banana));
            b.setPadding(Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 6), Ui.dp(a, 10));   // the triangle looks centred a little right of its box
            b.setBackground(Ui.round(Ui.col(a, R.color.bn_banana), Ui.dp(a, 999), 0, 0));
            b.setContentDescription(Tx.s(R.string.common_play));
            b.setTooltipText(Tx.s(R.string.common_play));
            b.setClickable(true);
            b.setFocusable(true);
            b.setOnClickListener(v -> a.launch(g));
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44));
            pl.setMarginStart(Ui.dp(a, 8));
            bar.addView(b, pl);
        }
        outer.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 48)));
        boolean ownCover = Covers.show(tile, g);   // the player's own cover wins over the game's icon
        GameMeta.loadControl(new File(g.gameDir), Edition.control(a, g), m -> {
            if (m.icon != null && !ownCover) Ui.tileIcon(tile, m.icon);
            String l = m.line();
            if (l != null) { meta.setText(l); meta.setVisibility(View.VISIBLE); }
        });
    }

    /**
     * A wide window: a left column with Back and Play, the game's picture, name and version, and the tabs as a list; the right pane (MainActivity.newPane, which
     * scrolls on its own) is returned for the tab's page
     */
    private static LinearLayout split(MainActivity a, LinearLayout outer, Game g, boolean play, String[] keys, String[] labels, int sel, Runnable close) {
        topBar(a, outer, g, false, close);   // Back, the game's picture and its name; under it the left column and the page
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.HORIZONTAL);
        Ui.add(outer, body, 8, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout left = new LinearLayout(a);
        left.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView ls = new android.widget.ScrollView(a);
        ls.setVerticalScrollBarEnabled(false);
        ls.addView(left);
        body.addView(ls, new LinearLayout.LayoutParams(Ui.dp(a, 180), ViewGroup.LayoutParams.MATCH_PARENT));
        if (play) {
            android.widget.Button b = Ui.button(a, Tx.s(R.string.common_play), Ui.Kind.PRIMARY, v -> a.launch(g));
            b.setCompoundDrawablesRelativeWithIntrinsicBounds(Ui.icon(a, R.drawable.ic_play, R.color.bn_on_banana), null, null, null);
            b.setCompoundDrawablePadding(Ui.dp(a, 4));
            left.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 44)));
        }
        for (int k = 0; k < keys.length; k++) {
            int i2 = k;
            String e = keys[k].equals(PERFORMANCE) ? "\u26A1" : keys[k].equals(GAME) ? "\uD83C\uDFAE" : keys[k].equals(SAVES) ? "\uD83D\uDCBE" : "\uD83D\uDCE6";
            TextView n = Ui.navItem(a, e, labels[k], k == sel, v -> { tab = keys[i2]; a.gameSettingsTop(); });
            n.setMinHeight(Ui.dp(a, 38));
            Ui.add(left, n, k == 0 ? 10 : 0);
        }
        return a.newPane(body);
    }

    /** The small line under a section's card */
    private static TextView info(MainActivity a, LinearLayout parent, String s) {
        TextView t = Ui.add(parent, Ui.small(a, s), 6);
        t.setTextSize(12);
        t.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0);
        return t;
    }

    /** A profile option's tile, by what its switch's name says it touches (RES: the picture's size, POOL / MEM: memory, FPS / RATE: the frame rate); a plain slider otherwise */
    static String optionIcon(String env) {
        String e = env.toUpperCase(java.util.Locale.ROOT);
        return e.contains("RES") ? "🔭" : e.contains("POOL") || e.contains("MEM") ? "🧠" : e.contains("FPS") || e.contains("RATE") ? "⏩" : "🎚️";
    }

    /** A row whose value may end in "· needs Prepare again" (presetValue, sceneValue): the value alone, and why on a line of its own: the 3D scene sizes are made from the game's files at Prepare */
    private static Ui.Setting prepared(Ui.Setting s, String v) {
        String tail = Tx.s(R.string.game_needs_prepare_again_tail);
        boolean need = v.endsWith(tail);
        s.value(need ? v.substring(0, v.length() - tail.length()) : v);
        return s.status(need ? Tx.s(R.string.game_needs_prepare_again_makes_this) : null);
    }

    /** Dynamic resolution's value: Off, or the range the render size moves in (Prefs.DRS_NAMES: the lowest size, as % of the window; always up to 100 %) */
    static String drsValue(int i) { return i <= 0 ? Tx.s(R.string.game_off) : Tx.s(R.string.game_drs_between, Prefs.DRS_NAMES[i]); }

    /** A list's choice without its explanation in brackets, as a row's value ("FSR 1 (sharp, fast)" reads "FSR 1"); the list shows it whole */
    static String shortName(String s) { int i = s.indexOf(" ("); return i > 0 ? s.substring(0, i) : s; }

    /** The GPU driver row's value: System, or the installed driver's name and version */
    /** Titles that run much slower on a Mesa (Turnip) driver than on Qualcomm's: Ocarina of Time, 1.5-2x slower in the phone measurements of 2026-11-12 (dm/games/0400486024DEC000/README.md "Recommended
     *  settings on Qualcomm phones"). Per game on purpose: a warning, not a block; other games are not compared */
    private static final java.util.Set<String> TURNIP_SLOW = java.util.Collections.singleton("0400486024DEC000");
    static String TURNIP_SLOW_NOTE() { return Tx.s(R.string.game_turnip_slow_note); }
    static boolean slowOnTurnip(MainActivity a, String id) { GpuDriver.Info i = GpuDriver.selected(a, id); return i != null && TURNIP_SLOW.contains(id) && GpuDriver.isMesa(i); }

    static String driverShort(MainActivity a, String id) { GpuDriver.Info i = GpuDriver.selected(a, id); return i == null ? Tx.s(R.string.common_system) : i.label(); }

    /** The Performance tab's chip: the GPU (GLES's renderer, GpuGl) and the driver this game starts with (an installed one's name, else the system driver's from the kept facts) */
    static String chipText(MainActivity a, String id) {
        GpuDriver.Info i = GpuDriver.selected(a, id);
        String d = i != null ? i.label() + (slowOnTurnip(a, id) ? Tx.s(R.string.game_low_performance_here) : "") : Readiness.driverWord(Prefs.facts(a, Recommend.SYSTEM));
        if (d == null) d = Tx.s(R.string.game_system_driver_2);
        String n = GpuGl.name();
        return n != null ? n + " · " + d : d;
    }

    /** The title ID whose last pick here needs another render target pool than the setting before it (Profile.renderRestartNote): the next drawing of its sheet shows the line once */
    private static String renderNoteFor;

    /**
     * Resolution, Upscaling quality and Graphics quality of a game whose profile has a render_cfg row: three rows, each opening its radio choices as Display does, labels from the
     * string resources (RenderLabels), in the Picture section. A pick stores all three (Prefs.render) for the next start. One line, "This change will apply on game restart.", leads the
     * section's line under its card only right after a pick that needs another render target pool size than the stored setting did
     */
    private static void renderRows(MainActivity a, Game g, Profile p, Section s, Runnable again) {
        String id = g.titleId;
        Profile.Render cur = Prefs.render(a, p, id);
        RenderLabels l = new RenderLabels(a);
        String[] outs = p.renderOutputs(), outLabels = new String[outs.length], pctLabels = new String[Profile.RENDER_PCTS.length];
        for (int i = 0; i < outs.length; i++) outLabels[i] = l.out(p, outs[i]);
        for (int i = 0; i < pctLabels.length; i++) pctLabels[i] = l.pct(Profile.RENDER_PCTS[i]);
        java.util.function.Consumer<Profile.Render> pick = r -> {
            renderNoteFor = p.renderRestartNote(r, null, cur, Prefs.lowRam(a)) ? id : null;
            Prefs.render(a, id, r);
            again.run();
        };
        s.add(new Ui.Setting(a, "🖼️", l.resTitle, v -> radio(a, l.resTitle + " · " + g.name, outLabels, Arrays.asList(outs).indexOf(cur.out),
                w -> pick.accept(new Profile.Render(outs[w], cur.pct, cur.quality)))).value(l.out(p, cur.out)).chevron(a));
        int pi = Math.max(0, indexOfPct(cur.pct));
        s.add(new Ui.Setting(a, "🔍", l.upscaleTitle, v -> radio(a, l.upscaleTitle + " · " + g.name, pctLabels, pi,
                w -> pick.accept(new Profile.Render(cur.out, Profile.RENDER_PCTS[w], cur.quality)))).value(pctLabels[pi]).chevron(a));
        s.add(new Ui.Setting(a, "✨", l.gfxTitle, v -> radio(a, l.gfxTitle + " · " + g.name, l.gfx, cur.quality ? 0 : 1,
                w -> pick.accept(new Profile.Render(cur.out, cur.pct, w == 0)))).value(l.gfx[cur.quality ? 0 : 1]).chevron(a));
        if (id.equals(renderNoteFor)) { s.info = s.info == null ? l.restartNote : l.restartNote + " " + s.info; renderNoteFor = null; }   // first in the line under the card
    }
    static int indexOfPct(int pct) { for (int i = 0; i < Profile.RENDER_PCTS.length; i++) if (Profile.RENDER_PCTS[i] == pct) return i; return -1; }
    /** "As in Settings: x" while the game has no value of its own, else the value */
    static String ownLabel(boolean own, String value) { return own ? value : Tx.s(R.string.game_as_in_settings, value); }

    /** A choice list whose first entry is Default (Settings: x), which removes the game's own value (picked gets -1); checked -1: no own value */
    private static void ownChoice(MainActivity a, Game g, String title, String[] names, int checked, String settingsValue, java.util.function.IntConsumer picked, Runnable done) {
        String[] labels = new String[names.length + 1];
        labels[0] = Tx.s(R.string.game_default_settings_value, settingsValue);
        System.arraycopy(names, 0, labels, 1, names.length);
        new PadFocus.Builder(a).setTitle(title + " · " + g.name).setSingleChoiceItems(labels, checked + 1, (x, w) -> { picked.accept(w - 1); x.dismiss(); done.run(); })
                .setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }

    private static void radio(MainActivity a, String title, String[] labels, int checked, java.util.function.IntConsumer picked) {
        new AlertDialog.Builder(a).setTitle(title).setSingleChoiceItems(labels, checked, (x, w) -> { x.dismiss(); picked.accept(w); }).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }

    static String windowLabel(MainActivity a, String id) {
        String w = Prefs.gameWindow(a, id);
        Profile p = Profile.of(id);
        if (p != null && p.modeOf(w) >= 0) return p.modeLabel(p.modeOf(w));   // the two-choice form (Profile.modes): Docked or Handheld
        String l = Prefs.windowName(w, false);
        return l;
    }

    static String capLabel(MainActivity a, String id) {
        String l = Prefs.limited(id) ? Prefs.paceLabel(Prefs.paceCap(a, id)) : MainActivity.capLabel(Prefs.fpsCap(a, id));
        return l;
    }

    /** Registers a row of a recommended setting: refresh gives it its tag and its "Recommended: …" line */
    private static Ui.Setting track(java.util.Map<String, Ui.Setting> rows, String setting, Ui.Setting s) { rows.put(setting, s); return s; }

    /** A recommended setting's row: the tag before its value (Recommend.tag: Recommended, or Changed in the warning tint) and, when changed, a muted "Recommended: <short name>" line */
    static void decorate(MainActivity a, String id, Ui.Setting row, Recommend.State st) {
        String t = Recommend.tag(st);
        row.tag(a, t, st.changed());
        row.note(st.changed() ? Tx.s(R.string.game_recommended_colon, Prefs.recName(a, id, st.setting, st.recommended)) : null);
    }

    static final String[] GPU_TS = {"segment", "submission"};

    private static String gpuTsName(String mode) { return mode.equals("segment") ? "Per segment" : "Per submission"; }

    static String gpuTsLabel(MainActivity a, String id) {
        String m = Prefs.gpuTs(a, id);
        Profile p = Profile.of(id);
        return m != null ? gpuTsName(m) : Tx.s(R.string.game_game_default_dot, gpuTsName(p == null ? "submission" : p.gpuTs));
    }

    /**
     * GPU timing: what the game's GPU timestamps measure (DM_NVN_GPU_TS; game.tsv gpu_timestamps is the default). Per segment: each timestamp gets the GPU time of
     * its place in the submission, so a game's dynamic resolution and frame pacing see the real GPU time; per submission: every timestamp of a submission gets
     * its completion time (the older behaviour)
     */
    private static void gpuTiming(MainActivity a, Game g, Runnable done) {
        Profile p = Profile.of(g.titleId);
        String[] labels = {Tx.s(R.string.game_game_default_name, gpuTsName(p == null ? "submission" : p.gpuTs)), Tx.s(R.string.game_per_segment), Tx.s(R.string.game_per_submission)};
        String m = Prefs.gpuTs(a, g.titleId);
        int checked = m == null ? 0 : 1 + Math.max(0, Arrays.asList(GPU_TS).indexOf(m));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] d = new AlertDialog[1];
        box.addView(Ui.small(a, Tx.s(R.string.game_gpu_timing_explain)));
        android.widget.RadioGroup rg = new android.widget.RadioGroup(a);
        for (int k = 0; k < labels.length; k++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(a);
            rb.setText(labels[k]);
            rb.setId(k + 1);
            rg.addView(rb);
        }
        rg.check(checked + 1);
        rg.setOnCheckedChangeListener((x, w) -> {
            Prefs.gpuTs(a, g.titleId, w <= 1 ? null : GPU_TS[w - 2]);
            d[0].dismiss();
            done.run();
        });
        Ui.add(box, rg, 8);
        d[0] = Ui.sheet(a, Tx.s(R.string.game_title_for, Tx.s(R.string.game_gpu_timing), g.name), box).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }

    /** The pack's scene_res rows (Pack.scenes), empty without a pack or from one that cannot be read */
    private static java.util.Map<String, String> packScenes(Game g) {
        File pd = g.packDir != null ? new File(g.packDir) : null;
        try { return pd != null ? Pack.read(pd).scenes : java.util.Map.of(); } catch (java.io.IOException | RuntimeException e) { return java.util.Map.of(); }
    }

    /** Game settings' value of Resolution: the preset in force with its window and 3D scene, Advanced with its two values; and Prepare again when the pack lacks the overlay */
    private static String presetValue(MainActivity a, Game g, List<ResPresets.Preset> ps, String rc, boolean simple) {
        if (ResPresets.ADVANCED.equals(rc)) return simple ? "Custom setup (window and 3D scene set apart)" : Tx.s(R.string.game_advanced_window_and_3d_scene);
        ResPresets.Preset p = ResPresets.of(ps, rc);
        if (p == null) return "";
        String l = ResPresets.label(ps, rc);
        File pd = g.packDir != null ? new File(g.packDir) : null;
        return !p.overlay() || SceneRes.overlay(packScenes(g), pd, p.id) != null ? l : l + Tx.s(R.string.game_needs_prepare_again_tail);
    }

    /**
     * Resolution: the profile's presets (ResPresets), each setting the window and the 3D scene together and labelled by what the player gets (its second line: the window and
     * the scene's size, "about" until measured), and Advanced, which keeps Window and 3D scene apart for testers (Game settings then shows both). From the next start; the
     * in-game panel changes the window at once
     */
    private static void presets(MainActivity a, Game g, Runnable done) {
        List<ResPresets.Preset> ps = ResPresets.presets(Profile.of(g.titleId));
        if (ps.isEmpty()) return;
        java.util.Map<String, String> rows = packScenes(g);
        File pd = g.packDir != null ? new File(g.packDir) : null;
        String recPreset = Prefs.rec(a, g.titleId, Recommend.RES_PRESET);   // this phone's recommended preset (Prefs.recommended), named in its line
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] d = new AlertDialog[1];
        boolean simple = Prefs.simpleRes(a, g.titleId);
        box.addView(Ui.small(a, ResPresets.NOTE + " " + Tx.s(R.string.game_window_note_tail)));
        android.widget.RadioGroup rg = new android.widget.RadioGroup(a);
        for (int k = 0; k <= (simple ? ps.size() - 1 : ps.size()); k++) {   // the tester rows add Advanced after the presets
            android.widget.RadioButton rb = new android.widget.RadioButton(a);
            if (k < ps.size()) {
                ResPresets.Preset p = ps.get(k);
                boolean missing = p.overlay() && SceneRes.overlay(rows, pd, p.id) == null;
                rb.setText(ResPresets.itemText(p, k == 0, p.id.equals(recPreset)) + (missing ? Tx.s(R.string.game_needs_prepare_again) : ""));
            } else rb.setText(Tx.s(R.string.game_advanced_for_testers_nwindow_and));
            rb.setId(k + 1);
            rg.addView(rb);
        }
        String rc = Prefs.resolutionChoice(a, g.titleId);
        ResPresets.Preset cur = ResPresets.of(ps, rc);
        if (cur != null) rg.check(ps.indexOf(cur) + 1);
        else if (!simple) rg.check(ps.size() + 1);   // Advanced; a player's saved custom setup shows none checked
        rg.setOnCheckedChangeListener((x, w) -> {
            Prefs.resPreset(a, g.titleId, w <= ps.size() ? ps.get(w - 1).id : ResPresets.ADVANCED);
            d[0].dismiss();
            done.run();
        });
        Ui.add(box, rg, 8);
        d[0] = Ui.sheet(a, Tx.s(R.string.game_title_for, Tx.s(R.string.common_resolution), g.name), box).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }

    /** Game settings' value of Advanced › 3D scene: the choice's label, and when the pack has no overlay for it (made before the option, or the pack step refused the palette), what to do */
    private static String sceneValue(MainActivity a, Game g, List<SceneRes.Preset> ps) {
        String choice = Prefs.sceneRes(a, g.titleId), label = SceneRes.label(ps, choice);
        SceneRes.Preset p = SceneRes.of(ps, choice);
        if (p == null || p.arg()) return label;   // game arguments (scene_arg) need nothing from Prepare
        File pd = g.packDir != null ? new File(g.packDir) : null;
        return SceneRes.overlay(ps, packScenes(g), pd, choice) != null ? label : label + Tx.s(R.string.game_needs_prepare_again_tail);
    }

    /**
     * Advanced › 3D scene (or 3D scene, for a game with scene_scale rows and no presets): the 3D scene drawn at a scale of the window crop, by the game's own dynamic resolution palette rewritten when the game was prepared (dm/tools/scene_res.py,
     * DM_ROMFS_OVERLAY); the crop, the menus and the upscaler stay as they are. Game default: the game's own palette. From the next start. For a game with scene_arg rows, the
     * scale the game's engine takes as arguments (DM_GAME_ARGS_ADD), nothing made at Prepare
     */
    private static void scene(MainActivity a, Game g, Runnable done) {
        List<SceneRes.Preset> ps = SceneRes.presets(Profile.of(g.titleId));
        if (ps.isEmpty()) return;
        String[] labels = new String[ps.size() + 1];
        labels[0] = Tx.s(R.string.game_game_default_its_own_dynamic);
        for (int k = 0; k < ps.size(); k++) labels[k + 1] = ps.get(k).label;
        SceneRes.Preset cur = SceneRes.of(ps, Prefs.sceneRes(a, g.titleId));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] d = new AlertDialog[1];
        boolean args = ps.stream().allMatch(SceneRes.Preset::arg);
        box.addView(Ui.small(a, args
                ? Tx.s(R.string.game_scene_args_explain)
                : Tx.s(R.string.game_scene_overlay_explain)));
        android.widget.RadioGroup rg = new android.widget.RadioGroup(a);
        for (int k = 0; k < labels.length; k++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(a);
            rb.setText(labels[k]);
            rb.setId(k + 1);
            rg.addView(rb);
        }
        rg.check(cur == null ? 1 : ps.indexOf(cur) + 2);
        rg.setOnCheckedChangeListener((x, w) -> {
            Prefs.sceneRes(a, g.titleId, w <= 1 ? null : ps.get(w - 2).name);
            keepAdvanced(a, g);
            d[0].dismiss();
            done.run();
        });
        Ui.add(box, rg, 8);
        d[0] = Ui.sheet(a, Tx.s(R.string.game_title_for, Tx.s(R.string.game_3d_scene), g.name), box).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }

    static String phoneTag() { return java.util.Locale.getDefault().toLanguageTag(); }

    /** Game language (DM_LANG, GameLanguage): the phone's language first (the default), then the console's languages; a game without the one picked uses one it has */
    static void language(android.app.Activity a, Game g, Runnable done) {   // the in-game panel's shortcut too
        String[] labels = new String[GameLanguage.CODES.length + 1];
        labels[0] = GameLanguage.label(null, phoneTag());
        System.arraycopy(GameLanguage.NAMES, 0, labels, 1, GameLanguage.NAMES.length);
        String m = Prefs.gameLang(a, g.titleId);
        int checked = m == null ? 0 : 1 + Math.max(0, Arrays.asList(GameLanguage.CODES).indexOf(m));
        new PadFocus.Builder(a).setTitle(Tx.s(R.string.game_title_for, Tx.s(R.string.game_game_language), g.name)).setSingleChoiceItems(labels, checked, (x, w) -> {
            Prefs.gameLang(a, g.titleId, w == 0 ? null : GameLanguage.CODES[w - 1]);
            x.dismiss();
            done.run();
        }).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }

    /** A choice made under Advanced (a game with presets) stores Advanced, so the choices are not taken for a preset they happen to match (ResPresets.current) */
    private static void keepAdvanced(MainActivity a, Game g) {
        if (!ResPresets.presets(Profile.of(g.titleId)).isEmpty()) Prefs.resPreset(a, g.titleId, ResPresets.ADVANCED);
    }

    /** The window list's title: Window under a game's Advanced (it has presets), Resolution otherwise */
    private static String windowTitle(Game g) { return ResPresets.presets(Profile.of(g.titleId)).isEmpty() ? Tx.s(R.string.render_res_title) : Tx.s(R.string.game_window); }

    private static void resolution(MainActivity a, Game g, Runnable done) {
        Profile mp = Profile.of(g.titleId);
        String[] modes = mp != null ? mp.modes() : null;
        if (modes != null) {   // a game with one docked and one handheld row (Profile.modes): those two choices only, as Display shows its two
            new PadFocus.Builder(a).setTitle(windowTitle(g) + " · " + g.name).setSingleChoiceItems(new String[] {mp.modeLabel(0), mp.modeLabel(1)},
                    mp.modeOf(Prefs.gameWindow(a, g.titleId)), (x, w) -> {
                Prefs.gameWindow(a, g.titleId, modes[w]);
                keepAdvanced(a, g);
                x.dismiss();
                done.run();
            }).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
            return;
        }
        String[] labels = new String[Prefs.WINDOWS.length + 1];
        labels[0] = Tx.s(R.string.game_default_value, Prefs.windowName(Prefs.defaultWindow(a, g.titleId), false));
        { Profile lp = Profile.of(g.titleId); for (int li = 0; li < Prefs.WINDOWS.length; li++) labels[li + 1] = lp != null ? lp.windowLabel(Prefs.WINDOWS[li], Prefs.WINDOW_NAMES[li]) : Prefs.WINDOW_NAMES[li]; }   // a choice the game draws at another size says so (Profile.windowLabel)
        int checked = Prefs.ownWindow(a, g.titleId) ? 1 + Math.max(0, Arrays.asList(Prefs.WINDOWS).indexOf(Prefs.gameWindow(a, g.titleId))) : 0;
        new PadFocus.Builder(a).setTitle(windowTitle(g) + " · " + g.name).setSingleChoiceItems(labels, checked, (x, w) -> {
            Prefs.gameWindow(a, g.titleId, w == 0 ? null : Prefs.WINDOWS[w - 1]);
            keepAdvanced(a, g);
            x.dismiss();
            done.run();
        }).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }

    /** Display: Fit keeps the game's 16:9 picture with bars on a screen of another shape; Stretch fills the screen (DM_VK_STRETCH), the touch mapping with it */
    private static void display(MainActivity a, Game g, Runnable done) {
        new PadFocus.Builder(a).setTitle(Tx.s(R.string.game_title_for, Tx.s(R.string.common_display), g.name)).setSingleChoiceItems(Tx.arr(R.array.arr_display), Prefs.stretch(a, g.titleId) ? 1 : 0, (x, w) -> {
            Prefs.stretch(a, g.titleId, w == 1);
            x.dismiss();
            done.run();
        }).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }

    private static void xbox(MainActivity a, Game g, Runnable done) {   // Default first: the game follows Settings again
        String[] labels = new String[Tx.arr(R.array.arr_xbox).length + 1];
        labels[0] = Tx.s(R.string.game_default_settings_value, Prefs.xboxLabel(Prefs.xboxButtons(a)));
        System.arraycopy(Tx.arr(R.array.arr_xbox), 0, labels, 1, Tx.arr(R.array.arr_xbox).length);
        int checked = Prefs.ownXboxButtons(a, g.titleId) ? Prefs.xboxButtons(a, g.titleId) + 1 : 0;
        new PadFocus.Builder(a).setTitle(Tx.s(R.string.game_title_for, Tx.s(R.string.settings_xbox_button_label_swap), g.name)).setSingleChoiceItems(labels, checked, (x, w) -> {
            Prefs.xboxButtons(a, g.titleId, w == 0 ? null : w - 1);
            x.dismiss();
            done.run();
        }).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }

    private static void cap(MainActivity a, Game g, Runnable done) {
        if (Prefs.limited(g.titleId)) {   // a frame limit, no locking (Prefs.PACE_CAPS)
            String[] labels = new String[Prefs.PACE_CAPS.length + 1];
            int pl = Prefs.profileLimit(g.titleId);   // the profile's own default (fps_limit), else the main screen's
            Integer rc = Prefs.recCap(a, g.titleId);
            labels[0] = rc != null ? Tx.s(R.string.game_recommended_value, Prefs.paceLabel(rc)) : pl > 0 ? Tx.s(R.string.game_default_this_games, Prefs.paceLabel(Prefs.paceOf(pl))) : Tx.s(R.string.game_default_settings_value, Prefs.paceLabel(Prefs.paceOf(Prefs.fpsCap(a))));
            int checked = 0, cur = Prefs.paceCap(a, g.titleId);
            for (int k = 0; k < Prefs.PACE_CAPS.length; k++) { labels[k + 1] = Prefs.paceLabel(Prefs.PACE_CAPS[k]); if (Prefs.ownFpsCap(a, g.titleId) && Prefs.PACE_CAPS[k] == cur) checked = k + 1; }
            new PadFocus.Builder(a).setTitle(Tx.s(R.string.game_title_for, Tx.s(R.string.common_frame_rate_cap), g.name)).setSingleChoiceItems(labels, checked, (x, w) -> {
                Prefs.fpsCap(a, g.titleId, w == 0 ? null : Prefs.PACE_CAPS[w - 1]);
                x.dismiss(); done.run();
            }).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
            return;
        }
        int n = Prefs.FPS_CAPS.length;
        String[] labels = new String[n + 2];
        Integer rc = Prefs.recCap(a, g.titleId);
        labels[0] = firstLabel(rc != null ? MainActivity.capLabel(rc) : null, MainActivity.capLabel(Prefs.fpsCap(a)));
        labels[1] = MainActivity.capLabel(-1);
        int checked = 0;
        boolean own = Prefs.ownFpsCap(a, g.titleId);
        int cur = Prefs.fpsCap(a, g.titleId);
        if (own && cur < 0) checked = 1;
        for (int k = 0; k < n; k++) {
            labels[k + 2] = MainActivity.capLabel(Prefs.FPS_CAPS[k]);
            if (own && Prefs.FPS_CAPS[k] == cur) checked = k + 2;
        }
        new PadFocus.Builder(a).setTitle(Tx.s(R.string.game_title_for, Tx.s(R.string.common_frame_rate_cap), g.name)).setSingleChoiceItems(labels, checked, (x, w) -> {
            Prefs.fpsCap(a, g.titleId, w == 0 ? null : w == 1 ? -1 : Prefs.FPS_CAPS[w - 2]);
            x.dismiss();
            done.run();
        }).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }

    private static void driver(MainActivity a, Game g, Runnable done) {
        List<GpuDriver.Info> inst = GpuDriver.installed(a);
        GpuDriver.Info def = GpuDriver.selected(a);
        List<String> labels = new ArrayList<>();
        String rd = Prefs.recommendedDriver(a, g.titleId);   // the first choice is this phone's recommended driver when there is one (Prefs.recommendedDriver)
        labels.add(rd != null ? Tx.s(R.string.game_recommended_value, Prefs.recName(a, g.titleId, Recommend.DRIVER, Recommend.SYSTEM.equals(rd) ? "system" : rd)) : Tx.s(R.string.game_default_main_screens, def == null ? Tx.s(R.string.common_system) : def.label()));
        labels.add(Tx.s(R.string.game_system_the_phone_s_own));
        String c = GpuDriver.choice(a, g.titleId);
        int checked = c == null ? 0 : c.equals(GpuDriver.SYSTEM) ? 1 : 0;
        for (int k = 0; k < inst.size(); k++) {
            labels.add(inst.get(k).label());
            if (c != null && inst.get(k).dir.getName().equals(c)) checked = k + 2;
        }
        new PadFocus.Builder(a).setTitle(Tx.s(R.string.game_title_for, Tx.s(R.string.common_gpu_driver), g.name)).setSingleChoiceItems(labels.toArray(new String[0]), checked, (x, w) -> {
            GpuDriver.choice(a, g.titleId, w == 0 ? null : w == 1 ? GpuDriver.SYSTEM : inst.get(w - 2).dir.getName());
            x.dismiss();
            if (slowOnTurnip(a, g.titleId)) Toast.makeText(a, TURNIP_SLOW_NOTE(), Toast.LENGTH_LONG).show();
            done.run();
        }).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }

    private static void env(MainActivity a, Game g, Runnable done) {
        EditText t = new EditText(a);
        t.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        t.setMinLines(4);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(13);
        t.setHint("DM_WATCH_STALL=25");
        t.setText(Prefs.gameEnv(a, g.titleId));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.small(a, Tx.s(R.string.game_extra_env_explain, g.name)));
        Ui.add(box, t, 8);
        Ui.sheet(a, Tx.s(R.string.game_title_for, Tx.s(R.string.game_extra_environment), g.name), box).setPositiveButton(Tx.s(R.string.game_save), (x, w) -> { Prefs.gameEnv(a, g.titleId, t.getText().toString()); done.run(); })
                .setNeutralButton(Tx.s(R.string.game_clear), (x, w) -> { Prefs.gameEnv(a, g.titleId, null); done.run(); }).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }

    static String dflt(boolean own, String v) { return v; }   // a row shows the value alone; the list's first choice says Default (Settings: …)

    /** The first choice of a list, which removes the game's own value: "Recommended (x)" where this phone has a recommendation (Prefs.recommended), else "Default (Settings: y)" */
    private static String firstLabel(String recName, String settingsValue) { return recName != null ? Tx.s(R.string.game_recommended_value, recName) : Tx.s(R.string.game_default_settings_value, settingsValue); }
    private static String recLabel(String[] names, String[] words, String recommended) { int i = Recommend.index(words, recommended); return i >= 0 && i < names.length ? names[i] : null; }

    /** A choice with firstLabel's choice first: picking it removes the game's own value (set(null)) */
    private static void choose(MainActivity a, String title, String[] items, String first, boolean own, int cur, java.util.function.Consumer<Integer> set, Runnable done) {
        String[] labels = new String[items.length + 1];
        labels[0] = first;
        System.arraycopy(items, 0, labels, 1, items.length);
        new PadFocus.Builder(a).setTitle(title).setSingleChoiceItems(labels, own ? cur + 1 : 0, (x, w) -> { set.accept(w == 0 ? null : w - 1); x.dismiss(); done.run(); })
                .setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }

    private static void upscaler(MainActivity a, Game g, Runnable done) {   // the Upscaler (GameUpscaler: DM_VK_NDSR and the present's scaling); the in-game panel changes it live
        choose(a, Tx.s(R.string.game_title_for, Tx.s(R.string.common_upscaler), g.name), Tx.arr(R.array.arr_upscaler_modes), firstLabel(recLabel(Tx.arr(R.array.arr_upscaler_modes), Recommend.UPSCALERS, Prefs.rec(a, g.titleId, Recommend.UPSCALER)), Tx.arr(R.array.arr_upscaler_modes)[GameUpscaler.mode(a)]), GameUpscaler.ownMode(a, g.titleId), GameUpscaler.mode(a, g.titleId),
               v -> GameUpscaler.mode(a, g.titleId, v), done);
    }

    private static void sharpness(MainActivity a, Game g, Runnable done) {   // FSR's RCAS sharpening (GameUpscaler, DM_VK_NDSR_SHARP)
        choose(a, Tx.s(R.string.game_title_for, Tx.s(R.string.common_fsr_sharpness), g.name), Tx.arr(R.array.arr_fsr_sharp), firstLabel(null, GameUpscaler.sharpName(GameUpscaler.sharp(a))), GameUpscaler.ownSharp(a, g.titleId),
               GameUpscaler.sharpIndex(GameUpscaler.sharp(a, g.titleId)), v -> GameUpscaler.sharp(a, g.titleId, v == null ? null : GameUpscaler.SHARPS[v]), done);
    }

    private static void screenScale(MainActivity a, Game g, Runnable done) {   // a game on the DLSS stand-in: the present's scaling (GameUpscaler.screen, DM_VK_PRESENT_SCALE); the in-game panel changes it live
        choose(a, Tx.s(R.string.game_title_for, Tx.s(R.string.common_screen_scaling), g.name), Tx.arr(R.array.arr_screen_scale),
               Tx.s(R.string.game_default_value, Tx.arr(R.array.arr_screen_scale)[GameUpscaler.screenDefault(a, g.titleId)]), GameUpscaler.ownScreen(a, g.titleId),
               GameUpscaler.screen(a, g.titleId), v -> GameUpscaler.screen(a, g.titleId, v), done);
    }

    private static void screenSharp(MainActivity a, Game g, Runnable done) {   // the present's FSR 1 RCAS under Screen scaling (the same ndsr_sharp as FSR sharpness, DM_VK_FSR_SHARP)
        choose(a, Tx.s(R.string.game_title_for, Tx.s(R.string.common_screen_sharpness), g.name), Tx.arr(R.array.arr_fsr_sharp), firstLabel(null, GameUpscaler.sharpName(GameUpscaler.sharp(a))), GameUpscaler.ownSharp(a, g.titleId),
               GameUpscaler.sharpIndex(GameUpscaler.sharp(a, g.titleId)), v -> GameUpscaler.sharp(a, g.titleId, v == null ? null : GameUpscaler.SHARPS[v]), done);
    }

    private static void dlssMode(MainActivity a, Game g, Runnable done) {   // the DLSS stand-in's mode (GameUpscaler.dlssMode, DM_VK_DLSS); the in-game panel changes it live
        choose(a, Tx.s(R.string.game_title_for, Tx.s(R.string.common_upscaler), g.name), Tx.arr(R.array.arr_dlss_modes), firstLabel(recLabel(Tx.arr(R.array.arr_dlss_modes), Recommend.DLSS_MODES, Prefs.rec(a, g.titleId, Recommend.UPSCALER)), Tx.arr(R.array.arr_dlss_modes)[DlssTune.MODE_DEFAULT]), GameUpscaler.ownDlssMode(a, g.titleId), GameUpscaler.dlssMode(a, g.titleId),
               v -> GameUpscaler.dlssMode(a, g.titleId, v), done);
    }

    static final String CLARITY_LEFT = "Steady", CLARITY_RIGHT = "Clear";   // the Motion clarity slider's ends: more history (steadier) / less ghosting
    private static void clarity(MainActivity a, Game g, Runnable done) {   // the GSR2 stand-in's clarity step (DlssTune, DM_VK_GSR2_CLARITY); the in-game panel changes it live
        StepSlider.dialog(a, Tx.s(R.string.game_motion_clarity), " · " + g.name, DlssTune.clarityNames(), GameUpscaler.clarity(a, g.titleId), CLARITY_LEFT, CLARITY_RIGHT,
                          DlssTune.clarityName(DlssTune.CLARITY_DEFAULT), GameUpscaler.ownClarity(a, g.titleId), v -> GameUpscaler.clarity(a, g.titleId, v), done);
    }
    private static void dlssSharp(MainActivity a, Game g, Runnable done) {   // the DLSS stand-in's RCAS (DlssTune, DM_VK_DLSS_SHARP; GSR 2's and FSR 1's); the in-game panel changes it live
        StepSlider.dialog(a, DlssTune.sharpTitle(GameUpscaler.dlssMode(a, g.titleId)), " · " + g.name, DlssTune.sharpNames(), GameUpscaler.dlssSharp(a, g.titleId), Tx.s(R.string.game_softer), Tx.s(R.string.game_sharper),
                          DlssTune.sharpName(DlssTune.SHARP_DEFAULT), GameUpscaler.ownDlssSharp(a, g.titleId), v -> GameUpscaler.dlssSharp(a, g.titleId, v), done);
    }

    private static void motionSens(MainActivity a, Game g, Runnable done) {   // the phone's gyro scale (Prefs.MOTION_PCT, DM_MOTION_SENS); read when the game starts
        StepSlider.dialog(a, Tx.s(R.string.game_motion_sensitivity), " · " + g.name, Prefs.MOTION_NAMES, Prefs.motionSens(a, g.titleId), "Slower", "Faster",
                          Prefs.MOTION_NAMES[Prefs.MOTION_DEFAULT], Prefs.ownMotionSens(a, g.titleId), v -> Prefs.motionSens(a, g.titleId, v), done);
    }
    private static void drs(MainActivity a, Game g, Runnable done) {   // the render size's range (Prefs.DRS), from the next start; the in-game panel changes it live
        DrsSlider.dialog(a, " · " + g.name, Prefs.drs(a, g.titleId), firstLabel(recLabel(Prefs.DRS_NAMES, Recommend.DRS_VALUES, Prefs.rec(a, g.titleId, Recommend.DRS)), Prefs.DRS_NAMES[Prefs.drs(a)]), Prefs.ownDrs(a, g.titleId), v -> Prefs.drs(a, g.titleId, v), done);
    }

    /** The profile's aspect variants' labels ("4:3"): its variant rows labelled w:h */
    static List<String> aspects(Profile p) {
        List<String> out = new ArrayList<>();
        if (p == null) return out;
        for (String v : p.variants.split(",")) { int eq = v.indexOf('='); if (eq > 0 && v.substring(eq + 1).matches("[0-9]+:[0-9]+")) out.add(v.substring(eq + 1)); }
        for (String v : p.cameraAspects.split(",")) if (v.matches("[0-9]+:[0-9]+") && !out.contains(v)) out.add(v);   // the host follows these with the game's 3D cameras (no module variant)
        if (!p.cameraAspects.isEmpty() || p.aspectVariant(ScreenShape.CHOICE) != null) out.add(ScreenShape.CHOICE);   // last: the window's own shape, for a game that follows any shape (host cameras, or a variant labelled "screen")
        return out;
    }

    static String aspectLabel(android.content.Context a, String id) {
        String l = Prefs.aspect(a, id);
        Profile p = Profile.of(id);
        if (ScreenShape.CHOICE.equals(l) && aspects(p).contains(l)) return "Screen shape";
        return l == null || !aspects(p).contains(l) ? "16:9 (as shipped)" : l + (p.aspectVariant(l) != null ? Tx.s(R.string.game_aspect_native) : Tx.s(R.string.game_aspect_3d_view));
    }

    /** Aspect ratio: as shipped, or a native aspect mod (the pack holds both, so it takes effect at the next start without Prepare); 4:3 shows the picture at 4:3, filling a
     *  4:3 screen and with side bars on a wider one (Display › Stretch still fills the screen). Screen shape (a game whose cameras follow any shape) is the game window's own
     *  shape, taken at the start (ScreenShape), so the picture fills the screen */
    private static void aspect(MainActivity a, Game g, Runnable done) {
        List<String> as = aspects(Profile.of(g.titleId));
        String[] labels = new String[as.size() + 1];
        labels[0] = Tx.s(R.string.game_16_9_as_shipped);
        for (int k = 0; k < as.size(); k++) labels[k + 1] = ScreenShape.CHOICE.equals(as.get(k)) ? "Screen shape (the picture fills the window; applies at the next start)"
                    : as.get(k) + (Profile.of(g.titleId).aspectVariant(as.get(k)) != null ? Tx.s(R.string.game_aspect_native_laid_out, as.get(k))
                    : Tx.s(R.string.game_aspect_3d_follows));
        String cur = Prefs.aspect(a, g.titleId);
        int checked = cur == null ? 0 : Math.max(0, as.indexOf(cur) + 1);
        new PadFocus.Builder(a).setTitle(Tx.s(R.string.game_title_for, Tx.s(R.string.game_aspect_ratio), g.name)).setSingleChoiceItems(labels, checked, (x, w) -> {
            Prefs.aspect(a, g.titleId, w == 0 ? null : as.get(w - 1));
            x.dismiss();
            done.run();
        }).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
    }
}
