package dev.nxnative.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** The launcher's own background picture (Settings › Appearance › Background › My picture): picked once, scaled down and kept in the app's files */
final class UiLook {
    private UiLook() {}

    static File backgroundFile(Context c) { return new File(c.getFilesDir(), "ui_background.jpg"); }

    /** Reads the picked picture, scales its long side to 1920 px at most and keeps it; false when it is not a picture */
    static boolean pickBackground(Context c, android.net.Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
            if (o.outWidth <= 0) return false;
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 1920) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            Bitmap b;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) { b = BitmapFactory.decodeStream(in, null, o); }
            if (b == null) return false;
            try (FileOutputStream out = new FileOutputStream(backgroundFile(c))) { b.compress(Bitmap.CompressFormat.JPEG, 88, out); }
            b.recycle();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
