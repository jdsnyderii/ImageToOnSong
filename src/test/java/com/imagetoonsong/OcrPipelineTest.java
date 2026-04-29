package com.imagetoonsong;

import com.imagetoonsong.core.ImageSource;
import com.imagetoonsong.core.OcrProcessor;
import com.imagetoonsong.core.OnSongBuilder;
import com.imagetoonsong.core.TessData;
import javafx.application.Platform;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration test for the full OCR → OnSong pipeline.
 *
 * Test data layout under src/test/resources:
 *
 *   src/test/resources/
 *     images/
 *       forgiving_god.png
 *       good_father.png
 *       ...
 *     expected-outputs/
 *       forgiving_god.txt     ← expected OnSong text for forgiving_god.png
 *       good_father.txt
 *       ...
 *
 * Naming convention:
 *   Every image file must have a corresponding expected output file with
 *   the same base name and a .txt extension.
 *
 * Adding a new test case:
 *   1. Drop the image into src/test/resources/images/
 *   2. Run the pipeline manually once and capture the correct output
 *   3. Save it as src/test/resources/expected-outputs/<basename>.txt
 *
 * The test discovers image files automatically — no code changes needed
 * when new test cases are added.
 */


@Execution(ExecutionMode.CONCURRENT)
class OcrPipelineTest {

    private static final Path RESOURCES     = Paths.get("src", "test", "resources");
    private static final Path IMAGES_DIR    = RESOURCES.resolve("images");
    private static final Path EXPECTED_DIR  = RESOURCES.resolve("expected-outputs");
    private static TessData tessData;  // ← holds reference so it isn't GC'd


    @BeforeAll
    static void initJavaFX() throws Exception {

        tessData = new TessData();

        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Toolkit already initialized — safe to ignore
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        tessData.close();
    }

    // ── Data provider ────────────────────────────────────────────────────────

    /**
     * Discovers all image files under src/test/resources/images and returns
     * them as a stream of DynamicTest arguments.
     *
     * Supported extensions: png, jpg, jpeg.
     *
     * Each argument is a Path to the image file. JUnit names each test case
     * from the file name so failures clearly identify which image broke.
     */
    static Stream<Path> imageFiles() throws IOException {
        if (!Files.exists(IMAGES_DIR)) {
            // Return empty stream — no test cases yet, don't fail the build
            return Stream.empty();
        }

        return Files.walk(IMAGES_DIR)
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return name.endsWith(".png")
                            || name.endsWith(".jpg")
                            || name.endsWith(".jpeg");
                })
                .sorted(); // deterministic ordering
    }

    // ── Parameterized test ───────────────────────────────────────────────────

    /**
     * For each image file, runs the full pipeline and compares the output
     * against the corresponding expected output file.
     *
     * Failure modes:
     *   - Missing expected output file → clear message explaining what to create
     *   - Output mismatch → diff-friendly assertEquals (JUnit shows both values)
     *
     * Title, artist, key, tempo are fixed to known values for test stability.
     * If your test images require different metadata, add a companion
     * .properties file alongside the image and read from it here.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("imageFiles")
    void pipelineProducesExpectedOnSongOutput(Path imagePath) throws Exception {

        // ── Resolve expected output file ──────────────────────────────────────
        String baseName    = baseNameOf(imagePath);
        Path   expectedPath = EXPECTED_DIR.resolve(baseName + ".txt");

        if (!Files.exists(expectedPath)) {
            fail(String.format(
                    "Missing expected output file for '%s'.%n" +
                            "Run the pipeline manually and save the correct output to:%n" +
                            "  %s",
                    imagePath.getFileName(), expectedPath.toAbsolutePath()));
        }

        // ── Run pipeline ──────────────────────────────────────────────────────
        ImageSource source  = ImageSource.fromFile(imagePath.toFile());
        OcrProcessor ocr    = new OcrProcessor();
        OnSongBuilder builder = new OnSongBuilder();

        String rawText = ocr.extractText(source);
        String actual  = builder.buildOnSong(
                rawText,
                "Untitled Song",
                "Unknown Artist",
                true /* emptyTextBox */);

        // ── Compare ───────────────────────────────────────────────────────────
        String expected = Files.readString(expectedPath).stripTrailing();
        actual = actual.stripTrailing();

        assertEquals(
                expected,
                actual,
                String.format("OnSong output mismatch for image: %s",
                        imagePath.getFileName()));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Returns the file name without its extension.
     * e.g. "forgiving_god.png" → "forgiving_god"
     */
    private static String baseNameOf(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}