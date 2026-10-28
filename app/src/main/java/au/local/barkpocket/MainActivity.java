package au.local.barkpocket;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.URI;

/**
 * Setup and settings. When everything is configured, launching this activity
 * immediately summons the floating bubble and gets out of the way.
 */
public class MainActivity extends Activity {
    static final String EXTRA_SETTINGS = "au.local.barkpocket.EXTRA_SETTINGS";

    private static final int REQ_FOLDER = 22;
    private static final int REQ_PERMISSIONS = 23;

    private static final String DEFAULT_ENDPOINT =
            "http://192.168.20.14:24100/v1/audio/transcriptions";

    private SharedPreferences prefs;
    private SessionStore store;
    private EditText endpoint;
    private EditText token;
    private TextView micStatus;
    private TextView bubbleStatus;
    private TextView folderStatus;
    private TextView folderSubtitle;
    private TextView error;
    private TextView startButton;
    private TextView hideButton;
    private TextView noteLabel;
    private final TextView[] pausePills = new TextView[3];
    private static final int[] PAUSE_VALUES = {2000, 4000, 6000};
    private View content;
    private boolean settingsMode;
    private boolean awaitingOverlay;
    private boolean awaitingFolder;
    private boolean startWhenReady;
    private boolean autoStarted;
    private boolean handoffPending;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = SessionStore.get(this);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        settingsMode = settingsRequested(getIntent());
        edgeToEdge();
        if (!settingsMode && isReady()) {
            // Do not inflate the setup form just to hide it again. Keep a blank
            // paper window while the floating service starts, then finish.
            autoStarted = true;
            handoffPending = true;
            View blank = new View(this);
            blank.setBackgroundColor(Ui.PAPER);
            content = blank;
            setContentView(blank);
            return;
        }
        buildUi();
        updateStatus();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        settingsMode = settingsRequested(intent);
        if (settingsMode) {
            ensureSetupUi();
        } else if (content != null) {
            content.setVisibility(View.VISIBLE);
        }
        updateStatus();
        if (!settingsMode && isReady() && !autoStarted) {
            startBubble(true);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (handoffPending) {
            // Start only once the activity is actually visible, which keeps the
            // microphone foreground service legal on Android 14.
            content.post(() -> {
                if (handoffPending && !isFinishing() && !isDestroyed()) {
                    handoffPending = false;
                    startBubble(true);
                }
            });
        }
        awaitingFolder = false;
        updateStatus();
        if (awaitingOverlay) {
            awaitingOverlay = false;
            if (Settings.canDrawOverlays(this)) {
                if (startWhenReady) attemptStart();
            } else {
                startWhenReady = false;
                showError("Floating bubble access is still off.");
            }
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_FOLDER) return;
        awaitingFolder = false;
        if (result != RESULT_OK || data == null || data.getData() == null) {
            startWhenReady = false;
            return;
        }
        try {
            Uri tree = data.getData();
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            prefs.edit().putString("folder", tree.toString()).apply();
            showError(null);
            updateStatus();
            if (startWhenReady) attemptStart();
        } catch (Exception e) {
            startWhenReady = false;
            showError(e.getMessage() == null ? "The folder could not be opened." : e.getMessage());
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != REQ_PERMISSIONS) return;
        updateStatus();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            startWhenReady = false;
            showError("Microphone permission is needed to dictate.");
            return;
        }
        if (startWhenReady) attemptStart();
    }

    private static boolean settingsRequested(Intent intent) {
        Bundle extras = intent == null ? null : intent.getExtras();
        if (extras == null) return false;
        Object value = extras.get(EXTRA_SETTINGS);
        if (value instanceof Boolean) return (Boolean) value;
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private void edgeToEdge() {
        Window window = getWindow();
        // PhoneWindow#getInsetsController() dereferences mDecor; make sure the
        // decor exists before touching the controller from onCreate.
        window.getDecorView();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        }
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.addView(new PaperView(this),
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 22);
        body.setPadding(pad, Ui.dp(this, 24), pad, Ui.dp(this, 28));
        scroll.addView(body, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        body.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(pad + bars.left, Ui.dp(this, 24) + bars.top,
                    pad + bars.right, Ui.dp(this, 28) + bars.bottom);
            return insets;
        });
        content = body;
        setContentView(root);

        body.addView(brand());

        TextView description = Ui.text(this,
                "One floating button for your voice. Pause while you think, then add the finished draft to a single note for today.",
                14, Ui.TEXT, false);
        description.setLineSpacing(Ui.dp(this, 4), 1.12f);
        LinearLayout.LayoutParams descriptionLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        descriptionLp.topMargin = Ui.dp(this, 18);
        body.addView(description, descriptionLp);

        TextView eyebrow = Ui.text(this, "SETUP", 10.5f, Ui.MUTED, true);
        eyebrow.setLetterSpacing(0.16f);
        LinearLayout.LayoutParams eyebrowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        eyebrowLp.topMargin = Ui.dp(this, 28);
        body.addView(eyebrow, eyebrowLp);

        LinearLayout accessCard = card();
        body.addView(accessCard, cardLp());
        accessCard.addView(accessRow(R.drawable.ic_mic, "Microphone",
                "Used only while dictating", 0), rowLp());
        accessCard.addView(divider(), dividerLp());
        accessCard.addView(accessRow(R.drawable.ic_sparkle, "Floating bubble",
                "Display over other apps", 1), rowLp());
        accessCard.addView(divider(), dividerLp());
        accessCard.addView(accessRow(R.drawable.ic_folder, "Daily notes",
                "Where your note file lives", 2), rowLp());

        TextView dictationEyebrow = Ui.text(this, "DICTATION", 10.5f, Ui.MUTED, true);
        dictationEyebrow.setLetterSpacing(0.16f);
        LinearLayout.LayoutParams dictationLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dictationLp.topMargin = Ui.dp(this, 20);
        body.addView(dictationEyebrow, dictationLp);

        LinearLayout pauseCard = card();
        body.addView(pauseCard, cardLp());
        pauseCard.addView(Ui.text(this, "Pause before transcribing", 14, Ui.TEXT, true));
        TextView pauseHint = Ui.text(this,
                "Longer gives you room to think. Tap Stop to send immediately.",
                11, Ui.MUTED, false);
        pauseHint.setLineSpacing(Ui.dp(this, 2), 1.08f);
        LinearLayout.LayoutParams pauseHintLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pauseHintLp.topMargin = Ui.dp(this, 2);
        pauseCard.addView(pauseHint, pauseHintLp);

        LinearLayout pauseRow = new LinearLayout(this);
        pauseRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pauseRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pauseRowLp.topMargin = Ui.dp(this, 10);
        pauseCard.addView(pauseRow, pauseRowLp);
        String[] labels = {"Quick · 2 s", "Natural · 4 s", "Relaxed · 6 s"};
        for (int i = 0; i < labels.length; i++) {
            final int value = PAUSE_VALUES[i];
            TextView pill = new TextView(this);
            pill.setText(labels[i]);
            pill.setTextSize(11.5f);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(Ui.dp(this, 4), Ui.dp(this, 9),
                    Ui.dp(this, 4), Ui.dp(this, 9));
            pill.setOnClickListener(v -> {
                prefs.edit().putInt("pause_ms", value).apply();
                Ui.haptic(v);
                updatePausePills();
            });
            Ui.touchAnimation(pill);
            LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) pillLp.leftMargin = Ui.dp(this, 6);
            pauseRow.addView(pill, pillLp);
            pausePills[i] = pill;
        }

        TextView serverEyebrow = Ui.text(this, "SERVER", 10.5f, Ui.MUTED, true);
        serverEyebrow.setLetterSpacing(0.16f);
        LinearLayout.LayoutParams serverLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        serverLp.topMargin = Ui.dp(this, 20);
        body.addView(serverEyebrow, serverLp);

        LinearLayout serverCard = card();
        body.addView(serverCard, cardLp());
        serverCard.addView(Ui.text(this, "Transcription endpoint", 12, Ui.MUTED, false));
        endpoint = input(false);
        endpoint.setText(prefs.getString("endpoint", DEFAULT_ENDPOINT));
        LinearLayout.LayoutParams endpointLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        endpointLp.topMargin = Ui.dp(this, 7);
        serverCard.addView(endpoint, endpointLp);

        TextView tokenLabel = Ui.text(this, "API key / bearer token (optional)", 12, Ui.MUTED, false);
        LinearLayout.LayoutParams tokenLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tokenLp.topMargin = Ui.dp(this, 12);
        serverCard.addView(tokenLabel, tokenLp);
        token = input(true);
        token.setHint("Paste the key only (no 'Bearer')");
        token.setText(prefs.getString("token", ""));
        LinearLayout.LayoutParams tokenInputLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tokenInputLp.topMargin = Ui.dp(this, 7);
        serverCard.addView(token, tokenInputLp);

        TextView contract = Ui.text(this,
                "Posts one phrase as multipart WAV and expects {\"text\":\"…\"}. Use the full path, not the server home page; the key is sent as Authorization: Bearer <key>.",
                10.5f, Ui.MUTED, false);
        contract.setLineSpacing(Ui.dp(this, 2), 1.08f);
        LinearLayout.LayoutParams contractLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        contractLp.topMargin = Ui.dp(this, 10);
        serverCard.addView(contract, contractLp);

        error = Ui.text(this, "", 12, Ui.DANGER, false);
        error.setVisibility(View.GONE);
        LinearLayout.LayoutParams errorLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        errorLp.topMargin = Ui.dp(this, 10);
        body.addView(error, errorLp);

        startButton = new TextView(this);
        startButton.setText("Start floating bubble");
        startButton.setTextSize(15.5f);
        startButton.setTextColor(Ui.PAPER);
        startButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        startButton.setGravity(Gravity.CENTER);
        startButton.setBackground(Ui.rounded(Ui.INK, 28, this));
        startButton.setCompoundDrawablePadding(Ui.dp(this, 8));
        Drawable spark = getDrawable(R.drawable.ic_sparkle);
        spark.setTint(Ui.PAPER);
        spark.setBounds(0, 0, Ui.dp(this, 16), Ui.dp(this, 16));
        startButton.setCompoundDrawables(spark, null, null, null);
        startButton.setOnClickListener(v -> {
            startWhenReady = true;
            Ui.haptic(v);
            attemptStart();
        });
        Ui.touchAnimation(startButton);
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 58));
        startLp.topMargin = Ui.dp(this, 18);
        body.addView(startButton, startLp);

        hideButton = Ui.text(this, "Hide floating bubble", 12.5f, Ui.MUTED, true);
        hideButton.setGravity(Gravity.CENTER);
        hideButton.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
        hideButton.setOnClickListener(v -> {
            startService(new Intent(this, BubbleService.class)
                    .setAction(BubbleService.ACTION_HIDE));
            hideButton.postDelayed(this::updateStatus, 250);
        });
        body.addView(hideButton);

        noteLabel = Ui.text(this, "", 11, Ui.MUTED, false);
        noteLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        noteLp.topMargin = Ui.dp(this, 10);
        body.addView(noteLabel, noteLp);

        TextView privacy = Ui.text(this,
                "Drafts and audio stay private on this phone. Only the selected phrase is sent to your server. No cloud sync or analytics.",
                11, Ui.MUTED, false);
        privacy.setGravity(Gravity.CENTER);
        privacy.setLineSpacing(Ui.dp(this, 2), 1.1f);
        LinearLayout.LayoutParams privacyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        privacyLp.topMargin = Ui.dp(this, 10);
        body.addView(privacy, privacyLp);

        TextView github = Ui.text(this, "View source on GitHub", 11.5f, Ui.TERRACOTTA, true);
        github.setGravity(Gravity.CENTER);
        github.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 4));
        github.setOnClickListener(v -> openGitHub());
        body.addView(github);
    }

    private LinearLayout brand() {
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout mark = new FrameLayout(this);
        mark.setBackground(shapeCircle(Ui.rounded(Ui.PENCIL, 23, this)));
        ImageView sparkle = new ImageView(this);
        sparkle.setImageResource(R.drawable.ic_sparkle);
        sparkle.setColorFilter(Ui.INK);
        mark.addView(sparkle, new FrameLayout.LayoutParams(
                Ui.dp(this, 24), Ui.dp(this, 24), Gravity.CENTER));
        brand.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wordsLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        wordsLp.leftMargin = Ui.dp(this, 13);
        brand.addView(words, wordsLp);

        TextView title = Ui.text(this, "Scribe", 31, Ui.TEXT, false);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setLetterSpacing(-0.01f);
        words.addView(title);
        TextView tagline = Ui.text(this, "A thought, captured.", 13, Ui.MUTED, false);
        words.addView(tagline);

        return brand;
    }

    private LinearLayout accessRow(int iconRes, String title, String subtitle, int which) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));

        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(shapeCircle(Ui.rounded(Ui.SURFACE_ALT, 18, this)));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Ui.MUTED);
        badge.addView(icon, new FrameLayout.LayoutParams(
                Ui.dp(this, 18), Ui.dp(this, 18), Gravity.CENTER));
        row.addView(badge, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = Ui.dp(this, 11);
        row.addView(text, textLp);
        text.addView(Ui.text(this, title, 14, Ui.TEXT, true));
        TextView sub = Ui.text(this, subtitle, 11.5f, Ui.MUTED, false);
        text.addView(sub);

        TextView status = Ui.text(this, "", 11, Ui.MUTED, true);
        status.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5));
        row.addView(status);

        if (which == 0) {
            micStatus = status;
            row.setOnClickListener(v -> {
                startWhenReady = false;
                requestMic();
            });
        } else if (which == 1) {
            bubbleStatus = status;
            row.setOnClickListener(v -> {
                startWhenReady = false;
                requestOverlay();
            });
        } else {
            folderStatus = status;
            folderSubtitle = sub;
            row.setOnClickListener(v -> {
                startWhenReady = false;
                chooseFolder();
            });
        }
        Ui.touchAnimation(row);
        // The row is added to the card below; return a wrapper containing it.
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return wrapper;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 13), Ui.dp(this, 10),
                Ui.dp(this, 13), Ui.dp(this, 10));
        card.setBackground(Ui.outline(Ui.SURFACE, 0x332E2B27, 24, this));
        return card;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 9);
        return lp;
    }

    private LinearLayout.LayoutParams rowLp() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0x1A2E2B27);
        return v;
    }

    private LinearLayout.LayoutParams dividerLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 1));
        lp.leftMargin = Ui.dp(this, 47);
        return lp;
    }

    private EditText input(boolean password) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextSize(13.5f);
        edit.setTextColor(Ui.TEXT);
        edit.setHintTextColor(Ui.MUTED);
        edit.setPadding(Ui.dp(this, 13), Ui.dp(this, 11),
                Ui.dp(this, 13), Ui.dp(this, 11));
        edit.setBackground(Ui.outline(0xFFFFFDF7, 0x332E2B27, 14, this));
        edit.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        edit.setHint(password ? "Bearer token (optional)" : "http://server:port/v1/audio/transcriptions");
        return edit;
    }

    private void requestMic() {
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMISSIONS);
        } else {
            updateStatus();
        }
    }

    private void requestOverlay() {
        awaitingOverlay = true;
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            awaitingOverlay = false;
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Exception e2) {
                showError("Open Settings → Apps → Special access → Display over other apps.");
            }
        }
    }

    private void chooseFolder() {
        if (!awaitingFolder) awaitingFolder = true;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_FOLDER);
        } catch (Exception e) {
            awaitingFolder = false;
            startWhenReady = false;
            showError("The system folder picker could not be opened.");
        }
    }

    private void attemptStart() {
        String url = endpoint.getText().toString().trim();
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null
                    || !( "http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            prefs.edit().putString("endpoint", url)
                    .putString("token", token.getText().toString().trim()).apply();
        } catch (Exception e) {
            startWhenReady = false;
            showError("Enter a full HTTP/HTTPS transcription URL.");
            return;
        }
        showError(null);

        if (prefs.getString("folder", "").isEmpty()) {
            if (!awaitingFolder) {
                showError("Choose where your daily note should live.");
                chooseFolder();
            }
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestMic();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            if (!awaitingOverlay) requestOverlay();
            return;
        }
        startWhenReady = false;
        startBubble(!settingsMode);
    }

    private void startBubble(boolean openPanel) {
        autoStarted = true;
        try {
            boolean running = BubbleService.isRunning();
            Intent intent = new Intent(this, BubbleService.class)
                    .setAction(running
                            ? (openPanel ? BubbleService.ACTION_SHOW_PANEL : BubbleService.ACTION_SHOW)
                            : BubbleService.ACTION_SHOW);
            startForegroundService(intent);
            // Do not remove the visible task until the service has actually entered
            // the foreground and attached the overlay; Android ties microphone FGS
            // eligibility to the visible activity.
            awaitBubbleStart(System.currentTimeMillis() + 2200L);
        } catch (Exception e) {
            autoStarted = false;
            ensureSetupUi();
            showError(e.getMessage() == null ? "Could not start the bubble." : e.getMessage());
        }
    }

    private void awaitBubbleStart(long deadline) {
        content.postDelayed(() -> {
            if (BubbleService.isReady()) {
                finishAndRemoveTask();
            } else if (System.currentTimeMillis() >= deadline) {
                autoStarted = false;
                ensureSetupUi();
                showError("The floating bubble could not start. Check the permissions.");
            } else {
                awaitBubbleStart(deadline);
            }
        }, 50);
    }

    private boolean isReady() {
        return !prefs.getString("folder", "").isEmpty()
                && !prefs.getString("endpoint", "").isEmpty()
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
                && Settings.canDrawOverlays(this);
    }

    private void updateStatus() {
        if (micStatus == null) return;
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean overlay = Settings.canDrawOverlays(this);
        String folder = prefs.getString("folder", "");
        setPill(micStatus, mic ? "Ready" : "Allow", mic);
        setPill(bubbleStatus, overlay ? "On" : "Allow", overlay);
        setPill(folderStatus, folder.isEmpty() ? "Choose" : "Set", !folder.isEmpty());
        if (folderSubtitle != null) {
            folderSubtitle.setText(folder.isEmpty()
                    ? "Where your note file lives"
                    : DailyNotes.folderLabel(this, folder));
        }
        boolean running = BubbleService.isRunning();
        startButton.setText(running ? "Save and return to bubble" : "Start floating bubble");
        hideButton.setVisibility(running ? View.VISIBLE : View.GONE);
        noteLabel.setText("Today’s note · " + DailyNotes.fileName());
        updatePausePills();
    }

    private void updatePausePills() {
        int selected = prefs.getInt("pause_ms", 4000);
        for (int i = 0; i < pausePills.length; i++) {
            TextView pill = pausePills[i];
            if (pill == null) continue;
            boolean on = PAUSE_VALUES[i] == selected;
            pill.setTextColor(on ? Ui.PAPER : Ui.INK_SOFT);
            pill.setBackground(on
                    ? Ui.rounded(Ui.INK, 14, this)
                    : Ui.rounded(Ui.SURFACE_ALT, 14, this));
            pill.setTypeface(Typeface.create(on ? "sans-serif-medium" : "sans-serif",
                    Typeface.NORMAL));
        }
    }

    private void setPill(TextView view, String text, boolean ok) {
        view.setText(text);
        view.setTextColor(ok ? Ui.MINT : Ui.PEACH);
        view.setBackground(Ui.rounded(ok ? Ui.alpha(Ui.MINT, 26) : Ui.alpha(Ui.PEACH, 26),
                10, this));
    }

    private void ensureSetupUi() {
        if (error != null) return;
        handoffPending = false;
        autoStarted = false;
        buildUi();
        updateStatus();
    }

    private void showError(String message) {
        if (error == null) return;
        if (message == null || message.isEmpty()) {
            error.setVisibility(View.GONE);
        } else {
            error.setText(message);
            error.setVisibility(View.VISIBLE);
        }
    }

    private void openGitHub() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse(getString(R.string.github_url))));
        } catch (Exception ignored) {
        }
    }

    private static android.graphics.drawable.GradientDrawable shapeCircle(
            android.graphics.drawable.GradientDrawable gradient) {
        gradient.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        return gradient;
    }
}
