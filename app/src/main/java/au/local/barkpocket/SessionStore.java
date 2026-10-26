package au.local.barkpocket;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Recoverable current draft plus the ordered, private WAV queue.
 * Committing clears the draft but keeps the daily document itself.
 */
final class SessionStore {
    private static final String PREFS = "settings";

    // Holds only the application context, so this cannot leak an Activity.
    @SuppressLint("StaticFieldLeak")
    private static SessionStore instance;

    static synchronized SessionStore get(Context context) {
        if (instance == null) instance = new SessionStore(context.getApplicationContext());
        return instance;
    }

    final Context context;
    final File dir;
    private final AtomicFile state;
    private final SharedPreferences prefs;

    volatile boolean active = false;
    volatile boolean busy = false;
    volatile String draft = "";
    volatile String status = "Ready";
    volatile String error = "";

    private int sequence = 0;
    private int accepted = 0;

    private SessionStore(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.dir = new File(context.getFilesDir(), "session");
        dir.mkdirs();
        this.state = new AtomicFile(new File(dir, "state.json"));
        try (InputStream in = state.openRead()) {
            JSONObject json = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            draft = Transcript.normalize(json.optString("draft", json.optString("text", "")));
            sequence = json.optInt("sequence", 0);
            accepted = json.optInt("accepted", 0);
            status = !draft.isEmpty() ? "Recovered draft" : "Ready";
        } catch (Exception ignored) {
        }
        cleanAcknowledgedAudio();
    }

    private void cleanAcknowledgedAudio() {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".wav") || name.endsWith(".tmp"));
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".tmp")) {
                file.delete();
                continue;
            }
            try {
                int number = Integer.parseInt(name.substring(0, 6));
                if (number <= accepted) file.delete();
            } catch (Exception ignored) {
            }
        }
    }

    synchronized void persist() throws Exception {
        JSONObject json = new JSONObject();
        json.put("draft", draft);
        json.put("sequence", sequence);
        json.put("accepted", accepted);
        FileOutputStream out = null;
        try {
            out = state.startWrite();
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            state.finishWrite(out);
        } catch (Exception e) {
            if (out != null) state.failWrite(out);
            throw e;
        }
    }

    synchronized File[] pending() {
        File[] files = dir.listFiles((d, name) -> {
            if (!name.matches("[0-9]{6}\\.wav")) return false;
            return Integer.parseInt(name.substring(0, 6)) > accepted;
        });
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    synchronized int pendingCount() {
        return pending().length;
    }

    synchronized File nextFile() throws Exception {
        sequence++;
        persist();
        return new File(dir, String.format(java.util.Locale.US, "%06d.wav", sequence));
    }

    synchronized void accept(File audio, String piece) throws Exception {
        String beforeDraft = draft;
        int beforeAccepted = accepted;
        draft = Transcript.join(draft, piece);
        accepted = Integer.parseInt(audio.getName().substring(0, 6));
        try {
            persist();
        } catch (Exception e) {
            draft = beforeDraft;
            accepted = beforeAccepted;
            throw e;
        }
        // A persisted acknowledgement prevents duplicate text if the process is
        // killed between committing the transcript and deleting the audio.
        if (!audio.delete()) accepted = Math.max(accepted, Integer.parseInt(audio.getName().substring(0, 6)));
    }

    synchronized void setDraft(String text) throws Exception {
        draft = Transcript.normalize(text);
        persist();
    }

    synchronized String draftText() {
        return draft;
    }

    synchronized boolean hasDraft() {
        return !draft.trim().isEmpty();
    }

    synchronized int sequence() {
        return sequence;
    }

    synchronized int accepted() {
        return accepted;
    }

    synchronized String commit() throws Exception {
        if (active || busy) throw new IOException("Wait for the current session to finish.");
        if (pending().length > 0) throw new IOException("Wait for pending phrases to finish.");
        if (draft.trim().isEmpty()) throw new IOException("There is nothing to add yet.");
        String name = DailyNotes.append(context, folder(), draft);
        draft = "";
        error = "";
        accepted = sequence;
        status = "Added to " + name;
        deleteAudio();
        persist();
        return name;
    }

    synchronized void clearAll() throws Exception {
        if (active || busy) throw new IOException("Stop recording and wait for transcription first.");
        draft = "";
        error = "";
        status = "Cleared";
        accepted = sequence;
        deleteAudio();
        persist();
    }

    String folder() {
        return prefs.getString("folder", "");
    }

    String endpoint() {
        return prefs.getString("endpoint", "");
    }

    private void deleteAudio() {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".wav") || name.endsWith(".tmp"));
        if (files == null) return;
        for (File file : files) {
            try {
                file.delete();
            } catch (Exception ignored) {
            }
        }
    }
}
