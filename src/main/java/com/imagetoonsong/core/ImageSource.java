package com.imagetoonsong.core;

import javafx.scene.image.*;
import javafx.scene.image.Image;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Canonical image container for the OCR pipeline.
 *
 * Holds the JavaFX Image as the source of truth — no pixel conversion
 * occurs at construction time. Conversion to BufferedImage (and onward
 * to Mat) is deferred to toBufferedImage(), called once inside
 * OcrProcessor.extractText() just before the Mat is needed.
 *
 * This eliminates the quality loss that occurred when BufferedImage was
 * produced eagerly at load time: any intermediate resampling, colour-space
 * conversion, or alpha compositing now happens exactly once, under
 * controlled conditions, at the last possible moment.
 *
 * Metadata carried alongside the image:
 *  - dpi:    used by Tesseract's internal size calculations. File sources
 *            read this from EXIF/pHYs chunks; clipboard sources estimate
 *            it from screen density.
 *  - source: human-readable label for logging / debug output.
 */
public record ImageSource(Image image, int dpi, String source) {

    // ── Factory methods ──────────────────────────────────────────────────────

    /**
     * Loads from a file on disk.
     *
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
     *
     * DPI is estimated from the primary screen output scale because clipboard
     * images carry no file metadata. On a Retina display this gives 144–192;
     * on a standard display 96.
     */
    public static ImageSource fromClipboard(Image fxImage) {
        Image rgbImage = ImageSource.convertToARGB(fxImage);
        int dpi = 144;
        return new ImageSource(rgbImage, dpi, "clipboard");
    }

    // ── Pixel dimensions (convenience, avoids casting at call sites) ─────────

    public int width()  { return (int) image.getWidth();  }
    public int height() { return (int) image.getHeight(); }

    // ── Deferred conversion ──────────────────────────────────────────────────

    /**
     * Converts the JavaFX Image to a TYPE_INT_RGB BufferedImage.
     */
    public BufferedImage toBufferedImage() {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();

        BufferedImage bImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();

        // Create an integer array to hold the pixel data
        int[] pixels = new int[width * height];

        // Read the pixels from the FX Image into the array
        reader.getPixels(0, 0, width, height, WritablePixelFormat.getIntArgbInstance(), pixels, 0, width);

        // Set the pixels into the BufferedImage
        bImage.setRGB(0, 0, width, height, pixels, 0, width);

        return bImage;
    }

    private static Image convertToARGB(Image clipboardImage) {
        int w = (int) clipboardImage.getWidth();
        int h = (int) clipboardImage.getHeight();
        int[] buffer = new int[w * h];

        // Read exactly what's on the clipboard (Standard ARGB)
        clipboardImage.getPixelReader().getPixels(0, 0, w, h,
                WritablePixelFormat.getIntArgbInstance(), buffer, 0, w);

        for (int i = 0; i < buffer.length; i++) {
            int argb = buffer[i];
            int a = (argb >> 24) & 0xFF;
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;

            // Flatten onto WHITE background
            // This math effectively "blends" the text color with white
            r = ((r * a) + (255 * (255 - a))) / 255;
            g = ((g * a) + (255 * (255 - a))) / 255;
            b = ((b * a) + (255 * (255 - a))) / 255;

            // Re-pack as fully OPAQUE
            buffer[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }

        WritableImage opaqueImage = new WritableImage(w, h);
        opaqueImage.getPixelWriter().setPixels(0, 0, w, h,
                WritablePixelFormat.getIntArgbInstance(), buffer, 0, w);

        return opaqueImage;
    }
}