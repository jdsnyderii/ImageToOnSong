package com.imagetoonsong.core;

import java.util.regex.Pattern;

public class ChordDetector {

    // Robust regex for common guitar/piano chords: C, Am7, F#, G/B, Dsus4, C#m7b5, etc.
    private static final Pattern CHORD_PATTERN = Pattern.compile(
            "\\b([A-G])(#{1,2}|b{1,2})?(m|min|maj|sus|sus4|dim|aug|add|ø|°)?\\d?/?[A-G]?[b#]?\\d?\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Converts a raw OCR line that looks like a chord line into bracketed format.
     * Example: "C   Am7   F   G/B"  →  "[C]     [Am7]   [F]   [G/B]"
     */
    public String convertToBracketed(String line) {
        if (line == null || line.trim().isEmpty()) return line;

        // Replace matched chords with [Chord]
        String bracketed = CHORD_PATTERN.matcher(line).replaceAll(match -> {
            String chord = match.group();
            return "[" + chord + "]";
        });

        // Clean extra spaces (optional - can preserve for alignment later)
        return bracketed.replaceAll("\\s+", " ");
    }

    /**
     * Simple heuristic: returns true if a line contains mostly chords
     */
    public boolean isLikelyChordLine(String line) {
        if (line == null || line.trim().isEmpty()) return false;
        String trimmed = line.trim();
        long chordMatches = CHORD_PATTERN.matcher(trimmed).results().count();
        // Heuristic: if >30% of "words" look like chords
        String[] words = trimmed.split("\\s+");
        return chordMatches >= Math.max(1, words.length * 0.3);
    }
}