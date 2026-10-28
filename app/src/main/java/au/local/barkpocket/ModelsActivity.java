package au.local.barkpocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Download/install/select/delete optional on-device ASR models. */
public class ModelsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Row> rows = new ArrayList<>();
    private LinearLayout list;
    private boolean resumed;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            refresh();
            handler.postDelayed(this, 600);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        edgeToEdge();
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        refresh();
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(poll);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(poll);
        super.onDestroy();
    }

    private void edgeToEdge() {
        Window window = getWindow();
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
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.addView(new PaperView(this), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 22);
        body.setPadding(pad, Ui.dp(this, 24), pad, Ui.dp(this, 32));
        scroll.addView(body, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        body.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(pad + bars.left, Ui.dp(this, 24) + bars.top,
                    pad + bars.right, Ui.dp(this, 32) + bars.bottom);
            return insets;
        });
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(header);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        header.addView(titles, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView title = Ui.text(this, "On-device models", 28, Ui.TEXT, false);
        titles.addView(title);
        TextView sub = Ui.text(this, "Download once, then transcribe without a server.",
                12.5f, Ui.MUTED, false);
        sub.setLineSpacing(Ui.dp(this, 2), 1.08f);
        titles.addView(sub);
        ImageButton close = Ui.icon(this, R.drawable.ic_close, Ui.INK, 44f, "Close");
        close.setOnClickListener(v -> finish());
        header.addView(close);

        TextView intro = Ui.text(this,
                "The app includes the runtime, not model weights. Download models separately; "
                        + "they’re stored privately in the app. Wi-Fi is recommended; "
                        + "Parakeet is the largest download.",
                12, Ui.MUTED, false);
        intro.setLineSpacing(Ui.dp(this, 3), 1.1f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        introLp.topMargin = Ui.dp(this, 20);
        body.addView(intro, introLp);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        listLp.topMargin = Ui.dp(this, 16);
        body.addView(list, listLp);

        for (LocalModels.Spec spec : LocalModels.ALL) {
            Row row = new Row(spec);
            rows.add(row);
            list.addView(row.card, cardLp());
        }

        TextView note = Ui.text(this,
                "All models are English-only. Downloads come from sherpa-onnx GitHub releases "
                        + "and use MIT or CC-BY-4.0 licences. Downloads can continue when "
                        + "this screen is closed.",
                10.5f, Ui.MUTED, false);
        note.setLineSpacing(Ui.dp(this, 2), 1.08f);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        noteLp.topMargin = Ui.dp(this, 16);
        body.addView(note, noteLp);
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 12);
        return lp;
    }

    private void refresh() {
        for (Row row : rows) refresh(row);
    }

    private void refresh(Row row) {
        LocalModels.Spec spec = row.spec;
        ModelDownloader.Status status = row.busy
                ? new ModelDownloader.Status(ModelDownloader.DOWNLOADING, 0, 0, 0, "")
                : ModelDownloader.status(this, spec);
        boolean selected = LocalModels.selected(this).id.equals(spec.id)
                && LocalModels.installed(this, spec);

        if (row.busy) {
            row.status.setText("Installing model… This may take a minute.");
            row.progress.setVisibility(View.VISIBLE);
            row.progress.setIndeterminate(true);
            row.action.setText("Installing…");
            row.action.setEnabled(false);
            row.delete.setVisibility(View.GONE);
            return;
        }

        row.action.setEnabled(true);
        row.progress.setIndeterminate(false);
        if (selected) {
            row.card.setBackground(Ui.outline(Ui.SURFACE, 0xAA9E5638, 22, this));
        } else {
            row.card.setBackground(Ui.outline(Ui.SURFACE, 0x332E2B27, 22, this));
        }

        switch (status.state) {
            case ModelDownloader.INSTALLED:
                row.status.setText("Installed · " + LocalModels.mb(
                        LocalModels.installedBytes(this, spec)) + " · " + spec.license);
                row.progress.setVisibility(View.GONE);
                row.action.setText(selected ? "Active" : "Use");
                row.action.setEnabled(!selected);
                row.delete.setVisibility(View.VISIBLE);
                break;
            case ModelDownloader.DOWNLOADING:
                row.status.setText("Downloading · " + status.progress + "% · "
                        + LocalModels.mb(status.downloaded) + " / "
                        + LocalModels.mb(status.total));
                row.progress.setVisibility(View.VISIBLE);
                row.progress.setProgress(status.progress);
                row.action.setText("Cancel");
                row.delete.setVisibility(View.VISIBLE);
                break;
            case ModelDownloader.DOWNLOADED:
                row.status.setText("Downloaded · " + LocalModels.mb(
                        LocalModels.archive(this, spec).length()) + " — tap Install");
                row.progress.setVisibility(View.GONE);
                row.action.setText("Install");
                row.delete.setVisibility(View.VISIBLE);
                break;
            case ModelDownloader.FAILED:
                row.status.setText(status.reason == null || status.reason.isEmpty()
                        ? "Download failed." : status.reason);
                row.progress.setVisibility(View.GONE);
                row.action.setText("Retry");
                row.delete.setVisibility(View.VISIBLE);
                break;
            case ModelDownloader.NONE:
            default:
                row.status.setText(spec.description + " · " + spec.downloadLabel + " download");
                row.progress.setVisibility(View.GONE);
                row.action.setText("Download");
                row.delete.setVisibility(View.GONE);
                break;
        }
    }

    private void onAction(Row row) {
        LocalModels.Spec spec = row.spec;
        ModelDownloader.Status status = ModelDownloader.status(this, spec);
        if (LocalModels.installed(this, spec)) {
            LocalModels.select(this, spec);
            LocalAsr.release();
            refresh();
            Toast.makeText(this, spec.name + " selected", Toast.LENGTH_SHORT).show();
            return;
        }
        if (status.state == ModelDownloader.DOWNLOADING) {
            ModelDownloader.cancel(this, spec);
            refresh();
            return;
        }
        if (status.state == ModelDownloader.DOWNLOADED) {
            install(row);
            return;
        }
        try {
            ModelDownloader.enqueue(this, spec);
            refresh();
        } catch (Exception e) {
            row.status.setText(e.getMessage() == null ? "Could not start the download." : e.getMessage());
        }
    }

    private void install(Row row) {
        row.busy = true;
        refresh(row);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(() -> {
            Exception failure = null;
            try {
                ModelInstaller.install(this, row.spec);
            } catch (Exception e) {
                failure = e;
            }
            Exception finalFailure = failure;
            runOnUiThread(() -> {
                row.busy = false;
                getWindow().clearFlags(
                        android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                LocalAsr.release();
                refresh();
                if (finalFailure != null) {
                    // A bad/partial archive must be removed so the row returns to
                    // a Download action instead of offering Install forever.
                    ModelDownloader.cancel(this, row.spec);
                    Toast.makeText(this, finalFailure.getMessage() == null
                            ? "Could not install the model." : finalFailure.getMessage(),
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, row.spec.name + " installed", Toast.LENGTH_SHORT).show();
                }
            });
        }, "scribe-model-install").start();
    }

    private void confirmDelete(Row row) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + row.spec.name + "?")
                .setMessage("This removes the model from Scribe. You can download it again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    ModelDownloader.cancel(this, row.spec);
                    LocalModels.delete(this, row.spec);
                    LocalAsr.release();
                    refresh();
                })
                .show();
    }

    private final class Row {
        final LocalModels.Spec spec;
        final LinearLayout card;
        final TextView status;
        final ProgressBar progress;
        final TextView action;
        final TextView delete;
        boolean busy;

        Row(LocalModels.Spec spec) {
            this.spec = spec;
            card = new LinearLayout(ModelsActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(Ui.dp(ModelsActivity.this, 15), Ui.dp(ModelsActivity.this, 13),
                    Ui.dp(ModelsActivity.this, 15), Ui.dp(ModelsActivity.this, 13));
            card.setBackground(Ui.outline(Ui.SURFACE, 0x332E2B27, 22, ModelsActivity.this));

            TextView name = Ui.text(ModelsActivity.this, spec.name, 16, Ui.TEXT, true);
            card.addView(name);
            TextView meta = Ui.text(ModelsActivity.this,
                    spec.downloadLabel + " download · " + spec.license,
                    10.5f, Ui.MUTED, false);
            LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            metaLp.topMargin = Ui.dp(ModelsActivity.this, 2);
            card.addView(meta, metaLp);

            status = Ui.text(ModelsActivity.this, "", 11.5f, Ui.INK_SOFT, false);
            status.setLineSpacing(Ui.dp(ModelsActivity.this, 2), 1.06f);
            LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            statusLp.topMargin = Ui.dp(ModelsActivity.this, 8);
            card.addView(status, statusLp);

            progress = new ProgressBar(ModelsActivity.this, null,
                    android.R.attr.progressBarStyleHorizontal);
            progress.setMax(100);
            progress.setProgressTintList(ColorStateList.valueOf(Ui.TERRACOTTA));
            progress.setVisibility(View.GONE);
            LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(ModelsActivity.this, 4));
            progressLp.topMargin = Ui.dp(ModelsActivity.this, 8);
            card.addView(progress, progressLp);

            LinearLayout buttons = new LinearLayout(ModelsActivity.this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            buttonsLp.topMargin = Ui.dp(ModelsActivity.this, 10);
            card.addView(buttons, buttonsLp);

            action = Ui.text(ModelsActivity.this, "Download", 12, Ui.PAPER, true);
            action.setGravity(Gravity.CENTER);
            action.setPadding(Ui.dp(ModelsActivity.this, 15), Ui.dp(ModelsActivity.this, 9),
                    Ui.dp(ModelsActivity.this, 15), Ui.dp(ModelsActivity.this, 9));
            action.setBackground(Ui.rounded(Ui.INK, 18, ModelsActivity.this));
            action.setOnClickListener(v -> onAction(Row.this));
            buttons.addView(action);

            delete = Ui.text(ModelsActivity.this, "Delete", 11.5f, Ui.DANGER, true);
            delete.setPadding(Ui.dp(ModelsActivity.this, 12), Ui.dp(ModelsActivity.this, 8),
                    Ui.dp(ModelsActivity.this, 12), Ui.dp(ModelsActivity.this, 8));
            delete.setVisibility(View.GONE);
            delete.setOnClickListener(v -> confirmDelete(Row.this));
            LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            deleteLp.leftMargin = Ui.dp(ModelsActivity.this, 10);
            buttons.addView(delete, deleteLp);
        }
    }
}
