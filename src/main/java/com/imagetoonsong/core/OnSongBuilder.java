package com.imagetoonsong.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class OnSongBuilder {
    private static final Logger logger = LoggerFactory.getLogger(
            MethodHandles.lookup().lookupClass());

    public String buildOnSong(String rawOcrText, String title, String artist, boolean emptyTextBox) {
        logger.info("rawOCRText: {}}", rawOcrText);

        StringBuilder sb = new StringBuilder();

        if (emptyTextBox) {
            // Metadata (OnSong format)
            sb.append(title != null && !title.isEmpty() ? title : "Untitled Song").append("\n");
            sb.append(artist != null && !artist.isEmpty() ? artist : "Unknown Artist").append("\n");
            sb.append("Key: C").append("\n");
            sb.append("Tempo: 96").append("\n");
            sb.append("Time: 4/4").append("\n");
            sb.append("\n");
        }
        String[] lines = rawOcrText.split("\n");

        int lineNumber = 0;
        for (String line : lines) {
            lineNumber++;
            if (line.isEmpty()) {
                sb.append("\n");
                logger.info("{} : Found empty", lineNumber);

                continue;
            }

            // Detect possible section headers (Verse 1, Chorus, Bridge, etc.)
            if (SectionDetector.detectSectionCaseInsensitive(line)) {
                sb.append("\n");
                logger.info("{} : Found section {}", lineNumber, line);
                sb.append(normalizeSection(line)).append("\n");
                continue;
            }
            if (line.length() == 1) {
                logger.info("{} : Found singleton {}}", lineNumber, line);
            }
            sb.append(line).append("\n");
        }

        return flagIncompleteLines(sb.toString());
    }

    private static String flagIncompleteLines(String onSongText) {
        String[] lines = onSongText.split("\n");
        Pattern hasChord = Pattern.compile("\\[[A-G][^]]*]");
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean prevHasChord = i > 0 && hasChord.matcher(lines[i - 1]).find();
            boolean nextHasChord = i < lines.length - 1 && hasChord.matcher(lines[i + 1]).find();
            boolean thisHasChord = hasChord.matcher(line).find();
            boolean isSectionHeader = SectionDetector.isSectionHeader(line);
            boolean isBlank = line.isBlank();

            if (!isSectionHeader && !isBlank && !thisHasChord && (prevHasChord || nextHasChord)) {
                sb.append(line).append("  ← ⚠️ possible missing chord\n");
            } else {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private static String normalizeSection(String raw) {
        if (raw == null || raw.isBlank()) return raw;

        String stripped = raw.strip();

        int start = 0;
        int end = stripped.length() - 1;

        // Only strip square brackets — never parentheses.
        // "(x4)" at the end is valid repeat annotation content, not a wrapper.
        if (stripped.charAt(0) == '[') start++;
        if (stripped.charAt(end) == ']') end--;

        if (start > end) return raw;

        String content = stripped.substring(start, end + 1);

        // Strip any residual bracket characters from OCR misreads.
        // e.g. "Tag]" → "Tag"
        // Only strip square brackets — parentheses inside content are intentional.
        content = content.replace("[", "").replace("]", "");

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
            if (!sb.isEmpty()) sb.append(' ');
            if (word.isEmpty()) continue;
            sb.append(Character.toUpperCase(word.charAt(0)));
            sb.append(word.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}