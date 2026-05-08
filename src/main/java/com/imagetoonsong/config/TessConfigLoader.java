package com.imagetoonsong.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TessConfigLoader {
    private static final Logger logger = LoggerFactory.getLogger(
            MethodHandles.lookup().lookupClass());
    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    public static final String TESSERACT_CONFIGS = "tesseract-configs/";
    public static final String TESSERACT_CONFIGS_INDEX_JSON = TESSERACT_CONFIGS + "index.json";

    private final Map<String, TessConfig> configs = new ConcurrentHashMap<>();

    public TessConfigLoader() {
        loadAllConfigs();
    }

    private void loadAllConfigs() {
        try {
            InputStream indexStream = getClass().getClassLoader()
                    .getResourceAsStream(TESSERACT_CONFIGS_INDEX_JSON);

            if (indexStream == null) {
                throw new IllegalStateException(String.format("%s not found in resources", TESSERACT_CONFIGS_INDEX_JSON));
            }

            List<String> configFiles = mapper.readValue(indexStream, new TypeReference<>() {});

            for (String filename : configFiles) {
                loadSingleConfig(TESSERACT_CONFIGS + filename);
            }

            logger.info("✅ Loaded {} Tesseract configurations ", configs.size());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load Tesseract configuration index", e);
        }
    }

    private void loadSingleConfig(String resourcePath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                logger.error("⚠️  Config file not found: {}", resourcePath);
                return;
            }

            TessConfig config = mapper.readValue(is, TessConfig.class);
            configs.put(config.getName(), config);
            System.out.println("   Loaded Tesseract config: " + config.getName());
        } catch (Exception e) {
            logger.error("❌ Failed to load config {} → {}", resourcePath , e.getMessage());
        }
    }

    public TessConfig getConfig(String name) {
        TessConfig config = configs.get(name);
        if (config == null) {
            throw new IllegalArgumentException("Tesseract config not found: '" + name + "'. Available: " + configs.keySet());
        }
        return config;
    }

    public Map<String, TessConfig> getAllConfigs() {
        return new HashMap<>(configs);
    }

    // Optional: Reload during development
    public void reload() {
        configs.clear();
        loadAllConfigs();
    }
}
