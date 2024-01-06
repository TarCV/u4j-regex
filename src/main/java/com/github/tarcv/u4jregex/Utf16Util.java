package com.github.tarcv.u4jregex;

import com.ibm.icu.text.UTF16;

public class Utf16Util {
    static int U16_GET(final char[] s, final int start, final int i, final int length) {
        char c = (s)[i];
        if (U16_IS_SURROGATE(c)) {
            char __c2;
            if (U16_IS_SURROGATE_LEAD(c)) {
                __c2 = (s)[i + 1];
                if ((i) + 1 != (length) && Util.U16_IS_TRAIL(__c2)) {
                    return Math.toIntExact(Util.U16_GET_SUPPLEMENTARY((c), __c2));
                }
            } else {
                __c2 = (s)[i - 1];
                if ((i) > (start) && Util.U16_IS_LEAD(__c2)) {
                    return Math.toIntExact(Util.U16_GET_SUPPLEMENTARY(__c2, c));
                }
            }
        }
        return c;
    }

    static boolean U16_IS_SURROGATE(final char c) {
        return UTF16.isSurrogate(c);
    }

    static boolean U16_IS_SURROGATE_LEAD(final char c) {
        return (c & 0x400) == 0;
    }

    static boolean U16_IS_SURROGATE_TRAIL(final char c) {
        return (c & 0x400) != 0;
    }

    static Util.IndexAndChar U16_PREV(final char[] s, final int start, final int i) {
        int iNew = i;
        int c = (s)[--iNew];
        if(Util.U16_IS_TRAIL(c)) {
            /*uint16*/int __c2;
            if((iNew)>(start) && Util.U16_IS_LEAD(__c2=(s)[(iNew)-1])) {
                --iNew;
                c = Math.toIntExact(Util.U16_GET_SUPPLEMENTARY(__c2, (c)));
            }
        }
        return new Util.IndexAndChar(iNew, c);
    }

    static int U16_BACK_1(final char[] s, final int start, final int i) {
        int iNew = i;
        if(Util.U16_IS_TRAIL((s)[--(iNew)]) && (iNew)>(start) && Util.U16_IS_LEAD((s)[(iNew)-1])) {
            --(iNew);
        }
        return iNew;
    }

    static int U16_SET_CP_START(final char[] s, final int start, final int i) {
        int iNew = i;
        if(Util.U16_IS_TRAIL((s)[iNew]) && (iNew)>(start) && Util.U16_IS_LEAD((s)[(iNew)-1])) {
            --(iNew);
        }
        return iNew;
    }

    public static int U16_LENGTH(final int c) {
        return (/*uint32*/long)(c)<=0xffff ? 1 : 2;
    }
}
