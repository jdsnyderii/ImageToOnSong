package com.imagetoonsong.core;

import static com.imagetoonsong.core.ChordDetector.CHORD_PATTERN;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HocrTolerantParser {

    // ── Tuning constants ────────────────────────────────────────────────────────
    /** Words whose y_top values differ by less than this are on the same line.   */
    private static final int Y_TOLERANCE = 12;

    /** Regex to recognise a chord token (e.g. A, Bm, C#7, Dsus4, F/A, G/B).    */
//    private static final Pattern CHORD_RE = Pattern.compile(
//            "^[A-G][b#]?(m|maj|min|aug|dim|sus|add)?\\d*((\\/[A-G][b#]?)?)$"
//    );

    // ── Public entry-point ──────────────────────────────────────────────────────

    public static String parseHocrToString(String html) {
        Document doc = Jsoup.parse(html);

        // 1. Collect every word with its bounding box
        List<WordEntry> allWords = extractWords(doc);
        if (allWords.isEmpty()) return "";

        // 2. Cluster words into logical lines by vertical proximity
        List<LogicalLine> lines = clusterIntoLines(allWords, Y_TOLERANCE);

        // 3. Sort lines top-to-bottom, words left-to-right within each line
        lines.sort(Comparator.comparingInt(l -> l.yTop));
        for (LogicalLine line : lines) {
            line.words.sort(Comparator.comparingInt(w -> w.xLeft));
        }

        // 4. Emit OnSong text
        return renderOnSong(lines);
    }

    // ── Step 1 : word extraction ────────────────────────────────────────────────

    private static List<WordEntry> extractWords(Document doc) {
        List<WordEntry> result = new ArrayList<>();
        for (Element span : doc.select("span.ocrx_word")) {
            int[] bbox = parseBbox(span.attr("title"));
            if (bbox == null) continue;
            String text = span.text().trim();
            if (!text.isEmpty()) {
                result.add(new WordEntry(text, bbox[0], bbox[1], bbox[2], bbox[3]));
            }
        }
        return result;
    }

    // ── Step 2 : line clustering ────────────────────────────────────────────────
    //
    // Strategy: iterate words sorted by y_top; each word either joins an
    // existing "open" line whose representative y_top is within tolerance, or
    // it starts a new line.
    //
    // Using a sorted list of open lines and checking only the closest candidate
    // keeps this O(n log n) without needing a full grid.

    private static List<LogicalLine> clusterIntoLines(List<WordEntry> words, int tolerance) {
        // Sort by y_top so we process top-to-bottom
        words.sort(Comparator.comparingInt(w -> w.yTop));

        List<LogicalLine> lines = new ArrayList<>();

        for (WordEntry word : words) {
            LogicalLine best = null;
            int bestDist = Integer.MAX_VALUE;

            for (LogicalLine line : lines) {
                int dist = Math.abs(line.yTop - word.yTop);
                if (dist <= tolerance && dist < bestDist) {
                    best = line;
                    bestDist = dist;
                }
            }

            if (best != null) {
                best.words.add(word);
                // Keep the representative y_top as a running average so the
                // cluster centre drifts gently rather than locking to the first
                // word only. This helps when Tesseract gives slightly wobbling
                // baselines across a wide carea.
                best.yTop = (best.yTop * (best.words.size() - 1) + word.yTop)
                        / best.words.size();
            } else {
                LogicalLine newLine = new LogicalLine(word.yTop);
                newLine.words.add(word);
                lines.add(newLine);
            }
        }

        return lines;
    }

    private static String renderOnSong(List<LogicalLine> lines) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < lines.size(); i++) {
            LogicalLine line = lines.get(i);
            boolean isChordLine = isChordLine(line);

            if (isChordLine && i + 1 < lines.size() && !isChordLine(lines.get(i + 1))) {
                // Merge chord line into the following lyric line using [Chord]
                // inline notation, positioned by x_left offset.
                sb.append(mergeChordIntoLyric(line, lines.get(i + 1)));
                i++; // skip the lyric line we just consumed
            } else {
                // Pure lyric line (or standalone chord line with no lyric below)
                sb.append(wordsToString(line));
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    /**
     * Interleaves chord tokens into a lyric line using OnSong [Chord] notation.
     *
     * Chords that fall within a word's pixel span are split at a proportional
     * character index so that e.g. [Bb] above the 'b' in "Remember" becomes
     * "Re[Bb]member" rather than "[Bb]Remember".
     */
    private static String mergeChordIntoLyric(LogicalLine chordLine, LogicalLine lyricLine) {
        List<WordEntry> chords = new ArrayList<>(chordLine.words);
        List<WordEntry> lyrics  = lyricLine.words;

        StringBuilder sb = new StringBuilder();
        int ci = 0; // chord cursor

        for (WordEntry word : lyrics) {

            // ── A. Flush chords that fall in the GAP before this word ──────────────
            // chord.xLeft < word.xLeft means it sits to the left of this word,
            // i.e. in the inter-word space (or before the first word entirely).
            while (ci < chords.size() && chords.get(ci).xLeft < word.xLeft) {
                sb.append('[').append(normalizeChordToken(chords.get(ci).text)).append(']');
                ci++;
            }

            // ── B. Collect chords whose xLeft falls INSIDE this word's pixel span ──
            List<WordEntry> inWord = new ArrayList<>();
            while (ci < chords.size() && chords.get(ci).xLeft <= word.xRight) {
                inWord.add(chords.get(ci));
                ci++;
            }

            // ── C. Render the word, splitting it at each chord's proportional index ─
            if (inWord.isEmpty()) {
                sb.append(word.text);
            } else {
                String text      = word.text;
                int   pixelWidth = word.xRight - word.xLeft;
                int   charCursor = 0;

                for (WordEntry chord : inWord) {
                    int insertAt;

                    if (pixelWidth <= 0 || chord.xLeft <= word.xLeft) {
                        // Chord is right at/before the left edge — insert at position 0
                        insertAt = 0;
                    } else {
                        double ratio = (double)(chord.xLeft - word.xLeft) / pixelWidth;
                        insertAt = (int) Math.round(ratio * text.length());
                        insertAt = Math.clamp(insertAt, 0, text.length());
                    }

                    // Append the slice of the word before the chord, then the tag
                    sb.append(text, charCursor, insertAt);
                    sb.append('[').append(normalizeChordToken(chord.text)).append(']');
                    charCursor = insertAt;
                }

                // Remainder of the word after the last chord
                sb.append(text, charCursor, text.length());
            }

            sb.append(' ');
        }

        // ── D. Any chords that trail after the last lyric word ──────────────────────
        while (ci < chords.size()) {
            sb.append('[').append(normalizeChordToken(chords.get(ci).text)).append(']');
            ci++;
        }

        return sb.toString().stripTrailing();
    }
    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static final Map<String, String> OCR_CORRECTIONS = Map.of(
            "é", "s",
            "è", "s",
            "ê", "s",
            "ë", "s",
            "0", "O",  // zero in chord names is always letter O
            "1", "l"   // only applies in chord context, be selective
    );

    /** Applied to individual chord tokens BEFORE chord regex matching. */
    private static String normalizeChordToken(String raw) {
        String s = raw;
        for (Map.Entry<String, String> e : OCR_CORRECTIONS.entrySet()) {
            s = s.replace(e.getKey(), e.getValue());
        }
        // Capitalize first letter — fixes c7 → C7
        if (!s.isEmpty() && Character.isLowerCase(s.charAt(0))) {
            s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
        // 2. Fix OCR ghosting (e.g., "Cc7" -> "C7")
        if (s.length() >= 2) {
            char second = s.charAt(1);
            // If second char is lowercase but NOT 'm' or 'b', it's noise.
            if (Character.isLowerCase(second) && second != 'm' && second != 'b') {
                s = s.charAt(0) + s.substring(2);
            }
        }
        return s;
    }

    /** Returns true when most tokens on the line look like chord names. */
    private static boolean isChordLine(LogicalLine line) {
        long chordCount = line.words.stream()
                .filter(w -> CHORD_PATTERN.matcher(normalizeChordToken(w.text)).matches())
                .count();
        // Majority vote – allows one mis-OCR'd token without flipping the verdict
        return chordCount > 0 && chordCount >= (line.words.size() + 1) / 2;
    }

    private static String wordsToString(LogicalLine line) {
        StringBuilder sb = new StringBuilder();
        for (WordEntry w : line.words) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(w.text);
        }
        return sb.toString();
    }

    /**
     * Parses "bbox x_left y_top x_right y_bottom" from a Tesseract title string.
     * Returns int[4] = { xLeft, yTop, xRight, yBottom } or null on failure.
     */
    private static int[] parseBbox(String title) {
        if (title == null) return null;
        Pattern p = Pattern.compile("bbox\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)");
        Matcher m = p.matcher(title);
        if (!m.find()) return null;
        return new int[]{
                Integer.parseInt(m.group(1)), // x_left
                Integer.parseInt(m.group(2)), // y_top
                Integer.parseInt(m.group(3)), // x_right
                Integer.parseInt(m.group(4))  // y_bottom
        };
    }

    // ── Inner data classes ───────────────────────────────────────────────────────

    private record WordEntry(String text, int xLeft, int yTop, int xRight, int yBottom) {
    }

    private static class LogicalLine {
        int yTop; // mutable running average
        final List<WordEntry> words = new ArrayList<>();

        LogicalLine(int yTop) { this.yTop = yTop; }
    }
}