package au.local.barkpocket;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import java.io.File;

/** System DownloadManager wrapper for the optional model archives. */
final class ModelDownloader {
    static final int NONE = 0;
    static final int DOWNLOADING = 1;
    static final int DOWNLOADED = 2;
    static final int FAILED = 3;
    static final int INSTALLED = 4;

    private ModelDownloader() {
    }

    static long enqueue(Context context, LocalModels.Spec spec) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) throw new IllegalStateException("Downloads are unavailable on this device.");
        File archive = LocalModels.archive(context, spec);
        File parent = archive.getParentFile();
        if (parent == null) throw new IllegalStateException("Could not create the model download folder.");
        parent.mkdirs();
        if (archive.exists()) archive.delete();
        File ready = new File(archive.getPath() + ".ready");
        if (ready.exists()) ready.delete();

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(spec.url))
                .setTitle("Scribe · " + spec.name)
                .setDescription("Downloading " + spec.downloadLabel + " model")
                .setMimeType("application/x-bzip2")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS,
                        "scribe-models/" + spec.archiveName);
        long id = dm.enqueue(request);
        LocalModels.prefs(context).edit()
                .putLong("download_id_" + spec.id, id).apply();
        return id;
    }

    static void cancel(Context context, LocalModels.Spec spec) {
        long id = LocalModels.prefs(context).getLong("download_id_" + spec.id, -1);
        if (id != -1) {
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                try {
                    dm.remove(id);
                } catch (Exception ignored) {
                }
            }
        }
        LocalModels.prefs(context).edit().remove("download_id_" + spec.id).apply();
        File archive = LocalModels.archive(context, spec);
        if (archive.exists()) archive.delete();
        File ready = new File(archive.getPath() + ".ready");
        if (ready.exists()) ready.delete();
    }

    static Status status(Context context, LocalModels.Spec spec) {
        if (LocalModels.installed(context, spec)) {
            return new Status(INSTALLED, 0, 0, 100, "");
        }
        long id = LocalModels.prefs(context).getLong("download_id_" + spec.id, -1);
        if (id != -1 && context.getSystemService(Context.DOWNLOAD_SERVICE) != null) {
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            try (Cursor cursor = dm.query(new DownloadManager.Query().setFilterById(id))) {
                if (cursor != null && cursor.moveToFirst()) {
                    int state = cursor.getInt(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_STATUS));
                    long soFar = cursor.getLong(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total = cursor.getLong(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    int progress = total > 0
                            ? (int) Math.max(0, Math.min(100, soFar * 100 / total)) : 0;
                    if (state == DownloadManager.STATUS_SUCCESSFUL) {
                        File archive = LocalModels.archive(context, spec);
                        if (!archive.isFile() || archive.length() <= 0) {
                            LocalModels.prefs(context).edit()
                                    .remove("download_id_" + spec.id).apply();
                            return new Status(NONE, 0, 0, 0, "");
                        }
                        markReady(context, spec);
                        return new Status(DOWNLOADED, soFar, total, 100, "");
                    }
                    if (state == DownloadManager.STATUS_RUNNING
                            || state == DownloadManager.STATUS_PENDING
                            || state == DownloadManager.STATUS_PAUSED) {
                        return new Status(DOWNLOADING, soFar, total, progress, "");
                    }
                    if (state == DownloadManager.STATUS_FAILED) {
                        int reason = cursor.getInt(cursor.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_REASON));
                        return new Status(FAILED, soFar, total, progress,
                                reasonText(reason));
                    }
                } else {
                    LocalModels.prefs(context).edit()
                            .remove("download_id_" + spec.id).apply();
                }
            } catch (Exception e) {
                return new Status(FAILED, 0, 0, 0, e.getMessage() == null
                        ? "Download status unavailable." : e.getMessage());
            }
        }

        File archive = LocalModels.archive(context, spec);
        File ready = new File(archive.getPath() + ".ready");
        if (archive.isFile() && archive.length() > 0
                && (ready.isFile() || id == -1)) {
            return new Status(DOWNLOADED, archive.length(), archive.length(), 100, "");
        }
        return new Status(NONE, 0, 0, 0, "");
    }

    static void markReady(Context context, LocalModels.Spec spec) {
        File archive = LocalModels.archive(context, spec);
        if (!archive.isFile() || archive.length() <= 0) return;
        try {
            new File(archive.getPath() + ".ready").createNewFile();
        } catch (Exception ignored) {
        }
    }

    private static String reasonText(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_CANNOT_RESUME:
                return "Cannot resume the download.";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                return "Storage is unavailable.";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                return "The download file already exists.";
            case DownloadManager.ERROR_FILE_ERROR:
                return "The download folder could not be written.";
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
                return "Network data error.";
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return "Not enough storage for this model.";
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                return "The model download redirected too many times.";
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                return "The model server returned an unexpected response.";
            case DownloadManager.ERROR_UNKNOWN:
            default:
                return "The download failed.";
        }
    }

    static final class Status {
        final int state;
        final long downloaded;
        final long total;
        final int progress;
        final String reason;

        Status(int state, long downloaded, long total, int progress, String reason) {
            this.state = state;
            this.downloaded = downloaded;
            this.total = total;
            this.progress = progress;
            this.reason = reason;
        }
    }
}
