#!/usr/bin/env python3
# SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
# SPDX-License-Identifier: GPL-3.0-or-later
"""Re-applies the Eden NX2 branding after merging a Lichii (or Hokkaido) update.

Lichii names files, directories, identifiers and strings after itself. This script turns all of
that into Eden / Eden NX2, so a merge only needs its real code conflicts resolved by hand:

    git merge --no-ff <lichii build commit>
    # resolve code conflicts; for branding-only conflicts take Lichii's side (git checkout --theirs)
    python3 -I tools/eden-nx2/apply_branding.py
    git add -A && git commit

It is idempotent (running it twice changes nothing) and ends with checks that fail when something
still says Lichii, or a build file points at a missing asset. See docs/UPDATING.md.
"""

import os
import re
import subprocess
import sys

ROOT = subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip()

# Lichii's artwork and ids: Eden's own (dist/eden.*, dist/com.eden.eden_nx2.*) replace them.
DELETE = [
    r"^dist/branding/",
    r"^dist/lichii\.(icns|ico|png)$",
    r"^dist/lichii_wordmark(@2x)?\.png$",
    r"^dist/io\.github\.zortos293\.lichii\.",
    r"^dist/packaging/arch/lichii-git/",
    r"^dist/Eglantine\.png$",
    r"^dist/eglantine\.icns$",
    r"^tools/Generate-LichiiIcons\.ps1$",
]

# Path renames before the generic lichii -> eden one.
PATH_SPECIFIC = [
    ("dist/qt_themes/lichii/", "dist/qt_themes/eden_dark/"),
]

# Kept as they are: links to upstream projects and Lichii's compatibility site.
PROTECT = [
    "github.com/zortos293/Lichii",
    "zortos293/Lichii",
    "zortos293/lichii",
    "lichii.zortos.me",
    "Lichii-Android",
    "(from Lichii #161)",
]

# Documents that credit Lichii and Hokkaido by name.
SKIP_TEXT = {"README.md", "docs/MIRROR_CHANGES.md", "docs/UPDATING.md", "tools/eden-nx2/apply_branding.py"}

TEXT_SPECIFIC = [
    ("qt_themes/lichii", "qt_themes/eden_dark"),
    (":/lichii/", ":/eden_dark/"),
    ('prefix="icons/lichii"', 'prefix="icons/eden_dark"'),
    ('prefix="lichii"', 'prefix="eden_dark"'),
    ('{"Lichii Dark", "lichii"}', '{"Eden Dark", "eden_dark"}'),
    ('theme == std::string("lichii")', 'theme == std::string("eden_dark")'),
    ('UISettings::values.theme == "lichii"', 'UISettings::values.theme == "eden_dark"'),
    ("Lichii Dark", "Eden Dark"),
    ("LichiiDark", "EdenDark"),
    ("IsLichiiTheme", "IsEdenDarkTheme"),
    ("render_lichii_icons", "render_eden_dark_icons"),
    ("Copyright 2026 Lichii Emulator Project", "Copyright 2026 Eden Emulator Project"),
    ("Copyright 2026 Lichii Project", "Copyright 2026 Eden Emulator Project"),
    ("Lichii contributors", "Eden Emulator Project"),
    ('"Exit Eglantine"', '"Exit Eden"'),
    (">Exit Eglantine<", ">Exit Eden<"),
    ("Salir de Eglantine", "Salir de Eden"),
]
GENERIC = [("LICHII", "EDEN"), ("Lichii", "Eden"), ("lichii", "eden")]

# Lichii's Android resources keep Eden's synthwave theme (eden_error, EdenSlider, Theme.Eden.Main,
# ...) next to its own base theme (lichii_error, LichiiSlider, Theme.Lichii.Main, ...), so the
# generic rename would give two resources one name and the APK would not build. Lichii's own get
# an EdenNX2 name instead. The duplicate-resource check below catches any new clash.
ANDROID_SPECIFIC = [
    (re.compile(r"\blichii_error\b"), "eden_nx2_error"),
    (re.compile(r"\bLichiiSlider\b"), "EdenNX2Slider"),
    (re.compile(r"\bLichiiPopupMenu\b"), "EdenNX2PopupMenu"),
    (re.compile(r"\bTheme([._])Lichii(?=[._]Main(?![A-Za-z0-9]))"), r"Theme\1EdenNX2"),
]

# Desktop ids (desktop entry, MIME, AppStream, Qt desktop file name). Android keeps the Kotlin
# package io.github.zortos293.eden that the generic rename makes of Lichii's.
DESKTOP_ID = ("io.github.zortos293.eden", "com.eden.eden_nx2")

# Fix-ups of Lichii asset references that the generic rename would point at missing files.
FIXUPS = [
    # The What's New dialog names the app Eden NX2 (nx2_ui_strings); its test checks that text.
    ("src/tests/qt_whats_new/whats_new.cpp", re.compile(r'QStringLiteral\("Eden build '),
     'QStringLiteral("Eden NX2 build '),
    # Banana Native's LogProvider authority is the host app's ID: Hokkaido's (dev.lichii.android)
    # becomes ours, with the build types' suffixes.
    ("src/android/banana/build.gradle.kts", re.compile(r'\\"dev\.(?:lichii|eden)\.android'),
     r'\\"com.eden.eden_nx2'),
    ("CMakeLists.txt",
     re.compile(r'    install\(FILES "dist/eden\.png"\n\s*DESTINATION "share/icons/hicolor/256x256/apps"\n'
                r'\s*RENAME "[^"]+"\)\n'),
     '    install(FILES "dist/com.eden.eden_nx2.svg"\n'
     '        DESTINATION "share/icons/hicolor/scalable/apps")\n'),
    (".github/workflows/release-binaries.yml", re.compile(r'cp dist/eden\.png "\$icon"'),
     'cp dist/qt_themes/default/icons/256x256/eden.png "$icon"'),
    ("dist/qt_themes/default/default.qrc",
     re.compile(r'<file alias="256x256/eden\.png">\.\./\.\./eden\.png</file>'),
     '<file alias="256x256/eden.png">icons/256x256/eden.png</file>'),
    ("src/eden/eden.qrc",
     re.compile(r'\s*<file alias="eden_wordmark(@2x)?\.png">\.\./\.\./dist/eden_wordmark(@2x)?\.png</file>'),
     ""),
    ("src/eden/Info.plist",
     re.compile(r"<string>com\.eden\.eden_nx2</string>( <!-- May be required by 3rd party ext -->)?"),
     "<string>com.eden.eden-nx2</string>"),
    ("src/eden/Info.plist", re.compile(r"<string>eden\.icns</string>"), "<string>eden.icns</string>"),
    ("dist/installer.nsi", re.compile(r'!define PRODUCT_NAME "Eden"\n'), '!define PRODUCT_NAME "Eden NX2"\n'),
    ("CMakeModules/GenerateSCMRev.cmake", re.compile(r'set\(REPO_NAME "Eden"\)'), 'set(REPO_NAME "Eden NX2")'),
    ("CMakeModules/GenerateSCMRev.cmake", re.compile(r'set\(REPO_NAME "Eden Nightly"\)'),
     'set(REPO_NAME "Eden NX2 Nightly")'),
    ("src/android/app/build.gradle.kts", re.compile(r'applicationId = "dev\.eden\.eden_emulator"'),
     'applicationId = "com.eden.eden_nx2"'),
    ("src/android/app/build.gradle.kts", re.compile(r'applicationId = "dev\.legacy\.eden_emulator"'),
     'applicationId = "com.eden.eden_nx2_legacy"'),
    ("src/android/app/build.gradle.kts",
     re.compile(r'"appNameBase" to "Eden( Optimized| Legacy| ChromeOS)?"'),
     lambda m: f'"appNameBase" to "Eden NX2{m.group(1) or ""}"'),
    ("src/android/app/build.gradle.kts",
     re.compile(r'resValue\("string", "app_name_suffixed", "Eden( Optimized| Legacy| ChromeOS)?"\)'),
     lambda m: f'resValue("string", "app_name_suffixed", "Eden NX2{m.group(1) or ""}")'),
    ("src/android/app/build.gradle.kts", re.compile(r'as\? String \?: "Eden"'), 'as? String ?: "Eden NX2"'),
]

# "Eden" in user-facing strings becomes "Eden NX2", except where it names official Eden, a theme
# or colour, the "Exit Eden" settings key, or the log's "Eden Version:" prefix.
NX2_NAME = re.compile(r"\bEden\b(?! NX2| Dark| pink|'s Veil|\\'s Veil| Version: )")
NX2_KEEP_PHRASES = ["Exit Eden"]
ANDROID_KEEP_NAMES = {"eden_theme", "app_notification_channel_id"}
BINARY_EXT = {".png", ".ico", ".icns", ".bmp", ".jpg", ".car", ".profdata", ".nx", ".ttf", ".otf",
              ".bin", ".zip", ".wav", ".ogg", ".jar", ".webp", ".gif", ".keystore", ".so", ".dll"}


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True)


def tracked():
    return [f for f in git("ls-files", "-z").split("\0") if f]


def read(path):
    try:
        with open(os.path.join(ROOT, path), encoding="utf-8") as f:
            return f.read()
    except (UnicodeDecodeError, FileNotFoundError, IsADirectoryError):
        return None


def write(path, text):
    with open(os.path.join(ROOT, path), "w", encoding="utf-8", newline="") as f:
        f.write(text)


def delete_lichii_assets():
    gone = [f for f in tracked() if any(re.search(p, f) for p in DELETE)]
    for chunk in range(0, len(gone), 200):
        git("rm", "-q", "-r", "--cached", "--ignore-unmatch", "--", *gone[chunk:chunk + 200])
    for f in gone:
        p = os.path.join(ROOT, f)
        if os.path.exists(p):
            os.remove(p)
    return len(gone)


def new_path(path):
    for old, new in PATH_SPECIFIC:
        if path.startswith(old):
            path = new + path[len(old):]
    for old, new in GENERIC:
        path = path.replace(old, new)
    return path


def rename_paths():
    moved = 0
    for f in tracked():
        target = new_path(f)
        if target == f:
            continue
        src, dst = os.path.join(ROOT, f), os.path.join(ROOT, target)
        if os.path.exists(dst):
            if read(f) is not None and read(f) == read(target):
                git("rm", "-q", "--cached", "--", f)
                os.remove(src)
                continue
            sys.exit(f"apply_branding: {f} -> {target}: the target exists with other content")
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        git("mv", "--", f, target)
        moved += 1
    return moved


def rebrand_text(text, path):
    for i, phrase in enumerate(PROTECT):
        text = text.replace(phrase, f"\0P{i}\0")
    for old, new in TEXT_SPECIFIC:
        text = text.replace(old, new)
    if path.startswith("src/android/"):
        for old, new in ANDROID_SPECIFIC:
            text = old.sub(new, text)
    for old, new in GENERIC:
        text = text.replace(old, new)
    if not path.startswith("src/android/"):
        text = text.replace(*DESKTOP_ID)
    for i, phrase in enumerate(PROTECT):
        text = text.replace(f"\0P{i}\0", phrase)
    return text


HOKKAIDO_WORD = re.compile(r"\bHokkaido\b")


def nx2_name(text):
    if any(k in text for k in NX2_KEEP_PHRASES):
        return text
    return NX2_NAME.sub("Eden NX2", text)


def nx2_ui_strings(path, text):
    if path.endswith((".cpp", ".h")) and path.startswith(("src/eden/", "src/qt_common/")):
        def call(m):
            return re.sub(r'"(?:[^"\\]|\\.)*"', lambda q: nx2_name(q.group(0)), m.group(0))
        text = re.sub(r'\b(?:tr|QT_TR_NOOP)\((?:\s*"(?:[^"\\]|\\.)*")+', call, text)
        # The importer's source list names official Eden.
        text = text.replace('STRUCT_EMU(QT_TR_NOOP("Eden NX2"), LegacyEden)',
                            'STRUCT_EMU(QT_TR_NOOP("Eden"), LegacyEden)')
    elif path.endswith(".ui") and path.startswith("src/eden/") and not path.endswith("new_user_dialog.ui"):
        text = re.sub(r"<string>([^<]*)</string>", lambda m: f"<string>{nx2_name(m.group(1))}</string>", text)
    elif path.startswith("dist/languages/") and path.endswith(".ts"):
        def message(m):
            block = m.group(0)
            if "new_user_dialog.ui" in block or "migration_worker.h" in block:
                return block
            src = re.search(r"<source>(.*?)</source>", block, re.S)
            if not src or not NX2_NAME.search(src.group(1)) or any(k in src.group(1) for k in NX2_KEEP_PHRASES):
                return block
            block = re.sub(r"<source>(.*?)</source>", lambda q: f"<source>{nx2_name(q.group(1))}</source>", block, flags=re.S)
            return re.sub(r"(<translation[^>]*>)(.*?)(</translation>)",
                          lambda q: q.group(1) + nx2_name(q.group(2)) + q.group(3), block, flags=re.S)
        text = re.sub(r"<message>.*?</message>", message, text, flags=re.S)
    elif re.match(r"src/android/app/src/main/res/values[^/]*/strings\.xml$", path):
        def string(m):
            name = m.group(1)
            if name in ANDROID_KEEP_NAMES or "veil" in name.lower():
                return m.group(0)
            body = m.group(2).replace("Eglantine", "Eden").replace("Hokkaido", "Eden")
            return m.group(0)[: m.start(2) - m.start(0)] + nx2_name(body) + m.group(0)[m.end(2) - m.start(0):]
        text = re.sub(r'<string name="([^"]+)"[^>]*>(.*?)</string>', string, text, flags=re.S)
    elif path.startswith("src/android/") and path.endswith((".java", ".kt")):
        # Messages in Hokkaido's Banana Native integration name the app. Class names
        # (HokkaidoBridge), settings keys ("hokkaido_...") and comments keep Hokkaido's names, so
        # its later commits still apply.
        text = re.sub(r'"(?:[^"\\\n]|\\.)*"', lambda q: HOKKAIDO_WORD.sub("Eden NX2", q.group(0)), text)
    return text


def rewrite_files():
    changed = 0
    for f in tracked():
        if f in SKIP_TEXT or os.path.splitext(f)[1].lower() in BINARY_EXT:
            continue
        text = read(f)
        if text is None:
            continue
        new = nx2_ui_strings(f, rebrand_text(text, f))
        for path, pattern, repl in FIXUPS:
            if f == path:
                new = pattern.sub(repl, new)
        if new != text:
            write(f, new)
            changed += 1
    return changed


def check():
    problems = []
    files = tracked()
    for f in files:
        if re.search(r"lichii", f, re.I):
            problems.append(f"path still says Lichii: {f}")
    for f in files:
        if f in SKIP_TEXT or os.path.splitext(f)[1].lower() in BINARY_EXT:
            continue
        text = read(f)
        if text is None:
            continue
        for p in PROTECT:
            text = text.replace(p, "")
        for n, line in enumerate(text.splitlines(), 1):
            if re.search(r"lichii", line, re.I):
                problems.append(f"{f}:{n}: {line.strip()[:120]}")
    # Assets that build files and resources point at must exist.
    refs = re.compile(r"\bdist/[A-Za-z0-9_./@-]+\.(?:png|ico|icns|svg|bmp|desktop|xml|manifest|car)\b")
    for f in [x for x in files if x.endswith(("CMakeLists.txt", ".cmake", ".yml", ".rc", ".nsi", ".qrc"))]:
        text = read(f) or ""
        for ref in set(refs.findall(text)):
            if "${" in ref:
                continue
            base = ROOT if not f.endswith((".rc", ".qrc")) else os.path.dirname(os.path.join(ROOT, f))
            candidate = os.path.normpath(os.path.join(ROOT, ref)) if base == ROOT else None
            if candidate and not os.path.exists(candidate):
                problems.append(f"{f}: points at missing {ref}")
    for f in [x for x in files if x.endswith((".rc", ".qrc"))]:
        text = read(f) or ""
        for ref in re.findall(r'"(\.\./[^"]+)"|>(\.\./[^<]+)<|>(icons/[^<]+)<', text):
            ref = next(r for r in ref if r)
            if not os.path.exists(os.path.normpath(os.path.join(ROOT, os.path.dirname(f), ref))):
                problems.append(f"{f}: points at missing {ref}")
    # Android's libc++ has no std::atomic_ref (Lichii only builds for desktop): use
    # __atomic_load_n/__atomic_store_n and friends instead.
    for f in files:
        # Not built for Android: tests, Apple's Hypervisor backend, dynarmic's x64 backend.
        if f.startswith("src/") and not f.startswith(
                ("src/tests/", "src/dynarmic/externals/", "src/core/arm/hypervisor/",
                 "src/dynarmic/src/dynarmic/backend/x64/")) \
                and f.endswith((".cpp", ".h", ".inc")):
            for n, line in enumerate((read(f) or "").splitlines(), 1):
                if "std::atomic_ref" in line and not line.lstrip().startswith("//"):
                    problems.append(f"{f}:{n}: std::atomic_ref does not build for Android")
    # Android resources: one name may be defined only once per configuration (values, values-night,
    # ...), or aapt fails ("Found item ... more than one time").
    resource = re.compile(r'<(color|string|string-array|integer-array|array|plurals|dimen|style|bool|integer)'
                          r'\s+name="([^"]+)"')
    seen = {}
    for f in files:
        m = re.match(r"(src/android/.*/res/values[^/]*)/[^/]+\.xml$", f)
        if not m:
            continue
        for kind, name in resource.findall(read(f) or ""):
            key = (m.group(1), kind, name)
            if key in seen:
                problems.append(f"{f}: Android {kind} {name} is already defined (in {seen[key]})")
            seen.setdefault(key, f)
    # Strings and arrays the app uses must be defined: a merge that takes one side's strings.xml
    # drops the other side's strings (aapt or the Kotlin compile then fail late in CI).
    defined = {name for (_, _, name) in seen}
    for f in [x for x in files if x.startswith("src/android/") and x.endswith((".gradle.kts", ".gradle"))]:
        defined |= set(re.findall(r'resValue\(\s*"string",\s*"(\w+)"', read(f) or ""))
    library = {"appbar_scrolling_view_behavior", "help"}  # AndroidX / Material strings
    for f in files:
        if not (f.startswith("src/android/app/src/main/") and f.endswith((".kt", ".java", ".xml"))):
            continue
        text = read(f) or ""
        refs = re.findall(r"(?<![\w.])R\.(?:string|array|plurals)\.(\w+)", text) + \
            re.findall(r"@(?:string|array|plurals)/(\w+)", text)
        for name in sorted(set(refs) - defined - library):
            problems.append(f"{f}: uses Android resource {name}, which is not defined")
    for f in files:
        if not f.startswith("src/android/"):
            continue
        text = read(f) or ""
        if re.search(r"/res/values[^/]*/strings\.xml$", f):
            for m in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', text, re.S):
                if "Hokkaido" in m.group(2):
                    problems.append(f"{f}: string {m.group(1)} still says Hokkaido")
        elif f.endswith((".java", ".kt")):
            for m in re.finditer(r'"(?:[^"\\\n]|\\.)*"', text):
                if HOKKAIDO_WORD.search(m.group(0)):
                    problems.append(f"{f}: message {m.group(0)[:60]} still says Hokkaido")
    return problems


def main():
    os.chdir(ROOT)
    print(f"deleted {delete_lichii_assets()} Lichii asset files")
    print(f"renamed {rename_paths()} paths")
    print(f"rewrote {rewrite_files()} files")
    problems = check()
    for p in problems[:200]:
        print("CHECK:", p)
    if problems:
        sys.exit(f"apply_branding: {len(problems)} problems")
    print("checks passed")


if __name__ == "__main__":
    main()
