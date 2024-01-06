package com.github.tarcv.u4jregex;

import com.ibm.icu.impl.Utility;
import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.text.ReplaceableString;
import com.ibm.icu.text.UTF16;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class Util { // TODO: Inline all one line methods
    static final int U_SENTINEL = -1;
    static final int U_PARSE_CONTEXT_LEN = 16;

    static long utext_nativeLength(final String str) {
        return str.length();
    }

    static int utext_previous32(final StringWithOffset ut) {
        utext_moveIndex32(ut, -1);
        return utext_current32(ut);
    }

    static boolean utext_moveIndex32(final StringWithOffset ut, final int delta) {
        int oldOffset = ut.chunkOffset;
        try {
            ut.chunkOffset = UCharacter.offsetByCodePoints(ut.targetString, ut.chunkOffset, delta);
        } catch (StringIndexOutOfBoundsException e) {
            if (delta > 0) {
                ut.chunkOffset = ut.chunkLength;
            } else {
                throw e;
            }
        }
        return ut.chunkOffset != oldOffset;
    }

    static int utext_current32(final StringWithOffset ut) {
        if (ut.chunkOffset >= ut.chunkLength) {
            return U_SENTINEL;
        }
        return UCharacter.codePointAt(ut.targetString, ut.chunkOffset);
    }

    static int utext_next32(final StringWithOffset ut) {
        int result = utext_current32(ut);
        if (result == U_SENTINEL) {
            return U_SENTINEL;
        }
        utext_moveIndex32(ut, 1);
        return result;
    }

    static int utext_extract(final String ut,
                             final long nativeStart, final long nativeLimit,
                             final char[] dest, final int destCapacity) {
        if (dest != null) {
            if (nativeLimit - nativeStart > dest.length || destCapacity > dest.length) {
                throw new IllegalArgumentException();
            }
            ut.getChars(Math.toIntExact(nativeStart), Math.toIntExact(nativeLimit), dest, 0);
        }
        return Math.toIntExact(nativeLimit - nativeStart);
    }

    static int utext_replace(final ReplaceableString ut,
                             final long nativeStart, final long nativeLimit,
                             final char[] replacementText, final int replacementLength) {
        return utext_replace(ut, nativeStart, nativeLimit, replacementText, 0, replacementLength);
    }

    static int utext_replace(final ReplaceableString ut,
                             final long nativeStart, final long nativeLimit,
                             final char[] replacementText, final int replacementStart, final int replacementLength) {
        int originalLength = ut.length();
        ut.replace(Math.toIntExact(nativeStart), Math.toIntExact(nativeLimit), replacementText, replacementStart, replacementLength);
        return ut.length() - originalLength;
    }

    static CodeAndOffset u_unescapeAt(final int offset, final int length, final char[] context) {
        String truncatedContext = new String(context, 0, length);
        int codeAndLength = Utility.unescapeAndLengthAt(truncatedContext, offset);
        if (codeAndLength == -1) {
            return new CodeAndOffset(0, offset);
        } else {
            return new CodeAndOffset(codeAndLength, offset);
        }
    }

    static CodeAndOffset u_unescapeAt(final int offset, final StringWithOffset text) {
        String filteredContext = uregex_utext_unescape_chars(text);
        int codeAndLength = Utility.unescapeAndLengthAt(filteredContext, offset);
        return new CodeAndOffset(codeAndLength, offset);
    }

    private static String uregex_utext_unescape_chars(final StringWithOffset text) {
        int[] filteredPoints = text.targetString
                .substring(text.chunkOffset)
                .codePoints()
                .limit(9) // escape sequences (dropping slash) are no longer than 9 characters
                .map(code -> {
                    if (UCharacter.isBMP(code)) {
                        return code;
                    } else {
                        return 0;
                    }
                })
                .toArray();
        char[] filteredChars = new char[filteredPoints.length];
        for (int i = 0; i < filteredPoints.length; i++) {
            filteredChars[i] = toCharExact(filteredPoints[i]);
        }
        String filteredContext = new String(filteredChars);
        assert filteredContext.length() == filteredPoints.length;
        return filteredContext;
    }

    public static char toCharExact(final int ch) {
        if (ch < Character.MIN_VALUE || ch > Character.MAX_VALUE) {
            throw new ArithmeticException();
        }
        return (char) ch;
    }

    public static void fullCopyOf(final char[] src, final char[] dest) {
        System.arraycopy(src, 0, dest, 0, src.length);
    }

    public static int strlen(final byte[] chars) {
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == 0) {
                return i - 1;
            }
        }
        return chars.length;
    }

    public static byte[] toByteArray(final char[] arr) {
        byte[] out = new byte[arr.length];
        for (int i = 0; i < arr.length; i++) {
            char c = arr[i];
            //noinspection ConstantValue
            if (c < Byte.MIN_VALUE || c > Byte.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid byte value: " + (int) c);
            }
            out[i] = (byte) c;
        }
        return out;
    }

    public static byte[] toByteArrayWrapping(final char[] arr) {
        byte[] out = new byte[arr.length];
        for (int i = 0; i < arr.length; i++) {
            char c = arr[i];
            if (c > Byte.MAX_VALUE) {
                int newC = c - 0x100;
                //noinspection NumericCastThatLosesPrecision
                out[i] = (byte) newC;
            } else {
                out[i] = (byte) c;
            }
        }
        return out;
    }

    public static String stringRepeat(final char ch, final int number) {
        char[] buffer = new char[number];
        Arrays.fill(buffer, ch);
        return new String(buffer);
    }

    public static String safeCodepointToStr(final int codepoint) {
        if (codepoint == U_SENTINEL) {
            return "<U_SENTINEL>";
        } else {
            return String.format("%c", codepoint);
        }
    }

    public static int lowerBounded(final int value, final int minValue) {
        return Math.max(value, 0);
    }

    public static int upperBounded(final int value, final int maxValue) {
        return Math.min(value, maxValue);
    }

    static class CodeAndOffset {
        final int code;
        final int newOffset;

        CodeAndOffset(final int codeAndLength, final int escapeStartOffset) {
            if (codeAndLength == -1) { // These are specific values that are expected on error:
                this.code = -1;
                this.newOffset = escapeStartOffset;
            } else {
                this.code = codeAndLength >> 8;
                this.newOffset = escapeStartOffset + (codeAndLength & 0xff);
            }
        }
    }

    static int char32At(final StringBuffer uniString, final int index) {
        return UTF16.charAt(uniString, index);
    }

    static int moveIndex32(final StringBuffer uniString, final int index, final int delta) {
        return UTF16.moveCodePointOffset(uniString, index, delta);
    }

    static void truncate(final StringBuffer uniString, final int length) {
        uniString.setLength(length);
    }

    static void clear(final ReplaceableString uniString) {
        uniString.replace(0, uniString.length(), "");
    }

    static void remove(final StringBuffer uniString) {
        truncate(uniString, 0);
    }

    static void foldCase(final StringBuffer uniString) {
        String newString = UCharacter.foldCase(uniString.toString(), 0);
        uniString.replace(0, uniString.length(), newString);
    }

    static void setTo(final StringBuffer uniString, final String newValue) {
        uniString.replace(0, uniString.length(), newValue);
    }

    static char U16_LEAD(final int c) {
        assert Character.isSupplementaryCodePoint(c);
        return Character.highSurrogate(c);
    }

    static char U16_TRAIL(final int c) {
        assert Character.isSupplementaryCodePoint(c);
        return Character.lowSurrogate(c);
    }

    static boolean U16_IS_TRAIL(final int c) {
        return UCharacter.isLowSurrogate(c);
    }

    // TODO: Must check return
    static IndexAndChar U16_NEXT_UNSAFE(final char[] s, final int iOriginal) {
        // TODO: make iOriginal an iterator and remove Index part from the return value
        int i = iOriginal;
        int c = (s)[(i)++];
        if (U16_IS_LEAD(c)) {
            c = Math.toIntExact(U16_GET_SUPPLEMENTARY((c), (s)[(i)++]));
        }
        return new IndexAndChar(i, c);
    }

    static IndexAndChar U16_NEXT(final char[] s, final int iOriginal, final int length) {
        // TODO: make iOriginal an iterator and remove Index part from the return value
        int i = iOriginal;
        int c=(s)[(i)++];
        if(U16_IS_LEAD(c)) {
            /*uint16*/int __c2;
            if((i)!=(length) && U16_IS_TRAIL(__c2=(s)[(i)])) {
                ++(i);
                c = Math.toIntExact(U16_GET_SUPPLEMENTARY(c, __c2));
            }
        }
        return new IndexAndChar(i, c);
    }

    static long U16_GET_SUPPLEMENTARY(final int lead, final int trail) {
        return UCharacter.toCodePoint(lead, trail);
    }

    static boolean U16_IS_LEAD(final int c) {
        return UCharacter.isHighSurrogate(c);
    }

    static long utext_getNativeIndex(final StringWithOffset ut) {
        return ut.mapOffsetToNative();
    }

    static int U16_FWD_1(final char[] s, final int iOriginal, final int length) {
        // TODO: make iOriginal an iterator and remove return value from the method
        int i = iOriginal;
        if (U16_IS_LEAD((s)[i++]) && i != length && U16_IS_TRAIL((s)[i])) {
            return ++i;
        } else {
            return i;
        }
    }

    static boolean UTEXT_FULL_TEXT_IN_CHUNK(final StringWithOffset ut, final long len) {
        // TODO: Does this method ever return false?
        return len == ut.chunkNativeLimit && len == ut.nativeIndexingLimit;
    }

    static void utext_setNativeIndex(final StringWithOffset ut, final long ix) {
        utext_setNativeIndex(ut, Math.toIntExact(ix));
    }

    static void utext_setNativeIndex(final StringWithOffset ut, final int ix) {
        if (ut.targetString.isEmpty() && ix == 0) {
            ut.chunkOffset = 0;
            return;
        }
        try {
            int index = UCharacter.offsetByCodePoints(ut.targetString, ix, -1);
            ut.chunkOffset = UCharacter.offsetByCodePoints(ut.targetString, index, 1);
        } catch (final StringIndexOutOfBoundsException e) {
            int index = UCharacter.offsetByCodePoints(ut.targetString, ix, 1);
            ut.chunkOffset = UCharacter.offsetByCodePoints(ut.targetString, index, -1);
        }
    }

    static class IndexAndChar {
        final int i;
        final int c;

        IndexAndChar(final int i, final int c) {
            this.i = i;
            this.c = c;
        }
    }

    static class ArrayPointer<T> {
        private final T[] array;
        private int index;

        ArrayPointer(final T[] array, final int index) {
            //noinspection AssignmentOrReturnOfFieldWithMutableType
            this.array = array; // it is intended to use the array as is, so no .clone()
            this.index = index;
        }

        public T get() {
            return array[index];
        }

        public void next() {
            index++;
            if (index > array.length) {
                throw new ArrayIndexOutOfBoundsException(
                        "Pointer cannot point further than one point after the last item");
            }
        }
    }

    static class StringWithOffset {
        final String targetString;
        final long chunkNativeLimit;
        final int chunkLength;
        int chunkOffset;
        final int nativeIndexingLimit;

        StringWithOffset(final String targetString) {
            this.targetString = targetString;
            chunkLength = targetString.length();
            chunkNativeLimit = targetString.length();
            nativeIndexingLimit = targetString.length();
        }

        public long mapOffsetToNative() {
            return chunkOffset;
        }

        public boolean isEmpty() {
            return targetString.isEmpty();
        }

        public void getChars(final int srcBegin, final int srcEnd, final char[] dst, final int dstBegin) {
            targetString.getChars(srcBegin, srcEnd, dst, dstBegin);
        }

        @Override
        public int hashCode() {
            return targetString.hashCode();
        }

        @Override
        public boolean equals(final Object obj) {
            assert !(obj instanceof CharSequence);
            if (!(obj instanceof StringWithOffset)) {
                return false;
            }
            return targetString.equals(((StringWithOffset) obj).targetString);
        }
    }

    static int utext_next32From(final StringWithOffset ut, final long index) {
        utext_setNativeIndex(ut, Math.toIntExact(index));
        return utext_next32(ut);
    }

    static int UPRV_LENGTHOF(final char[] arr) {
        return arr.length;
    }

    static String utext_openUTF8(final byte[] arr, final int length) {
        int lengthNew = length;
        if (lengthNew == -1) {
            lengthNew = strlen(arr);
        } else {
            assert arr.length == lengthNew;
        }
        return new String(arr, 0, lengthNew, StandardCharsets.UTF_8);
    }
}
