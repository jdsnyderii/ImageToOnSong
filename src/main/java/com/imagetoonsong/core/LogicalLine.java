package com.imagetoonsong.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.imagetoonsong.core.ChordDetector.*;


public class LogicalLine {


    LineType lineType;
    HocrTolerantParser.Bbox bbox;
    private final List<WordEntry> words = new ArrayList<>();

    public LogicalLine(HocrTolerantParser.Bbox bbox, LineType lineType) {
        this.bbox = bbox; this.lineType = lineType;
    }
    public void clear() {
        words.clear();
    }

    public List<WordEntry> words() {
        return words;
    }

    public void bracketChords() {
        if  (lineType != LineType.CHORD) return;
        List<WordEntry> newChords = new ArrayList<>();
        for (WordEntry word : words()) {
            newChords.add(new WordEntry(ChordDetector.safeChordBracket(word.text),  word.bbox));
        }
        clear();
        words().addAll(newChords);
    }

    public HocrTolerantParser.Bbox bbox() {
        return bbox;
    }
    public boolean isLikelyChord() {
        return lineType == LineType.LIKELY_CHORD;
    }
    public String wordsToString() {
        StringBuilder sb = new StringBuilder();
        for (WordEntry w : words) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(w.text);
        }
        return sb.toString();
    }
    /**
     * Returns true when this line is a strumming/bar pattern.
     * <p>
     * Only reached when OcrProcessor did NOT produce a re-OCR override for this
     * line — i.e. the first-pass token did not trigger looksLikeStrummingToken().
     * This typically means a multi-token case or a line with different garbling.
     * <p>
     * PATH A — single-token: token has pipe + slashes + chord root (same test as
     * OcrProcessor.looksLikeStrummingToken — catches any the re-OCR pass missed).
     * PATH B — multi-token with recognisable slash/pipe tokens.
     * PATH C — multi-token with high slash-character density (≥ 40%).
     */
    public boolean isStrummingLine() {
        if (lineType == LogicalLine.LineType.STRUM) return true;
        if (words().isEmpty()) return false;
        if (isSectionHeader() || isChordLine()) return false;
        // PATH A — single collapsed token
        if (words().size() == 1) {
            boolean singleTokenStrumming = isSingleTokenStrumming(words().getFirst().text());
            lineType = LogicalLine.LineType.STRUM;
            return singleTokenStrumming;
        }

        // PATH B — multi-token with recognisable slash/pipe tokens
        long chordCount = words().stream()
                .filter(w -> BRACKETED_CHORD_PATTERN.matcher(w.text()).matches())
                .count();
        long slashCount = words().stream()
                .filter(w -> w.text().matches("[/\\\\]+"))
                .count();
        long pipeCount = words().stream()
                .filter(w -> w.text().matches("[|lLiI1]{1,2}"))
                .count();

        boolean isStrumming = chordCount >= 1 && (slashCount >= 3 && (pipeCount >= 3));
        if (isStrumming) {
            lineType = LogicalLine.LineType.STRUM;
        }
        return isStrumming;
    }

    private static boolean isSingleTokenStrumming(String text) {
        boolean hasPipeLike = text.chars().anyMatch(c -> "|[]{}".indexOf(c) >= 0);
        long slashLike = text.chars().filter(c -> "/1lL".indexOf(c) >= 0).count();
        boolean hasChordRoot = text.chars().anyMatch(c -> "ABCDEFG".indexOf(c) >= 0);
        return hasPipeLike && slashLike >= 3 && hasChordRoot && text.length() >= 5;
    }
    /**
     * Returns true if a token is a repeat annotation like (x2), (2x), (4x).
     * These appear inline with chord lines in many chart formats and should
     * not count against isChordLine()'s majority vote.
     */
    public boolean isRepeatAnnotation(String text) {
        return text.matches("(?i)\\(?[x×]?\\d+[x×]?\\)?");
    }

    public boolean isChordLine() {
        if (lineType == LogicalLine.LineType.CHORD) return true;
        if (lineType != LogicalLine.LineType.UNCLASSIFIED) return false;

        long total = words().stream()
                .filter(w -> !isRepeatAnnotation(w.text()))
                .count();
        long chordCount = words().stream()
                .filter(w -> !isRepeatAnnotation(w.text()))
                .filter(w -> CHORD_PATTERN.matcher(w.text()).matches() && w.bbox().confidence() >= 40)
                .count();
        boolean detectedChordLine = chordCount > 0 && chordCount >= (total + 1) / 2;
        if (total == chordCount) {
            lineType = LogicalLine.LineType.CHORD;
            bracketChords();
        } else if (detectedChordLine) {
            lineType = LogicalLine.LineType.LIKELY_CHORD;
        }

        return total == chordCount;
    }

    public boolean isSectionHeader() {
        if (lineType == LogicalLine.LineType.SECTION) return true;
        String text = wordsToString().trim();
        boolean isHeader = SectionDetector.isSectionHeader(text) && lineType == LogicalLine.LineType.UNCLASSIFIED;
        if (isHeader) {
            lineType = LogicalLine.LineType.SECTION;
            WordEntry firstWord = words().getFirst();
            words().clear();
            words().add(new WordEntry(normalizeSection(text), firstWord.bbox()));
        }
        return isHeader;
    }

    private String normalizeSection(String raw) {
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
    public boolean isLyricLine() {
        return lineType == LineType.LYRIC;
    }

    /**
     * Replaces digits that are visually similar to letters when they appear
     * inside an otherwise alphabetic word — e.g. "Ch0rus" → "Chorus".
     * Digits that are legitimately numeric (e.g. the "1" in "Verse 1") are
     * left alone because they are surrounded by spaces, not letters.
     */
    private String fixOcrDigitsInWords(String input) {
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

    private String toTitleCase(String input) {
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

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s", bbox.toString()));
        for (WordEntry word : words) {
            sb.append(" ").append(word.toString());
        }
        return sb.toString();
    }


    public record WordEntry(String text, HocrTolerantParser.Bbox bbox) {
    }

    public enum LineType {
        SECTION,
        CHORD,
        STRUM,
        LYRIC,
        LIKELY_CHORD,
        UNCLASSIFIED
    }
}