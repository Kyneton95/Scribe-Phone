package au.local.barkpocket;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** A calm sheet of warm paper with a faint dot grid and margin rule. */
final class PaperView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    PaperView(Context context) {
        super(context);
        setBackgroundColor(Ui.PAPER);
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        canvas.drawColor(Ui.PAPER);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.alpha(Ui.INK, 16));
        float step = Ui.dp(getContext(), 26);
        for (float x = step; x < w; x += step) {
            for (float y = step; y < h; y += step) {
                canvas.drawCircle(x, y, Ui.dp(getContext(), 0.8f), paint);
            }
        }

        // A faint red margin, like a ruled notebook.
        paint.setColor(Ui.alpha(Ui.TERRACOTTA, 38));
        paint.setStrokeWidth(Ui.dp(getContext(), 1));
        canvas.drawLine(Ui.dp(getContext(), 14), 0,
                Ui.dp(getContext(), 14), h, paint);
    }
}
