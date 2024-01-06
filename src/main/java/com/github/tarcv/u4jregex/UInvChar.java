package com.github.tarcv.u4jregex;

public class UInvChar {
    /*
     * Bit sets indicating which characters of the ASCII repertoire
     * (by ASCII/Unicode code) are "invariant".
     * See utypes.h for more details.
     *
     * As invariant are considered the characters of the ASCII repertoire except
     * for the following:
     * 21  '!' <exclamation mark>
     * 23  '#' <number sign>
     * 24  '$' <dollar sign>
     *
     * 40  '@' <commercial at>
     *
     * 5b  '[' <left bracket>
     * 5c  '\' <backslash>
     * 5d  ']' <right bracket>
     * 5e  '^' <circumflex>
     *
     * 60  '`' <grave accent>
     *
     * 7b  '{' <left brace>
     * 7c  '|' <vertical line>
     * 7d  '}' <right brace>
     * 7e  '~' <tilde>
     */
    final static /*uint32*/long[] invariantChars = new long[] {
            0xfffffbffL, /* 00..1f but not 0a */
            0xffffffe5L, /* 20..3f but not 21 23 24 */
            0x87fffffeL, /* 40..5f but not 40 5b..5e */
            0x87fffffeL  /* 60..7f but not 60 7b..7e */
    };

    /*
     * test unsigned types (or values known to be non-negative) for invariant characters,
     * tests ASCII-family character values
     */
    static boolean UCHAR_IS_INVARIANT(int c) {
        return (((c)<=0x7f) && (invariantChars[(c)>>>5]&((/*uint32*/ long)1<<((c)&0x1f)))!=0);
    }

    static boolean uprv_isInvariantUString(CharSequence chars) {
        return chars.chars().allMatch(c -> {
            if (c > Character.MAX_VALUE || c < 0) {
                return false;
            } else {
                return UCHAR_IS_INVARIANT(c);
            }
        });
    }
}
