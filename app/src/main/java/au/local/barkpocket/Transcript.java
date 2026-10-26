package au.local.barkpocket;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Joins independently transcribed phrases into one readable draft.
 *
 * The server transcribes each phrase as a standalone utterance, so it tends to
 * end every phrase with a full stop. A long-ish pause in speech is therefore
 * often a thinking pause, not the end of a sentence. This joiner removes a
 * phrase-final stop when the next phrase clearly continues it (lower-case start
 * or a common discourse connector) and otherwise keeps the sentence boundary.
 */
final class Transcript {
    private static final Set<String> CONTINUATIONS = new HashSet<>(Arrays.asList(
            "and", "but", "so", "because", "then", "which", "that", "or", "also",
            "actually", "basically", "like", "just", "well", "plus", "though",
            "although", "while", "when", "if", "as", "yeah", "okay", "ok",
            "um", "uh", "hmm", "maybe", "probably", "perhaps", "anyway",
            "anyways", "right", "sure", "still", "even", "only"
    ));
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern SPACE_BEFORE_PUNCTUATION = Pattern.compile("\\s+([,.;:!?])");
    private static final Pattern REPEATED_SEPARATORS = Pattern.compile("([,;:])(?:\\s*[,;:])+");

    private Transcript() {
    }

    static String join(String draft, String piece) {
        String a = normalize(draft);
        String b = normalize(piece);
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;

        boolean continuationWord = startsWithContinuation(b);
        boolean lowerCaseStart = Character.isLowerCase(firstLetter(b));
        if (endsSentence(a) && (continuationWord || lowerCaseStart)) {
            a = a.substring(0, a.length() - 1).trim();
            if (continuationWord) {
                b = lowerFirstLetter(b);
                return a + (a.endsWith(",") ? " " : ", ") + b;
            }
            return a + " " + b;
        }
        return a + " " + b;
    }

    static String normalize(String text) {
        if (text == null) return "";
        String s = WHITESPACE.matcher(text.trim()).replaceAll(" ");
        s = SPACE_BEFORE_PUNCTUATION.matcher(s).replaceAll("$1");
        s = REPEATED_SEPARATORS.matcher(s).replaceAll("$1");
        return s.trim();
    }

    private static boolean endsSentence(String s) {
        if (s.isEmpty()) return false;
        char c = s.charAt(s.length() - 1);
        return c == '.' || c == '!' || c == '?';
    }

    private static char firstLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) return c;
        }
        return 0;
    }

    private static boolean startsWithContinuation(String s) {
        int start = 0;
        while (start < s.length() && !Character.isLetterOrDigit(s.charAt(start))) start++;
        int end = start;
        while (end < s.length()) {
            char c = s.charAt(end);
            if (!Character.isLetter(c) && c != '\'') break;
            end++;
        }
        if (end == start) return false;
        return CONTINUATIONS.contains(s.substring(start, end).toLowerCase(Locale.ROOT));
    }

    private static String lowerFirstLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                return s.substring(0, i) + Character.toLowerCase(c) + s.substring(i + 1);
            }
        }
        return s;
    }
}
