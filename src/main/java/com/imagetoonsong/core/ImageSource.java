package com.imagetoonsong.core;

import javafx.application.Platform;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.image.WritablePixelFormat;
import javafx.scene.transform.Transform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.imagetoonsong.core.ImageMetadata.estimateDpiFromDimensions;

/**
 * Canonical image container for the OCR pipeline.
 * <p>
 * Holds the JavaFX Image as the source of truth — no pixel conversion
 * occurs at construction time. Conversion to BufferedImage (and onward
 * to Mat) is deferred to toBufferedImage(), called once inside
 * OcrProcessor.extractText() just before the Mat is needed.
 * <p>
 * This eliminates the quality loss that occurred when BufferedImage was
 * produced eagerly at load time: any intermediate resampling, colour-space
 * conversion, or alpha compositing now happens exactly once, under
 * controlled conditions, at the last possible moment.
 * <p>
 * Metadata carried alongside the image:
 * - dpi:    used by Tesseract's internal size calculations. File sources
 * read this from EXIF/pHYs chunks; clipboard sources estimate
 * it from screen density.
 * - source: human-readable label for logging / debug output.
 */
public record ImageSource(Image image, int dpi, String source) {

    private static final Logger logger = LoggerFactory.getLogger(
            MethodHandles.lookup().lookupClass());
    // ── Factory methods ──────────────────────────────────────────────────────

    /**
     * Loads from a file on disk.
     * <p>
     * Uses ImageIO to read metadata (DPI) and then creates a JavaFX Image
     * from the same file URI so the JavaFX image pipeline handles decoding.
     * This preserves colour profiles and avoids double-decoding artefacts.
     */
    public static ImageSource fromFile(File file) throws IOException {
        // Read DPI from file metadata before handing off to JavaFX
        int dpi = ImageMetadata.extractDpi(file);

        // JavaFX Image from URI — preserves original colour depth / profile
        Image fxImage = new Image(file.toURI().toString());
        if (fxImage.isError()) {
            throw new IOException("JavaFX could not load image: " + file.getName(),
                    fxImage.getException());
        }

        return new ImageSource(fxImage, dpi, file.getName());
    }

    /**
     * Wraps a JavaFX Image from the clipboard (or any other in-memory source).
     * <p>
     * DPI is estimated from the primary screen output scale because clipboard
     * images carry no file metadata. On a Retina display this gives 144–192;
     * on a standard display 96.
     */
    public static ImageSource fromClipboard(Image fxImage) {
        Image normalized = flattenToCanvas(fxImage);
        int dpi = estimateDpiFromDimensions((int) normalized.getWidth());
        logger.info("[Clipboard] original={}x{}  normalized={}x{}  dpi={}",
                (int) fxImage.getWidth(), (int) fxImage.getHeight(),
                (int) normalized.getWidth(), (int) normalized.getHeight(), dpi);
        return new ImageSource(normalized, dpi, "clipboard");
    }

    /**
     * Flattens a JavaFX Image by rendering it onto a Canvas with a white
     * background and snapshotting the result into a WritableImage.
     * <p>
     * This normalizes clipboard images to match the quality of disk screenshots:
     * - Composites any transparency against white
     * - Resolves colour space differences through JavaFX's render pipeline
     * - Produces a clean sRGB WritableImage identical in character to a
     * PNG snapshot saved by macOS
     * <p>
     * Must be called on the JavaFX Application Thread.
     */
    private static Image flattenToCanvas(Image source) {
        if (Platform.isFxApplicationThread()) {
            return doFlatten(source);
        }

        // Called from background thread — marshal to FX thread and wait
        CompletableFuture<Image> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                future.complete(doFlatten(source));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("[Clipboard] Canvas flatten failed, using raw image: {}", e.getMessage());
            return source; // fallback to raw
        }
    }

    public void saveImage(File outputFile) {
        saveImage(image, outputFile);
    }

    private static void saveImage(Image fxImage, File outputFile) {
        BufferedImage bImage = toBufferedImage(fxImage);

        try {
            // ImageIO handles the low-level encoding and headers
            boolean success = ImageIO.write(bImage, "png", outputFile);

            if (success) {
                logger.info("Successfully wrote image to: {}", outputFile.getAbsolutePath());
            } else {
                // This happens if the SPI can't find a writer for "png"
                logger.error("Failed to find a writer for format: png");
            }
        } catch (IOException e) {
            logger.error("I/O error while saving image", e);
        }
    }

    private static Image doFlatten(Image source) {
        int w = (int) source.getWidth();
        int h = (int) source.getHeight();

        Canvas canvas = new Canvas(w, h);

        GraphicsContext gc = canvas.getGraphicsContext2D();
        // This is required because dual displays will cause anti-aliasing to be triggered otherwise -- which is bad
        gc.setImageSmoothing(false);
        gc.setFill(javafx.scene.paint.Color.WHITE);
        gc.fillRect(0, 0, w, h);
        gc.drawImage(source, 0, 0, w, h);

        WritableImage result = new WritableImage(w, h);
        SnapshotParameters params = new SnapshotParameters();
        params.setTransform(Transform.scale(1.0, 1.0));
        canvas.snapshot(params, result);
        saveImage(result, new File("build/afterFlattened.png"));
        return result;
    }

    // ── Deferred conversion ──────────────────────────────────────────────────

    /**
     * Converts the JavaFX Image to a TYPE_INT_RGB BufferedImage.
     */
    public BufferedImage toBufferedImage() {
        return toBufferedImage(image);
    }

    private static BufferedImage toBufferedImage(Image fxImage) {
        int width = (int) fxImage.getWidth();
        int height = (int) fxImage.getHeight();

        BufferedImage bImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = fxImage.getPixelReader();

        // Create an integer array to hold the pixel data
        int[] pixels = new int[width * height];

        // Read the pixels from the FX Image into the array
        reader.getPixels(0, 0, width, height, WritablePixelFormat.getIntArgbInstance(), pixels, 0, width);

        // Set the pixels into the BufferedImage
        bImage.setRGB(0, 0, width, height, pixels, 0, width);

        return bImage;
    }
}