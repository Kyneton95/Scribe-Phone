package au.local.barkpocket;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Appends each committed draft to one plain-text file per local day. */
final class DailyNotes {
    private static final int MAX_EXISTING_BYTES = 1024 * 1024;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private DailyNotes() {
    }

    static String fileName() {
        return fileName(LocalDate.now());
    }

    static String fileName(LocalDate date) {
        return "Scribe_" + date.format(DATE) + ".md";
    }

    static String append(Context context, String treeString, String text) throws Exception {
        if (treeString == null || treeString.trim().isEmpty()) {
            throw new IOException("Choose a daily notes folder in settings.");
        }
        String trimmed = Transcript.normalize(text);
        if (trimmed.isEmpty()) throw new IOException("There is nothing to add yet.");

        Uri tree;
        try {
            tree = Uri.parse(treeString);
        } catch (Exception e) {
            throw new IOException("The notes folder setting is invalid.", e);
        }
        if (!DocumentsContract.isTreeUri(tree)) {
            throw new IOException("Choose the notes folder again.");
        }

        ContentResolver resolver = context.getContentResolver();
        String name = fileName();
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
        Uri document = findDocument(resolver, tree, name);
        boolean created = false;
        if (document == null) {
            document = DocumentsContract.createDocument(resolver, parent, "text/plain", name);
            created = true;
        }
        if (document == null) {
            throw new IOException("The notes folder could not create a text file.");
        }

        String existing = readExisting(resolver, document);
        String payload = payloadFor(existing,
                LocalTime.now().format(TIME), trimmed);
        try (OutputStream out = resolver.openOutputStream(document, "wa")) {
            if (out == null) throw new IOException("Could not open today's note for writing.");
            out.write(payload.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IllegalArgumentException | UnsupportedOperationException | FileNotFoundException e) {
            if (created || existing == null) {
                throw new IOException("This folder does not support appending.", e);
            }
            // A few document providers only implement truncate mode. Rewrite the
            // small daily note atomically enough for a prototype.
            try (OutputStream out = resolver.openOutputStream(document, "wt")) {
                if (out == null) throw new IOException("Could not open today's note for writing.");
                out.write((existing + payload).getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
        return name;
    }

    static String folderLabel(Context context, String treeString) {
        if (treeString == null || treeString.trim().isEmpty()) return "Choose a folder";
        try {
            Uri tree = Uri.parse(treeString);
            Uri document = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor cursor = context.getContentResolver().query(
                    document, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    String name = cursor.getString(0);
                    if (name != null && !name.trim().isEmpty()) return name.trim();
                }
            }
        } catch (Exception ignored) {
        }
        String last = Uri.decode(treeString);
        int slash = last.lastIndexOf('/');
        if (slash >= 0) last = last.substring(slash + 1);
        int colon = last.lastIndexOf(':');
        if (colon >= 0) last = last.substring(colon + 1);
        return last.isEmpty() ? "Notes folder" : last;
    }

    /** Package-private for the JVM text test. */
    static String payloadFor(String existing, String time, String text) {
        String trimmed = Transcript.normalize(text);
        if (trimmed.isEmpty()) return "";
        String entry = "## " + time + "\n\n" + trimmed + "\n";
        if (existing == null || existing.isEmpty()) return entry;
        if (existing.endsWith("\n\n")) return entry;
        if (existing.endsWith("\n")) return "\n" + entry;
        return "\n\n" + entry;
    }

    private static Uri findDocument(ContentResolver resolver, Uri tree, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor cursor = resolver.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        }, null, null, null)) {
            if (cursor == null) return null;
            while (cursor.moveToNext()) {
                String display = cursor.getString(1);
                if (name.equals(display)) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String readExisting(ContentResolver resolver, Uri document) {
        try (InputStream in = resolver.openInputStream(document)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > MAX_EXISTING_BYTES) return null;
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
