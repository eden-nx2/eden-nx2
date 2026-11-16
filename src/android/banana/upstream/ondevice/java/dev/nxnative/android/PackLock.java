package dev.nxnative.android;

/**
 * The one lock of the packer's process (:pack) over its embedded Python (PackNative: one tool at a time per process; a tool sets os.environ and sys.argv, shader_layer.py's
 * run_build among them, and the services set the tools' environment through PackEngine.setenv): a Prepare job (PackService) and the shader layer's build (LayerService)
 * never run a tool at once. A job holds it from its start (or resume) to its end, so no layer build starts between its steps; a layer build holds it while it runs.
 * <ul>
 * <li>A layer build starts only when nothing holds it ({@link #tryLayer}): while a job exists the watcher asks again later.</li>
 * <li>A job that starts while a layer build runs claims it at once ({@link #beginJob}: no new layer build from then on), asks the build to stop ({@link #layerStopAsked}, which
 *     the service reads before its tool starts, and PackEngine.interrupt for one that runs: the translation stops after the shader in hand, as at Prepare's Pause; what it
 *     translated stays in its result files) and waits for it to end ({@link #awaitLayerEnd}), its card saying "Waiting for the shader build to end".</li>
 * </ul>
 * No Android classes: the JVM test (LayerPendingJvmTest, dm/tools/tests/test_android_layer.py) runs it on the Mac.
 */
final class PackLock {
    private PackLock() {}

    /** The card's words while a job waits for the layer build (PackService) */
    static final String WAITING = "Waiting for the shader build to end";

    private static final Object LOCK = new Object();
    private static int jobs;
    private static boolean layer;

    /** A layer build may start now: true and it holds the lock (end it with {@link #endLayer}), false while a job exists or another layer build runs */
    static boolean tryLayer() {
        synchronized (LOCK) {
            if (jobs > 0 || layer) return false;
            layer = true;
            return true;
        }
    }

    static void endLayer() {
        synchronized (LOCK) {
            layer = false;
            LOCK.notifyAll();
        }
    }

    /** Whether a job has claimed the lock while the layer build holds it: the build is to stop (LayerService checks before its tool starts) */
    static boolean layerStopAsked() { synchronized (LOCK) { return jobs > 0; } }

    /** Whether a layer build runs now */
    static boolean layerRunning() { synchronized (LOCK) { return layer; } }

    /** Whether a job exists now (one started and not ended) */
    static boolean jobRunning() { synchronized (LOCK) { return jobs > 0; } }

    /** A job starts: no layer build starts from now on. → whether one runs now (the job then asks it to stop and waits: {@link #awaitLayerEnd}). End it with {@link #endJob} */
    static boolean beginJob() {
        synchronized (LOCK) {
            jobs++;
            return layer;
        }
    }

    static void endJob() {
        synchronized (LOCK) {
            if (jobs > 0) jobs--;
            LOCK.notifyAll();
        }
    }

    /** Waits at most ms for the layer build to end; → whether none runs now */
    static boolean awaitLayerEnd(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        synchronized (LOCK) {
            for (long left; layer && (left = end - System.currentTimeMillis()) > 0; ) LOCK.wait(left);
            return !layer;
        }
    }
}
