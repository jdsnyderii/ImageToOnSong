package com.imagetoonsong.core;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.png.PngDirectory;

import java.io.File;

public class ImageMetadata {

    private static final int DEFAULT_DPI = 96;
    private static final int RETINA_DPI = 144;

    public static int extractDpi(File imageFile) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(imageFile);

            // 1. Try EXIF (Standard for JPEGs and many modern PNGs)
            ExifIFD0Directory exifDir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exifDir != null && exifDir.containsTag(ExifIFD0Directory.TAG_X_RESOLUTION)) {
                return exifDir.getInt(ExifIFD0Directory.TAG_X_RESOLUTION);
            }

            // 2. Try PNG pHYs Chunk (Standard for older/web PNGs)
            PngDirectory pngDir = metadata.getFirstDirectoryOfType(PngDirectory.class);
            if (pngDir != null) {
                // PNG stores in pixels per meter. We convert to inches.
                Integer ppm = pngDir.getInteger(PngDirectory.TAG_PIXELS_PER_UNIT_X);
                if (ppm != null && ppm > 0) {
                    return (int) Math.round(ppm * 0.0254);
                }
            }

            // 3. Heuristic for Mac Screenshots
            // If it's a PNG with no DPI metadata, check the filename or
            // assume 144 if it's high-res (Retina).
            if (imageFile.getName().toLowerCase().endsWith(".png")) {
                return estimateMacResolution(imageFile);
            }

        } catch (Exception e) {
            // Log error or fall through to default
        }
        return DEFAULT_DPI;
    }

    public static int estimateDpiFromDimensions(int width) {
        if (width < 250) {
            return DEFAULT_DPI;
        }
        return RETINA_DPI;
    }

    private static int estimateMacResolution(File file) {
        // High-level logic: If no metadata exists, we often check the
        // pixel dimensions via ImageIO. If width > 2500 on a small file,
        // it's likely a 144dpi Retina capture.
        return RETINA_DPI;
    }
}