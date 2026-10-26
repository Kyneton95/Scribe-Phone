package au.local.barkpocket;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

// A single recoverable session. Audio stays private until the server accepts it.
final class SessionStore {
    // Holds only the application context, so this cannot leak an Activity.
    @SuppressLint("StaticFieldLeak")
    private static SessionStore instance;
    static synchronized SessionStore get(Context c) {
        if (instance == null) instance = new SessionStore(c.getApplicationContext());
        return instance;
    }
    final Context context;
    final File dir;
    private final AtomicFile state;
    volatile boolean active = false;
    volatile boolean busy = false;
    boolean exists = false;
    boolean document = false;
    String text = "", status = "Ready", error = "", endpoint = "", folder = "", saved = "", id = "";
    int sequence = 0;
    int accepted = 0;
    private SessionStore(Context c) {
        context = c;
        dir = new File(c.getFilesDir(), "session"); dir.mkdirs();
        state = new AtomicFile(new File(dir, "state.json"));
        try (InputStream in = state.openRead()) {
            JSONObject j = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            exists = j.optBoolean("exists"); document = j.optBoolean("document");
            text = j.optString("text"); endpoint = j.optString("endpoint"); folder = j.optString("folder");
            saved = j.optString("saved"); id = j.optString("id"); sequence = j.optInt("sequence"); accepted = j.optInt("accepted");
            status = exists ? "Recovered session. Retry pending audio or copy/save the text." : "Ready";
        } catch (Exception ignored) { }
    }
    synchronized void persist() throws Exception {
        JSONObject j = new JSONObject();
        j.put("exists", exists); j.put("document", document); j.put("text", text);
        j.put("endpoint", endpoint); j.put("folder", folder); j.put("saved", saved);
        j.put("id", id); j.put("sequence", sequence); j.put("accepted", accepted);
        FileOutputStream out = null;
        try { out = state.startWrite(); out.write(j.toString().getBytes(StandardCharsets.UTF_8)); state.finishWrite(out); }
        catch (Exception e) { if (out != null) state.failWrite(out); throw e; }
    }
    synchronized void begin(boolean doc, String url, String tree) throws Exception {
        if (active || busy || pending().length > 0) throw new IOException("Finish or discard the previous session first.");
        // Keep a completed session until the user explicitly discards it.
        if (exists) throw new IOException("Copy/save the previous session, then tap Discard / new.");
        exists = true; document = doc; endpoint = url; folder = tree;
        id = new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(new Date()) + "_" + UUID.randomUUID().toString().substring(0, 6);
        text = ""; error = ""; saved = ""; sequence = 0; accepted = 0; active = true; status = "Listening"; persist();
    }
    synchronized File[] pending() {
        File[] a = dir.listFiles((d, n) -> {
            if (!n.matches("[0-9]{6}\\.wav")) return false;
            return Integer.parseInt(n.substring(0, 6)) > accepted;
        });
        if (a == null) return new File[0];
        Arrays.sort(a, Comparator.comparing(File::getName)); return a;
    }
    synchronized File nextFile() throws Exception {
        sequence++; persist(); return new File(dir, String.format(Locale.US, "%06d.wav", sequence));
    }
    synchronized void accept(File audio, String piece) throws Exception {
        String before = text; int previous = accepted;
        if (!piece.isBlank()) text += (text.isBlank() ? "" : " ") + piece.trim();
        accepted = Integer.parseInt(audio.getName().substring(0, 6));
        try { persist(); } catch (Exception e) { text = before; accepted = previous; throw e; }
        // A persisted acknowledgement prevents duplicate text if deletion fails
        // or the process is killed between committing the transcript and deleting.
        audio.delete();
    }
    synchronized void saveText() throws Exception {
        if (!saved.isEmpty()) return;
        if (folder.isEmpty()) throw new IOException("Choose your local Syncthing folder first.");
        if (text.isBlank()) throw new IOException("There is no transcript to save yet.");
        Uri tree = Uri.parse(folder);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri file = DocumentsContract.createDocument(context.getContentResolver(), parent, "text/plain", "Voice_" + id + ".txt");
        if (file == null) throw new IOException("The selected folder could not create a text file.");
        try (OutputStream out = context.getContentResolver().openOutputStream(file, "wt")) {
            if (out == null) throw new IOException("Cannot open the new text file.");
            out.write((text + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { try { DocumentsContract.deleteDocument(context.getContentResolver(), file); } catch (Exception ignored) {} throw e; }
        saved = file.toString(); persist(); status = "Saved plain text: Voice_" + id + ".txt";
    }
    synchronized void discard() throws Exception {
        if (active || busy) throw new IOException("Stop recording and wait for processing first.");
        File[] audio = dir.listFiles((d, n) -> n.endsWith(".wav") || n.endsWith(".tmp"));
        if (audio != null) for (File f : audio) if (!f.delete()) throw new IOException("Could not delete private audio.");
        exists = false; text = ""; saved = ""; error = ""; status = "Ready"; sequence = 0; accepted = 0; persist();
    }
}
