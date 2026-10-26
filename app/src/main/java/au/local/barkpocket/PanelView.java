package au.local.barkpocket;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** The expanded floating chat card. */
final class PanelView extends LinearLayout {
    interface Listener {
        void onRecordToggle();
        void onCommit();
        void onEdit();
        void onCopy();
        void onClear();
        void onRetry();
        void onSettings();
        void onHide();
        void onClose();
    }

    private final Context context;
    private final Listener listener;
    private final TextView subtitle;
    private final TextView intro;
    private final TextView draftView;
    private final TextView statusText;
    private final TextView retry;
    private final TextView errorText;
    private final TextView savedText;
    private final TextView fileLabel;
    private final TextView commitBtn;
    private final TextView editBtn;
    private final TextView copyBtn;
    private final TextView clearBtn;
    private final TextView confirmText;
    private final TextView confirmCancel;
    private final TextView confirmClear;
    private final LinearLayout confirmBar;
    private final ImageButton recordBtn;
    private final WaveformView wave;
    private final ScrollView scroll;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideSaved;
    private final ValueAnimator recordPulse;
    private String lastDraft = "";
    private boolean listening;
    private boolean confirmingClear;
    private boolean hasDraft;
    private boolean hasFolder;
    private int lastPending;

    PanelView(Context context, Listener listener) {
        super(context);
        this.context = context;
        this.listener = listener;
        setOrientation(VERTICAL);
        setPadding(Ui.dp(context, 18), Ui.dp(context, 15),
                Ui.dp(context, 18), Ui.dp(context, 16));
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF2161B2B, 0xF20B0D14});
        background.setCornerRadius(Ui.dp(context, 28));
        background.setStroke(Ui.dp(context, 1), 0x26FFFFFF);
        setBackground(background);
        setElevation(Ui.dp(context, 18));

        View accent = new View(context);
        accent.setBackground(Ui.gradient(Ui.VIOLET, Ui.BLUE, 2, context));
        addView(accent, new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(context, 3)));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams headerLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        headerLp.topMargin = Ui.dp(context, 12);
        addView(header, headerLp);

        FrameLayout avatar = new FrameLayout(context);
        avatar.setBackground(shapeCircle(Ui.gradient(Ui.VIOLET, Ui.BLUE, 19, context)));
        ImageView sparkle = new ImageView(context);
        sparkle.setImageResource(R.drawable.ic_sparkle);
        sparkle.setColorFilter(Color.WHITE);
        FrameLayout.LayoutParams sparkleLp = new FrameLayout.LayoutParams(
                Ui.dp(context, 19), Ui.dp(context, 19), Gravity.CENTER);
        avatar.addView(sparkle, sparkleLp);
        header.addView(avatar, new LayoutParams(Ui.dp(context, 38), Ui.dp(context, 38)));

        LinearLayout titleCol = new LinearLayout(context);
        titleCol.setOrientation(VERTICAL);
        LayoutParams titleLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = Ui.dp(context, 10);
        header.addView(titleCol, titleLp);
        TextView title = Ui.text(context, "Scribe", 17, Ui.TEXT, true);
        title.setLetterSpacing(0.015f);
        titleCol.addView(title);
        subtitle = Ui.text(context, "Ready", 11.5f, Ui.MUTED, false);
        titleCol.addView(subtitle);

        ImageButton settings = Ui.icon(context, R.drawable.ic_settings, Ui.MUTED, 34f,
                "Settings");
        settings.setOnClickListener(v -> listener.onSettings());
        header.addView(settings);
        ImageButton hide = Ui.icon(context, R.drawable.ic_power, Ui.MUTED, 34f,
                "Hide floating bubble");
        hide.setOnClickListener(v -> listener.onHide());
        header.addView(hide);
        ImageButton close = Ui.icon(context, R.drawable.ic_chevron_down, Ui.MUTED, 34f,
                "Collapse");
        close.setOnClickListener(v -> listener.onClose());
        header.addView(close);

        View divider = new View(context);
        divider.setBackgroundColor(0x14FFFFFF);
        LayoutParams dividerLp = new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(context, 1));
        dividerLp.topMargin = Ui.dp(context, 12);
        addView(divider, dividerLp);

        scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 4));
        int availableHeight = Ui.screenHeight(context) - Ui.statusBar(context)
                - Ui.navigationBar(context);
        int scrollHeight = Math.max(Ui.dp(context, 100),
                Math.min(Ui.dp(context, 208), availableHeight - Ui.dp(context, 260)));
        LayoutParams scrollLp = new LayoutParams(LayoutParams.MATCH_PARENT, scrollHeight);
        addView(scroll, scrollLp);

        LinearLayout chat = new LinearLayout(context);
        chat.setOrientation(VERTICAL);
        scroll.addView(chat, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        intro = Ui.text(context,
                "Tap the mic and speak. Pause while you think — I’ll stitch your pauses into one draft, then add it to today’s note.",
                13, Ui.MUTED, false);
        intro.setLineSpacing(Ui.dp(context, 3), 1.1f);
        chat.addView(intro);

        draftView = Ui.text(context, "", 16, Ui.TEXT, false);
        draftView.setTextIsSelectable(false);
        draftView.setPadding(Ui.dp(context, 15), Ui.dp(context, 13),
                Ui.dp(context, 15), Ui.dp(context, 13));
        draftView.setBackground(Ui.outline(0x14FFFFFF, 0x1FFFFFFF, 20, context));
        draftView.setLineSpacing(Ui.dp(context, 4), 1.08f);
        LayoutParams draftLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        draftLp.topMargin = Ui.dp(context, 10);
        draftView.setVisibility(GONE);
        chat.addView(draftView, draftLp);

        LinearLayout statusRow = new LinearLayout(context);
        statusRow.setOrientation(HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(Ui.dp(context, 10), Ui.dp(context, 7),
                Ui.dp(context, 10), Ui.dp(context, 7));
        statusRow.setBackground(Ui.rounded(0x0FFFFFFF, 16, context));
        LayoutParams statusLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = Ui.dp(context, 10);
        chat.addView(statusRow, statusLp);

        wave = new WaveformView(context);
        statusRow.addView(wave, new LayoutParams(Ui.dp(context, 74), Ui.dp(context, 24)));
        statusText = Ui.text(context, "Ready", 12, Ui.MUTED, false);
        statusText.setMaxLines(2);
        LayoutParams statusTextLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        statusTextLp.leftMargin = Ui.dp(context, 8);
        statusRow.addView(statusText, statusTextLp);
        retry = Ui.text(context, "Retry", 12, Ui.MINT, true);
        retry.setPadding(Ui.dp(context, 8), Ui.dp(context, 4),
                Ui.dp(context, 4), Ui.dp(context, 4));
        retry.setVisibility(GONE);
        retry.setOnClickListener(v -> listener.onRetry());
        statusRow.addView(retry);

        errorText = Ui.text(context, "", 12, Ui.DANGER, false);
        errorText.setPadding(Ui.dp(context, 10), Ui.dp(context, 8),
                Ui.dp(context, 10), Ui.dp(context, 8));
        errorText.setBackground(Ui.rounded(Ui.alpha(Ui.DANGER, 30), 12, context));
        errorText.setVisibility(GONE);
        LayoutParams errorLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        errorLp.topMargin = Ui.dp(context, 8);
        chat.addView(errorText, errorLp);

        savedText = Ui.text(context, "", 12, Ui.MINT, true);
        savedText.setPadding(Ui.dp(context, 10), Ui.dp(context, 8),
                Ui.dp(context, 10), Ui.dp(context, 8));
        savedText.setBackground(Ui.rounded(Ui.alpha(Ui.MINT, 28), 12, context));
        savedText.setVisibility(GONE);
        hideSaved = () -> savedText.setVisibility(GONE);
        LayoutParams savedLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        savedLp.topMargin = Ui.dp(context, 8);
        chat.addView(savedText, savedLp);

        fileLabel = Ui.text(context, "", 11, Ui.MUTED, false);
        LayoutParams fileLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        fileLp.topMargin = Ui.dp(context, 10);
        addView(fileLabel, fileLp);

        LinearLayout primary = new LinearLayout(context);
        primary.setOrientation(HORIZONTAL);
        primary.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams primaryLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        primaryLp.topMargin = Ui.dp(context, 8);
        addView(primary, primaryLp);

        recordBtn = new ImageButton(context);
        recordBtn.setImageResource(R.drawable.ic_mic);
        recordBtn.setColorFilter(Color.WHITE);
        recordBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        recordBtn.setPadding(Ui.dp(context, 16), Ui.dp(context, 16),
                Ui.dp(context, 16), Ui.dp(context, 16));
        recordBtn.setBackground(Ui.gradient(Ui.VIOLET, Ui.BLUE, 30, context));
        recordBtn.setContentDescription("Start dictation");
        Ui.touchAnimation(recordBtn);
        recordBtn.setOnClickListener(v -> listener.onRecordToggle());
        primary.addView(recordBtn, new LayoutParams(Ui.dp(context, 60), Ui.dp(context, 60)));

        commitBtn = new TextView(context);
        commitBtn.setText("Add to today’s note");
        commitBtn.setTextSize(13.5f);
        commitBtn.setTextColor(0xFF07111C);
        commitBtn.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        commitBtn.setGravity(Gravity.CENTER);
        commitBtn.setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 12), 0);
        commitBtn.setBackground(Ui.gradient(Ui.MINT, Ui.BLUE, 26, context));
        commitBtn.setOnClickListener(v -> {
            if (commitBtn.getTag() != null && Boolean.TRUE.equals(commitBtn.getTag())) {
                listener.onSettings();
            } else {
                listener.onCommit();
            }
        });
        Ui.touchAnimation(commitBtn);
        LayoutParams commitLp = new LayoutParams(0, Ui.dp(context, 52), 1f);
        commitLp.leftMargin = Ui.dp(context, 12);
        primary.addView(commitBtn, commitLp);

        LinearLayout secondary = new LinearLayout(context);
        secondary.setOrientation(HORIZONTAL);
        secondary.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams secondaryLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        secondaryLp.topMargin = Ui.dp(context, 8);
        addView(secondary, secondaryLp);

        editBtn = pill("Edit", R.drawable.ic_edit, Ui.MUTED, v -> listener.onEdit());
        copyBtn = pill("Copy", R.drawable.ic_copy, Ui.MUTED, v -> listener.onCopy());
        clearBtn = pill("Clear", R.drawable.ic_trash, Ui.MUTED, v -> toggleClearConfirm());
        secondary.addView(editBtn, pillLp(6));
        secondary.addView(copyBtn, pillLp(6));
        secondary.addView(clearBtn, pillLp(0));

        confirmBar = new LinearLayout(context);
        confirmBar.setOrientation(HORIZONTAL);
        confirmBar.setGravity(Gravity.CENTER_VERTICAL);
        confirmBar.setPadding(Ui.dp(context, 10), Ui.dp(context, 8),
                Ui.dp(context, 6), Ui.dp(context, 8));
        confirmBar.setBackground(Ui.rounded(Ui.alpha(Ui.DANGER, 28), 14, context));
        confirmBar.setVisibility(GONE);
        LayoutParams confirmLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        confirmLp.topMargin = Ui.dp(context, 8);
        addView(confirmBar, confirmLp);
        confirmText = Ui.text(context, "Clear this draft and audio?", 12, Ui.TEXT, false);
        confirmBar.addView(confirmText, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        confirmCancel = Ui.text(context, "Cancel", 12, Ui.MUTED, true);
        confirmCancel.setPadding(Ui.dp(context, 8), Ui.dp(context, 6),
                Ui.dp(context, 8), Ui.dp(context, 6));
        confirmCancel.setOnClickListener(v -> setClearConfirm(false));
        confirmBar.addView(confirmCancel);
        confirmClear = Ui.text(context, "Clear", 12, Ui.DANGER, true);
        confirmClear.setPadding(Ui.dp(context, 8), Ui.dp(context, 6),
                Ui.dp(context, 8), Ui.dp(context, 6));
        confirmClear.setOnClickListener(v -> {
            setClearConfirm(false);
            listener.onClear();
        });
        confirmBar.addView(confirmClear);

        recordPulse = ValueAnimator.ofFloat(1f, 1.07f);
        recordPulse.setDuration(820);
        recordPulse.setRepeatMode(ValueAnimator.REVERSE);
        recordPulse.setRepeatCount(ValueAnimator.INFINITE);
        recordPulse.setInterpolator(new LinearInterpolator());
        recordPulse.addUpdateListener(a -> {
            float s = (float) a.getAnimatedValue();
            recordBtn.setScaleX(s);
            recordBtn.setScaleY(s);
        });
    }

    void render(boolean listening, boolean busy, String draft, String status, String error,
                int pending, boolean commitRequested, String fileName, boolean hasFolder) {
        this.listening = listening;
        this.hasDraft = draft != null && !draft.trim().isEmpty();
        this.hasFolder = hasFolder;
        this.lastPending = pending;

        subtitle.setText(listening ? "Listening" : busy ? "Transcribing"
                : hasDraft ? "Draft ready" : "Ready");

        String safeDraft = draft == null ? "" : draft.trim();
        if (!safeDraft.equals(lastDraft)) {
            lastDraft = safeDraft;
            draftView.setText(safeDraft);
            if (!safeDraft.isEmpty()) {
                draftView.setAlpha(0.6f);
                draftView.animate().alpha(1f).setDuration(180).start();
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            }
        }
        draftView.setVisibility(this.hasDraft ? VISIBLE : GONE);
        intro.setVisibility(this.hasDraft ? GONE : VISIBLE);

        statusText.setText(status == null || status.isEmpty() ? "Ready" : status);
        wave.setActive(listening);

        errorText.setVisibility(error != null && !error.isEmpty() ? VISIBLE : GONE);
        if (error != null && !error.isEmpty()) errorText.setText(error);

        retry.setVisibility((pending > 0 && !listening) || (error != null && !error.isEmpty())
                ? VISIBLE : GONE);
        retry.setText(pending > 0 ? "Retry " + pending : "Retry");

        fileLabel.setText(hasFolder
                ? "Today · " + fileName
                : "No notes folder yet · tap Settings");

        boolean commitEnabled = (this.hasDraft || listening || pending > 0) && !busy;
        commitBtn.setEnabled(commitEnabled);
        commitBtn.setAlpha(commitEnabled ? 1f : 0.38f);
        boolean chooseFolder = this.hasDraft && !hasFolder;
        commitBtn.setTag(chooseFolder);
        commitBtn.setText(commitRequested ? "Finishing…"
                : chooseFolder ? "Choose notes folder"
                : listening ? "Finish & add to today’s note"
                : "Add to today’s note");

        boolean editable = this.hasDraft && !listening && !busy;
        editBtn.setAlpha(editable ? 1f : 0.35f);
        editBtn.setEnabled(editable);
        copyBtn.setAlpha(this.hasDraft ? 1f : 0.35f);
        copyBtn.setEnabled(this.hasDraft);
        clearBtn.setAlpha((this.hasDraft || pending > 0) && !listening && !busy ? 1f : 0.35f);
        clearBtn.setEnabled((this.hasDraft || pending > 0) && !listening && !busy);

        recordBtn.setImageResource(listening ? R.drawable.ic_stop : R.drawable.ic_mic);
        recordBtn.setContentDescription(listening ? "Stop dictation" : "Start dictation");
        recordBtn.setBackground(listening
                ? Ui.gradient(Ui.PEACH, Ui.DANGER, 30, context)
                : Ui.gradient(Ui.VIOLET, Ui.BLUE, 30, context));
        setRecordPulse(listening);
    }

    void setLevel(float level) {
        wave.setLevel(level);
    }

    void showSaved(String fileName) {
        savedText.setText("Added to " + fileName);
        savedText.setVisibility(VISIBLE);
        savedText.setAlpha(0f);
        savedText.setTranslationY(Ui.dp(context, 6));
        savedText.animate().alpha(1f).translationY(0f).setDuration(220).start();
        handler.removeCallbacks(hideSaved);
        handler.postDelayed(hideSaved, 5200);
    }

    void setClearConfirm(boolean show) {
        confirmingClear = show;
        confirmBar.setVisibility(show ? VISIBLE : GONE);
        clearBtn.setText(show ? "Clear?" : "Clear");
        if (show) {
            confirmBar.setAlpha(0f);
            confirmBar.animate().alpha(1f).setDuration(160).start();
        }
    }

    boolean isClearConfirming() {
        return confirmingClear;
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        recordPulse.cancel();
        super.onDetachedFromWindow();
    }

    private void toggleClearConfirm() {
        setClearConfirm(!confirmingClear);
    }

    private void setRecordPulse(boolean on) {
        if (on) {
            if (!recordPulse.isStarted()) recordPulse.start();
        } else {
            recordPulse.cancel();
            recordBtn.setScaleX(1f);
            recordBtn.setScaleY(1f);
        }
    }

    private TextView pill(String text, int iconRes, int color, OnClickListener click) {
        TextView v = new TextView(context);
        v.setText(text);
        v.setTextSize(12f);
        v.setTextColor(color);
        v.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        v.setGravity(Gravity.CENTER);
        v.setPadding(Ui.dp(context, 8), Ui.dp(context, 8),
                Ui.dp(context, 8), Ui.dp(context, 8));
        v.setBackground(Ui.rounded(0x12FFFFFF, 18, context));
        int size = Ui.dp(context, 14);
        Drawable icon = context.getDrawable(iconRes);
        icon.setTint(color);
        icon.setBounds(0, 0, size, size);
        v.setCompoundDrawables(icon, null, null, null);
        v.setCompoundDrawablePadding(Ui.dp(context, 5));
        v.setOnClickListener(click);
        Ui.touchAnimation(v);
        return v;
    }

    private LayoutParams pillLp(int endMargin) {
        LayoutParams lp = new LayoutParams(0, Ui.dp(context, 38), 1f);
        lp.rightMargin = Ui.dp(context, endMargin);
        return lp;
    }

    private static GradientDrawable shapeCircle(GradientDrawable gradient) {
        gradient.setShape(GradientDrawable.OVAL);
        return gradient;
    }
}
