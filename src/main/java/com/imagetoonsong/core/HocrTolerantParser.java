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

    private static List<LogicalLine> clusterIntoLines(List<WordEntry> words, int tolerance) {
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
                // Running average keeps cluster centre from locking to the first
                // word when Tesseract gives slightly wobbling baselines.
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

    // ── Step 3 : render ─────────────────────────────────────────────────────────

    /**
     * Renders all logical lines to OnSong text.
     *
     * Line type priority (checked in order):
     *  1. STRUMMING  — e.g. | G / / / / | Bm / / / / |
     *     Must be checked BEFORE isChordLine() because a strumming line with
     *     Tesseract-dropped slashes looks like a pure chord line (only G and Bm
     *     survive), which would cause those chords to be merged into the next
     *     lyric line — the primary misplacement bug this fixes.
     *  2. CHORD+LYRIC — chord line immediately above a lyric line → merged inline
     *  3. PLAIN       — lyric, section header, standalone chord line, etc.
     */
    private static String renderOnSong(List<LogicalLine> lines) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < lines.size(); i++) {
            LogicalLine line = lines.get(i);

            if (isStrummingLine(line)) {
                // ── NEW: strumming lines rendered with gap-filled slash reconstruction
                sb.append(renderStrummingLine(line));

            } else if (isChordLine(line) && i + 1 < lines.size() && !isChordLine(lines.get(i + 1))) {
                // Chord line paired with the lyric line below → inline [Chord] notation
                sb.append(mergeChordIntoLyric(line, lines.get(i + 1)));
                i++; // skip the lyric line we just consumed

            } else {
                // Plain lyric, section header, or standalone chord line
                sb.append(wordsToString(line));
            }

            sb.append('\n');
        }

        return sb.toString();
    }

    // ── Strumming line detection ─────────────────────────────────────────────────

    /**
     * Returns true when this line looks like a strumming/bar pattern,
     * e.g.:  | G / / / / / / | Bm / / / / / / |
     *
     * A strumming line has at least one real chord token AND either:
     *  (a) three or more slash/pipe tokens, OR
     *  (b) at least one pipe token alongside at least one slash token.
     *
     * Tokens Tesseract commonly produces for '|': |  l  L  I  i  1
     * Tokens Tesseract commonly produces for '/': /  \
     *
     * Must be checked before isChordLine() — when Tesseract drops all slash
     * tokens (noise rejection), only the chord names survive, making the line
     * indistinguishable from a normal chord line by isChordLine() alone.
     */
    private static boolean isStrummingLine(LogicalLine line) {
        if (line.words.size() < 2) return false;

        long chordCount = line.words.stream()
                .filter(w -> CHORD_PATTERN.matcher(normalizeChordToken(w.text)).matches())
                .count();

        long slashCount = line.words.stream()
                .filter(w -> w.text.matches("[/\\\\]+"))
                .count();

        long pipeCount = line.words.stream()
                .filter(w -> w.text.matches("[|lLiI1]{1,2}"))
                .count();

        if (chordCount < 1) return false;

        // Strong signal: 3+ slashes, or at least one pipe + one slash
        return slashCount >= 3 || (pipeCount >= 1 && slashCount >= 1);
    }

    /**
     * Reconstructs a strumming line from its hOCR tokens, filling gaps where
     * Tesseract dropped slash characters using bounding-box x-position arithmetic.
     *
     * Algorithm:
     *  1. Sort tokens left-to-right by xLeft.
     *  2. Estimate the pixel width of one slash token on this line (median of
     *     actual slash/pipe token widths, or a 20px fallback).
     *  3. Walk tokens; for each gap wider than 1.2× slash width, emit that many
     *     reconstructed '/' characters (capped at 8 to avoid runaway output).
     *  4. Chord tokens → emit as [Chord].
     *  5. Pipe tokens  → emit as '|'.
     *  6. Slash tokens → emit as '/'.
     *
     * Output example:  | [G]/ / / / / / | [Bm]/ / / / / / |
     */
    private static String renderStrummingLine(LogicalLine line) {
        List<WordEntry> tokens = new ArrayList<>(line.words);
        tokens.sort(Comparator.comparingInt(w -> w.xLeft));

        int slashWidth = estimateSlashWidth(tokens);

        StringBuilder sb = new StringBuilder();
        WordEntry prev = null;

        for (WordEntry token : tokens) {

            // Fill gap between previous token and this one with dropped slashes
            if (prev != null) {
                int gap = token.xLeft - prev.xRight;
                if (gap > slashWidth * 1.2 && slashWidth > 0) {
                    int dropped = Math.min((int) Math.round((double) gap / slashWidth), 8);
                    for (int j = 0; j < dropped; j++) sb.append("/ ");
                }
            }

            String normalised = normalizeChordToken(token.text);

            if (CHORD_PATTERN.matcher(normalised).matches()) {
                sb.append('[').append(normalised).append(']');
            } else if (token.text.matches("[|lLiI1]{1,2}")) {
                sb.append("| ");
            } else if (token.text.matches("[/\\\\]+")) {
                // Tesseract sometimes bunches multiple slashes into one token
                for (int j = 0; j < token.text.length(); j++) sb.append("/ ");
            } else {
                // Unknown short token — emit as-is
                sb.append(token.text).append(' ');
            }

            prev = token;
        }

        // Ensure line ends with a closing bar if it opened with one
        String result = sb.toString().stripTrailing();
        if (result.startsWith("|") && !result.endsWith("|")) result += " |";
        return result;
    }

    /**
     * Returns the median pixel width of slash/pipe tokens on this line.
     * Used by renderStrummingLine() to estimate how many slashes were dropped
     * in a gap between two recognised tokens.
     * Falls back to 20px if no slash/pipe tokens are present.
     */
    private static int estimateSlashWidth(List<WordEntry> tokens) {
        List<Integer> widths = tokens.stream()
                .filter(w -> w.text.matches("[|/lLiI1\\\\]"))
                .map(w -> w.xRight - w.xLeft)
                .filter(w -> w > 0)
                .sorted()
                .toList();

        if (widths.isEmpty()) return 20; // fallback: ~20px per slash at 3× upscale
        return widths.get(widths.size() / 2); // median
    }

    // ── Chord+lyric merging ──────────────────────────────────────────────────────

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

            // A. Flush chords that fall in the gap before this word
            while (ci < chords.size() && chords.get(ci).xLeft < word.xLeft) {
                sb.append('[').append(normalizeChordToken(chords.get(ci).text)).append(']');
                ci++;
            }

            // B. Collect chords whose xLeft falls inside this word's pixel span
            List<WordEntry> inWord = new ArrayList<>();
            while (ci < chords.size() && chords.get(ci).xLeft <= word.xRight) {
                inWord.add(chords.get(ci));
                ci++;
            }

            // C. Render the word, splitting it at each chord's proportional index
            if (inWord.isEmpty()) {
                sb.append(word.text);
            } else {
                String text      = word.text;
                int   pixelWidth = word.xRight - word.xLeft;
                int   charCursor = 0;

                for (WordEntry chord : inWord) {
                    int insertAt;
                    if (pixelWidth <= 0 || chord.xLeft <= word.xLeft) {
                        insertAt = 0;
                    } else {
                        double ratio = (double)(chord.xLeft - word.xLeft) / pixelWidth;
                        insertAt = (int) Math.round(ratio * text.length());
                        insertAt = Math.clamp(insertAt, 0, text.length());
                    }
                    sb.append(text, charCursor, insertAt);
                    sb.append('[').append(normalizeChordToken(chord.text)).append(']');
                    charCursor = insertAt;
                }

                sb.append(text, charCursor, text.length());
            }

            sb.append(' ');
        }

        // D. Any chords that trail after the last lyric word
        while (ci < chords.size()) {
            sb.append('[').append(normalizeChordToken(chords.get(ci).text)).append(']');
            ci++;
        }

        return sb.toString().stripTrailing();
    }

    // ── Line type classification ─────────────────────────────────────────────────

    /**
     * Returns true when most tokens on the line look like chord names.
     * Uses CHORD_PATTERN.matches() (whole-token, anchored) — not .find().
     * Majority vote allows one mis-OCR'd token without flipping the result.
     */
    private static boolean isChordLine(LogicalLine line) {
        long chordCount = line.words.stream()
                .filter(w -> CHORD_PATTERN.matcher(normalizeChordToken(w.text)).matches())
                .count();
        return chordCount > 0 && chordCount >= (line.words.size() + 1) / 2;
    }

    // ── Token normalisation ──────────────────────────────────────────────────────

    private static final Map<String, String> OCR_CORRECTIONS = Map.of(
            "é", "s",
            "è", "s",
            "ê", "s",
            "ë", "s",
            "0", "O",   // zero in chord context is always letter O
            "1", "l"    // only meaningful in chord context
    );

    /**
     * Applied to individual chord tokens before chord regex matching.
     *  - Applies OCR character corrections.
     *  - Uppercases the first letter (fixes ocr producing "c7" → "C7").
     *  - Strips OCR ghosting (e.g. "Cc7" → "C7") — a doubled lowercase letter
     *    that is not 'm' (minor) or 'b' (flat) is treated as noise.
     */
    private static String normalizeChordToken(String raw) {
        String s = raw;
        for (Map.Entry<String, String> e : OCR_CORRECTIONS.entrySet()) {
            s = s.replace(e.getKey(), e.getValue());
        }
        if (!s.isEmpty() && Character.isLowerCase(s.charAt(0))) {
            s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
        if (s.length() >= 2) {
            char second = s.charAt(1);
            if (Character.isLowerCase(second) && second != 'm' && second != 'b') {
                s = s.charAt(0) + s.substring(2);
            }
        }
        return s;
    }

    // ── Rendering helpers ────────────────────────────────────────────────────────

    private static String wordsToString(LogicalLine line) {
        StringBuilder sb = new StringBuilder();
        for (WordEntry w : line.words) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(w.text);
        }
        return sb.toString();
    }

    // ── hOCR parsing ─────────────────────────────────────────────────────────────

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
                Integer.parseInt(m.group(1)), // xLeft
                Integer.parseInt(m.group(2)), // yTop
                Integer.parseInt(m.group(3)), // xRight
                Integer.parseInt(m.group(4))  // yBottom
        };
    }

    // ── Inner data classes ───────────────────────────────────────────────────────

    private record WordEntry(String text, int xLeft, int yTop, int xRight, int yBottom) {}

    private static class LogicalLine {
        int yTop; // mutable running average
        final List<WordEntry> words = new ArrayList<>();
        LogicalLine(int yTop) { this.yTop = yTop; }
    }
}
