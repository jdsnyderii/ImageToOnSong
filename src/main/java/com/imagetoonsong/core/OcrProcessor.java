package com.imagetoonsong.core;

import com.imagetoonsong.config.TessConfig;
import com.imagetoonsong.config.TessConfigLoader;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.indexer.IntIndexer;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_imgproc.Vec4iVector;
import org.bytedeco.tesseract.TessBaseAPI;
import org.bytedeco.tesseract.global.tesseract;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

import static com.imagetoonsong.App.TESS_DATA;
import static com.imagetoonsong.core.ChordDetector.CHORD_PATTERN;
import static com.imagetoonsong.core.HocrTolerantParser.Bbox;
import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imwrite;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

public class OcrProcessor {

    public static final TessConfigLoader configLoader = new TessConfigLoader();

    public static final String SPAN_OCRX_WORD = "span.ocrx_word";
    public static final String SPAN_OCR_LINE = "span.ocr_line";
    private static final Logger logger = LoggerFactory.getLogger(
            MethodHandles.lookup().lookupClass());
    public static final String PAGE_WHITELIST = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" +
            "0123456789#/()[]., '-{}|";
    public static final String OCR_LANGUAGE = "eng";
    public static final String TITLE = "title";

    private TessBaseAPI PAGE_OCR_API;
    private TessBaseAPI LINE_OCR_API;
    private TessBaseAPI CHORD_FALLBACK;
    private TessBaseAPI CHORD_SINGLE_CHAR;
    private TessBaseAPI STRUMMING_SINGLE_CHAR;
    private TessBaseAPI STRUMMING_FALLBACK;

    // ── Angle threshold for '|' vs '/' classification ───────────────────────
    private static final double ANGLE_THRESHOLD_DEGREES = 65.0;

    /**
     * Restricted character whitelist for strumming-line re-OCR.
     * <p>
     * Contains exactly the characters that can appear in a strumming pattern:
     * A–G          chord roots
     * b            flat accidental (also 'b' in "dim", "add" etc.)
     * #            sharp accidental
     * m a j i u s d g M   quality suffix letters (min, maj, aug, dim, sus, add)
     * 0–9          numeric extensions (7, 9, sus2, sus4, add9…)
     * /            beat stroke AND slash-chord separator (G/D)
     * |            bar line
     * (space)      token separator
     * <p>
     * Deliberately excludes all other letters and punctuation so Tesseract
     * cannot misread a '/' as 'l', 'T', '1', or any other character.
     */
    private static final String STRUMMING_WHITELIST = " |ABCDEFGMbmajisudg#245679/";

    /**
     * Chord-line whitelist — everything valid in a chord symbol.
     * Wider than STRUMMING_WHITELIST since chord lines have richer notation:
     * qualities (maj, min, sus, dim, aug, add), extensions (7,9,11,13),
     * slash chords (/), accidentals (#, b), superscript Unicode chars.
     * <p>
     * Excludes prose letters (full words) so Tesseract can't produce lyric text.
     */
    private static final String CHORD_LINE_WHITELIST =
            "ABCDEFGMbmajisudgntø°△Δ#²³⁴⁵⁶⁷⁸⁹ˢᵘᵐᵃʲᵒᵈⁱⁿᵗᵍ⁺⁻/() 0123456789";

    /**
     * Vertical padding (in preprocessed-image pixels) added above and below
     * a strumming-line crop before re-OCR.
     * <p>
     * The preprocessed image is 3× upscaled, so 5px here ≈ 1-2px in the
     * original — enough to include ascenders/descenders without bleeding
     * into the adjacent line above or below.
     */
    private static final int STRUMMING_CROP_VERT_PAD = 5;

    /**
     * Aspect-ratio threshold for classifying a contour as a bar line '|'.
     * <p>
     * A bar line is a near-vertical stroke with very little horizontal extent.
     * A beat stroke '/' is diagonal and has substantially more width.
     * <p>
     * '|' typical bounding box: width ≈ 3–8px,  height ≈ 30–50px → w/h ≈ 0.06–0.16
     * '/' typical bounding box: width ≈ 15–25px, height ≈ 30–50px → w/h ≈ 0.40–0.70
     * <p>
     * Any contour with w/h below this threshold is classified as '|' without
     * running Tesseract on it, which avoids the PSM_SINGLE_CHAR empty-result
     * failure that '|' consistently produces (too narrow for Tesseract's
     * character recognizer to produce a confident match).
     * <p>
     * Set conservatively at 0.30 — well between the two ranges — so neither
     * '|' nor '/' is misclassified. Increase if thin slash fonts produce false
     * bar-line matches; decrease if wide bar-line fonts are missed.
     */
    private static final double BARLINE_ASPECT_RATIO_THRESHOLD = 0.30;

    // The recognition for this is very sensitive to 4.0 changes.
    private static final float PREPROCESSING_SCALE = 4.0f;

    private static final String TESS_CHAR_WHITELIST = "tessedit_char_whitelist";

    List<TessBaseAPI> tessBaseAPIList = new ArrayList<>();

    public OcrProcessor() {
        PAGE_OCR_API = createApiFromConfig("page-sparse"); tessBaseAPIList.add(PAGE_OCR_API);
        LINE_OCR_API = createApiFromConfig("chord-line"); tessBaseAPIList.add(LINE_OCR_API);
        CHORD_FALLBACK = createApiFromConfig("chord-fallback"); tessBaseAPIList.add(CHORD_FALLBACK);
        CHORD_SINGLE_CHAR = createApiFromConfig("chord-single-char"); tessBaseAPIList.add(CHORD_SINGLE_CHAR);
        STRUMMING_FALLBACK = createApiFromConfig("strum-fallback"); tessBaseAPIList.add(STRUMMING_FALLBACK);
        STRUMMING_SINGLE_CHAR = createApiFromConfig("strum-single-char"); tessBaseAPIList.add(STRUMMING_SINGLE_CHAR);

        logger.info("All Tesseract APIs initialized with config system");
    }

    public void shutdown() {
        for (TessBaseAPI tessBaseAPI : tessBaseAPIList) {
            try {
                tessBaseAPI.close();
            } catch (Exception e) {
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // MAIN OCR PIPELINE
    // ════════════════════════════════════════════════════════════════════════

    public String extractText(File imageFile) throws Exception {
        return extractText(ImageSource.fromFile(imageFile));
    }

    /**
     * Full pipeline:
     * 1. First-pass Tesseract on whole page → hOCR.
     * 2. Scan hOCR at the LINE level for strumming patterns.
     * 3. For each found, crop the full-width row from the preprocessed Mat
     * and re-run Tesseract with PSM_SINGLE_LINE + STRUMMING_WHITELIST.
     * 4. Pass hOCR + strumming overrides to HocrTolerantParser.
     */
    public String extractText(ImageSource imageSource) throws Exception {
        logger.debug("=== Starting Bytedeco Tesseract OCR ===");
        int tesseractDpi = Math.round(imageSource.dpi());

        Java2DFrameConverter biConverter = new Java2DFrameConverter();
        OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();

        Mat mat = matConverter.convert(biConverter.convert(imageSource.toBufferedImage()));

        Mat processed = preprocessImage(mat);

        Mat padded = new Mat();
        copyMakeBorder(processed, padded,
                0, 0, 0, 40,
                BORDER_CONSTANT,
                new Scalar(255, 255, 255, 255));
        processed.release();

        try {
            setTessImageParameters(PAGE_OCR_API, padded, tesseractDpi);

            // ── First pass ───────────────────────────────────────────────────────
            BytePointer outText = PAGE_OCR_API.GetHOCRText(0);
            if (outText == null) {
                throw new RuntimeException("PAGE_OCR_API.GetHOCRText() returned a null pointer");
            }
            String html = outText.getString();
            logger.info(html);
            outText.deallocate();

            // ── Re-OCR pass for strumming lines ──────────────────────────────────
            Document doc = Jsoup.parse(html);
            List<LogicalLine> lines = HocrTolerantParser.clusterIntoLines(doc);
            saveLinesHtml(lines, "01_after_cluster");

            // Mutate document in place — chord words get corrected text,
            // spatial coordinates unchanged, parser sees clean data
            detectLineTypes(lines);
            saveLinesHtml(lines, "02_after_detectLineTypes");
            applyChordReOcrToLines(lines, padded, tesseractDpi);
            saveLinesHtml(lines, "03_after_chordReOcr");
            Map<Integer, String> strummingOverrides = reOcrStrummingLines(doc, padded, tesseractDpi);
            padded.release();

            // ── Parse ────────────────────────────────────────────────────────────
            String result = HocrTolerantParser.buildFormattedOnsong(lines, strummingOverrides);
            logger.info("OCR completed successfully - {} characters returned", result.length());
            return result;
        } finally {
            mat.release();
            PAGE_OCR_API.Clear();
        }
    }

    /**
     * Detect and brack lines that have all good chords so we can reduce the rescanning later.
     * @param lines
     */
    private void detectLineTypes(List<LogicalLine> lines) {
        for (LogicalLine line : lines) {
            if (line.isChordLine()) { continue;}
            if (line.isLikelyChord()) { continue;}
            if (line.isStrummingLine()) { continue;}
            if (line.isSectionHeader()) { continue;}
            line.lineType = LogicalLine.LineType.LYRIC;
        }
    }

    /**
     * Translates ALL positional data in an hOCR title attribute from
     * crop-relative coordinates to document coordinates, while preserving
     * ALL non-positional metadata (x_wconf, x_size, x_descenders,
     * x_ascenders, baseline etc.) unchanged.
     * <p>
     * hOCR title format (ocrx_word):
     * "bbox x1 y1 x2 y2; x_wconf 53"
     * <p>
     * hOCR title format (ocr_line):
     * "bbox x1 y1 x2 y2; baseline 0 -8; x_size 53; x_descenders 8; x_ascenders 15"
     * <p>
     * Only the bbox coordinates are translated — everything after the
     * first semicolon is preserved as-is.
     */
    private static Bbox translateBboxInTitle(
            Bbox newBox, int cropOriginX, int cropOriginY) {

        return new Bbox(cropOriginX + newBox.xLeft(),
                cropOriginY + newBox.yTop(), cropOriginX + newBox.xRight(), cropOriginY + newBox.yBottom(), newBox.confidence());
    }

    /**
     * Replaces the ocrx_word children of originalLineSpan with those from
     * newHocr, translating all bboxes from crop space to document space.
     * <p>
     * Data transfer contract per word span:
     * bbox        → translated to document coordinates       (from new OCR)
     * x_wconf     → kept from new OCR pass                   (reflects new quality)
     * x_size      → kept from original line span             (font metrics unchanged)
     * x_descenders/x_ascenders → kept from original          (font metrics unchanged)
     * text content → from new OCR                            (the corrected text)
     * <p>
     * The ocr_line span's own title (bbox + baseline + x_size etc.) is
     * preserved from the original — the line geometry hasn't changed,
     * only the word content within it has been corrected.
     */
    private void replaceLineNode(LogicalLine originalLine,
                                 Document newDoc,
                                 int cropOriginX,
                                 int cropOriginY) {

        Element newLineSp = newDoc.selectFirst(SPAN_OCR_LINE);
        if (newLineSp == null) {
            logger.warn("[replaceLineNode] no ocr_line found in new hOCR — skipping");
            return;
        }

        // Build replacement word spans with correctly translated bboxes
        List<Element> newWords = newLineSp.select(SPAN_OCRX_WORD);
        if (newWords.isEmpty()) {
            logger.warn("[replaceLineNode] new hOCR produced no ocrx_word spans — skipping");
            return;
        }

        originalLine.clear();
        for (Element newWord : newWords) {
            String newText = newWord.text();

            logger.info("Trying chord {}", newText);
            Bbox newBox = parseBboxFromHocrTitle(newWord.attr(TITLE));
            logger.info("Coordinates before {}", newBox);
            Bbox translatedBox = translateBboxInTitle(newBox, cropOriginX, cropOriginY);
            String bracketedText = ChordDetector.safeChordBracket(newText);
            originalLine.words().add(new LogicalLine.WordEntry(bracketedText, translatedBox));
            logger.info("Coordinates after {}", translatedBox);
        }
        originalLine.lineType = LogicalLine.LineType.CHORD;

        logger.info("[replaceLineNode] replaced {} word(s) in line yTop={}",
                newWords.size(),
                originalLine.bbox.yTop()); // yTop for logging
    }

    /**
     * Extracts a named field value from an hOCR title string.
     * e.g. extractTitleField("bbox 0 0 100 50; x_size 53.0", "x_size") → "53.0"
     * Returns empty string if the field is not present.
     */
    private static String extractTitleField(String title, String fieldName) {
        if (title == null) return "";
        java.util.regex.Matcher m = Pattern.compile(
                fieldName + "\\s+([\\d.\\-]+)").matcher(title);
        return m.find() ? m.group(1) : "";
    }

    private String runLineOcr(Mat crop, int dpi) {
        logger.info("Running runLineOcr tesseract with {}");
        try {
            ImageSource.saveImage(crop, new File("build/lineCrop.png"));
            setTessImageParameters(LINE_OCR_API, crop, dpi);
            BytePointer ptr = LINE_OCR_API.GetHOCRText(0);  // hOCR not plain text
            if (ptr == null) return "";
            String hocr = ptr.getString();
            ptr.deallocate();
            return hocr;

        } finally {
            LINE_OCR_API.Clear();
        }
    }

    /**
     * Saves a human-readable HTML debug view of the logical lines at the current
     * pipeline stage.  Call this after any mutation step to see what changed.
     *
     * Output: build/<name>_<timestamp>.html
     *
     * Colour coding:
     *   CHORD          blue
     *   LYRIC          green
     *   SECTION        orange
     *   STRUMMING      purple
     *   LIKELY_CHORD   teal
     *   UNCLASSIFIED   grey
     */
    public static void saveLinesHtml(List<LogicalLine> lines, String stageName) {
        try {
            Path workingDir = Path.of("").toAbsolutePath();
            Files.createDirectories(workingDir.resolve("Documents/ImageToOnSong/"));
            logger.info("Writing to directory {}", workingDir.toString());

            String filename = stageName + "_" + System.currentTimeMillis() + ".html";
            Path outputPath = workingDir.resolve(filename);

            StringBuilder html = new StringBuilder(4096);
            html.append("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <title>LogicalLines — """).append(stageName).append("""
                </title>
                  <style>
                    body  { font-family: monospace; font-size: 14px;
                            background: #1e1e1e; color: #d4d4d4; padding: 16px; }
                    h1    { color: #9cdcfe; margin-bottom: 4px; }
                    h2    { color: #888; font-size: 12px; margin: 0 0 16px; }
                    table { border-collapse: collapse; width: 100%; margin-bottom: 24px; }
                    th    { background: #333; color: #9cdcfe; padding: 6px 10px;
                            text-align: left; font-size: 12px; }
                    td    { padding: 4px 10px; border-bottom: 1px solid #333;
                            vertical-align: top; }
                    tr:hover td { background: #2a2a2a; }
                    .badge { display:inline-block; padding:2px 7px; border-radius:4px;
                             font-size:11px; font-weight:bold; }
                    .CHORD         { background:#0e4a7e; color:#9cdcfe; }
                    .LYRIC         { background:#1a4a1a; color:#6ac26a; }
                    .SECTION       { background:#5a3800; color:#ffcc66; }
                    .STRUMMING     { background:#3a1a5a; color:#c586c0; }
                    .LIKELY_CHORD  { background:#004a4a; color:#4ec9b0; }
                    .UNCLASSIFIED  { background:#3a3a3a; color:#aaa; }
                    .word-chip { display:inline-block; margin:2px 4px;
                                 padding:2px 6px; background:#2d2d2d;
                                 border:1px solid #555; border-radius:3px; }
                    .conf-high { border-color:#6ac26a; }
                    .conf-mid  { border-color:#ffcc66; }
                    .conf-low  { border-color:#f44747; }
                    .bbox { font-size:10px; color:#666; }
                  </style>
                </head>
                <body>
                """);

            html.append("<h1>Stage: ").append(escapeHtml(stageName)).append("</h1>\n");
            html.append("<h2>").append(lines.size()).append(" logical lines</h2>\n");
            html.append("<table>\n");
            html.append("<tr><th>#</th><th>Type</th><th>BBox</th>")
                    .append("<th>Words</th><th>Raw text</th></tr>\n");

            int idx = 0;
            for (LogicalLine line : lines) {
                String typeName = line.lineType == null ? "UNCLASSIFIED" : line.lineType.name();

                html.append("<tr>");

                // Index
                html.append("<td>").append(idx++).append("</td>");

                // Type badge
                html.append("<td><span class=\"badge ").append(typeName).append("\">")
                        .append(typeName).append("</span></td>");

                // Line bbox
                if (line.bbox != null) {
                    html.append("<td class=\"bbox\">")
                            .append(line.bbox.xLeft()).append(',')
                            .append(line.bbox.yTop()).append(" → ")
                            .append(line.bbox.xRight()).append(',')
                            .append(line.bbox.yBottom())
                            .append("</td>");
                } else {
                    html.append("<td class=\"bbox\">—</td>");
                }

                // Word chips with per-word confidence colouring
                html.append("<td>");
                for (LogicalLine.WordEntry word : line.words()) {
                    int conf = word.bbox() != null ? word.bbox().confidence() : -1;
                    String confClass = conf >= 80 ? "conf-high" : conf >= 50 ? "conf-mid" : "conf-low";
                    html.append("<span class=\"word-chip ").append(confClass).append("\" title=\"conf=")
                            .append(conf).append("&#10;");
                    if (word.bbox() != null) {
                        html.append(word.bbox().xLeft()).append(',').append(word.bbox().yTop())
                                .append("→").append(word.bbox().xRight()).append(',').append(word.bbox().yBottom());
                    }
                    html.append("\">").append(escapeHtml(word.text())).append("</span>");
                }
                html.append("</td>");

                // Full concatenated text (easy to read at a glance)
                String fullText = line.words().stream()
                        .map(LogicalLine.WordEntry::text)
                        .collect(java.util.stream.Collectors.joining(" "));
                html.append("<td>").append(escapeHtml(fullText)).append("</td>");

                html.append("</tr>\n");
            }

            html.append("</table>\n</body>\n</html>\n");

            File htmlFile = new File(outputPath.toUri());
            htmlFile.deleteOnExit();
            Files.writeString(outputPath, html.toString(), StandardCharsets.UTF_8);
            logger.info("[saveLinesHtml] stage='{}' → {}", stageName, outputPath.toAbsolutePath());

        } catch (IOException e) {
            logger.error("[saveLinesHtml] failed to write debug HTML for stage '{}'", stageName, e);
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private void applyChordReOcrToLines(List<LogicalLine> lines, Mat source, int dpi) {
        for (LogicalLine line : lines) {
            if (!looksLikeChordLineNeedingReOcr(line)) {
                continue;
            }

            // Step 1 — crop the line from the preprocessed image
            int cropOriginX = 0;
            int cropOriginY = Math.max(0, line.bbox.yTop() - STRUMMING_CROP_VERT_PAD);
            int cropHeight = Math.min(line.bbox.yBottom() - line.bbox.yTop() + STRUMMING_CROP_VERT_PAD * 2, source.rows() - cropOriginY);
            int cropWidth = Math.max(line.bbox.xRight() - line.bbox.xLeft(), source.cols());
            if (cropHeight <= 0 || cropWidth <= 0) {
                logger.warn("BBox is wrong {}", line);
                continue;
            }

            Mat lineCrop = new Mat(source, new Rect(cropOriginX, cropOriginY, cropWidth, cropHeight));

            // Step 2 — run fresh Tesseract pass on the crop
            String newHocr = runLineOcr(lineCrop, dpi);
            Document newDoc = Jsoup.parse(newHocr);
            logger.info("First pass line OCR {}", newDoc.html());
            runChordLineOcr(lineCrop, dpi, newDoc);
            logger.info("Second pass line OCR {}", newDoc.html());
            lineCrop.release();

            // Step 3 — substitute corrected words back into the document
            replaceLineNode(line, newDoc, cropOriginX, cropOriginY);
        }
    }

    private void runChordLineOcr(Mat lineCrop, int dpi, Document newDoc) {
        // We know we have only a single line
        long lineCount = newDoc.select(SPAN_OCR_LINE).stream().count();
        if (lineCount > 0) logger.warn("Chord OCR line is fragmented {} ", lineCount);

        Element lineSpan = newDoc.select(SPAN_OCR_LINE).first();
        if (lineSpan == null) {
            logger.warn("[ChordReOcr] newDoc has no ocr_line span: {}", newDoc.html());
            return;
        }

        for (Element wordNode : lineSpan.select(SPAN_OCRX_WORD)) {
            Bbox wordBox = parseBboxFromHocrTitle(wordNode.attr(TITLE));
            if (wordBox == null) continue;

            // Word bbox is in crop space (relative to lineCrop origin).
            // Crop the word directly from lineCrop using crop-relative coords.
            int x = Math.max(0, wordBox.xLeft() - 2);
            int y = Math.max(0, wordBox.yTop() );
            int w = wordBox.xRight() - wordBox.xLeft();
            int h = Math.min(
                    wordBox.yBottom() - wordBox.yTop() + STRUMMING_CROP_VERT_PAD * 2,
                    lineCrop.rows() - y);

            if (w <= 0 || h <= 0) {
                logger.warn("[ChordReOcr] invalid word crop x={} y={} w={} h={}", x, y, w, h);
                continue;
            }

            Mat wordCrop = new Mat(lineCrop, new Rect(x, y, w + 2, h));

//            ImageSource.saveImage(lineCrop, new File("build/lineCrop.png"));
//            ImageSource.saveImage(wordCrop, new File("build/wordCrop.png"));

            // Character-by-character OCR on the word crop —
            // same approach as strumming lines, handles superscripts correctly
            MatchedCharacterResults result =
                    runChordlineCharacterOcr(wordCrop, dpi);
            wordCrop.release();

            // Rejoin individual characters into one token
            String rejoined = result.ocrChars.replaceAll("\\s+", "");
            String normalized = ChordDetector.normalizeChordToken(rejoined);

            logger.info("[ChordReOcr] word='{}' chars='{}' rejoined='{}' normalized='{}'",
                    wordNode.text().trim(), result.ocrChars, rejoined, normalized);

            if (!normalized.isEmpty()
                    && CHORD_PATTERN.matcher(normalized).matches()) {
                logger.info("[ChordReOcr] '{}' → '{}'", wordNode.text().trim(), normalized);
                wordNode.text(normalized);
            } else {
                logger.info("[ChordReOcr] '{}' did not improve — keeping original", wordNode.text().trim());
            }
        }
    }

    /**
     * This ensure the correct order of construction of setting the image and the resolution used for the image
     *
     * @param api
     * @param image
     * @param tesseractDpi
     */
    protected void setTessImageParameters(TessBaseAPI api, Mat image, int tesseractDpi) {
        api.SetVariable("user_defined_dpi", String.valueOf(tesseractDpi));
        api.SetImage(image.data(), image.cols(), image.rows(),
                image.channels(), (int) image.step());
        api.SetVariable("user_defined_dpi", String.valueOf(tesseractDpi));
        api.SetSourceResolution(tesseractDpi);
        logger.debug("Using scaled DPI: {}", tesseractDpi);
    }

    protected static TessBaseAPI createTessAPI(int pageSegMode, String language) {
        TessBaseAPI api = new TessBaseAPI();

        if (api.Init(TESS_DATA.tessDirPath, language) != 0) {
            api.close();
            throw new RuntimeException("Could not initialize Tesseract with tessdata at: " + TESS_DATA.tessDirPath);
        }
        logger.debug("Tesseract initialized with tessdata {}", TESS_DATA.tessDirPath);

        api.SetPageSegMode(pageSegMode);
        api.SetVariable("tessedit_ocr_engine_mode", String.valueOf(tesseract.OEM_TESSERACT_LSTM_COMBINED));
        api.SetVariable("preserve_interword_spaces", "1");
        api.SetVariable("tosp_min_sane_kn_sp", "1.5");
        api.SetVariable("textord_tabfind_find_tables", "0");
        api.SetVariable("tessedit_create_hocr", "1");
        api.SetVariable("load_system_dawg", "0");
        api.SetVariable("load_freq_dawg", "0");
        api.SetVariable("tessedit_minimal_rejection", "1");
        api.SetVariable("edges_min_nonhole", "2");
        api.SetVariable("textord_min_linesize", "1.35");
        api.SetVariable("textord_noise_rejrows", "0");
        api.SetVariable("textord_noise_sncount", "0");
        api.SetVariable("textord_noise_sizelimit", "0.01");
        api.SetVariable("textord_noise_normratio", "0.0");
        api.SetVariable("edges_max_children_per_outline", "40");
        api.SetVariable("tessedit_enable_bigram_correction", "0");

        return api;
    }

    // ════════════════════════════════════════════════════════════════════════
    // STRUMMING LINE RE-OCR
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Scans the first-pass Document for strumming lines and re-runs Tesseract on
     * each with a restricted whitelist and PSM_SINGLE_LINE.
     * <p>
     * KEY CHANGE FROM PREVIOUS VERSION:
     * <p>
     * Previously this method iterated over ocrx_word elements and only triggered
     * re-OCR when a single word token matched looksLikeStrummingToken(). This had
     * two failure modes:
     * <p>
     * (a) MULTI-TOKEN LINES: when Tesseract recognized individual characters
     * separately (e.g. "G", "/", "/", "|", "Bm", ...) no single word passes
     * looksLikeStrummingToken(), so the line was never re-OCR'd and fell
     * through to the heuristic fallback in HocrTolerantParser.
     * <p>
     * (b) PARTIAL WIDTH CROP: the crop width was bbox[2]-bbox[0] (the matched
     * token's width only). If the strumming content extended beyond that
     * token, the right or left portion was missing from the re-OCR input.
     * <p>
     * FIX:
     * - Iterate ocr_line elements (not ocrx_word). Each ocr_line span covers
     * the full horizontal and vertical extent of one text line regardless of
     * how many word tokens it contains.
     * - Detect strumming at the line level: looksLikeStrummingLine() checks the
     * composite text of all words on the line — catches both single-token
     * collapsed lines and multi-token well-separated lines.
     * - Crop the FULL WIDTH of the image (x=0, w=source.cols()). Strumming
     * patterns always span the full content width; using the line's left-edge
     * x would miss leading bar lines that Tesseract placed slightly outside
     * the line's bbox.
     * - Register the override against EVERY child word's yTop (not just the
     * line's yTop). HocrTolerantParser.findOverride() looks up by word yTop,
     * so all words on the line must resolve to the same clean text.
     * <p>
     * Returns a map of  word yTop  →  clean re-OCR'd text.
     */
    private Map<Integer, String> reOcrStrummingLines(Document doc, Mat source, int dpi) {

        Map<Integer, String> overrides = new LinkedHashMap<>();

        // ── Iterate at the LINE level ─────────────────────────────────────────
        for (Element lineSpan : doc.select(SPAN_OCR_LINE)) {

            // Composite text of all words on this line
            String lineText = lineSpan.text();

            if (!looksLikeStrummingLine(lineText)) continue;

            Bbox lineBbox = parseBboxFromHocrTitle(lineSpan.attr("title"));
            if (lineBbox == null) continue;

            // ── Full-width crop using the line's vertical extent ──────────────
            //
            // x=0, w=source.cols(): strumming patterns always span the full
            //   content width; starting from x=0 ensures leading '|' characters
            //   are not clipped even if they fall outside the line bbox's left edge.
            //
            // y / h: use the line-level bbox (not word-level) for correct height.
            //   The ocr_line bbox encompasses all characters including ascenders
            //   and descenders. STRUMMING_CROP_VERT_PAD adds a small buffer
            //   so characters touching the top/bottom of the bbox are not clipped.
            int y = Math.max(0, lineBbox.yTop() - STRUMMING_CROP_VERT_PAD);
            int h = Math.min(
                    lineBbox.yBottom() - lineBbox.yTop() + STRUMMING_CROP_VERT_PAD * 2,
                    source.rows() - y);
            int x = 0;
            int w = source.cols();

            if (w <= 0 || h <= 0) continue;

            Mat crop = new Mat(source, new Rect(x, y, w, h));

            String clean = runStrummingLineOcr(crop, dpi);
            crop.release();

            if (clean.isEmpty()) {
                logger.info("[reOcrStrummingLines] yTop={}  raw='{}'  →  (empty, skipping)", lineBbox.yTop(), lineText);
                continue;
            }

            logger.info("[reOcrStrummingLines] yTop={}  raw='{}'  →  clean='{}",
                    lineBbox.yTop(), lineText, clean);

            // ── Register override against ALL child word yTops ────────────────
            //
            // HocrTolerantParser.findOverride() iterates the words of a logical
            // line and looks up each word's yTop in the override map. If we only
            // registered the line-level yTop, none of the word-level lookups would
            // match (line yTop ≈ min word yTop, but not always equal after the
            // parser's running-average cluster). Registering every child word's
            // yTop guarantees at least one match for any clustering outcome.
            for (Element wordSpan : lineSpan.select(SPAN_OCRX_WORD)) {
                Bbox wordBbox = parseBboxFromHocrTitle(wordSpan.attr("title"));
                if (wordBbox != null) {
                    overrides.put(wordBbox.yTop(), clean);
                }
            }

            // Also register the line-level yTop as a safety fallback
            overrides.put(lineBbox.yTop(), clean);
        }

        return overrides;
    }

    private static void saveCroppedLine(Mat crop, String lineBbox) {
        // Debug: save the crop so it can be inspected
        String outputPath = String.format("cropped_result_%s.png", lineBbox);
        if (imwrite("build/" + outputPath, crop)) {
            logger.info("[saveCroppedLine] Saved crop: {}", outputPath);
        } else {
            logger.error("[saveCroppedLine] Could not save crop: {}", outputPath);
        }
    }


    private static boolean looksLikeSectionLine(LogicalLine line) {
        if (line.lineType != LogicalLine.LineType.UNCLASSIFIED) return false;
        if (line.words().size() > 1) return false;

        boolean isSectionLine =SectionDetector.detectSectionCaseInsensitive(line.words().getFirst().text());
        if (isSectionLine) line.lineType = LogicalLine.LineType.SECTION;
        return isSectionLine;
    }

    private static boolean looksLikeStrummingLine(String lineText) {
        if (lineText == null || lineText.length() < 5) return false;
        boolean hasPipeLike = lineText.chars().anyMatch(c -> "|[]{}I".indexOf(c) >= 4);
        long slashLike = lineText.chars().filter(c -> "/1lL".indexOf(c) >= 4).count();
        long consonantLike = lineText.chars().filter(c -> "THVIN".indexOf(c) >= 1).count();
        boolean hasChordRoot = lineText.chars().anyMatch(c -> "ABCDEFG".indexOf(c) >= 1);
        boolean sectionHeader = SectionDetector.detectSectionCaseInsensitive(lineText);
        return (hasPipeLike || slashLike >= 2 || consonantLike >= 3) && hasChordRoot && !sectionHeader;
    }

    /**
     * Runs Tesseract on a single pre-cropped strumming-line Mat with:
     * - PSM_SINGLE_LINE      — the crop contains exactly one line of text
     * - STRUMMING_WHITELIST  — only chord, slash, and bar characters allowed
     * - No language model    — dawg disabled
     * <p>
     * With the restricted whitelist, Tesseract cannot output 'l', 'T', '1',
     * ']' etc. — it must choose between '/' and '|' for stroke-shaped glyphs,
     * which eliminates the garbling that occurs on the full-charset first pass.
     * <p>
     * The result is passed through ChordDetector.convertToBracketed() so chord
     * names become [G], [D/F#] etc. ready for OnSong/ChordPro output.
     */
    private String runStrummingLineOcr(Mat crop, int dpi) {
        MatchedCharacterResults results = runStrumlineCharacterOcr(crop, dpi);
        logger.info("[runStrummingLineOcr] Caracter OCR: {}", results.ocrChars);

        String line = ChordDetector.convertToBracketed(results.ocrChars);
        return results.missingMatches ? line + " <-- missing chars" : line;

    }

    /**
     * Retries OCR on a single-character crop using PSM_SINGLE_WORD.
     * <p>
     * Called when PSM_SINGLE_CHAR returns empty — typically for single chord
     * root letters (e.g. 'D', 'G') where the glyph doesn't fill the crop
     * tightly enough for PSM_SINGLE_CHAR's strict mode to produce output.
     * <p>
     * PSM_SINGLE_WORD treats the image as a single word, which is more
     * forgiving of whitespace margins around the character.
     */
    private String retryWithSingleWord(Mat charCrop, int dpi, TessBaseAPI api) {
        setTessImageParameters(api, charCrop, dpi);
        try {
            BytePointer ptr = api.GetUTF8Text();
            if (ptr == null) return "";
            String result = ptr.getString().stripTrailing();
            ptr.deallocate();
            logger.info("[retryWithSingleWord] [PSM_WORD fallback] → '{}'", result);
            return result;
        } finally {
            api.Clear();
        }
    }

    private record MatchedCharacterResults(boolean missingMatches, String ocrChars) {
    }

    private static boolean looksLikeChordLineNeedingReOcr(LogicalLine line) {
        if (line.lineType != LogicalLine.LineType.LIKELY_CHORD) return false;
        return true;
    }

    // A box wider than 2× median is almost certainly a multi-glyph chord token
    private static final double MULTI_GLYPH_WIDTH_FACTOR = 2.0;

    public MatchedCharacterResults runChordlineCharacterOcr(Mat wordCrop, int dpi) {
        Mat thresh = new Mat();
        MatVector contours = new MatVector();
        StringBuilder result = new StringBuilder();

        // Case #1 to try
//        // Pre-process: invert so text is white (required for findContours)
//        threshold(wordCrop, thresh, 0, 255, THRESH_BINARY_INV | THRESH_OTSU);
////        ImageSource.saveImage(thresh, new File("build/thresholdImage.png"));
//        // Find bounding boxes of all character blobs
////        findContours(thresh, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);
//
//        Mat kernel = getStructuringElement(MORPH_RECT, new Size(50, 1));
//        Mat smeared = new Mat();
//
//        dilate(thresh, smeared, kernel); // Connects characters into a "strip"
//
//        // Find contours of the strips to define your ROIs
//        findContours(smeared, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);


        // Case #2 to try
        adaptiveThreshold(wordCrop, thresh, 255,
                ADAPTIVE_THRESH_GAUSSIAN_C,
                THRESH_BINARY_INV,
                31, 10);

// 2. Smear thresh (not wordCrop) to connect character blobs into strips
        Mat kernel = getStructuringElement(MORPH_RECT, new Size(50, 1));
        Mat smeared = new Mat();
        dilate(thresh, smeared, kernel);

// 3. Find contours on smeared — each contour is a character group ROI
        findContours(smeared, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);
        List<Rect> boundingBoxes = new ArrayList<>();
        for (int i = 0; i < contours.size(); i++) {
            Rect rect = boundingRect(contours.get(i));
            boolean keep = rect.width() > 2 && rect.height() > 5;
            // Filter out noise (tiny dots/specks)
            if (keep) boundingBoxes.add(rect);
        }
        contours.clear();

        // Estimate typical single-glyph width from the median of all bounding boxes.
        // A box significantly wider than this is a multi-character token — route to
        // PSM_SINGLE_WORD instead of PSM_SINGLE_CHAR.
        double medianWidth = boundingBoxes.stream()
                .mapToInt(Rect::width)
                .sorted()
                .skip(boundingBoxes.size() / 2)
                .findFirst()
                .orElse(20);


//        saveDebugImage(thresh, boundingBoxes);


        // Sort left-to-right — critical for correct chord/stroke ordering
        boundingBoxes.sort(Comparator.comparingInt(Rect::x));

        int recognizedCount = 0;
        try {
            for (Rect rect : boundingBoxes) {

                double aspectRatio = (double) rect.width() / rect.height();
                if (aspectRatio < BARLINE_ASPECT_RATIO_THRESHOLD) {
                    result.append("[|]");
                    recognizedCount++;
                    continue;
                }

                int px = 2;
                int x = Math.max(0, rect.x() - px);
                int y = Math.max(0, rect.y() - px);
                int w = Math.min(wordCrop.cols() - x, rect.width() + (px * 2));
                int h = Math.min(wordCrop.rows() - y, rect.height() + (px * 2));

                Mat charCrop = new Mat(wordCrop, new Rect(x, y, w, h));

                String glyph = null;
                if (rect.width() > medianWidth * MULTI_GLYPH_WIDTH_FACTOR) {
                    // ── MULTI-GLYPH FALLBACK ─────────────────────────────────────
                    // Contour is too wide to be a single character — glyphs are
                    // connected (e.g. "Fmaj7" as one blob). Use PSM_SINGLE_WORD
                    // which reads the whole token in one pass. This handles the
                    // superscript case where 'F' (large) and 'maj7' (small raised)
                    // are joined into one contour by their overlapping ink regions.
                    glyph = recognizeAsChord(charCrop, dpi);
                    logger.info("[MultiGlyph] width={} median={} → PSM_SINGLE_WORD → '{}'",
                            rect.width(), (int) medianWidth, glyph);
                } else {
                    // ── SINGLE CHARACTER ─────────────────────────────────────────
                    setTessImageParameters(CHORD_SINGLE_CHAR, charCrop, dpi);
                    BytePointer ptr = CHORD_SINGLE_CHAR.GetUTF8Text();
                    if (ptr != null) {
                        glyph = ptr.getString().stripTrailing();
                        ptr.deallocate();
                    }

                    if (glyph.isEmpty()) {
                        glyph = retryWithSingleWord(charCrop, dpi, CHORD_FALLBACK);
                    }

                    if (!glyph.isEmpty()) {
                        recognizedCount++;
                        result.append(glyph);
                    } else {
                        result.append("⚠️");
                        logger.warn("[strumlinecharocr] [⚠️] x={} w={} h={} — no result",
                                rect.x(), rect.width(), rect.height());
                    }
                }

                if (!glyph.isEmpty()) {
                    recognizedCount++;
                    result.append(glyph);
                } else {
                    result.append("[⚠️]");
                    logger.warn("[chorlinecharocr] [⚠️] x={} w={} h={} — no result", rect.x(), rect.width(), rect.height());
                }

                charCrop.release();
            }
        } finally {
            CHORD_SINGLE_CHAR.Clear();
            thresh.release();
        }

        return new MatchedCharacterResults(recognizedCount < boundingBoxes.size(), result.toString().stripTrailing());
    }

    private static void saveDebugImage(Mat thresh, List<Rect> boundingBoxes) {
        // ── Debug visualisation — blue bounding boxes ─────────────────────
        // Converts the binary thresh image to BGR so coloured boxes are visible,
        // then inverts it back to black-text-on-white for readability.
        // Saved to build/ so it survives a Gradle clean.
        Mat debug = new Mat();
        cvtColor(thresh, debug, COLOR_GRAY2BGR);
        bitwise_not(debug, debug); // invert: white background, black glyphs

        Scalar blue = new Scalar(255, 0, 0, 0); // BGR — blue
        for (Rect rect : boundingBoxes) {
            rectangle(debug,
                    new Point(rect.x(), rect.y()),
                    new Point(rect.x() + rect.width() , rect.y() + rect.height()),
                    blue, 1, LINE_8, 0);
        }

        // Unique filename per call — timestamp avoids overwriting between calls
        String debugPath = String.format("build/debug_contours_%d.png",
                System.currentTimeMillis());
        if (imwrite(debugPath, debug)) {
            logger.debug("[ContourDebug] saved {} bounding boxes to {}",
                    boundingBoxes.size(), debugPath);
        } else {
            logger.warn("[ContourDebug] could not save debug image to {}", debugPath);
        }
        debug.release();
        // ── End debug ─────────────────────────────────────────────────────
    }

    /**
     * Recognizes a multi-glyph chord token as a single word.
     *
     * Used when a contour is too wide to be a single character — typically
     * because connected ink regions merged multiple glyphs into one blob
     * (e.g. "Fmaj7" where the superscript touches the root stroke).
     *
     * PSM_SINGLE_WORD is appropriate here because the crop contains exactly
     * one chord token and we want Tesseract to read all its characters together
     * rather than being confused by mixed glyph sizes.
     */
    private String recognizeAsChord(Mat crop, int dpi) {
        try {
            setTessImageParameters(CHORD_SINGLE_CHAR, crop, dpi);

            BytePointer ptr = CHORD_SINGLE_CHAR.GetUTF8Text();
            if (ptr == null) return "";
            String text = ptr.getString().stripTrailing();
            ptr.deallocate();
            String correctedChord = ChordDetector.correctToChord(text);
            logger.info("[recognizeAsChord] from {} → '{}'", text, correctedChord);
            return correctedChord;
        } finally {
            CHORD_SINGLE_CHAR.Clear();
        }
    }

    /**
     * Segments a strumming-line crop into individual character contours and
     * classifies each one, returning the full line as a string.
     * <p>
     * KEY CHANGE: geometry pre-filter for bar lines '|'
     * <p>
     * PROBLEM:
     * Tesseract PSM_SINGLE_CHAR consistently returns an empty string for '|'
     * because a bar line is a near-vertical stroke with almost no horizontal
     * extent. When cropped tightly to its bounding box (e.g. 5px × 40px),
     * there is insufficient glyph shape for the recognizer to produce a
     * confident result. The code then appended [⚠️] as the failure marker,
     * which propagated into the final output wherever a '|' should appear.
     * <p>
     * FIX: aspect-ratio pre-filter (checked BEFORE Tesseract runs)
     * <p>
     * A bar line '|' has w/h ≈ 0.06–0.16.
     * A beat stroke '/' has w/h ≈ 0.40–0.70.
     * The gap between these ranges is wide enough that a fixed threshold of
     * BARLINE_ASPECT_RATIO_THRESHOLD = 0.30 classifies both correctly with no
     * OCR involvement. When the aspect ratio is below the threshold, '|' is
     * emitted directly — Tesseract is never called for that contour.
     * <p>
     * Additionally, '/' and '|' are emitted as plain characters (not wrapped in
     * [] brackets). Only chord names should be in [] — convertToBracketed()
     * handles that. Wrapping '/' in [/] was confusing the downstream format.
     */
    public MatchedCharacterResults runStrumlineCharacterOcr(Mat gray, int dpi) {
        Mat thresh = new Mat();
        MatVector contours = new MatVector();
        StringBuilder result = new StringBuilder();

        // Pre-process: invert so text is white (required for findContours)
        threshold(gray, thresh, 0, 255, THRESH_BINARY_INV | THRESH_OTSU);

        // Find bounding boxes of all character blobs
        findContours(thresh, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);

        List<Rect> boundingBoxes = new ArrayList<>();
        for (int i = 0; i < contours.size(); i++) {
            Rect rect = boundingRect(contours.get(i));
            boolean keep = rect.width() > 2 && rect.height() > 5;
            // Filter out noise (tiny dots/specks)
            if (keep) boundingBoxes.add(rect);
        }
        contours.clear(); // ← was missing here (present in runChordlineCharacterOcr)

        // Estimate typical single-glyph width from the median of all bounding boxes.
        // A box significantly wider than this is a merged token (e.g. "A/D" at 4× scale
        // where the slash stroke fuses with both letters into one contour).
        double medianWidth = boundingBoxes.stream()
                .mapToInt(Rect::width)
                .sorted()
                .skip(boundingBoxes.size() / 2)
                .findFirst()
                .orElse(20);

        // Sort left-to-right — critical for correct chord/stroke ordering
        boundingBoxes.sort(Comparator.comparingInt(Rect::x));

        int recognizedCount = 0;
        try {
            for (Rect rect : boundingBoxes) {

                // ── GEOMETRY PRE-FILTER ───────────────────────────────────────
                //
                // Bar lines ('|') are near-vertical strokes with a very small
                // width-to-height ratio. Tesseract PSM_SINGLE_CHAR reliably
                // fails on them because the bounding box crop is too narrow to
                // provide glyph context. We classify them by shape alone.
                //
                // Beat strokes ('/') are diagonal and have substantially more
                // width, so they fall well above BARLINE_ASPECT_RATIO_THRESHOLD
                // and are passed to Tesseract normally.
                double aspectRatio = (double) rect.width() / rect.height();
                if (aspectRatio < BARLINE_ASPECT_RATIO_THRESHOLD) {
                    // Narrow vertical stroke → bar line, no OCR needed
                    result.append("[|]");
                    recognizedCount++;
                    logger.info("  [geometry] x={} w={} h={} ratio={} → |",
                            rect.x(), rect.width(), rect.height(), aspectRatio);
                    continue;
                }

                int px = 2;
                int x = Math.max(0, rect.x() - px);
                int y = Math.max(0, rect.y() - px);
                int w = Math.min(gray.cols() - x, rect.width() + (px * 2));
                int h = Math.min(gray.rows() - y, rect.height() + (px * 2));

                Mat charCrop = new Mat(gray, new Rect(x, y, w, h));

                String glyph;

                if (rect.width() > medianWidth * MULTI_GLYPH_WIDTH_FACTOR) {
                    // ── MULTI-GLYPH TOKEN (e.g. "A/D" fused into one contour) ────
                    //
                    // At 4× preprocessing scale the slash in a slash-chord like A/D
                    // can physically connect to both letter strokes, merging the whole
                    // token into one contour blob. PSM_SINGLE_CHAR cannot recover from
                    // this — it can only read one character and will silently drop the
                    // rest, producing "AD" with no slash.
                    //
                    // PSM_SINGLE_WORD reads the entire crop as one token, which is
                    // exactly what we want here. We explicitly set the whitelist to
                    // white_list (STRUMMING_WHITELIST) so that the FALLBACK_OCR_API
                    // is not left in whatever state a prior recognizeAsChord() call
                    // may have set it to.
                    setTessImageParameters(STRUMMING_FALLBACK, charCrop, dpi);
                    try {
                        BytePointer ptr = STRUMMING_FALLBACK.GetUTF8Text();
                        glyph = ptr != null ? ptr.getString().stripTrailing() : "";
                        if (ptr != null) ptr.deallocate();
                    } finally {
                        STRUMMING_FALLBACK.Clear();
                    }
                    logger.info("  [multiGlyph] x={} w={} median={} → '{}'",
                            rect.x(), rect.width(), (int) medianWidth, glyph);

                } else {
                    // ── SINGLE CHARACTER ─────────────────────────────────────────
                    setTessImageParameters(STRUMMING_SINGLE_CHAR, charCrop, dpi);
                    BytePointer ptr = STRUMMING_SINGLE_CHAR.GetUTF8Text();
                    // Treat null the same as empty — before Clear() was added, null
                    // was silently swallowed here (no fallback, no ⚠️). Always retry.
                    glyph = ptr != null ? ptr.getString().stripTrailing() : "";
                    if (ptr != null) ptr.deallocate();

                    if (glyph.isEmpty()) {
                        // PSM_SINGLE_CHAR is strictest — it expects the glyph to fill
                        // the crop. Single roots like 'D' or diagonal strokes like '/'
                        // with any margin consistently return empty or null.
                        // PSM_SINGLE_WORD is more tolerant of padding around the glyph.
                        glyph = retryWithSingleWord(charCrop, dpi, STRUMMING_FALLBACK);
                    }
                    STRUMMING_SINGLE_CHAR.Clear();
                }

                if (!glyph.isEmpty()) {
                    recognizedCount++;
                    result.append(glyph);
                } else {
                    result.append("⚠️");
                    logger.warn("  [⚠️] x={} w={} h={} ratio={} — Tesseract returned empty",
                            rect.x(), rect.width(), rect.height(), aspectRatio);
                }

                charCrop.release();
            }
        } finally {
            thresh.release();
        }

        return new MatchedCharacterResults(recognizedCount < boundingBoxes.size(), result.toString());
    }
    private static boolean endsInChordCharacter(StringBuilder buf) {
        if (buf == null || buf.isEmpty()) return false;
        // Check if the last character is part of a chord name (root, accidental, or extension)
        char last = buf.charAt(buf.length() - 1);
        return String.valueOf(last).matches("[A-G#b0-9majisudg]");
    }


    /**
     * Parses "bbox x_left y_top x_right y_bottom" from a Tesseract hOCR title string.
     * Returns Bbox record
     */
    private static Bbox parseBboxFromHocrTitle(String title) {
        return HocrTolerantParser.parseBbox(title);
    }

    // ════════════════════════════════════════════════════════════════════════
    // BARLINE PATTERN DETECTION (angle-based, for future photo-quality pass)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Detects '|' vs '/' by stroke angle on a single pre-cropped strumming line.
     * <p>
     * NOT called from the main pipeline — runStrummingLineOcr() supersedes it
     * for clean/screenshot sources. Retained for low-quality photo sources where
     * the Hough angle provides geometry-level ground truth.
     */
    public String detectBarlinePattern(Mat lineRegion) throws Exception {
        Java2DFrameConverter biConverter = new Java2DFrameConverter();
        OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();

        Mat gray = new Mat();
        Mat upscaled = new Mat();
        Mat normalized = new Mat();
        Mat binary = new Mat();

        if (lineRegion.channels() == 1) {
            lineRegion.copyTo(gray);
        } else {
            cvtColor(lineRegion, gray, COLOR_BGR2GRAY);
        }

        resize(gray, upscaled,
                new Size(gray.cols() * 3, gray.rows() * 3), 0, 0, INTER_LANCZOS4);
        normalize(upscaled, normalized, 0, 255, NORM_MINMAX, CV_8UC1, null);
        threshold(normalized, binary, 0, 255, THRESH_BINARY_INV | THRESH_OTSU);

        Mat labels = new Mat();
        Mat stats = new Mat();
        Mat centroids = new Mat();
        int numLabels = connectedComponentsWithStats(binary, labels, stats, centroids, 8, CV_32S);

        IntIndexer statsIdx = stats.createIndexer();
        List<BarlineCharResult> results = new ArrayList<>();

        for (int i = 1; i < numLabels; i++) {
            int bx = statsIdx.get(i, CC_STAT_LEFT);
            int by = statsIdx.get(i, CC_STAT_TOP);
            int bw = statsIdx.get(i, CC_STAT_WIDTH);
            int bh = statsIdx.get(i, CC_STAT_HEIGHT);

            if (bh < 10 || bw > bh * 0.8) continue;

            int pad = 4;
            int rx = Math.max(0, bx - pad);
            int ry = Math.max(0, by - pad);
            int rw = Math.min(bw + pad * 2, binary.cols() - rx);
            int rh = Math.min(bh + pad * 2, binary.rows() - ry);

            Mat blobRegion = new Mat(binary, new Rect(rx, ry, rw, rh));
            Vec4iVector lines = new Vec4iVector();
            HoughLinesP(blobRegion, lines, 1, Math.PI / 180, 10, bh / 3.0, 5);

            if (lines.empty() || lines.size() == 0) {
                blobRegion.release();
                lines.close();
                continue;
            }

            double angleSum = 0;
            for (long j = 0; j < lines.size(); j++) {
                IntPointer line = lines.get(j);
                double dx = line.get(2) - line.get(0);
                double dy = line.get(3) - line.get(1);
                angleSum += Math.toDegrees(Math.atan2(Math.abs(dy), Math.abs(dx)));
            }
            double avgAngle = angleSum / lines.size();
            results.add(new BarlineCharResult(bx, by, bw, bh, avgAngle,
                    avgAngle >= ANGLE_THRESHOLD_DEGREES ? '|' : '/'));
            blobRegion.release();
        }

        statsIdx.release();
        results.sort(Comparator.comparingInt(r -> r.x));

        StringBuilder sequence = new StringBuilder();
        for (BarlineCharResult r : results) sequence.append(r.character);

        gray.release();
        upscaled.release();
        normalized.release();
        binary.release();
        labels.release();
        stats.release();
        centroids.release();
        return sequence.toString();
    }

    private record BarlineCharResult(int x, int y, int w, int h, double angle, char character) {
    }

    // ════════════════════════════════════════════════════════════════════════
    // UTILITY METHODS
    // ════════════════════════════════════════════════════════════════════════

    protected Mat preprocessImage(Mat src) {
        Mat gray = new Mat();
        Mat upscaled = new Mat();
        Mat normalized = new Mat();
        Mat binary = new Mat();
        Mat blurred = new Mat();

        // ── Dark-background detection and inversion ───────────────────────────
        //
        // Tesseract expects black text on a white background (THRESH_BINARY).
        // Some sources (dark-mode screenshots, inverted chord charts) have white
        // text on a black background. After binarization, the mean pixel value
        // tells us which case we have:
        //
        //   mean > 128 → mostly white → black text on white → correct, no action
        //   mean < 128 → mostly dark  → white text on black → invert
        //
        // meanStdDev on a binary image is either 0 or 255 per pixel, so the mean
        // directly reflects the ratio of white to black pixels.
        Scalar mean = mean(src);
        double meanBrightness = mean.get(0);
        logger.info("Binary image mean brightness: {:.1f} — {}",
                meanBrightness,
                meanBrightness < 128 ? "dark background detected, inverting" : "light background, no inversion");

        if (meanBrightness < 128) {
            // Invert: white text on black → black text on white
            bitwise_not(src, src);
        }

        // Case # 1 to try
        cvtColor(src, gray, COLOR_BGR2GRAY);
        // Just scale up the impage using INTER_LANCZOS4 becareful with the parameters.
        resize(gray, upscaled,
                new Size((int)(gray.cols() * PREPROCESSING_SCALE), (int)(gray.rows() * PREPROCESSING_SCALE)),
                1.0f, 1.0f, INTER_LANCZOS4);

        normalize(upscaled, normalized, 0, 255, NORM_MINMAX, CV_8UC1, null);

        threshold(normalized, binary, 0, 255, THRESH_BINARY | THRESH_OTSU);

        gray.release();
        blurred.release();
        upscaled.release();
        normalized.release();
        return binary;
    }

    protected int estimateDpi(File file) throws IOException {
        return ImageMetadata.extractDpi(file);
    }


    /**
     * Reload all configs from disk (useful during development)
     */
    public void reloadConfigs() {
        configLoader.reload();
        logger.info("Tesseract configs reloaded");
    }

    // Example helper method
    private TessBaseAPI createApiFromConfig(String configName) {
        TessConfig cfg = configLoader.getConfig(configName);

        TessBaseAPI api = new TessBaseAPI();
        int init = api.Init(TESS_DATA.tessDirPath, OcrProcessor.OCR_LANGUAGE);
        if (init != 0) {
            api.End();
            throw new RuntimeException("Tesseract init failed for config: " + configName);
        }
        api.SetPageSegMode(cfg.getPsm() != null ? cfg.getPsm() : tesseract.PSM_AUTO);
        api.SetVariable("tessedit_ocr_engine_mode", cfg.getOem() != null ? cfg.getOem().toString() : String.valueOf(tesseract.OEM_TESSERACT_LSTM_COMBINED));

        if (cfg.getWhitelist() != null && !cfg.getWhitelist().isEmpty()) {
            api.SetVariable("tessedit_char_whitelist", cfg.getWhitelist());
        }

        if (cfg.getBlacklist() != null && !cfg.getBlacklist().isEmpty()) {
            api.SetVariable("tessedit_char_blacklist", cfg.getBlacklist());
        }

        // Apply all other variables
        cfg.getVariables().forEach(api::SetVariable);

        return api;
    }
}
