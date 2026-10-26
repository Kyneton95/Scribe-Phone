package au.local.barkpocket;

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
    static final int VIOLET = Color.rgb(139, 92, 246);
    static final int BLUE = Color.rgb(56, 189, 248);
    static final int MINT = Color.rgb(110, 231, 183);
    static final int PEACH = Color.rgb(253, 186, 116);
    static final int DANGER = Color.rgb(251, 113, 133);
    static final int TEXT = Color.rgb(245, 247, 255);
    static final int MUTED = Color.rgb(152, 162, 184);
    static final int SURFACE = Color.rgb(20, 24, 38);
    static final int SURFACE_2 = Color.rgb(28, 34, 52);
    static final int BG = Color.rgb(7, 8, 13);
    static final int BG_2 = Color.rgb(16, 20, 33);

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

    static GradientDrawable gradient(int start, int end, float radiusDp, Context context) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{start, end});
        d.setCornerRadius(dp(context, radiusDp));
        return d;
    }

    static GradientDrawable gradientVertical(int start, int end, float radiusDp, Context context) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{start, end});
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
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null);
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
