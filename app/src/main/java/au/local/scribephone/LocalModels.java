package au.local.scribephone;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Catalog and storage for optional, user-downloaded on-device ASR models.
 * No model weights are shipped in the APK; the APK only carries the native
 * sherpa-onnx runtime.
 */
final class LocalModels {
    private static final String PREFS = "settings";
    static final String ENGINE = "engine";
    static final String SELECTED = "local_model";
    static final String ENGINE_SERVER = "server";
    static final String ENGINE_LOCAL = "local";

    static final String FAMILY_WHISPER = "whisper";
    static final String FAMILY_MOONSHINE = "moonshine";
    static final String FAMILY_PARAKEET = "parakeet";

    static final List<Spec> ALL;

    static {
        List<Spec> list = new ArrayList<>();
        list.add(new Spec(
                "whisper-tiny-en",
                "Whisper Tiny English",
                FAMILY_WHISPER,
                "Fastest Whisper model for everyday notes.",
                "~113 MB",
                118_000_000L,
                "sherpa-onnx-whisper-tiny.en.tar.bz2",
                "sherpa-onnx-whisper-tiny.en",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
                        + "sherpa-onnx-whisper-tiny.en.tar.bz2",
                new String[]{
                        "tiny.en-encoder.int8.onnx",
                        "tiny.en-decoder.int8.onnx",
                        "tiny.en-tokens.txt"
                },
                "MIT"));
        list.add(new Spec(
                "moonshine-tiny-en",
                "Moonshine Tiny English",
                FAMILY_MOONSHINE,
                "Low-latency English model for quick phrases.",
                "~103 MB",
                108_000_000L,
                "sherpa-onnx-moonshine-tiny-en-int8.tar.bz2",
                "sherpa-onnx-moonshine-tiny-en-int8",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
                        + "sherpa-onnx-moonshine-tiny-en-int8.tar.bz2",
                new String[]{
                        "preprocess.onnx",
                        "encode.int8.onnx",
                        "uncached_decode.int8.onnx",
                        "cached_decode.int8.onnx",
                        "tokens.txt"
                },
                "MIT"));
        list.add(new Spec(
                "parakeet-tdt-0.6b-v2-int8",
                "Parakeet TDT 0.6B v2",
                FAMILY_PARAKEET,
                "Best accuracy, but largest download and slower on CPU.",
                "~460 MB",
                482_468_385L,
                "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2",
                "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
                        + "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2",
                new String[]{
                        "encoder.int8.onnx",
                        "decoder.int8.onnx",
                        "joiner.int8.onnx",
                        "tokens.txt"
                },
                "CC-BY-4.0"));
        ALL = Collections.unmodifiableList(list);
    }

    private LocalModels() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isLocal(Context context) {
        return ENGINE_LOCAL.equals(prefs(context).getString(ENGINE, ENGINE_SERVER));
    }

    static void setEngine(Context context, boolean local) {
        prefs(context).edit().putString(ENGINE, local ? ENGINE_LOCAL : ENGINE_SERVER).apply();
    }

    static Spec byId(String id) {
        for (Spec spec : ALL) {
            if (spec.id.equals(id)) return spec;
        }
        return ALL.get(0);
    }

    static Spec selected(Context context) {
        return byId(prefs(context).getString(SELECTED, ALL.get(0).id));
    }

    static void select(Context context, Spec spec) {
        prefs(context).edit()
                .putString(SELECTED, spec.id)
                .putString(ENGINE, ENGINE_LOCAL)
                .apply();
    }

    static File modelsRoot(Context context) {
        return new File(context.getFilesDir(), "models");
    }

    static File modelDir(Context context, Spec spec) {
        return new File(modelsRoot(context), spec.id);
    }

    static boolean installed(Context context, Spec spec) {
        File dir = modelDir(context, spec);
        for (String name : spec.requiredFiles) {
            File file = new File(dir, name);
            if (!file.isFile() || file.length() <= 0) return false;
        }
        return true;
    }

    static File archive(Context context, Spec spec) {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) base = context.getFilesDir();
        return new File(new File(base, "scribe-models"), spec.archiveName);
    }

    static long installedBytes(Context context, Spec spec) {
        return size(modelDir(context, spec));
    }

    static long size(File file) {
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) total += size(child);
        }
        return total;
    }

    static String mb(long bytes) {
        if (bytes <= 0) return "0 MB";
        return String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0));
    }

    static void delete(Context context, Spec spec) {
        deleteTree(modelDir(context, spec));
        File archive = archive(context, spec);
        if (archive.exists()) archive.delete();
        File ready = new File(archive.getPath() + ".ready");
        if (ready.exists()) ready.delete();
        prefs(context).edit()
                .remove("download_id_" + spec.id)
                .apply();
        if (spec.id.equals(prefs(context).getString(SELECTED, ""))) {
            prefs(context).edit().remove(SELECTED).apply();
        }
    }

    static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteTree(child);
            }
        }
        file.delete();
    }

    static final class Spec {
        final String id;
        final String name;
        final String family;
        final String description;
        final String downloadLabel;
        final long downloadBytes;
        final String archiveName;
        final String root;
        final String url;
        final String[] requiredFiles;
        final String license;

        Spec(String id, String name, String family, String description,
             String downloadLabel, long downloadBytes, String archiveName,
             String root, String url, String[] requiredFiles, String license) {
            this.id = id;
            this.name = name;
            this.family = family;
            this.description = description;
            this.downloadLabel = downloadLabel;
            this.downloadBytes = downloadBytes;
            this.archiveName = archiveName;
            this.root = root;
            this.url = url;
            this.requiredFiles = requiredFiles;
            this.license = license;
        }
    }
}
