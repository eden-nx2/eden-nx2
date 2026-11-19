package dev.nxnative.android;

import android.app.LocaleManager;
import android.os.Build;
import android.content.Context;
import android.os.LocaleList;

import java.util.Locale;

/** The launcher's own language (Settings › Appearance › Language; not the game's language, GameLanguage): the per-app locale of Android 13+
 *  (LocaleManager.setApplicationLocales), which the system remembers and applies to every process of the app. "System default" is the phone's language when
 *  the launcher has it, else English. A language is added with a values-&lt;tag&gt;/strings.xml, a line in res/xml/locales_config.xml and a row in LIST. */
final class Languages {
    private Languages() {}

    /** One language: its BCP 47 tag (as in locales_config.xml), and its own name */
    static final class Lang {
        final String tag;
        final int nameRes;
        Lang(String tag, int nameRes) { this.tag = tag; this.nameRes = nameRes; }
        String label() { return Tx.s(nameRes); }
    }

    /** English first (the source and the fallback), then the others */
    static final Lang[] LIST = {
        new Lang("en", R.string.lang_en),
        new Lang("es", R.string.lang_es),
        new Lang("tr", R.string.lang_tr),
        new Lang("zh-CN", R.string.lang_zh_cn),
        new Lang("fr", R.string.lang_fr),
        new Lang("ar", R.string.lang_ar),
        new Lang("pl", R.string.lang_pl),
        new Lang("ko", R.string.lang_ko),
        new Lang("uk", R.string.lang_uk),
        new Lang("de", R.string.lang_de),
        new Lang("it", R.string.lang_it),
    };

    /** The language the player picked, or null for System default */
    static Lang picked(Context c) {
        if (Build.VERSION.SDK_INT < 33) return null;   // no per-app language before Android 13 (LocaleManager): the phone's language is used
        LocaleManager m = c.getSystemService(LocaleManager.class);
        if (m == null) return null;
        LocaleList l = m.getApplicationLocales();
        if (l.isEmpty()) return null;
        return find(l.get(0));
    }

    /** Picks a language (null: System default); the screens are rebuilt by the system, which recreates the activities */
    static void apply(Context c, Lang lang) {
        if (Build.VERSION.SDK_INT < 33) return;
        LocaleManager m = c.getSystemService(LocaleManager.class);
        if (m == null) return;
        m.setApplicationLocales(lang == null ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(lang.tag));
    }

    /** The language "System default" gives: the first of the phone's languages the launcher has, else English */
    static Lang system() {
        LocaleList phone = android.content.res.Resources.getSystem().getConfiguration().getLocales();
        for (int i = 0; i < phone.size(); i++) {
            Lang l = find(phone.get(i));
            if (l != null) return l;
        }
        return LIST[0];
    }

    /** The row of the picker for System default, and the value shown when nothing is picked */
    static String systemLabel() { return Tx.s(R.string.settings_language_system, Tx.s(system().nameRes)); }

    /** The row's value: the picked language, or System default with the language it gives */
    static String current(Context c) {
        Lang p = picked(c);
        return p != null ? p.label() : systemLabel();
    }

    static Lang find(Locale loc) {
        for (Lang l : LIST) if (matches(loc, Locale.forLanguageTag(l.tag))) return l;
        return null;
    }

    /** The same language; when the launcher's translation names a region (zh-CN) the phone's must be that region or the same script */
    static boolean matches(Locale phone, Locale ours) {
        if (!phone.getLanguage().equals(ours.getLanguage())) return false;
        if (ours.getCountry().isEmpty()) return true;
        return ours.getCountry().equals(phone.getCountry()) || (phone.getCountry().isEmpty() && phone.getScript().isEmpty()) || "Hans".equals(phone.getScript());
    }
}
