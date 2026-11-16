package dev.nxnative.android;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The order of Home's tiles, chosen by the player (Reorder games). The order is a list of game ids, never list positions: an id is the title ID and the pack
 * ({@link #id}), so it holds when games are added, removed or merged (Game.collapse). {@link #sort} puts the games in that order (a game not in it goes last,
 * in its current order); {@link #move} moves one id; {@link #write} is what Prefs stores (the games there are now, so a removed game drops out on the next
 * write). A game whose pack changed (an older entry merged into a newer one of the same title) takes the place of an id of its title that no game uses any more.
 * No Android classes: the JVM test (GameOrderJvmTest) runs it.
 */
final class GameOrder {
    private GameOrder() {}

    /** One game: its title ID (any case) and the pack folder it runs from */
    static String id(String titleId, String packDir) { return titleId.toUpperCase(java.util.Locale.ROOT) + "|" + packDir; }

    private static String title(String id) { int i = id.indexOf('|'); return i < 0 ? id : id.substring(0, i); }

    /** The games in the player's order; the list given is not changed. Sorting twice gives the same list */
    static <T> List<T> sort(List<T> games, List<String> order, Function<T, String> idOf) {
        int n = games.size();
        int[] pos = new int[n];
        boolean[] used = new boolean[order.size()];
        Set<String> present = new HashSet<>();
        for (T g : games) present.add(idOf.apply(g));
        for (int i = 0; i < n; i++) {   // the exact id first
            pos[i] = -1;
            int j = order.indexOf(idOf.apply(games.get(i)));
            if (j >= 0 && !used[j]) { pos[i] = j; used[j] = true; }
        }
        for (int i = 0; i < n; i++) {   // else the place of an id of its title that no game has now (a merged entry's pack)
            if (pos[i] >= 0) continue;
            String t = title(idOf.apply(games.get(i)));
            for (int j = 0; j < order.size(); j++)
                if (!used[j] && !present.contains(order.get(j)) && title(order.get(j)).equals(t)) { pos[i] = j; used[j] = true; break; }
        }
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Integer.compare(pos[a] < 0 ? Integer.MAX_VALUE : pos[a], pos[b] < 0 ? Integer.MAX_VALUE : pos[b]));   // stable
        List<T> out = new ArrayList<>(n);
        for (int i : idx) out.add(games.get(i));
        return out;
    }

    /** The ids with one moved by delta places (clamped at the ends); the list given is not changed */
    static List<String> move(List<String> ids, String id, int delta) {
        List<String> out = new ArrayList<>(ids);
        int i = out.indexOf(id);
        if (i < 0) return out;
        int j = Math.max(0, Math.min(out.size() - 1, i + delta));
        out.remove(i);
        out.add(j, id);
        return out;
    }

    /** What is stored after a move: the shown order (the games now). Ids of games that are gone are not kept */
    static List<String> write(List<String> shown) { return new ArrayList<>(shown); }

    /** The order as one string for the preferences (an id has no line break) */
    static String encode(List<String> ids) { return String.join("\n", ids); }

    static List<String> decode(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String x : s.split("\n")) if (!x.isEmpty() && !out.contains(x)) out.add(x);
        return out;
    }
}
