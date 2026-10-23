package com.cleanssh.stringwidth;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import com.ibm.icu.lang.UCharacterEnums;
import com.ibm.icu.text.BreakIterator;
import com.ibm.icu.text.UnicodeSet;
import com.ibm.icu.util.ULocale;

/**
 * Java port of sindresorhus/string-width v8 (index.js).
 *
 * Rules (same order as the JS implementation):
 *  1. Strip ANSI escape codes (unless countAnsiEscapeCodes).
 *  2. Fast path for printable ASCII (0x20-0x7E): width == length.
 *  3. Segment into extended grapheme clusters (ICU BreakIterator, UAX #29).
 *  4. Per cluster:
 *     a. Skip cluster if it has no visible base scalar -> 0.
 *     b. RGI emoji, or unqualified keycap, or ZWJ sequence with >=2
 *        Extended_Pictographic -> 2.
 *     c. Hangul L+V(+T) jamo collapse -> 2; unmatched jamo additive.
 *     d. Otherwise: East_Asian_Width of the first visible scalar, plus
 *        trailing Spacing_Mark / Halfwidth-Fullwidth forms.
 */
public final class StringWidth {

    private StringWidth() {}

    /**
     * RGI_Emoji (Recommended for General Interchange) sequence set, exactly the
     * property used by the JS implementation via \p{RGI_Emoji}.
     */
    private static final UnicodeSet RGI_EMOJI = new UnicodeSet("[:RGI_Emoji:]").freeze();

    /** Extended_Pictographic, used for the non-RGI ZWJ fallback (same as JS). */
    private static final UnicodeSet EXTENDED_PICTOGRAPHIC =
            new UnicodeSet("[:Extended_Pictographic:]").freeze();

    public static int stringWidth(String input) {
        return stringWidth(input, true, false);
    }

    public static int stringWidth(String input, boolean ambiguousIsNarrow) {
        return stringWidth(input, ambiguousIsNarrow, false);
    }

    /**
     * @param input              text to measure
     * @param ambiguousIsNarrow  true (default) counts East Asian Ambiguous as 1
     * @param countAnsiEscapeCodes if false (default), ANSI escapes are stripped
     */
    public static int stringWidth(String input, boolean ambiguousIsNarrow, boolean countAnsiEscapeCodes) {
        if (input == null || input.isEmpty()) {
            return 0;
        }

        String string = input;
        if (!countAnsiEscapeCodes && (string.indexOf('\u001B') >= 0 || string.indexOf('\u009B') >= 0)) {
            string = stripAnsi(string);
        }
        if (string.isEmpty()) {
            return 0;
        }

        // Fast path: printable ASCII only.
        if (isPrintableAscii(string)) {
            return string.length();
        }

        int width = 0;
        boolean ambiguousAsWide = !ambiguousIsNarrow;

        BreakIterator it = BreakIterator.getCharacterInstance(ULocale.ROOT);
        it.setText(string);
        int start = it.first();
        for (int end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            String segment = string.substring(start, end);

            String visible = baseVisible(segment);
            if (visible == null) {
                continue; // whole cluster is zero-width / non-printing
            }

            // (b) emoji
            if (isDoubleWidthEmoji(segment)) {
                width += 2;
                continue;
            }

            // (c) Hangul
            Integer hangulWidth = hangulClusterWidth(visible, ambiguousAsWide);
            if (hangulWidth != null) {
                width += hangulWidth;
                continue;
            }

            // (d) EAW of first visible scalar + trailing spacing marks
            int firstCp = visible.codePointAt(0);
            width += eastAsianWidth(firstCp, ambiguousAsWide);
            width += trailingWidth(visible, ambiguousAsWide);
        }
        return width;
    }

    // ------------------------------------------------------------------
    // (a) visible base
    // ------------------------------------------------------------------

    private static boolean isNonPrinting(int cp) {
        int cat = UCharacter.getIntPropertyValue(cp, UProperty.GENERAL_CATEGORY);
        if (cat == UCharacterEnums.ECharacterCategory.CONTROL
                || cat == UCharacterEnums.ECharacterCategory.FORMAT
                || cat == UCharacterEnums.ECharacterCategory.NON_SPACING_MARK
                || cat == UCharacterEnums.ECharacterCategory.ENCLOSING_MARK
                || cat == UCharacterEnums.ECharacterCategory.SURROGATE) {
            return true;
        }
        return isDefaultIgnorable(cp);
    }

    /** Same set as the JS regex class: Default_Ignorable | Control | Format | Nonspacing_Mark | Enclosing_Mark | Surrogate. */
    private static String baseVisible(String segment) {
        int i = 0;
        while (i < segment.length()) {
            int cp = segment.codePointAt(i);
            if (!isNonPrinting(cp)) {
                return segment.substring(i);
            }
            i += Character.charCount(cp);
        }
        return null;
    }

    private static boolean isDefaultIgnorable(int cp) {
        // Approximates Unicode Default_Ignorable_Code_Point.
        return (cp >= 0x00AD && cp <= 0x00AD)
                || (cp >= 0x034F && cp <= 0x034F)
                || (cp >= 0x061C && cp <= 0x061C)
                || (cp >= 0x115F && cp <= 0x1160)
                || (cp >= 0x17B4 && cp <= 0x17B5)
                || (cp >= 0x180B && cp <= 0x180F)
                || (cp >= 0x200B && cp <= 0x200F)
                || (cp >= 0x202A && cp <= 0x202E)
                || (cp >= 0x2060 && cp <= 0x206F)
                || (cp >= 0x3164 && cp <= 0x3164)
                || (cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0xFEFF && cp <= 0xFEFF)
                || (cp >= 0xFFA0 && cp <= 0xFFA0)
                || (cp >= 0xFFF0 && cp <= 0xFFF8)
                || (cp >= 0x1BCA0 && cp <= 0x1BCA3)
                || (cp >= 0x1D173 && cp <= 0x1D17A)
                || (cp >= 0xE0000 && cp <= 0xE0FFF);
    }

    // ------------------------------------------------------------------
    // (b) emoji
    // ------------------------------------------------------------------

    private static boolean isDoubleWidthEmoji(String segment) {
        if (segment.length() > 50) {
            return false; // guard against pathological input (same as JS)
        }

        // (1) RGI emoji: exact \p{RGI_Emoji} match, including fully-qualified
        //     sequences, flags, keycaps and tagged flags.
        if (RGI_EMOJI.contains(segment)) {
            return true;
        }

        // (2) Minimally-qualified / unqualified emoji clusters that are not in
        //     RGI but still render double-width (same as the JS fallback).
        //     - unqualified keycap: [0-9#*] U+20E3
        if (segment.length() == 2) {
            int a = segment.charAt(0);
            if (segment.charAt(1) == '\u20E3'
                    && ((a >= '0' && a <= '9') || a == '#' || a == '*')) {
                return true;
            }
        }

        // (3) ZWJ sequence with >= 2 Extended_Pictographic
        if (segment.indexOf('\u200D') >= 0) {
            int pictographics = 0;
            for (int i = 0; i < segment.length(); ) {
                int cp = segment.codePointAt(i);
                if (EXTENDED_PICTOGRAPHIC.contains(cp)) {
                    pictographics++;
                    if (pictographics >= 2) {
                        return true;
                    }
                }
                i += Character.charCount(cp);
            }
        }

        return false;
    }

    // ------------------------------------------------------------------
    // (c) Hangul
    // ------------------------------------------------------------------

    private static boolean isHangulLeadingJamo(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F) || (cp >= 0xA960 && cp <= 0xA97C);
    }

    private static boolean isHangulVowelJamo(int cp) {
        return (cp >= 0x1160 && cp <= 0x11A7) || (cp >= 0xD7B0 && cp <= 0xD7C6);
    }

    private static boolean isHangulTrailingJamo(int cp) {
        return (cp >= 0x11A8 && cp <= 0x11FF) || (cp >= 0xD7CB && cp <= 0xD7FB);
    }

    private static boolean isHangulJamo(int cp) {
        return isHangulLeadingJamo(cp) || isHangulVowelJamo(cp) || isHangulTrailingJamo(cp);
    }

    private static Integer hangulClusterWidth(String visible, boolean ambiguousAsWide) {
        int[] cps = visible.codePoints()
                .filter(cp -> !isNonPrinting(cp))
                .toArray();
        if (cps.length == 0) {
            return null;
        }

        int width = 0;
        for (int index = 0; index < cps.length; index++) {
            int cp = cps[index];
            if (!isHangulJamo(cp)) {
                if (width == 0) {
                    return null;
                }
                for (int remaining = index; remaining < cps.length; remaining++) {
                    width += eastAsianWidth(cps[remaining], ambiguousAsWide);
                }
                return width;
            }
            if (isHangulLeadingJamo(cp) && index + 1 < cps.length && isHangulVowelJamo(cps[index + 1])) {
                width += 2;
                index += (index + 2 < cps.length && isHangulTrailingJamo(cps[index + 2])) ? 2 : 1;
                continue;
            }
            width += eastAsianWidth(cp, ambiguousAsWide);
        }
        return width;
    }

    // ------------------------------------------------------------------
    // (d) trailing spacing marks + halfwidth/fullwidth forms
    // ------------------------------------------------------------------

    private static boolean isSpacingMark(int cp) {
        return UCharacter.getIntPropertyValue(cp, UProperty.GENERAL_CATEGORY)
                == UCharacterEnums.ECharacterCategory.COMBINING_SPACING_MARK;
    }

    private static int trailingWidth(String visible, boolean ambiguousAsWide) {
        int extra = 0;
        boolean first = true;
        for (int i = 0; i < visible.length(); ) {
            int cp = visible.codePointAt(i);
            i += Character.charCount(cp);
            if (first) {
                first = false;
                continue;
            }
            boolean halfFullForm = cp >= 0xFF00 && cp <= 0xFFEF;
            if (isSpacingMark(cp) || halfFullForm) {
                extra += eastAsianWidth(cp, ambiguousAsWide);
            }
        }
        return extra;
    }

    // ------------------------------------------------------------------
    // East_Asian_Width
    // ------------------------------------------------------------------

    /** ICU East_Asian_Width numeric values (UProperty.EAST_ASIAN_WIDTH). */
    private static int eastAsianWidth(int cp, boolean ambiguousAsWide) {
        int eaw = UCharacter.getIntPropertyValue(cp, UProperty.EAST_ASIAN_WIDTH);
        switch (eaw) {
            case UCharacter.EastAsianWidth.WIDE:
            case UCharacter.EastAsianWidth.FULLWIDTH:
                return 2;
            case UCharacter.EastAsianWidth.AMBIGUOUS:
                return ambiguousAsWide ? 2 : 1;
            case UCharacter.EastAsianWidth.HALFWIDTH:
            case UCharacter.EastAsianWidth.NARROW:
            case UCharacter.EastAsianWidth.NEUTRAL:
            default:
                return 1;
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static boolean isPrintableAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /** Minimal ANSI escape stripper (CSI, OSC, and simple 2-byte escapes). */
    static String stripAnsi(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\u001B') {
                if (i + 1 < n) {
                    char next = s.charAt(i + 1);
                    if (next == '[') {
                        // CSI: ESC [ ... final byte 0x40-0x7E
                        i += 2;
                        while (i < n) {
                            char b = s.charAt(i);
                            i++;
                            if (b >= 0x40 && b <= 0x7E) {
                                break;
                            }
                        }
                        continue;
                    } else if (next == ']') {
                        // OSC: ESC ] ... BEL or ST
                        i += 2;
                        while (i < n) {
                            char b = s.charAt(i);
                            if (b == '\u0007') {
                                i++;
                                break;
                            }
                            if (b == '\u001B' && i + 1 < n && s.charAt(i + 1) == '\\') {
                                i += 2;
                                break;
                            }
                            i++;
                        }
                        continue;
                    } else {
                        i += 2; // other 2-byte escape
                        continue;
                    }
                }
                i++;
                continue;
            }
            if (c == '\u009B') {
                // 8-bit CSI
                i++;
                while (i < n) {
                    char b = s.charAt(i);
                    i++;
                    if (b >= 0x40 && b <= 0x7E) {
                        break;
                    }
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
