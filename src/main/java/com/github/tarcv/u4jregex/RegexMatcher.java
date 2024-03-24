// New code and changes are © 2024 TarCV
// © 2016 and later: Unicode, Inc. and others.
// License & terms of use: http://www.unicode.org/copyright.html
/*
 **************************************************************************
 *   Copyright (C) 2002-2016 International Business Machines Corporation
 *   and others. All rights reserved.
 **************************************************************************
 */
package com.github.tarcv.u4jregex;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.text.BreakIterator;
import com.ibm.icu.text.ReplaceableString;
import com.ibm.icu.text.UnicodeSet;
import com.ibm.icu.util.ULocale;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static com.github.tarcv.u4jregex.RegexImp.RESTACKFRAME_HDRCOUNT;
import static com.github.tarcv.u4jregex.URX.*;
import static com.github.tarcv.u4jregex.URegexpFlag.UREGEX_UNIX_LINES;
import static com.github.tarcv.u4jregex.UrxOps.*;
import static com.github.tarcv.u4jregex.Utf16Util.*;
import static com.github.tarcv.u4jregex.Util.*;
import static com.ibm.icu.lang.UCharacter.FOLD_CASE_DEFAULT;
import static com.ibm.icu.lang.UCharacterEnums.ECharacterCategory.*;
import static com.ibm.icu.lang.UProperty.GRAPHEME_EXTEND;

/**
 * class RegexMatcher bundles together a regular expression pattern and
 * input text to which the expression can be applied.  It includes methods
 * for testing for matches, and for find and replace operations.
 *
 * @since 74.2
 */
public final class RegexMatcher {
    /**
     * Default limit for the size of the back track stack, to avoid system
     * failures causedby heap exhaustion.  Units are in 32 bit words, not bytes.
     * This value puts this library limits higher than most other regexp implementations,
     * which use recursion rather than the heap, and take more storage per
     * backtrack point.
     */
    private static final int DEFAULT_BACKTRACK_STACK_CAPACITY = 8000000;

    /**
     * Time limit counter constant.
     * Time limits for expression evaluation are in terms of quanta of work by
     * the engine, each of which is 10,000 state saves.
     * This constant determines that state saves per tick number.
     */
    private static final int TIMER_INITIAL_VALUE = 10000;

    private final RegexPattern fPattern;

    /**
     * The text being matched. Is never nullptr.
     */
    private StringWithOffset fInputText;
    /**
     * A shallow copy of the text being matched.
     *  Only created if the pattern contains backreferences.
     */
    private StringWithOffset fAltInputText;
    /**
     * Full length of the input text.
     */
    private long fInputLength;
    /**
     * The size of a frame in the backtrack stack.
     */
    private int fFrameSize;

    /**
     * Start of the input region, default = 0.
     */
    private long fRegionStart;
    /**
     * End of input region, default to input.length.
     */
    private long fRegionLimit;

    /**
     * Region bounds for anchoring operations (^ or $).
     */
    private long fAnchorStart;
    /**
     * See useAnchoringBounds
     */
    private long fAnchorLimit;

    /**
     * Region bounds for look-ahead/behind and
     * and other boundary tests.  See
     * useTransparentBounds
     */
    private long fLookStart, fLookLimit;
    /**
     * Currently active bounds for matching.
     */
    private long fActiveStart;
    /**
     * Usually is the same as region, but
     * is changed to fLookStart/Limit when
     * entering look around regions.
     */
    private long fActiveLimit;

    /**
     * True if using transparent bounds.
     */
    private boolean fTransparentBounds;
    /**
     * True if using anchoring bounds.
     */
    private boolean fAnchoringBounds;

    /**
     * True if the last attempted match was successful.
     */
    private boolean fMatch;
    /**
     * Position of the start of the most recent match
     */
    private long fMatchStart;
    /**
     * First position after the end of the most recent match
     * Zero if no previous match, even when a region
     * is active.
     */
    private long fMatchEnd;
    /**
     * First position after the end of the previous match,
     * or -1 if there was no previous match.
     */
    private long fLastMatchEnd;
    /**
     * First position after the end of the previous
     * appendReplacement().  As described by the
     * JavaDoc for Java Matcher, where it is called
     * "append position"
     */
    private long fAppendPosition;
    /**
     * True if the last match touched the end of input.
     */
    private boolean fHitEnd;
    /**
     * True if the last match required end-of-input
     * (matched $ or Z)
     */
    private boolean fRequireEnd;

    private MutableVector64 fStack;
    /**
     * After finding a match, the last active stack frame,
     * which will contain the capture group results.
     * NOT valid while match engine is running.
     */
    private REStackFrame fFrame;

    /**
     * Data area for use by the compiled pattern.
     */
    private long[] fData;
    /**
     * Use this for data if it's enough.
     */
    private final long[] fSmallData = new long[8];

    /**
     * Max time (in arbitrary steps) to let the
     * match engine run.  Zero for unlimited.
     */
    private int fTimeLimit;

    /**
     * Match time, accumulates while matching.
     */
    private int fTime;
    /**
     * Low bits counter for time.  Counts down StateSaves.
     * Kept separately from fTime to keep as much
     * code as possible out of the inline
     * StateSave function.
     */
    private int fTickCounter;

    /**
     * Maximum memory size to use for the backtrack
     * stack, in bytes.  Zero for unlimited.
     */
    private int fStackLimit;

    /**
     * Pointer to match progress callback funct.
     * nullptr if there is no callback.
     */
    private URegexMatchCallback fCallbackFn;
    /**
     * User Context ptr for callback function.
     */
    private Object fCallbackContext;

    /**
     * Pointer to match progress callback funct.
     * nullptr if there is no callback.
     */
    private URegexFindProgressCallback fFindProgressCallbackFn;
    /**
     * User Context ptr for callback function.
     */
    private Object fFindProgressCallbackContext;

    private BreakIterator fWordBreakItr;
    private BreakIterator fGCBreakItr;

    /**
     * Test for any of the Unicode line terminating characters.
     */
    private static boolean isLineTerminator(final int c) {
        if ((c & ~(0x0a | 0x0b | 0x0c | 0x0d | 0x85 | 0x2028 | 0x2029)) != 0) {
            return false;
        }
        return (c <= 0x0d && c >= 0x0a) || c == 0x85 || c == 0x2028 || c == 0x2029;
    }

    /**
     * Construct a RegexMatcher for a regular expression.
     * This is a convenience method that avoids the need to explicitly create
     * a RegexPattern object.  Note that if several RegexMatchers need to be
     * created for the same expression, it will be more efficient to
     * separately create and cache a RegexPattern object, and use
     * its matcher() method to create the RegexMatcher objects.
     * <p>
     * The matcher will retain a reference to the supplied input string, and all regexp
     * pattern matching operations happen directly on the original string.
     *
     *  @param regexp The Regular Expression to be compiled.
     *  @param input  The string to match.  The matcher retains a reference to the
     *                caller's string; mo copy is made.
     *  @param flags  #URegexpFlag options, such as #UREGEX_CASE_INSENSITIVE.
     *  @since 74.2
     */
    public RegexMatcher(final String regexp, final String input,
            final Collection<URegexpFlag> flags) {
        this(RegexPattern.compile(regexp, flags), input);
    }

    /**
     * Construct a RegexMatcher for a regular expression.
     * This is a convenience method that avoids the need to explicitly create
     * a RegexPattern object.  Note that if several RegexMatchers need to be
     * created for the same expression, it will be more efficient to
     * separately create and cache a RegexPattern object, and use
     * its matcher() method to create the RegexMatcher objects.
     *
     *  @param regexp The Regular Expression to be compiled.
     *  @param flags  {@link URegexpFlag} options, such as {@link URegexpFlag#UREGEX_CASE_INSENSITIVE}.
     *  @since 74.2
     */
    public RegexMatcher(final String regexp, final Collection<URegexpFlag> flags) {
        this(RegexPattern.compile(regexp, flags), RegexStaticSets.INSTANCE.fEmptyText);
    }


    RegexMatcher(final RegexPattern fPattern) {
        this(fPattern, RegexStaticSets.INSTANCE.fEmptyText);
    }

    private RegexMatcher(final RegexPattern fPattern, final String input) {
        if (fPattern == null) {
            throw new IllegalArgumentException();
        }
//
//   init()   common initialization for use by all constructors.
//            Initialize all fields, get the object into a consistent state.
//            This must be done even when the initial status shows an error,
//            so that the object is initialized sufficiently well for the destructor
//            to run safely.
//
        this.fPattern = fPattern;
        fFrameSize = 0;
        fRegionStart = 0;
        fRegionLimit = 0;
        fAnchorStart = 0;
        fAnchorLimit = 0;
        fLookStart = 0;
        fLookLimit = 0;
        fActiveStart = 0;
        fActiveLimit = 0;
        fTransparentBounds = false;
        fAnchoringBounds = true;
        fMatch = false;
        fMatchStart = 0;
        fMatchEnd = 0;
        fLastMatchEnd = -1;
        fAppendPosition = 0;
        fHitEnd = false;
        fRequireEnd = false;
        fStack = null;
        fFrame = null;
        fTimeLimit = 0;
        fTime = 0;
        fTickCounter = 0;
        fStackLimit = DEFAULT_BACKTRACK_STACK_CAPACITY;
        fCallbackFn = null;
        fCallbackContext = null;
        fFindProgressCallbackFn = null;
        fFindProgressCallbackContext = null;
        fData = fSmallData;
        fWordBreakItr = null;
        fGCBreakItr = null;

        fStack = null;
        fInputText = null;
        fAltInputText = null;
        fInputLength = 0;

//
//  init2()   Common initialization for use by RegexMatcher constructors, part 2.
//            This handles the common setup to be done after the Pattern is available.
//
        if (fPattern.fDataSize > fSmallData.length) {
            fData = new long[fPattern.fDataSize];
        }

        fStack = new MutableVector64(() -> new UErrorException(UErrorCode.U_REGEX_STACK_OVERFLOW, null));

        reset(input);
        setStackLimit(DEFAULT_BACKTRACK_STACK_CAPACITY);
    }


    private static final char BACKSLASH = 0x5c;
    private static final char DOLLARSIGN = 0x24;
    private static final char LEFTBRACKET = 0x7b;
    private static final char RIGHTBRACKET = 0x7d;

    /**
     *   Implements a replace operation intended to be used as part of an
     *   incremental find-and-replace.
     * <p>
     *   The input string, starting from the end of the previous replacement and ending at
     *   the start of the current match, is appended to the destination string.  Then the
     *   replacement string is appended to the output string,
     *   including handling any substitutions of captured text.
     * <p>
     *   For simple, prepackaged, non-incremental find-and-replace
     *   operations, see {@link #replaceFirst(String)} or {@link #replaceAll(String)}.
     *
     *   @param   dest        A ReplaceableString to which the results of the find-and-replace are appended.
     *   @param   replacement A String that provides the text to be substituted for
     *                        the input text that matched the regexp pattern.  The replacement
     *                        text may contain references to captured text from the
     *                        input.
     *   @throws IllegalStateException if no match has been
     *                        attempted or the last match failed
     *   @throws IndexOutOfBoundsException if the replacement text specifies a capture group that
     *                        does not exist in the pattern.
     *
     *   @return  this  RegexMatcher
     *   @since 74.2
     *
     */
    public RegexMatcher appendReplacement(final ReplaceableString dest,
                                   final String replacement) {

        StringWithOffset replacementText = new StringWithOffset(replacement);
        appendReplacement(dest, replacementText);

        return this;
    }

    private void appendReplacement(final ReplaceableString dest, final StringWithOffset replacement) {
        if (fMatch == false) {
            throw new UErrorException(UErrorCode.U_REGEX_INVALID_STATE);
        }

        // Copy input string from the end of previous match to start of current match
        long destLen = utext_nativeLength(dest.toString());
        if (fMatchStart > fAppendPosition) {
            if (UTEXT_FULL_TEXT_IN_CHUNK(fInputText, fInputLength)) {
                destLen += utext_replace(dest, destLen, destLen, fInputText.targetString.toCharArray(), Math.toIntExact(fAppendPosition), Math.toIntExact((fMatchStart - fAppendPosition)));
            } else {
                int len16 = Math.toIntExact((fMatchStart - fAppendPosition));
                char[] inputChars = new char[len16 + 1];
                utext_extract(fInputText.targetString, fAppendPosition, fMatchStart, inputChars, len16 + 1);
                destLen += utext_replace(dest, destLen, destLen, inputChars, len16);
            }
        }
        fAppendPosition = fMatchEnd;


        // scan the replacement text, looking for substitutions ($n) and \escapes.
        //  TODO:  optimize this loop by efficiently scanning for '$' or '\',
        //         move entire ranges not containing substitutions.
        Util.utext_setNativeIndex(replacement, 0);
        for (int c = Util.utext_next32(replacement); c != U_SENTINEL; c = Util.utext_next32(replacement)) {
            if (c == BACKSLASH) {
                // Backslash Escape.  Copy the following char out without further checks.
                //                    Note:  Surrogate pairs don't need any special handling
                //                           The second half wont be a '$' or a '\', and
                //                           will move to the dest normally on the next
                //                           loop iteration.
                c = Util.utext_current32(replacement);
                if (c == U_SENTINEL) {
                    break;
                }

                if (c == 0x55/*U*/ || c == 0x75/*u*/) {
                    // We have a \udddd or \Udddddddd escape sequence.
                    int offset = 0;
                    CodeAndOffset codeAndOffset = u_unescapeAt(offset, replacement);
                    int escapedChar = codeAndOffset.code;
                    offset = codeAndOffset.newOffset;
                    if (escapedChar != -1) {
                        if (UCharacter.isBMP(escapedChar)) {
                            char[] c16 = new char[]{Util.toCharExact(escapedChar)};
                            destLen += utext_replace(dest, destLen, destLen, c16, 1);
                        } else {
                            char[] surrogate = new char[2];
                            surrogate[0] = U16_LEAD(escapedChar);
                            surrogate[1] = U16_TRAIL(escapedChar);
                            destLen += utext_replace(dest, destLen, destLen, surrogate, 2);
                        }
                        utext_moveIndex32(replacement, offset);
                    }
                } else {
                    Util.utext_next32(replacement);
                    // Plain backslash escape.  Just put out the escaped character.
                    if (UCharacter.isBMP(c)) {
                        char[] c16 = new char[]{Util.toCharExact(c)};
                        destLen += utext_replace(dest, destLen, destLen, c16, 1);
                    } else {
                        char[] surrogate = new char[2];
                        surrogate[0] = U16_LEAD(c);
                        surrogate[1] = U16_TRAIL(c);
                        destLen += utext_replace(dest, destLen, destLen, surrogate, 2);
                    }
                }
            } else if (c != DOLLARSIGN) {
                // Normal char, not a $.  Copy it out without further checks.
                if (UCharacter.isBMP(c)) {
                    char[] c16 = new char[]{Util.toCharExact(c)};
                    destLen += utext_replace(dest, destLen, destLen, c16, 1);
                } else {
                    char[] surrogate = new char[2];
                    surrogate[0] = U16_LEAD(c);
                    surrogate[1] = U16_TRAIL(c);
                    destLen += utext_replace(dest, destLen, destLen, surrogate, 2);
                }
            } else {
                // We've got a $.  Pick up a capture group name or number if one follows.
                // Consume digits so long as the resulting group number <= the number of
                // number of capture groups in the pattern.

                int groupNum = 0;
                int numDigits = 0;
                int nextChar = utext_current32(replacement);
                if (nextChar == LEFTBRACKET) {
                    // Scan for a Named Capture Group, ${name}.
                    StringBuilder groupName = new StringBuilder();
                    utext_next32(replacement);
                    while (nextChar != RIGHTBRACKET) {
                        nextChar = utext_next32(replacement);
                        if (nextChar == U_SENTINEL) {
                            throw new UErrorException(UErrorCode.U_REGEX_INVALID_CAPTURE_GROUP_NAME);
                        } else if ((nextChar >= 0x41 && nextChar <= 0x5a) ||       // A..Z
                                (nextChar >= 0x61 && nextChar <= 0x7a) ||       // a..z
                                (nextChar >= 0x31 && nextChar <= 0x39)) {       // 0..9
                            groupName.appendCodePoint(nextChar);
                        } else if (nextChar == RIGHTBRACKET) {
                            Long num = fPattern.fNamedCaptureMap != null ? fPattern.fNamedCaptureMap.get(groupName.toString()) : null;
                            if (num == null || num == 0) {
                                throw new UErrorException(UErrorCode.U_REGEX_INVALID_CAPTURE_GROUP_NAME);
                            }
                            groupNum = Math.toIntExact(num);
                        } else {
                            // Character was something other than a name char or a closing '}'
                            throw new UErrorException(UErrorCode.U_REGEX_INVALID_CAPTURE_GROUP_NAME);
                        }
                    }

                } else if (UCharacter.isDigit(nextChar)) {
                    // $n    Scan for a capture group number
                    int numCaptureGroups = fPattern.fGroupMap.size();
                    for (; ; ) {
                        nextChar = Util.utext_current32(replacement);
                        if (nextChar == U_SENTINEL) {
                            break;
                        }
                        if (UCharacter.isDigit(nextChar) == false) {
                            break;
                        }
                        int nextDigitVal = UCharacter.digit(nextChar);
                        if (groupNum * 10 + nextDigitVal > numCaptureGroups) {
                            // Don't consume the next digit if it makes the capture group number too big.
                            if (numDigits == 0) {
                                throw new IndexOutOfBoundsException();
                            }
                            break;
                        }
                        Util.utext_next32(replacement);
                        groupNum = groupNum * 10 + nextDigitVal;
                        ++numDigits;
                    }
                } else {
                    // $ not followed by capture group name or number.
                    throw new UErrorException(UErrorCode.U_REGEX_INVALID_CAPTURE_GROUP_NAME);
                }

                destLen += appendGroup(groupNum, dest);
            }  // End of $ capture group handling
        }  // End of per-character loop through the replacement string.

    }

    /**
     * As the final step in a find-and-replace operation, append the remainder
     * of the input string, starting at the position following the last appendReplacement(),
     * to the destination string. `appendTail()` is intended to be invoked after one
     * or more invocations of the {@link #appendReplacement(ReplaceableString, String)}.
     *
     *  @param dest A ReplaceableString to which the results of the find-and-replace are appended.
     *               Must not be nullptr.
     *  @return  the destination string.
     *
     *  @since 74.2
     */
    public ReplaceableString appendTail(final ReplaceableString dest) {

        if (fInputLength > fAppendPosition) {
            if (UTEXT_FULL_TEXT_IN_CHUNK(fInputText, fInputLength)) {
                long destLen = utext_nativeLength(dest.toString());
                utext_replace(dest, destLen, destLen, fInputText.targetString.toCharArray(), Math.toIntExact(fAppendPosition),
                        Math.toIntExact(fInputLength - fAppendPosition));
            } else {
                int len16;
                len16 = Math.toIntExact(fInputLength - fAppendPosition);

                char[] inputChars = new char[len16];
                utext_extract(fInputText.targetString, fAppendPosition, fInputLength, inputChars, len16); // unterminated
                long destLen = utext_nativeLength(dest.toString());
                utext_replace(dest, destLen, destLen, inputChars, len16);
            }
        }
        return dest;
    }


    /**
     *    Returns the index in the input string of the first character following the
     *    text matched during the previous match operation.
     *
     *   @throws IllegalStateException if no match has been
     *                        attempted or the last match failed.
     *    @return the index of the last character matched, plus one.
     *                        The index value returned is a native index, corresponding to
     *                        code units for the underlying encoding type, for example,
     *                        a byte index for UTF-8.
     *   @since 74.2
     */
    public int end() {
        return end(0);
    }

    /**
     *    Returns the index in the input string of the first character following the
     *    text matched during the previous match operation.
     *
     *   @throws IllegalStateException if no match has been
     *                        attempted or the last match failed.
     *    @return the index of the last character matched, plus one.
     *                        The index value returned is a native index, corresponding to
     *                        code units for the underlying encoding type, for example,
     *                        a byte index for UTF-8.
     *   @since 74.2
     */
    public long end64() {
        return end64(0);
    }

    /**
     *    Returns the index in the input string of the character following the
     *    text matched by the specified capture group during the previous match operation.
     *
     *    @param group  the capture group number
     *    @throws IllegalStateException if no match has been
     *                        attempted or the last match failed and
     *    @throws IndexOutOfBoundsException for a bad capture group number
     *    @return  the index of the first character following the text
     *              captured by the specified group during the previous match operation.
     *              Return -1 if the capture group exists in the pattern but was not part of the match.
     *              The index value returned is a native index, corresponding to
     *              code units for the underlying encoding type, for example,
     *              a byte index for UTF8.
     *   @since 74.2
     */
    public long end64(final int group) {
        if (!fMatch) {
            throw new IllegalStateException();
        }
        if (group < 0 || group > fPattern.fGroupMap.size()) {
            throw new IndexOutOfBoundsException();
        }
        long e = -1;
        if (group == 0) {
            e = fMatchEnd;
        } else {
            // Get the position within the stack frame of the variables for
            //    this capture group.
            int groupOffset = fPattern.fGroupMap.elementAti(group - 1);
            assert (groupOffset < fPattern.fFrameSize);
            assert (groupOffset >= 0);
            e = fFrame.fExtra(groupOffset + 1);
        }

        return e;
    }

    /**
     *    Returns the index in the input string of the character following the
     *    text matched by the specified capture group during the previous match operation.
     *
     *    @param group  the capture group number
     *    @throws IllegalStateException if no match has been
     *                        attempted or the last match failed and
     *    @throws IndexOutOfBoundsException for a bad capture group number
     *    @return  the index of the first character following the text
     *              captured by the specified group during the previous match operation.
     *              Return -1 if the capture group exists in the pattern but was not part of the match.
     *              The index value returned is a native index, corresponding to
     *              code units for the underlying encoding type, for example,
     *              a byte index for UTF8.
     *    @since 74.2
     */
    public int end(final int group) {
        return Math.toIntExact(end64(group));
    }

    /**
     * findProgressInterrupt  This function is called once for each advance in the target
     * string from the find() function, and calls the user progress callback
     * function if there is one installed.
     * @return true if the find operation is to be terminated.
     * false if the find operation is to continue running.
     */
    private boolean findProgressInterrupt(final long pos) {
        if (fFindProgressCallbackFn != null && !(fFindProgressCallbackFn.onProgress(fFindProgressCallbackContext, pos))) {
            throw new UErrorException(UErrorCode.U_REGEX_STOPPED_BY_CALLER);
//        return true;
        }
        return false;
    }

    /**
     *  Find the next pattern match in the input string.
     *  The find begins searching the input at the location following the end of
     *  the previous match, or at the start of the string if there is no previous match.
     *  If a match is found, {@link #start()}, {@link #end()} and {@link #group()}
     *  will provide more information regarding the match.
     *  Note that if the input string is changed by the application,
     *     use {@link #find(startPos)} instead of {@link #find()}, because the saved starting
     *     position may not be valid with the altered input string.
     *  @return  true if a match is found.
     *  @since 74.2
     */
    public boolean find() {
        // Start at the position of the last match end.  (Will be zero if the
        //   matcher has been reset.)
        //

        if (UTEXT_FULL_TEXT_IN_CHUNK(fInputText, fInputLength)) {
            return findUsingChunk();
        }

        long startPos = fMatchEnd;
        if (startPos == 0) {
            startPos = fActiveStart;
        }

        if (fMatch) {
            // Save the position of any previous successful match.
            fLastMatchEnd = fMatchEnd;

            if (fMatchStart == fMatchEnd) {
                // Previous match had zero length.  Move start position up one position
                //  to avoid sending find() into a loop on zero-length matches.
                if (startPos >= fActiveLimit) {
                    fMatch = false;
                    fHitEnd = true;
                    return false;
                }
                Util.utext_setNativeIndex(fInputText, Math.toIntExact(startPos));
                Util.utext_next32(fInputText);
                startPos = Util.utext_getNativeIndex(fInputText);
            }
        } else {
            if (fLastMatchEnd >= 0) {
                // A previous find() failed to match.  Don't try again.
                //   (without this test, a pattern with a zero-length match
                //    could match again at the end of an input string.)
                fHitEnd = true;
                return false;
            }
        }


        // Compute the position in the input string beyond which a match can not begin, because
        //   the minimum length match would extend past the end of the input.
        //   Note:  some patterns that cannot match anything will have fMinMatchLength==Max Int.
        //          Be aware of possible overflows if making changes here.
        long testStartLimit;
        testStartLimit = fActiveLimit - fPattern.fMinMatchLen;
        if (startPos > testStartLimit) {
            fMatch = false;
            fHitEnd = true;
            return false;
        }

        int c;
        assert (startPos >= 0);

        switch (fPattern.fStartType) {
            case START_NO_INFO:
                // No optimization was found.
                //  Try a match at each input position.
                for (; ; ) {
                    MatchAt(startPos, false);
                    if (fMatch) {
                        return true;
                    }
                    if (startPos >= testStartLimit) {
                        fHitEnd = true;
                        return false;
                    }
                    Util.utext_setNativeIndex(fInputText, Math.toIntExact(startPos));
                    Util.utext_next32(fInputText);
                    startPos = Util.utext_getNativeIndex(fInputText);
                    // Note that it's perfectly OK for a pattern to have a zero-length
                    //   match at the end of a string, so we must make sure that the loop
                    //   runs with startPos == testStartLimit the last time through.
                    if (findProgressInterrupt(startPos))
                        return false;
                }
//                throw new IllegalStateException();

            case START_START:
                // Matches are only possible at the start of the input string
                //   (pattern begins with ^ or \A)
                if (startPos > fActiveStart) {
                    fMatch = false;
                    return false;
                }
                MatchAt(startPos, false);
                return fMatch;


            case START_SET: {
                // Match may start on any char from a pre-computed set.
                assert (fPattern.fMinMatchLen > 0);
                Util.utext_setNativeIndex(fInputText, startPos);
                for (; ; ) {
                    long pos = startPos;
                    c = Util.utext_next32(fInputText);
                    startPos = Util.utext_getNativeIndex(fInputText);
                    // c will be -1 (U_SENTINEL) at end of text, in which case we
                    // skip this next block (so we don't have a negative array index)
                    // and handle end of text in the following block.
                    if (c >= 0 && fPattern.fInitialChars.contains(c)) {
                        MatchAt(pos, false);
                        if (fMatch) {
                            return true;
                        }
                        Util.utext_setNativeIndex(fInputText, pos);
                    }
                    if (startPos > testStartLimit) {
                        fMatch = false;
                        fHitEnd = true;
                        return false;
                    }
                    if (findProgressInterrupt(startPos))
                        return false;
                }
            }
//            throw new IllegalStateException();

            case START_STRING:
            case START_CHAR: {
                // Match starts on exactly one char.
                assert (fPattern.fMinMatchLen > 0);
                int theChar = fPattern.fInitialChar;
                Util.utext_setNativeIndex(fInputText, startPos);
                for (; ; ) {
                    long pos = startPos;
                    c = Util.utext_next32(fInputText);
                    startPos = Util.utext_getNativeIndex(fInputText);
                    if (c == theChar) {
                        MatchAt(pos, false);
                        if (fMatch) {
                            return true;
                        }
                        Util.utext_setNativeIndex(fInputText, startPos);
                    }
                    if (startPos > testStartLimit) {
                        fMatch = false;
                        fHitEnd = true;
                        return false;
                    }
                    if (findProgressInterrupt(startPos))
                        return false;
                }
            }
//            throw new IllegalStateException();

            case START_LINE: {
                int ch;
                if (startPos == fAnchorStart) {
                    MatchAt(startPos, false);
                    if (fMatch) {
                        return true;
                    }
                    Util.utext_setNativeIndex(fInputText, startPos);
                    ch = Util.utext_next32(fInputText);
                    startPos = Util.utext_getNativeIndex(fInputText);
                } else {
                    Util.utext_setNativeIndex(fInputText, startPos);
                    ch = Util.utext_previous32(fInputText);
                    Util.utext_setNativeIndex(fInputText, startPos);
                }

                if (fPattern.fFlags.contains(UREGEX_UNIX_LINES)) {
                    for (; ; ) {
                        if (ch == 0x0a) {
                            MatchAt(startPos, false);
                            if (fMatch) {
                                return true;
                            }
                            Util.utext_setNativeIndex(fInputText, startPos);
                        }
                        if (startPos >= testStartLimit) {
                            fMatch = false;
                            fHitEnd = true;
                            return false;
                        }
                        ch = Util.utext_next32(fInputText);
                        startPos = Util.utext_getNativeIndex(fInputText);
                        // Note that it's perfectly OK for a pattern to have a zero-length
                        //   match at the end of a string, so we must make sure that the loop
                        //   runs with startPos == testStartLimit the last time through.
                        if (findProgressInterrupt(startPos))
                            return false;
                    }
                } else {
                    for (; ; ) {
                        if (isLineTerminator(ch)) {
                            if (ch == 0x0d && startPos < fActiveLimit && Util.utext_current32(fInputText) == 0x0a) {
                                Util.utext_next32(fInputText);
                                startPos = Util.utext_getNativeIndex(fInputText);
                            }
                            MatchAt(startPos, false);
                            if (fMatch) {
                                return true;
                            }
                            Util.utext_setNativeIndex(fInputText, startPos);
                        }
                        if (startPos >= testStartLimit) {
                            fMatch = false;
                            fHitEnd = true;
                            return false;
                        }
                        ch = Util.utext_next32(fInputText);
                        startPos = Util.utext_getNativeIndex(fInputText);
                        // Note that it's perfectly OK for a pattern to have a zero-length
                        //   match at the end of a string, so we must make sure that the loop
                        //   runs with startPos == testStartLimit the last time through.
                        if (findProgressInterrupt(startPos))
                            return false;
                    }
                }
            }

            default:
                assert false;
                // Unknown value in fPattern.fStartType, should be from StartOfMatch enum. But
                // we have reports of this in production code, don't use throw new IllegalStateException().
                // See ICU-21669.
                throw new IllegalStateException();
//                return false;
        }

//        throw new IllegalStateException();
    }

    /**
     *   Resets this RegexMatcher and then attempts to find the next substring of the
     *   input string that matches the pattern, starting at the specified index.
     *
     *   @param   start     The (native) index in the input string to begin the search.
     *   @return  true if a match is found.
     *   @since 74.2
     */
    public boolean find(final long start) {
        this.reset();                        // Note:  Reset() is specified by Java Matcher documentation.
        //        This will reset the region to be the full input length.
        if (start < 0) {
            throw new IndexOutOfBoundsException();
//        return false;
        }

        long nativeStart = start;
        if (nativeStart < fActiveStart || nativeStart > fActiveLimit) {
            throw new IndexOutOfBoundsException();
//        return false;
        }
        fMatchEnd = nativeStart;
        return find();
    }


    /**
     * like find(), but with the advance knowledge that the
     * entire string is available in the String's chunk buffer.
     */
    private boolean findUsingChunk() {
        // Start at the position of the last match end.  (Will be zero if the
        //   matcher has been reset.
        //

        int startPos = Math.toIntExact(fMatchEnd);
        if (startPos == 0) {
            startPos = Math.toIntExact(fActiveStart);
        }

        final char[] inputBuf = fInputText.targetString.toCharArray();

        if (fMatch) {
            // Save the position of any previous successful match.
            fLastMatchEnd = fMatchEnd;

            if (fMatchStart == fMatchEnd) {
                // Previous match had zero length.  Move start position up one position
                //  to avoid sending find() into a loop on zero-length matches.
                if (startPos >= fActiveLimit) {
                    fMatch = false;
                    fHitEnd = true;
                    return false;
                }
                startPos = U16_FWD_1(inputBuf, startPos, Math.toIntExact(fInputLength));
            }
        } else {
            if (fLastMatchEnd >= 0) {
                // A previous find() failed to match.  Don't try again.
                //   (without this test, a pattern with a zero-length match
                //    could match again at the end of an input string.)
                fHitEnd = true;
                return false;
            }
        }


        // Compute the position in the input string beyond which a match can not begin, because
        //   the minimum length match would extend past the end of the input.
        //   Note:  some patterns that cannot match anything will have fMinMatchLength==Max Int.
        //          Be aware of possible overflows if making changes here.
        //   Note:  a match can begin at inputBuf + testLen; it is an inclusive limit.
        int testLen = Math.toIntExact((fActiveLimit - fPattern.fMinMatchLen));
        if (startPos > testLen) {
            fMatch = false;
            fHitEnd = true;
            return false;
        }

        int c;
        assert (startPos >= 0);

        switch (fPattern.fStartType) {
            case START_NO_INFO:
                // No optimization was found.
                //  Try a match at each input position.
                for (; ; ) {
                    MatchChunkAt(startPos, false);
                    if (fMatch) {
                        return true;
                    }
                    if (startPos >= testLen) {
                        fHitEnd = true;
                        return false;
                    }
                    startPos = U16_FWD_1(inputBuf, startPos, Math.toIntExact(fActiveLimit));
                    // Note that it's perfectly OK for a pattern to have a zero-length
                    //   match at the end of a string, so we must make sure that the loop
                    //   runs with startPos == testLen the last time through.
                    if (findProgressInterrupt(startPos))
                        return false;
                }
//                throw new IllegalStateException();

            case START_START:
                // Matches are only possible at the start of the input string
                //   (pattern begins with ^ or \A)
                if (startPos > fActiveStart) {
                    fMatch = false;
                    return false;
                }
                MatchChunkAt(startPos, false);
                return fMatch;


            case START_SET: {
                // Match may start on any char from a pre-computed set.
                assert (fPattern.fMinMatchLen > 0);
                for (; ; ) {
                    int pos = startPos;
                    IndexAndChar uNextResult = U16_NEXT(inputBuf, startPos, Math.toIntExact(fActiveLimit));  // like c = inputBuf[startPos++];
                    c = uNextResult.c;
                    startPos = uNextResult.i;
                    if (fPattern.fInitialChars.contains(c)) {
                        MatchChunkAt(pos, false);
                        if (fMatch) {
                            return true;
                        }
                    }
                    if (startPos > testLen) {
                        fMatch = false;
                        fHitEnd = true;
                        return false;
                    }
                    if (findProgressInterrupt(startPos))
                        return false;
                }
            }
//            throw new IllegalStateException();

            case START_STRING:
            case START_CHAR: {
                // Match starts on exactly one char.
                assert (fPattern.fMinMatchLen > 0);
                int theChar = fPattern.fInitialChar;
                for (; ; ) {
                    int pos = startPos;
                    IndexAndChar uNextResult = U16_NEXT(inputBuf, startPos, Math.toIntExact(fActiveLimit));  // like c = inputBuf[startPos++];
                    c = uNextResult.c;
                    startPos = uNextResult.i;
                    if (c == theChar) {
                        MatchChunkAt(pos, false);
                        if (fMatch) {
                            return true;
                        }
                    }
                    if (startPos > testLen) {
                        fMatch = false;
                        fHitEnd = true;
                        return false;
                    }
                    if (findProgressInterrupt(startPos))
                        return false;
                }
            }
//            throw new IllegalStateException();

            case START_LINE: {
                int ch;
                if (startPos == fAnchorStart) {
                    MatchChunkAt(startPos, false);
                    if (fMatch) {
                        return true;
                    }
                    startPos = U16_FWD_1(inputBuf, startPos, Math.toIntExact(fActiveLimit));
                }

                if (fPattern.fFlags.contains(UREGEX_UNIX_LINES)) {
                    for (; ; ) {
                        ch = inputBuf[startPos - 1];
                        if (ch == 0x0a) {
                            MatchChunkAt(startPos, false);
                            if (fMatch) {
                                return true;
                            }
                        }
                        if (startPos >= testLen) {
                            fMatch = false;
                            fHitEnd = true;
                            return false;
                        }
                        startPos = U16_FWD_1(inputBuf, startPos, Math.toIntExact(fActiveLimit));
                        // Note that it's perfectly OK for a pattern to have a zero-length
                        //   match at the end of a string, so we must make sure that the loop
                        //   runs with startPos == testLen the last time through.
                        if (findProgressInterrupt(startPos))
                            return false;
                    }
                } else {
                    for (; ; ) {
                        ch = inputBuf[startPos - 1];
                        if (isLineTerminator(ch)) {
                            if (ch == 0x0d && startPos < fActiveLimit && inputBuf[startPos] == 0x0a) {
                                startPos++;
                            }
                            MatchChunkAt(startPos, false);
                            if (fMatch) {
                                return true;
                            }
                        }
                        if (startPos >= testLen) {
                            fMatch = false;
                            fHitEnd = true;
                            return false;
                        }
                        startPos = U16_FWD_1(inputBuf, startPos, Math.toIntExact(fActiveLimit));
                        // Note that it's perfectly OK for a pattern to have a zero-length
                        //   match at the end of a string, so we must make sure that the loop
                        //   runs with startPos == testLen the last time through.
                        if (findProgressInterrupt(startPos))
                            return false;
                    }
                }
            }

            default:
                assert false;
                // Unknown value in fPattern.fStartType, should be from StartOfMatch enum. But
                // we have reports of this in production code, don't use throw new IllegalStateException().
                // See ICU-21669.
                throw new IllegalStateException();
//                return false;
        }

//        throw new IllegalStateException();
    }


    /**
     *   Returns a string containing the text matched by the previous match.
     *   If the pattern can match an empty string, an empty string may be returned.
     *   @throws IllegalStateException if no match
     *                        has been attempted or the last match failed.
     *   @return  a string containing the matched input text.
     *   @since 74.2
     */
    public String group() {
        return group(0);
    }

    /**
     *    Returns a string containing the text captured by the given group
     *    during the previous match operation.  Group(0) is the entire match.
     * <p>
     *    A zero length string is returned both for capture groups that did not
     *    participate in the match and for actual zero length matches.
     *    To distinguish between these two cases use the function {@link #start()},
     *    which returns -1 for non-participating groups.
     *
     *    @param groupNum the capture group number
     *    @throws IllegalStateException if no match
     *                        has been attempted or the last match failed and
     *    @throws IndexOutOfBoundsException for a bad capture group number.
     *    @return the captured text
     *    @since 74.2
     */
    public String group(final int groupNum) {
        String result = "";
        long groupStart = start64(groupNum);
        long groupEnd = end64(groupNum);
        if (groupStart == -1 || groupStart == groupEnd) {
            return result;
        }

        return fInputText.targetString.substring(Math.toIntExact(groupStart), Math.toIntExact(groupEnd));
    }


/**
 * appends a group to a String rather than replacing its contents
 */
    private long appendGroup(final int groupNum, final ReplaceableString dest) {
        long destLen = utext_nativeLength(dest.toString());

        if (fMatch == false) {
            throw new UErrorException(UErrorCode.U_REGEX_INVALID_STATE);
//        return utext_replace(dest, destLen, destLen, null, 0);
        }
        if (groupNum < 0 || groupNum > fPattern.fGroupMap.size()) {
            throw new IndexOutOfBoundsException();
//        return utext_replace(dest, destLen, destLen, null, 0);
        }

        long s, e;
        if (groupNum == 0) {
            s = fMatchStart;
            e = fMatchEnd;
        } else {
            int groupOffset = fPattern.fGroupMap.elementAti(groupNum - 1);
            assert (groupOffset < fPattern.fFrameSize);
            assert (groupOffset >= 0);
            s = fFrame.fExtra(groupOffset);
            e = fFrame.fExtra(groupOffset + 1);
        }

        if (s < 0) {
            // A capture group wasn't part of the match
            return utext_replace(dest, destLen, destLen, null, 0);
        }
        assert (s <= e);

        long deltaLen;
        if (UTEXT_FULL_TEXT_IN_CHUNK(fInputText, fInputLength)) {
            assert (e <= fInputLength);
            deltaLen = utext_replace(dest, destLen, destLen, fInputText.targetString.toCharArray(), Math.toIntExact(s), Math.toIntExact((e - s)));
        } else {
            int len16;
            len16 = Math.toIntExact((e - s));
            char[] groupChars = new char[len16 + 1];
            utext_extract(fInputText.targetString, s, e, groupChars, len16 + 1);

            deltaLen = utext_replace(dest, destLen, destLen, groupChars, len16);
        }
        return deltaLen;
    }


    /**
     *   Returns the number of capturing groups in this matcher's pattern.
     *   @return the number of capture groups
     *   @since 74.2
     */
    public int groupCount() {
        return fPattern.fGroupMap.size();
    }

    /**
     * Return true if this matcher is using anchoring bounds.
     * By default, matchers use anchoring region bounds.
     *
     * @return true if this matcher is using anchoring bounds.
     * @since 74.2
     */
    public boolean hasAnchoringBounds() {
        return fAnchoringBounds;
    }


    /**
     * Queries the transparency of region bounds for this matcher.
     * See useTransparentBounds for a description of transparent and opaque bounds.
     * By default, a matcher uses opaque region boundaries.
     *
     * @return true if this matcher is using opaque bounds, false if it is not.
     * @since 74.2
     */
    public boolean hasTransparentBounds() {
        return fTransparentBounds;
    }


    /**
     * Return true if the most recent matching operation attempted to access
     *  additional input beyond the available input text.
     *  In this case, additional input text could change the results of the match.
     * <p>
     *  hitEnd() is defined for both successful and unsuccessful matches.
     *  In either case hitEnd() will return true if the end of the text was
     *  reached at any point during the matching process.
     *
     *  @return  true if the most recent match hit the end of input
     *  @since 74.2
     */
    public boolean hitEnd() {
        return fHitEnd;
    }


    /**
     *   Returns the input string being matched.
     *   @return the input string
     *   @since 74.2
     */
    public String input() {
        return fInputText.targetString;
    }


    /**
     * Returns the input string being matched, either by copying it into the provided
     * ReplaceableString parameter or by returning a shallow clone of the live input. Note that copying
     * the entire input may cause significant performance and memory issues.
     *
     * @param dest The ReplaceableString into which the input should be copied, or nullptr to create a new string
     * @return dest if non-nullptr, a shallow copy of the input text otherwise
     * @since 74.2
     */
    public ReplaceableString getInput(final ReplaceableString dest) {

        if (dest != null) {
            if (UTEXT_FULL_TEXT_IN_CHUNK(fInputText, fInputLength)) {
                utext_replace(dest, 0, utext_nativeLength(dest.toString()), fInputText.targetString.toCharArray(), Math.toIntExact(fInputLength));
            } else {
                int input16Len;
                input16Len = Math.toIntExact(fInputLength);
                char[] inputChars = new char[input16Len];

                utext_extract(fInputText.targetString, 0, fInputLength, inputChars, input16Len); // not terminated warning
                utext_replace(dest, 0, utext_nativeLength(dest.toString()), inputChars, input16Len);

            }
            return dest;
        } else {
            return new ReplaceableString(fInputText.targetString);
        }
    }


    /**
     *   Attempts to match the input string, starting from the beginning of the region,
     *   against the pattern.  Like the {@link #matches()} method, this function
     *   always starts at the beginning of the input region;
     *   unlike that function, it does not require that the entire region be matched.
     * <p>
     *   If the match succeeds then more information can be obtained via the {@link #start()},
     *   {@link #end()}, and {@link #group()} functions.
     *
     *    @return  true if there is a match at the start of the input string.
     *    @since 74.2
     */
    public boolean lookingAt() {

        if (false) {
            // fInputText is always a utf16 string
            if (false) {
                fInputLength = utext_nativeLength(fInputText.targetString);
                reset();
            }
        } else {
            resetPreserveRegion();
        }
        if (UTEXT_FULL_TEXT_IN_CHUNK(fInputText, fInputLength)) {
            MatchChunkAt(Math.toIntExact(fActiveStart), false);
        } else {
            MatchAt(fActiveStart, false);
        }
        return fMatch;
    }

    /**
     *   Attempts to match the input string, starting from the specified index, against the pattern.
     *   The match may be of any length, and is not required to extend to the end
     *   of the input string.  Contrast with {@link #match()}.
     * <p>
     *   If the match succeeds then more information can be obtained via the {@link #start()},
     *   {@link #end()}, and {@link #group()} functions.
     *
     *    @param   startIndex The input string (native) index at which to begin matching.
     *    @return  true if there is a match.
     *    @since 74.2
     */
    public boolean lookingAt(final long startIndex) {
        reset();

        if (startIndex < 0) {
            throw new IndexOutOfBoundsException();
//        return false;
        }

        if (false) {
            // fInputText is always a utf16 string
            if (false) {
                fInputLength = utext_nativeLength(fInputText.targetString);
                reset();
            }
        }

        long nativeStart;
        nativeStart = startIndex;
        if (nativeStart < fActiveStart || nativeStart > fActiveLimit) {
            throw new IndexOutOfBoundsException();
//        return false;
        }

        if (UTEXT_FULL_TEXT_IN_CHUNK(fInputText, fInputLength)) {
            MatchChunkAt(Math.toIntExact(nativeStart), false);
        } else {
            MatchAt(nativeStart, false);
        }
        return fMatch;
    }


    /**
     *   Attempts to match the entire input region against the pattern.
     *    @return true if there is a match
     *    @since 74.2
     */
    public boolean matches() {

        if (false) {
            // fInputText is always a utf16 string
            if (false) {
                fInputLength = utext_nativeLength(fInputText.targetString);
                reset();
            }
        } else {
            resetPreserveRegion();
        }

        if (UTEXT_FULL_TEXT_IN_CHUNK(fInputText, fInputLength)) {
            MatchChunkAt(Math.toIntExact(fActiveStart), true);
        } else {
            MatchAt(fActiveStart, true);
        }
        return fMatch;
    }

    /**
     *   Resets the matcher, then attempts to match the input beginning
     *   at the specified startIndex, and extending to the end of the input.
     *   The input region is reset to include the entire input string.
     *   A successful match must extend to the end of the input.
     *    @param   startIndex The input string (native) index at which to begin matching.
     *    @return true if there is a match
     *    @since 74.2
     */
    public boolean matches(final long startIndex) {
        reset();

        if (startIndex < 0) {
            throw new IndexOutOfBoundsException();
//        return false;
        }

        if (false) {
            // fInputText is always a utf16 string
            if (false) {
                fInputLength = utext_nativeLength(fInputText.targetString);
                reset();
            }
        }

        long nativeStart;
        nativeStart = startIndex;
        if (nativeStart < fActiveStart || nativeStart > fActiveLimit) {
            throw new IndexOutOfBoundsException();
//        return false;
        }

        if (UTEXT_FULL_TEXT_IN_CHUNK(fInputText, fInputLength)) {
            MatchChunkAt(Math.toIntExact(nativeStart), true);
        } else {
            MatchAt(nativeStart, true);
        }
        return fMatch;
    }


    /**
     *    Returns the pattern that is interpreted by this matcher.
     *    @return  the RegexPattern for this RegexMatcher
     *    @since 74.2
     */
    public RegexPattern pattern() {
        return fPattern;
    }


    /**
     * Identical to {@link #region(long, long)} but also allows a start position without
     *  resetting the region state.
     * @param regionStart The region start
     * @param regionLimit the limit of the region
     * @param startIndex  The (native) index within the region bounds at which to begin searches.
     * @throws IndexOutOfBoundsException If startIndex is not within the specified region bounds.
     * @since 74.2
     */
    public RegexMatcher region(final long regionStart, final long regionLimit, final long startIndex) {

        if (regionStart > regionLimit || regionStart < 0 || regionLimit < 0) {
            throw new IllegalArgumentException();
        }

        long nativeStart = regionStart;
        long nativeLimit = regionLimit;
        if (nativeStart > fInputLength || nativeLimit > fInputLength) {
            throw new IllegalArgumentException();
        }

        if (startIndex == -1)
            this.reset();
        else
            resetPreserveRegion();

        fRegionStart = nativeStart;
        fRegionLimit = nativeLimit;
        fActiveStart = nativeStart;
        fActiveLimit = nativeLimit;

        if (startIndex != -1) {
            if (startIndex < fActiveStart || startIndex > fActiveLimit) {
                throw new IndexOutOfBoundsException();
            }
            fMatchEnd = startIndex;
        }

        if (!fTransparentBounds) {
            fLookStart = nativeStart;
            fLookLimit = nativeLimit;
        }
        if (fAnchoringBounds) {
            fAnchorStart = nativeStart;
            fAnchorLimit = nativeLimit;
        }
        return this;
    }

    /** Sets the limits of this matcher's region.
     * The region is the part of the input string that will be searched to find a match.
     * Invoking this method resets the matcher, and then sets the region to start
     * at the index specified by the start parameter and end at the index specified
     * by the end parameter.
     * <p>
     * Depending on the transparency and anchoring being used (see useTransparentBounds
     * and useAnchoringBounds), certain constructs such as anchors may behave differently
     * at or around the boundaries of the region
     * <p>
     * The function will fail if start is greater than limit, or if either index
     *  is less than zero or greater than the length of the string being matched.
     *
     * @param start  The (native) index to begin searches at.
     * @param limit  The index to end searches at (exclusive).
     * @since 74.2
     */
    public RegexMatcher region(final long start, final long limit) {
        return region(start, limit, -1);
    }

    /**
     * Reports the end (limit) index (exclusive) of this matcher's region. The searches
     * this matcher conducts are limited to finding matches within regionStart
     * (inclusive) and regionEnd (exclusive).
     *
     * @return The ending point (native) of this matcher's region.
     * @since 74.2
     */
    public int regionEnd() {
        return Math.toIntExact(fRegionLimit);
    }

    /**
     * Reports the end (limit) index (exclusive) of this matcher's region. The searches
     * this matcher conducts are limited to finding matches within regionStart
     * (inclusive) and regionEnd (exclusive).
     *
     * @return The ending point (native) of this matcher's region.
     * @since 74.2
     */
    public long regionEnd64() {
        return fRegionLimit;
    }

    /**
     * Reports the start index of this matcher's region. The searches this matcher
     * conducts are limited to finding matches within regionStart (inclusive) and
     * regionEnd (exclusive).
     *
     * @return The starting (native) index of this matcher's region.
     * @since 74.2
     */
    public int regionStart() {
        return Math.toIntExact(fRegionStart);
    }

    /**
     * Reports the start index of this matcher's region. The searches this matcher
     * conducts are limited to finding matches within regionStart (inclusive) and
     * regionEnd (exclusive).
     *
     * @return The starting (native) index of this matcher's region.
     * @since 74.2
     */
    public long regionStart64() {
        return fRegionStart;
    }


    /**
     *    Replaces every substring of the input that matches the pattern
     *    with the given replacement string.  This is a convenience function that
     *    provides a complete find-and-replace-all operation.
     * <p>
     *    This method first resets this matcher. It then scans the input string
     *    looking for matches of the pattern. Input that is not part of any
     *    match is left unchanged; each match is replaced in the result by the
     *    replacement string. The replacement string may contain references to
     *    capture groups.
     *
     *    @param   replacement a string containing the replacement text.
     *    @return              a string containing the results of the find and replace.
     *    @since 74.2
     */
    public StringBuffer replaceAll(final String replacement) {
        ReplaceableString resultText;
        StringBuffer resultString = new StringBuffer();

        resultText = new ReplaceableString(resultString);

        replaceAll(replacement, resultText);


        return resultString;
    }

    /**
     *    Replaces every substring of the input that matches the pattern
     *    with the given replacement string.  This is a convenience function that
     *    provides a complete find-and-replace-all operation.
     * <p>
     *    This method first resets this matcher. It then scans the input string
     *    looking for matches of the pattern. Input that is not part of any
     *    match is left unchanged; each match is replaced in the result by the
     *    replacement string. The replacement string may contain references to
     *    capture groups.
     *
     *    @param   replacement a string containing the replacement text.
     *    @param   dest        a ReplaceableString in which the results are placed.
     *                          If nullptr, a new ReplaceableString will be created.
     *    @return              a string containing the results of the find and replace.
     *                          If a pre-allocated ReplaceableString was provided, it will always be used and returned.
     *
     *    @since 74.2
     */
    public ReplaceableString replaceAll(final String replacement, final ReplaceableString dest) {
        ReplaceableString newDest;
        if (dest == null) {
            newDest = new ReplaceableString();
        } else {
            newDest = dest;
        }

        reset();
        while (find()) {
            appendReplacement(newDest, replacement);
        }
        appendTail(newDest);

        return newDest;
    }


    /**
     * Replaces the first substring of the input that matches
     * the pattern with the replacement string.   This is a convenience
     * function that provides a complete find-and-replace operation.
     * <p>
     * This function first resets this RegexMatcher. It then scans the input string
     * looking for a match of the pattern. Input that is not part
     * of the match is appended directly to the result string; the match is replaced
     * in the result by the replacement string. The replacement string may contain
     * references to captured groups.
     * <p>
     * The state of the matcher (the position at which a subsequent {@link #find()}
     *    would begin) after completing a replaceFirst() is not specified.  The
     *    RegexMatcher should be reset before doing additional {@link #find()} operations.
     *
     *    @param   replacement a string containing the replacement text.
     *    @return              a StringBuffer containing the results of the find and replace.
     *    @since 74.2
     */
    public StringBuffer replaceFirst(final String replacement) {
        StringBuffer result = new StringBuffer();
        ReplaceableString resultText = new ReplaceableString(result);

        replaceFirst(replacement, resultText);


        return result;
    }

    /**
     * Replaces the first substring of the input that matches
     * the pattern with the replacement string.   This is a convenience
     * function that provides a complete find-and-replace operation.
     * <p>
     * This function first resets this RegexMatcher. It then scans the input string
     * looking for a match of the pattern. Input that is not part
     * of the match is appended directly to the result string; the match is replaced
     * in the result by the replacement string. The replacement string may contain
     * references to captured groups.
     * <p>
     * The state of the matcher (the position at which a subsequent {@link #find()}
     *    would begin) after completing a replaceFirst() is not specified.  The
     *    RegexMatcher should be reset before doing additional {@link #find()} operations.
     *
     *    @param   replacement a string containing the replacement text.
     *    @param   dest        a mutable UText in which the results are placed.
     *                          If nullptr, a new ReplaceableString will be created.
     *    @return              a string containing the results of the find and replace.
     *                          If a pre-allocated ReplaceableString was provided, it will always be used and returned.
     *
     *    @since 74.2
     */
    public ReplaceableString replaceFirst(final String replacement, final ReplaceableString dest) {

        reset();
        if (!find()) {
            return getInput(dest);
        }

        ReplaceableString newDest;
        if (dest == null) {

            newDest = new ReplaceableString();
        } else {
            newDest = dest;
        }

        appendReplacement(newDest, replacement);
        appendTail(newDest);

        return newDest;
    }


    /**
     * Return true the most recent match succeeded and additional input could cause
     * it to fail. If this method returns false and a match was found, then more input
     * might change the match but the match won't be lost. If a match was not found,
     * then requireEnd has no meaning.
     *
     * @return true if more input could cause the most recent match to no longer match.
     * @since 74.2
     */
    public boolean requireEnd() {
        return fRequireEnd;
    }


    /**
     *   Resets this matcher.  The effect is to remove any memory of previous matches,
     *       and to cause subsequent find() operations to begin at the beginning of
     *       the input string.
     *
     *   @return this RegexMatcher.
     *   @since 74.2
     */
    public RegexMatcher reset() {
        fRegionStart = 0;
        fRegionLimit = fInputLength;
        fActiveStart = 0;
        fActiveLimit = fInputLength;
        fAnchorStart = 0;
        fAnchorLimit = fInputLength;
        fLookStart = 0;
        fLookLimit = fInputLength;
        resetPreserveRegion();
        return this;
    }

    private void resetPreserveRegion() {
        fMatchStart = 0;
        fMatchEnd = 0;
        fLastMatchEnd = -1;
        fAppendPosition = 0;
        fMatch = false;
        fHitEnd = false;
        fRequireEnd = false;
        fTime = 0;
        fTickCounter = TIMER_INITIAL_VALUE;
        //resetStack(); // more expensive than it looks...
    }

    /**
     *   Resets this matcher with a new input string.  This allows instances of RegexMatcher
     *     to be reused, which is more efficient than creating a new RegexMatcher for
     *     each input string to be processed.
     *   @param input The new string on which subsequent pattern matches will operate.
     *                The matcher retains a reference to the callers string, and operates
     *                directly on that.
     *   @return this RegexMatcher.
     *   @since 74.2
     */
    public RegexMatcher reset(final String input) {
        if (fInputText == null || !Objects.equals(fInputText.targetString, input)) {
            fInputText = new StringWithOffset(input);
            if (fPattern.fNeedsAltInput) fAltInputText = new StringWithOffset(fInputText.targetString);
            fInputLength = utext_nativeLength(fInputText.targetString);

            if (fWordBreakItr != null) {
                fWordBreakItr.setText(input);
            }
            if (fGCBreakItr != null) {
                fGCBreakItr.setText(fInputText.targetString);
            }
        }
        reset();

        return this;
    }

/*RegexMatcher &reset(final char[]) {
    fDeferredStatus = U_INTERNAL_PROGRAM_ERROR;
    return this;
}*/

    /**
     *   Resets this matcher, and set the current input position.
     *   The effect is to remove any memory of previous matches,
     *       and to cause subsequent find() operations to begin at
     *       the specified (native) position in the input string.
     * <p>
     *   The matcher's region is reset to its default, which is the entire
     *   input string.
     * <p>
     *   An alternative to this function is to set a match region
     *   beginning at the desired index.
     *
     *   @return this RegexMatcher.
     *   @since 74.2
     */
    public RegexMatcher reset(final long index) {
        reset();       // Reset also resets the region to be the entire string.

        if (index < 0 || index > fActiveLimit) {
            throw new IndexOutOfBoundsException();
//        return this;
        }
        fMatchEnd = index;
        return this;
    }

    /**
     * String, replace entire contents of the destination String with a substring of the source String.
     *
     * @param src   The source String
     * @param dest  The destination String. Must be writable.
     *              May be null, in which case a new String will be allocated.
     * @param start Start index of source substring.
     * @param limit Limit index of source substring.
     */
    private static ReplaceableString utext_extract_replace(final String src, final ReplaceableString dest, final long start, final long limit) {
        if (start == limit) {
            if (dest != null) {
                utext_replace(dest, 0, utext_nativeLength(dest.toString()), null, 0);
                return dest;
            } else {
                return new ReplaceableString();
            }
        }
        int length = utext_extract(src, start, limit, null, 0);
        char[] buffer = new char[length + 1];
        utext_extract(src, start, limit, buffer, length + 1);
        if (dest != null) {
            utext_replace(dest, 0, utext_nativeLength(dest.toString()), buffer, length);
            return dest;
        }

        // Caller did not provide a preexisting String.
        return new ReplaceableString(new String(buffer, 0, length));
    }


    /**
     * Split a string into fields.  Somewhat like %split() from Perl.
     * The pattern matches identify delimiters that separate the input
     *  into fields.  The input data between the matches becomes the
     *  fields themselves.
     *
     * @param input   The string to be split into fields.  The field delimiters
     *                match the pattern (in the "this" object).  This matcher
     *                will be reset to this input string.
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
              final int destCapacity) {
        //
        // Check arguments for validity
        //

        if (destCapacity < 1) {
            throw new IllegalArgumentException();
//        return 0;
        }

        final StringWithOffset newInput = new StringWithOffset(input);

        //
        // Reset for the input text
        //
        reset(newInput.targetString);
        long nextOutputStringStart = 0;
        if (fActiveLimit == 0) {
            return 0;
        }

        //
        // Loop through the input text, searching for the delimiter pattern
        //
        int i;
        int numCaptureGroups = fPattern.fGroupMap.size();
        for (i = 0; ; i++) {
            if (i >= destCapacity - 1) {
                // There is one or zero output string left.
                // Fill the last output string with whatever is left from the input, then exit the loop.
                //  ( i will be == destCapacity if we filled the output array while processing
                //    capture groups of the delimiter expression, in which case we will discard the
                //    last capture group saved in favor of the unprocessed remainder of the
                //    input string.)
                i = destCapacity - 1;
                if (fActiveLimit > nextOutputStringStart) {
                    if (UTEXT_FULL_TEXT_IN_CHUNK(newInput, fInputLength)) {
                        if (dest[i] != null) {
                            ReplaceableString tmp = new ReplaceableString(dest[i]);
                            utext_replace(tmp, 0L, utext_nativeLength(dest[i]),
                                    newInput.targetString.toCharArray(), Math.toIntExact(nextOutputStringStart),
                                    Math.toIntExact(fActiveLimit - nextOutputStringStart));
                            dest[i] = tmp.toString();
                        } else {
                            String remainingText = "";
                            remainingText = new String(newInput.targetString.toCharArray(), Math.toIntExact(nextOutputStringStart),
                                    Math.toIntExact(fActiveLimit - nextOutputStringStart));
                            dest[i] = remainingText;
                        }
                    } else {
                        int remaining16Length =
                                utext_extract(newInput.targetString, nextOutputStringStart, fActiveLimit, null, 0);
                        char[] remainingChars = new char[remaining16Length + 1];

                        utext_extract(newInput.targetString, nextOutputStringStart, fActiveLimit, remainingChars, remaining16Length + 1);
                        if (dest[i] != null) {
                            ReplaceableString tmp = new ReplaceableString(dest[i]);
                            utext_replace(tmp, 0, utext_nativeLength(dest[i]), remainingChars, remaining16Length);
                            dest[i] = tmp.toString();
                        } else {
                            String remainingText = "";
                            remainingText = new String(remainingChars, 0, remaining16Length);
                            dest[i] = remainingText;
                        }

                    }
                }
                break;
            }
            if (find()) {
                // We found another delimiter.  Move everything from where we started looking
                //  up until the start of the delimiter into the next output string.
                if (UTEXT_FULL_TEXT_IN_CHUNK(newInput, fInputLength)) {
                    if (dest[i] != null) {
                        ReplaceableString tmp = new ReplaceableString(dest[i]);
                        utext_replace(tmp, 0, utext_nativeLength(dest[i]),
                                newInput.targetString.toCharArray(), Math.toIntExact(nextOutputStringStart),
                                Math.toIntExact((fMatchStart - nextOutputStringStart)));
                        dest[i] = tmp.toString();
                    } else {
                        String remainingText = "";
                        remainingText = new String(newInput.targetString.toCharArray(), Math.toIntExact(nextOutputStringStart),
                                Math.toIntExact(fMatchStart - nextOutputStringStart));
                        dest[i] = remainingText;
                    }
                } else {
                    int remaining16Length = utext_extract(newInput.targetString, nextOutputStringStart, fMatchStart, null, 0);
                    char[] remainingChars = new char[remaining16Length + 1];
                    utext_extract(newInput.targetString, nextOutputStringStart, fMatchStart, remainingChars, remaining16Length + 1);
                    if (dest[i] != null) {
                        ReplaceableString tmp = new ReplaceableString(dest[i]);
                        utext_replace(tmp, 0, utext_nativeLength(dest[i]), remainingChars, remaining16Length);
                        dest[i] = tmp.toString();
                    } else {
                        String remainingText = "";
                        remainingText = new String(remainingChars, 0, remaining16Length);
                        dest[i] = remainingText;
                    }

                }
                nextOutputStringStart = fMatchEnd;

                // If the delimiter pattern has capturing parentheses, the captured
                //  text goes out into the next n destination strings.
                int groupNum;
                for (groupNum = 1; groupNum <= numCaptureGroups; groupNum++) {
                    if (i >= destCapacity - 2) {
                        // Never fill the last available output string with capture group text.
                        // It will filled with the last field, the remainder of the
                        //  unsplit input text.
                        break;
                    }
                    i++;
                    ReplaceableString tmp;
                    if (dest[i] != null) {
                        tmp = new ReplaceableString(dest[i]);
                    } else {
                        tmp = new ReplaceableString();
                    }
                    dest[i] = utext_extract_replace(fInputText.targetString, tmp,
                            start64(groupNum), end64(groupNum)).toString();
                }

                if (nextOutputStringStart == fActiveLimit) {
                    // The delimiter was at the end of the string.  We're done, but first
                    // we output one last empty string, for the empty field following
                    //   the delimiter at the end of input.
                    if (i + 1 < destCapacity) {
                        ++i;
                        if (dest[i] == null) {
                            dest[i] = "";
                        } else {
                            ReplaceableString tmp = new ReplaceableString(dest[i]);
                            utext_replace(tmp, 0, utext_nativeLength(dest[i]), new char[]{(char) 0}, 0);
                            dest[i] = tmp.toString();
                        }
                    }
                    break;

                }
            } else {
                // We ran off the end of the input while looking for the next delimiter.
                // All the remaining text goes into the current output string.
                if (UTEXT_FULL_TEXT_IN_CHUNK(newInput, fInputLength)) {
                    if (dest[i] != null) {
                        ReplaceableString tmp = new ReplaceableString(dest[i]);
                        utext_replace(tmp, 0, utext_nativeLength(dest[i]),
                                newInput.targetString.toCharArray(), Math.toIntExact(nextOutputStringStart),
                                Math.toIntExact((fActiveLimit - nextOutputStringStart)));
                        dest[i] = tmp.toString();
                    } else {
                        String remainingText = "";
                        remainingText = new String(newInput.targetString.toCharArray(), Math.toIntExact(nextOutputStringStart),
                                Math.toIntExact(fActiveLimit - nextOutputStringStart));
                        dest[i] = remainingText;
                    }
                } else {
                    int remaining16Length = utext_extract(newInput.targetString, nextOutputStringStart, fActiveLimit, null, 0);
                    char[] remainingChars = new char[remaining16Length + 1];

                    utext_extract(newInput.targetString, nextOutputStringStart, fActiveLimit, remainingChars, remaining16Length + 1);
                    if (dest[i] != null) {
                        ReplaceableString tmp = new ReplaceableString(dest[i]);
                        utext_replace(tmp, 0, utext_nativeLength(dest[i]), remainingChars, remaining16Length);
                        dest[i] = tmp.toString();
                    } else {
                        String remainingText = "";
                        remainingText = new String(remainingChars, 0, remaining16Length);
                        dest[i] = remainingText;
                    }

                }
                break;
            }
        }   // end of for loop
        return i + 1;
    }

    /**
     *   Returns the index in the input string of the start of the text matched
     *   during the previous match operation.
     *    @return              The (native) position in the input string of the start of the last match.
     *    @since 74.2
     */
    public int start() {
        return start(0);
    }

    /**
     *   Returns the index in the input string of the start of the text matched
     *   during the previous match operation.
     *    @return              The (native) position in the input string of the start of the last match.
     *   @since 74.2
     */
    public long start64() {
        return start64(0);
    }

    /**
     *   Returns the index in the input string of the start of the text matched by the
     *    specified capture group during the previous match operation.  Return -1 if
     *    the capture group exists in the pattern, but was not part of the last match.
     *
     *    @param  group       the capture group number.
     *    @throws IllegalStateException if no match has been
     *                        attempted or the last match failed, and
     *    @throws IndexOutOfBoundsException for a bad capture group number.
     *    @return the (native) start position of substring matched by the specified group.
     *    @since 74.2
     */
    public long start64(final int group) {
        if (fMatch == false) {
            throw new IllegalStateException();
//        return -1;
        }
        if (group < 0 || group > fPattern.fGroupMap.size()) {
            throw new IndexOutOfBoundsException();
//        return -1;
        }
        long s;
        if (group == 0) {
            s = fMatchStart;
        } else {
            int groupOffset = fPattern.fGroupMap.elementAti(group - 1);
            assert (groupOffset < fPattern.fFrameSize);
            assert (groupOffset >= 0);
            s = fFrame.fExtra(groupOffset);
        }

        return s;
    }

    /**
     *   Returns the index in the input string of the start of the text matched by the
     *    specified capture group during the previous match operation.  Return -1 if
     *    the capture group exists in the pattern, but was not part of the last match.
     *
     *    @param  group       the capture group number
     *    @throws IllegalStateException if no match has been
     *                        attempted or the last match failed, and
     *    @throws IndexOutOfBoundsException for a bad capture group number
     *    @return the (native) start position of substring matched by the specified group.
     *    @since 74.2
     */
    public int start(final int group) {
        return Math.toIntExact(start64(group));
    }

    /**
     * Set whether this matcher is using Anchoring Bounds for its region.
     * With anchoring bounds, pattern anchors such as ^ and $ will match at the start
     * and end of the region.  Without Anchoring Bounds, anchors will only match at
     * the positions they would in the complete text.
     * <p>
     * Anchoring Bounds are the default for regions.
     *
     * @param b true if to enable anchoring bounds; false to disable them.
     * @return  This Matcher
     * @since 74.2
     */
    public RegexMatcher useAnchoringBounds(final boolean b) {
        fAnchoringBounds = b;
        fAnchorStart = (fAnchoringBounds ? fRegionStart : 0);
        fAnchorLimit = (fAnchoringBounds ? fRegionLimit : fInputLength);
        return this;
    }


    /**
     * Sets the transparency of region bounds for this matcher.
     * Invoking this function with an argument of true will set this matcher to use transparent bounds.
     * If the boolean argument is false, then opaque bounds will be used.
     * <p>
     * Using transparent bounds, the boundaries of this matcher's region are transparent
     * to lookahead, lookbehind, and boundary matching constructs. Those constructs can
     * see text beyond the boundaries of the region while checking for a match.
     * <p>
     * With opaque bounds, no text outside of the matcher's region is visible to lookahead,
     * lookbehind, and boundary matching constructs.
     * <p>
     * By default, a matcher uses opaque bounds.
     *
     * @param   b true for transparent bounds; false for opaque bounds
     * @return  This Matcher;
     * @since 74.2
     **/
    public RegexMatcher useTransparentBounds(final boolean b) {
        fTransparentBounds = b;
        fLookStart = (fTransparentBounds ? 0 : fRegionStart);
        fLookLimit = (fTransparentBounds ? fInputLength : fRegionLimit);
        return this;
    }

    /**
     *   Set a processing time limit for match operations with this Matcher.
     * <p>
     *   Some patterns, when matching certain strings, can run in exponential time.
     *   For practical purposes, the match operation may appear to be in an
     *   infinite loop.
     *   When a limit is set a match operation will fail with an error if the
     *   limit is exceeded.
     * <p>
     *   The units of the limit are steps of the match engine.
     *   Correspondence with actual processor time will depend on the speed
     *   of the processor and the details of the specific pattern, but will
     *   typically be on the order of milliseconds.
     * <p>
     *   By default, the matching time is not limited.
     *
     *
     *   @param   limit       The limit value, or 0 for no limit.
     *   @since 74.2
     */
    public void setTimeLimit(final int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException();
        }
        fTimeLimit = limit;
    }


    /**
     * Get the time limit, if any, for match operations made with this Matcher.
     *
     *   @return the maximum allowed time for a match, in units of processing steps.
     *   @since 74.2
     */
    public int getTimeLimit() {
        return fTimeLimit;
    }


    /**
     *  Set the amount of heap storage available for use by the match backtracking stack.
     *  The matcher is also reset, discarding any results from previous matches.
     * <p>
     *  The library uses a backtracking regular expression engine, with the backtrack stack
     *  maintained on the heap.  This function sets the limit to the amount of memory
     *  that can be used for this purpose.  A backtracking stack overflow will
     *  result in an error from the match operation that caused it.
     * <p>
     *  A limit is desirable because a malicious or poorly designed pattern can use
     *  excessive memory, potentially crashing the process.  A limit is enabled
     *  by default.
     *
     *  @param limit  The maximum size, in bytes, of the matching backtrack stack.
     *                A value of zero means no limit.
     *                The limit must be greater or equal to zero.
     *
     *  @since 74.2
     */
    public void setStackLimit(final int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException();
        }

        // Reset the matcher.  This is needed here in case there is a current match
        //    whose final stack frame (containing the match results, pointed to by fFrame)
        //    would be lost by resizing to a smaller stack size.
        reset();

        if (limit == 0) {
            // Unlimited stack expansion
            fStack.setMaxCapacity(0);
        } else {
            // Change the units of the limit  from bytes to ints, and bump the size up
            //   to be big enough to hold at least one stack frame for the pattern,
            //   if it isn't there already.
            int adjustedLimit = limit / 4;
            if (adjustedLimit < fPattern.fFrameSize) {
                adjustedLimit = fPattern.fFrameSize;
            }
            fStack.setMaxCapacity(adjustedLimit);
        }
        fStackLimit = limit;
    }


    /**
     *  Get the size of the heap storage available for use by the back tracking stack.
     *
     *  @return  the maximum backtracking stack size, in bytes, or zero if the
     *           stack size is unlimited.
     *  @since 74.2
     */
    public int getStackLimit() {
        return fStackLimit;
    }


    /**
     * Set a callback function for use with this Matcher.
     * During matching operations the function will be called periodically,
     * giving the application the opportunity to terminate a long-running
     * match.
     *
     *    @param   callback    A user-supplied callback function.
     *    @param   context     User context object.  The value supplied at the
     *                         time the callback function is set will be saved
     *                         and passed to the callback each time that it is called.
     *  @since 74.2
     */
    public void setMatchCallback(final URegexMatchCallback callback,
                          final Object context) {
        fCallbackFn = callback;
        fCallbackContext = context;
    }


    /**
     *  Get the callback function for this regular expression.
     *    @return    the user-supplied callback function.
     *    @since 74.2
     */
    public URegexMatchCallback getMatchCallback() {
        return fCallbackFn;
    }

    /**
     *  Get the callback function context for this regular expression.
     *
     *    @return      the user context object.
     *    @since 74.2
     */
    public Object getMatchCallbackContext() {
        return fCallbackContext;
    }


    /**
     * Set a progress callback function for use with find operations on this Matcher.
     * During find operations, the callback will be invoked after each return from a
     * match attempt, giving the application the opportunity to terminate a long-running
     * find operation.
     *
     *    @param   callback    A user-supplied callback function.
     *    @param   context     User context object.  The value supplied at the
     *                         time the callback function is set will be saved
     *                         and passed to the callback each time that it is called.
     *    @since 74.2
     */
    public void setFindProgressCallback(final URegexFindProgressCallback callback,
                                 final Object context) {
        fFindProgressCallbackFn = callback;
        fFindProgressCallbackContext = context;
    }


    /**
     *  Get the find progress callback function for this regular expression.
     *
     *    @return the user-supplied callback function.
     *    @since 74.2
     */
    public URegexFindProgressCallback getFindProgressCallback() {
        return fFindProgressCallbackFn;
    }

    /**
     *  Get the find progress callback function for this URegularExpression.
     *
     *    @return      the user context object.
     *    @since 74.2
     */
    public Object getFindProgressCallbackContext() {
        return fFindProgressCallbackContext;
    }


//================================================================================
//
//    Code following this point in this file is the internal
//    Match Engine Implementation.
//
//================================================================================


    /**
     * Discard any previous contents of the state save stack, and initialize a
     * new stack frame to all -1.  The -1s are needed for capture group limits,
     * where they indicate that a group has not yet matched anything.
     */
    private REStackFrame resetStack() {
        // Discard any previous contents of the state save stack, and initialize a
        //  new stack frame with all -1 data.  The -1s are needed for capture group limits,
        //  where they indicate that a group has not yet matched anything.
        fStack.removeAllElements();

        REStackFrame iFrame = fStack.reserveBlock(fPattern.fFrameSize);

        int i;
        for (i = 0; i < fPattern.fFrameSize - RESTACKFRAME_HDRCOUNT; i++) {
            iFrame.setFExtra(i, -1);
        }
        return iFrame;
    }


    /**
     * Perl-like  \b test.
     * in perl, "xab..cd..", \b is true at positions 0,3,5,7
     * For us,
     * If the current char is a combining mark,
     * \b is false.
     * Else Scan backwards to the first non-combining char.
     * We are at a boundary if the this char and the original chars are
     * opposite in membership in \w set
     * parameters:   pos   - the current position in the input buffer
     * TODO:  double-check edge cases at region boundaries.
     */
    private boolean isWordBoundary(final long pos) {
        boolean isBoundary = false;
        boolean cIsWord = false;

        if (pos >= fLookLimit) {
            fHitEnd = true;
        } else {
            // Determine whether char c at current position is a member of the word set of chars.
            // If we're off the end of the string, behave as though we're not at a word char.
            Util.utext_setNativeIndex(fInputText, pos);
            int c = Util.utext_current32(fInputText);
            if (UCharacter.hasBinaryProperty(c, GRAPHEME_EXTEND) || UCharacter.getType(c) == FORMAT) {
                // Current char is a combining one.  Not a boundary.
                return false;
            }
            cIsWord = RegexStaticSets.INSTANCE.fPropSets[URX_ISWORD_SET.getIndex()].contains(c);
        }

        // Back up until we come to a non-combining char, determine whether
        //  that char is a word char.
        boolean prevCIsWord = false;
        for (; ; ) {
            if (Util.utext_getNativeIndex(fInputText) <= fLookStart) {
                break;
            }
            int prevChar = Util.utext_previous32(fInputText);
            if (!(UCharacter.hasBinaryProperty(prevChar, GRAPHEME_EXTEND)
                    || UCharacter.getType(prevChar) == FORMAT)) {
                prevCIsWord = RegexStaticSets.INSTANCE.fPropSets[URX_ISWORD_SET.getIndex()].contains(prevChar);
                break;
            }
        }
        isBoundary = cIsWord ^ prevCIsWord;
        return isBoundary;
    }

    private boolean isChunkWordBoundary(final int pos) {
        int posNew = pos; // pos is not a pointer or reference, so it's ok to modify it and return a new value
        boolean isBoundary = false;
        boolean cIsWord = false;

        final char[] inputBuf = fInputText.targetString.toCharArray();

        if (posNew >= fLookLimit) {
            fHitEnd = true;
        } else {
            // Determine whether char c at current position is a member of the word set of chars.
            // If we're off the end of the string, behave as though we're not at a word char.
            int c = U16_GET(inputBuf, Math.toIntExact(fLookStart), posNew, Math.toIntExact(fLookLimit));
            if (UCharacter.hasBinaryProperty(c, GRAPHEME_EXTEND) || UCharacter.getType(c) == FORMAT) {
                // Current char is a combining one.  Not a boundary.
                return false;
            }
            cIsWord = RegexStaticSets.INSTANCE.fPropSets[URX_ISWORD_SET.getIndex()].contains(c);
        }

        // Back up until we come to a non-combining char, determine whether
        //  that char is a word char.
        boolean prevCIsWord = false;
        for (; ; ) {
            if (posNew <= fLookStart) {
                break;
            }
            IndexAndChar indexAndChar = U16_PREV(inputBuf, Math.toIntExact(fLookStart), posNew);
            posNew = indexAndChar.i;
            int prevChar = indexAndChar.c;
            if (!(UCharacter.hasBinaryProperty(prevChar, GRAPHEME_EXTEND)
                    || UCharacter.getType(prevChar) == FORMAT)) {
                prevCIsWord = RegexStaticSets.INSTANCE.fPropSets[URX_ISWORD_SET.getIndex()].contains(prevChar);
                break;
            }
        }
        isBoundary = cIsWord ^ prevCIsWord;
        return isBoundary;
    }

    /**
     * Test for a word boundary using RBBI word break (perform RBBI based \b test).
     * parameters:   pos   - the current position in the input buffer
     */
    private boolean isUWordBoundary(final long pos) {
        boolean returnVal = false;

        // Note: this point will never be reached if break iteration is configured out.
        //       Regex patterns that would require this function will fail to compile.

        // If we haven't yet created a break iterator for this matcher, do it now.
        if (fWordBreakItr == null) {
            fWordBreakItr = BreakIterator.getWordInstance(ULocale.ENGLISH);
            fWordBreakItr.setText(fInputText.targetString);
        }

        // Note: zero width boundary tests like \b see through transparent region bounds,
        //       which is why fLookLimit is used here, rather than fActiveLimit.
        if (pos >= fLookLimit) {
            fHitEnd = true;
            returnVal = true;   // With Unicode word rules, only positions within the interior of "real"
            //    words are not boundaries.  All non-word chars stand by themselves,
            //    with word boundaries on both sides.
        } else {
            returnVal = fWordBreakItr.isBoundary(Math.toIntExact(pos));
        }
        return returnVal;
    }

    /**
     * Find a grapheme cluster boundary using a break iterator. For handling \X in regexes.
     */
    private long followingGCBoundary(final long pos) {
        long result = pos;

        // Note: this point will never be reached if break iteration is configured out.
        //       Regex patterns that would require this function will fail to compile.

        // If we haven't yet created a break iterator for this matcher, do it now.
        if (fGCBreakItr == null) {
            fGCBreakItr = BreakIterator.getCharacterInstance(ULocale.ENGLISH);
            fGCBreakItr.setText(fInputText.targetString);
        }
        result = fGCBreakItr.following(Math.toIntExact(pos));
        if (result == BreakIterator.DONE) {
            result = pos;
        }
        return result;
    }

    /**
     * This function is called once each TIMER_INITIAL_VALUE state
     * saves. Increment the "time" counter, and call the
     * user callback function if there is one installed.
     * If the match operation needs to be aborted, either for a time-out
     * or because the user callback asked for it, just throw an exception.
     * The engine will pick that up and stop in its outer loop.
     */
    private void IncrementTime() {
        fTickCounter = TIMER_INITIAL_VALUE;
        fTime++;
        if (fCallbackFn != null) {
            if (fCallbackFn.onMatch(fCallbackContext, fTime) == false) {
                throw new UErrorException(UErrorCode.U_REGEX_STOPPED_BY_CALLER);
            }
        }
        if (fTimeLimit > 0 && fTime >= fTimeLimit) {
            throw new UErrorException(UErrorCode.U_REGEX_TIME_OUT);
        }
    }

    /**
     * --------------------------------------------------------------------------------
     * Make a new stack frame, initialized as a copy of the current stack frame.
     * Set the pattern index in the original stack frame from the operand value
     * in the opcode.  Execution of the engine continues with the state in
     * the newly created stack frame
     * Note that reserveBlock() may grow the stack, resulting in the
     * whole thing being relocated in memory.
     * fp           The top frame pointer when called.  At return, a new
     * fame will be present
     * @param savePatIdx   An index into the compiled pattern.  Goes into the original
     * (not new) frame.  If execution ever back-tracks out of the
     * new frame, this will be where we continue from in the pattern.
     * @return The new frame pointer.
     * --------------------------------------------------------------------------------
     */
    private REStackFrame StateSave(final long savePatIdx) {
        final REStackFrame fp;
        fp = fStack.getLastBlock(fFrameSize);  // in case of realloc of stack.
        // push storage for a new frame.
        REStackFrame newFP = fStack.reserveBlock(fFrameSize);

        // New stack frame = copy of old top frame.
        IndexedView source = fp.asIndexedView();
        int sourceIndex = 0;
        IndexedView dest   = newFP.asIndexedView();
        int destIndex = 0;
        do {
            dest.set(destIndex++, source.get(sourceIndex++));
            // TODO: Rewrite this condition:
        } while (source.baseOffset + sourceIndex != newFP.asIndexedView().baseOffset);

        fTickCounter--;
        if (fTickCounter <= 0) {
            IncrementTime();    // Re-initializes fTickCounter
        }
        fp.setFPatIdx(savePatIdx);
        return newFP;
    }

    /**
     * This is the actual matching engine.
     * @param startIdx    begin matching a this index.
     * @param toEnd       if true, match must extend to end of the input region
     */
    private void MatchAt(final long startIdx, final boolean toEnd) {
        boolean isMatch = false;      // True if the we have a match.

        long backSearchIndex = Long.MAX_VALUE; // used after greedy single-character matches for searching backwards

        int op;                    // Operation from the compiled pattern, split into
        UrxOps opType;                //    the opcode
        int opValue;               //    and the operand value.

        //  Cache frequently referenced items from the compiled pattern
        //
        IndexedView pat = fPattern.fCompiledPat.getBufferView(0);

        final char[] litText = fPattern.fLiteralText.toString().toCharArray();
        ArrayList<UnicodeSet> fSets = fPattern.fSets;

        fFrameSize = fPattern.fFrameSize;
        REStackFrame fp = resetStack();

        fp.setFPatIdx(0);
        fp.setFInputIdx(startIdx);

        // Zero out the pattern's static data
        int i;
        for (i = 0; i < fPattern.fDataSize; i++) {
            fData[i] = 0;
        }

        //
        //  Main loop for interpreting the compiled pattern.
        //  One iteration of the loop per pattern operation performed.
        //
        breakFromLoop:
        for (; ; ) {
            op = Math.toIntExact(pat.get(Math.toIntExact(fp.fPatIdx())));
            opType = URX_TYPE(op);
            opValue = URX_VAL(op);
            fp.setFPatIdx(fp.fPatIdx() + 1);

            switch (opType) {


                case URX_NOP:
                    break;


                case URX_BACKTRACK:
                    // Force a backtrack.  In some circumstances, the pattern compiler
                    //   will notice that the pattern can't possibly match anything, and will
                    //   emit one of these at that point.
                    fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    break;


                case URX_ONECHAR:
                    if (fp.fInputIdx() < fActiveLimit) {
                        Util.utext_setNativeIndex(fInputText, fp.fInputIdx());
                        int c = Util.utext_next32(fInputText);
                        if (c == opValue) {
                            fp.setFInputIdx(Util.utext_getNativeIndex(fInputText));
                            break;
                        }
                    } else {
                        fHitEnd = true;
                    }
                    fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    break;


                case URX_STRING: {
                    // Test input against a literal string.
                    // Strings require two slots in the compiled pattern, one for the
                    //   offset to the string text, and one for the length.

                    int stringStartIdx = opValue;
                    op = Math.toIntExact(pat.get(Math.toIntExact(fp.fPatIdx())));     // Fetch the second operand
                    fp.setFPatIdx(fp.fPatIdx() + 1);
                    opType = URX_TYPE(op);
                    int stringLen = URX_VAL(op);
                    assert (opType == URX_STRING_LEN);
                    assert (stringLen >= 2);

                    final char[] patternString = Arrays.copyOfRange(litText, stringStartIdx, litText.length);
                    int patternStringIndex = 0;
                    Util.utext_setNativeIndex(fInputText, fp.fInputIdx());
                    int inputChar;
                    int patternChar;
                    boolean success = true;
                    while (patternStringIndex < stringLen) {
                        if (Util.utext_getNativeIndex(fInputText) >= fActiveLimit) {
                            success = false;
                            fHitEnd = true;
                            break;
                        }
                        inputChar = Util.utext_next32(fInputText);
                        IndexAndChar uNextResult = U16_NEXT(patternString, patternStringIndex, stringLen);
                        patternChar = uNextResult.c;
                        patternStringIndex = uNextResult.i;
                        if (patternChar != inputChar) {
                            success = false;
                            break;
                        }
                    }

                    if (success) {
                        fp.setFInputIdx(Util.utext_getNativeIndex(fInputText));
                    } else {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;


                case URX_STATE_SAVE:
                    fp = StateSave(opValue);
                    break;


                case URX_END:
                    // The match loop will exit via this path on a successful match,
                    //   when we reach the end of the pattern.
                    if (toEnd && fp.fInputIdx() != fActiveLimit) {
                        // The pattern matched, but not to the end of input.  Try some more.
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }
                    isMatch = true;
                    break breakFromLoop;

                // Start and End Capture stack frame variables are laid out out like this:
                //  fp.fExtra[opValue]  - The start of a completed capture group
                //             opValue+1 - The end   of a completed capture group
                //             opValue+2 - the start of a capture group whose end
                //                          has not yet been reached (and might not ever be).
                case URX_START_CAPTURE:
                    assert (opValue >= 0 && opValue < fFrameSize - 3);
                    fp.setFExtra(opValue + 2, fp.fInputIdx());
                    break;


                case URX_END_CAPTURE:
                    assert (opValue >= 0 && opValue < fFrameSize - 3);
                    assert (fp.fExtra(opValue + 2) >= 0);            // Start pos for this group must be set.
                    fp.setFExtra(opValue, fp.fExtra(opValue + 2));   // Tentative start becomes real.
                    fp.setFExtra(opValue + 1, fp.fInputIdx());           // End position
                    assert (fp.fExtra(opValue) <= fp.fExtra(opValue + 1));
                    break;


                case URX_DOLLAR:                   //  $, test for End of line
                    //     or for position before new line at end of input
                {
                    if (fp.fInputIdx() >= fAnchorLimit) {
                        // We really are at the end of input.  Success.
                        fHitEnd = true;
                        fRequireEnd = true;
                        break;
                    }

                    Util.utext_setNativeIndex(fInputText, fp.fInputIdx());

                    // If we are positioned just before a new-line that is located at the
                    //   end of input, succeed.
                    int c = Util.utext_next32(fInputText);
                    if (Util.utext_getNativeIndex(fInputText) >= fAnchorLimit) {
                        if (isLineTerminator(c)) {
                            // If not in the middle of a CR/LF sequence
                            if (!(c == 0x0a && fp.fInputIdx() > fAnchorStart)) {
                                Util.utext_previous32(fInputText);
                                if ((Util.utext_previous32(fInputText)) == 0x0d) {
                                    // At new-line at end of input. Success
                                    fHitEnd = true;
                                    fRequireEnd = true;

                                    break;
                                }
                            }
                        }
                    } else {
                        int nextC = Util.utext_next32(fInputText);
                        if (c == 0x0d && nextC == 0x0a && Util.utext_getNativeIndex(fInputText) >= fAnchorLimit) {
                            fHitEnd = true;
                            fRequireEnd = true;
                            break;                         // At CR/LF at end of input.  Success
                        }
                    }

                    fp = (REStackFrame) fStack.popFrame(fFrameSize);
                }
                break;


                case URX_DOLLAR_D:                   //  $, test for End of Line, in UNIX_LINES mode.
                    if (fp.fInputIdx() >= fAnchorLimit) {
                        // Off the end of input.  Success.
                        fHitEnd = true;
                        fRequireEnd = true;
                        break;
                    } else {
                        Util.utext_setNativeIndex(fInputText, fp.fInputIdx());
                        int c = Util.utext_next32(fInputText);
                        // Either at the last character of input, or off the end.
                        if (c == 0x0a && Util.utext_getNativeIndex(fInputText) == fAnchorLimit) {
                            fHitEnd = true;
                            fRequireEnd = true;
                            break;
                        }
                    }

                    // Not at end of input.  Back-track out.
                    fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    break;


                case URX_DOLLAR_M:                //  $, test for End of line in multi-line mode
                {
                    if (fp.fInputIdx() >= fAnchorLimit) {
                        // We really are at the end of input.  Success.
                        fHitEnd = true;
                        fRequireEnd = true;
                        break;
                    }
                    // If we are positioned just before a new-line, succeed.
                    // It makes no difference where the new-line is within the input.
                    Util.utext_setNativeIndex(fInputText, fp.fInputIdx());
                    int c = Util.utext_current32(fInputText);
                    if (isLineTerminator(c)) {
                        // At a line end, except for the odd chance of  being in the middle of a CR/LF sequence
                        //  In multi-line mode, hitting a new-line just before the end of input does not
                        //   set the hitEnd or requireEnd flags
                        if (!(c == 0x0a && fp.fInputIdx() > fAnchorStart && Util.utext_previous32(fInputText) == 0x0d)) {
                            break;
                        }
                    }
                    // not at a new line.  Fail.
                    fp = (REStackFrame) fStack.popFrame(fFrameSize);
                }
                break;


                case URX_DOLLAR_MD:                //  $, test for End of line in multi-line and UNIX_LINES mode
                {
                    if (fp.fInputIdx() >= fAnchorLimit) {
                        // We really are at the end of input.  Success.
                        fHitEnd = true;
                        fRequireEnd = true;  // Java set requireEnd in this case, even though
                        break;               //   adding a new-line would not lose the match.
                    }
                    // If we are not positioned just before a new-line, the test fails; backtrack out.
                    // It makes no difference where the new-line is within the input.
                    Util.utext_setNativeIndex(fInputText, fp.fInputIdx());
                    if (Util.utext_current32(fInputText) != 0x0a) {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;


                case URX_CARET:                    //  ^, test for start of line
                    if (fp.fInputIdx() != fAnchorStart) {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                    break;


                case URX_CARET_M:                   //  ^, test for start of line in mulit-line mode
                {
                    if (fp.fInputIdx() == fAnchorStart) {
                        // We are at the start input.  Success.
                        break;
                    }
                    // Check whether character just before the current pos is a new-line
                    //   unless we are at the end of input
                    Util.utext_setNativeIndex(fInputText, fp.fInputIdx());
                    int c = Util.utext_previous32(fInputText);
                    if ((fp.fInputIdx() < fAnchorLimit) && isLineTerminator(c)) {
                        //  It's a new-line.  ^ is true.  Success.
                        //  TODO:  what should be done with positions between a CR and LF?
                        break;
                    }
                    // Not at the start of a line.  Fail.
                    fp = (REStackFrame) fStack.popFrame(fFrameSize);
                }
                break;


                case URX_CARET_M_UNIX:       //  ^, test for start of line in mulit-line + Unix-line mode
                {
                    assert (fp.fInputIdx() >= fAnchorStart);
                    if (fp.fInputIdx() <= fAnchorStart) {
                        // We are at the start input.  Success.
                        break;
                    }
                    // Check whether character just before the current pos is a new-line
                    assert (fp.fInputIdx() <= fAnchorLimit);
                    Util.utext_setNativeIndex(fInputText, fp.fInputIdx());
                    int c = Util.utext_previous32(fInputText);
                    if (c != 0x0a) {
                        // Not at the start of a line.  Back-track out.
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;

                case URX_BACKSLASH_B:          // Test for word boundaries
                {
                    boolean success = isWordBoundary(fp.fInputIdx());
                    success ^= (boolean) (opValue != 0);     // flip sense for \B
                    if (!success) {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;


                case URX_BACKSLASH_BU:          // Test for word boundaries, Unicode-style
                {
                    boolean success = isUWordBoundary(fp.fInputIdx());
                    success ^= (boolean) (opValue != 0);     // flip sense for \B
                    if (!success) {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;


                case URX_BACKSLASH_D:            // Test for decimal digit
                {
                    if (fp.fInputIdx() >= fActiveLimit) {
                        fHitEnd = true;
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }

                    Util.utext_setNativeIndex(fInputText, fp.fInputIdx());

                    int c = Util.utext_next32(fInputText);
                    int ctype = UCharacter.getType(c);     // TODO:  make a unicode set for this.  Will be faster.
                    boolean success = (ctype == DECIMAL_DIGIT_NUMBER);
                    success ^= (boolean) (opValue != 0);        // flip sense for \D
                    if (success) {
                        fp.setFInputIdx(Util.utext_getNativeIndex(fInputText));
                    } else {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;


                case URX_BACKSLASH_G:          // Test for position at end of previous match
                    if (!((fMatch && fp.fInputIdx() == fMatchEnd) || (fMatch == false && fp.fInputIdx() == fActiveStart))) {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                    break;


                case URX_BACKSLASH_H:            // Test for \h, horizontal white space.
                {
                    if (fp.fInputIdx() >= fActiveLimit) {
                        fHitEnd = true;
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }
                    Util.utext_setNativeIndex(fInputText, fp.fInputIdx());
                    int c = Util.utext_next32(fInputText);
                    int ctype = UCharacter.getType(c);
                    boolean success = (ctype == SPACE_SEPARATOR || c == 9);  // SPACE_SEPARATOR || TAB
                    success ^= (boolean) (opValue != 0);        // flip sense for \H
                    if (success) {
                        fp.setFInputIdx(Util.utext_getNativeIndex(fInputText));
                    } else {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;


                case URX_BACKSLASH_R:            // Test for \R, any line break sequence.
                {
                    if (fp.fInputIdx() >= fActiveLimit) {
                        fHitEnd = true;
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }
                    Util.utext_setNativeIndex(fInputText, fp.fInputIdx());
                    int c = Util.utext_next32(fInputText);
                    if (isLineTerminator(c)) {
                        if (c == 0x0d && utext_current32(fInputText) == 0x0a) {
                            utext_next32(fInputText);
                        }
                        fp.setFInputIdx(Util.utext_getNativeIndex(fInputText));
                    } else {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;


                case URX_BACKSLASH_V:            // \v, any single line ending character.
                {
                    if (fp.fInputIdx() >= fActiveLimit) {
                        fHitEnd = true;
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }
                    Util.utext_setNativeIndex(fInputText, fp.fInputIdx());
                    int c = Util.utext_next32(fInputText);
                    boolean success = isLineTerminator(c);
                    success ^= (boolean) (opValue != 0);        // flip sense for \V
                    if (success) {
                        fp.setFInputIdx(Util.utext_getNativeIndex(fInputText));
                    } else {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;


                case URX_BACKSLASH_X:
                    //  Match a Grapheme, as defined by Unicode UAX 29.

                    // Fail if at end of input
                    if (fp.fInputIdx() >= fActiveLimit) {
                        fHitEnd = true;
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }

                    fp.setFInputIdx(followingGCBoundary(fp.fInputIdx()));
                    if (fp.fInputIdx() >= fActiveLimit) {
                        fHitEnd = true;
                        fp.setFInputIdx(fActiveLimit);
                    }
                    break;


                case URX_BACKSLASH_Z:          // Test for end of Input
                    if (fp.fInputIdx() < fAnchorLimit) {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    } else {
                        fHitEnd = true;
                        fRequireEnd = true;
                    }
                    break;


                case URX_STATIC_SETREF: {
                    // Test input character against one of the predefined sets
                    //    (Word Characters, for example)
                    // The high bit of the op value is a flag for the match polarity.
                    //    0:   success if input char is in set.
                    //    1:   success if input char is not in set.
                    if (fp.fInputIdx() >= fActiveLimit) {
                        fHitEnd = true;
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }

                    boolean success = ((opValue & URX_NEG_SET.getIndex()) == URX_NEG_SET.getIndex());
                    opValue &= ~URX_NEG_SET.getIndex();
                    assert (opValue > 0 && opValue < URX_LAST_SET.getIndex());

                    Util.utext_setNativeIndex(fInputText, fp.fInputIdx());
                    int c = Util.utext_next32(fInputText);
                    final UnicodeSet s = RegexStaticSets.INSTANCE.fPropSets[opValue];
                    if (s.contains(c)) {
                        success = !success;
                    }
                    if (success) {
                        fp.setFInputIdx(Util.utext_getNativeIndex(fInputText));
                    } else {
                        // the character wasn't in the set.
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;


                case URX_STAT_SETREF_N: {
                    // Test input character for NOT being a member of  one of
                    //    the predefined sets (Word Characters, for example)
                    if (fp.fInputIdx() >= fActiveLimit) {
                        fHitEnd = true;
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }

                    assert (opValue > 0 && opValue < URX_LAST_SET.getIndex());

                    Util.utext_setNativeIndex(fInputText, fp.fInputIdx());

                    int c = Util.utext_next32(fInputText);
                    final UnicodeSet s = RegexStaticSets.INSTANCE.fPropSets[opValue];
                    if (s.contains(c) == false) {
                        fp.setFInputIdx(Util.utext_getNativeIndex(fInputText));
                        break;
                    }
                    // the character wasn't in the set.
                    fp = (REStackFrame) fStack.popFrame(fFrameSize);
                }
                break;


                case URX_SETREF:
                    if (fp.fInputIdx() >= fActiveLimit) {
                        fHitEnd = true;
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    } else {
                        Util.utext_setNativeIndex(fInputText, fp.fInputIdx());

                        // There is input left.  Pick up one char and test it for set membership.
                        int c = Util.utext_next32(fInputText);
                        assert (opValue > 0 && opValue < fSets.size());
                        UnicodeSet s = (UnicodeSet) fSets.get(opValue);
                        if (s.contains(c)) {
                            // The character is in the set.  A Match.
                            fp.setFInputIdx(Util.utext_getNativeIndex(fInputText));
                            break;
                        }

                        // the character wasn't in the set.
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                    break;


                case URX_DOTANY: {
                    // . matches anything, but stops at end-of-line.
                    if (fp.fInputIdx() >= fActiveLimit) {
                        // At end of input.  Match failed.  Backtrack out.
                        fHitEnd = true;
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }

                    Util.utext_setNativeIndex(fInputText, fp.fInputIdx());

                    // There is input left.  Advance over one char, unless we've hit end-of-line
                    int c = Util.utext_next32(fInputText);
                    if (isLineTerminator(c)) {
                        // End of line in normal mode.   . does not match.
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }
                    fp.setFInputIdx(Util.utext_getNativeIndex(fInputText));
                }
                break;


                case URX_DOTANY_ALL: {
                    // ., in dot-matches-all (including new lines) mode
                    if (fp.fInputIdx() >= fActiveLimit) {
                        // At end of input.  Match failed.  Backtrack out.
                        fHitEnd = true;
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }

                    Util.utext_setNativeIndex(fInputText, fp.fInputIdx());

                    // There is input left.  Advance over one char, except if we are
                    //   at a cr/lf, advance over both of them.
                    int c;
                    c = Util.utext_next32(fInputText);
                    fp.setFInputIdx(Util.utext_getNativeIndex(fInputText));
                    if (c == 0x0d && fp.fInputIdx() < fActiveLimit) {
                        // In the case of a CR/LF, we need to advance over both.
                        int nextc = Util.utext_current32(fInputText);
                        if (nextc == 0x0a) {
                            Util.utext_next32(fInputText);
                            fp.setFInputIdx(Util.utext_getNativeIndex(fInputText));
                        }
                    }
                }
                break;


                case URX_DOTANY_UNIX: {
                    // '.' operator, matches all, but stops at end-of-line.
                    //   UNIX_LINES mode, so 0x0a is the only recognized line ending.
                    if (fp.fInputIdx() >= fActiveLimit) {
                        // At end of input.  Match failed.  Backtrack out.
                        fHitEnd = true;
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }

                    Util.utext_setNativeIndex(fInputText, fp.fInputIdx());

                    // There is input left.  Advance over one char, unless we've hit end-of-line
                    int c = Util.utext_next32(fInputText);
                    if (c == 0x0a) {
                        // End of line in normal mode.   '.' does not match the \n
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    } else {
                        fp.setFInputIdx(Util.utext_getNativeIndex(fInputText));
                    }
                }
                break;


                case URX_JMP:
                    fp.setFPatIdx(opValue);
                    break;

                case URX_FAIL:
                    isMatch = false;
                    break breakFromLoop;

                case URX_JMP_SAV:
                    assert (opValue < fPattern.fCompiledPat.size());
                    fp = StateSave(fp.fPatIdx());       // State save to loc following current
                    fp.setFPatIdx(opValue);                         // Then JMP.
                    break;

                case URX_JMP_SAV_X:
                    // This opcode is used with (x)+, when x can match a zero length string.
                    // Same as JMP_SAV, except conditional on the match having made forward progress.
                    // Destination of the JMP must be a URX_STO_INP_LOC, from which we get the
                    //   data address of the input position at the start of the loop.
                {
                    assert (opValue > 0 && opValue < fPattern.fCompiledPat.size());
                    int stoOp = Math.toIntExact(pat.get(opValue - 1));
                    assert (URX_TYPE(stoOp) == URX_STO_INP_LOC);
                    int frameLoc = URX_VAL(stoOp);
                    assert (frameLoc >= 0 && frameLoc < fFrameSize);
                    long prevInputIdx = fp.fExtra(frameLoc);
                    assert (prevInputIdx <= fp.fInputIdx());
                    if (prevInputIdx < fp.fInputIdx()) {
                        // The match did make progress.  Repeat the loop.
                        fp = StateSave(fp.fPatIdx());  // State save to loc following current
                        fp.setFPatIdx(opValue);
                        fp.setFExtra(frameLoc, fp.fInputIdx());
                    }
                    // If the input position did not advance, we do nothing here,
                    //   execution will fall out of the loop.
                }
                break;

                case URX_CTR_INIT: {
                    assert (opValue >= 0 && opValue < fFrameSize - 2);
                    fp.setFExtra(opValue, 0);                 //  Set the loop counter variable to zero

                    // Pick up the three extra operands that CTR_INIT has, and
                    //    skip the pattern location counter past
                    int instrOperandLoc = Math.toIntExact(fp.fPatIdx());
                    fp.setFPatIdx(fp.fPatIdx() + 3);
                    int loopLoc = URX_VAL(Math.toIntExact(pat.get(instrOperandLoc)));
                    int minCount = Math.toIntExact(pat.get(instrOperandLoc + 1));
                    int maxCount = Math.toIntExact(pat.get(instrOperandLoc + 2));
                    assert (minCount >= 0);
                    assert (maxCount >= minCount || maxCount == -1);
                    assert (loopLoc >= fp.fPatIdx());

                    if (minCount == 0) {
                        fp = StateSave(loopLoc + 1);
                    }
                    if (maxCount == -1) {
                        fp.setFExtra(opValue + 1, fp.fInputIdx());   //  For loop breaking.
                    } else if (maxCount == 0) {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;

                case URX_CTR_LOOP: {
                    assert (opValue > 0 && opValue < fp.fPatIdx() - 2);
                    int initOp = Math.toIntExact(pat.get(opValue));
                    assert (URX_TYPE(initOp) == URX_CTR_INIT);
                    int pCounterIndex = URX_VAL(initOp);
                    int minCount = Math.toIntExact(pat.get(opValue + 2));
                    int maxCount = Math.toIntExact(pat.get(opValue + 3));
                    pCounterIndex++;
                    if (fp.fExtra(pCounterIndex) >= (/* uint32 */long) maxCount && maxCount != -1) {
                        assert (fp.fExtra(pCounterIndex) == maxCount);
                        break;
                    }
                    if (fp.fExtra(pCounterIndex) >= minCount) {
                        if (maxCount == -1) {
                            // Loop has no hard upper bound.
                            // Check that it is progressing through the input, break if it is not.
                            int pLastInputIdxIndex = URX_VAL(initOp) + 1;
                            if (fp.fInputIdx() == fp.fExtra(pLastInputIdxIndex)) {
                                break;
                            } else {
                                fp.setFExtra(pLastInputIdxIndex, fp.fInputIdx());
                            }
                        }
                        fp = StateSave(fp.fPatIdx());
                    } else {
                        // Increment time-out counter. (StateSave() does it if count >= minCount)
                        fTickCounter--;
                        if (fTickCounter <= 0) {
                            IncrementTime();    // Re-initializes fTickCounter
                        }
                    }

                    fp.setFPatIdx(opValue + 4);    // Loop back.
                }
                break;

                case URX_CTR_INIT_NG: {
                    // Initialize a non-greedy loop
                    assert (opValue >= 0 && opValue < fFrameSize - 2);
                    fp.setFExtra(opValue, 0);                 //  Set the loop counter variable to zero

                    // Pick up the three extra operands that CTR_INIT_NG has, and
                    //    skip the pattern location counter past
                    int instrOperandLoc = Math.toIntExact(fp.fPatIdx());
                    fp.setFPatIdx(fp.fPatIdx() + 3);
                    int loopLoc = URX_VAL(Math.toIntExact(pat.get(instrOperandLoc)));
                    int minCount = Math.toIntExact(pat.get(instrOperandLoc + 1));
                    int maxCount = Math.toIntExact(pat.get(instrOperandLoc + 2));
                    assert (minCount >= 0);
                    assert (maxCount >= minCount || maxCount == -1);
                    assert (loopLoc > fp.fPatIdx());
                    if (maxCount == -1) {
                        fp.setFExtra(opValue + 1, fp.fInputIdx());   //  Save initial input index for loop breaking.
                    }

                    if (minCount == 0) {
                        if (maxCount != 0) {
                            fp = StateSave(fp.fPatIdx());
                        }
                        fp.setFPatIdx(loopLoc + 1);   // Continue with stuff after repeated block
                    }
                }
                break;

                case URX_CTR_LOOP_NG: {
                    // Non-greedy {min, max} loops
                    assert (opValue > 0 && opValue < fp.fPatIdx() - 2);
                    int initOp = Math.toIntExact(pat.get(opValue));
                    assert (URX_TYPE(initOp) == URX_CTR_INIT_NG);
                    int pCounterIndex = URX_VAL(initOp);
                    int minCount = Math.toIntExact(pat.get(opValue + 2));
                    int maxCount = Math.toIntExact(pat.get(opValue + 3));

                    fp.setFExtra(pCounterIndex, fp.fExtra(pCounterIndex) + 1);
                    if (fp.fExtra(pCounterIndex) >= (/* uint32 */long) maxCount && maxCount != -1) {
                        // The loop has matched the maximum permitted number of times.
                        //   Break out of here with no action.  Matching will
                        //   continue with the following pattern.
                        assert (fp.fExtra(pCounterIndex) == maxCount);
                        break;
                    }

                    if (fp.fExtra(pCounterIndex) < minCount) {
                        // We haven't met the minimum number of matches yet.
                        //   Loop back for another one.
                        fp.setFPatIdx(opValue + 4);    // Loop back.
                        // Increment time-out counter. (StateSave() does it if count >= minCount)
                        fTickCounter--;
                        if (fTickCounter <= 0) {
                            IncrementTime();    // Re-initializes fTickCounter
                        }
                    } else {
                        // We do have the minimum number of matches.

                        // If there is no upper bound on the loop iterations, check that the input index
                        // is progressing, and stop the loop if it is not.
                        if (maxCount == -1) {
                            int pLastInputIdxIndex = URX_VAL(initOp) + 1;
                            if (fp.fInputIdx() == fp.fExtra(pLastInputIdxIndex)) {
                                break;
                            }
                            fp.setFExtra(pLastInputIdxIndex, fp.fInputIdx());
                        }

                        // Loop Continuation: we will fall into the pattern following the loop
                        //   (non-greedy, don't execute loop body first), but first do
                        //   a state save to the top of the loop, so that a match failure
                        //   in the following pattern will try another iteration of the loop.
                        fp = StateSave(opValue + 4);
                    }
                }
                break;

                case URX_STO_SP:
                    assert (opValue >= 0 && opValue < fPattern.fDataSize);
                    fData[opValue] = fStack.size();
                    break;

                case URX_LD_SP: {
                    assert (opValue >= 0 && opValue < fPattern.fDataSize);
                    int newStackSize = Math.toIntExact(fData[opValue]);
                    assert (newStackSize <= fStack.size());
                    IndexedView newFP = fStack.getBufferView(newStackSize - fFrameSize);
                    if (newFP == fp.asIndexedView()) {
                        break;
                    }
                    int j;
                    for (j = 0; j < fFrameSize; j++) {
                        newFP.set(j, fp.asIndexedView().get(j));
                    }
                    fp = newFP.asREStackFrame(fFrameSize);
                    fStack.setSize(newStackSize);
                }
                break;

                case URX_BACKREF: {
                    assert (opValue < fFrameSize);
                    long groupStartIdx = fp.fExtra(opValue);
                    long groupEndIdx = fp.fExtra(opValue + 1);
                    assert (groupStartIdx <= groupEndIdx);
                    if (groupStartIdx < 0) {
                        // This capture group has not participated in the match thus far,
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);   // FAIL, no match.
                        break;
                    }
                    Util.utext_setNativeIndex(fAltInputText, groupStartIdx);
                    Util.utext_setNativeIndex(fInputText, fp.fInputIdx());

                    //   Note: if the capture group match was of an empty string the backref
                    //         match succeeds.  Verified by testing:  Perl matches succeed
                    //         in this case, so we do too.

                    boolean success = true;
                    for (; ; ) {
                        if (utext_getNativeIndex(fAltInputText) >= groupEndIdx) {
                            success = true;
                            break;
                        }
                        if (utext_getNativeIndex(fInputText) >= fActiveLimit) {
                            success = false;
                            fHitEnd = true;
                            break;
                        }
                        int captureGroupChar = utext_next32(fAltInputText);
                        int inputChar = utext_next32(fInputText);
                        if (inputChar != captureGroupChar) {
                            success = false;
                            break;
                        }
                    }

                    if (success) {
                        fp.setFInputIdx(Util.utext_getNativeIndex(fInputText));
                    } else {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;


                case URX_BACKREF_I: {
                    assert (opValue < fFrameSize);
                    long groupStartIdx = fp.fExtra(opValue);
                    long groupEndIdx = fp.fExtra(opValue + 1);
                    assert (groupStartIdx <= groupEndIdx);
                    if (groupStartIdx < 0) {
                        // This capture group has not participated in the match thus far,
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);   // FAIL, no match.
                        break;
                    }
                    utext_setNativeIndex(fAltInputText, Math.toIntExact(groupStartIdx));
                    utext_setNativeIndex(fInputText, Math.toIntExact(fp.fInputIdx()));
                    CaseFoldingUTextIterator captureGroupItr = new CaseFoldingUTextIterator(fAltInputText);
                    CaseFoldingUTextIterator inputItr = new CaseFoldingUTextIterator(fInputText);

                    //   Note: if the capture group match was of an empty string the backref
                    //         match succeeds.  Verified by testing:  Perl matches succeed
                    //         in this case, so we do too.

                    boolean success = true;
                    for (; ; ) {
                        if (!captureGroupItr.inExpansion() && utext_getNativeIndex(fAltInputText) >= groupEndIdx) {
                            success = true;
                            break;
                        }
                        if (!inputItr.inExpansion() && utext_getNativeIndex(fInputText) >= fActiveLimit) {
                            success = false;
                            fHitEnd = true;
                            break;
                        }
                        int captureGroupChar = captureGroupItr.next();
                        int inputChar = inputItr.next();
                        if (inputChar != captureGroupChar) {
                            success = false;
                            break;
                        }
                    }

                    if (success && inputItr.inExpansion()) {
                        // We obtained a match by consuming part of a string obtained from
                        // case-folding a single code point of the input text.
                        // This does not count as an overall match.
                        success = false;
                    }

                    if (success) {
                        fp.setFInputIdx(Util.utext_getNativeIndex(fInputText));
                    } else {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }

                }
                break;

                case URX_STO_INP_LOC: {
                    assert (opValue >= 0 && opValue < fFrameSize);
                    fp.setFExtra(opValue, fp.fInputIdx());
                }
                break;

                case URX_JMPX: {
                    int instrOperandLoc = Math.toIntExact(fp.fPatIdx());
                    fp.setFPatIdx(fp.fPatIdx() + 1);
                    int dataLoc = URX_VAL(Math.toIntExact(pat.get(instrOperandLoc)));
                    assert (dataLoc >= 0 && dataLoc < fFrameSize);
                    long savedInputIdx = fp.fExtra(dataLoc);
                    assert (savedInputIdx <= fp.fInputIdx());
                    if (savedInputIdx < fp.fInputIdx()) {
                        fp.setFPatIdx(opValue);                               // JMP
                    } else {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);   // FAIL, no progress in loop.
                    }
                }
                break;

                case URX_LA_START: {
                    // Entering a look around block.
                    // Save Stack Ptr, Input Pos.
                    assert (opValue >= 0 && opValue + 3 < fPattern.fDataSize);
                    fData[opValue] = fStack.size();
                    fData[opValue + 1] = fp.fInputIdx();
                    fData[opValue + 2] = fActiveStart;
                    fData[opValue + 3] = fActiveLimit;
                    fActiveStart = fLookStart;          // Set the match region change for
                    fActiveLimit = fLookLimit;          //   transparent bounds.
                }
                break;

                case URX_LA_END: {
                    // Leaving a look-ahead block.
                    //  restore Stack Ptr, Input Pos to positions they had on entry to block.
                    assert (opValue >= 0 && opValue + 3 < fPattern.fDataSize);
                    int stackSize = fStack.size();
                    int newStackSize = Math.toIntExact(fData[opValue]);
                    assert (stackSize >= newStackSize);
                    if (stackSize > newStackSize) {
                        // Copy the current top frame back to the new (cut back) top frame.
                        //   This makes the capture groups from within the look-ahead
                        //   expression available.
                        IndexedView newFP = fStack.getBufferView(newStackSize - fFrameSize);
                        int j;
                        for (j = 0; j < fFrameSize; j++) {
                            newFP.set(j, fp.asIndexedView().get(j));
                        }
                        fp = (REStackFrame) newFP.asREStackFrame(fFrameSize);
                        fStack.setSize(newStackSize);
                    }
                    fp.setFInputIdx(fData[opValue + 1]);

                    // Restore the active region bounds in the input string; they may have
                    //    been changed because of transparent bounds on a Region.
                    fActiveStart = fData[opValue + 2];
                    fActiveLimit = fData[opValue + 3];
                    assert (fActiveStart >= 0);
                    assert (fActiveLimit <= fInputLength);
                }
                break;

                case URX_ONECHAR_I:
                    // Case insensitive one char.  The char from the pattern is already case folded.
                    // Input text is not, but case folding the input can not reduce two or more code
                    // points to one.
                    if (fp.fInputIdx() < fActiveLimit) {
                        Util.utext_setNativeIndex(fInputText, fp.fInputIdx());

                        int c = Util.utext_next32(fInputText);
                        if (UCharacter.foldCase(c, FOLD_CASE_DEFAULT) == opValue) {
                            fp.setFInputIdx(Util.utext_getNativeIndex(fInputText));
                            break;
                        }
                    } else {
                        fHitEnd = true;
                    }

                    fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    break;

                case URX_STRING_I: {
                    // Case-insensitive test input against a literal string.
                    // Strings require two slots in the compiled pattern, one for the
                    //   offset to the string text, and one for the length.
                    //   The compiled string has already been case folded.
                    {
                        final char[] patternString = litText;
                        final int patternStringOffset = opValue;
                        int patternStringIdx = patternStringOffset;

                        op = Math.toIntExact(pat.get(Math.toIntExact(fp.fPatIdx())));
                        fp.setFPatIdx(fp.fPatIdx() + 1);
                        opType = URX_TYPE(op);
                        opValue = URX_VAL(op);
                        assert (opType == URX_STRING_LEN);
                        int patternStringLen = opValue;  // Length of the string from the pattern.


                        int cPattern;
                        int cText;
                        boolean success = true;

                        Util.utext_setNativeIndex(fInputText, fp.fInputIdx());
                        CaseFoldingUTextIterator inputIterator = new CaseFoldingUTextIterator(fInputText);
                        while (patternStringIdx < patternStringLen + patternStringOffset) {
                            if (!inputIterator.inExpansion() && Util.utext_getNativeIndex(fInputText) >= fActiveLimit) {
                                success = false;
                                fHitEnd = true;
                                break;
                            }
                            IndexAndChar uNextResult = U16_NEXT(patternString, patternStringIdx, patternStringLen);
                            cPattern = uNextResult.c;
                            patternStringIdx = uNextResult.i;
                            cText = inputIterator.next();
                            if (cText != cPattern) {
                                success = false;
                                break;
                            }
                        }
                        if (inputIterator.inExpansion()) {
                            success = false;
                        }

                        if (success) {
                            fp.setFInputIdx(Util.utext_getNativeIndex(fInputText));
                        } else {
                            fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        }
                    }
                }
                break;

                case URX_LB_START: {
                    // Entering a look-behind block.
                    // Save Stack Ptr, Input Pos and active input region.
                    //   TODO:  implement transparent bounds.  Ticket #6067
                    assert (opValue >= 0 && opValue + 4 < fPattern.fDataSize);
                    fData[opValue] = fStack.size();
                    fData[opValue + 1] = fp.fInputIdx();
                    // Save input string length, then reset to pin any matches to end at
                    //   the current position.
                    fData[opValue + 2] = fActiveStart;
                    fData[opValue + 3] = fActiveLimit;
                    fActiveStart = fRegionStart;
                    fActiveLimit = fp.fInputIdx();
                    // Init the variable containing the start index for attempted matches.
                    fData[opValue + 4] = -1;
                }
                break;


                case URX_LB_CONT: {
                    // Positive Look-Behind, at top of loop checking for matches of LB expression
                    //    at all possible input starting positions.

                    // Fetch the min and max possible match lengths.  They are the operands
                    //   of this op in the pattern.
                    int minML = Math.toIntExact(pat.get(Math.toIntExact(fp.postIncrementFPatIdx())));
                    int maxML = Math.toIntExact(pat.get(Math.toIntExact(fp.postIncrementFPatIdx())));
                    assert (minML <= maxML);
                    assert (minML >= 0);

                    // Fetch (from data) the last input index where a match was attempted.
                    assert (opValue >= 0 && opValue + 4 < fPattern.fDataSize);
                    final int lbStartIdxOffset = opValue + 4;
                    long lbStartIdx = fData[lbStartIdxOffset];
                    if (fData[lbStartIdxOffset] < 0) {
                        // First time through loop.
                        fData[lbStartIdxOffset] = fp.fInputIdx() - minML;
                        if (fData[lbStartIdxOffset] > 0) {
                            // move index to a code point boundary, if it's not on one already.
                            Util.utext_setNativeIndex(fInputText, fData[lbStartIdxOffset]);
                            fData[lbStartIdxOffset] = Util.utext_getNativeIndex(fInputText);
                        }
                    } else {
                        // 2nd through nth time through the loop.
                        // Back up start position for match by one.
                        if (fData[lbStartIdxOffset] == 0) {
                            fData[lbStartIdxOffset]--;
                        } else {
                            Util.utext_setNativeIndex(fInputText, fData[lbStartIdxOffset]);
                            Util.utext_previous32(fInputText);
                            fData[lbStartIdxOffset] = Util.utext_getNativeIndex(fInputText);
                        }
                    }

                    if (fData[lbStartIdxOffset] < 0 || fData[lbStartIdxOffset] < fp.fInputIdx() - maxML) {
                        // We have tried all potential match starting points without
                        //  getting a match.  Backtrack out, and out of the
                        //   Look Behind altogether.
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        fActiveStart = fData[opValue + 2];
                        fActiveLimit = fData[opValue + 3];
                        assert (fActiveStart >= 0);
                        assert (fActiveLimit <= fInputLength);
                        break;
                    }

                    //    Save state to this URX_LB_CONT op, so failure to match will repeat the loop.
                    //      (successful match will fall off the end of the loop.)
                    fp = StateSave(fp.fPatIdx() - 3);
                    fp.setFInputIdx(fData[lbStartIdxOffset]);
                }
                break;

                case URX_LB_END:
                    // End of a look-behind block, after a successful match.
                {
                    assert (opValue >= 0 && opValue + 4 < fPattern.fDataSize);
                    if (fp.fInputIdx() != fActiveLimit) {
                        //  The look-behind expression matched, but the match did not
                        //    extend all the way to the point that we are looking behind from.
                        //  FAIL out of here, which will take us back to the LB_CONT, which
                        //     will retry the match starting at another position or fail
                        //     the look-behind altogether, whichever is appropriate.
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }

                    // Look-behind match is good.  Restore the original input string region,
                    //   which had been truncated to pin the end of the lookbehind match to the
                    //   position being looked-behind.
                    fActiveStart = fData[opValue + 2];
                    fActiveLimit = fData[opValue + 3];
                    assert (fActiveStart >= 0);
                    assert (fActiveLimit <= fInputLength);
                }
                break;


                case URX_LBN_CONT: {
                    // Negative Look-Behind, at top of loop checking for matches of LB expression
                    //    at all possible input starting positions.

                    // Fetch the extra parameters of this op.
                    int minML = Math.toIntExact(pat.get(Math.toIntExact(fp.postIncrementFPatIdx())));
                    int maxML = Math.toIntExact(pat.get(Math.toIntExact(fp.postIncrementFPatIdx())));
                    int continueLoc = Math.toIntExact(pat.get(Math.toIntExact(fp.postIncrementFPatIdx())));
                    continueLoc = URX_VAL(continueLoc);
                    assert (minML <= maxML);
                    assert (minML >= 0);
                    assert (continueLoc > fp.fPatIdx());

                    // Fetch (from data) the last input index where a match was attempted.
                    assert (opValue >= 0 && opValue + 4 < fPattern.fDataSize);
                    int lbStartIdxOffset = opValue + 4;
                    // long &lbStartIdx = fData[lbStartIdxOffset];
                    if (fData[lbStartIdxOffset] < 0) {
                        // First time through loop.
                        fData[lbStartIdxOffset] = fp.fInputIdx() - minML;
                        if (fData[lbStartIdxOffset] > 0) {
                            // move index to a code point boundary, if it's not on one already.
                            Util.utext_setNativeIndex(fInputText, fData[lbStartIdxOffset]);
                            fData[lbStartIdxOffset] = Util.utext_getNativeIndex(fInputText);
                        }
                    } else {
                        // 2nd through nth time through the loop.
                        // Back up start position for match by one.
                        if (fData[lbStartIdxOffset] == 0) {
                            fData[lbStartIdxOffset]--;
                        } else {
                            Util.utext_setNativeIndex(fInputText, fData[lbStartIdxOffset]);
                            Util.utext_previous32(fInputText);
                            fData[lbStartIdxOffset] = Util.utext_getNativeIndex(fInputText);
                        }
                    }

                    if (fData[lbStartIdxOffset] < 0 || fData[lbStartIdxOffset] < fp.fInputIdx() - maxML) {
                        // We have tried all potential match starting points without
                        //  getting a match, which means that the negative lookbehind as
                        //  a whole has succeeded.  Jump forward to the continue location
                        fActiveStart = fData[opValue + 2];
                        fActiveLimit = fData[opValue + 3];
                        assert (fActiveStart >= 0);
                        assert (fActiveLimit <= fInputLength);
                        fp.setFPatIdx(continueLoc);
                        break;
                    }

                    //    Save state to this URX_LB_CONT op, so failure to match will repeat the loop.
                    //      (successful match will cause a FAIL out of the loop altogether.)
                    fp = StateSave(fp.fPatIdx() - 4);
                    fp.setFInputIdx(fData[lbStartIdxOffset]);
                }
                break;

                case URX_LBN_END:
                    // End of a negative look-behind block, after a successful match.
                {
                    assert (opValue >= 0 && opValue + 4 < fPattern.fDataSize);
                    if (fp.fInputIdx() != fActiveLimit) {
                        //  The look-behind expression matched, but the match did not
                        //    extend all the way to the point that we are looking behind from.
                        //  FAIL out of here, which will take us back to the LB_CONT, which
                        //     will retry the match starting at another position or succeed
                        //     the look-behind altogether, whichever is appropriate.
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }

                    // Look-behind expression matched, which means look-behind test as
                    //   a whole Fails

                    //   Restore the original input string length, which had been truncated
                    //   inorder to pin the end of the lookbehind match
                    //   to the position being looked-behind.
                    fActiveStart = fData[opValue + 2];
                    fActiveLimit = fData[opValue + 3];
                    assert (fActiveStart >= 0);
                    assert (fActiveLimit <= fInputLength);

                    // Restore original stack position, discarding any state saved
                    //   by the successful pattern match.
                    assert (opValue >= 0 && opValue + 1 < fPattern.fDataSize);
                    int newStackSize = Math.toIntExact(fData[opValue]);
                    assert (fStack.size() > newStackSize);
                    fStack.setSize(newStackSize);

                    //  FAIL, which will take control back to someplace
                    //  prior to entering the look-behind test.
                    fp = (REStackFrame) fStack.popFrame(fFrameSize);
                }
                break;


                case URX_LOOP_SR_I:
                    // Loop Initialization for the optimized implementation of
                    //     [some character set]*
                    //   This op scans through all matching input.
                    //   The following LOOP_C op emulates stack unwinding if the following pattern fails.
                {
                    assert (opValue > 0 && opValue < fSets.size());
                    UnicodeSet s = (UnicodeSet) fSets.get(opValue);

                    // Loop through input, until either the input is exhausted or
                    //   we reach a character that is not a member of the set.
                    long ix = fp.fInputIdx();
                    Util.utext_setNativeIndex(fInputText, ix);
                    for (; ; ) {
                        if (ix >= fActiveLimit) {
                            fHitEnd = true;
                            break;
                        }
                        int c = Util.utext_next32(fInputText);
                        if (s.contains(c) == false) {
                            break;
                        }
                        ix = Util.utext_getNativeIndex(fInputText);
                    }

                    // If there were no matching characters, skip over the loop altogether.
                    //   The loop doesn't run at all, a * op always succeeds.
                    if (ix == fp.fInputIdx()) {
                        fp.postIncrementFPatIdx();   // skip the URX_LOOP_C op.
                        break;
                    }

                    // Peek ahead in the compiled pattern, to the URX_LOOP_C that
                    //   must follow.  It's operand is the stack location
                    //   that holds the starting input index for the match of this [set]*
                    int loopcOp = Math.toIntExact(pat.get(Math.toIntExact(fp.fPatIdx())));
                    assert (URX_TYPE(loopcOp) == URX_LOOP_C);
                    int stackLoc = URX_VAL(loopcOp);
                    assert (stackLoc >= 0 && stackLoc < fFrameSize);
                    fp.setFExtra(stackLoc, fp.fInputIdx());
                    fp.setFInputIdx(ix);

                    // Save State to the URX_LOOP_C op that follows this one,
                    //   so that match failures in the following code will return to there.
                    //   Then bump the pattern idx so the LOOP_C is skipped on the way out of here.
                    fp = StateSave(fp.fPatIdx());
                    fp.postIncrementFPatIdx();
                }
                break;


                case URX_LOOP_DOT_I:
                    // Loop Initialization for the optimized implementation of .*
                    //   This op scans through all remaining input.
                    //   The following LOOP_C op emulates stack unwinding if the following pattern fails.
                {
                    // Loop through input until the input is exhausted (we reach an end-of-line)
                    // In DOTALL mode, we can just go straight to the end of the input.
                    long ix;
                    if ((opValue & 1) == 1) {
                        // Dot-matches-All mode.  Jump straight to the end of the string.
                        ix = fActiveLimit;
                        fHitEnd = true;
                    } else {
                        // NOT DOT ALL mode.  Line endings do not match '.'
                        // Scan forward until a line ending or end of input.
                        ix = fp.fInputIdx();
                        Util.utext_setNativeIndex(fInputText, ix);
                        for (; ; ) {
                            if (ix >= fActiveLimit) {
                                fHitEnd = true;
                                break;
                            }
                            int c = Util.utext_next32(fInputText);
                            if ((c & 0x7f) <= 0x29) {          // Fast filter of non-new-line-s
                                if ((c == 0x0a) ||             //  0x0a is newline in both modes.
                                        (((opValue & 2) == 0) &&    // IF not UNIX_LINES mode
                                                isLineTerminator(c))) {
                                    //  char is a line ending.  Exit the scanning loop.
                                    break;
                                }
                            }
                            ix = Util.utext_getNativeIndex(fInputText);
                        }
                    }

                    // If there were no matching characters, skip over the loop altogether.
                    //   The loop doesn't run at all, a * op always succeeds.
                    if (ix == fp.fInputIdx()) {
                        fp.postIncrementFPatIdx();   // skip the URX_LOOP_C op.
                        break;
                    }

                    // Peek ahead in the compiled pattern, to the URX_LOOP_C that
                    //   must follow.  It's operand is the stack location
                    //   that holds the starting input index for the match of this .*
                    int loopcOp = Math.toIntExact(pat.get(Math.toIntExact(fp.fPatIdx())));
                    assert (URX_TYPE(loopcOp) == URX_LOOP_C);
                    int stackLoc = URX_VAL(loopcOp);
                    assert (stackLoc >= 0 && stackLoc < fFrameSize);
                    fp.setFExtra(stackLoc, fp.fInputIdx());
                    fp.setFInputIdx(ix);

                    // Save State to the URX_LOOP_C op that follows this one,
                    //   so that match failures in the following code will return to there.
                    //   Then bump the pattern idx so the LOOP_C is skipped on the way out of here.
                    fp = StateSave(fp.fPatIdx());
                    fp.postIncrementFPatIdx();
                }
                break;


                case URX_LOOP_C: {
                    assert (opValue >= 0 && opValue < fFrameSize);
                    backSearchIndex = fp.fExtra(opValue);
                    assert (backSearchIndex <= fp.fInputIdx());
                    if (backSearchIndex == fp.fInputIdx()) {
                        // We've backed up the input idx to the point that the loop started.
                        // The loop is done.  Leave here without saving state.
                        //  Subsequent failures won't come back here.
                        break;
                    }
                    // Set up for the next iteration of the loop, with input index
                    //   backed up by one from the last time through,
                    //   and a state save to this instruction in case the following code fails again.
                    //   (We're going backwards because this loop emulates stack unwinding, not
                    //    the initial scan forward.)
                    assert (fp.fInputIdx() > 0);
                    Util.utext_setNativeIndex(fInputText, fp.fInputIdx());
                    int prevC = Util.utext_previous32(fInputText);
                    fp.setFInputIdx(Util.utext_getNativeIndex(fInputText));

                    int twoPrevC = Util.utext_previous32(fInputText);
                    if (prevC == 0x0a &&
                            fp.fInputIdx() > backSearchIndex &&
                            twoPrevC == 0x0d) {
                        int prevOp = Math.toIntExact(pat.get(Math.toIntExact(fp.fPatIdx() - 2)));
                        if (URX_TYPE(prevOp) == URX_LOOP_DOT_I) {
                            // .*, stepping back over CRLF pair.
                            fp.setFInputIdx(Util.utext_getNativeIndex(fInputText));
                        }
                    }


                    fp = StateSave(fp.fPatIdx() - 1);
                }
                break;


                default:
                    // Trouble.  The compiled pattern contains an entry with an
                    //           unrecognized type tag.
                    assert false;
                    // Unknown opcode type in opType = URX_TYPE(pat.get(fp.fPatIdx())). But we have
                    // reports of this in production code, don't use throw new IllegalStateException().
                    // See ICU-21669.
                    throw new IllegalStateException();
            }

        }

        fMatch = isMatch;
        if (isMatch) {
            fLastMatchEnd = fMatchEnd;
            fMatchStart = startIdx;
            fMatchEnd = fp.fInputIdx();
        }

        fFrame = fp;                // The active stack frame when the engine stopped.
        //   Contains the capture group results that we need to
        //    access later.
        return;
    }


    /**
     * This is the actual matching engine. Like MatchAt, but with the
     * assumption that the entire string is available in the String's
     * chunk buffer. For now, that means we can use int indexes,
     * except for anything that needs to be saved (like group starts
     * and ends).
     * @param startIdx    begin matching a this index.
     * @param toEnd       if true, match must extend to end of the input region
     */
    private void MatchChunkAt(final int startIdx, final boolean toEnd) {
        boolean isMatch = false;      // True if the we have a match.

        int backSearchIndex = Integer.MAX_VALUE; // used after greedy single-character matches for searching backwards

        int op;                    // Operation from the compiled pattern, split into
        UrxOps opType;                //    the opcode
        int opValue;               //    and the operand value.

        //  Cache frequently referenced items from the compiled pattern
        //
        Vector64View pat = new Vector64View(fPattern.fCompiledPat, 0);

        final StringBuffer litText = fPattern.fLiteralText;
        List<UnicodeSet> fSets = fPattern.fSets;

        final char[] inputBuf = fInputText.targetString.toCharArray();

        fFrameSize = fPattern.fFrameSize;
        REStackFrame fp = resetStack();

        fp.setFPatIdx(0);
        fp.setFInputIdx(startIdx);

        // Zero out the pattern's static data
        int i;
        for (i = 0; i < fPattern.fDataSize; i++) {
            fData[i] = 0;
        }

        //
        //  Main loop for interpreting the compiled pattern.
        //  One iteration of the loop per pattern operation performed.
        //
        breakFromLoop:
        for (; ; ) {
            op = Math.toIntExact(pat.get(Math.toIntExact(fp.fPatIdx())));
            opType = URX_TYPE(op);
            opValue = URX_VAL(op);
            fp.postIncrementFPatIdx();

            switch (opType) {


                case URX_NOP:
                    break;


                case URX_BACKTRACK:
                    // Force a backtrack.  In some circumstances, the pattern compiler
                    //   will notice that the pattern can't possibly match anything, and will
                    //   emit one of these at that point.
                    fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    break;


                case URX_ONECHAR:
                    if (fp.fInputIdx() < fActiveLimit) {
                        int c;
                        IndexAndChar uNextResult = U16_NEXT(inputBuf, Math.toIntExact(fp.fInputIdx()), Math.toIntExact(fActiveLimit));
                        c = uNextResult.c;
                        fp.setFInputIdx(uNextResult.i);
                        if (c == opValue) {
                            break;
                        }
                    } else {
                        fHitEnd = true;
                    }
                    fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    break;


                case URX_STRING: {
                    // Test input against a literal string.
                    // Strings require two slots in the compiled pattern, one for the
                    //   offset to the string text, and one for the length.
                    int stringStartIdx = opValue;
                    int stringLen;

                    op = Math.toIntExact(pat.get(Math.toIntExact(fp.fPatIdx())));     // Fetch the second operand
                    fp.postIncrementFPatIdx();
                    opType = URX_TYPE(op);
                    stringLen = URX_VAL(op);
                    assert (opType == URX_STRING_LEN);
                    assert (stringLen >= 2);

                    final CharArrayView pInp = new CharArrayView(inputBuf, Math.toIntExact(fp.fInputIdx()));
                    final CharArrayView pInpLimit = new CharArrayView(inputBuf, Math.toIntExact(fActiveLimit));
                    final StringBufferView pPat = new StringBufferView(litText, stringStartIdx);
                    final CharArrayView pEnd = new CharArrayView(pInp, stringLen);
                    boolean success = true;
                    while (pInp.compareBaseOffsetWith(pEnd) < 0) {
                        if (pInp.compareBaseOffsetWith(pInpLimit) >= 0) {
                            fHitEnd = true;
                            success = false;
                            break;
                        }
                        if (pInp.next() != pPat.next()) {
                            success = false;
                            break;
                        }
                    }

                    if (success) {
                        fp.setFInputIdx(fp.fInputIdx() + stringLen);
                    } else {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;


                case URX_STATE_SAVE:
                    fp = StateSave(opValue);
                    break;


                case URX_END:
                    // The match loop will exit via this path on a successful match,
                    //   when we reach the end of the pattern.
                    if (toEnd && fp.fInputIdx() != fActiveLimit) {
                        // The pattern matched, but not to the end of input.  Try some more.
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }
                    isMatch = true;
                    break breakFromLoop;

                // Start and End Capture stack frame variables are laid out out like this:
                //  fp.fExtra(opValue)  - The start of a completed capture group
                //             opValue+1 - The end   of a completed capture group
                //             opValue+2 - the start of a capture group whose end
                //                          has not yet been reached (and might not ever be).
                case URX_START_CAPTURE:
                    assert (opValue >= 0 && opValue < fFrameSize - 3);
                    fp.setFExtra(opValue + 2, fp.fInputIdx());
                    break;


                case URX_END_CAPTURE:
                    assert (opValue >= 0 && opValue < fFrameSize - 3);
                    assert (fp.fExtra(opValue + 2) >= 0);            // Start pos for this group must be set.
                    fp.setFExtra(opValue, fp.fExtra(opValue + 2));   // Tentative start becomes real.
                    fp.setFExtra(opValue + 1, fp.fInputIdx());           // End position
                    assert (fp.fExtra(opValue) <= fp.fExtra(opValue + 1));
                    break;


                case URX_DOLLAR:                   //  $, test for End of line
                    //     or for position before new line at end of input
                    if (fp.fInputIdx() < fAnchorLimit - 2) {
                        // We are no where near the end of input.  Fail.
                        //   This is the common case.  Keep it first.
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }
                    if (fp.fInputIdx() >= fAnchorLimit) {
                        // We really are at the end of input.  Success.
                        fHitEnd = true;
                        fRequireEnd = true;
                        break;
                    }

                    // If we are positioned just before a new-line that is located at the
                    //   end of input, succeed.
                    if (fp.fInputIdx() == fAnchorLimit - 1) {
                        int c;
                        c = U16_GET(inputBuf, Math.toIntExact(fAnchorStart), Math.toIntExact(fp.fInputIdx()), Math.toIntExact(fAnchorLimit));

                        if (isLineTerminator(c)) {
                            if (!(c == 0x0a && fp.fInputIdx() > fAnchorStart && inputBuf[Math.toIntExact(fp.fInputIdx() - 1)] == 0x0d)) {
                                // At new-line at end of input. Success
                                fHitEnd = true;
                                fRequireEnd = true;
                                break;
                            }
                        }
                    } else if (fp.fInputIdx() == fAnchorLimit - 2 &&
                            inputBuf[Math.toIntExact(fp.fInputIdx())] == 0x0d && inputBuf[Math.toIntExact(fp.fInputIdx() + 1)] == 0x0a) {
                        fHitEnd = true;
                        fRequireEnd = true;
                        break;                         // At CR/LF at end of input.  Success
                    }

                    fp = (REStackFrame) fStack.popFrame(fFrameSize);

                    break;


                case URX_DOLLAR_D:                   //  $, test for End of Line, in UNIX_LINES mode.
                    if (fp.fInputIdx() >= fAnchorLimit - 1) {
                        // Either at the last character of input, or off the end.
                        if (fp.fInputIdx() == fAnchorLimit - 1) {
                            // At last char of input.  Success if it's a new line.
                            if (inputBuf[Math.toIntExact(fp.fInputIdx())] == 0x0a) {
                                fHitEnd = true;
                                fRequireEnd = true;
                                break;
                            }
                        } else {
                            // Off the end of input.  Success.
                            fHitEnd = true;
                            fRequireEnd = true;
                            break;
                        }
                    }

                    // Not at end of input.  Back-track out.
                    fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    break;


                case URX_DOLLAR_M:                //  $, test for End of line in multi-line mode
                {
                    if (fp.fInputIdx() >= fAnchorLimit) {
                        // We really are at the end of input.  Success.
                        fHitEnd = true;
                        fRequireEnd = true;
                        break;
                    }
                    // If we are positioned just before a new-line, succeed.
                    // It makes no difference where the new-line is within the input.
                    int c = inputBuf[Math.toIntExact(fp.fInputIdx())];
                    if (isLineTerminator(c)) {
                        // At a line end, except for the odd chance of  being in the middle of a CR/LF sequence
                        //  In multi-line mode, hitting a new-line just before the end of input does not
                        //   set the hitEnd or requireEnd flags
                        if (!(c == 0x0a && fp.fInputIdx() > fAnchorStart && inputBuf[Math.toIntExact(fp.fInputIdx() - 1)] == 0x0d)) {
                            break;
                        }
                    }
                    // not at a new line.  Fail.
                    fp = (REStackFrame) fStack.popFrame(fFrameSize);
                }
                break;


                case URX_DOLLAR_MD:                //  $, test for End of line in multi-line and UNIX_LINES mode
                {
                    if (fp.fInputIdx() >= fAnchorLimit) {
                        // We really are at the end of input.  Success.
                        fHitEnd = true;
                        fRequireEnd = true;  // Java set requireEnd in this case, even though
                        break;               //   adding a new-line would not lose the match.
                    }
                    // If we are not positioned just before a new-line, the test fails; backtrack out.
                    // It makes no difference where the new-line is within the input.
                    if (inputBuf[Math.toIntExact(fp.fInputIdx())] != 0x0a) {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;


                case URX_CARET:                    //  ^, test for start of line
                    if (fp.fInputIdx() != fAnchorStart) {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                    break;


                case URX_CARET_M:                   //  ^, test for start of line in mulit-line mode
                {
                    if (fp.fInputIdx() == fAnchorStart) {
                        // We are at the start input.  Success.
                        break;
                    }
                    // Check whether character just before the current pos is a new-line
                    //   unless we are at the end of input
                    char c = inputBuf[Math.toIntExact(fp.fInputIdx() - 1)];
                    if ((fp.fInputIdx() < fAnchorLimit) &&
                            isLineTerminator(c)) {
                        //  It's a new-line.  ^ is true.  Success.
                        //  TODO:  what should be done with positions between a CR and LF?
                        break;
                    }
                    // Not at the start of a line.  Fail.
                    fp = (REStackFrame) fStack.popFrame(fFrameSize);
                }
                break;


                case URX_CARET_M_UNIX:       //  ^, test for start of line in mulit-line + Unix-line mode
                {
                    assert (fp.fInputIdx() >= fAnchorStart);
                    if (fp.fInputIdx() <= fAnchorStart) {
                        // We are at the start input.  Success.
                        break;
                    }
                    // Check whether character just before the current pos is a new-line
                    assert (fp.fInputIdx() <= fAnchorLimit);
                    char c = inputBuf[Math.toIntExact(fp.fInputIdx() - 1)];
                    if (c != 0x0a) {
                        // Not at the start of a line.  Back-track out.
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;

                case URX_BACKSLASH_B:          // Test for word boundaries
                {
                    boolean success = isChunkWordBoundary(Math.toIntExact(fp.fInputIdx()));
                    success ^= (boolean) (opValue != 0);     // flip sense for \B
                    if (!success) {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;


                case URX_BACKSLASH_BU:          // Test for word boundaries, Unicode-style
                {
                    boolean success = isUWordBoundary(fp.fInputIdx());
                    success ^= (boolean) (opValue != 0);     // flip sense for \B
                    if (!success) {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;


                case URX_BACKSLASH_D:            // Test for decimal digit
                {
                    if (fp.fInputIdx() >= fActiveLimit) {
                        fHitEnd = true;
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }

                    int c;
                    IndexAndChar uNextResult = U16_NEXT(inputBuf, Math.toIntExact(fp.fInputIdx()), Math.toIntExact(fActiveLimit));
                    c = uNextResult.c;
                    fp.setFInputIdx(uNextResult.i);
                    int ctype = UCharacter.getType(c);     // TODO:  make a unicode set for this.  Will be faster.
                    boolean success = (ctype == DECIMAL_DIGIT_NUMBER);
                    success ^= (boolean) (opValue != 0);        // flip sense for \D
                    if (!success) {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;


                case URX_BACKSLASH_G:          // Test for position at end of previous match
                    if (!((fMatch && fp.fInputIdx() == fMatchEnd) || (fMatch == false && fp.fInputIdx() == fActiveStart))) {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                    break;


                case URX_BACKSLASH_H:            // Test for \h, horizontal white space.
                {
                    if (fp.fInputIdx() >= fActiveLimit) {
                        fHitEnd = true;
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }
                    int c;
                    IndexAndChar uNextResult = U16_NEXT(inputBuf, Math.toIntExact(fp.fInputIdx()), Math.toIntExact(fActiveLimit));
                    c = uNextResult.c;
                    fp.setFInputIdx(uNextResult.i);
                    int ctype = UCharacter.getType(c);
                    boolean success = (ctype == SPACE_SEPARATOR || c == 9);  // SPACE_SEPARATOR || TAB
                    success ^= (boolean) (opValue != 0);        // flip sense for \H
                    if (!success) {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;


                case URX_BACKSLASH_R:            // Test for \R, any line break sequence.
                {
                    if (fp.fInputIdx() >= fActiveLimit) {
                        fHitEnd = true;
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }
                    int c;
                    IndexAndChar uNextResult = U16_NEXT(inputBuf, Math.toIntExact(fp.fInputIdx()), Math.toIntExact(fActiveLimit));
                    c = uNextResult.c;
                    fp.setFInputIdx(uNextResult.i);
                    if (isLineTerminator(c)) {
                        if (c == 0x0d && fp.fInputIdx() < fActiveLimit) {
                            // Check for CR/LF sequence. Consume both together when found.
                            char c2;
                            IndexAndChar uNextResult2 = U16_NEXT(inputBuf, Math.toIntExact(fp.fInputIdx()), Math.toIntExact(fActiveLimit));
                            c2 = Util.toCharExact(uNextResult2.c);
                            fp.setFInputIdx(uNextResult2.i);
                            if (c2 != 0x0a) {
                                IndexAndChar indexAndChar = U16_PREV(inputBuf, 0, Math.toIntExact(fp.fInputIdx()));
                                fp.setFInputIdx(indexAndChar.i);
                                c2 = Util.toCharExact(indexAndChar.c);
                            }
                        }
                    } else {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;


                case URX_BACKSLASH_V:         // Any single code point line ending.
                {
                    if (fp.fInputIdx() >= fActiveLimit) {
                        fHitEnd = true;
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }
                    int c;
                    IndexAndChar uNextResult = U16_NEXT(inputBuf, Math.toIntExact(fp.fInputIdx()), Math.toIntExact(fActiveLimit));
                    c = uNextResult.c;
                    fp.setFInputIdx(uNextResult.i);
                    boolean success = isLineTerminator(c);
                    success ^= (boolean) (opValue != 0);        // flip sense for \V
                    if (!success) {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;


                case URX_BACKSLASH_X:
                    //  Match a Grapheme, as defined by Unicode UAX 29.

                    // Fail if at end of input
                    if (fp.fInputIdx() >= fActiveLimit) {
                        fHitEnd = true;
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }

                    fp.setFInputIdx(followingGCBoundary(fp.fInputIdx()));
                    if (fp.fInputIdx() >= fActiveLimit) {
                        fHitEnd = true;
                        fp.setFInputIdx(fActiveLimit);
                    }
                    break;


                case URX_BACKSLASH_Z:          // Test for end of Input
                    if (fp.fInputIdx() < fAnchorLimit) {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    } else {
                        fHitEnd = true;
                        fRequireEnd = true;
                    }
                    break;


                case URX_STATIC_SETREF: {
                    // Test input character against one of the predefined sets
                    //    (Word Characters, for example)
                    // The high bit of the op value is a flag for the match polarity.
                    //    0:   success if input char is in set.
                    //    1:   success if input char is not in set.
                    if (fp.fInputIdx() >= fActiveLimit) {
                        fHitEnd = true;
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }

                    boolean success = ((opValue & URX_NEG_SET.getIndex()) == URX_NEG_SET.getIndex());
                    opValue &= ~URX_NEG_SET.getIndex();
                    assert (opValue > 0 && opValue < URX_LAST_SET.getIndex());

                    int c;
                    IndexAndChar uNextResult = U16_NEXT(inputBuf, Math.toIntExact(fp.fInputIdx()), Math.toIntExact(fActiveLimit));
                    c = uNextResult.c;
                    fp.setFInputIdx(uNextResult.i);
                    final UnicodeSet s = RegexStaticSets.INSTANCE.fPropSets[opValue];
                    if (s.contains(c)) {
                        success = !success;
                    }
                    if (!success) {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;


                case URX_STAT_SETREF_N: {
                    // Test input character for NOT being a member of  one of
                    //    the predefined sets (Word Characters, for example)
                    if (fp.fInputIdx() >= fActiveLimit) {
                        fHitEnd = true;
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }

                    assert (opValue > 0 && opValue < URX_LAST_SET.getIndex());

                    int c;
                    IndexAndChar uNextResult = U16_NEXT(inputBuf, Math.toIntExact(fp.fInputIdx()), Math.toIntExact(fActiveLimit));
                    c = uNextResult.c;
                    fp.setFInputIdx(uNextResult.i);
                    final UnicodeSet s = RegexStaticSets.INSTANCE.fPropSets[opValue];
                    if (s.contains(c) == false) {
                        break;
                    }
                    fp = (REStackFrame) fStack.popFrame(fFrameSize);
                }
                break;


                case URX_SETREF: {
                    if (fp.fInputIdx() >= fActiveLimit) {
                        fHitEnd = true;
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }

                    assert (opValue > 0 && opValue < fSets.size());

                    // There is input left.  Pick up one char and test it for set membership.
                    int c;
                    IndexAndChar uNextResult = U16_NEXT(inputBuf, Math.toIntExact(fp.fInputIdx()), Math.toIntExact(fActiveLimit));
                    c = uNextResult.c;
                    fp.setFInputIdx(uNextResult.i);
                    UnicodeSet s = (UnicodeSet) fSets.get(opValue);
                    if (s.contains(c)) {
                        // The character is in the set.  A Match.
                        break;
                    }

                    // the character wasn't in the set.
                    fp = (REStackFrame) fStack.popFrame(fFrameSize);
                }
                break;


                case URX_DOTANY: {
                    // . matches anything, but stops at end-of-line.
                    if (fp.fInputIdx() >= fActiveLimit) {
                        // At end of input.  Match failed.  Backtrack out.
                        fHitEnd = true;
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }

                    // There is input left.  Advance over one char, unless we've hit end-of-line
                    int c;
                    IndexAndChar uNextResult = U16_NEXT(inputBuf, Math.toIntExact(fp.fInputIdx()), Math.toIntExact(fActiveLimit));
                    c = uNextResult.c;
                    fp.setFInputIdx(uNextResult.i);
                    if (isLineTerminator(c)) {
                        // End of line in normal mode.   . does not match.
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }
                }
                break;


                case URX_DOTANY_ALL: {
                    // . in dot-matches-all (including new lines) mode
                    if (fp.fInputIdx() >= fActiveLimit) {
                        // At end of input.  Match failed.  Backtrack out.
                        fHitEnd = true;
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }

                    // There is input left.  Advance over one char, except if we are
                    //   at a cr/lf, advance over both of them.
                    int c;
                    IndexAndChar uNextResult = U16_NEXT(inputBuf, Math.toIntExact(fp.fInputIdx()), Math.toIntExact(fActiveLimit));
                    c = uNextResult.c;
                    fp.setFInputIdx(uNextResult.i);
                    if (c == 0x0d && fp.fInputIdx() < fActiveLimit) {
                        // In the case of a CR/LF, we need to advance over both.
                        if (inputBuf[Math.toIntExact(fp.fInputIdx())] == 0x0a) {
                            fp.setFInputIdx(U16_FWD_1(inputBuf, Math.toIntExact(fp.fInputIdx()), Math.toIntExact(fActiveLimit)));
                        }
                    }
                }
                break;


                case URX_DOTANY_UNIX: {
                    // '.' operator, matches all, but stops at end-of-line.
                    //   UNIX_LINES mode, so 0x0a is the only recognized line ending.
                    if (fp.fInputIdx() >= fActiveLimit) {
                        // At end of input.  Match failed.  Backtrack out.
                        fHitEnd = true;
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }

                    // There is input left.  Advance over one char, unless we've hit end-of-line
                    int c;
                    IndexAndChar uNextResult = U16_NEXT(inputBuf, Math.toIntExact(fp.fInputIdx()), Math.toIntExact(fActiveLimit));
                    c = uNextResult.c;
                    fp.setFInputIdx(uNextResult.i);
                    if (c == 0x0a) {
                        // End of line in normal mode.   '.' does not match the \n
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;


                case URX_JMP:
                    fp.setFPatIdx(opValue);
                    break;

                case URX_FAIL:
                    isMatch = false;
                    break breakFromLoop;

                case URX_JMP_SAV:
                    assert (opValue < fPattern.fCompiledPat.size());
                    fp = StateSave(fp.fPatIdx());       // State save to loc following current
                    fp.setFPatIdx(opValue);                         // Then JMP.
                    break;

                case URX_JMP_SAV_X:
                    // This opcode is used with (x)+, when x can match a zero length string.
                    // Same as JMP_SAV, except conditional on the match having made forward progress.
                    // Destination of the JMP must be a URX_STO_INP_LOC, from which we get the
                    //   data address of the input position at the start of the loop.
                {
                    assert (opValue > 0 && opValue < fPattern.fCompiledPat.size());
                    int stoOp = Math.toIntExact(pat.get(opValue - 1));
                    assert (URX_TYPE(stoOp) == URX_STO_INP_LOC);
                    int frameLoc = URX_VAL(stoOp);
                    assert (frameLoc >= 0 && frameLoc < fFrameSize);
                    int prevInputIdx = Math.toIntExact(fp.fExtra(frameLoc));
                    assert (prevInputIdx <= fp.fInputIdx());
                    if (prevInputIdx < fp.fInputIdx()) {
                        // The match did make progress.  Repeat the loop.
                        fp = StateSave(fp.fPatIdx());  // State save to loc following current
                        fp.setFPatIdx(opValue);
                        fp.setFExtra(frameLoc, fp.fInputIdx());
                    }
                    // If the input position did not advance, we do nothing here,
                    //   execution will fall out of the loop.
                }
                break;

                case URX_CTR_INIT: {
                    assert (opValue >= 0 && opValue < fFrameSize - 2);
                    fp.setFExtra(opValue, 0);                 //  Set the loop counter variable to zero

                    // Pick up the three extra operands that CTR_INIT has, and
                    //    skip the pattern location counter past
                    int instrOperandLoc = Math.toIntExact(fp.fPatIdx());
                    fp.setFPatIdx(fp.fPatIdx() + 3);
                    int loopLoc = URX_VAL(Math.toIntExact(pat.get(instrOperandLoc)));
                    int minCount = Math.toIntExact(pat.get(instrOperandLoc + 1));
                    int maxCount = Math.toIntExact(pat.get(instrOperandLoc + 2));
                    assert (minCount >= 0);
                    assert (maxCount >= minCount || maxCount == -1);
                    assert (loopLoc >= fp.fPatIdx());

                    if (minCount == 0) {
                        fp = StateSave(loopLoc + 1);
                    }
                    if (maxCount == -1) {
                        fp.setFExtra(opValue + 1, fp.fInputIdx());   //  For loop breaking.
                    } else if (maxCount == 0) {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;

                case URX_CTR_LOOP: {
                    assert (opValue > 0 && opValue < fp.fPatIdx() - 2);
                    int initOp = Math.toIntExact(pat.get(opValue));
                    assert (URX_TYPE(initOp) == URX_CTR_INIT);
                    final int pCounterOffset = URX_VAL(initOp);
//        long *pCounter = &fp.fExtra(pCounterOffset);
                    int minCount = Math.toIntExact(pat.get(opValue + 2));
                    int maxCount = Math.toIntExact(pat.get(opValue + 3));
                    fp.setFExtra(pCounterOffset, fp.fExtra(pCounterOffset) + 1);
                    if (fp.fExtra(pCounterOffset) >= (/* uint32 */long) maxCount && maxCount != -1) {
                        assert (fp.fExtra(pCounterOffset) == maxCount);
                        break;
                    }
                    if (fp.fExtra(pCounterOffset) >= minCount) {
                        if (maxCount == -1) {
                            // Loop has no hard upper bound.
                            // Check that it is progressing through the input, break if it is not.
                            final int pLastInputIdxOffset = URX_VAL(initOp) + 1;
                            long pLastInputIdx = fp.fExtra(pLastInputIdxOffset);
                            if (fp.fInputIdx() == fp.fExtra(pLastInputIdxOffset)) {
                                break;
                            } else {
                                fp.setFExtra(pLastInputIdxOffset, fp.fInputIdx());
                            }
                        }
                        fp = StateSave(fp.fPatIdx());
                    } else {
                        // Increment time-out counter. (StateSave() does it if count >= minCount)
                        fTickCounter--;
                        if (fTickCounter <= 0) {
                            IncrementTime();    // Re-initializes fTickCounter
                        }
                    }
                    fp.setFPatIdx(opValue + 4);    // Loop back.
                }
                break;

                case URX_CTR_INIT_NG: {
                    // Initialize a non-greedy loop
                    assert (opValue >= 0 && opValue < fFrameSize - 2);
                    fp.setFExtra(opValue, 0);                 //  Set the loop counter variable to zero

                    // Pick up the three extra operands that CTR_INIT_NG has, and
                    //    skip the pattern location counter past
                    int instrOperandLoc = Math.toIntExact(fp.fPatIdx());
                    fp.setFPatIdx(fp.fPatIdx() + 3);
                    int loopLoc = URX_VAL(Math.toIntExact(pat.get(instrOperandLoc)));
                    int minCount = Math.toIntExact(pat.get(instrOperandLoc + 1));
                    int maxCount = Math.toIntExact(pat.get(instrOperandLoc + 2));
                    assert (minCount >= 0);
                    assert (maxCount >= minCount || maxCount == -1);
                    assert (loopLoc > fp.fPatIdx());
                    if (maxCount == -1) {
                        fp.setFExtra(opValue + 1, fp.fInputIdx());   //  Save initial input index for loop breaking.
                    }

                    if (minCount == 0) {
                        if (maxCount != 0) {
                            fp = StateSave(fp.fPatIdx());
                        }
                        fp.setFPatIdx(loopLoc + 1);   // Continue with stuff after repeated block
                    }
                }
                break;

                case URX_CTR_LOOP_NG: {
                    // Non-greedy {min, max} loops
                    assert (opValue > 0 && opValue < fp.fPatIdx() - 2);
                    int initOp = Math.toIntExact(pat.get(opValue));
                    assert (URX_TYPE(initOp) == URX_CTR_INIT_NG);
                    final int pCounterOffset = URX_VAL(initOp);
//        long *pCounter = &fp.fExtra(pCounterOffset);
                    int minCount = Math.toIntExact(pat.get(opValue + 2));
                    int maxCount = Math.toIntExact(pat.get(opValue + 3));

                    fp.setFExtra(pCounterOffset, fp.fExtra(pCounterOffset) + 1);
                    if (fp.fExtra(pCounterOffset) >= (/* uint32 */long) maxCount && maxCount != -1) {
                        // The loop has matched the maximum permitted number of times.
                        //   Break out of here with no action.  Matching will
                        //   continue with the following pattern.
                        assert (fp.fExtra(pCounterOffset) == maxCount);
                        break;
                    }

                    if (fp.fExtra(pCounterOffset) < minCount) {
                        // We haven't met the minimum number of matches yet.
                        //   Loop back for another one.
                        fp.setFPatIdx(opValue + 4);    // Loop back.
                        fTickCounter--;
                        if (fTickCounter <= 0) {
                            IncrementTime();    // Re-initializes fTickCounter
                        }
                    } else {
                        // We do have the minimum number of matches.

                        // If there is no upper bound on the loop iterations, check that the input index
                        // is progressing, and stop the loop if it is not.
                        if (maxCount == -1) {
                            final int pLastInputIdxOffset = URX_VAL(initOp) + 1;
//        long *pLastInputIdx = &fp.fExtra(pLastInputIdxOffset);
                            if (fp.fInputIdx() == fp.fExtra(pLastInputIdxOffset)) {
                                break;
                            }
                            fp.setFExtra(pLastInputIdxOffset, fp.fInputIdx());
                        }

                        // Loop Continuation: we will fall into the pattern following the loop
                        //   (non-greedy, don't execute loop body first), but first do
                        //   a state save to the top of the loop, so that a match failure
                        //   in the following pattern will try another iteration of the loop.
                        fp = StateSave(opValue + 4);
                    }
                }
                break;

                case URX_STO_SP:
                    assert (opValue >= 0 && opValue < fPattern.fDataSize);
                    fData[opValue] = fStack.size();
                    break;

                case URX_LD_SP: {
                    assert (opValue >= 0 && opValue < fPattern.fDataSize);
                    int newStackSize = Math.toIntExact(fData[opValue]);
                    assert (newStackSize <= fStack.size());
                    IndexedView newFP = fStack.getBufferView(newStackSize - fFrameSize);
                    if (newFP == fp.asIndexedView()) {
                        break;
                    }
                    int j;
                    for (j = 0; j < fFrameSize; j++) {
                        newFP.set(j, fp.asIndexedView().get(j));
                    }
                    fp = newFP.asREStackFrame(fFrameSize);
                    fStack.setSize(newStackSize);
                }
                break;

                case URX_BACKREF: {
                    assert (opValue < fFrameSize);
                    long groupStartIdx = fp.fExtra(opValue);
                    long groupEndIdx = fp.fExtra(opValue + 1);
                    assert (groupStartIdx <= groupEndIdx);
                    long inputIndex = fp.fInputIdx();
                    if (groupStartIdx < 0) {
                        // This capture group has not participated in the match thus far,
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);   // FAIL, no match.
                        break;
                    }
                    boolean success = true;
                    for (long groupIndex = groupStartIdx; groupIndex < groupEndIdx; ++groupIndex, ++inputIndex) {
                        if (inputIndex >= fActiveLimit) {
                            success = false;
                            fHitEnd = true;
                            break;
                        }
                        if (inputBuf[Math.toIntExact(groupIndex)] != inputBuf[Math.toIntExact(inputIndex)]) {
                            success = false;
                            break;
                        }
                    }
                    if (success && groupStartIdx < groupEndIdx && U16_IS_LEAD(inputBuf[Math.toIntExact(groupEndIdx - 1)]) &&
                            inputIndex < fActiveLimit && U16_IS_TRAIL(inputBuf[Math.toIntExact(inputIndex)])) {
                        // Capture group ended with an unpaired lead surrogate.
                        // Back reference is not permitted to match lead only of a surrogatge pair.
                        success = false;
                    }
                    if (success) {
                        fp.setFInputIdx(inputIndex);
                    } else {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;

                case URX_BACKREF_I: {
                    assert (opValue < fFrameSize);
                    long groupStartIdx = fp.fExtra(opValue);
                    long groupEndIdx = fp.fExtra(opValue + 1);
                    assert (groupStartIdx <= groupEndIdx);
                    if (groupStartIdx < 0) {
                        // This capture group has not participated in the match thus far,
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);   // FAIL, no match.
                        break;
                    }
                    CaseFoldingUCharIterator captureGroupItr = new CaseFoldingUCharIterator(inputBuf, groupStartIdx, groupEndIdx);
                    CaseFoldingUCharIterator inputItr = new CaseFoldingUCharIterator(inputBuf, fp.fInputIdx(), fActiveLimit);

                    //   Note: if the capture group match was of an empty string the backref
                    //         match succeeds.  Verified by testing:  Perl matches succeed
                    //         in this case, so we do too.

                    boolean success = true;
                    for (; ; ) {
                        int captureGroupChar = captureGroupItr.next();
                        if (captureGroupChar == U_SENTINEL) {
                            success = true;
                            break;
                        }
                        int inputChar = inputItr.next();
                        if (inputChar == U_SENTINEL) {
                            success = false;
                            fHitEnd = true;
                            break;
                        }
                        if (inputChar != captureGroupChar) {
                            success = false;
                            break;
                        }
                    }

                    if (success && inputItr.inExpansion()) {
                        // We obtained a match by consuming part of a string obtained from
                        // case-folding a single code point of the input text.
                        // This does not count as an overall match.
                        success = false;
                    }

                    if (success) {
                        fp.setFInputIdx(inputItr.getIndex());
                    } else {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;

                case URX_STO_INP_LOC: {
                    assert (opValue >= 0 && opValue < fFrameSize);
                    fp.setFExtra(opValue, fp.fInputIdx());
                }
                break;

                case URX_JMPX: {
                    int instrOperandLoc = Math.toIntExact(fp.fPatIdx());
                    fp.setFPatIdx(fp.fPatIdx() + 1);
                    int dataLoc = URX_VAL(Math.toIntExact(pat.get(instrOperandLoc)));
                    assert (dataLoc >= 0 && dataLoc < fFrameSize);
                    int savedInputIdx = Math.toIntExact(fp.fExtra(dataLoc));
                    assert (savedInputIdx <= fp.fInputIdx());
                    if (savedInputIdx < fp.fInputIdx()) {
                        fp.setFPatIdx(opValue);                               // JMP
                    } else {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);   // FAIL, no progress in loop.
                    }
                }
                break;

                case URX_LA_START: {
                    // Entering a look around block.
                    // Save Stack Ptr, Input Pos.
                    assert (opValue >= 0 && opValue + 3 < fPattern.fDataSize);
                    fData[opValue] = fStack.size();
                    fData[opValue + 1] = fp.fInputIdx();
                    fData[opValue + 2] = fActiveStart;
                    fData[opValue + 3] = fActiveLimit;
                    fActiveStart = fLookStart;          // Set the match region change for
                    fActiveLimit = fLookLimit;          //   transparent bounds.
                }
                break;

                case URX_LA_END: {
                    // Leaving a look around block.
                    //  restore Stack Ptr, Input Pos to positions they had on entry to block.
                    assert (opValue >= 0 && opValue + 3 < fPattern.fDataSize);
                    int stackSize = fStack.size();
                    int newStackSize = Math.toIntExact(fData[opValue]);
                    assert (stackSize >= newStackSize);
                    if (stackSize > newStackSize) {
                        // Copy the current top frame back to the new (cut back) top frame.
                        //   This makes the capture groups from within the look-ahead
                        //   expression available.
                        IndexedView newFP = fStack.getBufferView(newStackSize - fFrameSize);
                        int j;
                        for (j = 0; j < fFrameSize; j++) {
                            newFP.set(j, fp.asIndexedView().get(j));
                        }
                        fp = newFP.asREStackFrame(fFrameSize);
                        fStack.setSize(newStackSize);
                    }
                    fp.setFInputIdx(fData[opValue + 1]);

                    // Restore the active region bounds in the input string; they may have
                    //    been changed because of transparent bounds on a Region.
                    fActiveStart = fData[opValue + 2];
                    fActiveLimit = fData[opValue + 3];
                    assert (fActiveStart >= 0);
                    assert (fActiveLimit <= fInputLength);
                }
                break;

                case URX_ONECHAR_I:
                    if (fp.fInputIdx() < fActiveLimit) {
                        int c;
                        IndexAndChar uNextResult = U16_NEXT(inputBuf, Math.toIntExact(fp.fInputIdx()), Math.toIntExact(fActiveLimit));
                        c = uNextResult.c;
                        fp.setFInputIdx(uNextResult.i);
                        if (UCharacter.foldCase(c, FOLD_CASE_DEFAULT) == opValue) {
                            break;
                        }
                    } else {
                        fHitEnd = true;
                    }
                    fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    break;

                case URX_STRING_I:
                    // Case-insensitive test input against a literal string.
                    // Strings require two slots in the compiled pattern, one for the
                    //   offset to the string text, and one for the length.
                    //   The compiled string has already been case folded.
                {
                    final StringBufferView patternString = new StringBufferView(litText, opValue);

                    op = Math.toIntExact(pat.get(Math.toIntExact(fp.fPatIdx())));
                    fp.postIncrementFPatIdx();
                    opType = URX_TYPE(op);
                    opValue = URX_VAL(op);
                    assert (opType == URX_STRING_LEN);
                    int patternStringLen = opValue;  // Length of the string from the pattern.

                    int cText;
                    int cPattern;
                    boolean success = true;
                    int patternStringIdx = 0;
                    CaseFoldingUCharIterator inputIterator = new CaseFoldingUCharIterator(inputBuf, fp.fInputIdx(), fActiveLimit);
                    while (patternStringIdx < patternStringLen) {
                        IndexAndChar uNextResult = U16_NEXT(patternString.toCharArray(), patternStringIdx, patternStringLen);
                        cPattern = uNextResult.c;
                        patternStringIdx = uNextResult.i;
                        cText = inputIterator.next();
                        if (cText != cPattern) {
                            success = false;
                            if (cText == U_SENTINEL) {
                                fHitEnd = true;
                            }
                            break;
                        }
                    }
                    if (inputIterator.inExpansion()) {
                        success = false;
                    }

                    if (success) {
                        fp.setFInputIdx(inputIterator.getIndex());
                    } else {
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                    }
                }
                break;

                case URX_LB_START: {
                    // Entering a look-behind block.
                    // Save Stack Ptr, Input Pos and active input region.
                    //   TODO:  implement transparent bounds.  Ticket #6067
                    assert (opValue >= 0 && opValue + 4 < fPattern.fDataSize);
                    fData[opValue] = fStack.size();
                    fData[opValue + 1] = fp.fInputIdx();
                    // Save input string length, then reset to pin any matches to end at
                    //   the current position.
                    fData[opValue + 2] = fActiveStart;
                    fData[opValue + 3] = fActiveLimit;
                    fActiveStart = fRegionStart;
                    fActiveLimit = fp.fInputIdx();
                    // Init the variable containing the start index for attempted matches.
                    fData[opValue + 4] = -1;
                }
                break;


                case URX_LB_CONT: {
                    // Positive Look-Behind, at top of loop checking for matches of LB expression
                    //    at all possible input starting positions.

                    // Fetch the min and max possible match lengths.  They are the operands
                    //   of this op in the pattern.
                    int minML = Math.toIntExact(pat.get(Math.toIntExact(fp.postIncrementFPatIdx())));
                    int maxML = Math.toIntExact(pat.get(Math.toIntExact(fp.postIncrementFPatIdx())));
                    assert (minML <= maxML);
                    assert (minML >= 0);

                    // Fetch (from data) the last input index where a match was attempted.
                    assert (opValue >= 0 && opValue + 4 < fPattern.fDataSize);
                    int lbStartIdxOffset = opValue + 4;
//        long  *lbStartIdx = &fData[lbStartIdxOffset];
                    if (fData[lbStartIdxOffset] < 0) {
                        // First time through loop.
                        fData[lbStartIdxOffset] = fp.fInputIdx() - minML;
                        if (fData[lbStartIdxOffset] > 0 && fData[lbStartIdxOffset] < fInputLength) {
                            fData[lbStartIdxOffset] = U16_SET_CP_START(inputBuf, 0, Math.toIntExact(fData[lbStartIdxOffset]));
                        }
                    } else {
                        // 2nd through nth time through the loop.
                        // Back up start position for match by one.
                        if (fData[lbStartIdxOffset] == 0) {
                            fData[lbStartIdxOffset]--;
                        } else {
                            fData[lbStartIdxOffset] = U16_BACK_1(inputBuf, 0, Math.toIntExact(fData[lbStartIdxOffset]));
                        }
                    }

                    if (fData[lbStartIdxOffset] < 0 || fData[lbStartIdxOffset] < fp.fInputIdx() - maxML) {
                        // We have tried all potential match starting points without
                        //  getting a match.  Backtrack out, and out of the
                        //   Look Behind altogether.
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        fActiveStart = fData[opValue + 2];
                        fActiveLimit = fData[opValue + 3];
                        assert (fActiveStart >= 0);
                        assert (fActiveLimit <= fInputLength);
                        break;
                    }

                    //    Save state to this URX_LB_CONT op, so failure to match will repeat the loop.
                    //      (successful match will fall off the end of the loop.)
                    fp = StateSave(fp.fPatIdx() - 3);
                    fp.setFInputIdx(fData[lbStartIdxOffset]);
                }
                break;

                case URX_LB_END:
                    // End of a look-behind block, after a successful match.
                {
                    assert (opValue >= 0 && opValue + 4 < fPattern.fDataSize);
                    if (fp.fInputIdx() != fActiveLimit) {
                        //  The look-behind expression matched, but the match did not
                        //    extend all the way to the point that we are looking behind from.
                        //  FAIL out of here, which will take us back to the LB_CONT, which
                        //     will retry the match starting at another position or fail
                        //     the look-behind altogether, whichever is appropriate.
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }

                    // Look-behind match is good.  Restore the original input string region,
                    //   which had been truncated to pin the end of the lookbehind match to the
                    //   position being looked-behind.
                    fActiveStart = fData[opValue + 2];
                    fActiveLimit = fData[opValue + 3];
                    assert (fActiveStart >= 0);
                    assert (fActiveLimit <= fInputLength);
                }
                break;


                case URX_LBN_CONT: {
                    // Negative Look-Behind, at top of loop checking for matches of LB expression
                    //    at all possible input starting positions.

                    // Fetch the extra parameters of this op.
                    int minML = Math.toIntExact(pat.get(Math.toIntExact(fp.postIncrementFPatIdx())));
                    int maxML = Math.toIntExact(pat.get(Math.toIntExact(fp.postIncrementFPatIdx())));
                    int continueLoc = Math.toIntExact(pat.get(Math.toIntExact(fp.postIncrementFPatIdx())));
                    continueLoc = URX_VAL(continueLoc);
                    assert (minML <= maxML);
                    assert (minML >= 0);
                    assert (continueLoc > fp.fPatIdx());

                    // Fetch (from data) the last input index where a match was attempted.
                    assert (opValue >= 0 && opValue + 4 < fPattern.fDataSize);
                    int lbStartIdxOffset = opValue + 4;
                    long lbStartIdx = fData[lbStartIdxOffset];
                    if (fData[lbStartIdxOffset] < 0) {
                        // First time through loop.
                        fData[lbStartIdxOffset] = fp.fInputIdx() - minML;
                        if (fData[lbStartIdxOffset] > 0 && fData[lbStartIdxOffset] < fInputLength) {
                            fData[lbStartIdxOffset] = U16_SET_CP_START(inputBuf, 0, Math.toIntExact(fData[lbStartIdxOffset]));
                        }
                    } else {
                        // 2nd through nth time through the loop.
                        // Back up start position for match by one.
                        if (fData[lbStartIdxOffset] == 0) {
                            fData[lbStartIdxOffset]--;   // Because U16_BACK is unsafe starting at 0.
                        } else {
                            fData[lbStartIdxOffset] = U16_BACK_1(inputBuf, 0, Math.toIntExact(fData[lbStartIdxOffset]));
                        }
                    }

                    if (fData[lbStartIdxOffset] < 0 || fData[lbStartIdxOffset] < fp.fInputIdx() - maxML) {
                        // We have tried all potential match starting points without
                        //  getting a match, which means that the negative lookbehind as
                        //  a whole has succeeded.  Jump forward to the continue location
                        fActiveStart = fData[opValue + 2];
                        fActiveLimit = fData[opValue + 3];
                        assert (fActiveStart >= 0);
                        assert (fActiveLimit <= fInputLength);
                        fp.setFPatIdx(continueLoc);
                        break;
                    }

                    //    Save state to this URX_LB_CONT op, so failure to match will repeat the loop.
                    //      (successful match will cause a FAIL out of the loop altogether.)
                    fp = StateSave(fp.fPatIdx() - 4);
                    fp.setFInputIdx(fData[lbStartIdxOffset]);
                }
                break;

                case URX_LBN_END:
                    // End of a negative look-behind block, after a successful match.
                {
                    assert (opValue >= 0 && opValue + 4 < fPattern.fDataSize);
                    if (fp.fInputIdx() != fActiveLimit) {
                        //  The look-behind expression matched, but the match did not
                        //    extend all the way to the point that we are looking behind from.
                        //  FAIL out of here, which will take us back to the LB_CONT, which
                        //     will retry the match starting at another position or succeed
                        //     the look-behind altogether, whichever is appropriate.
                        fp = (REStackFrame) fStack.popFrame(fFrameSize);
                        break;
                    }

                    // Look-behind expression matched, which means look-behind test as
                    //   a whole Fails

                    //   Restore the original input string length, which had been truncated
                    //   inorder to pin the end of the lookbehind match
                    //   to the position being looked-behind.
                    fActiveStart = fData[opValue + 2];
                    fActiveLimit = fData[opValue + 3];
                    assert (fActiveStart >= 0);
                    assert (fActiveLimit <= fInputLength);

                    // Restore original stack position, discarding any state saved
                    //   by the successful pattern match.
                    assert (opValue >= 0 && opValue + 1 < fPattern.fDataSize);
                    int newStackSize = Math.toIntExact(fData[opValue]);
                    assert (fStack.size() > newStackSize);
                    fStack.setSize(newStackSize);

                    //  FAIL, which will take control back to someplace
                    //  prior to entering the look-behind test.
                    fp = (REStackFrame) fStack.popFrame(fFrameSize);
                }
                break;


                case URX_LOOP_SR_I:
                    // Loop Initialization for the optimized implementation of
                    //     [some character set]*
                    //   This op scans through all matching input.
                    //   The following LOOP_C op emulates stack unwinding if the following pattern fails.
                {
                    assert (opValue > 0 && opValue < fSets.size());
                    UnicodeSet s = (UnicodeSet) fSets.get(opValue);

                    // Loop through input, until either the input is exhausted or
                    //   we reach a character that is not a member of the set.
                    int ix = Math.toIntExact(Math.toIntExact(fp.fInputIdx()));
                    for (; ; ) {
                        if (ix >= fActiveLimit) {
                            fHitEnd = true;
                            break;
                        }
                        int c;
                        IndexAndChar uNextResult = U16_NEXT(inputBuf, ix, Math.toIntExact(fActiveLimit));
                        c = uNextResult.c;
                        ix = uNextResult.i;
                        if (s.contains(c) == false) {
                            ix = U16_BACK_1(inputBuf, 0, ix);
                            break;
                        }
                    }

                    // If there were no matching characters, skip over the loop altogether.
                    //   The loop doesn't run at all, a * op always succeeds.
                    if (ix == fp.fInputIdx()) {
                        fp.postIncrementFPatIdx();   // skip the URX_LOOP_C op.
                        break;
                    }

                    // Peek ahead in the compiled pattern, to the URX_LOOP_C that
                    //   must follow.  It's operand is the stack location
                    //   that holds the starting input index for the match of this [set]*
                    int loopcOp = Math.toIntExact(pat.get(Math.toIntExact(fp.fPatIdx())));
                    assert (URX_TYPE(loopcOp) == URX_LOOP_C);
                    int stackLoc = URX_VAL(loopcOp);
                    assert (stackLoc >= 0 && stackLoc < fFrameSize);
                    fp.setFExtra(stackLoc, fp.fInputIdx());
                    fp.setFInputIdx(ix);

                    // Save State to the URX_LOOP_C op that follows this one,
                    //   so that match failures in the following code will return to there.
                    //   Then bump the pattern idx so the LOOP_C is skipped on the way out of here.
                    fp = StateSave(fp.fPatIdx());
                    fp.postIncrementFPatIdx();
                }
                break;


                case URX_LOOP_DOT_I:
                    // Loop Initialization for the optimized implementation of .*
                    //   This op scans through all remaining input.
                    //   The following LOOP_C op emulates stack unwinding if the following pattern fails.
                {
                    // Loop through input until the input is exhausted (we reach an end-of-line)
                    // In DOTALL mode, we can just go straight to the end of the input.
                    int ix;
                    if ((opValue & 1) == 1) {
                        // Dot-matches-All mode.  Jump straight to the end of the string.
                        ix = Math.toIntExact(fActiveLimit);
                        fHitEnd = true;
                    } else {
                        // NOT DOT ALL mode.  Line endings do not match '.'
                        // Scan forward until a line ending or end of input.
                        ix = (int) Math.toIntExact(fp.fInputIdx());
                        for (; ; ) {
                            if (ix >= fActiveLimit) {
                                fHitEnd = true;
                                break;
                            }
                            int c;
                            IndexAndChar indexAndChar = U16_NEXT(inputBuf, ix, Math.toIntExact(fActiveLimit));// c = inputBuf[ix++]
                            ix = indexAndChar.i;
                            c = indexAndChar.c;
                            if ((c & 0x7f) <= 0x29) {          // Fast filter of non-new-line-s
                                if ((c == 0x0a) ||             //  0x0a is newline in both modes.
                                        (((opValue & 2) == 0) &&    // IF not UNIX_LINES mode
                                                isLineTerminator(c))) {
                                    //  char is a line ending.  Put the input pos back to the
                                    //    line ending char, and exit the scanning loop.
                                    ix = U16_BACK_1(inputBuf, 0, ix);
                                    break;
                                }
                            }
                        }
                    }

                    // If there were no matching characters, skip over the loop altogether.
                    //   The loop doesn't run at all, a * op always succeeds.
                    if (ix == fp.fInputIdx()) {
                        fp.postIncrementFPatIdx();   // skip the URX_LOOP_C op.
                        break;
                    }

                    // Peek ahead in the compiled pattern, to the URX_LOOP_C that
                    //   must follow.  It's operand is the stack location
                    //   that holds the starting input index for the match of this .*
                    int loopcOp = Math.toIntExact(pat.get(Math.toIntExact(fp.fPatIdx())));
                    assert (URX_TYPE(loopcOp) == URX_LOOP_C);
                    int stackLoc = URX_VAL(loopcOp);
                    assert (stackLoc >= 0 && stackLoc < fFrameSize);
                    fp.setFExtra(stackLoc, fp.fInputIdx());
                    fp.setFInputIdx(ix);

                    // Save State to the URX_LOOP_C op that follows this one,
                    //   so that match failures in the following code will return to there.
                    //   Then bump the pattern idx so the LOOP_C is skipped on the way out of here.
                    fp = StateSave(fp.fPatIdx());
                    fp.postIncrementFPatIdx();
                }
                break;


                case URX_LOOP_C: {
                    assert (opValue >= 0 && opValue < fFrameSize);
                    backSearchIndex = Math.toIntExact(fp.fExtra(opValue));
                    assert (backSearchIndex <= fp.fInputIdx());
                    if (backSearchIndex == fp.fInputIdx()) {
                        // We've backed up the input idx to the point that the loop started.
                        // The loop is done.  Leave here without saving state.
                        //  Subsequent failures won't come back here.
                        break;
                    }
                    // Set up for the next iteration of the loop, with input index
                    //   backed up by one from the last time through,
                    //   and a state save to this instruction in case the following code fails again.
                    //   (We're going backwards because this loop emulates stack unwinding, not
                    //    the initial scan forward.)
                    assert (fp.fInputIdx() > 0);
                    int prevC;
                    IndexAndChar indexAndChar = U16_PREV(inputBuf, 0, Math.toIntExact(fp.fInputIdx()));// !!!: should this 0 be one of f*Limit?
                    fp.setFInputIdx(indexAndChar.i);
                    prevC = indexAndChar.c;

                    if (prevC == 0x0a &&
                            fp.fInputIdx() > backSearchIndex &&
                            inputBuf[Math.toIntExact(fp.fInputIdx()) - 1] == 0x0d) {
                        int prevOp = Math.toIntExact(pat.get(Math.toIntExact(fp.fPatIdx() - 2)));
                        if (URX_TYPE(prevOp) == URX_LOOP_DOT_I) {
                            // .*, stepping back over CRLF pair.
                            fp.setFInputIdx(U16_BACK_1(inputBuf, 0, Math.toIntExact(fp.fInputIdx())));
                        }
                    }


                    fp = StateSave(fp.fPatIdx() - 1);
                }
                break;


                default:
                    // Trouble.  The compiled pattern contains an entry with an
                    //           unrecognized type tag.
                    assert false;
                    // Unknown opcode type in opType = URX_TYPE(pat.get(fp.fPatIdx())). But we have
                    // reports of this in production code, don't use throw new IllegalStateException().
                    // See ICU-21669.
                    throw new IllegalStateException();
            }

            if (false) {
                isMatch = false;
                break;
            }
        }

        fMatch = isMatch;
        if (isMatch) {
            fLastMatchEnd = fMatchEnd;
            fMatchStart = startIdx;
            fMatchEnd = fp.fInputIdx();
        }

        fFrame = fp;                // The active stack frame when the engine stopped.
        //   Contains the capture group results that we need to
        //    access later.

        return;
    }


}