package com.imagetoonsong.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class OnSongBuilder {
    private static final Logger logger = LoggerFactory.getLogger(
            MethodHandles.lookup().lookupClass());

    public String buildOnSong(String processedText, String title, String artist, boolean emptyTextBox) {
        logger.info("rawOCRText: {}}", processedText);

        StringBuilder sb = new StringBuilder();

        if (emptyTextBox) {
            // Metadata (OnSong format)
            sb.append(title != null && !title.isEmpty() ? title : "Untitled Song").append("\n");
            sb.append(artist != null && !artist.isEmpty() ? artist : "Unknown Artist").append("\n");
            sb.append("Key: C").append("\n");
            sb.append("Tempo: 96").append("\n");
            sb.append("Time: 4/4").append("\n");
            sb.append("\n");
        }
        String[] lines = processedText.split("\n");

        int lineNumber = 0;
        for (String line : lines) {
            lineNumber++;
            if (line.isEmpty()) {
                sb.append("\n");
                logger.info("{} : Found empty", lineNumber);

                continue;
            }
            if (line.length() == 1) {
                logger.info("{} : Found singleton {}}", lineNumber, line);
            }
            sb.append(line).append("\n");
        }

        return flagIncompleteLines(sb.toString());
    }

    private static String flagIncompleteLines(String onSongText) {
        String[] lines = onSongText.split("\n");
        Pattern hasChord = Pattern.compile("\\[[A-G][^]]*]");
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean prevHasChord = i > 0 && hasChord.matcher(lines[i - 1]).find();
            boolean nextHasChord = i < lines.length - 1 && hasChord.matcher(lines[i + 1]).find();
            boolean thisHasChord = hasChord.matcher(line).find();
            boolean isSectionHeader = SectionDetector.isSectionHeader(line);
            boolean isBlank = line.isBlank();

            if (!isSectionHeader && !isBlank && !thisHasChord && (prevHasChord || nextHasChord)) {
                sb.append(line).append("  ← ⚠️ possible missing chord\n");
            } else {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}