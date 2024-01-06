package com.github.tarcv.u4jregex;

import static com.ibm.icu.lang.UCharacterEnums.ECharacterCategory.*;

// TODO: Remove this class?
class UChar {
    /**
     * U_GC_XX_MASK constants are bit flags corresponding to Unicode
     * general category values.
     * For each category, the nth bit is set if the numeric value of the
     * corresponding UCharCategory constant is n.
     * <p>
     * There are also some U_GC_Y_MASK constants for groups of general categories
     * like L for all letter categories.
     *
     * @stable ICU4C 2.1
     * @see u_charType
     * @see U_GET_GC_MASK
     * @see UCharCategory
     */
    public static final long GC_CN_MASK = U_MASK(GENERAL_OTHER_TYPES);

    /**
     * Mask constant for a UCharCategory. @stable ICU4C 2.1
     */
    public static final long GC_CF_MASK = U_MASK(FORMAT);

    /**
     * Mask constant for a UCharCategory. @stable ICU4C 2.1
     */
    public static final long GC_LU_MASK = U_MASK(UPPERCASE_LETTER);
    /**
     * Mask constant for a UCharCategory. @stable ICU4C 2.1
     */
    public static final long GC_LL_MASK = U_MASK(LOWERCASE_LETTER);
    /**
     * Mask constant for a UCharCategory. @stable ICU4C 2.1
     */
    public static final long GC_LT_MASK = U_MASK(TITLECASE_LETTER);
    /**
     * Mask constant for a UCharCategory. @stable ICU4C 2.1
     */
    public static final long GC_LM_MASK = U_MASK(MODIFIER_LETTER);
    /**
     * Mask constant for a UCharCategory. @stable ICU4C 2.1
     */
    public static final long GC_LO_MASK = U_MASK(OTHER_LETTER);

    /**
     * Mask constant for multiple UCharCategory bits (L Letters). @stable ICU4C 2.1
     */
    public static final long GC_L_MASK =
            (GC_LU_MASK | GC_LL_MASK | GC_LT_MASK | GC_LM_MASK | GC_LO_MASK);

    /**
     * Mask constant for a UCharCategory. @stable ICU4C 2.1
     */
    public static final long GC_MN_MASK = U_MASK(NON_SPACING_MARK);
    /**
     * Mask constant for a UCharCategory. @stable ICU4c 2.1
     */
    public static final long GC_ME_MASK = U_MASK(ENCLOSING_MARK);
    /**
     * Mask constant for a UCharCategory. @stable ICU4c 2.1
     */
    public static final long GC_MC_MASK = U_MASK(COMBINING_SPACING_MARK);

    /**
     * Mask constant for a UCharCategory. @stable ICU4c 2.1
     */
    public static final long GC_ND_MASK = U_MASK(DECIMAL_DIGIT_NUMBER);

    /**
     * Mask constant for a UCharCategory. @stable ICU4c 2.1
     */
    public static final long GC_NL_MASK = U_MASK(LETTER_NUMBER);

    /**
     * Mask constant for a UCharCategory. @stable ICU4c 2.1
     */
    public static final long GC_PC_MASK = U_MASK(CONNECTOR_PUNCTUATION);

    /**
     * Mask constant for a UCharCategory. @stable ICU4c 2.1
     */
    public static final long GC_SC_MASK = U_MASK(CURRENCY_SYMBOL);

    /**
     * Mask constant for a UCharCategory. @stable ICU4c 2.1
     */
    public static final long GC_ZS_MASK = U_MASK(SPACE_SEPARATOR);

    /**
     * Mask constant for a UCharCategory. @stable ICU4c 2.1
     */
    public static final long GC_ZL_MASK = U_MASK(LINE_SEPARATOR);
    /**
     * Mask constant for a UCharCategory. @stable ICU4c 2.1
     */
    public static final long GC_ZP_MASK = U_MASK(PARAGRAPH_SEPARATOR);

    /**
     * Mask constant for multiple UCharCategory bits (Z Separators). @stable ICU4c 2.1
     */
    public static final long GC_Z_MASK = (GC_ZS_MASK | GC_ZL_MASK | GC_ZP_MASK);

    /**
     * Get a single-bit bit set (a flag) from a bit number 0..31.
     *
     * @stable ICU4c 2.1
     */
    static long U_MASK(byte category) {
        return 1L << category;
    }
}
