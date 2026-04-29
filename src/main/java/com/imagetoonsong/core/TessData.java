package com.imagetoonsong.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.lang.ref.Cleaner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TessData implements AutoCloseable{
    private static final Logger logger = LoggerFactory.getLogger(
            MethodHandles.lookup().lookupClass());
    private static final Cleaner CLEANER = Cleaner.create();
    private static final Set<Cleaner.Cleanable> TO_CLEAN = ConcurrentHashMap.newKeySet();
    public static String tessDirPath;
    private static Path tempRoot;

    static {
        // Register the shutdown hook once
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown hook: Cleaning remaining resources...");
            TO_CLEAN.forEach(Cleaner.Cleanable::clean);
        }));
    }

    private final Cleaner.Cleanable cleanable;

    public TessData() throws IOException {
        tempRoot = Files.createTempDirectory("tesseract_resources");
        tessDirPath = prepareTessData();
        State state = new State(tempRoot);
        // 3. Register the object and the cleaning action
        cleanable = CLEANER.register(this, state);
        TO_CLEAN.add(cleanable);

    }

    private record State(Path pointer) implements Runnable {

        @Override
        public void run() {
            // This is the actual "Destructor" logic
            logger.info("Cleaning up native resource at address: {}", pointer);
            try (Stream<Path> stream = Files.walk(pointer)) {
                stream.sorted(Comparator.reverseOrder()).forEach(TessData::deletePath);
            } catch (IOException e) {
                logger.error(e.getMessage());
            }
        }
    }

    protected String prepareTessData() throws IOException {
        final String dataDir    = "tessdata";
        final String dataSuffix = ".traineddata";
        final String[] trainedDataFiles = {
                "eng" + dataSuffix
        };

        Path tessDataFolder = tempRoot.resolve(dataDir);
        Path targetDirectory = Files.createDirectories(tessDataFolder);

        Arrays.stream(trainedDataFiles).forEach(dataFileName -> {
            String fileName = "/" + dataDir + "/" + dataFileName;
            try (InputStream is = getClass().getResourceAsStream(fileName)) {
                if (is == null) throw new IOException(
                        "Resource not found: " + fileName +
                                ". Ensure it is in src/main/resources/" + dataDir);
                Files.copy(is, targetDirectory.resolve(dataFileName), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return tessDataFolder.toAbsolutePath().toString();
    }

    @Override
    public void close() throws Exception {
        cleanable.clean();
        TO_CLEAN.remove(cleanable);
    }

    private static void deletePath(Path path) {
        logger.info("Deleting {} ", path.toString());
        try { Files.deleteIfExists(path); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
