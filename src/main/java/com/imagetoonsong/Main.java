package com.imagetoonsong;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;


/**
 * Application entry point.
 * <p>
 * This class intentionally does NOT extend javafx.application.Application.
 * <p>
 * When JavaFX is loaded from the module path (as it is in a jpackage bundle),
 * the JVM enforces that the class named in --main-class must NOT extend
 * Application directly — it must delegate to one that does. Keeping this
 * class as a plain launcher avoids a "JavaFX runtime components are missing"
 * error that appears only in the packaged .app, not during `./gradlew run`.
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(
            MethodHandles.lookup().lookupClass());

    static void main(String[] args) {

        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

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
     * <p>
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
            logger.info("[ImageToOnSong] Native cache: {}", cacheDir);
        } catch (IOException e) {
            // Soft failure — JavaCPP will fall back to java.io.tmpdir automatically
            logger.error("[ImageToOnSong] Warning: could not create native cache at {} "
                   + " — falling back to system temp. ( {} )", cacheDir, e.getMessage());
        }
    }
}