package com.imagetoonsong.core;

public class SectionDetector {
    static boolean detectSectionCaseInsensitive(String line) {
        return line.matches("(?i)^[^a-zA-Z]*(verse|chorus|bridge|intro|outro|pre-?chorus|tag|interlude|instrumental|break?down|ending)[^a-zA-Z]*.*");
    }

    static boolean isSectionHeader(String line) {
        return line.matches(
                "(?i)^(Verse|Chorus|Bridge|Intro|Outro|Pre-?Chorus|Tag|Instrumental|Interlude|Break?down|Ending).*:$");
    }
}
