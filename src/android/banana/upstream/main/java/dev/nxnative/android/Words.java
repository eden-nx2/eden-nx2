package dev.nxnative.android;

import java.util.Locale;

/**
 * The launcher's text in the classes that run without Android (Profile, Recommend, Job, PrepareText, Readiness ... : the JVM tests compile them with a plain javac).
 * {@code Words.s("key", "English")} gives the English given there while no source is set (the tests, the host's tools); the app sets {@link #source} once
 * (NxApp) to the string resources, and the same key in res/values-&lt;lang&gt;/strings.xml answers in the app's language. The English in the code must equal
 * the English of the key in res/values/strings.xml (dm/tools/tests/test_android_languages.py checks it), so a new text is one line here and one in strings.xml.
 * Compare states, never this text: a state the launcher acts on is a value of its own (Job.currentStep, a boolean ...), not a sentence.
 */
final class Words {
    private Words() {}

    /** Looks a key up in the resources: the text of a string or the one form of a plural; null when the key is not there */
    interface Source {
        String string(String key);
        String plural(String key, int count);
    }

    static volatile Source source;
    private static final ThreadLocal<Boolean> ENGLISH = new ThreadLocal<>();

    /** Runs f with every Words text in English (bug-report digests and logs are read by maintainers, whatever the app's language) */
    static <T> T english(java.util.function.Supplier<T> f) {
        Boolean old = ENGLISH.get();
        ENGLISH.set(Boolean.TRUE);
        try { return f.get(); } finally { ENGLISH.set(old); }
    }

    /** A text by its key; with arguments it is a format string with positional %1$s / %2$d (as in the resources) */
    static String s(String key, String english, Object... args) {
        Source src = ENGLISH.get() != null ? null : source;
        String t = src == null ? null : src.string(key);
        return format(t != null ? t : english, args);
    }

    /** A text that depends on a count: the language picks the form (plurals in the resources); the English given is the one form and the other form */
    static String n(String key, int count, String one, String other, Object... args) {
        Source src = ENGLISH.get() != null ? null : source;
        String t = src == null ? null : src.plural(key, count);
        return format(t != null ? t : count == 1 ? one : other, args);
    }

    private static String format(String t, Object[] args) { return args.length == 0 ? t : String.format(Locale.ROOT, t, args); }
}
