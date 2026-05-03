package com.imagetoonsong.core;

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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.imagetoonsong.core.ChordDetector.CHORD_PATTERN;
import static com.imagetoonsong.core.HocrTolerantParser.normalizeChordToken;
import static com.imagetoonsong.core.HocrTolerantParser.Bbox;
import static com.imagetoonsong.core.TessData.tessDirPath;
import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imwrite;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

public class OcrProcessor {

    public static final String SPAN_OCRX_WORD = "span.ocrx_word";
    public static final String SPAN_OCR_LINE = "span.ocr_line";
    private static final Logger logger = LoggerFactory.getLogger(
            MethodHandles.lookup().lookupClass());
    public static final String PAGE_WHITELIST = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" +
            "0123456789#/()[]., '-{}|";
    public static final String OCR_LANGUAGE = "eng";
    public static final String TITLE = "title";

    private static TessBaseAPI tessBaseAPI =  new TessBaseAPI();

    public OcrProcessor() {}

    // ── Angle threshold for '|' vs '/' classification ───────────────────────
    private static final double ANGLE_THRESHOLD_DEGREES = 83.0;

    /**
     * Restricted character whitelist for strumming-line re-OCR.
     *
     * Contains exactly the characters that can appear in a strumming pattern:
     *   A–G          chord roots
     *   b            flat accidental (also 'b' in "dim", "add" etc.)
     *   #            sharp accidental
     *   m a j i u s d g M   quality suffix letters (min, maj, aug, dim, sus, add)
     *   0–9          numeric extensions (7, 9, sus2, sus4, add9…)
     *   /            beat stroke AND slash-chord separator (G/D)
     *   |            bar line
     *   (space)      token separator
     *
     * Deliberately excludes all other letters and punctuation so Tesseract
     * cannot misread a '/' as 'l', 'T', '1', or any other character.
     */
    private static final String STRUMMING_WHITELIST = " |ABCDEFGMbmajisudg#245679/";

    /**
     * Vertical padding (in preprocessed-image pixels) added above and below
     * a strumming-line crop before re-OCR.
     *
     * The preprocessed image is 3× upscaled, so 5px here ≈ 1-2px in the
     * original — enough to include ascenders/descenders without bleeding
     * into the adjacent line above or below.
     */
    private static final int STRUMMING_CROP_VERT_PAD = 5;

    /**
     * Aspect-ratio threshold for classifying a contour as a bar line '|'.
     *
     * A bar line is a near-vertical stroke with very little horizontal extent.
     * A beat stroke '/' is diagonal and has substantially more width.
     *
     *   '|' typical bounding box: width ≈ 3–8px,  height ≈ 30–50px → w/h ≈ 0.06–0.16
     *   '/' typical bounding box: width ≈ 15–25px, height ≈ 30–50px → w/h ≈ 0.40–0.70
     *
     * Any contour with w/h below this threshold is classified as '|' without
     * running Tesseract on it, which avoids the PSM_SINGLE_CHAR empty-result
     * failure that '|' consistently produces (too narrow for Tesseract's
     * character recognizer to produce a confident match).
     *
     * Set conservatively at 0.30 — well between the two ranges — so neither
     * '|' nor '/' is misclassified. Increase if thin slash fonts produce false
     * bar-line matches; decrease if wide bar-line fonts are missed.
     */
    private static final double BARLINE_ASPECT_RATIO_THRESHOLD = 0.30;

    private static final int PREPROCESSING_SCALE = 4;

    private static final String TESS_CHAR_WHITELIST = "tessedit_char_whitelist";
    // ════════════════════════════════════════════════════════════════════════
    // MAIN OCR PIPELINE
    // ════════════════════════════════════════════════════════════════════════

    public String extractText(File imageFile) throws Exception {
        return extractText(ImageSource.fromFile(imageFile));
    }

    /**
     * Full pipeline:
     *  1. First-pass Tesseract on whole page → hOCR.
     *  2. Scan hOCR at the LINE level for strumming patterns.
     *  3. For each found, crop the full-width row from the preprocessed Mat
     *     and re-run Tesseract with PSM_SINGLE_LINE + STRUMMING_WHITELIST.
     *  4. Pass hOCR + strumming overrides to HocrTolerantParser.
     */
    public String extractText(ImageSource imageSource) throws Exception {
        logger.info("=== Starting Bytedeco Tesseract OCR ===");
        int tesseractDpi = Math.round(imageSource.dpi());

        TessBaseAPI api = createTessAPI(tesseract.PSM_SPARSE_TEXT, OCR_LANGUAGE);
        api.SetVariable(TESS_CHAR_WHITELIST, PAGE_WHITELIST);

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

        setTessImageParameters(api, padded, tesseractDpi);

        // ── First pass ───────────────────────────────────────────────────────
        BytePointer outText = api.GetHOCRText(0);
        String html = outText.getString();
        logger.info(html);
        outText.deallocate();

        // ── Re-OCR pass for strumming lines ──────────────────────────────────
        Document doc = Jsoup.parse(html);

        // Mutate document in place — chord words get corrected text,
        // spatial coordinates unchanged, parser sees clean data
        applyChordReOcrToDocument(doc, padded, tesseractDpi);
        Map<Integer, String> strummingOverrides = reOcrStrummingLines(doc, padded, tesseractDpi);

        api.End();
        api.close();
        padded.release();
        mat.release();

        // ── Parse ────────────────────────────────────────────────────────────
        String result = HocrTolerantParser.parseHocrToString(doc, strummingOverrides);
        logger.info("OCR completed successfully - {} characters returned", result.length());

        return result;
    }

    /**
     * Translates ALL positional data in an hOCR title attribute from
     * crop-relative coordinates to document coordinates, while preserving
     * ALL non-positional metadata (x_wconf, x_size, x_descenders,
     * x_ascenders, baseline etc.) unchanged.
     *
     * hOCR title format (ocrx_word):
     *   "bbox x1 y1 x2 y2; x_wconf 53"
     *
     * hOCR title format (ocr_line):
     *   "bbox x1 y1 x2 y2; baseline 0 -8; x_size 53; x_descenders 8; x_ascenders 15"
     *
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
     *
     * Data transfer contract per word span:
     *   bbox        → translated to document coordinates       (from new OCR)
     *   x_wconf     → kept from new OCR pass                   (reflects new quality)
     *   x_size      → kept from original line span             (font metrics unchanged)
     *   x_descenders/x_ascenders → kept from original          (font metrics unchanged)
     *   text content → from new OCR                            (the corrected text)
     *
     * The ocr_line span's own title (bbox + baseline + x_size etc.) is
     * preserved from the original — the line geometry hasn't changed,
     * only the word content within it has been corrected.
     */
    private void replaceLineNode(Element originalLineSpan,
                                 Document newDoc,
                                 int cropOriginX,
                                 int cropOriginY) {

        Element  newLineSp  = newDoc.selectFirst(SPAN_OCR_LINE);
        if (newLineSp == null) {
            logger.warn("[replaceLineNode] no ocr_line found in new hOCR — skipping");
            return;
        }

        // Extract font metrics from the original line span so they can be
        // grafted onto new word spans that lack them.
        // These are physical measurements of the font on the image — they
        // don't change just because the text content was corrected.
        String originalTitle    = originalLineSpan.attr("title");
        String originalXSize    = extractTitleField(originalTitle, "x_size");
        String originalXDesc    = extractTitleField(originalTitle, "x_descenders");
        String originalXAsc     = extractTitleField(originalTitle, "x_ascenders");

        // Build replacement word spans with correctly translated bboxes
        List<Element> newWords = newLineSp.select(SPAN_OCRX_WORD);
        if (newWords.isEmpty()) {
            logger.warn("[replaceLineNode] new hOCR produced no ocrx_word spans — skipping");
            return;
        }

        for (Element newWord : newWords) {
            logger.info("Trying chord {}", newWord.text());
            Bbox newBox = parseBboxFromHocrTitle(newWord.attr("title"));
            logger.info("Coordinates before {}", newBox);
            Bbox translatedBox = translateBboxInTitle(newBox, cropOriginX, cropOriginY);

            newWord.attr("title", translatedBox.asAttr());
            logger.info("Coordinates after {}", translatedBox);
        }

        // Replace ONLY the word children — keep the line span's own title
        // (bbox, baseline, x_size, x_descenders, x_ascenders) from the
        // original since the line geometry is unchanged.
        originalLineSpan.empty();
        for (Element newWord : newWords) {
            originalLineSpan.appendChild(newWord);
        }

        logger.info("[replaceLineNode] replaced {} word(s) in line yTop={}",
                newWords.size(),
                parseBboxFromHocrTitle(originalTitle).yTop()); // yTop for logging
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
        int pageSegMode = tesseract.PSM_SINGLE_LINE;
        TessBaseAPI api = createTessAPI(pageSegMode, OCR_LANGUAGE);
        logger.info("Running runLineOcr tesseract with {}", pageSegMode);
        try {
            api.SetVariable("tessedit_char_whitelist", CHORD_LINE_WHITELIST);
            // Chord symbols are not real words — any dictionary or n-gram model
            // will actively hurt accuracy by pushing OCR toward real words.
            // e.g. "Fmaj7" gets corrected toward "family" or similar.
            api.SetVariable("load_system_dawg",         "0");
            api.SetVariable("load_freq_dawg",           "0");
            api.SetVariable("load_punc_dawg",           "0");
            api.SetVariable("load_number_dawg",         "0");
            api.SetVariable("load_unambig_dawg",        "0");
            api.SetVariable("load_bigram_dawg",         "0");
            api.SetVariable("tessedit_enable_doc_dict", "0");

            // Preserve spacing so chord tokens stay separate
            api.SetVariable("preserve_interword_spaces", "1");

            // Keep small glyphs alive — superscripts are sub-line-height
            api.SetVariable("textord_min_linesize",      "0.3");
            api.SetVariable("edges_min_nonhole",         "2");
            api.SetVariable("textord_noise_sizelimit",   "0.01");

            BytePointer ptr = api.GetHOCRText(0);  // hOCR not plain text
            if (ptr == null) return "";
            String hocr = ptr.getString();
            ptr.deallocate();
            return hocr;

        } finally {
            api.End();
            api.close();
        }
    }

    private void applyChordReOcrToDocument(Document doc, Mat source, int dpi) {

        for (Element lineSpan : doc.select(SPAN_OCR_LINE)) {
            if (!looksLikeChordLineNeedingReOcr(lineSpan)) continue;

            Bbox lineBbox = parseBboxFromHocrTitle(lineSpan.attr("title"));
            if (lineBbox == null) continue;

            // Step 1 — crop the line from the preprocessed image
            int cropOriginX = 0;
            int cropOriginY = Math.max(0, lineBbox.yTop() - STRUMMING_CROP_VERT_PAD);
            int cropHeight  = lineBbox.yBottom() - lineBbox.yTop() + STRUMMING_CROP_VERT_PAD * 2;
            int cropWidth = Math.max(lineBbox.xRight() - lineBbox.xLeft(), source.cols() - 1);
            if (cropHeight <= 0 || cropWidth <=0 ) {
                logger.warn("BBox is wrong {}", lineBbox );
                continue;
            }

            Mat lineCrop = new Mat(source, new Rect(cropOriginX, cropOriginY, cropWidth, cropHeight));

            // Step 2 — run fresh Tesseract pass on the crop
            String newHocr = runLineOcr(lineCrop, dpi);
            Document newDoc = Jsoup.parse(newHocr);
            logger.info("First pass line OCR {}", newDoc.html());
            runChordOcr(lineCrop, dpi, newDoc);
            logger.info("Second pass line OCR {}", newDoc.html());
            lineCrop.release();

            // Step 3 — substitute corrected words back into the document
            replaceLineNode(lineSpan, newDoc, cropOriginX, cropOriginY);
        }
    }

    private void runChordOcr(Mat crop, int dpi, Document newDoc) {
        TessBaseAPI api = createTessAPI(tesseract.PSM_SINGLE_CHAR, OCR_LANGUAGE);
        api.SetVariable(TESS_CHAR_WHITELIST, CHORD_LINE_WHITELIST);

        Element lineSpan = newDoc.select(SPAN_OCR_LINE).first();
        if (lineSpan == null) {
            logger.warn("newDoc is malformed {}", newDoc.html());
            return;
        }
        for (Element wordNode : lineSpan.select(SPAN_OCRX_WORD)) {
            Bbox wordBox = parseBboxFromHocrTitle(wordNode.attr(TITLE));
            // Step 1 — crop the line from the preprocessed image

            int cropOriginX = 0;
            int cropOriginY = Math.max(0, wordBox.yTop() - STRUMMING_CROP_VERT_PAD);
            int cropHeight  = Math.min(
                    wordBox.yBottom() - wordBox.yTop() + STRUMMING_CROP_VERT_PAD * 2,
                    crop.rows() - cropOriginY);
            int cropWidth = wordBox.xRight() - wordBox.xLeft();
            if (cropWidth <= 0 || cropHeight <= 0) continue;

            Mat  wordCrop = new Mat(crop, new Rect(cropOriginX, cropOriginY, cropWidth, cropHeight));
            setTessImageParameters(api, wordCrop, dpi);
            api.GetUTF8Text();
            BytePointer ptr =api.GetUTF8Text();
            if (ptr == null) continue;
            String chord = ptr.getString();
            wordNode.text(chord);
            ptr.deallocate();
            logger.info("Chord OCR found {}", chord);
        }
    }

    /**
     * This ensure the correct order of construction of setting the image and the resolution used for the image
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
        logger.info("Using scaled DPI: {}", tesseractDpi);
    }

    protected TessBaseAPI createTessAPI(int pageSegMode, String language) {
        TessBaseAPI api = new TessBaseAPI();

        if (api.Init(tessDirPath, language) != 0) {
            api.close();
            throw new RuntimeException("Could not initialize Tesseract with tessdata at: " + tessDirPath);
        }
        logger.info("Tesseract initialized with tessdata {}", tessDirPath);

        api.SetPageSegMode(pageSegMode);
        api.SetVariable("tessedit_ocr_engine_mode", String.valueOf(tesseract.OEM_LSTM_ONLY));
        api.SetVariable("preserve_interword_spaces", "1");
        api.SetVariable("tosp_min_sane_kn_sp", "1.5");
        api.SetVariable("textord_tabfind_find_tables", "0");
        api.SetVariable("tessedit_create_hocr", "1");
        api.SetVariable("load_system_dawg", "0");
        api.SetVariable("load_freq_dawg", "0");
        api.SetVariable("tessedit_minimal_rejection", "0");
        api.SetVariable("edges_min_nonhole", "2");
        api.SetVariable("textord_min_linesize", "0.3");
        api.SetVariable("textord_noise_rejrows", "0");
        api.SetVariable("textord_noise_sncount", "0");
        api.SetVariable("textord_noise_sizelimit", "0.01");
        api.SetVariable("textord_noise_normratio", "0.0");
        api.SetVariable("edges_max_children_per_outline", "40");

        return api;
    }

    // ════════════════════════════════════════════════════════════════════════
    // STRUMMING LINE RE-OCR
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Scans the first-pass Document for strumming lines and re-runs Tesseract on
     * each with a restricted whitelist and PSM_SINGLE_LINE.
     *
     * KEY CHANGE FROM PREVIOUS VERSION:
     *
     * Previously this method iterated over ocrx_word elements and only triggered
     * re-OCR when a single word token matched looksLikeStrummingToken(). This had
     * two failure modes:
     *
     *  (a) MULTI-TOKEN LINES: when Tesseract recognized individual characters
     *      separately (e.g. "G", "/", "/", "|", "Bm", ...) no single word passes
     *      looksLikeStrummingToken(), so the line was never re-OCR'd and fell
     *      through to the heuristic fallback in HocrTolerantParser.
     *
     *  (b) PARTIAL WIDTH CROP: the crop width was bbox[2]-bbox[0] (the matched
     *      token's width only). If the strumming content extended beyond that
     *      token, the right or left portion was missing from the re-OCR input.
     *
     * FIX:
     *  - Iterate ocr_line elements (not ocrx_word). Each ocr_line span covers
     *    the full horizontal and vertical extent of one text line regardless of
     *    how many word tokens it contains.
     *  - Detect strumming at the line level: looksLikeStrummingLine() checks the
     *    composite text of all words on the line — catches both single-token
     *    collapsed lines and multi-token well-separated lines.
     *  - Crop the FULL WIDTH of the image (x=0, w=source.cols()). Strumming
     *    patterns always span the full content width; using the line's left-edge
     *    x would miss leading bar lines that Tesseract placed slightly outside
     *    the line's bbox.
     *  - Register the override against EVERY child word's yTop (not just the
     *    line's yTop). HocrTolerantParser.findOverride() looks up by word yTop,
     *    so all words on the line must resolve to the same clean text.
     *
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
                logger.info("[Re-OCR] yTop={}  raw='{}'  →  (empty, skipping)", lineBbox.yTop(), lineText);
                continue;
            }

            logger.info("[Re-OCR] yTop={}  raw='{}'  →  clean='{}",
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
            logger.info("Saved crop: {}", outputPath);
        } else {
            logger.error("Could not save crop: {}", outputPath);
        }
    }

    private static boolean looksLikeSectionLine(String text) {
        return SectionDetector.detectSectionCaseInsensitive(text);
    }

    /**
     * Returns true if the composite text of an ocr_line looks like a strumming
     * / bar pattern.
     *
     * Covers both cases:
     *
     *  SINGLE-TOKEN COLLAPSED (Tesseract merged the line into one blob):
     *    e.g. "|G//1/1|DIF#/1111]]"
     *    → has pipe-like chars + slash-like chars + chord root → true
     *
     *  MULTI-TOKEN WELL-SEPARATED (Tesseract recognized characters individually):
     *    e.g. "G / / / / / | Bm / / / / / |"   (from separate ocrx_word tokens)
     *    → composite text also has pipe + slashes + chord root → true
     *
     * Three signals must all be present:
     *  - At least one pipe-like character:  | [ ] { }
     *  - At least three slash-like chars:   / 1 l L
     *  - At least one uppercase chord-root: A B C D E F G
     *  - Composite text length ≥ 5
     *
     * This is intentionally identical to the old looksLikeStrummingToken() but
     * applied to the full composite line text instead of a single word token.
     */
    private static boolean looksLikeStrummingLine(String lineText) {
        if (lineText == null || lineText.length() < 5) return false;
        boolean hasPipeLike  = lineText.chars().anyMatch(c -> "|[]{}I".indexOf(c) >= 0);
        long    slashLike    = lineText.chars().filter(c -> "/1lL".indexOf(c) >= 0).count();
        long    consonantLike = lineText.chars().filter(c -> "THVIN".indexOf(c) >=0).count();
        boolean hasChordRoot = lineText.chars().anyMatch(c -> "ABCDEFG".indexOf(c) >= 0);
        boolean sectionHeader = SectionDetector.detectSectionCaseInsensitive(lineText);
        return (hasPipeLike || slashLike >= 2 || consonantLike >= 3) && hasChordRoot && !sectionHeader;
    }

    /**
     * Runs Tesseract on a single pre-cropped strumming-line Mat with:
     *  - PSM_SINGLE_LINE      — the crop contains exactly one line of text
     *  - STRUMMING_WHITELIST  — only chord, slash, and bar characters allowed
     *  - No language model    — dawg disabled
     *
     * With the restricted whitelist, Tesseract cannot output 'l', 'T', '1',
     * ']' etc. — it must choose between '/' and '|' for stroke-shaped glyphs,
     * which eliminates the garbling that occurs on the full-charset first pass.
     *
     * The result is passed through ChordDetector.convertToBracketed() so chord
     * names become [G], [D/F#] etc. ready for OnSong/ChordPro output.
     */
    private String runStrummingLineOcr(Mat crop, int dpi) {
        MatchedCharacterResults results = runIndividualCharacterOcr(crop, dpi, STRUMMING_WHITELIST);
        logger.info("Caracter OCR: {}", results.ocrChars);

        ChordDetector detector = new ChordDetector();
        String bracketedChords = detector.convertToBracketed(results.ocrChars);
        if (results.missingMatches) {
            return bracketedChords + " <-- missing characters";
        } else {
            return bracketedChords;
        }
    }

    /**
     * Retries OCR on a single-character crop using PSM_SINGLE_WORD.
     *
     * Called when PSM_SINGLE_CHAR returns empty — typically for single chord
     * root letters (e.g. 'D', 'G') where the glyph doesn't fill the crop
     * tightly enough for PSM_SINGLE_CHAR's strict mode to produce output.
     *
     * PSM_SINGLE_WORD treats the image as a single word, which is more
     * forgiving of whitespace margins around the character.
     */
    private String retryWithSingleWord(Mat charCrop, int dpi) {
        TessBaseAPI fallback = createTessAPI(tesseract.PSM_SINGLE_WORD, OCR_LANGUAGE);
        try {
            fallback.SetVariable(TESS_CHAR_WHITELIST,  STRUMMING_WHITELIST);
            fallback.SetVariable("load_punc_dawg",           "0");
            fallback.SetVariable("load_number_dawg",         "0");
            fallback.SetVariable("load_unambig_dawg",        "0");
            fallback.SetVariable("load_bigram_dawg",         "0");
            fallback.SetVariable("tessedit_enable_doc_dict", "0");
            fallback.SetVariable("user_defined_dpi",         String.valueOf(dpi));
            fallback.SetImage(charCrop.data(), charCrop.cols(), charCrop.rows(),
                    charCrop.channels(), (int) charCrop.step());

            BytePointer ptr = fallback.GetUTF8Text();
            if (ptr == null) return "";
            String result = ptr.getString().stripTrailing();
            ptr.deallocate();

            logger.info("  [PSM_WORD fallback] → '{}'", result);
            return result;
        } finally {
            fallback.End();
            fallback.close();
        }
    }
    private record MatchedCharacterResults(boolean missingMatches, String ocrChars) {}

    /**
     * Chord-line whitelist — everything valid in a chord symbol.
     * Wider than STRUMMING_WHITELIST since chord lines have richer notation:
     * qualities (maj, min, sus, dim, aug, add), extensions (7,9,11,13),
     * slash chords (/), accidentals (#, b), superscript Unicode chars.
     *
     * Excludes prose letters (full words) so Tesseract can't produce lyric text.
     */
    private static final String CHORD_LINE_WHITELIST =
            "ABCDEFGMbmajisudgntø°△Δ#²³⁴⁵⁶⁷⁸⁹ˢᵘᵐᵃʲᵒᵈⁱⁿᵗᵍ⁺⁻/() 0123456789";


    private static boolean looksLikeChordLineNeedingReOcr(Element lineSpan) {
        List<Element> words = lineSpan.select(SPAN_OCRX_WORD);
        if (words.isEmpty()) return false;

        boolean hasLowConfidence  = false;
        boolean hasValidChord     = false;
        boolean shouldSkip      = false;

        for (Element word : words) {
            String text = word.text().trim();
            if (text.isEmpty()) continue;

            // Lyric guard — any real prose word disqualifies the line
            if (text.matches(".*[a-z]{4,}.*")) {
                shouldSkip = true;
                break;
            }

            // Token too long to be a chord symbol
            if (looksLikeStrummingLine(text) || looksLikeSectionLine(text)) {
                shouldSkip = true;
                break;
            }

            // Check confidence
            Bbox bbox = parseBboxFromHocrTitle(word.attr("title"));
            if (bbox != null && bbox.confidence() < 70) {
                hasLowConfidence = true;
            }

            // Check for valid chord
            String normalized = normalizeChordToken(text);
            if (CHORD_PATTERN.matcher(normalized).matches()) {
                hasValidChord = true;
            }
        }

        if (shouldSkip) return false;
        return hasLowConfidence;
    }

    /**
     * Segments a strumming-line crop into individual character contours and
     * classifies each one, returning the full line as a string.
     *
     * KEY CHANGE: geometry pre-filter for bar lines '|'
     *
     * PROBLEM:
     *   Tesseract PSM_SINGLE_CHAR consistently returns an empty string for '|'
     *   because a bar line is a near-vertical stroke with almost no horizontal
     *   extent. When cropped tightly to its bounding box (e.g. 5px × 40px),
     *   there is insufficient glyph shape for the recognizer to produce a
     *   confident result. The code then appended [⚠️] as the failure marker,
     *   which propagated into the final output wherever a '|' should appear.
     *
     * FIX: aspect-ratio pre-filter (checked BEFORE Tesseract runs)
     *
     *   A bar line '|' has w/h ≈ 0.06–0.16.
     *   A beat stroke '/' has w/h ≈ 0.40–0.70.
     *   The gap between these ranges is wide enough that a fixed threshold of
     *   BARLINE_ASPECT_RATIO_THRESHOLD = 0.30 classifies both correctly with no
     *   OCR involvement. When the aspect ratio is below the threshold, '|' is
     *   emitted directly — Tesseract is never called for that contour.
     *
     * Additionally, '/' and '|' are emitted as plain characters (not wrapped in
     * [] brackets). Only chord names should be in [] — convertToBracketed()
     * handles that. Wrapping '/' in [/] was confusing the downstream format.
     */
    public MatchedCharacterResults runIndividualCharacterOcr(Mat gray, int dpi, String white_list) {
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

        // Sort left-to-right — critical for correct chord/stroke ordering
        boundingBoxes.sort(Comparator.comparingInt(Rect::x));

        int recognizedCount = 0;

        TessBaseAPI api = createTessAPI(tesseract.PSM_SINGLE_CHAR, OCR_LANGUAGE);
        try {
            api.SetVariable(TESS_CHAR_WHITELIST,   white_list);
            api.SetVariable("load_punc_dawg",            "0");
            api.SetVariable("load_number_dawg",          "0");
            api.SetVariable("load_unambig_dawg",         "0");
            api.SetVariable("load_bigram_dawg",          "0");
            api.SetVariable("tessedit_enable_doc_dict",  "0");
            api.SetVariable("tessedit_minimal_rejection","0");

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
                    logger.info("  [geometry] x={} w={} h={} ratio={} → |", rect.x(), rect.width(), rect.height(), aspectRatio);
                    continue;
                }

                // ── TESSERACT CHARACTER OCR ───────────────────────────────────
                int px = 2;
                int x  = Math.max(0, rect.x() - px);
                int y  = Math.max(0, rect.y() - px);
                int w  = Math.min(gray.cols() - x, rect.width()  + (px * 2));
                int h  = Math.min(gray.rows() - y, rect.height() + (px * 2));

                Mat charCrop = new Mat(gray, new Rect(x, y, w, h));
                setTessImageParameters(api, charCrop, dpi);


                BytePointer ptr = api.GetUTF8Text();
                if (ptr != null) {
                    String glyph = ptr.getString().stripTrailing();
                    if (!glyph.isEmpty()) {
                        recognizedCount++;
                        if (glyph.equals("/") && !endsInChordCharacter(result)) glyph = "[/]";
                        if (glyph.equals("|") && !endsInChordCharacter(result)) glyph = "[|]";
                        result.append(glyph);
                    } else {
                        // ── FALLBACK: retry with PSM_SINGLE_WORD ─────────────────────
                        // PSM_SINGLE_CHAR is the strictest mode and expects the glyph
                        // to fill the entire image. Single chord roots like 'D' with any
                        // whitespace margin or bold-font edge artifacts consistently return
                        // empty. PSM_SINGLE_WORD is more tolerant of padding.
                        glyph = retryWithSingleWord(charCrop, dpi);
                        if (!glyph.isEmpty()) {
                            recognizedCount++;
                            result.append(glyph);
                        } else {
                            // Tesseract returned nothing — emit warning marker so
                            // the caller knows the output is incomplete.
                            result.append("[⚠️]");
                            logger.info("  [⚠️] x={} w={} h={} ratio={} — Tesseract returned empty",
                                    rect.x(), rect.width(), rect.height(), aspectRatio);
                        }
                    }
                    ptr.deallocate();
                }
                charCrop.release();
            }
        } finally {
            thresh.release();
            api.End();
            api.close();
        }

        return new MatchedCharacterResults(recognizedCount < boundingBoxes.size(), result.toString().stripTrailing());
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
     *
     * NOT called from the main pipeline — runStrummingLineOcr() supersedes it
     * for clean/screenshot sources. Retained for low-quality photo sources where
     * the Hough angle provides geometry-level ground truth.
     */
    public String detectBarlinePattern(Mat lineRegion) throws Exception {
        Java2DFrameConverter biConverter       = new Java2DFrameConverter();
        OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();

        Mat gray       = new Mat();
        Mat upscaled   = new Mat();
        Mat normalized = new Mat();
        Mat binary     = new Mat();

        if (lineRegion.channels() == 1) {
            lineRegion.copyTo(gray);
        } else {
            cvtColor(lineRegion, gray, COLOR_BGR2GRAY);
        }

        resize(gray, upscaled,
                new Size(gray.cols() * 3, gray.rows() * 3), 0, 0, INTER_LANCZOS4);
        normalize(upscaled, normalized, 0, 255, NORM_MINMAX, CV_8UC1, null);
        threshold(normalized, binary, 0, 255, THRESH_BINARY_INV | THRESH_OTSU);

        Mat labels    = new Mat();
        Mat stats     = new Mat();
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
            int rx  = Math.max(0, bx - pad);
            int ry  = Math.max(0, by - pad);
            int rw  = Math.min(bw + pad * 2, binary.cols() - rx);
            int rh  = Math.min(bh + pad * 2, binary.rows() - ry);

            Mat blobRegion = new Mat(binary, new Rect(rx, ry, rw, rh));
            Vec4iVector lines = new Vec4iVector();
            HoughLinesP(blobRegion, lines, 1, Math.PI / 180, 10, bh / 3.0, 5);

            if (lines.empty() || lines.size() == 0) {
                blobRegion.release(); lines.close(); continue;
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

        gray.release(); upscaled.release(); normalized.release();
        binary.release(); labels.release(); stats.release(); centroids.release();
        return sequence.toString();
    }

    private record BarlineCharResult(int x, int y, int w, int h, double angle, char character) {}

    // ════════════════════════════════════════════════════════════════════════
    // UTILITY METHODS
    // ════════════════════════════════════════════════════════════════════════

    protected Mat preprocessImage(Mat src) {
        Mat gray       = new Mat();
        Mat upscaled   = new Mat();
        Mat binary     = new Mat();
        Mat normalized = new Mat();

        cvtColor(src, gray, COLOR_BGR2GRAY);
        resize(gray, upscaled,
                new Size(gray.cols() * PREPROCESSING_SCALE, gray.rows() * PREPROCESSING_SCALE),
                .9f, .9f, INTER_LANCZOS4);
        normalize(upscaled, normalized, 0, 255, NORM_MINMAX, CV_8UC1, null);
        threshold(normalized, binary, 0, 255, THRESH_BINARY | THRESH_OTSU);

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
        Scalar mean = mean(binary);
        double meanBrightness = mean.get(0);
        logger.info("Binary image mean brightness: {:.1f} — {}",
            meanBrightness,
            meanBrightness < 128 ? "dark background detected, inverting" : "light background, no inversion");

        if (meanBrightness < 128) {
            // Invert: white text on black → black text on white
            bitwise_not(binary, binary);
        }

        gray.release(); upscaled.release(); normalized.release();
        return binary;
    }

    protected int estimateDpi(File file) throws IOException {
        return ImageMetadata.extractDpi(file);
    }
}
