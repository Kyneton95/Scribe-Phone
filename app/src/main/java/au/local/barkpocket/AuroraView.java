package au.local.barkpocket;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Slow aurora glow used behind the setup screen. */
final class AuroraView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ValueAnimator animator;
    private float phase;

    AuroraView(Context context) {
        super(context);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        animator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        animator.setDuration(18000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!animator.isStarted()) animator.start();
    }

    @Override protected void onDetachedFromWindow() {
        animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        paint.setShader(new LinearGradient(0, 0, 0, h,
                Ui.BG, Ui.BG_2, Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, w, h, paint);

        glow(canvas, w * (0.12f + 0.06f * (float) Math.sin(phase)),
                h * (0.18f + 0.04f * (float) Math.cos(phase * 0.8)),
                Math.max(w, h) * 0.55f, Ui.alpha(Ui.VIOLET, 95));
        glow(canvas, w * (0.90f + 0.05f * (float) Math.cos(phase * 0.7)),
                h * (0.32f + 0.05f * (float) Math.sin(phase * 0.9)),
                Math.max(w, h) * 0.50f, Ui.alpha(Ui.BLUE, 80));
        glow(canvas, w * (0.55f + 0.08f * (float) Math.sin(phase * 0.6 + 1.7)),
                h * (0.88f + 0.04f * (float) Math.cos(phase * 0.8)),
                Math.max(w, h) * 0.55f, Ui.alpha(Ui.MINT, 45));
    }

    private void glow(Canvas canvas, float cx, float cy, float radius, int color) {
        paint.setShader(new RadialGradient(cx, cy, radius,
                new int[]{color, Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, paint);
    }
}
