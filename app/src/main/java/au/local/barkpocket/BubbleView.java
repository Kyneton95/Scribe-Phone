package au.local.barkpocket;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The floating "chat head". A gradient orb with a slow halo, an animated
 * recording ring, drag/tap/long-press gestures and a draft badge.
 */
final class BubbleView extends View {
    interface Listener {
        void onTap();
        void onLongPress();
        void onDragStart();
        void onDrag(float dx, float dy);
        void onDragEnd();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable mic;
    private final ValueAnimator pulse;
    private final int slop;
    private Listener listener;
    private boolean recording;
    private boolean hasDraft;
    private float phase;
    private float downRawX, downRawY;
    private boolean dragging;
    private boolean longPressed;
    private boolean pressed;
    private final Runnable longPressRunnable = this::fireLongPress;

    BubbleView(Context context) {
        super(context);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setClickable(true);
        setFocusable(true);
        mic = context.getDrawable(R.drawable.ic_scribe_mark);
        slop = Ui.touchSlop(context);

        pulse = ValueAnimator.ofFloat(0f, 1f);
        pulse.setDuration(4800);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setInterpolator(new LinearInterpolator());
        pulse.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        setContentDescription("Scribe. Tap to open, hold to dictate.");
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setRecording(boolean recording) {
        if (this.recording == recording) return;
        this.recording = recording;
        setContentDescription(recording
                ? "Scribe is listening. Tap to open, hold to stop."
                : "Scribe. Tap to open, hold to dictate.");
        animate().scaleX(1.08f).scaleY(1.08f).setDuration(140).withEndAction(() ->
                animate().scaleX(1f).scaleY(1f).setDuration(220).start()).start();
        invalidate();
    }

    void setHasDraft(boolean hasDraft) {
        if (this.hasDraft == hasDraft) return;
        this.hasDraft = hasDraft;
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!pulse.isStarted()) pulse.start();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(longPressRunnable);
        pulse.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) return;
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) * 0.35f;
        float t = (float) (SystemClock.uptimeMillis() % 100000) / 1000f;

        float breathe = recording ? 1f + 0.045f * (float) Math.sin(t * 5.2) : 1f;
        r *= breathe;

        // Flat pencil-smudge aura, not a neon glow.
        float aura = Math.min(r * 2.1f, Math.min(w, h) / 2f - Ui.dp(getContext(), 1));
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.alpha(recording ? Ui.BRICK : Ui.TERRACOTTA, recording ? 34 : 20));
        canvas.drawCircle(cx, cy, aura, paint);
        paint.setColor(Ui.alpha(recording ? Ui.BRICK : Ui.TERRACOTTA, recording ? 24 : 13));
        canvas.drawCircle(cx, cy, aura * 0.78f, paint);

        // A single graphite/pencil orbit ring.
        stroke.setShader(null);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(getContext(), 2.2f));
        stroke.setColor(recording ? Ui.BRICK : Ui.PENCIL);
        stroke.setAlpha(recording ? 230 : 190);
        canvas.drawCircle(cx, cy, r + Ui.dp(getContext(), 5), stroke);

        if (recording) {
            stroke.setStrokeWidth(Ui.dp(getContext(), 1.2f));
            for (int i = 0; i < 3; i++) {
                float p = (phase + i / 3f) % 1f;
                float ringMax = Math.min(w, h) / 2f - Ui.dp(getContext(), 1);
                float ring = Math.min(ringMax, r + Ui.dp(getContext(), 5) + p * Ui.dp(getContext(), 16));
                stroke.setColor(Ui.alpha(Ui.BRICK, (int) (150 * (1f - p))));
                canvas.drawCircle(cx, cy, ring, stroke);
            }
        }

        // Solid graphite body.
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.INK);
        paint.setShadowLayer(Ui.dp(getContext(), 14), 0, Ui.dp(getContext(), 5), 0x55000000);
        canvas.drawCircle(cx, cy, r, paint);
        paint.clearShadowLayer();

        // A fine paper-coloured inner ring replaces the glass highlight.
        stroke.setColor(Ui.alpha(Ui.PAPER, 60));
        stroke.setStrokeWidth(Ui.dp(getContext(), 1.1f));
        canvas.drawCircle(cx, cy, r * 0.82f, stroke);

        int icon = Math.round(r * 0.78f);
        mic.setBounds(Math.round(cx - icon / 2f), Math.round(cy - icon / 2f),
                Math.round(cx + icon / 2f), Math.round(cy + icon / 2f));
        mic.setTint(Ui.PAPER);
        mic.draw(canvas);

        if (hasDraft) {
            paint.setShader(null);
            paint.setColor(Ui.PENCIL);
            canvas.drawCircle(w - Ui.dp(getContext(), 8), Ui.dp(getContext(), 8),
                    Ui.dp(getContext(), 5), paint);
            stroke.setShader(null);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Ui.dp(getContext(), 2));
            stroke.setColor(Ui.INK);
            canvas.drawCircle(w - Ui.dp(getContext(), 8), Ui.dp(getContext(), 8),
                    Ui.dp(getContext(), 5), stroke);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                dragging = false;
                longPressed = false;
                pressed = true;
                animate().scaleX(0.93f).scaleY(0.93f).setDuration(100).start();
                postDelayed(longPressRunnable, 430);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!dragging && Math.hypot(dx, dy) > slop) {
                    dragging = true;
                    removeCallbacks(longPressRunnable);
                    if (listener != null) listener.onDragStart();
                }
                if (dragging && listener != null) listener.onDrag(dx, dy);
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPressRunnable);
                animate().scaleX(1f).scaleY(1f).setDuration(220).start();
                if (dragging) {
                    if (listener != null) listener.onDragEnd();
                } else if (!longPressed) {
                    performClick();
                }
                dragging = false;
                pressed = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressRunnable);
                animate().scaleX(1f).scaleY(1f).setDuration(180).start();
                if (dragging && listener != null) listener.onDragEnd();
                dragging = false;
                pressed = false;
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void fireLongPress() {
        if (!pressed || dragging || longPressed) return;
        longPressed = true;
        animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).withEndAction(() ->
                animate().scaleX(1f).scaleY(1f).setDuration(240).start()).start();
        if (listener != null) listener.onLongPress();
    }

    @Override public boolean performClick() {
        super.performClick();
        if (listener != null) listener.onTap();
        return true;
    }
}
