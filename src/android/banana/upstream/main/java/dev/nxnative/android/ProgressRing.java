package dev.nxnative.android;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * A round progress indicator as a console or a phone shows an install on a game's icon: a faint track and an arc from the top that grows with the progress; with
 * no known progress (set(-1)) a short arc that goes round. With showPercent the percentage is written in the middle.
 */
final class ProgressRing extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float progress = -1f, spin;
    private boolean percent;
    private String glyph;   // written in the middle in place of the percentage (a paused or failed job)
    private int color, glyphColor = 0xFFFFFFFF;
    private ValueAnimator spinner;

    ProgressRing(Context c, int color, boolean showPercent) {
        super(c);
        percent = showPercent;
        track.setStyle(Paint.Style.STROKE);
        track.setColor(0x55FFFFFF);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(color);
        this.color = color;
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** 0..1 for a known progress, a negative value for one that is not known (the arc goes round) */
    void set(float p) {
        progress = p;
        if (p < 0) startSpin(); else stopSpin();
        invalidate();
    }

    /** The arc's colour (a failed job: the danger colour) and the middle's glyph (null: the percentage, when the ring shows it); a state besides running reads without colour alone */
    void look(int arcColor, String middle) {
        arc.setColor(arcColor);
        glyph = middle;
        invalidate();
    }

    /** The arc's colour when it was made (running) */
    int baseColor() { return color; }

    private void startSpin() {
        if (spinner != null || !isAttachedToWindow()) return;
        spinner = ValueAnimator.ofFloat(0f, 360f);
        spinner.setDuration(1100);
        spinner.setInterpolator(new LinearInterpolator());
        spinner.setRepeatCount(ValueAnimator.INFINITE);
        spinner.addUpdateListener(a -> { spin = (Float) a.getAnimatedValue(); invalidate(); });
        spinner.start();
    }

    private void stopSpin() {
        if (spinner != null) { spinner.cancel(); spinner = null; }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (progress < 0) startSpin();
    }

    @Override protected void onDetachedFromWindow() {
        stopSpin();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight()), w = s * 0.11f;
        track.setStrokeWidth(w);
        arc.setStrokeWidth(w);
        box.set((getWidth() - s) / 2 + w / 2, (getHeight() - s) / 2 + w / 2, (getWidth() + s) / 2 - w / 2, (getHeight() + s) / 2 - w / 2);
        c.drawOval(box, track);
        if (progress >= 0) {
            c.drawArc(box, -90, 360 * Math.max(0.02f, Math.min(1f, progress)), false, arc);
            if (glyph != null) {
                text.setTextSize(s * 0.34f);
                c.drawText(glyph, getWidth() / 2f, getHeight() / 2f + s * 0.12f, text);
            } else if (percent) {
                text.setTextSize(s * 0.28f);
                c.drawText(Math.round(progress * 100) + "%", getWidth() / 2f, getHeight() / 2f + s * 0.1f, text);
            }
        } else c.drawArc(box, spin - 90, 100, false, arc);
    }
}
