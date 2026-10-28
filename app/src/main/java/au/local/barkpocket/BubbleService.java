package au.local.barkpocket;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One foreground service that owns the floating bubble, the expanded chat card,
 * the microphone, the ordered upload queue and the daily-note commit.
 *
 * It is always a microphone-type foreground service. That is deliberate: the
 * service is started while the setup activity is visible, and the user can then
 * start a phrase from the bubble while another app is on screen. Starting a new
 * microphone foreground service from a background overlay tap is restricted on
 * Android 14, so the service stays in the foreground for its lifetime.
 */
public class BubbleService extends Service {
    static final String ACTION_SHOW = "au.local.barkpocket.SHOW";
    static final String ACTION_SHOW_PANEL = "au.local.barkpocket.SHOW_PANEL";
    static final String ACTION_HIDE = "au.local.barkpocket.HIDE";
    static final String ACTION_TOGGLE = "au.local.barkpocket.TOGGLE";
    static final String ACTION_STOP = "au.local.barkpocket.STOP";
    static final String ACTION_COMMIT = "au.local.barkpocket.COMMIT";
    static final String ACTION_CLEAR = "au.local.barkpocket.CLEAR";
    static final String ACTION_RETRY = "au.local.barkpocket.RETRY";

    private static final String PREFS = "settings";
    private static final String PREF_X = "bubble_x";
    private static final String PREF_Y = "bubble_y";
    private static final String CHANNEL = "scribe_bubble";
    private static final String TAG = "ScribeBubble";
    private static final int NOTIFICATION_ID = 41;

    private static final int SAMPLE_RATE = 16000;
    private static final int DEFAULT_PAUSE_MS = 4000;
    private static final long MAX_PHRASE_MS = 30000;
    private static final long PRE_ROLL_MS = 300;
    private static final long MAX_SESSION_MS = 60L * 60L * 1000L;
    private static final int MIN_VOICED_SAMPLES = 3200;

    private static volatile boolean running;
    private static volatile boolean ready;

    static boolean isRunning() {
        return running;
    }

    /** True once the foreground notification and overlay are actually in place. */
    static boolean isReady() {
        return ready;
    }

    private SessionStore store;
    private WindowManager windowManager;
    private NotificationManager notificationManager;
    private PowerManager.WakeLock wake;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean uploading = new AtomicBoolean(false);

    private BubbleView bubble;
    private PanelView panel;
    private TextView caption;
    private TextView dropTarget;
    private WindowManager.LayoutParams dropTargetLp;
    private int dropCenterX;
    private int dropCenterY;
    private boolean dropArmed;
    private WindowManager.LayoutParams bubbleLp;
    private WindowManager.LayoutParams panelLp;
    private WindowManager.LayoutParams captionLp;
    private int bubbleSize;
    private int captionGeneration;
    private int dragStartX;
    private int dragStartY;
    private boolean panelOpen;
    private boolean captionShown;
    private volatile boolean listening;
    private volatile boolean failed;
    private volatile boolean destroyed;
    private volatile float level;
    private volatile boolean commitWhenIdle;
    private long listeningStarted;
    private String lastNotificationKey = "";

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (bubble != null && !Settings.canDrawOverlays(BubbleService.this)) {
                store.error = "Display-over-other-apps permission was removed.";
                stopEverything();
                return;
            }
            refreshUi();
            ui.postDelayed(this, 450);
        }
    };

    private final Runnable hideCaptionRunnable = () -> hideCaptionNow(false);

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        ready = false;
        store = SessionStore.get(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        notificationManager = getSystemService(NotificationManager.class);
        bubbleSize = Ui.dp(this, 84);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIFICATION_ID, notice(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (Exception e) {
            ready = false;
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            ready = false;
            store.error = "Allow Scribe to display over other apps.";
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        try {
            showBubble();
            ready = true;
        } catch (Exception e) {
            store.error = message(e);
            stopEverything();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_HIDE.equals(action)) {
            stopEverything();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            stopRecording();
        } else if (ACTION_TOGGLE.equals(action)) {
            toggleRecording();
        } else if (ACTION_COMMIT.equals(action)) {
            requestCommit();
        } else if (ACTION_CLEAR.equals(action)) {
            clearDraft();
        } else if (ACTION_RETRY.equals(action)) {
            retry();
        } else if (ACTION_SHOW_PANEL.equals(action)) {
            showPanel();
        }

        ensureTicker();
        refreshUi();
        updateNotification();
        return START_NOT_STICKY;
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (bubbleLp != null && bubble != null) {
            bubbleLp.x = clampX(bubbleLp.x);
            bubbleLp.y = clampY(bubbleLp.y);
            try {
                windowManager.updateViewLayout(bubble, bubbleLp);
            } catch (Exception ignored) {
            }
        }
        if (panelOpen) hidePanel();
        hideCaptionNow(false);
        hideDropTarget();
    }

    @Override public void onDestroy() {
        destroyed = true;
        running = false;
        ready = false;
        listening = false;
        if (store != null) {
            store.active = false;
            store.busy = false;
        }
        ui.removeCallbacksAndMessages(null);
        removeOverlays();
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- overlay

    private void showBubble() {
        if (bubble != null) return;
        if (!Settings.canDrawOverlays(this)) {
            throw new IllegalStateException("Allow Scribe to display over other apps.");
        }
        bubble = new BubbleView(this);
        bubble.setListener(new BubbleView.Listener() {
            @Override public void onTap() {
                if (panelOpen) hidePanel(); else showPanel();
            }

            @Override public void onLongPress() {
                if (bubble != null) Ui.haptic(bubble);
                toggleRecording();
            }

            @Override public void onDragStart() {
                if (panelOpen) hidePanel();
                hideCaptionNow(false);
                if (bubbleLp != null) {
                    dragStartX = bubbleLp.x;
                    dragStartY = bubbleLp.y;
                }
                showDropTarget();
            }

            @Override public void onDrag(float dx, float dy) {
                if (bubbleLp == null) return;
                bubbleLp.x = clampX(dragStartX + Math.round(dx));
                bubbleLp.y = clampY(dragStartY + Math.round(dy));
                try {
                    windowManager.updateViewLayout(bubble, bubbleLp);
                } catch (Exception ignored) {
                }
                updateDropTarget();
            }

            @Override public void onDragEnd() {
                boolean close = dropArmed;
                hideDropTarget();
                if (close) {
                    if (bubble != null) Ui.haptic(bubble);
                    try {
                        android.widget.Toast.makeText(BubbleService.this, "Scribe closed",
                                android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {
                    }
                    stopEverything();
                } else {
                    snapBubble();
                }
            }
        });

        bubbleLp = new WindowManager.LayoutParams(
                bubbleSize, bubbleSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = clampX(prefs().getInt(PREF_X,
                Ui.screenWidth(this) - bubbleSize - Ui.dp(this, 12)));
        bubbleLp.y = clampY(prefs().getInt(PREF_Y,
                Math.round(Ui.screenHeight(this) * 0.62f)));
        windowManager.addView(bubble, bubbleLp);

        bubble.setAlpha(0f);
        bubble.setScaleX(0.55f);
        bubble.setScaleY(0.55f);
        bubble.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(340)
                .setInterpolator(new OvershootInterpolator(0.9f))
                .start();
    }

    private void snapBubble() {
        if (bubbleLp == null || bubble == null) return;
        int screen = Ui.screenWidth(this);
        int target = bubbleLp.x + bubbleSize / 2 < screen / 2
                ? Ui.dp(this, 10)
                : screen - bubbleSize - Ui.dp(this, 10);
        target = clampX(target);
        int from = bubbleLp.x;
        ValueAnimator animator = ValueAnimator.ofInt(from, target);
        animator.setDuration(240);
        animator.addUpdateListener(a -> {
            if (bubbleLp == null) return;
            bubbleLp.x = (int) a.getAnimatedValue();
            try {
                windowManager.updateViewLayout(bubble, bubbleLp);
            } catch (Exception ignored) {
            }
        });
        animator.start();
        prefs().edit().putInt(PREF_X, target).putInt(PREF_Y, bubbleLp.y).apply();
    }

    private void showPanel() {
        if (panelOpen || bubble == null) return;
        if (!Settings.canDrawOverlays(this)) {
            store.error = "Allow Scribe to display over other apps.";
            return;
        }
        try {
            showPanelInternal();
        } catch (Throwable t) {
            Log.e(TAG, "Could not open floating card", t);
            if (panel != null) {
                try {
                    windowManager.removeView(panel);
                } catch (Exception ignored) {
                }
                panel = null;
            }
            panelOpen = false;
            store.error = "Could not open the card: " + describe(t);
            store.status = "Card failed to open";
            try {
                android.widget.Toast.makeText(this, store.error,
                        android.widget.Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {
            }
            try {
                showCaption("Couldn’t open the card");
                refreshUi();
                updateNotification();
            } catch (Exception ignored) {
            }
        }
    }

    private void showPanelInternal() {
        if (panel != null && panel.getParent() != null) {
            panel.animate().cancel();
            panel.setAlpha(1f);
            panel.setScaleX(1f);
            panel.setScaleY(1f);
            panel.setTranslationY(0f);
            panelOpen = true;
            refreshUi();
            return;
        }
        if (panel == null) {
            panel = new PanelView(this, new PanelView.Listener() {
                @Override public void onRecordToggle() {
                    toggleRecording();
                }

                @Override public void onCommit() {
                    requestCommit();
                }

                @Override public void onEdit() {
                    editDraft();
                }

                @Override public void onCopy() {
                    copyDraft();
                }

                @Override public void onClear() {
                    clearDraft();
                }

                @Override public void onRetry() {
                    retry();
                }

                @Override public void onSettings() {
                    openSettings();
                }

                @Override public void onHide() {
                    stopEverything();
                }

                @Override public void onClose() {
                    hidePanel();
                }
            });
        }
        int availableWidth = Math.max(Ui.dp(this, 200),
                Ui.screenWidth(this) - Ui.dp(this, 16));
        int width = Math.max(1, Math.min(Ui.dp(this, 360), availableWidth));
        int availableHeight = Math.max(Ui.dp(this, 240),
                Ui.screenHeight(this) - Ui.statusBar(this) - Ui.navigationBar(this));
        panelLp = new WindowManager.LayoutParams(
                width, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST));
        positionPanel();
        panel.setAlpha(0f);
        panel.setScaleX(0.92f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(Ui.dp(this, 14));
        windowManager.addView(panel, panelLp);
        panel.post(() -> {
            if (panelLp == null || panel == null) return;
            positionPanel();
            try {
                windowManager.updateViewLayout(panel, panelLp);
            } catch (Exception ignored) {
            }
        });
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(300)
                .setInterpolator(new OvershootInterpolator(0.8f))
                .start();
        panelOpen = true;
        hideCaptionNow(false);
        refreshUi();
        ensureTicker();
    }

    private void positionPanel() {
        if (panelLp == null || panel == null) return;
        int screenW = Ui.screenWidth(this);
        int screenH = Ui.screenHeight(this);
        int status = Ui.statusBar(this);
        int nav = Ui.navigationBar(this);
        int width = panelLp.width;
        if (width <= 0) return;
        int height = Math.max(panel.getMeasuredHeight(), Ui.dp(this, 220))
                + Ui.dp(this, 80);
        boolean bubbleOnLeft = bubbleLp != null && bubbleLp.x + bubbleSize / 2 < screenW / 2;
        int x = bubbleOnLeft
                ? (bubbleLp == null ? Ui.dp(this, 8) : bubbleLp.x + bubbleSize - Ui.dp(this, 18))
                : (bubbleLp == null ? screenW - width - Ui.dp(this, 8)
                        : bubbleLp.x - width + Ui.dp(this, 18));
        x = Math.max(Ui.dp(this, 8), Math.min(x, screenW - width - Ui.dp(this, 8)));
        int y = bubbleLp == null ? status + Ui.dp(this, 12)
                : bubbleLp.y - Ui.dp(this, 34);
        int maxY = screenH - nav - Ui.dp(this, 12) - height;
        y = Math.max(status + Ui.dp(this, 8), Math.min(y, Math.max(status + Ui.dp(this, 8), maxY)));
        panelLp.x = x;
        panelLp.y = y;
    }

    private void hidePanel() {
        if (!panelOpen || panel == null) return;
        panelOpen = false;
        final PanelView closing = panel;
        closing.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f)
                .setDuration(160)
                .withEndAction(() -> {
                    try {
                        windowManager.removeView(closing);
                    } catch (Exception ignored) {
                    }
                }).start();
    }

    private void removeOverlays() {
        hideDropTarget();
        if (panel != null) {
            try {
                windowManager.removeView(panel);
            } catch (Exception ignored) {
            }
            panel = null;
        }
        if (caption != null) {
            try {
                windowManager.removeView(caption);
            } catch (Exception ignored) {
            }
            caption = null;
        }
        if (bubble != null) {
            try {
                windowManager.removeView(bubble);
            } catch (Exception ignored) {
            }
            bubble = null;
        }
        panelOpen = false;
        captionShown = false;
    }

    private void showDropTarget() {
        if (dropTarget != null) return;
        try {
            dropTarget = Ui.text(this, "Close", 12, Ui.INK, true);
            dropTarget.setGravity(Gravity.CENTER);
            dropTarget.setPadding(Ui.dp(this, 15), Ui.dp(this, 9),
                    Ui.dp(this, 15), Ui.dp(this, 9));
            dropTarget.setBackground(Ui.outline(Ui.SURFACE, 0x552E2B27, 22, this));
            setDropTargetIcon(Ui.INK);
            dropTarget.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            dropCenterX = Ui.screenWidth(this) / 2;
            dropCenterY = Ui.screenHeight(this) - Ui.navigationBar(this) - Ui.dp(this, 96);
            dropTargetLp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            dropTargetLp.gravity = Gravity.TOP | Gravity.START;
            dropTargetLp.x = dropCenterX - dropTarget.getMeasuredWidth() / 2;
            dropTargetLp.y = dropCenterY - dropTarget.getMeasuredHeight() / 2;
            windowManager.addView(dropTarget, dropTargetLp);
            dropTarget.setAlpha(0f);
            dropTarget.setScaleX(0.85f);
            dropTarget.setScaleY(0.85f);
            dropTarget.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(180).start();
            dropArmed = false;
        } catch (Exception e) {
            Log.w(TAG, "Could not show drop target", e);
            dropTarget = null;
        }
    }

    private void updateDropTarget() {
        if (dropTarget == null || bubbleLp == null) return;
        float x = bubbleLp.x + bubbleSize / 2f;
        float y = bubbleLp.y + bubbleSize / 2f;
        boolean armed = Math.hypot(x - dropCenterX, y - dropCenterY) < Ui.dp(this, 78);
        if (armed == dropArmed) return;
        dropArmed = armed;
        dropTarget.setBackground(armed
                ? Ui.rounded(Ui.BRICK, 22, this)
                : Ui.outline(Ui.SURFACE, 0x552E2B27, 22, this));
        dropTarget.setTextColor(armed ? Ui.PAPER : Ui.INK);
        setDropTargetIcon(armed ? Ui.PAPER : Ui.INK);
        dropTarget.animate().scaleX(armed ? 1.08f : 1f).scaleY(armed ? 1.08f : 1f)
                .setDuration(140).start();
        if (armed && bubble != null) Ui.haptic(bubble);
    }

    private void setDropTargetIcon(int tint) {
        android.graphics.drawable.Drawable icon = getDrawable(R.drawable.ic_close);
        if (icon == null) return;
        icon.setTint(tint);
        int size = Ui.dp(this, 14);
        icon.setBounds(0, 0, size, size);
        dropTarget.setCompoundDrawables(icon, null, null, null);
        dropTarget.setCompoundDrawablePadding(Ui.dp(this, 6));
    }

    private void hideDropTarget() {
        if (dropTarget == null) return;
        try {
            windowManager.removeView(dropTarget);
        } catch (Exception ignored) {
        }
        dropTarget = null;
        dropArmed = false;
    }

    private void showCaption(String text) {
        if (destroyed || panelOpen || text == null || text.trim().isEmpty()) return;
        try {
            if (caption == null) {
                caption = Ui.text(this, "", 12, Ui.TEXT, false);
                caption.setMaxLines(2);
                caption.setMaxWidth(Ui.dp(this, 260));
                caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
                caption.setPadding(Ui.dp(this, 12), Ui.dp(this, 8),
                        Ui.dp(this, 12), Ui.dp(this, 8));
                caption.setBackground(Ui.outline(Ui.SURFACE, 0x552E2B27, 16, this));
                caption.setElevation(Ui.dp(this, 8));
                captionLp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                captionLp.gravity = Gravity.TOP | Gravity.START;
            }
            caption.setText(text);
            caption.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(this, 260),
                            View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int cw = caption.getMeasuredWidth();
            int ch = caption.getMeasuredHeight();
            int x = bubbleLp == null ? Ui.dp(this, 12)
                    : bubbleLp.x + bubbleSize / 2 - cw / 2;
            x = Math.max(Ui.dp(this, 8), Math.min(x, Ui.screenWidth(this) - cw - Ui.dp(this, 8)));
            int y = bubbleLp == null ? Ui.statusBar(this) + Ui.dp(this, 12)
                    : bubbleLp.y - ch - Ui.dp(this, 8);
            if (bubbleLp != null && y < Ui.statusBar(this) + Ui.dp(this, 6)) {
                y = bubbleLp.y + bubbleSize + Ui.dp(this, 6);
            }
            captionLp.x = x;
            captionLp.y = y;
            if (!captionShown) {
                windowManager.addView(caption, captionLp);
                captionShown = true;
                caption.setAlpha(0f);
                caption.setTranslationY(Ui.dp(this, 6));
            } else {
                try {
                    windowManager.updateViewLayout(caption, captionLp);
                } catch (Exception ignored) {
                }
            }
            caption.animate().alpha(1f).translationY(0f).setDuration(200).start();
            int generation = ++captionGeneration;
            ui.removeCallbacks(hideCaptionRunnable);
            ui.postDelayed(() -> {
                if (generation == captionGeneration) hideCaptionNow(true);
            }, 4200);
        } catch (Exception ignored) {
        }
    }

    private void hideCaptionNow(boolean animate) {
        captionGeneration++;
        ui.removeCallbacks(hideCaptionRunnable);
        if (caption == null || !captionShown) return;
        if (!animate) {
            try {
                windowManager.removeView(caption);
            } catch (Exception ignored) {
            }
            caption = null;
            captionShown = false;
            return;
        }
        TextView closing = caption;
        caption = null;
        captionShown = false;
        closing.animate().alpha(0f).translationY(Ui.dp(this, 4)).setDuration(160)
                .withEndAction(() -> {
                    try {
                        windowManager.removeView(closing);
                    } catch (Exception ignored) {
                    }
                }).start();
    }

    private int clampX(int x) {
        return Math.max(Ui.dp(this, 8),
                Math.min(x, Ui.screenWidth(this) - bubbleSize - Ui.dp(this, 8)));
    }

    private int clampY(int y) {
        int min = Ui.statusBar(this) + Ui.dp(this, 8);
        int max = Ui.screenHeight(this) - bubbleSize - Ui.navigationBar(this) - Ui.dp(this, 8);
        return Math.max(min, Math.min(y, max));
    }

    // ---------------------------------------------------------------- actions

    private void toggleRecording() {
        if (listening) stopRecording(); else startRecording();
    }

    private void startRecording() {
        if (listening) return;
        if (store.busy) {
            store.status = "Finishing the last phrase…";
            showCaption("Finishing the last phrase…");
            refreshUi();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            store.error = "Microphone permission is needed.";
            store.status = "Allow the microphone in settings";
            showCaption("Allow the microphone in settings");
            refreshUi();
            updateNotification();
            return;
        }
        failed = false;
        commitWhenIdle = false;
        store.error = "";
        store.status = "Listening…";
        listening = true;
        listeningStarted = SystemClock.elapsedRealtime();
        store.active = true;
        acquireWake();
        new Thread(this::capture, "scribe-capture").start();
        if (bubble != null) Ui.haptic(bubble);
        showCaption("Listening…");
        refreshUi();
        updateNotification();
    }

    private void stopRecording() {
        if (!listening) return;
        listening = false;
        store.status = "Finishing transcription…";
        level = 0f;
        showCaption("Finishing…");
        refreshUi();
        updateNotification();
    }

    private void requestCommit() {
        if (!store.hasDraft() && !listening && store.pendingCount() == 0) {
            store.status = "Nothing to add yet";
            showCaption("Nothing to add yet");
            refreshUi();
            return;
        }
        if (failed) {
            store.status = "Retry the pending audio first";
            refreshUi();
            return;
        }
        commitWhenIdle = true;
        if (listening) {
            listening = false;
            store.status = "Finishing session…";
        } else if (store.busy || store.pendingCount() > 0) {
            store.status = "Finishing transcription…";
            if (!store.busy && store.pendingCount() > 0) kick();
        } else {
            maybeCommit();
            return;
        }
        if (bubble != null) Ui.haptic(bubble);
        refreshUi();
        updateNotification();
    }

    private void maybeCommit() {
        if (destroyed || !commitWhenIdle) return;
        if (listening || store.active || store.busy || store.pendingCount() > 0) {
            if (!failed) refreshUi();
            return;
        }
        if (failed) {
            commitWhenIdle = false;
            refreshUi();
            return;
        }
        commitWhenIdle = false;
        if (!store.hasDraft()) {
            store.status = "No speech to add yet";
            releaseWakeIfIdle();
            refreshUi();
            updateNotification();
            return;
        }
        try {
            String name = store.commit();
            store.status = "Added to " + name;
            if (panel != null && panelOpen) panel.showSaved(name);
            showCaption("Added to today’s note");
            if (bubble != null) Ui.haptic(bubble);
        } catch (Exception e) {
            store.error = message(e);
            store.status = "Couldn’t save the note";
            showCaption("Couldn’t save the note");
        }
        releaseWakeIfIdle();
        refreshUi();
        updateNotification();
    }

    private void retry() {
        if (store.busy) return;
        failed = false;
        store.error = "";
        store.status = "Retrying…";
        refreshUi();
        updateNotification();
        if (store.pendingCount() > 0) {
            kick();
        } else if (commitWhenIdle) {
            maybeCommit();
        } else {
            store.status = store.hasDraft() ? "Draft ready" : "Ready";
            releaseWakeIfIdle();
            refreshUi();
        }
    }

    private void clearDraft() {
        if (listening || store.busy) {
            store.status = "Stop recording first";
            refreshUi();
            return;
        }
        try {
            store.clearAll();
            commitWhenIdle = false;
            failed = false;
            store.status = "Cleared";
            showCaption("Draft cleared");
            if (bubble != null) Ui.haptic(bubble);
        } catch (Exception e) {
            store.error = message(e);
        }
        releaseWakeIfIdle();
        refreshUi();
        updateNotification();
    }

    private void copyDraft() {
        String text = store.draftText();
        if (text.trim().isEmpty()) return;
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Scribe draft", text));
        showCaption("Draft copied");
        if (bubble != null) Ui.haptic(bubble);
    }

    private void editDraft() {
        if (listening || store.busy) return;
        Intent intent = new Intent(this, DraftEditActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        hidePanel();
    }

    private void openSettings() {
        Intent intent = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_SETTINGS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        hidePanel();
    }

    private void stopEverything() {
        ready = false;
        listening = false;
        commitWhenIdle = false;
        removeOverlays();
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void acquireWake() {
        if (wake == null) {
            wake = ((PowerManager) getSystemService(POWER_SERVICE))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Scribe:session");
            wake.setReferenceCounted(false);
        }
        if (!wake.isHeld()) wake.acquire(MAX_SESSION_MS + 60_000L);
    }

    private void releaseWakeIfIdle() {
        if (listening || store.busy || store.pendingCount() > 0) return;
        if (wake != null && wake.isHeld()) wake.release();
    }

    // --------------------------------------------------------------- capture

    private void capture() {
        AudioRecord recorder = null;
        ByteArrayOutputStream phrase = new ByteArrayOutputStream();
        final int preSize = (int) (SAMPLE_RATE * PRE_ROLL_MS / 1000) * 2;
        int pauseMs = Math.max(1000, Math.min(10000,
                prefs().getInt("pause_ms", DEFAULT_PAUSE_MS)));
        final int silenceSamples = (int) ((long) SAMPLE_RATE * pauseMs / 1000);
        final int maxBytes = (int) (SAMPLE_RATE * MAX_PHRASE_MS / 1000) * 2;
        byte[] pre = new byte[preSize];
        int preFill = 0;
        int silence = 0;
        int voiced = 0;
        long began = SystemClock.elapsedRealtime();
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw new IOException("Microphone permission is not granted.");
            }
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IOException("16 kHz microphone unavailable.");
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 6400));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("Microphone could not initialise.");
            }
            recorder.startRecording();
            short[] frame = new short[320];
            while (listening) {
                int n = recorder.read(frame, 0, frame.length, AudioRecord.READ_BLOCKING);
                if (n <= 0) throw new IOException("Microphone read failed: " + n);
                byte[] bytes = new byte[n * 2];
                double energy = 0;
                for (int i = 0; i < n; i++) {
                    int sample = frame[i];
                    energy += (double) sample * sample;
                    bytes[2 * i] = (byte) sample;
                    bytes[2 * i + 1] = (byte) (sample >> 8);
                }
                double rms = Math.sqrt(energy / n) / 32768.0;
                level = (float) Math.min(1.0, rms / 0.12);
                boolean speech = rms >= 0.012;
                if (speech && phrase.size() == 0 && preFill > 0) {
                    phrase.write(pre, 0, preFill);
                    preFill = 0;
                }
                if (speech || phrase.size() > 0) {
                    phrase.write(bytes);
                    if (speech) {
                        voiced += n;
                        silence = 0;
                    } else {
                        silence += n;
                    }
                    if (phrase.size() >= maxBytes || silence >= silenceSamples) {
                        if (voiced >= MIN_VOICED_SAMPLES) emit(phrase.toByteArray());
                        phrase.reset();
                        silence = 0;
                        voiced = 0;
                        preFill = 0;
                    }
                } else {
                    int keep = Math.min(preFill, pre.length - bytes.length);
                    if (keep > 0) System.arraycopy(pre, preFill - keep, pre, 0, keep);
                    System.arraycopy(bytes, 0, pre, keep, bytes.length);
                    preFill = keep + bytes.length;
                }
                if (store.pendingCount() >= 120) {
                    store.error = "Pending audio limit reached. Stop and retry.";
                    listening = false;
                }
                if (SystemClock.elapsedRealtime() - began > MAX_SESSION_MS) {
                    store.error = "One-hour recording limit reached.";
                    listening = false;
                }
            }
        } catch (Exception e) {
            store.error = message(e);
        } finally {
            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (Exception ignored) {
                }
                recorder.release();
            }
            if (!destroyed) {
                listening = false;
                level = 0f;
                try {
                    if (phrase.size() > 0 && voiced >= MIN_VOICED_SAMPLES) {
                        emit(phrase.toByteArray());
                    }
                } catch (Exception e) {
                    store.error = message(e);
                }
                store.active = false;
                if (store.error.isEmpty()) {
                    store.status = commitWhenIdle ? "Finishing session…" : "Draft ready";
                } else {
                    store.status = "Couldn’t transcribe. Tap Retry.";
                }
                ui.post(() -> {
                    if (destroyed) return;
                    if (commitWhenIdle) maybeCommit();
                    else if (store.pendingCount() > 0) kick();
                    else if (!store.hasDraft() && store.error.isEmpty()) {
                        store.status = "Ready";
                    }
                    releaseWakeIfIdle();
                    refreshUi();
                    updateNotification();
                });
            }
        }
    }

    private void emit(byte[] pcm) throws Exception {
        File file = store.nextFile();
        Wav.write(file, pcm);
        kick();
    }

    private void kick() {
        if (destroyed || failed) return;
        if (!uploading.compareAndSet(false, true)) return;
        store.busy = true;
        store.status = "Transcribing…";
        ui.post(() -> {
            refreshUi();
            updateNotification();
        });
        new Thread(() -> {
            try {
                while (!destroyed) {
                    File[] pending = store.pending();
                    if (pending.length == 0) break;
                    store.status = pending.length == 1 ? "Transcribing…" : "Transcribing " + pending.length + "…";
                    String text = upload(pending[0]);
                    if (destroyed) break;
                    store.accept(pending[0], text);
                    String snippet = Ui.shorten(text, 90);
                    ui.post(() -> {
                        if (destroyed) return;
                        showCaption(snippet.isEmpty() ? "Transcribed" : snippet);
                        refreshUi();
                        updateNotification();
                    });
                }
            } catch (Exception e) {
                failed = true;
                listening = false;
                store.error = message(e);
                store.status = "Couldn’t transcribe. Tap Retry.";
                ui.post(() -> {
                    if (destroyed) return;
                    showCaption("Couldn’t reach the server");
                    refreshUi();
                    updateNotification();
                });
            } finally {
                store.busy = false;
                store.active = listening;
                uploading.set(false);
                ui.post(() -> {
                    if (destroyed) return;
                    if (!failed && store.pendingCount() > 0) {
                        // A phrase can land between the upload loop exiting and
                        // uploading being released; make sure it is not stranded.
                        kick();
                    } else if (!failed && commitWhenIdle && !listening && !store.active) {
                        maybeCommit();
                    } else if (!failed && !listening && !store.active) {
                        if (store.error.isEmpty()) {
                            store.status = store.hasDraft() ? "Draft ready" : "Ready";
                        }
                        refreshUi();
                        updateNotification();
                    } else {
                        releaseWakeIfIdle();
                        refreshUi();
                    }
                });
            }
        }, "scribe-upload").start();
    }

    private String upload(File audio) throws Exception {
        String endpoint = store.endpoint();
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new IOException("Set the server URL in settings.");
        }
        URL url = new URL(endpoint.trim());
        if (url.getHost() == null || url.getHost().isEmpty()
                || !(url.getProtocol().equals("http") || url.getProtocol().equals("https"))) {
            throw new IOException("Use a full HTTP/HTTPS server URL.");
        }
        String token = normalizedToken();
        try {
            return uploadOnce(url, token, audio);
        } catch (EmptyResponseException first) {
            // A bare host URL is almost always the server home page, not the
            // transcription route. Retry once at the conventional full path if
            // the proxy answered 2xx with no body.
            if (!isBareRoot(url)) throw emptyResponse(first.code);
            try {
                URL retry = new URL(url.getProtocol() + "://" + url.getAuthority()
                        + "/v1/audio/transcriptions");
                return uploadOnce(retry, token, audio);
            } catch (EmptyResponseException second) {
                throw emptyResponse(second.code);
            }
        }
    }

    private String uploadOnce(URL url, String token, File audio) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(180000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Scribe/0.4.3 (Android)");
        String boundary = "ScribeBubble" + System.nanoTime();
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (!token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        byte[] prefix = ("--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"phrase.wav\""
                + "\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(prefix.length + audio.length() + suffix.length);
        try {
            try (OutputStream out = connection.getOutputStream();
                 InputStream in = new FileInputStream(audio)) {
                out.write(prefix);
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                out.write(suffix);
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = "";
            if (stream != null) {
                try (InputStream in = stream;
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (out.size() + n > 1024 * 1024) {
                            throw new IOException("Server response too large.");
                        }
                        out.write(buffer, 0, n);
                    }
                    body = out.toString("UTF-8");
                }
            }
            if (code < 200 || code >= 300) {
                String detail = body.isBlank() ? "(empty response)" : snippet(body);
                throw new IOException("Server HTTP " + code + ": " + detail);
            }
            if (body.isBlank()) {
                throw new EmptyResponseException(code);
            }
            JSONObject json;
            try {
                json = new JSONObject(body);
            } catch (JSONException e) {
                throw new IOException("Server returned HTTP " + code
                        + " but not JSON: " + snippet(body), e);
            }
            if (!json.has("text") || json.isNull("text")) {
                throw new IOException("Server response has no text field.");
            }
            return json.getString("text");
        } finally {
            connection.disconnect();
        }
    }

    /** Accept a key with or without a copied "Bearer " / "Authorization:" prefix. */
    private String normalizedToken() {
        String token = prefs().getString("token", "");
        if (token == null) return "";
        token = token.trim();
        if (token.regionMatches(true, 0, "Authorization:", 0, 14)) {
            token = token.substring(14).trim();
        }
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).trim();
        }
        return token;
    }

    private static boolean isBareRoot(URL url) {
        String path = url.getPath();
        return path == null || path.isEmpty() || "/".equals(path);
    }

    private static String snippet(String body) {
        String oneLine = body.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 240 ? oneLine : oneLine.substring(0, 240) + "…";
    }

    private static IOException emptyResponse(int code) {
        return new IOException("Server returned an empty HTTP " + code + " response. "
                + "Use the full transcription URL (for example https://host/v1/audio/transcriptions), "
                + "or the server's LAN URL, and allow POST through Cloudflare/WAF. "
                + "This is usually a proxy or wrong-URL issue, not the token.");
    }

    private static final class EmptyResponseException extends IOException {
        final int code;

        EmptyResponseException(int code) {
            super("Empty HTTP " + code);
            this.code = code;
        }
    }

    // ----------------------------------------------------------- notification

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Floating dictation", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the Scribe bubble and dictation available.");
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification notice() {
        PendingIntent open = PendingIntent.getService(this, 1,
                new Intent(this, BubbleService.class).setAction(ACTION_SHOW_PANEL),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, BubbleService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent commit = PendingIntent.getService(this, 3,
                new Intent(this, BubbleService.class).setAction(ACTION_COMMIT),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent hide = PendingIntent.getService(this, 4,
                new Intent(this, BubbleService.class).setAction(ACTION_HIDE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text = notificationText();
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Scribe")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setColor(Ui.TERRACOTTA);
        if (listening) {
            builder.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stop),
                    "Stop", stop).build());
        }
        if (store.hasDraft() && !listening && !store.busy) {
            builder.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_check),
                    "Add to note", commit).build());
        }
        builder.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_power),
                "Hide", hide).build());
        return builder.build();
    }

    private String notificationText() {
        if (listening) return "Listening — tap to open the bubble";
        if (store.busy) return "Transcribing…";
        if (store.pendingCount() > 0) {
            int n = store.pendingCount();
            return n + (n == 1 ? " phrase waiting — tap Retry" : " phrases waiting — tap Retry");
        }
        if (store.hasDraft()) return "Draft ready — tap to review or add to today’s note";
        return "Tap the bubble to dictate";
    }

    private void updateNotification() {
        if (destroyed) return;
        String key = notificationText() + "|" + listening + "|" + store.hasDraft()
                + "|" + store.pendingCount() + "|" + store.busy;
        if (key.equals(lastNotificationKey)) return;
        lastNotificationKey = key;
        try {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                notificationManager.notify(NOTIFICATION_ID, notice());
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------- ui

    private void ensureTicker() {
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    private void refreshUi() {
        if (destroyed) return;
        if (bubble != null) {
            bubble.setRecording(listening);
            bubble.setHasDraft(store.hasDraft());
        }
        if (panelOpen && panel != null) {
            try {
                panel.setLevel(level);
                panel.render(listening, store.busy, store.draft, statusText(), store.error,
                        store.pendingCount(), commitWhenIdle, DailyNotes.fileName(),
                        !store.folder().isEmpty());
            } catch (Throwable t) {
                Log.e(TAG, "Could not render floating card", t);
                store.error = "Card error: " + describe(t);
                store.status = "Card failed";
                panelOpen = false;
                try {
                    windowManager.removeView(panel);
                } catch (Exception ignored) {
                }
                panel = null;
            }
        }
    }

    private String statusText() {
        if (listening) {
            long s = (SystemClock.elapsedRealtime() - listeningStarted) / 1000;
            return "Listening · " + (s / 60) + ":" + String.format(java.util.Locale.US, "%02d", s % 60)
                    + " · pause to transcribe";
        }
        if (store.busy) return "Transcribing…";
        if (commitWhenIdle) return "Finishing session…";
        if (store.error != null && !store.error.isEmpty()) return store.error;
        if (store.status != null && !store.status.isEmpty()) return store.status;
        return store.hasDraft() ? "Draft ready" : "Ready";
    }

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isEmpty()
                ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + ": " + message;
    }
}
