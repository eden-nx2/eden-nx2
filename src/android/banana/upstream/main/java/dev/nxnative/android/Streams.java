package dev.nxnative.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** InputStream.readAllBytes / readNBytes are API 33 on Android (Java 9 methods the platform got with Android 13): the same on Android 12 */
final class Streams {
    private Streams() {}

    static byte[] readAll(InputStream in) throws IOException { return readUpTo(in, Integer.MAX_VALUE); }

    /** At most max bytes (fewer at the end of the stream), as InputStream.readNBytes(max) */
    static byte[] readUpTo(InputStream in, int max) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int left = max;
        while (left > 0) {
            int n = in.read(b, 0, Math.min(b.length, left));
            if (n < 0) break;
            o.write(b, 0, n);
            left -= n;
        }
        return o.toByteArray();
    }
}
