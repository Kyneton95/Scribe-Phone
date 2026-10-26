package au.local.barkpocket;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Reliable full-screen editor for the current draft (overlay windows cannot host an IME). */
public class DraftEditActivity extends Activity {
    private SessionStore store;
    private EditText editor;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = SessionStore.get(this);
        Window window = getWindow();
        // PhoneWindow#getInsetsController() dereferences mDecor.
        window.getDecorView();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setDecorFitsSystemWindows(false);
        android.view.WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            controller.setSystemBarsAppearance(
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        build();
    }

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.addView(new PaperView(this), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 22);
        body.setPadding(pad, Ui.dp(this, 22), pad, Ui.dp(this, 24));
        body.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(pad + bars.left, Ui.dp(this, 22) + bars.top,
                    pad + bars.right, Ui.dp(this, 24) + bars.bottom);
            return insets;
        });
        scroll.addView(body, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        android.widget.ImageButton close = Ui.icon(this, R.drawable.ic_close, Ui.MUTED, 38f,
                "Discard changes");
        close.setOnClickListener(v -> finish());
        header.addView(close);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titlesLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titlesLp.leftMargin = Ui.dp(this, 8);
        header.addView(titles, titlesLp);
        TextView title = Ui.text(this, "Edit draft", 21, Ui.TEXT, false);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        titles.addView(title);
        titles.addView(Ui.text(this, "Changes stay local until you add them to today’s note",
                11, Ui.MUTED, false));

        TextView save = new TextView(this);
        save.setText("Save");
        save.setTextSize(13.5f);
        save.setTextColor(Ui.PAPER);
        save.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        save.setGravity(Gravity.CENTER);
        save.setPadding(Ui.dp(this, 22), Ui.dp(this, 11),
                Ui.dp(this, 22), Ui.dp(this, 11));
        save.setBackground(Ui.rounded(Ui.INK, 20, this));
        save.setOnClickListener(v -> save());
        Ui.touchAnimation(save);
        header.addView(save);

        TextView hint = Ui.text(this,
                "Fix a word, add punctuation, or tighten a sentence. Empty the box to clear the draft.",
                12, Ui.MUTED, false);
        hint.setLineSpacing(Ui.dp(this, 3), 1.1f);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = Ui.dp(this, 22);
        body.addView(hint, hintLp);

        editor = new EditText(this);
        editor.setText(store.draft);
        editor.setHint("Your words will appear here…");
        editor.setHintTextColor(Ui.MUTED);
        editor.setTextColor(Ui.TEXT);
        editor.setTextSize(16.5f);
        editor.setLineSpacing(Ui.dp(this, 5), 1.1f);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setMinLines(10);
        editor.setPadding(Ui.dp(this, 16), Ui.dp(this, 15),
                Ui.dp(this, 16), Ui.dp(this, 15));
        editor.setBackground(Ui.outline(0xFFFFFDF7, 0x332E2B27, 22, this));
        LinearLayout.LayoutParams editorLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        editorLp.topMargin = Ui.dp(this, 10);
        body.addView(editor, editorLp);

        TextView footer = Ui.text(this,
                "The daily note itself is only touched after you tap Add to today’s note.",
                11, Ui.MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setLineSpacing(Ui.dp(this, 2), 1.1f);
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        footerLp.topMargin = Ui.dp(this, 14);
        body.addView(footer, footerLp);

        editor.postDelayed(() -> {
            editor.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
        }, 220);
    }

    private void save() {
        if (store.active || store.busy) {
            android.widget.Toast.makeText(this, "Wait for the current phrase to finish first.",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            store.setDraft(editor.getText().toString());
            Ui.haptic(editor);
            finish();
        } catch (Exception e) {
            android.widget.Toast.makeText(this,
                    e.getMessage() == null ? "Could not save the draft." : e.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
