package au.local.scribephone;

import android.content.Context;
import android.os.StatFs;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Extracts a downloaded .tar.bz2 model into app-private storage and verifies it. */
final class ModelInstaller {
    private static final long MIN_FREE = 80L * 1024 * 1024;

    private ModelInstaller() {
    }

    static void install(Context context, LocalModels.Spec spec) throws Exception {
        install(context, spec, true);
    }

    static void install(Context context, LocalModels.Spec spec, boolean checkSpace)
            throws Exception {
        File archive = LocalModels.archive(context, spec);
        if (!archive.isFile() || archive.length() <= 0) {
            throw new IOException("The model download is missing. Download it again.");
        }
        File root = LocalModels.modelsRoot(context);
        root.mkdirs();
        File target = LocalModels.modelDir(context, spec);
        File temp = new File(root, spec.id + ".installing");

        if (checkSpace) {
            StatFs stat = new StatFs(root.getPath());
            long free = stat.getAvailableBytes();
            long required = archive.length() * 3 + MIN_FREE;
            if (free < required) {
                throw new IOException("Not enough free storage. This model needs about "
                        + LocalModels.mb(required) + " free during installation.");
            }
        }

        LocalModels.deleteTree(temp);
        if (!temp.mkdirs()) throw new IOException("Could not create the model folder.");

        try (InputStream raw = new BufferedInputStream(new FileInputStream(archive));
             BZip2CompressorInputStream bz = new BZip2CompressorInputStream(raw, true);
             TarArchiveInputStream tar = new TarArchiveInputStream(bz)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = entry.getName();
                int slash = name.indexOf('/');
                if (slash < 0) continue;
                String relative = name.substring(slash + 1);
                if (relative.isEmpty()) continue;
                // Keep only the files the runtime uses. Whisper archives also
                // contain float32 models and every archive carries test WAVs.
                if (relative.startsWith("test_wavs/")) continue;
                if (LocalModels.FAMILY_WHISPER.equals(spec.family)
                        && relative.endsWith(".onnx")
                        && !relative.endsWith(".int8.onnx")) continue;
                File out = new File(temp, relative);
                String rootPath = temp.getCanonicalPath();
                String outPath = out.getCanonicalPath();
                if (!outPath.equals(rootPath)
                        && !outPath.startsWith(rootPath + File.separator)) {
                    throw new IOException("The model archive contains an unsafe path.");
                }
                if (entry.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs()) {
                        throw new IOException("Could not create " + relative);
                    }
                    continue;
                }
                if (entry.isSymbolicLink() || !entry.isFile()) continue;
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (BufferedOutputStream writer = new BufferedOutputStream(
                        new FileOutputStream(out))) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = tar.read(buffer)) != -1) writer.write(buffer, 0, n);
                }
            }
        } catch (Exception e) {
            LocalModels.deleteTree(temp);
            throw e instanceof IOException ? (IOException) e
                    : new IOException("Could not unpack the model: " + e.getMessage(), e);
        }

        for (String name : spec.requiredFiles) {
            File file = new File(temp, name);
            if (!file.isFile() || file.length() <= 0) {
                LocalModels.deleteTree(temp);
                throw new IOException("The model archive is incomplete (missing " + name + ").");
            }
        }

        LocalModels.deleteTree(target);
        if (!temp.renameTo(target)) {
            LocalModels.deleteTree(temp);
            throw new IOException("Could not finish installing the model.");
        }
        try {
            new File(target, ".installed").createNewFile();
        } catch (Exception ignored) {
        }
        archive.delete();
        File ready = new File(archive.getPath() + ".ready");
        if (ready.exists()) ready.delete();
        LocalModels.prefs(context).edit()
                .remove("download_id_" + spec.id).apply();
    }
}
