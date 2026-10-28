package au.local.scribephone;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Small live-looking equaliser shown while the microphone is open. */
final class WaveformView extends View {
    private static final int BARS = 7;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ValueAnimator animator;
    private float phase;
    private boolean active;
    private float level;

    WaveformView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(Ui.dp(context, 3.2f));
        animator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        animator.setDuration(900);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            if (active) invalidate();
        });
    }

    void setActive(boolean active) {
        if (this.active == active) {
            if (active && !animator.isStarted()) animator.start();
            return;
        }
        this.active = active;
        if (active && !animator.isStarted()) animator.start();
        if (!active) animator.cancel();
        invalidate();
    }

    void setLevel(float level) {
        this.level = Math.max(0f, Math.min(1f, level));
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;
        if (active && !animator.isStarted()) animator.start();

        float gap = Ui.dp(getContext(), 3.4f);
        float barW = Math.max(Ui.dp(getContext(), 2.6f),
                (w - gap * (BARS - 1)) / BARS);
        float total = barW * BARS + gap * (BARS - 1);
        float x = (w - total) / 2f + barW / 2f;
        float center = h / 2f;
        float max = h * 0.42f;

        paint.setShader(null);
        paint.setColor(active ? Ui.TERRACOTTA : Ui.MUTED);
        for (int i = 0; i < BARS; i++) {
            float energy = active
                    ? 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(phase + i * 0.95f))
                    + level * 0.5f
                    : 0.12f;
            float half = Math.max(1.5f, max * Math.min(1f, energy));
            canvas.drawLine(x, center - half, x, center + half, paint);
            x += barW + gap;
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (active && !animator.isStarted()) animator.start();
    }

    @Override protected void onDetachedFromWindow() {
        animator.cancel();
        super.onDetachedFromWindow();
    }
}
