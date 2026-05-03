package com.imagetoonsong;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import com.imagetoonsong.core.ImageSource;
import com.imagetoonsong.core.OcrProcessor;
import com.imagetoonsong.core.OnSongBuilder;
import com.imagetoonsong.core.TessData;
import javafx.application.Platform;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
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
 *     expected-outputs/
 *       forgiving_god.txt
 *       good_father.txt
 *
 * On failure, a unified diff is printed to the log so you can see
 * exactly which lines changed without manually comparing two long strings.
 *
 * Diff output format (standard unified diff):
 *   --- expected/forgiving_god.txt
 *   +++ actual
 *   @@ -12,4 +12,4 @@
 *    [G]He's a forgiving God
 *   -[D]No longer have to wander          ← in expected, missing from actual
 *   +[D]No longer have to wonder          ← in actual, different from expected
 *    [G/D]Calling sons and daughters
 */
class OcrPipelineTest {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final Path RESOURCES   = Paths.get("src", "test", "resources");
    private static final Path IMAGES_DIR  = RESOURCES.resolve("images");
    private static final Path EXPECTED_DIR = RESOURCES.resolve("expected-outputs");

    /** Shared TessData — holds strong reference so Cleaner doesn't GC it mid-run. */
    private static TessData tessData;

    // ── Setup / teardown ─────────────────────────────────────────────────────

    @BeforeAll
    static void setUp() throws Exception {
        tessData = new TessData();
        log.info("TessData initialized at: {}", TessData.tessDirPath);

        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Toolkit already initialized — safe to ignore
            log.debug("JavaFX toolkit already running: {}", e.getMessage());
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (tessData != null) tessData.close();
    }

    // ── Data provider ────────────────────────────────────────────────────────

    @SuppressWarnings("resource")
    static Stream<Path> imageFiles() throws IOException {
        log.info("Scanning for test images in: {}", IMAGES_DIR.toAbsolutePath());

        if (!Files.exists(IMAGES_DIR)) {
            log.warn("Images directory does not exist: {} — no tests will run",
                    IMAGES_DIR.toAbsolutePath());
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
                .sorted();
    }

    // ── Parameterized test ───────────────────────────────────────────────────

    @Execution(ExecutionMode.CONCURRENT)
    @ParameterizedTest(name = "{0}")
    @MethodSource("imageFiles")
    void pipelineProducesExpectedOnSongOutput(Path imagePath) throws Exception {
        String baseName     = baseNameOf(imagePath);
        Path   expectedPath = EXPECTED_DIR.resolve(baseName + ".txt");

        if (!Files.exists(expectedPath)) {
            fail(String.format(
                    "Missing expected output file for '%s'.%n" +
                            "Run the pipeline manually and save the correct output to:%n" +
                            "  %s",
                    imagePath.getFileName(), expectedPath.toAbsolutePath()));
        }

        // ── Run pipeline ──────────────────────────────────────────────────────
        log.info("[{}] Starting OCR", baseName);
        long start = System.currentTimeMillis();

        ImageSource   source  = ImageSource.fromFile(imagePath.toFile());
        OcrProcessor  ocr     = new OcrProcessor();
        OnSongBuilder builder = new OnSongBuilder();

        String rawText = ocr.extractText(source);
        String actual  = builder.buildOnSong(rawText, "Untitled Song", "Unknown Artist", true)
                .stripTrailing();

        log.info("[{}] OCR complete in {}ms", baseName, System.currentTimeMillis() - start);

        // ── Compare ───────────────────────────────────────────────────────────
        String expected = Files.readString(expectedPath).stripTrailing();

        if (!expected.equals(actual)) {
            String diff = unifiedDiff(expected, actual, baseName);
            log.error("[{}] Output mismatch:\n{}", baseName, diff);

            // Fail with the diff embedded in the assertion message so it appears
            // in the JUnit report, IDE test runner, and CI logs simultaneously.
            assertEquals(expected, actual,
                    String.format("OnSong output mismatch for '%s':%n%s", baseName, diff));
        }
    }

    // ── Diff helper ──────────────────────────────────────────────────────────

    /**
     * Produces a unified diff string between expected and actual output.
     *
     * Format matches standard `diff -u` output — familiar to any developer
     * and understood by most CI systems:
     *
     *   --- expected/forgiving_god.txt
     *   +++ actual
     *   @@ -12,4 +12,4 @@
     *    context line (unchanged)
     *   -line only in expected
     *   +line only in actual
     *
     * Context lines (unchanged lines surrounding a change) default to 3,
     * matching the standard unified diff convention.
     */
    private static String unifiedDiff(String expected, String actual, String baseName) {
        List<String> expectedLines = Arrays.asList(expected.split("\n", -1));
        List<String> actualLines   = Arrays.asList(actual.split("\n",   -1));

        Patch<String> patch = DiffUtils.diff(expectedLines, actualLines);

        List<String> diffLines = UnifiedDiffUtils.generateUnifiedDiff(
                "expected/" + baseName + ".txt",  // --- label
                "actual",                          // +++ label
                expectedLines,
                patch,
                3                                  // context lines
        );

        if (diffLines.isEmpty()) {
            // DiffUtils found no textual differences — likely a whitespace/
            // line-ending issue that String.equals caught but diff missed.
            return "(no line-level diff — possible whitespace or line-ending difference)";
        }

        return String.join("\n", diffLines);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static String baseNameOf(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}