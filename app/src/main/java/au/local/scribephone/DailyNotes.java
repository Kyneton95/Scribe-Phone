package au.local.scribephone;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * One Markdown file per day. Commits append a "## HH:mm" section.
 *
 * Some Android document providers ignore the requested extension for
 * text/plain and create "name.md.txt" instead. We therefore create Markdown
 * documents with the text/markdown MIME type, remember the returned document
 * URI, and repair a day's accidental shards by merging them into one .md file.
 */
final class DailyNotes {
    private static final String PREFS = "settings";
    private static final String K_TREE = "daily_tree";
    private static final String K_DATE = "daily_date";
    private static final String K_URI = "daily_uri";
    private static final String K_NAME = "daily_name";
    private static final String K_MIGRATED_PREFIX = "daily_migrated_";

    private static final String MIME_MARKDOWN = "text/markdown";
    private static final int MAX_BYTES = 1024 * 1024;
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
        String body = Transcript.normalize(text);
        if (body.isEmpty()) throw new IOException("There is nothing to add yet.");

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
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        LocalDate today = LocalDate.now();
        String date = today.format(DATE);
        String base = "Scribe_" + date;
        String expected = base + ".md";
        String todayTime = LocalTime.now().format(TIME);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));

        // A folder change invalidates the stored document and day migration state.
        if (!treeString.equals(prefs.getString(K_TREE, ""))) {
            prefs.edit().remove(K_URI).remove(K_DATE).remove(K_NAME)
                    .remove(K_MIGRATED_PREFIX + date).apply();
        }

        List<Doc> docs = queryChildren(resolver, tree);
        Uri target = null;
        String targetName = null;
        boolean targetWasExisting = false;

        // Prefer the URI returned when we created the file: providers may rename
        // the display name, which made exact-name lookup miss it every commit.
        if (date.equals(prefs.getString(K_DATE, ""))
                && treeString.equals(prefs.getString(K_TREE, ""))) {
            String stored = prefs.getString(K_URI, "");
            if (!stored.isEmpty()) {
                Uri candidate = Uri.parse(stored);
                // Do not depend on document-URI metadata queries: some providers
                // support opening a document but not querying it. Reading also
                // proves the URI is still valid.
                if (readText(resolver, candidate) != null) {
                    Doc info = queryDocument(resolver, candidate);
                    target = candidate;
                    targetName = info != null ? info.name : prefs.getString(K_NAME, expected);
                    targetWasExisting = true;
                }
            }
        }

        if (target == null) {
            for (Doc d : docs) {
                if (expected.equals(d.name)) {
                    target = d.uri;
                    targetName = d.name;
                    targetWasExisting = true;
                    break;
                }
            }
        }

        if (target == null) {
            Uri created = DocumentsContract.createDocument(
                    resolver, parent, MIME_MARKDOWN, expected);
            if (created == null) {
                throw new IOException("The notes folder could not create a Markdown file.");
            }
            Doc info = queryDocument(resolver, created);
            target = created;
            targetName = info == null ? expected : info.name;

            // If the provider still ignored .md, try an explicit rename once.
            if (targetName == null || !targetName.toLowerCase(Locale.ROOT).endsWith(".md")) {
                try {
                    Uri renamed = DocumentsContract.renameDocument(
                            resolver, created, expected);
                    if (renamed != null) {
                        Doc renamedInfo = queryDocument(resolver, renamed);
                        if (renamedInfo != null
                                && renamedInfo.name.toLowerCase(Locale.ROOT).endsWith(".md")) {
                            target = renamed;
                            targetName = renamedInfo.name;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (targetName != null && !targetName.toLowerCase(Locale.ROOT).endsWith(".md")) {
            try {
                Uri renamed = DocumentsContract.renameDocument(
                        resolver, target, expected);
                if (renamed != null) {
                    Doc info = queryDocument(resolver, renamed);
                    if (info != null && info.name.toLowerCase(Locale.ROOT).endsWith(".md")) {
                        target = renamed;
                        targetName = info.name;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // Re-query after rename/create so the shard list and target URI are fresh.
        docs = queryChildren(resolver, tree);
        String migratedKey = K_MIGRATED_PREFIX + date;
        boolean alreadyMigrated = prefs.getBoolean(migratedKey, false);
        if (!targetWasExisting || !alreadyMigrated) {
            boolean allMerged = mergeShards(resolver, docs, target, targetName, base, true);
            if (allMerged) {
                prefs.edit().putBoolean(migratedKey, true).apply();
            }
        } else {
            // Retry deleting shards left behind by a failed delete, but never
            // re-append old text after the user has edited the target.
            mergeShards(resolver, docs, target, targetName, base, false);
        }

        appendBlock(resolver, target, "## " + todayTime + "\n\n" + body);

        prefs.edit()
                .putString(K_TREE, treeString)
                .putString(K_DATE, date)
                .putString(K_URI, target.toString())
                .putString(K_NAME, targetName == null ? expected : targetName)
                .apply();
        return targetName == null ? expected : targetName;
    }

    /**
     * Merge accidental same-day shards into the chosen target. Sorts by modified
     * time, deduplicates blocks already in the target, then deletes a shard only
     * after its content is verified in the target.
     */
    private static boolean mergeShards(ContentResolver resolver, List<Doc> docs,
                                       Uri target, String targetName, String base,
                                       boolean appendMissing) {
        List<Doc> shards = new ArrayList<>();
        for (Doc d : docs) {
            if (d.uri.equals(target)) continue;
            if (targetName != null && d.name.equals(targetName)) continue;
            if (isCandidate(base, d.name)) shards.add(d);
        }
        if (shards.isEmpty()) return true;

        shards.sort(Comparator
                .comparingInt((Doc d) -> shardOrder(d.name))
                .thenComparingLong(d -> d.modified));
        String targetText = readText(resolver, target);
        boolean all = true;
        for (Doc shard : shards) {
            String content = readText(resolver, shard.uri);
            if (content == null || content.trim().isEmpty()) {
                try {
                    DocumentsContract.deleteDocument(resolver, shard.uri);
                } catch (Exception ignored) {
                }
                continue;
            }
            String block = withHeading(content, shard.modified);
            String normalizedTarget = normalizeForCompare(targetText);
            if (normalizedTarget == null
                    || !normalizedTarget.contains(normalizeForCompare(block))) {
                if (!appendMissing) {
                    all = false;
                    continue;
                }
                try {
                    appendBlock(resolver, target, block);
                    targetText = readText(resolver, target);
                } catch (Exception e) {
                    all = false;
                    continue;
                }
            }
            String after = normalizeForCompare(targetText);
            if (after == null || !after.contains(normalizeForCompare(block))) {
                all = false;
            }
        }

        if (all) {
            String finalText = normalizeForCompare(readText(resolver, target));
            for (Doc shard : shards) {
                String content = readText(resolver, shard.uri);
                if (content == null || content.trim().isEmpty()
                        || (finalText != null && finalText.contains(
                        normalizeForCompare(withHeading(content, shard.modified))))) {
                    try {
                        DocumentsContract.deleteDocument(resolver, shard.uri);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return all;
    }

    private static String withHeading(String content, long modified) {
        String trimmed = content.trim();
        if (trimmed.startsWith("#")) return trimmed;
        LocalTime time;
        try {
            time = Instant.ofEpochMilli(modified == 0 ? System.currentTimeMillis() : modified)
                    .atZone(ZoneId.systemDefault()).toLocalTime();
        } catch (Exception e) {
            time = LocalTime.now();
        }
        return "## " + time.format(TIME) + "\n\n" + trimmed;
    }

    private static void appendBlock(ContentResolver resolver, Uri document, String block) throws Exception {
        String clean = normalizeForCompare(block);
        if (clean == null || clean.isEmpty()) return;
        String existing = readText(resolver, document);
        if (existing == null) {
            throw new IOException("Could not read today's note.");
        }
        String payload = blockPayload(existing, clean);
        String combined = existing + payload;
        // Rewrite rather than relying on provider append mode. Several SAF
        // providers treat "wa" as a truncating write, which silently loses the
        // earlier sections of a daily note.
        try (OutputStream out = resolver.openOutputStream(document, "wt")) {
            if (out == null) throw new IOException("Could not open today's note for writing.");
            out.write(combined.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IllegalArgumentException | UnsupportedOperationException | FileNotFoundException e) {
            try (OutputStream out = resolver.openOutputStream(document, "wa")) {
                if (out == null) throw new IOException("Could not open today's note for writing.");
                out.write(payload.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    /** Package-private for the JVM text tests. */
    static String payloadFor(String existing, String time, String text) {
        return blockPayload(existing,
                "## " + time + "\n\n" + Transcript.normalize(text));
    }

    private static String blockPayload(String existing, String block) {
        String clean = normalizeForCompare(block);
        if (clean == null || clean.isEmpty()) return "";
        if (existing == null || existing.trim().isEmpty()) return clean + "\n";
        if (existing.endsWith("\n\n")) return clean + "\n";
        if (existing.endsWith("\n")) return "\n" + clean + "\n";
        return "\n\n" + clean + "\n";
    }

    static String folderLabel(Context context, String treeString) {
        if (treeString == null || treeString.trim().isEmpty()) return "Choose a folder";
        try {
            Uri tree = Uri.parse(treeString);
            Uri document = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor cursor = context.getContentResolver().query(
                    document, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null)) {
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

    private static int shardOrder(String name) {
        if (name == null) return 0;
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\((\\d+)\\)").matcher(name);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static boolean isCandidate(String base, String name) {
        if (name == null || !name.startsWith(base)) return false;
        String rest = name.substring(base.length());
        return rest.matches("(?:\\(\\d+\\))?\\.md")
                || rest.matches("(?:\\(\\d+\\))?\\.txt")
                || rest.matches("\\.md\\.txt")
                || rest.matches("\\.md\\(\\d+\\)\\.txt")
                || rest.matches("(?:\\(\\d+\\))?\\.md\\.txt");
    }

    private static List<Doc> queryChildren(ContentResolver resolver, Uri tree) {
        List<Doc> out = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor cursor = resolver.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        }, null, null)) {
            if (cursor == null) return out;
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                long modified = cursor.getLong(2);
                if (id != null) {
                    out.add(new Doc(DocumentsContract.buildDocumentUriUsingTree(tree, id),
                            name, modified));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static Doc queryDocument(ContentResolver resolver, Uri document) {
        try (Cursor cursor = resolver.query(document, new String[]{
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        }, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return new Doc(document, cursor.getString(0), cursor.getLong(1));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String readText(ContentResolver resolver, Uri document) {
        try (InputStream in = resolver.openInputStream(document)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > MAX_BYTES) return null;
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeForCompare(String text) {
        if (text == null) return null;
        return text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static final class Doc {
        final Uri uri;
        final String name;
        final long modified;

        Doc(Uri uri, String name, long modified) {
            this.uri = uri;
            this.name = name == null ? "" : name;
            this.modified = modified;
        }
    }
}
