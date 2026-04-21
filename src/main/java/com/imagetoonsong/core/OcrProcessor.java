package com.imagetoonsong.core;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_imgproc.Vec4iVector;
import org.bytedeco.tesseract.TessBaseAPI;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.tesseract.global.tesseract;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.indexer.IntIndexer;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import org.w3c.dom.NodeList;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;

public class OcrProcessor {

    // ── Angle threshold for '|' vs '/' classification ───────────────────────
    // Measured from horizontal: '|' ≈ 90°, '/' ≈ 60–75°
    // Raise this value if '/' strokes are being misclassified as '|'
    private static final double ANGLE_THRESHOLD_DEGREES = 83.0;

    // ════════════════════════════════════════════════════════════════════════
    // MAIN OCR PIPELINE — unchanged from original
    // ════════════════════════════════════════════════════════════════════════

    public String extractText(File imageFile) throws Exception, UncheckedIOException {
        System.out.println("=== Starting Bytedeco Tesseract OCR ===");
        System.out.println("Image: " + imageFile.getName());

        String tessDirPath = prepareTessData("eng");
        TessBaseAPI api = new TessBaseAPI();

        if (api.Init(tessDirPath, "eng") != 0) {
            api.close();
            throw new RuntimeException("Could not initialize Tesseract with tessdata at: " + tessDirPath);
        }
        api.SetPageSegMode(tesseract.PSM_SPARSE_TEXT);

        api.SetVariable("preserve_interword_spaces", "1");
        api.SetVariable("tosp_min_sane_kn_sp", "1.0");
        api.SetVariable("textord_tabfind_find_tables", "0");
        api.SetVariable("tessedit_create_hocr", "1");
        api.SetVariable("load_system_dawg", "0");
        api.SetVariable("load_freq_dawg", "0");
        api.SetVariable("tessedit_minimal_rejection", "1");
        api.SetVariable("user_defined_dpi", "300");
        api.SetVariable("edges_min_nonhole", "2");
        api.SetVariable("textord_min_linesize", "0.5");
        api.SetVariable("textord_noise_rejrows", "0");
        api.SetVariable("textord_noise_sncount", "1");
        api.SetVariable("textord_noise_rejrows", "0");
        api.SetVariable("textord_noise_sizelimit", "0.01");
        api.SetVariable("textord_noise_normratio", "0.0");
        api.SetVariable("edges_max_children_per_outline", "40");
        api.SetVariable("tessedit_char_whitelist",
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" +
                        "0123456789#/()[]., '-{}|");
        System.out.printf("Tesseract initialized with tessdata %s\n ", tessDirPath);

        BufferedImage bufferedImage = ImageIO.read(imageFile);
        int dpi = estimateDpi(bufferedImage);
        api.SetVariable("user_defined_dpi", String.valueOf(dpi));
        System.out.printf("Using DPI: %d%n", dpi);

        Java2DFrameConverter biConverter = new Java2DFrameConverter();
        OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();

        Mat mat = matConverter.convert(biConverter.convert(bufferedImage));
        Mat processed = preprocessImage(mat);

        Mat padded = new Mat();
        int paddingPx = processed.cols() / 3;
        int paddingSmall = 0;
        copyMakeBorder(processed, padded,
                paddingSmall, paddingSmall, paddingSmall, paddingPx,
                BORDER_CONSTANT,
                new Scalar(255, 255, 255, 255));
        processed.release();

        api.SetImage(padded.data(), padded.cols(), padded.rows(),
                padded.channels(), (int) padded.step());

        BytePointer outText = api.GetHOCRText(0);
        String html = outText.getString();
        System.out.println(html);
        String result = HocrTolerantParser.parseHocrToString(html);

        outText.deallocate();
        api.End();
        api.close();

        cleanupTessData(tessDirPath);
        System.out.println("OCR completed successfully - " + result.length() + " characters returned");
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // BARLINE PATTERN DETECTION — primary Mat-based entry point
    //
    // Changes from original:
    //  - Primary method now accepts a Mat directly instead of a File.
    //    This eliminates the disk round-trip (write crop → read back) when
    //    called from within the OCR pipeline where Mat data is already in memory.
    //  - The File-based overload is retained for backward compatibility and
    //    standalone use; it now delegates to the Mat-based method.
    //  - No logic changes — same Hough angle classification as before.
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Detects and distinguishes '|' (bar lines) from '/' (beat strokes) on a
     * single strumming-pattern line, e.g.:  | D / / / / / / |
     *
     * Accepts a Mat directly — caller should crop to a single line before
     * passing. The Mat may be colour or greyscale; conversion is handled internally.
     *
     * Strategy:
     *  1. Convert to greyscale if needed, then upscale 2× with Lanczos4.
     *  2. Binarise with Otsu's threshold (THRESH_BINARY_INV — strokes are white).
     *  3. connectedComponentsWithStats → one blob per character.
     *  4. HoughLinesP per blob → measure average stroke angle.
     *       '|'  ≈ 90° from horizontal  (near-vertical)
     *       '/'  ≈ 60–75° from horizontal (diagonal)
     *  5. Sort left-to-right and return the sequence as a String.
     *
     * @param lineRegion  Input Mat cropped to a single strumming-pattern line
     * @param outputFile  If non-null, an annotated debug PNG is written here
     *                    (green boxes for '|', blue for '/')
     * @return            Detected character sequence, e.g. "|////|"
     */
    public String detectBarlinePattern(Mat lineRegion, File outputFile) throws Exception {

        Java2DFrameConverter biConverter       = new Java2DFrameConverter();
        OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();

        // ── Greyscale conversion ──────────────────────────────────────────
        Mat gray       = new Mat();
        Mat upscaled   = new Mat();
        Mat normalized = new Mat();
        Mat binary     = new Mat();

        if (lineRegion.channels() == 1) {
            lineRegion.copyTo(gray);
        } else {
            cvtColor(lineRegion, gray, COLOR_BGR2GRAY);
        }

        // ── Upscale 2× with Lanczos4 ─────────────────────────────────────
        // 2× (not 3×) — we only need enough pixels to measure angle,
        // we are NOT feeding this into Tesseract.
        resize(gray, upscaled,
                new Size(gray.cols() * 2, gray.rows() * 2),
                0, 0,
                INTER_LANCZOS4);

        normalize(upscaled, normalized, 0, 255, NORM_MINMAX, CV_8UC1, null);

        // THRESH_BINARY_INV so strokes are white (foreground) on black
        threshold(normalized, binary, 0, 255, THRESH_BINARY_INV | THRESH_OTSU);

        // ── Connected components ──────────────────────────────────────────
        Mat labels    = new Mat();
        Mat stats     = new Mat();
        Mat centroids = new Mat();
        int numLabels = connectedComponentsWithStats(
                binary, labels, stats, centroids, 8, CV_32S);

        IntIndexer statsIdx = stats.createIndexer();
        List<BarlineCharResult> results = new ArrayList<>();

        for (int i = 1; i < numLabels; i++) {   // label 0 = background
            int bx = (int) statsIdx.get(i, CC_STAT_LEFT);
            int by = (int) statsIdx.get(i, CC_STAT_TOP);
            int bw = (int) statsIdx.get(i, CC_STAT_WIDTH);
            int bh = (int) statsIdx.get(i, CC_STAT_HEIGHT);

            // Skip noise blobs (too short) and wide blobs (letters, not strokes)
            if (bh < 10 || bw > bh * 0.8) continue;

            int pad = 4;
            int rx  = Math.max(0, bx - pad);
            int ry  = Math.max(0, by - pad);
            int rw  = Math.min(bw + pad * 2, binary.cols() - rx);
            int rh  = Math.min(bh + pad * 2, binary.rows() - ry);

            Mat blobRegion = new Mat(binary, new Rect(rx, ry, rw, rh));

            Vec4iVector lines = new Vec4iVector();
            HoughLinesP(
                    blobRegion, lines,
                    1,
                    Math.PI / 180,
                    10,
                    bh / 3.0,
                    5
            );

            if (lines.empty() || lines.size() == 0) {
                blobRegion.release();
                lines.close();
                continue;
            }

            double angleSum = 0;
            for (long j = 0; j < lines.size(); j++) {
                IntPointer line = lines.get(j);
                int x1 = line.get(0);
                int y1 = line.get(1);
                int x2 = line.get(2);
                int y2 = line.get(3);
                double dx = x2 - x1;
                double dy = y2 - y1;
                angleSum += Math.toDegrees(Math.atan2(Math.abs(dy), Math.abs(dx)));
            }
            double avgAngle = angleSum / lines.size();
            char detected = avgAngle >= ANGLE_THRESHOLD_DEGREES ? '|' : '/';
            results.add(new BarlineCharResult(bx, by, bw, bh, avgAngle, detected));

            blobRegion.release();
        }

        statsIdx.release();

        // ── Sort left-to-right and build output string ───────────────────
        results.sort(Comparator.comparingInt(r -> r.x));

        StringBuilder sequence = new StringBuilder();
        System.out.printf("%-6s %-6s %-10s %s%n", "X", "Y", "Angle(°)", "Char");
        System.out.println("─".repeat(32));
        for (BarlineCharResult r : results) {
            System.out.printf("%-6d %-6d %-10.1f %s%n", r.x, r.y, r.angle, r.character);
            sequence.append(r.character);
        }
        System.out.println("\nDetected sequence: " + sequence);

        // ── Optional annotated debug image ────────────────────────────────
        if (outputFile != null) {
            Mat annotated = new Mat();
            cvtColor(binary, annotated, COLOR_GRAY2BGR);

            for (BarlineCharResult r : results) {
                Scalar colour = r.character == '|'
                        ? new Scalar(0, 200, 0, 0)
                        : new Scalar(200, 100, 0, 0);
                rectangle(annotated,
                        new Point(r.x, r.y),
                        new Point(r.x + r.w, r.y + r.h),
                        colour, 2, LINE_8, 0);
                putText(annotated,
                        String.valueOf(r.character),
                        new Point(r.x, Math.max(0, r.y - 4)),
                        FONT_HERSHEY_SIMPLEX, 0.5, colour, 1, LINE_8, false);
            }

            BufferedImage outImage = biConverter.convert(matConverter.convert(annotated));
            ImageIO.write(outImage, "png", outputFile);
            System.out.println("Annotated debug image saved to: " + outputFile.getAbsolutePath());
            annotated.release();
        }

        // ── Release native memory ─────────────────────────────────────────
        gray.release();
        upscaled.release();
        normalized.release();
        binary.release();
        labels.release();
        stats.release();
        centroids.release();

        return sequence.toString();
    }

    /**
     * File-based overload — retained for backward compatibility and standalone use.
     * Loads the file, converts to Mat, and delegates to detectBarlinePattern(Mat, File).
     *
     * Prefer the Mat-based overload when calling from within the OCR pipeline
     * to avoid unnecessary disk I/O.
     */
    public String detectBarlinePattern(File imageFile, File outputFile) throws Exception {
        BufferedImage bufferedImage = ImageIO.read(imageFile);
        Java2DFrameConverter biConverter       = new Java2DFrameConverter();
        OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();
        Mat mat = matConverter.convert(biConverter.convert(bufferedImage));
        try {
            return detectBarlinePattern(mat, outputFile);
        } finally {
            mat.release();
        }
    }

    // ── Private data class ───────────────────────────────────────────────────────
    private record BarlineCharResult(int x, int y, int w, int h, double angle, char character) {}

    // ════════════════════════════════════════════════════════════════════════
    // EXISTING UTILITY METHODS — unchanged
    // ════════════════════════════════════════════════════════════════════════

    private static void cleanupTessData(String tessDirPath) throws UncheckedIOException, IOException {
        try (Stream<Path> stream = Files.walk(Path.of(tessDirPath))) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(OcrProcessor::deletePath);
        }
    }

    private static void deletePath(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    protected Mat preprocessImage(Mat src) {
        Mat gray       = new Mat();
        Mat upscaled   = new Mat();
        Mat binary     = new Mat();
        Mat normalized = new Mat();

        cvtColor(src, gray, COLOR_BGR2GRAY);

        resize(gray, upscaled,
                new Size(gray.cols() * 3, gray.rows() * 3),
                .9f, .9f,
                INTER_LANCZOS4);

        normalize(upscaled, normalized, 0, 255, NORM_MINMAX, CV_8UC1, null);

        threshold(normalized, binary, 0, 255, THRESH_BINARY | THRESH_OTSU);

        gray.release();
        upscaled.release();
        normalized.release();

        return binary;
    }

    protected int estimateDpi(BufferedImage originalImage) {
        int sourceDpi = originalImage.getWidth();

        try {
            ImageInputStream iis = ImageIO.createImageInputStream(originalImage);
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (readers.hasNext()) {
                ImageReader reader = readers.next();
                reader.setInput(iis);
                IIOMetadata metadata = reader.getImageMetadata(0);
                IIOMetadataNode root = (IIOMetadataNode)
                        metadata.getAsTree("javax_imageio_1.0");
                NodeList horiz = root.getElementsByTagName("HorizontalPixelSize");
                if (horiz.getLength() > 0) {
                    float mmPerPixel = Float.parseFloat(
                            ((IIOMetadataNode) horiz.item(0)).getAttribute("value"));
                    sourceDpi = Math.round(25.4f / mmPerPixel);
                }
            }
        } catch (Exception e) {
            sourceDpi = 96;
        }

        int scaleFactor = 3;
        return sourceDpi * scaleFactor;
    }

    protected String prepareTessData(String lang) throws IOException {
        Path tempDir = Files.createTempDirectory("tesseract_resources");

        final String dataDir    = "tessdata";
        final String dataSuffix = ".traineddata";

        Path tessDataFolder = tempDir.resolve(dataDir);
        Files.createDirectories(tessDataFolder);

        String resourceName = "/" + dataDir + "/" + lang + dataSuffix;
        Path targetFile     = tessDataFolder.resolve(lang + dataSuffix);
        System.out.println(targetFile.toString());

        try (InputStream is = getClass().getResourceAsStream(resourceName)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourceName +
                        ". Ensure it is in src/main/resources/" + dataDir);
            }
            Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }

        return tessDataFolder.toAbsolutePath().toString();
    }
}
