package dev.nxnative.android;

/**
 * The arithmetic of the cover crop screen (CropActivity): a picture of sw x sh pixels sits under a fixed frame, the player pans and zooms the picture, and the
 * result is the rectangle of the picture inside the frame. The picture always covers the frame (no empty edge): zoom 1 is the smallest picture that covers it, and
 * the pan is held so the frame stays inside the picture. The state is the picture point under the frame's centre ({@link #centerX}, {@link #centerY}, in picture
 * pixels) and the zoom; the frame is fw x fh view pixels. No Android classes: the JVM test (CropMathJvmTest) runs it.
 */
final class CropMath {
    static final float MAX_ZOOM = 8f;

    final int sw, sh;
    private float fw = 1, fh = 1;
    private float zoom = 1;
    private float cx, cy;

    CropMath(int sw, int sh) {
        this.sw = Math.max(1, sw);
        this.sh = Math.max(1, sh);
        cx = this.sw / 2f;
        cy = this.sh / 2f;
    }

    /** Sets the frame's size on the screen; the view calls it when it knows it. The crop (the part of the picture shown) is kept, as far as the new frame allows */
    void frame(float w, float h) {
        if (w < 1 || h < 1) return;
        fw = w;
        fh = h;
        clamp();
    }

    /** View pixels per picture pixel at zoom 1: the picture just covers the frame */
    float baseScale() { return Math.max(fw / sw, fh / sh); }

    /** View pixels per picture pixel now */
    float scale() { return baseScale() * zoom; }

    float zoom() { return zoom; }
    float centerX() { return cx; }
    float centerY() { return cy; }

    /** Drag: the picture follows the finger by (dx, dy) view pixels */
    void pan(float dx, float dy) {
        float s = scale();
        cx -= dx / s;
        cy -= dy / s;
        clamp();
    }

    /** Zoom by factor around the frame point (fx, fy), measured from the frame's centre in view pixels: the picture point under it stays under it */
    void zoomBy(float factor, float fx, float fy) {
        if (!(factor > 0)) return;
        float old = scale();
        zoom = Math.max(1f, Math.min(MAX_ZOOM, zoom * factor));
        float now = scale();
        cx += fx / old - fx / now;   // the picture point under (fx, fy) is cx + fx / scale: unchanged
        cy += fy / old - fy / now;
        clamp();
    }

    /** Where the picture's left and top edge are in the frame, in view pixels from the frame's top left (the view draws the picture there at scale()) */
    float left() { return fw / 2f - cx * scale(); }
    float top() { return fh / 2f - cy * scale(); }

    /** The part of the picture inside the frame: {left, top, right, bottom} in picture pixels */
    float[] rect() {
        float s = scale();
        float hw = fw / 2f / s, hh = fh / 2f / s;
        return new float[] {cx - hw, cy - hh, cx + hw, cy + hh};
    }

    /** The result's size in pixels: the frame's aspect, the long side at most maxLong and never more pixels than the picture has there */
    int[] outputSize(int maxLong) {
        float[] r = rect();
        float w = r[2] - r[0], h = r[3] - r[1];
        float k = Math.min(1f, maxLong / Math.max(w, h));
        return new int[] {Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k))};
    }

    private void clamp() {
        float s = scale();
        float hw = fw / 2f / s, hh = fh / 2f / s;
        cx = Math.max(hw, Math.min(sw - hw, cx));
        cy = Math.max(hh, Math.min(sh - hh, cy));
        if (hw * 2 > sw) cx = sw / 2f;   // rounding: the frame is as wide as the picture
        if (hh * 2 > sh) cy = sh / 2f;
    }
}
