package com.imagetoonsong.core;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.imagetoonsong.core.ChordDetector.*;

public class HocrTolerantParser {

    private static final Logger logger = LoggerFactory.getLogger(
            MethodHandles.lookup().lookupClass());

    // ── Tuning constants ────────────────────────────────────────────────────────
    /**
     * Words whose y_top values differ by less than this are on the same line.
     */
    public static final int Y_TOLERANCE = 12;

    /**
     * Unanchored chord extractor — used ONLY inside parseSingleTokenStrummingLine()
     * to find a chord at the START of a bar segment after splitting on '|'.
     * <p>
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
     * <p>
     * The overrides map contains  hOCR word yTop  →  clean re-OCR'd strumming text
     * for lines where OcrProcessor ran a second Tesseract pass with the restricted
     * STRUMMING_WHITELIST + PSM_SINGLE_LINE.
     * <p>
     * When a logical line's words include a yTop present in the override map,
     * the override value is emitted directly — no heuristic reconstruction needed.
     * The fallback path (isStrummingLine / renderStrummingLine) handles any lines
     * that were not caught by the re-OCR pass (e.g. multi-token garbled lines).
     *
     * @param lines                Logical Lines from the Document
     */
    private static void orderLogicalLinesAndWords(List<LogicalLine> lines) {
        // Ensure Lines and words are sorted.
        lines.sort(Comparator.comparingInt(l -> l.bbox().yTop()));
        for (LogicalLine line : lines) {
            line.words().sort(Comparator.comparingInt(w -> w.bbox().xLeft));
        }
    }

    // ── Step 2 : line clustering ────────────────────────────────────────────────
    public static List<LogicalLine> clusterIntoLines(Document doc) {

        // ── Pass 1: build one LogicalLine per ocr_line span ──────────────────
        // Using ocr_line as the anchor fixes the word-yTop-outside-line-bbox
        // problem (e.g. tall ascenders reported above their containing line).
        List<LogicalLine> raw = new ArrayList<>();

        for (Element lineSpan : doc.select("span.ocr_line")) {
            Bbox lineBbox = parseBbox(lineSpan.attr("title"));
            if (lineBbox == null) continue;

            LogicalLine logicalLine = new LogicalLine(lineBbox, LogicalLine.LineType.UNCLASSIFIED);

            for (Element wordSpan : lineSpan.select("span.ocrx_word")) {
                Bbox wordBbox = parseBbox(wordSpan.attr("title"));
                if (wordBbox == null) continue;
                String text = wordSpan.text().trim();
                if (!text.isEmpty()) {
                    logicalLine.words().add(
                            new LogicalLine.WordEntry(text, wordBbox));
                }
            }

            if (!logicalLine.words().isEmpty()) {
                raw.add(logicalLine);
            }
        }

        // Sort top-to-bottom before merging
        raw.sort(Comparator.comparingInt(l -> l.bbox().yTop()));

        // ── Pass 2: merge LogicalLines whose yTops are within tolerance ───────
        //
        // Tesseract splits a single visual line across multiple ocr_carea blocks
        // when it detects column boundaries or layout discontinuities.
        // e.g. "Na na na | na na | na na | na na naa" → 4 separate ocr_lines
        // all at yTop ≈ 4307, which should be one logical line.
        //
        // This is intentionally at the LogicalLine level (not word level) so we
        // still benefit from ocr_line anchoring for word-yTop outliers.
        List<LogicalLine> merged = new ArrayList<>();

        for (LogicalLine current : raw) {
            if (merged.isEmpty()) {
                merged.add(current);
                continue;
            }

            LogicalLine last = merged.getLast();
            if (Math.abs(current.bbox().yTop() - last.bbox().yTop()) <= Y_TOLERANCE) {
                // Same visual line — absorb words into the existing LogicalLine
                last.words().addAll(current.words());
                // Keep yTop as the minimum (topmost) of the two

                last.bbox = new Bbox(last.bbox().xLeft(), Math.min(last.bbox().yTop(), current.bbox().yTop()), last.bbox().xRight, last.bbox().yBottom, last.bbox().confidence);
            } else {
                merged.add(current);
            }
        }

        // Sort words left-to-right within each merged line
        for (LogicalLine line : merged) {
            line.words().sort(Comparator.comparingInt(w -> w.bbox().xLeft));
        }

        return merged;
    }

    // ── Step 3 : render ─────────────────────────────────────────────────────────

    /**
     * Renders all logical lines to OnSong text.
     * <p>
     * Line type priority (checked in order):
     * <p>
     * 0. OVERRIDE — a re-OCR'd strumming line supplied by OcrProcessor.
     * The value is emitted verbatim; all other checks are skipped.
     * This is the primary fix path for collapsed strumming tokens like
     * "|G//1/1|DIF#/1111]]" — OcrProcessor already produced the clean text.
     * <p>
     * 1. STRUMMING (fallback) — heuristic detection + reconstruction for lines
     * not covered by the override map (multi-token garbled lines, PATH B/C).
     * Must still be checked before isChordLine() for the same reasons as before.
     * <p>
     * 2. CHORD+LYRIC — chord line immediately above a lyric line → inline merge.
     * 3. PLAIN — lyric, section header, standalone chord line.
     */
    public static String buildFormattedOnsong(List<LogicalLine> lines,
                                              Map<Integer, String> overrides) {
        orderLogicalLinesAndWords(lines);
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < lines.size(); i++) {
            LogicalLine line = lines.get(i);

            // ── PRIORITY 0: re-OCR override ──────────────────────────────────
            String override = findOverride(line, overrides);
            if (override != null) {
                sb.append(override);

                // ── PRIORITY 1: heuristic strumming fallback ──────────────────────
            } else if (line.isStrummingLine()) {
                sb.append(renderStrummingLine(line));

                // ── PRIORITY 2: chord line(s) above a lyric line ─────────────────
                //
                // Tesseract sometimes splits chords that sit above the same lyric
                // into separate ocr_line spans — e.g. "D" and "G/D" each get their
                // own line. The old word-based clustering accidentally merged these
                // because their yTops were close. The new ocr_line-based clustering
                // keeps them separate, so we must consume ALL consecutive chord lines
                // and merge their words into one combined chord line before merging
                // with the lyric below.
            } else if (line.isChordLine()) {

                // Collect all consecutive chord lines
                List<LogicalLine> chordGroup = new ArrayList<>();
                chordGroup.add(line);
                while (i + 1 < lines.size() && lines.get(i + 1).isChordLine()) {
                    i++;
                    chordGroup.add(lines.get(i));
                }

                // Check whether a lyric line follows the chord group
                boolean hasNextLine = i + 1 < lines.size();

                if (hasNextLine) {
                    LogicalLine nextLine = lines.get(i + 1);
                    if (nextLine.isLyricLine()) {
                        LogicalLine combined = mergeChordLines(chordGroup);
                        sb.append(mergeChordIntoLyric(combined, lines.get(i + 1)));
                        i++;
                    } else if (nextLine.isSectionHeader()) {
                        sb.append(renderStandaloneChordLine(mergeChordLines(chordGroup)));
                    }
                } else {
                    sb.append(renderStandaloneChordLine(mergeChordLines(chordGroup)));
                }
            } else if (line.isSectionHeader()) {
                sb.append("\n").append(line.wordsToString());
            } else if (line.isLyricLine()) {
                sb.append(line.wordsToString());
            } else if (line.isLikelyChord()) {
                sb.append(line.wordsToString()).append(" *** \n");
            } else {
                sb.append(line.wordsToString()).append(" unclassified\n");
                logger.error("Found a line that is not classified {}", line.wordsToString());
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    /**
     * Renders a chord line that has no lyric below it.
     * <p>
     * Valid chord tokens are bracketed: G → [G], Gsus4 → [Gsus4]
     * Invalid tokens (OCR garbage like "Gsusd4d", low-confidence misreads)
     * are dropped entirely rather than emitted as noise.
     * Repeat annotations like (x2) are preserved as-is.
     * <p>
     * Output example:  [G] [Gsus4] [G] (x2)
     */
    private static String renderStandaloneChordLine(LogicalLine line) {
        StringBuilder sb = new StringBuilder();

        for (LogicalLine.WordEntry w : line.words()) {
            if (line.isRepeatAnnotation(w.text())) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(w.text());
                continue;
            }

            if (BRACKETED_CHORD_PATTERN.matcher(w.text()).matches()) {
                sb.append(w.text()).append(" ");
            } else {
                sb.append(w.text().replaceAll("\\|", "[|]"));
            }
        }

        return sb.toString();
    }

    /**
     * Merges multiple chord LogicalLines into one, sorting all words by xLeft.
     * <p>
     * Used when Tesseract splits chords above a single lyric line into separate
     * ocr_line spans. The merged line is then passed to mergeChordIntoLyric()
     * as if it were one chord line, preserving correct horizontal positioning.
     */
    private static LogicalLine mergeChordLines(List<LogicalLine> chordLines) {
        // Use the yTop of the first (topmost) chord line as the anchor
        LogicalLine combined = new LogicalLine(chordLines.getFirst().bbox, LogicalLine.LineType.CHORD);
        for (LogicalLine cl : chordLines) {
            combined.words().addAll(cl.words());
        }
        // Sort all words left-to-right so mergeChordIntoLyric() sees them
        // in the correct horizontal order regardless of which ocr_line they
        // came from
        combined.words().sort(Comparator.comparingInt(w -> w.bbox().xLeft));
        return combined;
    }

    /**
     * Looks up an override for this logical line by checking whether any of its
     * words' original hOCR yTop values are keys in the override map.
     * <p>
     * Using original word yTop (not the averaged line.yTop) ensures we match the
     * exact bbox value that OcrProcessor used as the map key when it cropped the
     * strumming line for re-OCR.
     * <p>
     * Returns the override string, or null if no match.
     */
    private static String findOverride(LogicalLine line, Map<Integer, String> overrides) {
        if (overrides.isEmpty()) return null;
        for (LogicalLine.WordEntry w : line.words()) {
            String found = overrides.get(w.bbox().yTop);
            if (found != null) return found;
        }
        return null;
    }

    // ── Strumming line detection (fallback for lines without override) ───────────

    // ── Strumming line rendering (fallback) ─────────────────────────────────────

    /**
     * Reconstructs a strumming line from heuristics when no re-OCR override exists.
     * <p>
     * Single token  → parseSingleTokenStrummingLine() (string-level repair).
     * Multi PATH C  → concatenate + parseSingleTokenStrummingLine().
     * Multi PATH B  → bbox gap-fill using bounding box x-positions.
     */
    private static String renderStrummingLine(LogicalLine line) {

        if (line.words().size() == 1) {
            return parseSingleTokenStrummingLine(line.words().getFirst().text());
        }

        long slashCount = line.words().stream()
                .filter(w -> w.text().matches("[/\\\\]+")).count();
        long pipeCount = line.words().stream()
                .filter(w -> w.text().matches("[|lLiI1]{1,2}")).count();

        if (slashCount == 0 && pipeCount == 0) {
            String combined = line.words().stream().map(LogicalLine.WordEntry::text).reduce("", String::concat);
            return parseSingleTokenStrummingLine(combined);
        }

        // PATH B — bbox gap-fill
        List<LogicalLine.WordEntry> tokens = new ArrayList<>(line.words());
        tokens.sort(Comparator.comparingInt(w -> w.bbox().xLeft));

        int slashWidth = estimateSlashWidth(tokens);
        StringBuilder sb = new StringBuilder();
        LogicalLine.WordEntry prev = null;

        for (LogicalLine.WordEntry token : tokens) {
            if (prev != null) {
                int gap = token.bbox().xLeft - prev.bbox().xRight;
                if (gap > slashWidth * 1.2 && slashWidth > 0) {
                    int dropped = Math.min((int) Math.round((double) gap / slashWidth), 8);
                    sb.repeat("/ ", Math.max(0, dropped));
                }
            }

            String normalised = ChordDetector.normalizeChordToken(token.text());

            if (CHORD_PATTERN.matcher(normalised).matches()) {
                sb.append('[').append(normalised).append(']');
            } else if (token.text().matches("[|lLiI1]{1,2}")) {
                sb.append("| ");
            } else if (token.text().matches("[/\\\\]+")) {
                sb.repeat("/ ", token.text().length());
            } else {
                sb.append(token.text()).append(' ');
            }
            prev = token;
        }

        String result = sb.toString().stripTrailing();
        if (result.startsWith("|") && !result.endsWith("|")) result += " |";
        return result;
    }

    /**
     * String-level repair for a single garbled strumming token.
     * <p>
     * This is the FALLBACK path — it runs only when OcrProcessor did not produce
     * a re-OCR override. It exists because:
     * (a) PATH C multi-token garbled lines may not be caught by
     * looksLikeStrummingToken() in OcrProcessor (which only checks single tokens).
     * (b) It provides a safety net if re-OCR is unavailable.
     * <p>
     * Input:  "|G//1/1|DIF#/1111]]"
     * Output: "| [G]/ / / / | [D/F#]/ / / / |"
     */
    private static String parseSingleTokenStrummingLine(String raw) {
        // Step 1: normalize bracket/brace chars → '|'
        String s = raw.replaceAll("[\\[\\]{}]", "|");

        // Step 2: repair mangled slash chords (e.g. "DIF#" → "D/F#")
        s = s.replaceAll("([A-G][#b]?)([IlL])([A-G][#b]?)", "$1/$3");

        // Step 3: split on pipe characters
        String[] segments = s.split("\\|+");
        StringBuilder sb = new StringBuilder();
        boolean first = true;

        for (String seg : segments) {
            seg = seg.stripTrailing();
            if (seg.isEmpty()) {
                sb.append("| ");
                first = false;
                continue;
            }

            Matcher chordMatcher = CHORD_EXTRACT.matcher(seg);
            String chord = null;
            String remainder = seg;

            if (chordMatcher.find() && chordMatcher.start() == 0) {
                chord = chordMatcher.group();
                remainder = seg.substring(chord.length());
            }

            int beats = 0;
            for (char c : remainder.toCharArray()) {
                if (c == '/' || c == '1' || c == 'l' || c == 'L') beats++;
            }

            if (!first) sb.append("| ");
            if (chord != null) sb.append('[').append(chord).append(']');
            sb.repeat("/ ", Math.max(0, beats));
            first = false;
        }

        String result = sb.toString().stripTrailing();
        if (!result.isEmpty() && !result.endsWith("|")) result += " |";
        return result;
    }

    private static int estimateSlashWidth(List<LogicalLine.WordEntry> tokens) {
        List<Integer> widths = tokens.stream()
                .filter(w -> w.text().matches("[|/lLiI1\\\\]"))
                .map(w -> w.bbox().xRight - w.bbox().xLeft)
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

    // When flushing chords before/after lyric words, only bracket valid chords


    private static String mergeChordIntoLyric(LogicalLine chordLine, LogicalLine lyricLine) {
        List<LogicalLine.WordEntry> chords = new ArrayList<>(chordLine.words());
        List<LogicalLine.WordEntry> lyrics = lyricLine.words();

        StringBuilder sb = new StringBuilder();
        int ci = 0;

        for (LogicalLine.WordEntry word : lyrics) {
            while (ci < chords.size() && chords.get(ci).bbox().xLeft < word.bbox().xLeft) {
                sb.append(chords.get(ci).text());
                ci++;
            }

            List<LogicalLine.WordEntry> inWord = new ArrayList<>();
            while (ci < chords.size() && chords.get(ci).bbox().xLeft <= word.bbox().xRight) {
                inWord.add(chords.get(ci++));
            }

            if (inWord.isEmpty()) {
                sb.append(patchWordText(word.text()));
            } else {
                String text = patchWordText(word.text());
                int pixelWidth = word.bbox().xRight - word.bbox().xLeft;
                int charCursor = 0;

                for (LogicalLine.WordEntry chord : inWord) {
                    int insertAt;
                    if (pixelWidth <= 0 || chord.bbox().xLeft <= word.bbox().xLeft) {
                        insertAt = 0;
                    } else {
                        double ratio = (double) (chord.bbox().xLeft - word.bbox().xLeft) / pixelWidth;
                        insertAt = Math.clamp((int) Math.round(ratio * text.length()),
                                0, text.length());
                    }
                    sb.append(text, charCursor, insertAt);
                    sb.append(chord.text());
                    charCursor = insertAt;
                }
                sb.append(text, charCursor, text.length());
            }
            sb.append(' ');
        }

        while (ci < chords.size()) {
            sb.append(chords.get(ci).text());
            ci++;
        }

        return sb.toString().stripTrailing();
    }

    // ── Line type classification ─────────────────────────────────────────────────


    public record Bbox(int xLeft, int yTop, int xRight, int yBottom, int confidence) {
        int[] asIntArray() {
            return new int[]{xLeft, yTop, xRight, yBottom, confidence};
        }
        String asAttr() {
            return String.format("bbox %d %d %d %d ; x_wconf %d", xLeft, yTop, xRight, yBottom, confidence);
        }
    };

    public static Bbox parseBbox(String title) {
        if (title == null) return null;
        Matcher m = Pattern.compile(
                "bbox\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)(?:.*?x_wconf\\s+(\\d+))?"
        ).matcher(title);
        if (!m.find()) return null;
        return new Bbox(Integer.parseInt(m.group(1)),           // xLeft
                Integer.parseInt(m.group(2)),           // yTop
                Integer.parseInt(m.group(3)),           // xRight
                Integer.parseInt(m.group(4)),           // yBottom
                m.group(5) != null
                        ? Integer.parseInt(m.group(5))  // wconf
                        : 0
        );
    }

    // ── Inner data classes ───────────────────────────────────────────────────────


}