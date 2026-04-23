package com.imagetoonsong.core;

import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritablePixelFormat;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.indexer.IntIndexer;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.JavaFXFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_imgproc.Vec4iVector;
import org.bytedeco.tesseract.ResultIterator;
import org.bytedeco.tesseract.TessBaseAPI;
import org.bytedeco.tesseract.global.tesseract;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.opencv.core.CvType;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.imagetoonsong.core.TessData.tessDirPath;
import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imwrite;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

public class OcrProcessor {

    public static final String PAGE_WHITELIST = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" +
            "0123456789#/()[]., '-{}|";
    public static final String ENG = "eng";

    public OcrProcessor() throws Exception {}

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
    public String extractText(ImageSource imageSource) throws Exception, UncheckedIOException {
        System.out.println("=== Starting Bytedeco Tesseract OCR ===");
        int tesseractDpi = imageSource.dpi();

        TessBaseAPI api = createTessAPI(tesseract.PSM_SPARSE_TEXT, ENG);
        api.SetVariable("tessedit_char_whitelist", PAGE_WHITELIST);


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
        System.out.println(html);
        outText.deallocate();

        // ── Re-OCR pass for strumming lines ──────────────────────────────────
        Document doc = Jsoup.parse(html);
        Map<Integer, String> strummingOverrides = reOcrStrummingLines(doc, padded, tesseractDpi);

        api.End();
        api.close();
        padded.release();
        mat.release();

        // ── Parse ────────────────────────────────────────────────────────────
        String result = HocrTolerantParser.parseHocrToString(doc, strummingOverrides);
        System.out.println("OCR completed successfully - " + result.length() + " characters returned");

        return result;
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
        System.out.printf("Using scaled DPI: %d%n", tesseractDpi);
    }

    protected TessBaseAPI createTessAPI(int pageSegMode, String language) {
        TessBaseAPI api = new TessBaseAPI();

        if (api.Init(tessDirPath, language) != 0) {
            api.close();
            throw new RuntimeException("Could not initialize Tesseract with tessdata at: " + tessDirPath);
        }
        System.out.printf("Tesseract initialized with tessdata %s\n", tessDirPath);

        api.SetPageSegMode(pageSegMode);
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
        for (Element lineSpan : doc.select("span.ocr_line")) {

            // Composite text of all words on this line
            String lineText = lineSpan.text();

            if (!looksLikeStrummingLine(lineText)) continue;

            int[] lineBbox = parseBboxFromHocrTitle(lineSpan.attr("title"));
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
            int y = Math.max(0, lineBbox[1] - STRUMMING_CROP_VERT_PAD);
            int h = Math.min(
                    lineBbox[3] - lineBbox[1] + STRUMMING_CROP_VERT_PAD * 2,
                    source.rows() - y);
            int x = 0;
            int w = source.cols();

            if (w <= 0 || h <= 0) continue;

            Mat crop = new Mat(source, new Rect(x, y, w, h));

            String clean = runStrummingLineOcr(crop, dpi);
            crop.release();

            if (clean.isEmpty()) {
                System.out.printf("[Re-OCR] yTop=%d  raw='%s'  →  (empty, skipping)%n",
                        lineBbox[1], lineText);
                continue;
            }

            System.out.printf("[Re-OCR] yTop=%d  raw='%s'  →  clean='%s'%n",
                    lineBbox[1], lineText, clean);

            // ── Register override against ALL child word yTops ────────────────
            //
            // HocrTolerantParser.findOverride() iterates the words of a logical
            // line and looks up each word's yTop in the override map. If we only
            // registered the line-level yTop, none of the word-level lookups would
            // match (line yTop ≈ min word yTop, but not always equal after the
            // parser's running-average cluster). Registering every child word's
            // yTop guarantees at least one match for any clustering outcome.
            for (Element wordSpan : lineSpan.select("span.ocrx_word")) {
                int[] wordBbox = parseBboxFromHocrTitle(wordSpan.attr("title"));
                if (wordBbox != null) {
                    overrides.put(wordBbox[1], clean);
                }
            }

            // Also register the line-level yTop as a safety fallback
            overrides.put(lineBbox[1], clean);
        }

        return overrides;
    }

    private static void saveCroppedLine(Mat crop, String lineBbox) {
        // Debug: save the crop so it can be inspected
        String outputPath = String.format("cropped_result_%s.png", lineBbox);
        if (imwrite("build/" + outputPath, crop)) {
            System.out.println("Saved crop: " + outputPath);
        } else {
            System.err.println("Could not save crop: " + outputPath);
        }
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
        boolean hasPipeLike  = lineText.chars().anyMatch(c -> "|[]{}".indexOf(c) >= 0);
        long    slashLike    = lineText.chars().filter(c -> "/1lL".indexOf(c) >= 0).count();
        long    consonantLike = lineText.chars().filter(c -> "THVIN".indexOf(c) >=0).count();
        boolean hasChordRoot = lineText.chars().anyMatch(c -> "ABCDEFG".indexOf(c) >= 0);
        boolean sectionHeader = SectionDetector.detectSectionCaseInsensitive(lineText);
        return (hasPipeLike || slashLike >= 2) && hasChordRoot && consonantLike >= 3 && !sectionHeader;
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
//        GaussianBlur(crop, crop, new Size(1, 3), 0);

        MatchedCharacterResults results = runIndividualCharacterOcr(crop, dpi);
        System.out.println("Caracter OCR: " + results.ocrChars);

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
        TessBaseAPI fallback = createTessAPI(tesseract.PSM_SINGLE_WORD, ENG);
        try {
            fallback.SetVariable("tessedit_char_whitelist",  STRUMMING_WHITELIST);
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

            System.out.printf("  [PSM_WORD fallback] → '%s'%n", result);
            return result;
        } finally {
            fallback.End();
            fallback.close();
        }
    }
    private record MatchedCharacterResults(boolean missingMatches, String ocrChars) {}

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
    public MatchedCharacterResults runIndividualCharacterOcr(Mat gray, int dpi) {
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

        TessBaseAPI api = createTessAPI(tesseract.PSM_SINGLE_CHAR, ENG);
        try {
            api.SetVariable("tessedit_char_whitelist",   STRUMMING_WHITELIST);
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
                    System.out.printf("  [geometry] x=%d w=%d h=%d ratio=%.2f → |%n",
                            rect.x(), rect.width(), rect.height(), aspectRatio);
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
                        if (glyph.equals("/")) glyph = "[/]";
                        if (glyph.equals("|")) glyph = "[|]";
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
                            System.out.printf("  [⚠️] x=%d w=%d h=%d ratio=%.2f — Tesseract returned empty%n",
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

    /**
     * Parses "bbox x_left y_top x_right y_bottom" from a Tesseract hOCR title string.
     * Returns int[4] = { xLeft, yTop, xRight, yBottom } or null on failure.
     */
    private static int[] parseBboxFromHocrTitle(String title) {
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
                new Size(gray.cols() * 2, gray.rows() * 2), 0, 0, INTER_LANCZOS4);
        normalize(upscaled, normalized, 0, 255, NORM_MINMAX, CV_8UC1, null);
        threshold(normalized, binary, 0, 255, THRESH_BINARY_INV | THRESH_OTSU);

        Mat labels    = new Mat();
        Mat stats     = new Mat();
        Mat centroids = new Mat();
        int numLabels = connectedComponentsWithStats(binary, labels, stats, centroids, 8, CV_32S);

        IntIndexer statsIdx = stats.createIndexer();
        List<BarlineCharResult> results = new ArrayList<>();

        for (int i = 1; i < numLabels; i++) {
            int bx = (int) statsIdx.get(i, CC_STAT_LEFT);
            int by = (int) statsIdx.get(i, CC_STAT_TOP);
            int bw = (int) statsIdx.get(i, CC_STAT_WIDTH);
            int bh = (int) statsIdx.get(i, CC_STAT_HEIGHT);

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
                new Size(gray.cols() * PREPROCESSING_SCALE, gray.rows() * PREPROCESSING_SCALE), .9f, .9f, INTER_LANCZOS4);
        normalize(upscaled, normalized, 0, 255, NORM_MINMAX, CV_8UC1, null);
        threshold(normalized, binary, 0, 255, THRESH_BINARY | THRESH_OTSU);

        gray.release(); upscaled.release(); normalized.release();
        return binary;
    }

    protected int estimateDpi(File file) throws IOException {
        return ImageMetadata.extractDpi(file);
    }
}
