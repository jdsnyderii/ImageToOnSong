package com.imagetoonsong.core;

import static com.imagetoonsong.core.ChordDetector.CHORD_PATTERN;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HocrTolerantParser {

    // ── Tuning constants ────────────────────────────────────────────────────────
    /** Words whose y_top values differ by less than this are on the same line. */
    private static final int Y_TOLERANCE = 12;

    /**
     * Unanchored chord extractor — used ONLY inside parseSingleTokenStrummingLine()
     * to find a chord at the START of a bar segment after splitting on '|'.
     *
     * This is intentionally separate from CHORD_PATTERN (anchored ^…$). It is
     * never used for line-type classification — only for best-effort reconstruction
     * of garbled tokens when no re-OCR override is available.
     */
    private static final Pattern CHORD_EXTRACT = Pattern.compile(
            "^[A-G]([#b])?(m(?!aj)|min|maj|M|aug|dim|sus[24]?|add|ø|°)?(\\d{1,2})?(add\\d{1,2})?(/[A-G][#b]?)?"
    );

    // ── Public entry-points ─────────────────────────────────────────────────────

    /**
     * Primary entry point — accepts re-OCR overrides from OcrProcessor.
     *
     * The overrides map contains  hOCR word yTop  →  clean re-OCR'd strumming text
     * for lines where OcrProcessor ran a second Tesseract pass with the restricted
     * STRUMMING_WHITELIST + PSM_SINGLE_LINE.
     *
     * When a logical line's words include a yTop present in the override map,
     * the override value is emitted directly — no heuristic reconstruction needed.
     * The fallback path (isStrummingLine / renderStrummingLine) handles any lines
     * that were not caught by the re-OCR pass (e.g. multi-token garbled lines).
     *
     * @param doc              Parsed html Document
     * @param strummingOverrides  yTop → clean text from OcrProcessor re-OCR pass
     */
    public static String parseHocrToString(Document doc, Map<Integer, String> strummingOverrides) {

        List<WordEntry> allWords = extractWords(doc);
        if (allWords.isEmpty()) return "";

        List<LogicalLine> lines = clusterIntoLines(allWords, Y_TOLERANCE);

        lines.sort(Comparator.comparingInt(l -> l.yTop));
        for (LogicalLine line : lines) {
            line.words.sort(Comparator.comparingInt(w -> w.xLeft));
        }

        return renderOnSong(lines, strummingOverrides);
    }

    // ── Step 1 : word extraction ────────────────────────────────────────────────

    private static List<WordEntry> extractWords(Document doc) {
        List<WordEntry> result = new ArrayList<>();
        for (Element span : doc.select("span.ocrx_word")) {
            int[] bbox = parseBbox(span.attr("title"));
            if (bbox == null) continue;
            String text = span.text().stripTrailing();
            if (!text.isEmpty()) {
                result.add(new WordEntry(text, bbox[0], bbox[1], bbox[2], bbox[3]));
            }
        }
        return result;
    }

    // ── Step 2 : line clustering ────────────────────────────────────────────────

    private static List<LogicalLine> clusterIntoLines(List<WordEntry> words, int tolerance) {
        words.sort(Comparator.comparingInt(w -> w.yTop));

        List<LogicalLine> lines = new ArrayList<>();

        for (WordEntry word : words) {
            LogicalLine best     = null;
            int         bestDist = Integer.MAX_VALUE;

            for (LogicalLine line : lines) {
                int dist = Math.abs(line.yTop - word.yTop);
                if (dist <= tolerance && dist < bestDist) {
                    best     = line;
                    bestDist = dist;
                }
            }

            if (best != null) {
                best.words.add(word);
                // Running average keeps cluster centre from locking to first word
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
     *
     *  0. OVERRIDE — a re-OCR'd strumming line supplied by OcrProcessor.
     *     The value is emitted verbatim; all other checks are skipped.
     *     This is the primary fix path for collapsed strumming tokens like
     *     "|G//1/1|DIF#/1111]]" — OcrProcessor already produced the clean text.
     *
     *  1. STRUMMING (fallback) — heuristic detection + reconstruction for lines
     *     not covered by the override map (multi-token garbled lines, PATH B/C).
     *     Must still be checked before isChordLine() for the same reasons as before.
     *
     *  2. CHORD+LYRIC — chord line immediately above a lyric line → inline merge.
     *  3. PLAIN — lyric, section header, standalone chord line.
     */
    private static String renderOnSong(List<LogicalLine> lines,
                                       Map<Integer, String> overrides) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < lines.size(); i++) {
            LogicalLine line = lines.get(i);

            // ── PRIORITY 0: re-OCR override ──────────────────────────────────
            String override = findOverride(line, overrides);
            if (override != null) {
                sb.append(override);

                // ── PRIORITY 1: heuristic strumming fallback ──────────────────────
            } else if (isStrummingLine(line)) {
                sb.append(renderStrummingLine(line));

                // ── PRIORITY 2: chord line above a lyric line ─────────────────────
            } else if (isChordLine(line)
                    && i + 1 < lines.size()
                    && !isChordLine(lines.get(i + 1))) {
                sb.append(mergeChordIntoLyric(line, lines.get(i + 1)));
                i++;

                // ── PRIORITY 3: plain output ──────────────────────────────────────
            } else {
                sb.append(wordsToString(line));
            }

            sb.append('\n');
        }

        return sb.toString();
    }

    /**
     * Looks up an override for this logical line by checking whether any of its
     * words' original hOCR yTop values are keys in the override map.
     *
     * Using original word yTop (not the averaged line.yTop) ensures we match the
     * exact bbox value that OcrProcessor used as the map key when it cropped the
     * strumming line for re-OCR.
     *
     * Returns the override string, or null if no match.
     */
    private static String findOverride(LogicalLine line, Map<Integer, String> overrides) {
        if (overrides.isEmpty()) return null;
        for (WordEntry w : line.words) {
            String found = overrides.get(w.yTop);
            if (found != null) return found;
        }
        return null;
    }

    // ── Strumming line detection (fallback for lines without override) ───────────

    /**
     * Returns true when this line is a strumming/bar pattern.
     *
     * Only reached when OcrProcessor did NOT produce a re-OCR override for this
     * line — i.e. the first-pass token did not trigger looksLikeStrummingToken().
     * This typically means a multi-token case or a line with different garbling.
     *
     * PATH A — single-token: token has pipe + slashes + chord root (same test as
     *   OcrProcessor.looksLikeStrummingToken — catches any the re-OCR pass missed).
     * PATH B — multi-token with recognisable slash/pipe tokens.
     * PATH C — multi-token with high slash-character density (≥ 40%).
     */
    private static boolean isStrummingLine(LogicalLine line) {
        if (line.words.isEmpty()) return false;

        // PATH A — single collapsed token
        if (line.words.size() == 1) {
            return isSingleTokenStrumming(line.words.get(0).text);
        }

        // PATH B — multi-token with recognisable slash/pipe tokens
        long chordCount = line.words.stream()
                .filter(w -> CHORD_PATTERN.matcher(normalizeChordToken(w.text)).matches())
                .count();
        long slashCount = line.words.stream()
                .filter(w -> w.text.matches("[/\\\\]+"))
                .count();
        long pipeCount  = line.words.stream()
                .filter(w -> w.text.matches("[|lLiI1]{1,2}"))
                .count();

        if (chordCount >= 1 && (slashCount >= 3 || (pipeCount >= 1 && slashCount >= 1))) {
            return true;
        }

        // PATH C — high slash-char density
        String allText   = line.words.stream().map(w -> w.text).reduce("", String::concat);
        long   slashLike = allText.chars()
                .filter(c -> "/1lLIT|[]{}\\".indexOf(c) >= 0)
                .count();
        boolean hasChordRoot = allText.chars().anyMatch(c -> "ABCDEFG".indexOf(c) >= 0);

        return hasChordRoot
                && allText.length() >= 5
                && (double) slashLike / allText.length() >= 0.40;
    }

    private static boolean isSingleTokenStrumming(String text) {
        boolean hasPipeLike  = text.chars().anyMatch(c -> "|[]{}".indexOf(c) >= 0);
        long    slashLike    = text.chars().filter(c -> "/1lL".indexOf(c) >= 0).count();
        boolean hasChordRoot = text.chars().anyMatch(c -> "ABCDEFG".indexOf(c) >= 0);
        return hasPipeLike && slashLike >= 3 && hasChordRoot && text.length() >= 5;
    }

    // ── Strumming line rendering (fallback) ─────────────────────────────────────

    /**
     * Reconstructs a strumming line from heuristics when no re-OCR override exists.
     *
     * Single token  → parseSingleTokenStrummingLine() (string-level repair).
     * Multi PATH C  → concatenate + parseSingleTokenStrummingLine().
     * Multi PATH B  → bbox gap-fill using bounding box x-positions.
     */
    private static String renderStrummingLine(LogicalLine line) {

        if (line.words.size() == 1) {
            return parseSingleTokenStrummingLine(line.words.getFirst().text);
        }

        long slashCount = line.words.stream()
                .filter(w -> w.text.matches("[/\\\\]+")).count();
        long pipeCount  = line.words.stream()
                .filter(w -> w.text.matches("[|lLiI1]{1,2}")).count();

        if (slashCount == 0 && pipeCount == 0) {
            String combined = line.words.stream().map(w -> w.text).reduce("", String::concat);
            return parseSingleTokenStrummingLine(combined);
        }

        // PATH B — bbox gap-fill
        List<WordEntry> tokens = new ArrayList<>(line.words);
        tokens.sort(Comparator.comparingInt(w -> w.xLeft));

        int           slashWidth = estimateSlashWidth(tokens);
        StringBuilder sb         = new StringBuilder();
        WordEntry     prev       = null;

        for (WordEntry token : tokens) {
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
                for (int j = 0; j < token.text.length(); j++) sb.append("/ ");
            } else {
                sb.append(token.text).append(' ');
            }
            prev = token;
        }

        String result = sb.toString().stripTrailing();
        if (result.startsWith("|") && !result.endsWith("|")) result += " |";
        return result;
    }

    /**
     * String-level repair for a single garbled strumming token.
     *
     * This is the FALLBACK path — it runs only when OcrProcessor did not produce
     * a re-OCR override. It exists because:
     *  (a) PATH C multi-token garbled lines may not be caught by
     *      looksLikeStrummingToken() in OcrProcessor (which only checks single tokens).
     *  (b) It provides a safety net if re-OCR is unavailable.
     *
     * Input:  "|G//1/1|DIF#/1111]]"
     * Output: "| [G]/ / / / | [D/F#]/ / / / |"
     */
    private static String parseSingleTokenStrummingLine(String raw) {
        // Step 1: normalize bracket/brace chars → '|'
        String s = raw.replaceAll("[\\[\\]{}]", "|");

        // Step 2: repair mangled slash chords (e.g. "DIF#" → "D/F#")
        s = s.replaceAll("([A-G][#b]?)([IlL])([A-G][#b]?)", "$1/$3");

        // Step 3: split on pipe characters
        String[]      segments = s.split("\\|+");
        StringBuilder sb       = new StringBuilder();
        boolean       first    = true;

        for (String seg : segments) {
            seg = seg.stripTrailing();
            if (seg.isEmpty()) {
                sb.append("| ");
                first = false;
                continue;
            }

            Matcher chordMatcher = CHORD_EXTRACT.matcher(seg);
            String  chord        = null;
            String  remainder    = seg;

            if (chordMatcher.find() && chordMatcher.start() == 0) {
                chord     = chordMatcher.group();
                remainder = seg.substring(chord.length());
            }

            int beats = 0;
            for (char c : remainder.toCharArray()) {
                if (c == '/' || c == '1' || c == 'l' || c == 'L') beats++;
            }

            if (!first) sb.append("| ");
            if (chord != null) sb.append('[').append(chord).append(']');
            for (int i = 0; i < beats; i++) sb.append("/ ");
            first = false;
        }

        String result = sb.toString().stripTrailing();
        if (!result.isEmpty() && !result.endsWith("|")) result += " |";
        return result;
    }

    private static int estimateSlashWidth(List<WordEntry> tokens) {
        List<Integer> widths = tokens.stream()
                .filter(w -> w.text.matches("[|/lLiI1\\\\]"))
                .map(w -> w.xRight - w.xLeft)
                .filter(w -> w > 0)
                .sorted()
                .toList();
        if (widths.isEmpty()) return 20;
        return widths.get(widths.size() / 2);
    }

    // ── Chord+lyric merging ──────────────────────────────────────────────────────

    private static String patchWordText(String text) {
        return text.replace('|', 'I');
    }

    private static String mergeChordIntoLyric(LogicalLine chordLine, LogicalLine lyricLine) {
        List<WordEntry> chords = new ArrayList<>(chordLine.words);
        List<WordEntry> lyrics = lyricLine.words;

        StringBuilder sb = new StringBuilder();
        int ci = 0;

        for (WordEntry word : lyrics) {
            while (ci < chords.size() && chords.get(ci).xLeft < word.xLeft) {
                sb.append('[').append(normalizeChordToken(chords.get(ci).text)).append(']');
                ci++;
            }

            List<WordEntry> inWord = new ArrayList<>();
            while (ci < chords.size() && chords.get(ci).xLeft <= word.xRight) {
                inWord.add(chords.get(ci++));
            }

            if (inWord.isEmpty()) {
                sb.append(patchWordText(word.text));
            } else {
                String text       = patchWordText(word.text);
                int    pixelWidth = word.xRight - word.xLeft;
                int    charCursor = 0;

                for (WordEntry chord : inWord) {
                    int insertAt;
                    if (pixelWidth <= 0 || chord.xLeft <= word.xLeft) {
                        insertAt = 0;
                    } else {
                        double ratio = (double) (chord.xLeft - word.xLeft) / pixelWidth;
                        insertAt = Math.clamp((int) Math.round(ratio * text.length()),
                                0, text.length());
                    }
                    sb.append(text, charCursor, insertAt);
                    sb.append('[').append(normalizeChordToken(chord.text)).append(']');
                    charCursor = insertAt;
                }
                sb.append(text, charCursor, text.length());
            }
            sb.append(' ');
        }

        while (ci < chords.size()) {
            sb.append('[').append(normalizeChordToken(chords.get(ci).text)).append(']');
            ci++;
        }

        return sb.toString().stripTrailing();
    }

    // ── Line type classification ─────────────────────────────────────────────────

    private static boolean isChordLine(LogicalLine line) {
        long chordCount = line.words.stream()
                .filter(w -> CHORD_PATTERN.matcher(normalizeChordToken(w.text)).matches())
                .count();
        return chordCount > 0 && chordCount >= (line.words.size() + 1) / 2;
    }

    // ── Token normalisation ──────────────────────────────────────────────────────

    private static final Map<String, String> OCR_CORRECTIONS = Map.of(
            "é", "s", "è", "s", "ê", "s", "ë", "s",
            "0", "O",
            "1", "l"
    );

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

    private static int[] parseBbox(String title) {
        if (title == null) return null;
        Matcher m = Pattern.compile("bbox\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)").matcher(title);
        if (!m.find()) return null;
        return new int[]{
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4))
        };
    }

    // ── Inner data classes ───────────────────────────────────────────────────────

    private record WordEntry(String text, int xLeft, int yTop, int xRight, int yBottom) {}

    private static class LogicalLine {
        int yTop;
        final List<WordEntry> words = new ArrayList<>();
        LogicalLine(int yTop) { this.yTop = yTop; }
    }
}