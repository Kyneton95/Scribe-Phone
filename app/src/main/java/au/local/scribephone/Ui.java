package au.local.scribephone;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;

/** Small programmatic styling helpers shared by the overlay and setup screen. */
final class Ui {
    // Paper-and-pencil palette: warm paper, graphite ink, pencil yellow,
    // a little terracotta and sage. Deliberately nearly gradient-free.
    static final int PAPER = Color.rgb(244, 240, 230);
    static final int PAPER_DEEP = Color.rgb(233, 226, 212);
    static final int SURFACE = Color.rgb(251, 248, 241);
    static final int SURFACE_ALT = Color.rgb(237, 230, 216);
    static final int INK = Color.rgb(46, 43, 39);
    static final int INK_SOFT = Color.rgb(76, 71, 64);
    static final int MUTED = Color.rgb(122, 116, 106);
    static final int PENCIL = Color.rgb(228, 195, 106);
    static final int TERRACOTTA = Color.rgb(158, 86, 56);
    static final int SAGE = Color.rgb(95, 125, 91);
    static final int OCHRE = Color.rgb(169, 120, 37);
    static final int BRICK = Color.rgb(162, 68, 50);

    // Historical names kept for call sites; all map onto the paper palette.
    static final int VIOLET = INK;
    static final int BLUE = TERRACOTTA;
    static final int MINT = SAGE;
    static final int PEACH = OCHRE;
    static final int DANGER = BRICK;
    static final int TEXT = INK;
    static final int SURFACE_2 = SURFACE_ALT;
    static final int BG = PAPER;
    static final int BG_2 = PAPER_DEEP;

    private Ui() {
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(int color, float radiusDp, Context context) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(context, radiusDp));
        return d;
    }

    static GradientDrawable outline(int fill, int stroke, float radiusDp, Context context) {
        GradientDrawable d = rounded(fill, radiusDp, context);
        d.setStroke(dp(context, 1), stroke);
        return d;
    }

    static RippleDrawable ripple(int radiusDp, Context context) {
        GradientDrawable content = rounded(Color.TRANSPARENT, radiusDp, context);
        return new RippleDrawable(ColorStateList.valueOf(0x222E2B27), content, null);
    }

    static TextView text(Context context, CharSequence value, float sizeSp, int color,
                         boolean medium) {
        TextView v = new TextView(context);
        v.setText(value);
        v.setTextSize(sizeSp);
        v.setTextColor(color);
        v.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        v.setLineSpacing(dp(context, 2), 1.08f);
        return v;
    }

    static ImageButton icon(Context context, int res, int tint, float sizeDp, String description) {
        ImageButton b = new ImageButton(context);
        b.setImageResource(res);
        b.setColorFilter(tint);
        b.setBackground(ripple(22, context));
        b.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int pad = dp(context, 10);
        b.setPadding(pad, pad, pad, pad);
        b.setContentDescription(description);
        b.setMinimumWidth(dp(context, sizeDp));
        b.setMinimumHeight(dp(context, sizeDp));
        b.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                dp(context, sizeDp), dp(context, sizeDp)));
        touchAnimation(b);
        return b;
    }

    static void touchAnimation(View view) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(100).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                    break;
            }
            return false;
        });
    }

    static void haptic(View view) {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
    }

    static int screenWidth(Context context) {
        int width = 0;
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            Rect bounds = wm.getCurrentWindowMetrics().getBounds();
            width = bounds.width();
        } catch (Exception ignored) {
        }
        int display = context.getResources().getDisplayMetrics().widthPixels;
        if (width <= 0) width = display;
        return Math.max(1, Math.max(width, display));
    }

    static int screenHeight(Context context) {
        int height = 0;
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            Rect bounds = wm.getCurrentWindowMetrics().getBounds();
            height = bounds.height();
        } catch (Exception ignored) {
        }
        int display = context.getResources().getDisplayMetrics().heightPixels;
        if (height <= 0) height = display;
        return Math.max(1, Math.max(height, display));
    }

    static int statusBar(Context context) {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            WindowInsets insets = wm.getCurrentWindowMetrics().getWindowInsets();
            return Math.max(0, insets.getInsets(WindowInsets.Type.statusBars()).top);
        } catch (Exception ignored) {
            return 0;
        }
    }

    static int navigationBar(Context context) {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            WindowInsets insets = wm.getCurrentWindowMetrics().getWindowInsets();
            return Math.max(0, insets.getInsets(WindowInsets.Type.navigationBars()).bottom);
        } catch (Exception ignored) {
            return 0;
        }
    }

    static int touchSlop(Context context) {
        return ViewConfiguration.get(context).getScaledTouchSlop();
    }

    static String shorten(String text, int max) {
        if (text == null) return "";
        String s = text.trim();
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)).trim() + "…";
    }

    static int alpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
