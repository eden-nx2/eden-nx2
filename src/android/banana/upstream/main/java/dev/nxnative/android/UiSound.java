package dev.nxnative.android;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The launcher's UI sounds: a soft tick when the focus moves and a two-note chirp on a confirm. Both are made in code (16-bit PCM written to the cache), so the app
 * carries no sound file. A sound the user picked (Settings › Appearance) replaces both. Off with Prefs.uiSounds; the game's process never uses this.
 */
final class UiSound {
    private UiSound() {}

    private static SoundPool pool;
    private static int tick, select;
    private static boolean loaded;
    private static long lastTick;

    /** The user's own sound, where it is kept */
    static File custom(Context c) { return new File(c.getFilesDir(), "ui_sound"); }

    static synchronized void init(Context c) {
        if (pool != null) return;
        pool = new SoundPool.Builder().setMaxStreams(3)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        pool.setOnLoadCompleteListener((p, id, status) -> loaded = true);
        try {
            File cu = custom(c);
            if (cu.isFile()) { tick = select = pool.load(cu.getPath(), 1); return; }
            tick = pool.load(wav(c, "ui_tick.wav", new int[]{1500}, new int[]{28}, 0.35f), 1);
            select = pool.load(wav(c, "ui_select.wav", new int[]{660, 990}, new int[]{55, 85}, 0.5f), 1);
        } catch (IOException e) {
            android.util.Log.w("nxnative", "ui sound: " + e.getMessage());
        }
    }

    /** The sounds again after the user picked or cleared their own */
    static synchronized void reload(Context c) {
        if (pool != null) { pool.release(); pool = null; loaded = false; }
        init(c);
    }

    /** The focus moved (at most one tick every 60 ms) */
    static void tick(Context c) {
        if (!Prefs.uiSounds(c)) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTick < 60) return;
        lastTick = now;
        play(c, tick, 0.6f);
    }

    /** A tap or confirm */
    static void select(Context c) { if (Prefs.uiSounds(c)) play(c, select, 1f); }

    private static void play(Context c, int id, float vol) {
        init(c);
        if (loaded && id != 0) pool.play(id, vol, vol, 1, 0, 1f);
    }

    /** Copies the sound the user picked; false when it cannot be read as audio */
    static boolean pick(Context c, android.net.Uri uri) {
        File tmp = new File(c.getFilesDir(), "ui_sound.tmp");
        try (InputStream in = c.getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[16384];
            long n = 0;
            for (int r; (r = in.read(b)) > 0 && (n += r) <= 2_000_000; ) out.write(b, 0, r);   // a UI sound is short: 2 MB at most
            if (n > 2_000_000) { tmp.delete(); return false; }
        } catch (IOException | RuntimeException e) {
            tmp.delete();
            return false;
        }
        File f = custom(c);
        if (f.exists()) f.delete();
        boolean ok = tmp.renameTo(f);
        if (ok) reload(c);
        return ok;
    }

    static void clearCustom(Context c) { custom(c).delete(); reload(c); }

    /** A WAV of notes (Hz, ms) with a fade-out each, written once to the cache */
    private static String wav(Context c, String name, int[] hz, int[] ms, float amp) throws IOException {
        File f = new File(c.getCacheDir(), name);
        if (f.isFile()) return f.getPath();
        final int rate = 22050;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        for (int k = 0; k < hz.length; k++) {
            int n = rate * ms[k] / 1000;
            for (int i = 0; i < n; i++) {
                double env = Math.pow(1.0 - (double) i / n, 2) * Math.min(1.0, i / 40.0);
                short v = (short) (Math.sin(2 * Math.PI * hz[k] * i / rate) * env * amp * 32767);
                pcm.write(v & 0xFF);
                pcm.write((v >> 8) & 0xFF);
            }
        }
        byte[] d = pcm.toByteArray();
        try (FileOutputStream o = new FileOutputStream(f)) {
            java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes()).putInt(36 + d.length).put("WAVEfmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                    .putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(d.length);
            o.write(h.array());
            o.write(d);
        }
        return f.getPath();
    }
}
