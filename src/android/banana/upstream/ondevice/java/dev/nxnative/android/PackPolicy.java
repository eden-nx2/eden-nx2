package dev.nxnative.android;

import java.util.Locale;

/**
 * PackService's automatic pause (ONDEVICE.md "The service and the screen"), with no Android classes so PackPolicyJvmTest runs it:
 * <ul>
 * <li>battery below 20 % and not charging: the job waits (the card says battery, never heat);</li>
 * <li>heat never pauses it: from thermal status SEVERE (3) the next step starts with ONE worker ({@link #workers}), never none. At critical status Android itself may close the
 *     app; the next start goes on from the step's kept results.</li>
 * </ul>
 * Jobs an older app paused for heat ("thermal") go on by themselves: {@link #reason} no longer returns it.
 */
final class PackPolicy {
    private PackPolicy() {}

    /** PowerManager.THERMAL_STATUS_* (API 29) */
    static final int NONE = 0, LIGHT = 1, MODERATE = 2, SEVERE = 3, CRITICAL = 4, EMERGENCY = 5, SHUTDOWN = 6;
    static final int BATTERY_MIN = 20;

    /** Why the job waits ("battery"), null when it may run. The other arguments are kept for the callers and the tests: heat is not a reason */
    static String reason(int batteryPct, boolean charging, int thermal, String was, boolean heatOverride) {
        return batteryPct < BATTERY_MIN && !charging ? "battery" : null;
    }

    /** Whether Go on anyway applies: never, there is no heat pause to lift */
    static boolean canOverride(String reason, int thermal) { return false; }

    /** The workers a step uses: what it would (wanted), one from SEVERE heat, never fewer than one */
    static int workers(int wanted, int thermal) { return thermal >= SEVERE ? 1 : Math.max(1, wanted); }

    static String thermalName(int s) {
        switch (s) {
            case NONE: return "none";
            case LIGHT: return "light";
            case MODERATE: return "moderate";
            case SEVERE: return "severe";
            case CRITICAL: return "critical";
            case EMERGENCY: return "emergency";
            case SHUTDOWN: return "shutdown";
            default: return "unknown";
        }
    }

    /** The readings behind a decision, for the step's log and the card: "thermal status 3 (severe), headroom 0.62, battery 80 %, charging" */
    static String readings(int batteryPct, boolean charging, int thermal, float headroom) {
        return "thermal status " + thermal + " (" + thermalName(thermal) + "), headroom " + (Float.isNaN(headroom) ? "not known" : String.format(Locale.ROOT, "%.2f", headroom))
                + ", battery " + batteryPct + " %" + (charging ? ", charging" : "");
    }
}
