package dev.nxnative.android;

/**
 * The in-game notice for skipped work (android/README.md, In-game notices; pure Java: dm/tools/tests/test_android_skipnotice.py runs it on the JVM). The host counts the draws
 * and dispatches the Vulkan replay cannot run for causes of the GPU or its driver (refused pipelines and shaders, a missing mesh shader or other feature) or because the
 * pack's shader library lacks their shaders (vka_skipwarn.h VKS_MISSING), and, once a run,
 * when they are a large share two windows in a row, answers Native.skipStatus() with "&lt;percent&gt; &lt;causes&gt;" (vka_exec.c nxhost_skip_status; DM_VK_SKIP_WARN is the
 * threshold, 0 = off). GameActivity polls it and shows this text once, small and dismissible.
 */
final class SkipNotice {
    private SkipNotice() {}

    /** The host's share of skipped draws in percent, 0 for no warning (an empty, missing or garbled status) */
    static int percent(String status) {
        if (status == null) return 0;
        String s = status.trim();
        int sp = s.indexOf(' ');
        try {
            int p = Integer.parseInt(sp < 0 ? s : s.substring(0, sp));
            return p >= 1 && p <= 100 ? p : 0;
        } catch (NumberFormatException e) { return 0; }
    }

    /** What the host named as the causes (for the log); empty when it named none */
    static String causes(String status) {
        if (status == null) return "";
        String s = status.trim();
        int sp = s.indexOf(' ');
        return sp < 0 ? "" : s.substring(sp + 1).trim();
    }

    /** Whether the largest cause is a device without mesh shading (the host names it first, vka_skipwarn.h): no other driver is known to help, so the notice only says what is missing */
    static boolean noMesh(String status) { return causes(status).startsWith("mesh draws without VK_EXT_mesh_shader"); }

    /** The notice for a device without mesh shading: honest about the cause */
    static String textNoMesh(int percent) {
        return "This GPU has no mesh shading, so some models are missing from the picture (" + percent + " % of draws skipped).";
    }

    /**
     * Whether the largest cause is shaders the pack's shader library does not have (vka_skipwarn.h VKS_MISSING): a pack made from an incomplete dump or by an older
     * split (BananaNative #356: a Switch 2 Edition prepared without its add-on's shaders). No driver draws a shader that is not there, so the notice names none
     */
    static boolean missingShaders(String status) { return causes(status).startsWith("shaders missing from the pack"); }

    /** The notice for shaders missing from the pack: not the driver; prepare the game again (the game page's Pack › Prepare again) */
    static String textMissingShaders(int percent) {
        return "Part of the picture is not drawn: this game's pack lacks shaders the game uses (" + percent + " % of draws skipped). Another GPU driver will not change this. "
                + "Prepare the game again from a complete dump: its page › Prepare again.";
    }

    /**
     * The notice: what is wrong, in one line, with no driver advice (the settings' pick is not what runs when a DM_VK_DRIVER line of env.txt or the game's own lines wins, so the
     * notice never names a driver; the log line does, {@link #driverName}). The host counts only skips of the device: a pipeline still being made in the background is none
     * (vka_skipwarn.h vks_pipeline_counts), and a draw the game's own frustum culling never submits (OoT's draw distance) is no skip at all
     */
    static String text(int percent) {
        return "Part of the picture is not drawn (" + percent + " % of draws skipped).";
    }

    /**
     * The driver a run has, from the last DM_VK_DRIVER= line of its final environment (the launcher's pick, then env.txt and the game's own lines, as the host reads them): null
     * when there is none (the system's), the folder's label (label: folder path → label or null) or the folder's name for one this app does not list
     */
    static String driverName(String line, java.util.function.Function<String, String> label) {
        String v = line == null || !line.startsWith("DM_VK_DRIVER=") ? "" : line.substring("DM_VK_DRIVER=".length()).trim();
        if (v.isEmpty() || v.equalsIgnoreCase("system")) return "System";
        String l = label == null ? null : label.apply(v);
        if (l != null) return l;
        int s = v.lastIndexOf('/');
        return s >= 0 && s < v.length() - 1 ? v.substring(s + 1) : v;
    }
}
