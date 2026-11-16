package dev.nxnative.android;

import android.content.res.Resources;

/** The launcher's text from resources (the strings.xml files of res; English is the fallback), for the places that have no Context at hand.
 *  The Application's resources follow the app's language (Languages.apply: LocaleManager.setApplicationLocales), in every process. */
final class Tx {
    private Tx() {}

    private static Resources res() { return NxApp.app.getResources(); }

    /** A string; with arguments it is a format string with positional %1$s / %2$d */
    static String s(int id, Object... args) { return args.length == 0 ? res().getString(id) : res().getString(id, args); }

    /** A list of labels (a string-array resource) */
    static String[] arr(int id) { return res().getStringArray(id); }

    /** A string that depends on a count (plurals; the language picks the form) */
    static String n(int id, int count, Object... args) { return res().getQuantityString(id, count, args); }
}
