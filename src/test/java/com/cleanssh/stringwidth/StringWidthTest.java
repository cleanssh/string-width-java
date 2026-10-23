package com.cleanssh.stringwidth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StringWidthTest {

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource({
            // ASCII and simple text
            "hello, 5",
            "'', 0",
            // CJK full-width
            "古, 2",
            "中文, 4",
            // Emoji
            "😀, 2",
            "❤️, 2",
            "❤, 1",
            "🇨🇳, 2",
            "👨‍👩‍👧, 2",
            "👍🏽, 2",
            "👩🏽‍🚀, 2",
            "1️⃣, 2",
            "1⃣, 2",
            "#️⃣, 2",
            "🏴󠁧󠁢󠁳󠁣󠁴󠁿, 2",
            // Combining mark
            "e\u0301, 1",
            // Ambiguous (default narrow)
            "±, 1",
            "▪, 1",
            // Hangul
            "가, 2",
            "가, 2",
    })
    @DisplayName("default widths")
    void defaultWidths(String input, int expected) {
        assertEquals(expected, StringWidth.stringWidth(input));
    }

    @Test
    @DisplayName("null and empty")
    void nullAndEmpty() {
        assertEquals(0, StringWidth.stringWidth(null));
        assertEquals(0, StringWidth.stringWidth(""));
    }

    @Test
    @DisplayName("ambiguous as wide option")
    void ambiguousAsWide() {
        assertEquals(1, StringWidth.stringWidth("±"));
        assertEquals(2, StringWidth.stringWidth("±", false));
    }

    @Test
    @DisplayName("ANSI escape codes are stripped by default")
    void ansiStripped() {
        assertEquals(2, StringWidth.stringWidth("\u001B[1m古\u001B[22m"));
        assertEquals(2, StringWidth.stringWidth("\u001B[31m古\u001B[0m"));
    }

    @Test
    @DisplayName("ANSI escape codes can be counted")
    void ansiCounted() {
        int width = StringWidth.stringWidth("\u001B[1m古\u001B[22m", true, true);
        assertEquals(9, width); // ESC[1m (5) + 古 (2) + ESC[22m (2) ... see comment
    }

    @Test
    @DisplayName("tabs are non-printing (match JS)")
    void tabIsNonPrinting() {
        assertEquals(0, StringWidth.stringWidth("\t"));
    }

    @Test
    @DisplayName("mixed content")
    void mixedContent() {
        assertEquals(6, StringWidth.stringWidth("中文😀"));
        assertEquals(9, StringWidth.stringWidth("abc中文😀"));
        assertEquals(11, StringWidth.stringWidth("abc中文😀🇨🇳"));
    }
}
