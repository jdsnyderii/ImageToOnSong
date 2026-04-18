package com.imagetoonsong.utils;

import org.bytedeco.javacv.Java2DFrameUtils;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;

import java.awt.image.BufferedImage;

public class ImagePreprocessor {

    public static BufferedImage preprocess(BufferedImage original) {
        try {
            // Convert to OpenCV Mat
            Mat mat = Java2DFrameUtils.toMat(original);

            // Grayscale
            Mat gray = new Mat();
            opencv_imgproc.cvtColor(mat, gray, opencv_imgproc.COLOR_BGR2GRAY);

            // Increase contrast (CLAHE)
            Mat claheMat = new Mat();
            opencv_imgproc.createCLAHE(2.0, new Size(8, 8)).apply(gray, claheMat);

            // Adaptive Gaussian Thresholding (excellent for chord sheets)
            Mat binary = new Mat();
            opencv_imgproc.adaptiveThreshold(claheMat, binary, 255,
                    opencv_imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    opencv_imgproc.THRESH_BINARY, 21, 5);   // Increased block size + constant

            // Optional: Light deskew (basic rotation correction)
            // For advanced deskew, we can add projection profile later

            // Convert back to BufferedImage
            return Java2DFrameUtils.toBufferedImage(binary);

        } catch (Exception e) {
            System.err.println("Preprocessing failed, returning original: " + e.getMessage());
            return original; // Fallback
        }
    }
}