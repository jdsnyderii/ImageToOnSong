package com.imagetoonsong.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.commons.text.similarity.LevenshteinDistance;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandles;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ChordDetector {

    private static final Logger logger = LoggerFactory.getLogger(
            MethodHandles.lookup().lookupClass());
    /**
     * Full-token matcher — use with .matches() on individual tokens only.
     * <p>
     * Changes from previous version:
     *  - Anchored with ^ and $ so it matches the whole token, not a substring.
     *    Eliminates false positives like matching 'G' inside "God" or 'C' in "Calling".
     *  - Root [A-G] is NOT case-insensitive — uppercase root is required.
     *    Callers must run normalizeChordToken() (which uppercases the first char) before matching.
     *  - m(?!aj) negative lookahead prevents 'm' in "maj" being consumed as minor quality.
     *  - Slash bass note requires a full bass note: /[A-G][#b]? — a bare trailing '/' won't match.
     *  - Removed \\b word boundaries (redundant with anchors, and unreliable at line edges).
     * <p>
     * Matches: G  Am  F#m  Bb  C#m7  Dsus4  G/D  D/F#  Bm7  Emaj7  Asus2  Cadd9
     * Rejects: God  Calling  daughters  the  and  |  /  l  1  G/  /D
     */
    public static final Pattern CHORD_PATTERN = Pattern.compile(
            "^[A-G]"                                    // root — uppercase only
          + "([#b])?"                                   // optional accidental
          + "(m(?!aj)|min|maj|M|aug|dim|sus[24]?|add|ø|°)?"  // optional quality
          + "(\\d{1,2})?"                               // optional numeric extension (7, 9, 11, 13…)
          + "(add\\d{1,2})?"                            // optional add-tone (add9, add11)
          + "(/[A-G][#b]?)?"                            // optional slash bass note — full note required
          + "$"
    );

    public static final Pattern BRACKETED_CHORD_PATTERN = Pattern.compile(
            "^\\["
                    + "[A-G]"
                    + "([#b])?"
                    // Removed 'add' from here to avoid "eating" it before the dedicated add group
                    + "(m(?!aj)|min|maj|M|aug|dim|sus[24]?|ø|°|\\+)?"
                    + "([#b]?\\d{1,2})*"
                    + "(add\\d{1,2})?"
                    + "((/[A-G][#b]?))?"
                    + "\\]$"
    );

    /**
     * Inline scanner — use with .replaceAll() on a full line of text.
     * <p>
     * Uses negative lookbehind/lookahead to avoid:
     * - Matching letters inside words (e.g. 'G' in "God")
     * - Double-bracketing already-bracketed chords like [G]
     * <p>
     * This is intentionally separate from CHORD_PATTERN because CHORD_PATTERN
     * is anchored (^…$) and cannot scan within a string.
     */
    private static final Pattern CHORD_INLINE = Pattern.compile(
            "(?<![\\[\\w])"                             // not preceded by '[' or word char
          + "[A-G]([#b])?(m(?!aj)|min|maj|M|aug|dim|sus[24]?|add|ø|°)?(\\d{1,2})?(add\\d{1,2})?(/[A-G][#b]?)?"
          + "(?![\\]\\w])"                             // not followed by ']' or word char
    );

    /**
     * Converts a raw OCR line that looks like a chord line into bracketed format.
     * Example: "C   Am7   F   G/B"  →  "[C] [Am7] [F] [G/B]"
     * <p>
     * Uses CHORD_INLINE (not CHORD_PATTERN) so it can scan within a string.
     */
    public static String convertToBracketed(String line) {
        if (line == null || line.isEmpty()) return line;
        String firstPass = CHORD_INLINE.matcher(line).replaceAll(match -> "[" + match.group() + "]");
        while (firstPass.contains("]/")) {
            firstPass = firstPass.replace("]/", "][/]");
        }
        return firstPass;
    }

    /**
     * Uppercases the first character of a token to mirror what
     * HocrTolerantParser.normalizeChordToken() does before chord matching.
     * Needed because CHORD_PATTERN no longer uses CASE_INSENSITIVE on the root.
     */
    private static String normalizeForDetection(String token) {
        if (token == null || token.isEmpty()) return token;
        return Character.toUpperCase(token.charAt(0)) + token.substring(1);
    }

    public static String safeChordBracket(String token) {
        String normalized = ChordDetector.normalizeChordToken(token);
        if (CHORD_PATTERN.matcher(normalized).matches()) {
            return "[" + normalized + "]";
        } else {
            return "[" + normalized + " ⚠️ ]";
        }
    }

    /**
     * Finds the closest chord name to rawText using Levenshtein distance.
     * <p>
     * Used as a post-OCR correction step when PSM_SINGLE_WORD returns
     * a near-miss like "Fa" for "Fmaj7" or "Gus" for "Gsus4".
     * <p>
     * Only returns a match if:
     *  1. The first character matches — chord root must be correct.
     *     "Fa" cannot correct to "Dm7" even if edit distance is low.
     *  2. Edit distance is within MAX_CHORD_EDIT_DISTANCE.
     *     Prevents "G" from matching "Gsus4" when "G" is actually correct.
     * <p>
     * Returns the closest chord name, or rawText unchanged if no match
     * is close enough.
     */
    private static final int MAX_CHORD_EDIT_DISTANCE = 3;

    public static String correctToChord(String rawText) {
        if (rawText == null || rawText.isEmpty()) return rawText;

        // Already a valid chord — no correction needed
        String normalized = normalizeChordToken(rawText);
        if (CHORD_PATTERN.matcher(normalized).matches()) {
            return normalized;
        }

        // Retrieve only the candidates starting with the same root
        char rawRoot = Character.toUpperCase(normalized.charAt(0));
        List<String> candidates = CHORD_DICTIONARY.getOrDefault(rawRoot, Collections.emptyList());

        if (candidates.isEmpty()) {
            return normalized;
        }

        String best = normalized;
        int bestDist = Integer.MAX_VALUE;

        for (String candidate : candidates) {
            // No more 'if (charAt(0) != rawRoot)' check needed here!
            int dist = LevenshteinDistance.getDefaultInstance().apply(normalized, candidate);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }

        if (bestDist <= MAX_CHORD_EDIT_DISTANCE) {
            logger.info("[ChordCorrect] '{}' → '{}' (distance={})", rawText, best, bestDist);
            return best;
        }

        logger.debug("[ChordCorrect] '{}' — no close match (best='{}' distance={})",
                rawText, best, bestDist);
        return rawText;
    }

    private static final Map<String, String> OCR_CORRECTIONS;

    static {
        OCR_CORRECTIONS = new LinkedHashMap<>();
        // Superscript digit normalization
        OCR_CORRECTIONS.put("²", "2");
        OCR_CORRECTIONS.put("³", "3");
        OCR_CORRECTIONS.put("⁴", "4");
        OCR_CORRECTIONS.put("⁵", "5");
        OCR_CORRECTIONS.put("⁶", "6");
        OCR_CORRECTIONS.put("⁷", "7");
        OCR_CORRECTIONS.put("⁸", "8");
        OCR_CORRECTIONS.put("⁹", "9");

        // Superscript letter normalization for chord quality suffixes.
        // Unicode has superscript forms for some letters but not all —
        // where no Unicode superscript exists, OCR typically outputs the
        // base character already, so no correction is needed for those.
        OCR_CORRECTIONS.put("ᵃ", "a");  // add   → Cadd9
        OCR_CORRECTIONS.put("ᵈ", "d");  // dim, add
        OCR_CORRECTIONS.put("ⁱ", "i");  // dim, min
        OCR_CORRECTIONS.put("ᵐ", "m");  // maj, min, m7
        OCR_CORRECTIONS.put("ⁿ", "n");  // min, dim
        OCR_CORRECTIONS.put("ᵒ", "o");  // dim (diminished °)
        OCR_CORRECTIONS.put("ˢ", "s");  // sus
        OCR_CORRECTIONS.put("ᵗ", "t");  // alt
        OCR_CORRECTIONS.put("ᵘ", "u");  // sus, aug, dim
        OCR_CORRECTIONS.put("ᵍ", "g");  // aug
        OCR_CORRECTIONS.put("ʲ", "j");  // maj
        OCR_CORRECTIONS.put("ʳ", "r");  // aug (augmented)

        // Common superscript symbols used as chord quality shorthand
        OCR_CORRECTIONS.put("°", "dim"); // ° → dim  (e.g. B° → Bdim)
        OCR_CORRECTIONS.put("ø", "m7b5");// ø → m7b5 (half-diminished)
        OCR_CORRECTIONS.put("△", "maj"); // △ → maj  (jazz notation)
        OCR_CORRECTIONS.put("Δ", "maj"); // Δ → maj  (alternate triangle)

        // Superscript plus/minus occasionally used in jazz charts
        OCR_CORRECTIONS.put("⁺", "aug"); // ⁺ → aug
        OCR_CORRECTIONS.put("⁻", "b");   // ⁻ → flat (e.g. b5, b9)
    };

    /**
     * FIX 2: Ghosting removal is now precise — only strips second char when it is
     * the lowercase echo of the root (e.g. "Cc7" → 'c' == toLowerCase('C') → "C7").
     * Previous logic stripped ANY lowercase non-m/b second char, which corrupted
     * legitimate quality suffixes: "Dsus4" → 's' != 'm'/'b' → wrongly stripped to "Dus4".
     * Now: "Dsus4" → 's' != toLowerCase('D')='d' → kept → "Dsus4" ✓
     * "Cc7"   → 'c' == toLowerCase('C')='c' → stripped → "C7" ✓
     * "Gg"    → 'g' == toLowerCase('G')='g' → stripped → "G" ✓
     */
    public static String normalizeChordToken(String raw) {
        String s = raw;
        for (Map.Entry<String, String> e : OCR_CORRECTIONS.entrySet()) {
            s = s.replace(e.getKey(), e.getValue());
        }
        if (!s.isEmpty() && Character.isLowerCase(s.charAt(0))) {
            s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
        // Only remove second char if it's a true OCR ghost (lowercase echo of root)
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char second = s.charAt(1);
            if (Character.isLowerCase(second)
                    && second == Character.toLowerCase(first)) {
                s = first + s.substring(2);
            }
        }
        return s;
    }

    /**
     * Chord dictionary — all valid chord names for correction lookup.
     * Sourced from chord_words.txt at startup so it stays in sync with
     * the Tesseract word list.
     */
    private static final Map<Character, List<String>> CHORD_DICTIONARY = loadChordDictionary();

    private static Map<Character, List<String>> loadChordDictionary() {
        try (InputStream is = ChordDetector.class.getResourceAsStream("/chord_words.txt")) {
            if (is == null) {
                logger.warn("[ChordDict] chord_words.txt not found — correction disabled");
                return Collections.emptyMap();
            }

            // Group by the first character of each chord name
            Map<Character, List<String>> dictionary = new BufferedReader(new InputStreamReader(is))
                    .lines()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.groupingBy(
                            s -> Character.toUpperCase(s.charAt(0))
                    ));

            int count = dictionary.values().stream().mapToInt(List::size).sum();
            logger.info("[ChordDict] loaded {} chord names across {} root buckets", count, dictionary.size());
            return dictionary;

        } catch (IOException e) {
            logger.warn("[ChordDict] failed to load: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
