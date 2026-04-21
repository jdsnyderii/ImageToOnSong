package com.imagetoonsong;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Application entry point.
 *
 * This class intentionally does NOT extend javafx.application.Application.
 *
 * When JavaFX is loaded from the module path (as it is in a jpackage bundle),
 * the JVM enforces that the class named in --main-class must NOT extend
 * Application directly — it must delegate to one that does. Keeping this
 * class as a plain launcher avoids a "JavaFX runtime components are missing"
 * error that appears only in the packaged .app, not during `./gradlew run`.
 */
public class MainApp {

    public static void main(String[] args) {

        // ── Step 1: Native library cache ──────────────────────────────────────
        // MUST be called before any org.bytedeco / JavaCPP class is referenced.
        // JavaCPP extracts platform .dylibs here at runtime. The directory must
        // be writable — the interior of a .app bundle is read-only after signing.
        initNativeCache();

        // ── Step 2: Launch JavaFX ─────────────────────────────────────────────
        // Delegates to App.java which extends Application. This keeps the
        // launcher/module boundary clean.
        javafx.application.Application.launch(App.class, args);
    }

    /**
     * Configures the JavaCPP native extraction cache to a writable location
     * under ~/Library/Caches (the macOS-standard cache directory).
     *
     * Falls back to the system temp directory if the preferred path cannot
     * be created — extraction will still work, but native libs will be
     * re-extracted on every cold launch instead of being reused.
     */
    private static void initNativeCache() {
        // Respect an explicit override supplied via -D on the command line
        if (System.getProperty("org.bytedeco.javacpp.cachedir") != null) {
            return;
        }

        Path cacheDir = Paths.get(
                System.getProperty("user.home"),
                "Library", "Caches", "ImageToOnSong", "natives"
        );

        try {
            Files.createDirectories(cacheDir);
            System.setProperty("org.bytedeco.javacpp.cachedir", cacheDir.toString());
            System.out.println("[ImageToOnSong] Native cache: " + cacheDir);
        } catch (IOException e) {
            // Soft failure — JavaCPP will fall back to java.io.tmpdir automatically
            System.err.println("[ImageToOnSong] Warning: could not create native cache at "
                    + cacheDir + " — falling back to system temp. (" + e.getMessage() + ")");
        }
    }
}