package com.imagetoonsong.core;

import java.util.Arrays;
import java.util.regex.Pattern;

public class ChordDetector {

    /**
     * Full-token matcher — use with .matches() on individual tokens only.
     *
     * Changes from previous version:
     *  - Anchored with ^ and $ so it matches the whole token, not a substring.
     *    Eliminates false positives like matching 'G' inside "God" or 'C' in "Calling".
     *  - Root [A-G] is NOT case-insensitive — uppercase root is required.
     *    Callers must run normalizeChordToken() (which uppercases the first char) before matching.
     *  - m(?!aj) negative lookahead prevents 'm' in "maj" being consumed as minor quality.
     *  - Slash bass note requires a full bass note: /[A-G][#b]? — a bare trailing '/' won't match.
     *  - Removed \\b word boundaries (redundant with anchors, and unreliable at line edges).
     *
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

    /**
     * Inline scanner — use with .replaceAll() on a full line of text.
     *
     * Uses negative lookbehind/lookahead to avoid:
     *  - Matching letters inside words (e.g. 'G' in "God")
     *  - Double-bracketing already-bracketed chords like [G]
     *
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
     *
     * Uses CHORD_INLINE (not CHORD_PATTERN) so it can scan within a string.
     */
    public String convertToBracketed(String line) {
        if (line == null || line.isEmpty()) return line;
        return CHORD_INLINE.matcher(line).replaceAll(match -> "[" + match.group() + "]");
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
}
