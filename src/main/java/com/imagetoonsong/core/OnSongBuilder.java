package com.imagetoonsong.core;

import java.util.LinkedHashMap;
import java.util.Map;

public class OnSongBuilder {

    private final ChordDetector chordDetector = new ChordDetector();

    public String buildOnSong(String rawOcrText, String title, String artist) {
        System.out.printf("rawOCRText: %s\n", rawOcrText);

        StringBuilder sb = new StringBuilder();

        // Metadata (OnSong format)
        sb.append(title != null && !title.isEmpty() ? title : "Untitled Song").append("\n");
        sb.append(artist != null && !artist.isEmpty() ? artist : "Unknown Artist").append("\n");
        sb.append("Key: C").append("\n");
        sb.append("Tempo: 96").append("\n");
        sb.append("Time: 4/4").append("\n");
        sb.append("\n");

        String[] lines = rawOcrText.split("\n");

        boolean inSection = false;
        for (String line : lines) {
            if (line.isEmpty()) {
                sb.append("\n");
                continue;
            }

            // Detect possible section headers (Verse 1, Chorus, Bridge, etc.)
            if (line.matches("(?i)^[^a-zA-Z]*(verse|chorus|bridge|intro|outro|pre-?chorus|tag|interlude|instrumental)[^a-zA-Z]*.*")) {
                if (inSection) sb.append("\n");
                sb.append(normalizeSection(line)).append("\n");
                inSection = true;
                continue;
            }

            // If line looks like chords, convert to bracketed
            if (chordDetector.isLikelyChordLine(line)) {
//                String bracketed = chordDetector.convertToBracketed(line);
                sb.append(line).append("\n");
            } else {
                // Assume lyrics line - for now just add as-is
                // Future: align chords above lyrics using boxes
                sb.append(line).append("\n");
            }
        }

        return sb.toString();
    }


    private static String normalizeSection(String raw) {
        if (raw == null || raw.isBlank()) return raw;

        String stripped = raw.strip();

        // Stop at first letter OR digit (not just letter)
        int start = 0;
        while (start < stripped.length() && !Character.isLetterOrDigit(stripped.charAt(start))) {
            start++;
        }

        int end = stripped.length() - 1;
        while (end > start && !Character.isLetterOrDigit(stripped.charAt(end))) {
            end--;
        }

        if (start > end) return raw;

        String content = stripped.substring(start, end + 1);
        content = fixOcrDigitsInWords(content);
        content = toTitleCase(content);

        return content + ":";
    }
    /**
     * Replaces digits that are visually similar to letters when they appear
     * inside an otherwise alphabetic word — e.g. "Ch0rus" → "Chorus".
     * Digits that are legitimately numeric (e.g. the "1" in "Verse 1") are
     * left alone because they are surrounded by spaces, not letters.
     */
    private static String fixOcrDigitsInWords(String input) {
        Map<Character, Character> digitToLetter = new LinkedHashMap<>();
        digitToLetter.put('0', 'o');
        digitToLetter.put('1', 'l');
        digitToLetter.put('3', 'e');
        digitToLetter.put('5', 's');
        digitToLetter.put('8', 'B');

        StringBuilder sb = new StringBuilder(input);
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (digitToLetter.containsKey(c)) {
                boolean prevIsLetter = i > 0 && Character.isLetter(sb.charAt(i - 1));
                boolean nextIsLetter = i < sb.length() - 1 && Character.isLetter(sb.charAt(i + 1));
                // Only substitute if sandwiched by letters — genuine numeric tokens are not
                if (prevIsLetter || nextIsLetter) {
                    sb.setCharAt(i, digitToLetter.get(c));
                }
            }
        }
        return sb.toString();
    }

    private static String toTitleCase(String input) {
        String[] words = input.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (sb.length() > 0) sb.append(' ');
            if (word.isEmpty()) continue;
            sb.append(Character.toUpperCase(word.charAt(0)));
            sb.append(word.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}