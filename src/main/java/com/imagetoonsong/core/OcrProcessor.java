package com.imagetoonsong.core;

import com.imagetoonsong.MainApp;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.tesseract.TessBaseAPI;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.tesseract.global.tesseract;

import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

import java.io.InputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

public class OcrProcessor {

    public String extractText(File imageFile) throws Exception, UncheckedIOException {
        System.out.println("=== Starting By t edeco Tesseract OCR ===");
        System.out.println("Image: " + imageFile.getName());


        String tessDirPath = prepareTessData("eng");
        TessBaseAPI api = new TessBaseAPI();

        // Initialize Tesseract
        if (api.Init(tessDirPath, "eng") != 0) {
            api.close();
            throw new RuntimeException("Could not initialize Tesseract with tessdata at: " + tessDirPath);
        }
        api.SetPageSegMode(tesseract.PSM_SPARSE_TEXT); // preserves spatial layout

        // 1. Enable space preservation
        api.SetVariable("preserve_interword_spaces", "1"); // already have this ✓
        api.SetVariable("tosp_min_sane_kn_sp", "1.0");    // tighter space threshold
        api.SetVariable("textord_tabfind_find_tables", "0"); // don't reorganize layout
        api.SetVariable("tessedit_create_hocr", "1");
        api.SetVariable("load_system_dawg", "0");
        api.SetVariable("load_freq_dawg", "0");
        api.SetVariable("tessedit_char_whitelist",
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" +
                        "0123456789#/()[]., '-{}");
        System.out.printf("Tesseract initialized with tessdata %s\n ", tessDirPath);

        // Read and set image
        BufferedImage bufferedImage = ImageIO.read(imageFile);

        // Standard JavaCV bridge
        Java2DFrameConverter biConverter = new Java2DFrameConverter();
        OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();

        Mat mat = matConverter.convert(biConverter.convert(bufferedImage));
        Mat processed = preprocessImage(mat);  // <-- add this

        api.SetImage(processed.data(), processed.cols(), processed.rows(), processed.channels(), (int)processed.step());

        // Run OCR
        BytePointer outText = api.GetHOCRText(0);
        String html = outText.getString();
        System.out.println(html);
        String result = HocrTolerantParser.parseHocrToString(html);

        // Cleanup
        outText.deallocate();
        api.End();
        api.close();

        cleanupTessData(tessDirPath);
        System.out.println("OCR completed successfully - " + result.length() + " characters returned");
        return result;
    }

    private static void cleanupTessData(String tessDirPath) throws UncheckedIOException, IOException{
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
        // org.bytedeco.opencv.opencv_core.Mat
        Mat gray     = new Mat();
        Mat upscaled = new Mat();
        Mat binary   = new Mat();
        Mat normalized = new Mat();

        // org.bytedeco.opencv.global.opencv_imgproc.cvtColor
        // COLOR_BGR2GRAY is a constant from opencv_imgproc
        cvtColor(src, gray, COLOR_BGR2GRAY);

        // org.bytedeco.opencv.global.opencv_imgproc.resize
        // org.bytedeco.opencv.opencv_core.Size
        // INTER_CUBIC is a constant from opencv_imgproc
        resize(gray, upscaled,
                new Size(src.cols() * 3, src.rows() * 3),
                0, 0,
                INTER_LANCZOS4);

        normalize(upscaled, normalized, 0, 255, NORM_MINMAX, CV_8UC1, null);

        // org.bytedeco.opencv.global.opencv_imgproc.threshold
        // THRESH_BINARY and THRESH_OTSU are constants from opencv_imgproc
        threshold(normalized, binary, 0, 255, THRESH_BINARY | THRESH_OTSU);

        // org.bytedeco.opencv.global.opencv_imgproc.getStructuringElement
        // org.bytedeco.opencv.global.opencv_imgproc.dilate
        // MORPH_RECT is a constant from opencv_imgproc
//        Mat kernel = getStructuringElement(MORPH_RECT, new Size(1, 1));
//        dilate(binary, binary, kernel);

        // Release intermediate Mats to avoid native memory leaks
        gray.release();
        upscaled.release();
        normalized.release();

        return binary;
    }


    /**
     * Prepares a temporary tessdata directory for Bytedeco Tesseract.
     * * @param lang The language code (e.g., "eng")
     * @return The absolute path to the PARENT folder of the extracted data
     */
    protected String prepareTessData(String lang) throws IOException {
        // 1. Create a unique temp directory for this run
        Path tempDir = Files.createTempDirectory("tesseract_resources");

        final String dataDir = "tessdata";
        final String dataSuffix = ".traineddata";

        // 2. Create the 'tessdata' subfolder (Tesseract expects this specific name)
        Path tessDataFolder = tempDir.resolve(dataDir);
        Files.createDirectories(tessDataFolder);

        // 3. Define the resource path and target file
        String resourceName = "/" + dataDir + "/" + lang + dataSuffix;
        Path targetFile = tessDataFolder.resolve(lang + dataSuffix);
        System.out.println(targetFile.toString());
        // 4. Stream the data out of the JAR
        try (InputStream is = getClass().getResourceAsStream(resourceName)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourceName +
                        ". Ensure it is in src/main/resources/" + dataDir);
            }

            Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }

        // 5. Return the path to the DIRECTORY containing the 'tessdata' folder
        // Tesseract Init() expects the path to the parent of 'tessdata'
        return tessDataFolder.toAbsolutePath().toString();
    }
}