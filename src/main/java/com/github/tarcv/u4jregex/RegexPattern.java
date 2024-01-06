// New code and changes are © 2024 TarCV
// © 2016 and later: Unicode, Inc. and others.
// License & terms of use: http://www.unicode.org/copyright.html
//
//
/*
 ***************************************************************************
 *   Copyright (C) 2002-2016 International Business Machines Corporation
 *   and others. All rights reserved.
 ***************************************************************************
 */
package com.github.tarcv.u4jregex;

import com.ibm.icu.text.UnicodeSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

import static com.github.tarcv.u4jregex.StartOfMatch.START_NO_INFO;
import static com.github.tarcv.u4jregex.UErrorCode.*;
import static com.github.tarcv.u4jregex.URegexpFlag.*;

/**
 * Class `RegexPattern` represents a compiled regular expression.  It includes
 * factory methods for creating a RegexPattern object from the source (string) form
 * of a regular expression, methods for creating RegexMatchers that allow the pattern
 * to be applied to input text, and a few convenience methods for simple common
 * uses of regular expressions.
 *
 * @since 74.2
 */
public final class RegexPattern {
    boolean isValid = true;

    //
    //  Implementation Data
    //

    /**
     * The original pattern string.
     */
    final Util.StringWithOffset fPattern;
    /**
     * The flags used when compiling the pattern.
     */
    final /* uint32 */ long        fFlags;
    /**
     * The compiled pattern p-code.
     */
    final MutableVector64 fCompiledPat;
    /**
     * Any literal string data from the pattern,
     * after un-escaping, for use during the match.
     */
    final StringBuffer fLiteralText;

    /**
     * Any UnicodeSets referenced from the pattern.
     */
    final ArrayList<UnicodeSet> fSets;

    /**
     * Minimum Match Length.  All matches will have length
     * >= this value.  For some patterns, this calculated
     * value may be less than the true shortest
     * possible match.
     */
    int         fMinMatchLen;

    /**
     * Size of a state stack frame in the
     * execution engine.
     */
    int         fFrameSize;

    /**
     * The size of the data needed by the pattern that
     * does not go on the state stack, but has just
     * a single copy per matcher.
     */
    int         fDataSize;

    /**
     * Map from capture group number to position of
     * the group's variables in the matcher stack frame.
     */
    final MutableVector32       fGroupMap;

    /**
     * Info on how a match must start.
     */
    StartOfMatch fStartType;
    int         fInitialStringIdx;     //
    int         fInitialStringLen;
    final UnicodeSet     fInitialChars;
    int         fInitialChar;
    boolean           fNeedsAltInput;

    /**
     * Map from capture group names to numbers.
     */
    HashMap<String, Long> fNamedCaptureMap;


    //--------------------------------------------------------------------------
//
//    RegexPattern    Default Constructor
//
//--------------------------------------------------------------------------
    private RegexPattern(final long fFlags, final String regex) {
        // Init all of this instance's data.
        this.fFlags            = fFlags;
        fLiteralText = new StringBuffer();
        fMinMatchLen      = 0;
        fFrameSize        = 0;
        fDataSize         = 0;
        fStartType        = START_NO_INFO;
        fInitialStringIdx = 0;
        fInitialStringLen = 0;
        fInitialChar      = 0;
        fNeedsAltInput    = false;
        fNamedCaptureMap  = null;

        fPattern          = new Util.StringWithOffset(regex);
        fCompiledPat      = new MutableVector64();
        fGroupMap         = new MutableVector32();
        fSets             = new ArrayList<>();
        fInitialChars     = new UnicodeSet();

        // Slot zero of the vector of sets is reserved.  Fill it here.
        fSets.add(null);
    }

    void initNamedCaptureMap() {
        if (fNamedCaptureMap != null) {
            return;
        }
        fNamedCaptureMap  = new HashMap<>(7);
    }

    /**
     * Comparison method.  Two RegexPattern objects are considered equal if they
     * were constructed from identical source patterns using the same #URegexpFlag
     * settings.
     * @param that a RegexPattern object to compare with "this".
     * @return true if the objects are equivalent.
     * @since 74.2
     */
@Override
    public boolean equals(final Object that) {
        if (!(that instanceof RegexPattern)) { return false; }
        final RegexPattern other = (RegexPattern) that;
        if (this.fFlags == other.fFlags) {
            if (this.fPattern == null) {
                if (other.fPattern == null) {
                    return true;
                }
            } else if (other.fPattern != null) {
                return this.fPattern.equals(other.fPattern);
            }
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fFlags, fPattern);
    }

    /**
     * Compiles the regular expression in string form into a RegexPattern
     * object using the specified #URegexpFlag match mode flags.  These compile methods,
     * rather than the constructors, are the usual way that RegexPattern objects
     * are created.
     * <p>
     * Note that it is often more convenient to construct a RegexMatcher directly
     *    from a pattern string instead of than separately compiling the pattern and
     *    then creating a RegexMatcher object from the pattern.
     *
     * @param regex The regular expression to be compiled.
     * @param flags The {@link URegexpFlag} match mode flags to be used, e.g. {@link URegexpFlag#UREGEX_CASE_INSENSITIVE}.
     * @return      A regexPattern object for the compiled pattern.
     *
     * @since 74.2
     */
    public static RegexPattern compile(final String                regex,
                          /* uint32 */final long             flags)
    {
    final /* uint32 */long allFlags = UREGEX_CANON_EQ.flag | UREGEX_CASE_INSENSITIVE.flag | UREGEX_COMMENTS.flag |
            UREGEX_DOTALL.flag   | UREGEX_MULTILINE.flag        | UREGEX_UWORD.flag |
            UREGEX_ERROR_ON_UNKNOWN_ESCAPES.flag           | UREGEX_UNIX_LINES.flag | UREGEX_LITERAL.flag;

        if ((flags & ~allFlags) != 0) {
            throw new UErrorException(U_REGEX_INVALID_FLAG);
        }

        if ((flags & UREGEX_CANON_EQ.flag) != 0) {
            throw new UErrorException(U_REGEX_UNIMPLEMENTED);
        }

        RegexPattern This = new RegexPattern(flags, regex);
        RegexCompile.compile(This, regex);

        return This;
    }

    /**
     * Compiles the regular expression in string form into a RegexPattern
     * object.  These compile methods, rather than the constructors, are the usual
     * way that RegexPattern objects are created.
     * <p>
     * All #URegexpFlag pattern match mode flags are set to their default values.
     * <p>
     * Note that it is often more convenient to construct a RegexMatcher directly
     *    from a pattern string rather than separately compiling the pattern and
     *    then creating a RegexMatcher object from the pattern.
     *
     * @param regex The regular expression to be compiled.
     * @return      A regexPattern object for the compiled pattern.
     *
     * @since 74.2
     */
public static RegexPattern compile(final String               regex)
    {
        return compile(regex, 0);
    }


/**
 * flags
 */
public /* uint32 */ long flags() {
        return fFlags;
    }


    /**
     * Creates a RegexMatcher that will match the given input against this pattern.  The
     * RegexMatcher can then be used to perform match, find or replace operations
     * on the input.
     * <p>
     * The matcher will retain a reference to the supplied input string, and all regexp
     * pattern matching operations happen directly on this original string.
     *
     * @param input    The input string to which the regular expression will be applied.
     * @return         A RegexMatcher object for this pattern and input.
     *
     * @since 74.2
     */
    public RegexMatcher matcher(final String input)   {
        checkValid();
        RegexMatcher    retMatcher = matcher();
        if (retMatcher != null) {
            retMatcher.reset(input);
        }
        return retMatcher;
    }


    /**
     * Creates a RegexMatcher that will match against this pattern.  The
     * RegexMatcher can be used to perform match, find or replace operations.
     * Note that a RegexPattern object must not be deleted while
     * RegexMatchers created from it still exist and might possibly be used again.
     *
     * @return      A RegexMatcher object for this pattern and input.
     *
     * @since 74.2
     */
    public RegexMatcher matcher()   {
        checkValid();
        return new RegexMatcher(this);
    }



    /**
     * Test whether a string matches a regular expression.  This convenience function
     * both compiles the regular expression and applies it in a single operation.
     * Note that if the same pattern needs to be applied repeatedly, this method will be
     * less efficient than creating and reusing a RegexMatcher object.
     *
     * @param regex The regular expression
     * @param input The string data to be matched
     * @return True if the regular expression exactly matches the full input string.
     *
     * @since 74.2
     */
    public static boolean matches(final String                regex,
                                  final String           input) {

        RegexPattern pat     = null;
        RegexMatcher matcher = null;

        pat     = compile(regex, 0);
        matcher = pat.matcher();
        matcher.reset(input);

        return matcher.matches();
    }

    /**
     * Returns the regular expression from which this pattern was compiled.
     * @since 74.2
     */
    public String pattern()  {
        if (fPattern != null) {
            return fPattern.targetString;
        } else {
            return RegexStaticSets.INSTANCE.fEmptyText;
        }
    }


    /**
     * Get the group number corresponding to a named capture group.
     * The returned number can be used with any function that access
     * capture groups by number.
     * <p>
     * The function returns an error status if the specified name does not
     * appear in the pattern.
     *
     * @param  groupName   The capture group name.
     *
     * @since 74.2
     */
    public int groupNumberFromName(final String groupName)  {
        checkValid();

        // No need to explicitly check for syntactically valid names.
        // Invalid ones will never be in the map, and the lookup will fail.
        Long number = fNamedCaptureMap != null ? fNamedCaptureMap.get(groupName) : null;
        if (number == null || number == 0) {
            throw new UErrorException(U_REGEX_INVALID_CAPTURE_GROUP_NAME);
        }
        return Math.toIntExact(number);
    }

    private void checkValid() {
        if (!isValid) {
            throw new IllegalStateException();
        }
    }

    /**
     * Split a string into fields.
     * Pattern matches identify delimiters that separate the input
     * into fields.  The input data between the delimiters becomes the
     * fields themselves.
     * <p>
     * If the delimiter pattern includes capture groups, the captured text will
     * also appear in the destination array of output strings, interspersed
     * with the fields.  This is similar to Perl, but differs from Java,
     * which ignores the presence of capture groups in the pattern.
     * <p>
     * Trailing empty fields will always be returned, assuming sufficient
     * destination capacity.  This differs from the default behavior for Java
     * and Perl where trailing empty fields are not returned.
     * <p>
     * The number of strings produced by the split operation is returned.
     * This count includes the strings from capture groups in the delimiter pattern.
     * This behavior differs from Java, which ignores capture groups.
     * <p>
     * For the best performance on split() operations,
     * <code>RegexMatcher::split</code> is preferable to this function
     *
     * @param input   The string to be split into fields.  The field delimiters
     *                match the pattern (in the "this" object)
     * @param dest    An array of Strings to receive the results of the split.
     * @param destCapacity  The number of elements in the destination array.
     *                If the number of fields found is less than destCapacity, the
     *                extra strings in the destination array are not altered.
     *                If the number of destination strings is less than the number
     *                of fields, the trailing part of the input string, including any
     *                field delimiters, is placed in the last destination string.
     * @return        The number of fields into which the input string was split.
     * @since 74.2
     */
    public int split(final String input,
              final String[] dest,
              final int          destCapacity)
    {
        RegexMatcher  m = new RegexMatcher(this);

        // Check m's status to make sure all is ok.
        return m.split(input, dest, destCapacity);
    }
}
