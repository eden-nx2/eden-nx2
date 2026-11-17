package dev.nxnative.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;

/**
 * The crop screen of a game's cover (Covers): the picked picture under a fixed square frame (the shape of Home's tile and of the game page's picture), moved with a
 * finger and zoomed with a pinch, or with a controller (D-pad or left stick moves, L1 / L2 and R1 / R2 or the triggers zoom, A or Start confirms, B or Back
 * cancels). Confirm cuts the frame's part (CropMath) out at most {@link Covers#MAX} px and keeps it (Covers.save). The picture is read once, here, from the one grant
 * the picker gave; it is decoded at most {@link #PREVIEW} px on the long side and turned upright by its EXIF orientation.
 */
public final class CropActivity extends Activity {
    static final String EXTRA_ID = "id";
    /** Results: SAVED (the cover changed), FAILED (the picture could not be read or kept); a cancel is RESULT_CANCELED */
    static final int SAVED = RESULT_FIRST_USER, FAILED = RESULT_FIRST_USER + 1;
    private static final int PREVIEW = 2560;

    private String id;
    private Bitmap src;
    private CropView view;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        id = getIntent().getStringExtra(EXTRA_ID);
        Uri u = getIntent().getData();
        src = id == null || u == null ? null : read(u);
        if (src == null) { setResult(FAILED); finish(); return; }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF101012);
        view = new CropView(this, new CropMath(src.getWidth(), src.getHeight()), src);
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView hint = Ui.text(this, Tx.s(R.string.game_cover_crop_hint), 13, R.color.bn_text_2, 500);
        hint.setTextColor(0xFFCFCFD4);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), 0);
        hint.setMaxLines(3);
        hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        bar.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        Ui.Action cancel = Ui.action(this, 0, Tx.s(R.string.common_cancel), Ui.Kind.TONAL, v -> cancel());
        Ui.Action ok = Ui.action(this, 0, Tx.s(R.string.game_cover_use), Ui.Kind.PRIMARY, v -> confirm());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 56));
        bp.setMargins(Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12));
        buttons.addView(cancel, bp);
        buttons.addView(ok, bp);
        bar.addView(buttons);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        root.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(s.left, s.top, s.right, s.bottom);
            return in;
        });
        setContentView(root);
        view.requestFocus();
    }

    /** The picture, upright, at most PREVIEW px on the long side; null if it cannot be read as a picture */
    private Bitmap read(Uri u) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(u)) { if (in == null) return null; BitmapFactory.decodeStream(in, null, o); }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int n = 1;
            while (Math.max(o.outWidth, o.outHeight) / (n * 2) >= PREVIEW) n *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = n;
            Bitmap b;
            try (InputStream in = getContentResolver().openInputStream(u)) { b = in == null ? null : BitmapFactory.decodeStream(in, null, o); }
            if (b == null) return null;
            int turn = 0;
            boolean flip = false;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                if (in != null) {
                    int ori = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                    switch (ori) {
                        case ExifInterface.ORIENTATION_ROTATE_90: turn = 90; break;
                        case ExifInterface.ORIENTATION_ROTATE_180: turn = 180; break;
                        case ExifInterface.ORIENTATION_ROTATE_270: turn = 270; break;
                        case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: flip = true; break;
                        case ExifInterface.ORIENTATION_FLIP_VERTICAL: flip = true; turn = 180; break;
                        case ExifInterface.ORIENTATION_TRANSPOSE: flip = true; turn = 90; break;
                        case ExifInterface.ORIENTATION_TRANSVERSE: flip = true; turn = 270; break;
                        default: break;
                    }
                }
            } catch (IOException | RuntimeException e) { /* no orientation: as stored */ }
            if (turn == 0 && !flip) return b;
            Matrix m = new Matrix();
            if (flip) m.postScale(-1, 1);
            m.postRotate(turn);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            return r;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private void cancel() { setResult(RESULT_CANCELED); finish(); }

    /** Cuts the frame's part at the kept size and saves it as the game's cover */
    private void confirm() {
        CropMath m = view.math;
        float[] r = m.rect();
        int[] o = m.outputSize(Covers.MAX);
        Bitmap out = null;
        boolean ok = false;
        try {
            out = Bitmap.createBitmap(o[0], o[1], Bitmap.Config.ARGB_8888);
            Rect from = new Rect(Math.round(r[0]), Math.round(r[1]), Math.round(r[2]), Math.round(r[3]));
            from.intersect(0, 0, src.getWidth(), src.getHeight());
            new Canvas(out).drawBitmap(src, from, new Rect(0, 0, o[0], o[1]), new Paint(Paint.FILTER_BITMAP_FLAG));
            ok = Covers.save(this, id, out);
        } catch (RuntimeException | OutOfMemoryError e) { /* too big for memory: FAILED below */ }
        if (out != null) out.recycle();
        setResult(ok ? SAVED : FAILED);
        finish();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (k) {
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) confirm();
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                if (e.getAction() == KeyEvent.ACTION_UP) cancel();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_BUTTON_L1: case KeyEvent.KEYCODE_BUTTON_R1: case KeyEvent.KEYCODE_BUTTON_L2: case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_PLUS: case KeyEvent.KEYCODE_EQUALS: case KeyEvent.KEYCODE_MINUS: case KeyEvent.KEYCODE_PAGE_UP: case KeyEvent.KEYCODE_PAGE_DOWN:
                if (down) view.key(k, e.getRepeatCount());
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != 0 && e.getAction() == MotionEvent.ACTION_MOVE) {
            view.stick(e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y),
                    Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)) - Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)));
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    /** The picture under the frame: draws it, dims the rest, and turns touches, keys and the stick into CropMath's pan and zoom */
    private static final class CropView extends View {
        final CropMath math;
        private final Bitmap bmp;
        private final Paint pic = new Paint(Paint.FILTER_BITMAP_FLAG), dim = new Paint(), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF frame = new RectF();
        private final Path veil = new Path();
        private final Matrix mx = new Matrix();
        private final float corner;
        private final ScaleGestureDetector pinch;
        private float lastX, lastY;
        private int lastN;
        private float sx, sy, sz;   // the stick and triggers now, -1..1
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (sx == 0 && sy == 0 && sz == 0) return;
                float step = Ui.dp(getContext(), 6);
                math.pan(-sx * step, -sy * step);
                if (sz != 0) math.zoomBy((float) Math.pow(1.025, sz), 0, 0);
                invalidate();
                postOnAnimation(this);
            }
        };

        CropView(CropActivity a, CropMath math, Bitmap bmp) {
            super(a);
            this.math = math;
            this.bmp = bmp;
            setFocusable(true);
            setFocusableInTouchMode(true);
            setDefaultFocusHighlightEnabled(false);
            corner = Ui.dp(a, 22);
            dim.setColor(0xB3000000);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Ui.dp(a, 2));
            edge.setColor(Ui.col(a, R.color.bn_banana));
            pinch = new ScaleGestureDetector(a, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    math.zoomBy(d.getScaleFactor(), d.getFocusX() - frame.centerX(), d.getFocusY() - frame.centerY());
                    invalidate();
                    return true;
                }
            });
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            // the square frame, centred in the room above the buttons (about 120 dp at the bottom)
            float room = Ui.dp(getContext(), 120), pad = Ui.dp(getContext(), 20);
            float side = Math.max(Ui.dp(getContext(), 64), Math.min(w - 2 * pad, h - room - pad));
            float l = (w - side) / 2f, t = Math.max(pad / 2f, (h - room - side) / 2f);
            frame.set(l, t, l + side, t + side);
            math.frame(side, side);
            veil.reset();
            veil.setFillType(Path.FillType.EVEN_ODD);
            veil.addRect(0, 0, w, h, Path.Direction.CW);
            veil.addRoundRect(frame, corner, corner, Path.Direction.CW);
        }

        @Override protected void onDraw(Canvas c) {
            float s = math.scale();
            mx.reset();
            mx.postScale(s, s);
            mx.postTranslate(frame.left + math.left(), frame.top + math.top());
            c.drawBitmap(bmp, mx, pic);
            c.drawPath(veil, dim);
            c.drawRoundRect(frame, corner, corner, edge);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            pinch.onTouchEvent(e);
            int n = e.getPointerCount();
            float x = 0, y = 0;
            int act = e.getActionMasked();
            int skip = act == MotionEvent.ACTION_POINTER_UP ? e.getActionIndex() : -1;
            int used = 0;
            for (int i = 0; i < n; i++) if (i != skip) { x += e.getX(i); y += e.getY(i); used++; }
            if (used > 0) { x /= used; y /= used; }
            if (act == MotionEvent.ACTION_MOVE && used == lastN && used > 0) { math.pan(x - lastX, y - lastY); invalidate(); }
            lastX = x;
            lastY = y;
            lastN = act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL ? 0 : used;
            return true;
        }

        /** A D-pad press moves by a step (a held one faster), a shoulder button or plus / minus zooms */
        void key(int k, int repeat) {
            float step = Ui.dp(getContext(), 8) * (1 + Math.min(repeat, 20) / 4f);
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT: math.pan(step, 0); break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: math.pan(-step, 0); break;
                case KeyEvent.KEYCODE_DPAD_UP: math.pan(0, step); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: math.pan(0, -step); break;
                case KeyEvent.KEYCODE_BUTTON_R1: case KeyEvent.KEYCODE_BUTTON_R2: case KeyEvent.KEYCODE_PLUS: case KeyEvent.KEYCODE_EQUALS: case KeyEvent.KEYCODE_PAGE_UP:
                    math.zoomBy(1.08f, 0, 0); break;
                default: math.zoomBy(1 / 1.08f, 0, 0); break;
            }
            invalidate();
        }

        /** The left stick pans and the triggers zoom while held (a tick repeats it each frame); a small push is ignored */
        void stick(float x, float y, float z) {
            sx = Math.abs(x) < 0.2f ? 0 : x;
            sy = Math.abs(y) < 0.2f ? 0 : y;
            sz = Math.abs(z) < 0.2f ? 0 : z;
            removeCallbacks(tick);
            postOnAnimation(tick);
        }
    }
}
