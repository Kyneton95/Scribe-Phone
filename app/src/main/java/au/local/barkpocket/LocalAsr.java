package au.local.barkpocket;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/** Lazy sherpa-onnx runner for user-downloaded Whisper, Moonshine and Parakeet models. */
final class LocalAsr {
    private static final String TAG = "ScribeLocal";
    private static OfflineRecognizer recognizer;
    private static String recognizerId;

    private LocalAsr() {
    }

    static synchronized String transcribe(Context context, File wav) throws Exception {
        LocalModels.Spec spec = LocalModels.selected(context);
        if (!LocalModels.installed(context, spec)) {
            throw new IOException("The on-device model is not installed. Open Scribe settings "
                    + "and download " + spec.name + ".");
        }
        try {
            OfflineRecognizer engine = ensure(context, spec);
            float[] samples = readWav(wav);
            if (samples.length == 0) throw new IOException("The recorded phrase is empty.");
            OfflineStream stream = engine.createStream();
            try {
                Log.i(TAG, "decode " + spec.id + " samples=" + samples.length);
                stream.acceptWaveform(samples, 16000);
                engine.decode(stream);
                OfflineRecognizerResult result = engine.getResult(stream);
                String text = result == null ? "" : result.getText();
                Log.i(TAG, "decoded " + spec.id + " chars=" + (text == null ? 0 : text.length()));
                return text == null ? "" : text.trim();
            } finally {
                stream.release();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Local transcription failed for " + spec.id, t);
            if (t instanceof IOException) throw (IOException) t;
            throw new IOException("Local model " + spec.name + " failed: " + describe(t), t);
        }
    }

    private static synchronized OfflineRecognizer ensure(Context context, LocalModels.Spec spec)
            throws Exception {
        if (recognizer != null && spec.id.equals(recognizerId)) return recognizer;
        releaseLocked();
        try {
            Log.i(TAG, "load " + spec.id);
            OfflineRecognizerConfig config = buildConfig(context, spec);
            // Model files are absolute paths in app storage. Passing the
            // AssetManager here makes sherpa treat them as assets and abort
            // when it cannot read tokens.txt; the null constructor loads files.
            recognizer = new OfflineRecognizer(null, config);
            recognizerId = spec.id;
            Log.i(TAG, "loaded " + spec.id);
            return recognizer;
        } catch (Throwable t) {
            Log.e(TAG, "Could not load " + spec.id, t);
            recognizer = null;
            recognizerId = null;
            throw new IOException("Could not load " + spec.name + ": " + describe(t), t);
        }
    }

    static synchronized void release() {
        releaseLocked();
    }

    private static void releaseLocked() {
        if (recognizer != null) {
            try {
                recognizer.release();
            } catch (Exception ignored) {
            }
        }
        recognizer = null;
        recognizerId = null;
    }

    private static OfflineRecognizerConfig buildConfig(Context context, LocalModels.Spec spec) {
        File dir = LocalModels.modelDir(context, spec);
        OfflineModelConfig model = new OfflineModelConfig();
        model.setNumThreads(Math.max(1, Math.min(4,
                Runtime.getRuntime().availableProcessors() - 1)));
        model.setProvider("cpu");
        model.setDebug(false);

        if (LocalModels.FAMILY_WHISPER.equals(spec.family)) {
            OfflineWhisperModelConfig whisper = new OfflineWhisperModelConfig();
            whisper.setEncoder(new File(dir, "tiny.en-encoder.int8.onnx").getAbsolutePath());
            whisper.setDecoder(new File(dir, "tiny.en-decoder.int8.onnx").getAbsolutePath());
            whisper.setLanguage("en");
            whisper.setTask("transcribe");
            whisper.setTailPaddings(1000);
            model.setWhisper(whisper);
            model.setTokens(new File(dir, "tiny.en-tokens.txt").getAbsolutePath());
        } else if (LocalModels.FAMILY_MOONSHINE.equals(spec.family)) {
            OfflineMoonshineModelConfig moonshine = new OfflineMoonshineModelConfig();
            moonshine.setPreprocessor(new File(dir, "preprocess.onnx").getAbsolutePath());
            moonshine.setEncoder(new File(dir, "encode.int8.onnx").getAbsolutePath());
            moonshine.setUncachedDecoder(new File(dir, "uncached_decode.int8.onnx").getAbsolutePath());
            moonshine.setCachedDecoder(new File(dir, "cached_decode.int8.onnx").getAbsolutePath());
            model.setMoonshine(moonshine);
            model.setTokens(new File(dir, "tokens.txt").getAbsolutePath());
        } else {
            OfflineTransducerModelConfig transducer = new OfflineTransducerModelConfig();
            transducer.setEncoder(new File(dir, "encoder.int8.onnx").getAbsolutePath());
            transducer.setDecoder(new File(dir, "decoder.int8.onnx").getAbsolutePath());
            transducer.setJoiner(new File(dir, "joiner.int8.onnx").getAbsolutePath());
            model.setTransducer(transducer);
            model.setTokens(new File(dir, "tokens.txt").getAbsolutePath());
            model.setModelType("nemo_transducer");
        }

        FeatureConfig feature = new FeatureConfig();
        feature.setSampleRate(16000);
        feature.setFeatureDim(80);
        feature.setDither(0f);

        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setFeatConfig(feature);
        config.setModelConfig(model);
        config.setDecodingMethod("greedy_search");
        config.setMaxActivePaths(4);
        return config;
    }

    private static float[] readWav(File file) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (in.length() < 44) throw new IOException("The recorded WAV is too short.");
            byte[] riff = new byte[12];
            in.readFully(riff);
            if (riff[0] != 'R' || riff[1] != 'I' || riff[2] != 'F' || riff[3] != 'F'
                    || riff[8] != 'W' || riff[9] != 'A' || riff[10] != 'V' || riff[11] != 'E') {
                throw new IOException("The recorded file is not a WAV.");
            }
            int channels = 1;
            int sampleRate = 16000;
            int bits = 16;
            byte[] data = null;
            long pointer = 12;
            while (pointer + 8 <= in.length()) {
                in.seek(pointer);
                byte[] header = new byte[8];
                in.readFully(header);
                String id = new String(header, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
                long size = le32(header, 4);
                long body = pointer + 8;
                if ("fmt ".equals(id) && size >= 16 && body + 16 <= in.length()) {
                    byte[] fmt = new byte[16];
                    in.readFully(fmt);
                    channels = (fmt[2] & 0xff) | ((fmt[3] & 0xff) << 8);
                    sampleRate = (int) le32(fmt, 4);
                    bits = (fmt[14] & 0xff) | ((fmt[15] & 0xff) << 8);
                } else if ("data".equals(id)) {
                    long available = in.length() - body;
                    int length = (int) Math.min(size, available);
                    if (length <= 0) break;
                    data = new byte[length];
                    in.readFully(data);
                    break;
                }
                pointer = body + size + (size & 1);
            }
            if (data == null || data.length < 2) throw new IOException("The WAV has no audio data.");
            if (channels != 1 || sampleRate != 16000 || bits != 16) {
                throw new IOException(String.format(Locale.US,
                        "Expected 16 kHz mono 16-bit audio, got %d Hz/%d ch/%d-bit.",
                        sampleRate, channels, bits));
            }
            int count = data.length / 2;
            float[] samples = new float[count];
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) samples[i] = buffer.getShort() / 32768f;
            return samples;
        }
    }

    private static long le32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL)
                | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16)
                | ((bytes[offset + 3] & 0xffL) << 24);
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) message = t.getClass().getSimpleName();
        return message;
    }
}
