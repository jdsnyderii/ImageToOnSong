package com.imagetoonsong.core;


public class SectionDetector {

    public static final String SECTION_DETECTOR = "(?i)^(Verse|Chorus|Bridge|Intro|Outro|Pre-?Chorus|Tag|Instrumental|Interlude|Break?down|Ending|Code|Refrain).*:$";

    static boolean detectSectionCaseInsensitive(String line) {
        return line.matches("(?i)^[^a-zA-Z]*(verse|chorus|bridge|intro|outro|pre-?chorus|tag|interlude|instrumental|break?down|ending)[^a-zA-Z]*.*");
    }

    static boolean isSectionHeader(String line) {
        if (line.startsWith("[") && line.endsWith("]")) return true;

        return line.matches(SECTION_DETECTOR);
    }
}
