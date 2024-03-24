// New code and changes are © 2024 TarCV
// © 2016 and later: Unicode, Inc. and others.
// License & terms of use: http://www.unicode.org/copyright.html
/* ******************************************************************
 * COPYRIGHT:
 * Copyright (c) 2002-2016, International Business Machines Corporation and
 * others. All Rights Reserved.
 ********************************************************************/
package com.github.tarcv.u4jregex;

import com.ibm.icu.impl.Utility;
import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.text.ReplaceableString;
import com.ibm.icu.text.UnicodeSet;
import com.ibm.icu.text.UnicodeSetIterator;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.github.tarcv.u4jregex.UErrorCode.*;
import static com.github.tarcv.u4jregex.UInvChar.uprv_isInvariantUString;
import static com.github.tarcv.u4jregex.URegexpFlag.*;
import static com.github.tarcv.u4jregex.Util.*;
import static com.ibm.icu.lang.UProperty.CASE_SENSITIVE;
import static com.ibm.icu.text.UnicodeSet.CASE_INSENSITIVE;

/**
 * ICU4C Regular Expressions test, part of intltest.
 */
public class RegexTest {
    private static final Logger LOGGER = Logger.getLogger(RegexTest.class.getName());

//---------------------------------------------------------------------------
//
//   Error Checking / Reporting macros used in all of the tests.
//
//---------------------------------------------------------------------------

    static void utextToPrintable(final char[] buf, final int bufLen, final Util.StringWithOffset text) {
        long oldIndex = Util.utext_getNativeIndex(text);
        Util.utext_setNativeIndex(text, 0);
        CharArrayView bufPtr = new CharArrayView(buf, 0);
        int c = utext_next32From(text, 0);
        while ((c != Util.U_SENTINEL) && (bufPtr.baseOffset < bufLen)) {
            if (0x000020<=c && c<0x00007e) {
                bufPtr.set(0, Util.toCharExact(c));
            } else {
                bufPtr.set(0,'%');
            }
            bufPtr.next();
            c = Util.utext_next32(text);
        }
        bufPtr.set(0, (char) 0);
        Util.utext_setNativeIndex(text, Math.toIntExact(oldIndex));
    }


    static void REGEX_VERBOSE_TEXT(final String text) {
    REGEX_VERBOSE_TEXT(new StringWithOffset(text));
}
static void REGEX_VERBOSE_TEXT(final Util.StringWithOffset text) {
        char[] buf = new char[200];
        utextToPrintable(buf,UPRV_LENGTHOF(buf), text);
        LOGGER.info(() -> String.format("UText %s=\"%s\"",  text.targetString, Arrays.toString(buf)));
    }

    static void REGEX_ASSERT(final boolean expr) {
    Assert.assertTrue(expr);
}

static void REGEX_ASSERT_FAIL(final Runnable expr, final UErrorCode errcode) {
    UErrorCode status = U_ZERO_ERROR;
    try {
            expr.run();
        } catch (final UErrorException e) {
            status = e.getErrorCode();
        } catch (final IllegalStateException e) {
            status = U_REGEX_INVALID_STATE;
        } catch (final IllegalArgumentException e) {
            status = UErrorCode.U_ILLEGAL_ARGUMENT_ERROR;
        }
        Assert.assertEquals(errcode, status);
    }

    // expected: final char[]  , restricted to invariant characters.
// actual: final StringBuffer
static void REGEX_ASSERT_UNISTR(final String expected, final String actual) {
    Assert.assertTrue(uprv_isInvariantUString(actual));
    Assert.assertEquals(expected, actual);
    }


    /**
     * @param expected expected text in UTF-8 (not platform) codepage
     */
    static void assertUText(final byte[] expected, final String actual) {

        String expectedText = new String(expected, StandardCharsets.UTF_8);
        Assert.assertEquals(expectedText, actual);
    }
    /**
     * @param expected invariant (platform local text) input
     */
    static void assertUTextInvariant(final byte[] expected, final String actual) {

        String expectedText = new String(expected);
        if (!uprv_isInvariantUString(expectedText)) {
            throw new IllegalArgumentException();
        }
        Assert.assertEquals(expectedText, actual);
    }

/**
 * Assumes utf-8 input
 */
static void REGEX_ASSERT_UTEXT_UTF8(final byte[] expected, String actual) {
    assertUText((expected), (actual));
}
/**
 * Assumes Invariant input
 */
            static void REGEX_ASSERT_UTEXT_INVARIANT(final byte[] expected, String actual) {
                assertUTextInvariant((expected), (actual));
            }

    static String regextst_openUTF8FromInvariant(final byte[] inv, final long length) {
        return new String(inv, StandardCharsets.UTF_8);
    }


//---------------------------------------------------------------------------
//
//    REGEX_TESTLM       Macro + invocation function to simplify writing quick tests
//                       for the LookingAt() and  Match() functions.
//
//       usage:
//          REGEX_TESTLM("pattern",  "input text",  lookingAt expected, matches expected);
//
//          The expected results are boolean - true or false.
//          The input text is unescaped.  The pattern is not.
//
//
//---------------------------------------------------------------------------

static void REGEX_TESTLM(final String pat, final String text, final boolean looking, final boolean match) {
        doRegexLMTest(pat.toCharArray(), text.toCharArray(), looking, match);
        doRegexLMTestUTF8(pat.toCharArray(), text.toCharArray(), looking, match);
    }

    static void doRegexLMTest(final char[] pat, final char[] text, final boolean looking, final boolean match) {
        RegexPattern        REPattern;
        RegexMatcher        REMatcher;

        REPattern = RegexPattern.compile(new String(pat), Collections.emptySet());

        StringBuffer inputString = new StringBuffer(new String(text));
        REMatcher = REPattern.matcher(Utility.unescape(inputString));

        boolean actualmatch;
        actualmatch = REMatcher.lookingAt();
        Assert.assertEquals("RegexTest: wrong return from lookingAt()", looking, actualmatch);
        actualmatch = REMatcher.matches();
        Assert.assertEquals("RegexTest: wrong return from matches()", match, actualmatch);
    }


    static void doRegexLMTestUTF8(final char[] pat, final char[] text, boolean looking, boolean match) {
        String               pattern;
        int             inputUTF8Length;
        byte[] textChars = null;
        String               inputText;

        RegexPattern        REPattern = null;
        RegexMatcher        REMatcher = null;
        boolean               retVal     = true;

        pattern = regextst_openUTF8FromInvariant(Util.toByteArray(pat), -1);
        REPattern = RegexPattern.compile(pattern, Collections.emptySet());

        StringBuffer inputString = new StringBuffer(new String(text));
        textChars = Utility.unescape(inputString).getBytes(StandardCharsets.UTF_8);
        inputText = new String(textChars, StandardCharsets.UTF_8);

        REMatcher = REPattern.matcher().reset(inputText);

        boolean actualmatch = REMatcher.lookingAt();
        Assert.assertEquals("RegexTest: wrong return from lookingAt() (UTF8)", looking, actualmatch);

        actualmatch = REMatcher.matches();
        Assert.assertEquals("RegexTest: wrong return from matches() at line %d (UTF8)", match, actualmatch);
    }



//---------------------------------------------------------------------------
//
//    regex_err       Macro + invocation function to simplify writing tests
//                       regex tests for incorrect patterns
//
//       usage:
//          regex_err("pattern",   expected error line, column, expected status);
//
//---------------------------------------------------------------------------
    void regex_err(final String patStr, int errLine, int errCol,
                              UErrorCode expectedStatus) {
        StringBuffer       pattern = new StringBuffer(patStr);


        RegexPattern        callerPattern = null;

        //
        //  Compile the caller's pattern
        //
        StringBuffer patString = new StringBuffer(patStr);

        try {
            callerPattern = RegexPattern.compile(patString.toString(), Collections.emptySet());
        } catch (RegexParseException e) {
            Assert.assertEquals(expectedStatus, e.getErrorCode());
            Assert.assertEquals(errLine, e.getLine());
            Assert.assertEquals(errCol, e.getOffset());
        } catch (Throwable e) {
            throw new AssertionError("Unexpected exception", e);
        }

        //
        //  Compile again, using a UTF-8-based String
        //
        String patternText;
        patternText = regextst_openUTF8FromInvariant(Util.toByteArray(patStr.toCharArray()), -1);
        try {
            callerPattern = RegexPattern.compile(patternText, Collections.emptySet());
        } catch (RegexParseException e) {
            Assert.assertEquals(expectedStatus, e.getErrorCode());
            Assert.assertEquals(errLine, e.getLine());
            Assert.assertEquals(errCol, e.getOffset());
        } catch (Throwable e) {
            throw new AssertionError("Unexpected exception", e);
        }
    }



    //---------------------------------------------------------------------------
//
//      Basic      Check for basic functionality of regex pattern matching.
//                 Avoid the use of REGEX_FIND test macro, which has
//                 substantial dependencies on basic Regex functionality.
//
//---------------------------------------------------------------------------
    @Test
    public void Basic() {
        //
        // Pattern with parentheses
        //
        REGEX_TESTLM("st(abc)ring", "stabcring thing", true,  false);
        REGEX_TESTLM("st(abc)ring", "stabcring",       true,  true);
        REGEX_TESTLM("st(abc)ring", "stabcrung",       false, false);

        //
        // Patterns with *
        //
        REGEX_TESTLM("st(abc)*ring", "string", true, true);
        REGEX_TESTLM("st(abc)*ring", "stabcring", true, true);
        REGEX_TESTLM("st(abc)*ring", "stabcabcring", true, true);
        REGEX_TESTLM("st(abc)*ring", "stabcabcdring", false, false);
        REGEX_TESTLM("st(abc)*ring", "stabcabcabcring etc.", true, false);

        REGEX_TESTLM("a*", "",  true, true);
        REGEX_TESTLM("a*", "b", true, false);


        //
        //  Patterns with "."
        //
        REGEX_TESTLM(".", "abc", true, false);
        REGEX_TESTLM("...", "abc", true, true);
        REGEX_TESTLM("....", "abc", false, false);
        REGEX_TESTLM(".*", "abcxyz123", true, true);
        REGEX_TESTLM("ab.*xyz", "abcdefghij", false, false);
        REGEX_TESTLM("ab.*xyz", "abcdefg...wxyz", true, true);
        REGEX_TESTLM("ab.*xyz", "abcde...wxyz...abc..xyz", true, true);
        REGEX_TESTLM("ab.*xyz", "abcde...wxyz...abc..xyz...", true, false);

        //
        //  Patterns with * applied to chars at end of literal string
        //
        REGEX_TESTLM("abc*", "ab", true, true);
        REGEX_TESTLM("abc*", "abccccc", true, true);

        //
        //  Supplemental chars match as single chars, not a pair of surrogates.
        //
        REGEX_TESTLM(".", "\\U00011000", true, true);
        REGEX_TESTLM("...", "\\U00011000x\\U00012002", true, true);
        REGEX_TESTLM("...", "\\U00011000x\\U00012002y", true, false);


        //
        //  UnicodeSets in the pattern
        //
        REGEX_TESTLM("[1-6]", "1", true, true);
        REGEX_TESTLM("[1-6]", "3", true, true);
        REGEX_TESTLM("[1-6]", "7", false, false);
        REGEX_TESTLM("a[1-6]", "a3", true, true);
        REGEX_TESTLM("a[1-6]", "a3", true, true);
        REGEX_TESTLM("a[1-6]b", "a3b", true, true);

        REGEX_TESTLM("a[0-9]*b", "a123b", true, true);
        REGEX_TESTLM("a[0-9]*b", "abc", true, false);
        REGEX_TESTLM("[\\p{Nd}]*", "123456", true, true);
        REGEX_TESTLM("[\\p{Nd}]*", "a123456", true, false);   // note that * matches 0 occurrences.
        REGEX_TESTLM("[a][b][[:Zs:]]*", "ab   ", true, true);

        //
        //   OR operator in patterns
        //
        REGEX_TESTLM("(a|b)", "a", true, true);
        REGEX_TESTLM("(a|b)", "b", true, true);
        REGEX_TESTLM("(a|b)", "c", false, false);
        REGEX_TESTLM("a|b", "b", true, true);

        REGEX_TESTLM("(a|b|c)*", "aabcaaccbcabc", true, true);
        REGEX_TESTLM("(a|b|c)*", "aabcaaccbcabdc", true, false);
        REGEX_TESTLM("(a(b|c|d)(x|y|z)*|123)", "ac", true, true);
        REGEX_TESTLM("(a(b|c|d)(x|y|z)*|123)", "123", true, true);
        REGEX_TESTLM("(a|(1|2)*)(b|c|d)(x|y|z)*|123", "123", true, true);
        REGEX_TESTLM("(a|(1|2)*)(b|c|d)(x|y|z)*|123", "222211111czzzzw", true, false);

        //
        //  +
        //
        REGEX_TESTLM("ab+", "abbc", true, false);
        REGEX_TESTLM("ab+c", "ac", false, false);
        REGEX_TESTLM("b+", "", false, false);
        REGEX_TESTLM("(abc|def)+", "defabc", true, true);
        REGEX_TESTLM(".+y", "zippity dooy dah ", true, false);
        REGEX_TESTLM(".+y", "zippity dooy", true, true);

        //
        //   ?
        //
        REGEX_TESTLM("ab?", "ab", true, true);
        REGEX_TESTLM("ab?", "a", true, true);
        REGEX_TESTLM("ab?", "ac", true, false);
        REGEX_TESTLM("ab?", "abb", true, false);
        REGEX_TESTLM("a(b|c)?d", "abd", true, true);
        REGEX_TESTLM("a(b|c)?d", "acd", true, true);
        REGEX_TESTLM("a(b|c)?d", "ad", true, true);
        REGEX_TESTLM("a(b|c)?d", "abcd", false, false);
        REGEX_TESTLM("a(b|c)?d", "ab", false, false);

        //
        //  Escape sequences that become single literal chars, should be handled
        //   internally by ICU's Unescape.
        //

        // REGEX_TESTLM("\101\142", "Ab", true, true);      // Octal     TODO: not implemented yet.
        REGEX_TESTLM("\\a", "\\u0007", true, true);        // BEL
        REGEX_TESTLM("\\cL", "\\u000c", true, true);       // Control-L
        REGEX_TESTLM("\\e", "\\u001b", true, true);        // Escape
        REGEX_TESTLM("\\f", "\\u000c", true, true);        // Form Feed
        REGEX_TESTLM("\\n", "\\u000a", true, true);        // new line
        REGEX_TESTLM("\\r", "\\u000d", true, true);        //  CR
        REGEX_TESTLM("\\t", "\\u0009", true, true);        // Tab
        REGEX_TESTLM("\\u1234", "\\u1234", true, true);
        REGEX_TESTLM("\\U00001234", "\\u1234", true, true);

        REGEX_TESTLM(".*\\Ax", "xyz", true, false);  //  \A matches only at the beginning of input
        REGEX_TESTLM(".*\\Ax", " xyz", false, false);  //  \A matches only at the beginning of input

        // Escape of special chars in patterns
        REGEX_TESTLM("\\\\\\|\\(\\)\\[\\{\\~\\$\\*\\+\\?\\.", "\\\\|()[{~$*+?.", true, true);
    }


    //---------------------------------------------------------------------------
//
//    UTextBasic   Check for quirks that are specific to the String
//                 implementation.
//
//---------------------------------------------------------------------------
    @Test
    public void UTextBasic() {
    final byte[] str_abc = { 0x61, 0x62, 0x63 }; /* abc */
        String pattern;
        pattern = utext_openUTF8(str_abc, -1);
        RegexMatcher matcher = new RegexMatcher(pattern, Collections.emptySet());

        String input;
        input = utext_openUTF8(str_abc, -1);
        matcher.reset(input);
        REGEX_ASSERT_UTEXT_UTF8(str_abc, matcher.input());

        matcher.reset(matcher.input());
        REGEX_ASSERT_UTEXT_UTF8(str_abc, matcher.input());
    }


    //---------------------------------------------------------------------------
//
//      API_Match   Test that the API for class RegexMatcher
//                  is present and nominally working, but excluding functions
//                  implementing replace operations.
//
//---------------------------------------------------------------------------
    @Test
    public void API_Match() {


        //
        // Simple pattern compilation
        //
        {
            Set<URegexpFlag> flags = Collections.emptySet();
            StringBuffer       re = new StringBuffer("abc");
            RegexPattern        pat2;
            pat2 = RegexPattern.compile(re.toString(), flags);

            StringBuffer inStr1 = new StringBuffer("abcdef this is a test");
            StringBuffer instr2 = new StringBuffer("not abc");
            StringBuffer empty  = new StringBuffer();


            //
            // Matcher creation and reset.
            //
            RegexMatcher m1 = pat2.matcher(inStr1.toString());
            Assert.assertTrue(m1.lookingAt());
            Assert.assertEquals(inStr1.toString(), m1.input().toString());
            m1.reset(instr2.toString());
            Assert.assertFalse(m1.lookingAt());
            Assert.assertEquals(instr2.toString(), m1.input().toString());
            m1.reset(inStr1.toString());
            Assert.assertEquals(inStr1.toString(), m1.input().toString());
            Assert.assertTrue(m1.lookingAt());
            m1.reset(empty.toString());
            Assert.assertFalse(m1.lookingAt());
            Assert.assertEquals(empty.toString(), m1.input().toString());
            Assert.assertEquals(pat2, m1.pattern());

            //
            //  reset(pos)
            //
            m1.reset(inStr1.toString());
            m1.reset(4);
            Assert.assertEquals(inStr1.toString(), m1.input().toString());
            Assert.assertTrue(m1.lookingAt());

            Assert.assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> m1.reset(-1)
            );


            m1.reset(0);

            {
                final int len = m1.input().length();
                m1.reset(len-1);


                m1.reset(len);


                Assert.assertThrows(
                        IndexOutOfBoundsException.class,
                        () -> m1.reset(len+1)
                );
            }

            //
            // match(pos)
            //
            m1.reset(instr2.toString());
            REGEX_ASSERT(m1.matches(4) == true);
            m1.reset();
            REGEX_ASSERT(m1.matches(3) == false);
            m1.reset();
            REGEX_ASSERT(m1.matches(5) == false);
            REGEX_ASSERT(m1.matches(4) == true);
            Assert.assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> m1.matches(-1)
            );

            // Match() at end of string should fail, but should not
            //  be an error.

            {
                final int len = m1.input().length();
                REGEX_ASSERT(m1.matches(len) == false);

                // Match beyond end of string should fail with an error.

                Assert.assertThrows(
                        IndexOutOfBoundsException.class,
                        () -> m1.matches(len + 1)
                );
            }

            // Successful match at end of string.
            {

                RegexMatcher m = new RegexMatcher("A?", Collections.emptySet());  // will match zero length string.
                m.reset(inStr1.toString());
                final int len = inStr1.length();
                REGEX_ASSERT(m.matches(len) == true);
                m.reset(empty.toString());
                REGEX_ASSERT(m.matches(0) == true);
            }


            //
            // lookingAt(pos)
            //

            m1.reset(instr2.toString());  // "not abc"
            REGEX_ASSERT(m1.lookingAt(4) == true);
            REGEX_ASSERT(m1.lookingAt(5) == false);
            REGEX_ASSERT(m1.lookingAt(3) == false);
            REGEX_ASSERT(m1.lookingAt(4) == true);
            Assert.assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> m1.lookingAt(-1)
            );

            final int len = m1.input().length();
            REGEX_ASSERT(m1.lookingAt(len) == false);
            Assert.assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> m1.lookingAt(len+1)
            );
        }


        //
        // Capture Group.
        //     RegexMatcher.start();
        //     RegexMatcher.end();
        //     RegexMatcher.groupCount();
        //
        {
            Set<URegexpFlag> flags=Collections.emptySet();
            RegexPattern pat = RegexPattern.compile("01(23(45)67)(.*)", flags);

            RegexMatcher matcher = pat.matcher("0123456789");
            Assert.assertTrue(matcher.lookingAt());
            final int[] matchStarts = {0,  2, 4, 8};
            final int[] matchEnds = {10, 8, 6, 10};
            int i;
            for (i=0; i<4; i++) {
                int actualStart = matcher.start(i);
                { Assert.assertEquals("Index " + i, matchStarts[i], actualStart);
                }
                {int actualEnd = matcher.end(i);
                Assert.assertEquals("Index " + i, matchEnds[i], actualEnd);
                }
            }

            REGEX_ASSERT(matcher.start(0) == matcher.start());
            REGEX_ASSERT(matcher.end(0) == matcher.end());

            Assert.assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> matcher.start(-1)
            );
            Assert.assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> matcher.start( 4)
            );
            matcher.reset();
            REGEX_ASSERT_FAIL(() -> matcher.start( 0), U_REGEX_INVALID_STATE);

            matcher.lookingAt();
            Assert.assertEquals("0123456789", matcher.group());
            Assert.assertEquals("0123456789", matcher.group(0));
            Assert.assertEquals("234567", matcher.group(1));
            Assert.assertEquals("45", matcher.group(2));
            Assert.assertEquals("89", matcher.group(3));
            Assert.assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> matcher.group(-1)
            );
            Assert.assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> matcher.group( 4)
            );
            matcher.reset();
            REGEX_ASSERT_FAIL(() -> matcher.group( 0), U_REGEX_INVALID_STATE);




        }

        //
        //  find
        //
        {
            Set<URegexpFlag> flags=Collections.emptySet();
            RegexPattern pat = RegexPattern.compile("abc", flags);

            RegexMatcher matcher = pat.matcher(".abc..abc...abc..");
            //                                  012345678901234567
            REGEX_ASSERT(matcher.find());
            Assert.assertEquals(1, matcher.start());
            REGEX_ASSERT(matcher.find());
            Assert.assertEquals(6, matcher.start());
            REGEX_ASSERT(matcher.find());
            Assert.assertEquals(12, matcher.start());
            Assert.assertFalse(matcher.find());
            Assert.assertFalse(matcher.find());

            matcher.reset();
            REGEX_ASSERT(matcher.find());
            Assert.assertEquals(1, matcher.start());

            REGEX_ASSERT(matcher.find(0));
            Assert.assertEquals(1, matcher.start());
            REGEX_ASSERT(matcher.find(1));
            Assert.assertEquals(1, matcher.start());
            REGEX_ASSERT(matcher.find(2));
            Assert.assertEquals(6, matcher.start());
            REGEX_ASSERT(matcher.find(12));
            Assert.assertEquals(12, matcher.start());
            REGEX_ASSERT(matcher.find(13) == false);
            REGEX_ASSERT(matcher.find(16) == false);
            REGEX_ASSERT(matcher.find(17) == false);
            REGEX_ASSERT_FAIL(() -> matcher.start(), U_REGEX_INVALID_STATE);


            Assert.assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> matcher.find(-1)
            );

            Assert.assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> matcher.find(18)
            );

            Assert.assertEquals(0, matcher.groupCount());



        }


        //
        //  find, with \G in pattern (true if at the end of a previous match).
        //
        {
            Set<URegexpFlag> flags=Collections.emptySet();
            RegexPattern pat = RegexPattern.compile(".*?(?:(\\Gabc)|(abc))", flags);

            RegexMatcher matcher = pat.matcher(".abcabc.abc..");
            //                                  012345678901234567
            REGEX_ASSERT(matcher.find());
            Assert.assertEquals(0, matcher.start());
            REGEX_ASSERT(matcher.start(1) == -1);
            REGEX_ASSERT(matcher.start(2) == 1);

            REGEX_ASSERT(matcher.find());
            Assert.assertEquals(4, matcher.start());
            REGEX_ASSERT(matcher.start(1) == 4);
            REGEX_ASSERT(matcher.start(2) == -1);



        }

        //
        //   find with zero length matches, match position should bump ahead
        //     to prevent loops.
        //
        {
            int                 i;

            RegexMatcher m = new RegexMatcher("(?= ?)", Collections.emptySet());   // This pattern will zero-length matches anywhere,
            //   using an always-true look-ahead.
            StringBuffer s = new StringBuffer("    ");
            m.reset(s.toString());
            for (i=0; ; i++) {
                if (m.find() == false) {
                    break;
                }
                Assert.assertEquals(i, m.start());
                Assert.assertEquals(i, m.end());
            }
            Assert.assertEquals(5, i);

            // Check that the bump goes over surrogate pairs OK
            s = new StringBuffer(Utility.unescape("\\U00010001\\U00010002\\U00010003\\U00010004"));
            m.reset(s.toString());
            for (i=0; ; i+=2) {
                if (m.find() == false) {
                    break;
                }
                Assert.assertEquals(i, m.start());
                Assert.assertEquals(i, m.end());
            }
            Assert.assertEquals(10, i);
        }
        {
            // find() loop breaking test.
            //        with pattern of /.?/, should see a series of one char matches, then a single
            //        match of zero length at the end of the input string.
            int                 i;

            RegexMatcher m = new RegexMatcher(".?", Collections.emptySet());
            m.reset("    ");
            for (i=0; ; i++) {
                if (m.find() == false) {
                    break;
                }
                Assert.assertEquals(i, m.start());
                REGEX_ASSERT(m.end() == (i<4 ? i+1 : i));
            }
            Assert.assertEquals(5, i);
        }


        //
        // Matchers with no input string behave as if they had an empty input string.
        //

        {
            RegexMatcher m = new RegexMatcher(".?", Collections.emptySet());
            REGEX_ASSERT(m.find());
            Assert.assertEquals(0, m.start());
            Assert.assertEquals("", m.input().toString());
        }
        {
            RegexPattern p = RegexPattern.compile(".", Collections.emptySet());
            RegexMatcher  m = p.matcher();

            Assert.assertFalse(m.find());
            Assert.assertEquals("", m.input().toString());


        }

        //
        // Regions
        //
        {
            String testString = "This is test data";
            RegexMatcher m = new RegexMatcher(".*", testString, Collections.emptySet());
            Assert.assertEquals(0, m.regionStart());
            REGEX_ASSERT(m.regionEnd() == testString.length());
            Assert.assertFalse(m.hasTransparentBounds());
            Assert.assertTrue(m.hasAnchoringBounds());

            m.region(2,4);
            REGEX_ASSERT(m.matches());
            Assert.assertEquals(2, m.start());
            Assert.assertEquals(4, m.end());

            m.reset();
            Assert.assertEquals(0, m.regionStart());
            REGEX_ASSERT(m.regionEnd() == testString.length());

            StringBuilder shorterString = new StringBuilder("short");
            m.reset(shorterString.toString());
            Assert.assertEquals(0, m.regionStart());
            REGEX_ASSERT(m.regionEnd() == shorterString.length());

            Assert.assertTrue(m.hasAnchoringBounds());
            REGEX_ASSERT(m == /*ref equals*/ m.useAnchoringBounds(false));
            Assert.assertFalse(m.hasAnchoringBounds());
            REGEX_ASSERT(m == /*ref equals*/ m.reset());
            Assert.assertFalse(m.hasAnchoringBounds());

            REGEX_ASSERT(m == /*ref equals*/ m.useAnchoringBounds(true));
            Assert.assertTrue(m.hasAnchoringBounds());
            REGEX_ASSERT(m == /*ref equals*/ m.reset());
            Assert.assertTrue(m.hasAnchoringBounds());

            Assert.assertFalse(m.hasTransparentBounds());
            REGEX_ASSERT(m == /*ref equals*/ m.useTransparentBounds(true));
            Assert.assertTrue(m.hasTransparentBounds());
            REGEX_ASSERT(m == /*ref equals*/ m.reset());
            Assert.assertTrue(m.hasTransparentBounds());

            REGEX_ASSERT(m == /*ref equals*/ m.useTransparentBounds(false));
            Assert.assertFalse(m.hasTransparentBounds());
            REGEX_ASSERT(m == /*ref equals*/ m.reset());
            Assert.assertFalse(m.hasTransparentBounds());

        }

        //
        // hitEnd() and requireEnd()
        //
        {
            String testString = ("aabb");
            RegexMatcher m1 = new RegexMatcher(".*", testString, Collections.emptySet());
            Assert.assertTrue(m1.lookingAt());
            Assert.assertTrue(m1.hitEnd());
            Assert.assertFalse(m1.requireEnd());


            RegexMatcher m2 = new RegexMatcher("a*", testString, Collections.emptySet());
            Assert.assertTrue(m2.lookingAt());
            Assert.assertFalse(m2.hitEnd());
            Assert.assertFalse(m2.requireEnd());


            RegexMatcher m3 = new RegexMatcher(".*$", testString, Collections.emptySet());
            Assert.assertTrue(m3.lookingAt());
            Assert.assertTrue(m3.hitEnd());
            Assert.assertTrue(m3.requireEnd());
        }


        //
        //  Time Outs.
        //       Note:  These tests will need to be changed when the regexp engine is
        //              able to detect and cut short the exponential time behavior on
        //              this type of match.
        //
        {

            //    Enough 'a's in the string to cause the match to time out.
            //       (Each on additional 'a' doubles the time)
            String testString = ("aaaaaaaaaaaaaaaaaaaaa");
            RegexMatcher matcher = new RegexMatcher("(a+)+b", testString, Collections.emptySet());
            Assert.assertEquals(0, matcher.getTimeLimit());
            matcher.setTimeLimit(100);
            Assert.assertEquals(100, matcher.getTimeLimit());
            REGEX_ASSERT_FAIL(() -> matcher.lookingAt(), U_REGEX_TIME_OUT);
        }
        {

            //   Few enough 'a's to slip in under the time limit.
            String testString = ("aaaaaaaaaaaaaaaaaa");
            RegexMatcher matcher = new RegexMatcher("(a+)+b", testString, Collections.emptySet());
            matcher.setTimeLimit(100);
            Assert.assertFalse(matcher.lookingAt());
        }

        //
        //  Stack Limits
        //
        {
            String testString = stringRepeat((char) 0x41, 1000000);  // Length 1,000,000, filled with 'A'

            // Adding the capturing parentheses to the pattern "(A)+A$" inhibits optimizations
            //   of the '+', and makes the stack frames larger.
            RegexMatcher matcher = new RegexMatcher("(A)+A$", testString, Collections.emptySet());

            // With the default stack, this match should fail to run
            REGEX_ASSERT_FAIL(() -> matcher.lookingAt(), U_REGEX_STACK_OVERFLOW);

            // With unlimited stack, it should run

            matcher.setStackLimit(0);
            Assert.assertTrue(matcher.lookingAt());
            Assert.assertEquals(0, matcher.getStackLimit());

            // With a limited stack, it the match should fail

            matcher.setStackLimit(10000);
            REGEX_ASSERT_FAIL(() -> matcher.lookingAt(), U_REGEX_STACK_OVERFLOW);
            Assert.assertEquals(10000, matcher.getStackLimit());
        }

        // A pattern that doesn't save state should work with
        //   a minimal sized stack
        {

            String testString = ("abc");
            RegexMatcher matcher = new RegexMatcher("abc", testString, Collections.emptySet());
            matcher.setStackLimit(30);
            Assert.assertTrue(matcher.matches());
            Assert.assertEquals(30, matcher.getStackLimit());

            // Negative stack sizes should fail

            matcher.setStackLimit(1000);
            REGEX_ASSERT_FAIL(() -> matcher.setStackLimit(-1), U_ILLEGAL_ARGUMENT_ERROR);
            Assert.assertEquals(1000, matcher.getStackLimit());
        }


    }






    //---------------------------------------------------------------------------
//
//      API_Replace        API test for class RegexMatcher, testing the
//                         Replace family of functions.
//
//---------------------------------------------------------------------------
    @Test
    public void API_Replace() {
        //
        //  Replace
        //
        Set<URegexpFlag> flags=Collections.emptySet();
        String re = ("abc");
        RegexPattern pat = RegexPattern.compile(re, flags);
        StringBuffer data = new StringBuffer(".abc..abc...abc..");
        //                                    012345678901234567
        RegexMatcher matcher = pat.matcher(data.toString());

        //
        //  Plain vanilla matches.
        //
        StringBuffer dest;
        dest = matcher.replaceFirst("yz");
        Assert.assertEquals(".yz..abc...abc..", dest.toString());

        dest = matcher.replaceAll("yz");
        Assert.assertEquals(".yz..yz...yz..", dest.toString());

        //
        //  Plain vanilla non-matches.
        //
        matcher.reset(".abx..abx...abx..");
        dest = matcher.replaceFirst("yz");
        REGEX_ASSERT(dest.toString().equals(".abx..abx...abx.."));

        dest = matcher.replaceAll("yz");
        REGEX_ASSERT(dest.toString().equals(".abx..abx...abx.."));

        //
        // Empty source string
        //
        String d3 = "";
        matcher.reset(d3);
        dest = matcher.replaceFirst("yz");
        REGEX_ASSERT(dest.toString().equals(""));

        dest = matcher.replaceAll("yz");
        REGEX_ASSERT(dest.toString().isEmpty());

        //
        // Empty substitution string
        //
        matcher.reset(data.toString());              // ".abc..abc...abc.."
        dest = matcher.replaceFirst("");
        REGEX_ASSERT(dest.toString().equals("...abc...abc.."));

        dest = matcher.replaceAll("");
        REGEX_ASSERT(dest.toString().equals("........"));

        //
        // match whole string
        //
        matcher.reset("abc");
        dest = matcher.replaceFirst("xyz");
        REGEX_ASSERT(dest.toString().equals("xyz"));

        dest = matcher.replaceAll("xyz");
        REGEX_ASSERT(dest.toString().equals("xyz"));

        //
        // Capture Group, simple case
        //
        String re2 = ("a(..)");
        RegexPattern pat2 = RegexPattern.compile(re2, flags);
        RegexMatcher matcher2 = pat2.matcher("abcdefg");
        dest = matcher2.replaceFirst("$1$1");
        REGEX_ASSERT("bcbcdefg".contentEquals(dest));

        dest = matcher2.replaceFirst(("The value of \\$1 is $1."));
        REGEX_ASSERT("The value of $1 is bc.defg".contentEquals(dest));

        Assert.assertThrows(
                Exception.class,
                () -> { matcher2.replaceFirst("$ by itself, no group number $$$"); }
        );


        String replacement = ("Supplemental Digit 1 $\\U0001D7CF.");
        replacement = Utility.unescape(replacement);
        dest = matcher2.replaceFirst(replacement);
        REGEX_ASSERT("Supplemental Digit 1 bc.defg".equals(dest.toString()));

        Assert.assertThrows(
                IndexOutOfBoundsException.class,
                () -> matcher2.replaceFirst("bad capture group number $5...")
        );


        //
        // Replacement String with \\u hex escapes
        //
        {
            String substitute = ("--\\u0043--");
            matcher.reset("abc 1 abc 2 abc 3");
            StringBuffer  result = matcher.replaceAll(substitute);
            Assert.assertEquals("--C-- 1 --C-- 2 --C-- 3", result.toString());
        }
        {
            StringBuffer src = new StringBuffer("abc !");
            String substitute = ("--\\U00010000--");
            matcher.reset(src.toString());
            StringBuffer  result = matcher.replaceAll(substitute);
            StringBuilder expected = new StringBuilder("--");
            expected.appendCodePoint((int)0x10000);
            expected.append("-- !");
            Assert.assertEquals(expected.toString(), result.toString());
        }
        // TODO:  need more through testing of capture substitutions.

        // Bug 4057
        //
        {

            RegexMatcher m = new RegexMatcher("ss(.*?)ee", Collections.emptySet());
            ReplaceableString result = new ReplaceableString();

            // Multiple finds do NOT bump up the previous appendReplacement position.
            m.reset("The matches start with ss and end with ee ss stuff ee fin");
            m.find();
            m.find();
            m.appendReplacement(result, "ooh");
            Assert.assertEquals("The matches start with ss and end with ee ooh", result.toString());

            // After a reset into the interior of a string, appendReplacemnt still starts at beginning.

            Util.clear(result);
            m.reset(10);
            m.find();
            m.find();
            m.appendReplacement(result, "ooh");
            Assert.assertEquals("The matches start with ss and end with ee ooh", result.toString());

            // find() at interior of string, appendReplacemnt still starts at beginning.

            Util.clear(result);
            m.reset();
            m.find(10);
            m.find();
            m.appendReplacement(result, "ooh");
            Assert.assertEquals("The matches start with ss and end with ee ooh", result.toString());

            m.appendTail(result);
            Assert.assertEquals("The matches start with ss and end with ee ooh fin", result.toString());

        }





    }


    //---------------------------------------------------------------------------
//
//      API_Pattern       Test that the API for class RegexPattern is
//                        present and nominally working.
//
//---------------------------------------------------------------------------
    @Test
    public void API_Pattern() {
        RegexPattern        pata = RegexPattern.compile(""); // Test default constructor to not crash.
        RegexPattern        patb = RegexPattern.compile("");

        REGEX_ASSERT(pata.equals(patb));

        String re1 = ("abc[a-l][m-z]");
        String re2 = ("def");

        RegexPattern pat1 = RegexPattern.compile(re1, Collections.emptySet());
        RegexPattern pat2 = RegexPattern.compile(re2, Collections.emptySet());
        REGEX_ASSERT(pat1 != pata);

        patb = pat1;

        REGEX_ASSERT(pat1 != pat2);
        patb = pat2;
        Assert.assertEquals(pat2, patb);

        // Compile with no flags.
        RegexPattern pat1a = RegexPattern.compile(re1);
        Assert.assertEquals(pat1, pat1a);

        Assert.assertTrue(pat1a.flags().isEmpty());

        // Compile with different flags should be not equal
        RegexPattern pat1b = RegexPattern.compile(re1, EnumSet.of(UREGEX_CASE_INSENSITIVE));

        REGEX_ASSERT(pat1b != pat1a);
        Assert.assertEquals(EnumSet.of(UREGEX_CASE_INSENSITIVE), pat1b.flags());
        Assert.assertTrue(pat1a.flags().isEmpty());


        // clone TODO
/*        RegexPattern pat1c = pat1.clone();
        Assert.assertEquals(*pat1, *pat1c);
        REGEX_ASSERT(*pat1c != *pat2);*/







        //
        //   Verify that a matcher created from a cloned pattern works.
        //     (Jitterbug 3423)
        // TODO
        /*{

            RegexPattern pSource = RegexPattern.compile(("\\p{L}+"), Collections.emptySet());
            RegexPattern  *pClone     = pSource.clone();

            RegexMatcher  *mFromClone = pClone.matcher();
            StringBuffer s = new StringBuffer("Hello World");
            mFromClone.reset(s);
            Assert.assertTrue(mFromClone.find());
            Assert.assertEquals("Hello", mFromClone.group());
            Assert.assertTrue(mFromClone.find());
            Assert.assertEquals("World", mFromClone.group());
            Assert.assertFalse(mFromClone.find());


        }*/

        //
        //   matches convenience API
        //
        REGEX_ASSERT(RegexPattern.matches(".*", "random input") == true);
        REGEX_ASSERT(RegexPattern.matches("abc", "random input") == false);
        REGEX_ASSERT(RegexPattern.matches(".*nput", "random input") == true);
        REGEX_ASSERT(RegexPattern.matches("random input", "random input") == true);
        // TODO: Why ICU4C has U_INDEX_OUTOFBOUNDS_ERROR here?
        REGEX_ASSERT(RegexPattern.matches(".*u", "random input") == false);

        //
        // Split()
        //

        pat1 = RegexPattern.compile(" +");
        String[] fields = new String[10];
        Arrays.fill(fields, "");

        int n;
        n = pat1.split("Now is the time", fields, 10);
        Assert.assertEquals(4, n);
        Assert.assertEquals("Now", fields[0]);
        Assert.assertEquals("is", fields[1]);
        Assert.assertEquals("the", fields[2]);
        Assert.assertEquals("time", fields[3]);
        Assert.assertEquals("", fields[4]);

        n = pat1.split("Now is the time", fields, 2);
        Assert.assertEquals(2, n);
        Assert.assertEquals("Now", fields[0]);
        Assert.assertEquals("is the time", fields[1]);
        Assert.assertEquals("the", fields[2]);   // left over from previous test

        fields[1] = "*";

        n = pat1.split("Now is the time", fields, 1);
        Assert.assertEquals(1, n);
        Assert.assertEquals("Now is the time", fields[0]);
        Assert.assertEquals("*", fields[1]);


        n = pat1.split("    Now       is the time   ", fields, 10);
        Assert.assertEquals(6, n);
        Assert.assertEquals("", fields[0]);
        Assert.assertEquals("Now", fields[1]);
        Assert.assertEquals("is", fields[2]);
        Assert.assertEquals("the", fields[3]);
        Assert.assertEquals("time", fields[4]);
        Assert.assertEquals("", fields[5]);

        n = pat1.split("     ", fields, 10);
        Assert.assertEquals(2, n);
        Assert.assertEquals("", fields[0]);
        Assert.assertEquals("", fields[1]);

        fields[0] = "foo";
        n = pat1.split("", fields, 10);
        Assert.assertEquals(0, n);
        Assert.assertEquals("foo", fields[0]);



        //  split, with a pattern with (capture)
        pat1 = RegexPattern.compile(("<(\\w*)>"));


        n = pat1.split("<a>Now is <b>the time<c>", fields, 10);
        Assert.assertEquals(7, n);
        Assert.assertEquals("", fields[0]);
        Assert.assertEquals("a", fields[1]);
        Assert.assertEquals("Now is ", fields[2]);
        Assert.assertEquals("b", fields[3]);
        Assert.assertEquals("the time", fields[4]);
        Assert.assertEquals("c", fields[5]);
        Assert.assertEquals("", fields[6]);

        n = pat1.split("  <a>Now is <b>the time<c>", fields, 10);
        Assert.assertEquals(7, n);
        Assert.assertEquals("  ", fields[0]);
        Assert.assertEquals("a", fields[1]);
        Assert.assertEquals("Now is ", fields[2]);
        Assert.assertEquals("b", fields[3]);
        Assert.assertEquals("the time", fields[4]);
        Assert.assertEquals("c", fields[5]);
        Assert.assertEquals("", fields[6]);


        fields[6] = "foo";
        n = pat1.split("  <a>Now is <b>the time<c>", fields, 6);
        Assert.assertEquals(6, n);
        Assert.assertEquals("  ", fields[0]);
        Assert.assertEquals("a", fields[1]);
        Assert.assertEquals("Now is ", fields[2]);
        Assert.assertEquals("b", fields[3]);
        Assert.assertEquals("the time", fields[4]);
        Assert.assertEquals("", fields[5]);  // All text following "<c>" field delimiter.
        Assert.assertEquals("foo", fields[6]);


        fields[5] = "foo";
        n = pat1.split("  <a>Now is <b>the time<c>", fields, 5);
        Assert.assertEquals(5, n);
        Assert.assertEquals("  ", fields[0]);
        Assert.assertEquals("a", fields[1]);
        Assert.assertEquals("Now is ", fields[2]);
        Assert.assertEquals("b", fields[3]);
        Assert.assertEquals("the time<c>", fields[4]);
        Assert.assertEquals("foo", fields[5]);


        fields[5] = "foo";
        n = pat1.split("  <a>Now is <b>the time", fields, 5);
        Assert.assertEquals(5, n);
        Assert.assertEquals("  ", fields[0]);
        Assert.assertEquals("a", fields[1]);
        Assert.assertEquals("Now is ", fields[2]);
        Assert.assertEquals("b", fields[3]);
        Assert.assertEquals("the time", fields[4]);
        Assert.assertEquals("foo", fields[5]);


        n = pat1.split("  <a>Now is <b>the time<c>", fields, 4);
        Assert.assertEquals(4, n);
        Assert.assertEquals("  ", fields[0]);
        Assert.assertEquals("a", fields[1]);
        Assert.assertEquals("Now is ", fields[2]);
        Assert.assertEquals("the time<c>", fields[3]);



        pat1 = RegexPattern.compile("([-,])");
        n = pat1.split("1-10,20", fields, 10);
        Assert.assertEquals(5, n);
        Assert.assertEquals("1", fields[0]);
        Assert.assertEquals("-", fields[1]);
        Assert.assertEquals("10", fields[2]);
        Assert.assertEquals(",", fields[3]);
        Assert.assertEquals("20", fields[4]);


        // Test split of string with empty trailing fields
        pat1 = RegexPattern.compile(",");
        n = pat1.split("a,b,c,", fields, 10);
        Assert.assertEquals(4, n);
        Assert.assertEquals("a", fields[0]);
        Assert.assertEquals("b", fields[1]);
        Assert.assertEquals("c", fields[2]);
        Assert.assertEquals("", fields[3]);

        n = pat1.split("a,,,", fields, 10);
        Assert.assertEquals(4, n);
        Assert.assertEquals("a", fields[0]);
        Assert.assertEquals("", fields[1]);
        Assert.assertEquals("", fields[2]);
        Assert.assertEquals("", fields[3]);


        // Split Separator with zero length match.
        pat1 = RegexPattern.compile(":?");
        n = pat1.split("abc", fields, 10);
        Assert.assertEquals(5, n);
        Assert.assertEquals("", fields[0]);
        Assert.assertEquals("a", fields[1]);
        Assert.assertEquals("b", fields[2]);
        Assert.assertEquals("c", fields[3]);
        Assert.assertEquals("", fields[4]);



        //
        // RegexPattern.pattern()
        //
        pat1 = RegexPattern.compile("");
        REGEX_ASSERT(Objects.equals("", pat1.pattern()));


        pat1 = RegexPattern.compile("(Hello, world)*");
        REGEX_ASSERT(Objects.equals("(Hello, world)*", pat1.pattern()));



        //
        // classID functions
        //
        pat1 = RegexPattern.compile("(Hello, world)*");
        Assert.assertEquals(RegexPattern.class, pat1.getClass());
        RegexMatcher m = pat1.matcher("Hello, world.");
        REGEX_ASSERT(!pat1.getClass().equals(m.getClass()));
        Assert.assertEquals(RegexMatcher.class, m.getClass());



    }

    //---------------------------------------------------------------------------
//
//      API_Match_UTF8   Test that the alternate engine for class RegexMatcher
//                       is present and working, but excluding functions
//                       implementing replace operations.
//
//---------------------------------------------------------------------------
    @Test
    public void API_Match_UTF8() {

        //
        // Simple pattern compilation
        //
        {
            Set<URegexpFlag> flags=Collections.emptySet();
            StringWithOffset re;
            re = new StringWithOffset(regextst_openUTF8FromInvariant("abc".getBytes(), -1));
            REGEX_VERBOSE_TEXT(re);
            RegexPattern        pat2;
            pat2 = RegexPattern.compile(re.targetString, flags);

            StringWithOffset input1;
            StringWithOffset input2;
            String empty  = "";
            input1 = new StringWithOffset(regextst_openUTF8FromInvariant("abcdef this is a test".getBytes(), -1));
            REGEX_VERBOSE_TEXT(input1);
            input2 = new StringWithOffset(regextst_openUTF8FromInvariant("not abc".getBytes(), -1));
            REGEX_VERBOSE_TEXT(input2);

            int input1Len = (strlen("abcdef this is a test".getBytes())); /* TODO: why not nativelen (input1) ? */
            int input2Len = (strlen("not abc".getBytes()));


            //
            // Matcher creation and reset.
            //
            RegexMatcher m1 = pat2.matcher().reset(input1.targetString);
            Assert.assertTrue(m1.lookingAt());
        final byte[] str_abcdefthisisatest = { 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x20, 0x74, 0x68, 0x69, 0x73, 0x20, 0x69, 0x73, 0x20, 0x61, 0x20, 0x74, 0x65, 0x73, 0x74 }; /* abcdef this is a test */
            REGEX_ASSERT_UTEXT_UTF8(str_abcdefthisisatest, m1.input());
            m1.reset(input2.targetString);
            Assert.assertFalse(m1.lookingAt());
        final byte[] str_notabc = { 0x6e, 0x6f, 0x74, 0x20, 0x61, 0x62, 0x63 }; /* not abc */
            REGEX_ASSERT_UTEXT_UTF8(str_notabc, m1.input());
            m1.reset(input1.targetString);
            REGEX_ASSERT_UTEXT_UTF8(str_abcdefthisisatest, m1.input());
            Assert.assertTrue(m1.lookingAt());
            m1.reset(empty);
            Assert.assertFalse(m1.lookingAt());
            REGEX_ASSERT(Util.utext_nativeLength(empty) == 0);

            //
            //  reset(pos)
            //
            m1.reset(input1.targetString);
            m1.reset(4);
            REGEX_ASSERT_UTEXT_UTF8(str_abcdefthisisatest, m1.input());
            Assert.assertTrue(m1.lookingAt());

            Assert.assertThrows(IndexOutOfBoundsException.class, () -> m1.reset(-1));


            m1.reset(0);


            m1.reset(input1Len-1);


            m1.reset(input1Len);


            Assert.assertThrows(IndexOutOfBoundsException.class, () -> m1.reset(input1Len+1));


            //
            // match(pos)
            //
            m1.reset(input2.targetString);
            REGEX_ASSERT(m1.matches(4) == true);
            m1.reset();
            REGEX_ASSERT(m1.matches(3) == false);
            m1.reset();
            REGEX_ASSERT(m1.matches(5) == false);
            REGEX_ASSERT(m1.matches(4) == true);
            Assert.assertThrows(IndexOutOfBoundsException.class, () -> m1.matches(-1));

            // Match() at end of string should fail, but should not
            //  be an error.

            REGEX_ASSERT(m1.matches(input2Len) == false);

            // Match beyond end of string should fail with an error.

            Assert.assertThrows(IndexOutOfBoundsException.class, () -> m1.matches(input2Len+1));

            // Successful match at end of string.
            {

                RegexMatcher m = new RegexMatcher("A?", Collections.emptySet());  // will match zero length string.
                m.reset(input1.targetString);
                REGEX_ASSERT(m.matches(input1Len) == true);
                m.reset(empty);
                REGEX_ASSERT(m.matches(0) == true);
            }


            //
            // lookingAt(pos)
            //

            m1.reset(input2.targetString);  // "not abc"
            REGEX_ASSERT(m1.lookingAt(4) == true);
            REGEX_ASSERT(m1.lookingAt(5) == false);
            REGEX_ASSERT(m1.lookingAt(3) == false);
            REGEX_ASSERT(m1.lookingAt(4) == true);
            Assert.assertThrows(IndexOutOfBoundsException.class, () -> m1.lookingAt(-1));

            REGEX_ASSERT(m1.lookingAt(input2Len) == false);
            Assert.assertThrows(IndexOutOfBoundsException.class, () -> m1.lookingAt(input2Len+1));
        }


        //
        // Capture Group.
        //     RegexMatcher.start();
        //     RegexMatcher.end();
        //     RegexMatcher.groupCount();
        //
        {
            Set<URegexpFlag> flags=Collections.emptySet();
            String               re="";
        final byte str_01234567_pat[] = { 0x30, 0x31, 0x28, 0x32, 0x33, 0x28, 0x34, 0x35, 0x29, 0x36, 0x37, 0x29, 0x28, 0x2e, 0x2a, 0x29 }; /* 01(23(45)67)(.*) */
            re = utext_openUTF8(str_01234567_pat, -1);

            RegexPattern pat = RegexPattern.compile(re, flags);

            String input;
        final byte str_0123456789[] = { 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39 }; /* 0123456789 */
            input = utext_openUTF8(str_0123456789, -1);

            RegexMatcher matcher = pat.matcher().reset(input);
            Assert.assertTrue(matcher.lookingAt());
            final int matchStarts[] = {0,  2, 4, 8};
            final int matchEnds[]   = {10, 8, 6, 10};
            int i;
            for (i=0; i<4; i++) {
                int actualStart = matcher.start(i);
                { Assert.assertEquals("Index " + i, matchStarts[i], actualStart);
                }
                int actualEnd = matcher.end(i);
                { Assert.assertEquals("Index " + i, matchEnds[i], actualEnd);
                }
            }

            REGEX_ASSERT(matcher.start(0) == matcher.start());
            REGEX_ASSERT(matcher.end(0) == matcher.end());

            Assert.assertThrows(IndexOutOfBoundsException.class, () -> matcher.start(-1));
            Assert.assertThrows(IndexOutOfBoundsException.class, () -> matcher.start( 4));
            matcher.reset();
            REGEX_ASSERT_FAIL(() -> matcher.start( 0), U_REGEX_INVALID_STATE);

            matcher.lookingAt();

            String result;
            //final char str_0123456789[] = { 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39 }; /* 0123456789 */
            //  Test shallow-clone API
            result = matcher.group(0);
            REGEX_ASSERT_UTEXT_UTF8(str_0123456789, result);
            result = matcher.group(0);
            REGEX_ASSERT_UTEXT_UTF8(str_0123456789, result);

            result = matcher.group(0);
            REGEX_ASSERT_UTEXT_UTF8(str_0123456789, result);
            result = matcher.group(0);
            Assert.assertEquals(10, result.length());
            REGEX_ASSERT_UTEXT_INVARIANT("0123456789".getBytes(), result);

            // Capture Group 1 == "234567"
            result = matcher.group(1);
            Assert.assertEquals(6, result.length());
            REGEX_ASSERT_UTEXT_INVARIANT("234567".getBytes(), result);

            result = matcher.group(1);
            Assert.assertEquals(6, result.length());
            REGEX_ASSERT_UTEXT_INVARIANT("234567".getBytes(), result);

            // Capture Group 2 == "45"
            result = matcher.group(2);
            Assert.assertEquals(2, result.length());
            REGEX_ASSERT_UTEXT_INVARIANT("45".getBytes(), result);

            result = matcher.group(2);
            Assert.assertEquals(2, result.length());
            REGEX_ASSERT_UTEXT_INVARIANT("45".getBytes(), result);

            // Capture Group 3 == "89"
            result = matcher.group(3);
            Assert.assertEquals(2, result.length());
            REGEX_ASSERT_UTEXT_INVARIANT("89".getBytes(), result);

            result = matcher.group(3);
            Assert.assertEquals(2, result.length());
            REGEX_ASSERT_UTEXT_INVARIANT("89".getBytes(), result);

            // Capture Group number out of range.

            Assert.assertThrows(IndexOutOfBoundsException.class, () -> matcher.group(-1));

            Assert.assertThrows(IndexOutOfBoundsException.class, () -> matcher.group( 4));

            matcher.reset();
            REGEX_ASSERT_FAIL(() -> matcher.group( 0), U_REGEX_INVALID_STATE);
        }

        //
        //  find
        //
        {
            Set<URegexpFlag> flags=Collections.emptySet();
            String               re="";
        final byte[] str_abc = { 0x61, 0x62, 0x63 }; /* abc */
            re = utext_openUTF8(str_abc, -1);

            RegexPattern pat = RegexPattern.compile(re, flags);
            String input;
        final byte[] str_abcabcabc = { 0x2e, 0x61, 0x62, 0x63, 0x2e, 0x2e, 0x61, 0x62, 0x63, 0x2e, 0x2e, 0x2e, 0x61, 0x62, 0x63, 0x2e, 0x2e }; /* .abc..abc...abc.. */
            input = utext_openUTF8(str_abcabcabc, -1);
            //                      012345678901234567

            RegexMatcher matcher = pat.matcher().reset(input);
            REGEX_ASSERT(matcher.find());
            Assert.assertEquals(1, matcher.start());
            REGEX_ASSERT(matcher.find());
            Assert.assertEquals(6, matcher.start());
            REGEX_ASSERT(matcher.find());
            Assert.assertEquals(12, matcher.start());
            Assert.assertFalse(matcher.find());
            Assert.assertFalse(matcher.find());

            matcher.reset();
            REGEX_ASSERT(matcher.find());
            Assert.assertEquals(1, matcher.start());

            REGEX_ASSERT(matcher.find(0));
            Assert.assertEquals(1, matcher.start());
            REGEX_ASSERT(matcher.find(1));
            Assert.assertEquals(1, matcher.start());
            REGEX_ASSERT(matcher.find(2));
            Assert.assertEquals(6, matcher.start());
            REGEX_ASSERT(matcher.find(12));
            Assert.assertEquals(12, matcher.start());
            REGEX_ASSERT(matcher.find(13) == false);
            REGEX_ASSERT(matcher.find(16) == false);
            REGEX_ASSERT(matcher.find(17) == false);
            REGEX_ASSERT_FAIL(() -> matcher.start(), U_REGEX_INVALID_STATE);


            Assert.assertThrows(IndexOutOfBoundsException.class, () -> matcher.find(-1));

            Assert.assertThrows(IndexOutOfBoundsException.class, () -> matcher.find(18));

            Assert.assertEquals(0, matcher.groupCount());
        }


        //
        //  find, with \G in pattern (true if at the end of a previous match).
        //
        {
            Set<URegexpFlag> flags=Collections.emptySet();
            String               re="";
        final byte[] str_Gabcabc = { 0x2e, 0x2a, 0x3f, 0x28, 0x3f, 0x3a, 0x28, 0x5c, 0x47, 0x61, 0x62, 0x63, 0x29, 0x7c, 0x28, 0x61, 0x62, 0x63, 0x29, 0x29 }; /* .*?(?:(\\Gabc)|(abc)) */
            re = utext_openUTF8(str_Gabcabc, -1);

            RegexPattern pat = RegexPattern.compile(re, flags);
            String input;
        final byte[] str_abcabcabc = { 0x2e, 0x61, 0x62, 0x63, 0x61, 0x62, 0x63, 0x2e, 0x61, 0x62, 0x63, 0x2e, 0x2e }; /* .abcabc.abc.. */
            input = utext_openUTF8(str_abcabcabc, -1);
            //                      012345678901234567

            RegexMatcher matcher = pat.matcher().reset(input);
            REGEX_ASSERT(matcher.find());
            Assert.assertEquals(0, matcher.start());
            REGEX_ASSERT(matcher.start(1) == -1);
            REGEX_ASSERT(matcher.start(2) == 1);

            REGEX_ASSERT(matcher.find());
            Assert.assertEquals(4, matcher.start());
            REGEX_ASSERT(matcher.start(1) == 4);
            REGEX_ASSERT(matcher.start(2) == -1);
        }

        //
        //   find with zero length matches, match position should bump ahead
        //     to prevent loops.
        //
        {
            int                 i;

            RegexMatcher m = new RegexMatcher("(?= ?)", Collections.emptySet());   // This pattern will zero-length matches anywhere,
            //   using an always-true look-ahead.
            String s;
            s = utext_openUTF8("    ".getBytes(), -1);
            m.reset(s);
            for (i=0; ; i++) {
                if (m.find() == false) {
                    break;
                }
                Assert.assertEquals(i, m.start());
                Assert.assertEquals(i, m.end());
            }
            Assert.assertEquals(5, i);

            // Check that the bump goes over characters outside the BMP OK
            // "\\U00010001\\U00010002\\U00010003\\U00010004".unescape()...in UTF-8
            char[] aboveBMP = {0xF0, 0x90, 0x80, 0x81, 0xF0, 0x90, 0x80, 0x82, 0xF0, 0x90, 0x80, 0x83, 0xF0, 0x90, 0x80, 0x84 };
            s = utext_openUTF8(Util.toByteArrayWrapping(aboveBMP), -1);
            m.reset(s);
            // the string is 4 codepoints, but 8 chars, so positions should be multiples of 2
            for (i=0; ; i+=2) {
                if (!m.find()) {
                    break;
                }
                Assert.assertEquals(i, m.start());
                Assert.assertEquals(i, m.end());
            }
            Assert.assertEquals(10, i);
        }
        {
            // find() loop breaking test.
            //        with pattern of /.?/, should see a series of one char matches, then a single
            //        match of zero length at the end of the input string.
            int                 i;

            RegexMatcher m = new RegexMatcher(".?", Collections.emptySet());
            String s;
            s = utext_openUTF8("    ".getBytes(), -1);
            m.reset(s);
            for (i=0; ; i++) {
                if (m.find() == false) {
                    break;
                }
                Assert.assertEquals(i, m.start());
                REGEX_ASSERT(m.end() == (i<4 ? i+1 : i));
            }
            Assert.assertEquals(5, i);
        }


        //
        // Matchers with no input string behave as if they had an empty input string.
        //

        {

            RegexMatcher m = new RegexMatcher(".?", Collections.emptySet());
            REGEX_ASSERT(m.find());
            Assert.assertEquals(0, m.start());
            REGEX_ASSERT(m.input().toString().isEmpty());
        }
        {

            RegexPattern p = RegexPattern.compile(".", Collections.emptySet());
            RegexMatcher  m = p.matcher();

            Assert.assertFalse(m.find());
            REGEX_ASSERT(Util.utext_nativeLength(m.input()) == 0);


        }

        //
        // Regions
        //
        {

            String testPattern;
            String testText    = "";
            testPattern = regextst_openUTF8FromInvariant(".*".getBytes(), -1);
//            REGEX_VERBOSE_TEXT(testPattern);
            testText = regextst_openUTF8FromInvariant("This is test data".getBytes(), -1);
//            REGEX_VERBOSE_TEXT(testText);

            RegexMatcher m = new RegexMatcher(testPattern, testText, Collections.emptySet());
            Assert.assertEquals(0, m.regionStart());
            REGEX_ASSERT(m.regionEnd() == (int)strlen("This is test data".getBytes()));
            Assert.assertFalse(m.hasTransparentBounds());
            Assert.assertTrue(m.hasAnchoringBounds());

            m.region(2,4);
            REGEX_ASSERT(m.matches());
            Assert.assertEquals(2, m.start());
            Assert.assertEquals(4, m.end());

            m.reset();
            Assert.assertEquals(0, m.regionStart());
            REGEX_ASSERT(m.regionEnd() == (int)strlen("This is test data".getBytes()));

            testText = regextst_openUTF8FromInvariant("short".getBytes(), -1);
//            REGEX_VERBOSE_TEXT(testText);
            m.reset(testText);
            Assert.assertEquals(0, m.regionStart());
            REGEX_ASSERT(m.regionEnd() == (int)strlen("short".getBytes()));

            Assert.assertTrue(m.hasAnchoringBounds());
            REGEX_ASSERT(m == /*ref equals*/ m.useAnchoringBounds(false));
            Assert.assertFalse(m.hasAnchoringBounds());
            REGEX_ASSERT(m == /*ref equals*/ m.reset());
            Assert.assertFalse(m.hasAnchoringBounds());

            REGEX_ASSERT(m == /*ref equals*/ m.useAnchoringBounds(true));
            Assert.assertTrue(m.hasAnchoringBounds());
            REGEX_ASSERT(m == /*ref equals*/ m.reset());
            Assert.assertTrue(m.hasAnchoringBounds());

            Assert.assertFalse(m.hasTransparentBounds());
            REGEX_ASSERT(m == /*ref equals*/ m.useTransparentBounds(true));
            Assert.assertTrue(m.hasTransparentBounds());
            REGEX_ASSERT(m == /*ref equals*/ m.reset());
            Assert.assertTrue(m.hasTransparentBounds());

            REGEX_ASSERT(m == /*ref equals*/ m.useTransparentBounds(false));
            Assert.assertFalse(m.hasTransparentBounds());
            REGEX_ASSERT(m == /*ref equals*/ m.reset());
            Assert.assertFalse(m.hasTransparentBounds());
        }

        //
        // hitEnd() and requireEnd()
        //
        {

            String testPattern;
            String testText    = "";
        final byte[] str_ = { 0x2e, 0x2a }; /* .* */
        final byte[] str_aabb = { 0x61, 0x61, 0x62, 0x62 }; /* aabb */
            testPattern = utext_openUTF8(str_, -1);
            testText = utext_openUTF8(str_aabb, -1);

            RegexMatcher m1 = new RegexMatcher(testPattern, testText, Collections.emptySet());
            Assert.assertTrue(m1.lookingAt());
            Assert.assertTrue(m1.hitEnd());
            Assert.assertFalse(m1.requireEnd());


        final byte[] str_a = { 0x61, 0x2a }; /* a* */
            testPattern = utext_openUTF8(str_a, -1);
            RegexMatcher m2 = new RegexMatcher(testPattern, testText, Collections.emptySet());
            Assert.assertTrue(m2.lookingAt());
            Assert.assertFalse(m2.hitEnd());
            Assert.assertFalse(m2.requireEnd());


        final byte[] str_dotstardollar = { 0x2e, 0x2a, 0x24 }; /* .*$ */
            testPattern = utext_openUTF8(str_dotstardollar, -1);
            RegexMatcher m3 = new RegexMatcher(testPattern, testText, Collections.emptySet());
            Assert.assertTrue(m3.lookingAt());
            Assert.assertTrue(m3.hitEnd());
            Assert.assertTrue(m3.requireEnd());
        }
    }


    //---------------------------------------------------------------------------
//
//      API_Replace_UTF8   API test for class RegexMatcher, testing the
//                         Replace family of functions.
//
//---------------------------------------------------------------------------
    @Test
    public void API_Replace_UTF8() {
        //
        //  Replace
        //
        Set<URegexpFlag> flags=Collections.emptySet();

        String               re="";
        re = regextst_openUTF8FromInvariant("abc".getBytes(), -1);
        REGEX_VERBOSE_TEXT(re);
        RegexPattern pat = RegexPattern.compile(re, flags);

        byte[] data = { 0x2e, 0x61, 0x62, 0x63, 0x2e, 0x2e, 0x61, 0x62, 0x63, 0x2e, 0x2e, 0x2e, 0x61, 0x62, 0x63, 0x2e, 0x2e }; /* .abc..abc...abc.. */
        //             012345678901234567
        String dataText;
        dataText = utext_openUTF8(data, -1);
        REGEX_VERBOSE_TEXT(dataText);
        RegexMatcher matcher = pat.matcher().reset(dataText);

        //
        //  Plain vanilla matches.
        //
            ReplaceableString dest = new ReplaceableString();
            ReplaceableString result;

            String replText;

            final byte[] str_yz = {0x79, 0x7a}; /* yz */
            replText = utext_openUTF8(str_yz, -1);
            REGEX_VERBOSE_TEXT(replText);
            result = matcher.replaceFirst(replText, null);
            final byte[] str_yzabcabc = {0x2e, 0x79, 0x7a, 0x2e, 0x2e, 0x61, 0x62, 0x63, 0x2e, 0x2e, 0x2e, 0x61, 0x62, 0x63, 0x2e, 0x2e}; /* .yz..abc...abc.. */
            REGEX_ASSERT_UTEXT_UTF8(str_yzabcabc, result.toString());
            result = matcher.replaceFirst(replText, dest);
            Assert.assertEquals(dest, result);
            REGEX_ASSERT_UTEXT_UTF8(str_yzabcabc, result.toString());

            result = matcher.replaceAll(replText, null);
            final byte[] str_yzyzyz = {0x2e, 0x79, 0x7a, 0x2e, 0x2e, 0x79, 0x7a, 0x2e, 0x2e, 0x2e, 0x79, 0x7a, 0x2e, 0x2e}; /* .yz..yz...yz.. */
            REGEX_ASSERT_UTEXT_UTF8(str_yzyzyz, result.toString());

            utext_replace(dest, 0, Util.utext_nativeLength(dest.toString()), new char[0], 0);
            result = matcher.replaceAll(replText, dest);
            Assert.assertEquals(dest, result);
            REGEX_ASSERT_UTEXT_UTF8(str_yzyzyz, result.toString());

            //
            //  Plain vanilla non-matches.
            //
            final byte[] str_abxabxabx = {0x2e, 0x61, 0x62, 0x78, 0x2e, 0x2e, 0x61, 0x62, 0x78, 0x2e, 0x2e, 0x2e, 0x61, 0x62, 0x78, 0x2e, 0x2e}; /* .abx..abx...abx.. */
            dataText = utext_openUTF8(str_abxabxabx, -1);
            matcher.reset(dataText);

            result = matcher.replaceFirst(replText, null);
            REGEX_ASSERT_UTEXT_UTF8(str_abxabxabx, result.toString());
            result = matcher.replaceFirst(replText, dest);
            Assert.assertEquals(dest.toString(), result.toString());
            REGEX_ASSERT_UTEXT_UTF8(str_abxabxabx, result.toString());

            result = matcher.replaceAll(replText, null);
            REGEX_ASSERT_UTEXT_UTF8(str_abxabxabx, result.toString());
            utext_replace(dest, 0, Util.utext_nativeLength(dest.toString()), new char[0], 0);
            result = matcher.replaceAll(replText, dest);
            Assert.assertEquals(dest, result);
            REGEX_ASSERT_UTEXT_UTF8(str_abxabxabx, result.toString());

            //
            // Empty source string
            //
            dataText = utext_openUTF8(new byte[0], 0);
            matcher.reset(dataText);

            result = matcher.replaceFirst(replText, null);
            REGEX_ASSERT_UTEXT_UTF8("".getBytes(), result.toString());
            result = matcher.replaceFirst(replText, dest);
            Assert.assertEquals(dest.toString(), result.toString());
            REGEX_ASSERT_UTEXT_UTF8("".getBytes(), result.toString());

            result = matcher.replaceAll(replText, null);
            REGEX_ASSERT_UTEXT_UTF8("".getBytes(), result.toString());
            result = matcher.replaceAll(replText, dest);
            Assert.assertEquals(dest, result);
            REGEX_ASSERT_UTEXT_UTF8("".getBytes(), result.toString());

        //
        // Empty substitution string
        //
        dataText = utext_openUTF8(data, -1); // ".abc..abc...abc.."
        matcher.reset(dataText);

        replText = utext_openUTF8(new byte[0], 0);
        result = matcher.replaceFirst(replText, null);
    final byte[] str_abcabc = { 0x2e, 0x2e, 0x2e, 0x61, 0x62, 0x63, 0x2e, 0x2e, 0x2e, 0x61, 0x62, 0x63, 0x2e, 0x2e }; /* ...abc...abc.. */
        REGEX_ASSERT_UTEXT_UTF8(str_abcabc, result.toString());
        result = matcher.replaceFirst(replText, dest);
        Assert.assertEquals(dest, result);
        REGEX_ASSERT_UTEXT_UTF8(str_abcabc, result.toString());

        result = matcher.replaceAll(replText, null);
    final byte[] str_dots = { 0x2e, 0x2e, 0x2e, 0x2e, 0x2e, 0x2e, 0x2e, 0x2e }; /* ........ */
        REGEX_ASSERT_UTEXT_UTF8(str_dots, result.toString());
        utext_replace(dest, 0, Util.utext_nativeLength(dest.toString()), new char[0], 0);
        result = matcher.replaceAll(replText, dest);
        Assert.assertEquals(dest, result);
        REGEX_ASSERT_UTEXT_UTF8(str_dots, result.toString());

        //
        // match whole string
        //
        {
            final byte[] str_abc = {0x61, 0x62, 0x63 }; /* abc */
            dataText = utext_openUTF8(str_abc, -1);
            matcher.reset(dataText);
        }

    final byte[] str_xyz = { 0x78, 0x79, 0x7a }; /* xyz */
        replText = utext_openUTF8(str_xyz, -1);
        result = matcher.replaceFirst(replText, null);
        REGEX_ASSERT_UTEXT_UTF8(str_xyz, result.toString());
        utext_replace(dest, 0, Util.utext_nativeLength(dest.toString()), new char[0], 0);
        result = matcher.replaceFirst(replText, dest);
        Assert.assertEquals(dest, result);
        REGEX_ASSERT_UTEXT_UTF8(str_xyz, result.toString());

        result = matcher.replaceAll(replText, null);
        REGEX_ASSERT_UTEXT_UTF8(str_xyz, result.toString());
        utext_replace(dest, 0, Util.utext_nativeLength(dest.toString()), new char[0], 0);
        result = matcher.replaceAll(replText, dest);
        Assert.assertEquals(dest, result);
        REGEX_ASSERT_UTEXT_UTF8(str_xyz, result.toString());

        //
        // Capture Group, simple case
        //
    final byte[] str_add = { 0x61, 0x28, 0x2e, 0x2e, 0x29 }; /* a(..) */
        re = utext_openUTF8(str_add, -1);
        RegexPattern pat2 = RegexPattern.compile(re, flags);

    final byte[] str_abcdefg = { 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67 }; /* abcdefg */
        dataText = utext_openUTF8(str_abcdefg, -1);
        RegexMatcher matcher2 = pat2.matcher().reset(dataText);

    final byte[] str_11 = { 0x24, 0x31, 0x24, 0x31 }; /* $1$1 */
        replText = utext_openUTF8(str_11, -1);
        result = matcher2.replaceFirst(replText, null);
    final byte[] str_bcbcdefg = { 0x62, 0x63, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67 }; /* bcbcdefg */
        REGEX_ASSERT_UTEXT_UTF8(str_bcbcdefg, result.toString());
        utext_replace(dest, 0, Util.utext_nativeLength(dest.toString()), new char[0], 0);
        result = matcher2.replaceFirst(replText, dest);
        Assert.assertEquals(dest, result);
        REGEX_ASSERT_UTEXT_UTF8(str_bcbcdefg, result.toString());

    final byte[] str_v = new byte[] { 0x54, 0x68, 0x65, 0x20, 0x76, 0x61, 0x6c, 0x75, 0x65, 0x20, 0x6f, 0x66, 0x20, 0x5c, 0x24, 0x31, 0x20, 0x69, 0x73, 0x20, 0x24, 0x31, 0x2e }; /* The value of \$1 is $1. */
        replText = utext_openUTF8(str_v, -1);
        REGEX_VERBOSE_TEXT(replText);
        result = matcher2.replaceFirst(replText, null);
    final byte[] str_Thevalueof1isbcdefg = { 0x54, 0x68, 0x65, 0x20, 0x76, 0x61, 0x6c, 0x75, 0x65, 0x20, 0x6f, 0x66, 0x20, 0x24, 0x31, 0x20, 0x69, 0x73, 0x20, 0x62, 0x63, 0x2e, 0x64, 0x65, 0x66, 0x67 }; /* The value of $1 is bc.defg */
        REGEX_ASSERT_UTEXT_UTF8(str_Thevalueof1isbcdefg, result.toString());
        utext_replace(dest, 0, Util.utext_nativeLength(dest.toString()), new char[0], 0);
        result = matcher2.replaceFirst(replText, dest);
        Assert.assertEquals(dest, result);
        REGEX_ASSERT_UTEXT_UTF8(str_Thevalueof1isbcdefg, result.toString());

    final byte[] str_byitselfnogroupnumber = { 0x5c, 0x24, 0x20, 0x62, 0x79, 0x20, 0x69, 0x74, 0x73, 0x65, 0x6c,
                0x66, 0x2c, 0x20, 0x6e, 0x6f, 0x20, 0x67, 0x72, 0x6f, 0x75, 0x70, 0x20, 0x6e, 0x75, 0x6d, 0x62,
                0x65, 0x72, 0x20, 0x5c, 0x24, 0x5c, 0x24, 0x5c, 0x24 }; /* \$ by itself, no group number \$\$\$ */
        replText = utext_openUTF8(str_byitselfnogroupnumber, -1);
        result = matcher2.replaceFirst(replText, null);
    final byte[] str_byitselfnogroupnumberdefg = { 0x24, 0x20, 0x62, 0x79, 0x20, 0x69, 0x74, 0x73, 0x65, 0x6c, 0x66, 0x2c, 0x20, 0x6e, 0x6f, 0x20, 0x67, 0x72, 0x6f, 0x75, 0x70, 0x20, 0x6e, 0x75, 0x6d, 0x62, 0x65, 0x72, 0x20, 0x24, 0x24, 0x24, 0x64, 0x65, 0x66, 0x67 }; /* $ by itself, no group number $$$defg */
        REGEX_ASSERT_UTEXT_UTF8(str_byitselfnogroupnumberdefg, result.toString());
        utext_replace(dest, 0, Util.utext_nativeLength(dest.toString()), new char[0], 0);
        result = matcher2.replaceFirst(replText, dest);
        Assert.assertEquals(dest, result);
        REGEX_ASSERT_UTEXT_UTF8(str_byitselfnogroupnumberdefg, result.toString());

        char supplDigitChars[] = { 0x53, 0x75, 0x70, 0x70, 0x6c, 0x65, 0x6d, 0x65, 0x6e, 0x74, 0x61, 0x6c, 0x20, 0x44, 0x69, 0x67, 0x69, 0x74, 0x20, 0x31, 0x20, 0x24, 0x78, 0x78, 0x78, 0x78, 0x2e }; /* Supplemental Digit 1 $xxxx. */
        //char supplDigitChars[] = "Supplemental Digit 1 $xxxx."; // \U0001D7CF, MATHEMATICAL BOLD DIGIT ONE
        //                                 012345678901234567890123456
        supplDigitChars[22] = 0xF0;
        supplDigitChars[23] = 0x9D;
        supplDigitChars[24] = 0x9F;
        supplDigitChars[25] = 0x8F;
        replText = utext_openUTF8(Util.toByteArrayWrapping(supplDigitChars), -1);

        result = matcher2.replaceFirst(replText, new ReplaceableString());
    final byte[] str_SupplementalDigit1bcdefg = { 0x53, 0x75, 0x70, 0x70, 0x6c, 0x65, 0x6d, 0x65, 0x6e, 0x74, 0x61, 0x6c, 0x20, 0x44, 0x69, 0x67, 0x69, 0x74, 0x20, 0x31, 0x20, 0x62, 0x63, 0x2e, 0x64, 0x65, 0x66, 0x67 }; /* Supplemental Digit 1 bc.defg */
        REGEX_ASSERT_UTEXT_UTF8(str_SupplementalDigit1bcdefg, result.toString());
        utext_replace(dest, 0, Util.utext_nativeLength(dest.toString()), new char[0], 0);
        result = matcher2.replaceFirst(replText, dest);
        Assert.assertEquals(dest, result);
        REGEX_ASSERT_UTEXT_UTF8(str_SupplementalDigit1bcdefg, result.toString());
        {
            final byte[] str_badcapturegroupnumber5 = {0x62, 0x61, 0x64, 0x20, 0x63, 0x61, 0x70, 0x74, 0x75, 0x72, 0x65, 0x20, 0x67, 0x72, 0x6f, 0x75, 0x70, 0x20, 0x6e, 0x75, 0x6d, 0x62, 0x65, 0x72, 0x20, 0x24, 0x35, 0x2e, 0x2e, 0x2e }; /* bad capture group number $5..." */
            final String replText2 = utext_openUTF8(str_badcapturegroupnumber5, -1);
            Assert.assertThrows(IndexOutOfBoundsException.class, () -> matcher2.replaceFirst(replText2, null));
            utext_replace(dest, 0, Util.utext_nativeLength(dest.toString()), new char[0], 0);
            Assert.assertThrows(IndexOutOfBoundsException.class, () -> matcher2.replaceFirst(replText2, dest));
//            REGEX_ASSERT_UTEXT_UTF8("abcdefg".getBytes(), destText.toString());
        }
        //
        // Replacement String with \\u hex escapes
        //
        {
      final byte[] str_abc1abc2abc3 = { 0x61, 0x62, 0x63, 0x20, 0x31, 0x20, 0x61, 0x62, 0x63, 0x20, 0x32, 0x20, 0x61, 0x62, 0x63, 0x20, 0x33 }; /* abc 1 abc 2 abc 3 */
      final byte[] str_u0043 = { 0x2d, 0x2d, 0x5c, 0x75, 0x30, 0x30, 0x34, 0x33, 0x2d, 0x2d }; /* --\u0043-- */
            dataText = utext_openUTF8(str_abc1abc2abc3, -1);
            replText = utext_openUTF8(str_u0043, -1);
            matcher.reset(dataText);

            result = matcher.replaceAll(replText, null);
        final byte[] str_C1C2C3 = { 0x2d, 0x2d, 0x43, 0x2d, 0x2d, 0x20, 0x31, 0x20, 0x2d, 0x2d, 0x43, 0x2d, 0x2d, 0x20, 0x32, 0x20, 0x2d, 0x2d, 0x43, 0x2d, 0x2d, 0x20, 0x33 }; /* --C-- 1 --C-- 2 --C-- 3 */
            REGEX_ASSERT_UTEXT_UTF8(str_C1C2C3, result.toString());
            utext_replace(dest, 0, Util.utext_nativeLength(dest.toString()), new char[0], 0);
            result = matcher.replaceAll(replText, dest);
            Assert.assertEquals(dest, result);
            REGEX_ASSERT_UTEXT_UTF8(str_C1C2C3, result.toString());
        }
        {
      final byte[] str_abc = { 0x61, 0x62, 0x63, 0x20, 0x21 }; /* abc ! */
            dataText = utext_openUTF8(str_abc, -1);
        final byte[] str_U00010000 = { 0x2d, 0x2d, 0x5c, 0x55, 0x30, 0x30, 0x30, 0x31, 0x30, 0x30, 0x30, 0x30, 0x2d, 0x2d }; /* --\U00010000-- */
            replText = utext_openUTF8(str_U00010000, -1);
            matcher.reset(dataText);

            char expected[] = { 0x2d, 0x2d, 0x78, 0x78, 0x78, 0x78, 0x2d, 0x2d, 0x20, 0x21 }; /* --xxxx-- ! */ // \U00010000, "LINEAR B SYLLABLE B008 A"
            //                          0123456789
            expected[2] = 0xF0;
            expected[3] = 0x90;
            expected[4] = 0x80;
            expected[5] = 0x80;

            result = matcher.replaceAll(replText, null);
            REGEX_ASSERT_UTEXT_UTF8(Util.toByteArrayWrapping(expected), result.toString());
            utext_replace(dest, 0, Util.utext_nativeLength(dest.toString()), new char[0], 0);
            result = matcher.replaceAll(replText, dest);
            Assert.assertEquals(dest, result);
            REGEX_ASSERT_UTEXT_UTF8(Util.toByteArrayWrapping(expected), result.toString());
        }
        // TODO:  need more through testing of capture substitutions.

        // Bug 4057
        //
        {

final byte[] str_ssee = { 0x73, 0x73, 0x28, 0x2e, 0x2a, 0x3f, 0x29, 0x65, 0x65 }; /* ss(.*?)ee */
final byte[] str_blah = { 0x54, 0x68, 0x65, 0x20, 0x6d, 0x61, 0x74, 0x63, 0x68, 0x65, 0x73, 0x20, 0x73, 0x74, 0x61, 0x72, 0x74, 0x20, 0x77, 0x69, 0x74, 0x68, 0x20, 0x73, 0x73, 0x20, 0x61, 0x6e, 0x64, 0x20, 0x65, 0x6e, 0x64, 0x20, 0x77, 0x69, 0x74, 0x68, 0x20, 0x65, 0x65, 0x20, 0x73, 0x73, 0x20, 0x73, 0x74, 0x75, 0x66, 0x66, 0x20, 0x65, 0x65, 0x20, 0x66, 0x69, 0x6e }; /* The matches start with ss and end with ee ss stuff ee fin */
final byte[] str_ooh = { 0x6f, 0x6f, 0x68 }; /* ooh */
            re = utext_openUTF8(str_ssee, -1);
            dataText = utext_openUTF8(str_blah, -1);
            replText = utext_openUTF8(str_ooh, -1);

            RegexMatcher m = new RegexMatcher(re, Collections.emptySet());

            result = new ReplaceableString();

            // Multiple finds do NOT bump up the previous appendReplacement position.
            m.reset(dataText);
            m.find();
            m.find();
            m.appendReplacement(result, replText);
        final byte[] str_blah2 = { 0x54, 0x68, 0x65, 0x20, 0x6d, 0x61, 0x74, 0x63, 0x68, 0x65, 0x73, 0x20, 0x73, 0x74, 0x61, 0x72, 0x74, 0x20, 0x77, 0x69, 0x74, 0x68, 0x20, 0x73, 0x73, 0x20, 0x61, 0x6e, 0x64, 0x20, 0x65, 0x6e, 0x64, 0x20, 0x77, 0x69, 0x74, 0x68, 0x20, 0x65, 0x65, 0x20, 0x6f, 0x6f, 0x68 }; /* The matches start with ss and end with ee ooh */
            REGEX_ASSERT_UTEXT_UTF8(str_blah2, result.toString());

            // After a reset into the interior of a string, appendReplacement still starts at beginning.

            Util.clear(result);
            m.reset(10);
            m.find();
            m.find();
            m.appendReplacement(result, replText);
        final byte[] str_blah3 = { 0x54, 0x68, 0x65, 0x20, 0x6d, 0x61, 0x74, 0x63, 0x68, 0x65, 0x73, 0x20, 0x73, 0x74, 0x61, 0x72, 0x74, 0x20, 0x77, 0x69, 0x74, 0x68, 0x20, 0x73, 0x73, 0x20, 0x61, 0x6e, 0x64, 0x20, 0x65, 0x6e, 0x64, 0x20, 0x77, 0x69, 0x74, 0x68, 0x20, 0x65, 0x65, 0x20, 0x6f, 0x6f, 0x68 }; /* The matches start with ss and end with ee ooh */
            REGEX_ASSERT_UTEXT_UTF8(str_blah3, result.toString());

            // find() at interior of string, appendReplacement still starts at beginning.

            Util.clear(result);
            m.reset();
            m.find(10);
            m.find();
            m.appendReplacement(result, replText);
        final byte[] str_blah8 = { 0x54, 0x68, 0x65, 0x20, 0x6d, 0x61, 0x74, 0x63, 0x68, 0x65, 0x73, 0x20, 0x73, 0x74, 0x61, 0x72, 0x74, 0x20, 0x77, 0x69, 0x74, 0x68, 0x20, 0x73, 0x73, 0x20, 0x61, 0x6e, 0x64, 0x20, 0x65, 0x6e, 0x64, 0x20, 0x77, 0x69, 0x74, 0x68, 0x20, 0x65, 0x65, 0x20, 0x6f, 0x6f, 0x68 }; /* The matches start with ss and end with ee ooh */
            REGEX_ASSERT_UTEXT_UTF8(str_blah8, result.toString());

            m.appendTail(result);
        final byte[] str_blah9 = { 0x54, 0x68, 0x65, 0x20, 0x6d, 0x61, 0x74, 0x63, 0x68, 0x65, 0x73, 0x20, 0x73, 0x74, 0x61, 0x72, 0x74, 0x20, 0x77, 0x69, 0x74, 0x68, 0x20, 0x73, 0x73, 0x20, 0x61, 0x6e, 0x64, 0x20, 0x65, 0x6e, 0x64, 0x20, 0x77, 0x69, 0x74, 0x68, 0x20, 0x65, 0x65, 0x20, 0x6f, 0x6f, 0x68, 0x20, 0x66, 0x69, 0x6e }; /* The matches start with ss and end with ee ooh fin */
            REGEX_ASSERT_UTEXT_UTF8(str_blah9, result.toString());
        }
    }


    //---------------------------------------------------------------------------
//
//      API_Pattern_UTF8  Test that the API for class RegexPattern is
//                        present and nominally working.
//
//---------------------------------------------------------------------------
    @Test
    public void API_Pattern_UTF8() {
        RegexPattern        pata = RegexPattern.compile("");    // Test default constructor to not crash.
        RegexPattern        patb = RegexPattern.compile("");

        Assert.assertEquals(patb, pata);
        Assert.assertEquals(pata, pata);

        String         re1;
        String         re2;

    final byte[] str_abcalmz = { 0x61, 0x62, 0x63, 0x5b, 0x61, 0x2d, 0x6c, 0x5d, 0x5b, 0x6d, 0x2d, 0x7a, 0x5d }; /* abc[a-l][m-z] */
    final byte[] str_def = { 0x64, 0x65, 0x66 }; /* def */
        re1 = utext_openUTF8(str_abcalmz, -1);
        re2 = utext_openUTF8(str_def, -1);

        RegexPattern pat1 = RegexPattern.compile(re1, Collections.emptySet());
        RegexPattern pat2 = RegexPattern.compile(re2, Collections.emptySet());
        Assert.assertEquals(pat1, pat1);
        REGEX_ASSERT(pat1 != pata);

        // Assign
        patb = pat1;
        Assert.assertEquals(pat1, patb);

        // Compile with no flags.
        RegexPattern pat1a = RegexPattern.compile(re1);
        Assert.assertEquals(pat1, pat1a);

        Assert.assertTrue(pat1a.flags().isEmpty());

        // Compile with different flags should be not equal
        RegexPattern pat1b = RegexPattern.compile(re1, EnumSet.of(UREGEX_CASE_INSENSITIVE));

        REGEX_ASSERT(pat1b != pat1a);
        Assert.assertEquals(EnumSet.of(UREGEX_CASE_INSENSITIVE), pat1b.flags());
        Assert.assertTrue(pat1a.flags().isEmpty());


        // clone TODO
/*
        RegexPattern pat1c = pat1.clone();
        Assert.assertEquals(*pat1, *pat1c);
        REGEX_ASSERT(*pat1c != *pat2);
*/

        //
        //   Verify that a matcher created from a cloned pattern works.
        //     (Jitterbug 3423)
        // TODO
        /*{

            String          pattern    = "";
        final char str_pL[] = { 0x5c, 0x70, 0x7b, 0x4c, 0x7d, 0x2b }; *//* \p{L}+ *//*
            pattern = utext_openUTF8(str_pL, -1);

            RegexPattern pSource = RegexPattern.compile(pattern, Collections.emptySet());
            RegexPattern  *pClone     = pSource.clone();

            RegexMatcher  mFromClone = pClone.matcher();

            String          input      = "";
        final char str_HelloWorld[] = { 0x48, 0x65, 0x6c, 0x6c, 0x6f, 0x20, 0x57, 0x6f, 0x72, 0x6c, 0x64 }; *//* Hello World *//*
            input = utext_openUTF8(str_HelloWorld, -1);
            mFromClone.reset(input);
            Assert.assertTrue(mFromClone.find());
            Assert.assertEquals("Hello", mFromClone.group());
            Assert.assertTrue(mFromClone.find());
            Assert.assertEquals("World", mFromClone.group());
            Assert.assertFalse(mFromClone.find());
        }*/

        //
        //   matches convenience API
        //
        {

            String      pattern;
            String      input   = "";

        final byte[] str_randominput = { 0x72, 0x61, 0x6e, 0x64, 0x6f, 0x6d, 0x20, 0x69, 0x6e, 0x70, 0x75, 0x74 }; /* random input */
            input = utext_openUTF8(str_randominput, -1);

        final byte[] str_dotstar = { 0x2e, 0x2a }; /* .* */
            pattern = utext_openUTF8(str_dotstar, -1);
            REGEX_ASSERT(RegexPattern.matches(pattern, input) == true);

        final byte[] str_abc = { 0x61, 0x62, 0x63 }; /* abc */
            pattern = utext_openUTF8(str_abc, -1);
            REGEX_ASSERT(RegexPattern.matches(pattern, "random input") == false);

        final byte[] str_nput = { 0x2e, 0x2a, 0x6e, 0x70, 0x75, 0x74 }; /* .*nput */
            pattern = utext_openUTF8(str_nput, -1);
            REGEX_ASSERT(RegexPattern.matches(pattern, "random input") == true);

            pattern = utext_openUTF8(str_randominput, -1);
            REGEX_ASSERT(RegexPattern.matches(pattern, "random input") == true);

        final byte[] str_u = { 0x2e, 0x2a, 0x75 }; /* .*u */
            pattern = utext_openUTF8(str_u, -1);
            REGEX_ASSERT(RegexPattern.matches(pattern, "random input") == false);
        }


        //
        // Split()
        //

    final byte[] str_spaceplus = { 0x20, 0x2b }; /*  + */
        re1 = utext_openUTF8(str_spaceplus, -1);
        pat1 = RegexPattern.compile(re1);
        String[] fields = new String[10];
        Arrays.fill(fields, "");

        int n;
        n = pat1.split("Now is the time", fields, 10);
        Assert.assertEquals(4, n);
        Assert.assertEquals("Now", fields[0]);
        Assert.assertEquals("is", fields[1]);
        Assert.assertEquals("the", fields[2]);
        Assert.assertEquals("time", fields[3]);
        Assert.assertEquals("", fields[4]);

        n = pat1.split("Now is the time", fields, 2);
        Assert.assertEquals(2, n);
        Assert.assertEquals("Now", fields[0]);
        Assert.assertEquals("is the time", fields[1]);
        Assert.assertEquals("the", fields[2]);   // left over from previous test

        fields[1] = "*";

        n = pat1.split("Now is the time", fields, 1);
        Assert.assertEquals(1, n);
        Assert.assertEquals("Now is the time", fields[0]);
        Assert.assertEquals("*", fields[1]);


        n = pat1.split("    Now       is the time   ", fields, 10);
        Assert.assertEquals(6, n);
        Assert.assertEquals("", fields[0]);
        Assert.assertEquals("Now", fields[1]);
        Assert.assertEquals("is", fields[2]);
        Assert.assertEquals("the", fields[3]);
        Assert.assertEquals("time", fields[4]);
        Assert.assertEquals("", fields[5]);
        Assert.assertEquals("", fields[6]);

        fields[2] = "*";
        n = pat1.split("     ", fields, 10);
        Assert.assertEquals(2, n);
        Assert.assertEquals("", fields[0]);
        Assert.assertEquals("", fields[1]);
        Assert.assertEquals("*", fields[2]);

        fields[0] = "foo";
        n = pat1.split("", fields, 10);
        Assert.assertEquals(0, n);
        Assert.assertEquals("foo", fields[0]);



        //  split, with a pattern with (capture)
        re1 = regextst_openUTF8FromInvariant("<(\\w*)>".getBytes(), -1);
        pat1 = RegexPattern.compile(re1);


        fields[6] = fields[7] = "*";
        n = pat1.split("<a>Now is <b>the time<c>", fields, 10);
        Assert.assertEquals(7, n);
        Assert.assertEquals("", fields[0]);
        Assert.assertEquals("a", fields[1]);
        Assert.assertEquals("Now is ", fields[2]);
        Assert.assertEquals("b", fields[3]);
        Assert.assertEquals("the time", fields[4]);
        Assert.assertEquals("c", fields[5]);
        Assert.assertEquals("", fields[6]);
        Assert.assertEquals("*", fields[7]);

        fields[6] = fields[7] = "*";
        n = pat1.split("  <a>Now is <b>the time<c>", fields, 10);
        Assert.assertEquals(7, n);
        Assert.assertEquals("  ", fields[0]);
        Assert.assertEquals("a", fields[1]);
        Assert.assertEquals("Now is ", fields[2]);
        Assert.assertEquals("b", fields[3]);
        Assert.assertEquals("the time", fields[4]);
        Assert.assertEquals("c", fields[5]);
        Assert.assertEquals("", fields[6]);
        Assert.assertEquals("*", fields[7]);


        fields[6] = "foo";
        n = pat1.split("  <a>Now is <b>the time<c> ", fields, 6);
        Assert.assertEquals(6, n);
        Assert.assertEquals("  ", fields[0]);
        Assert.assertEquals("a", fields[1]);
        Assert.assertEquals("Now is ", fields[2]);
        Assert.assertEquals("b", fields[3]);
        Assert.assertEquals("the time", fields[4]);
        Assert.assertEquals(" ", fields[5]);
        Assert.assertEquals("foo", fields[6]);


        fields[5] = "foo";
        n = pat1.split("  <a>Now is <b>the time<c>", fields, 5);
        Assert.assertEquals(5, n);
        Assert.assertEquals("  ", fields[0]);
        Assert.assertEquals("a", fields[1]);
        Assert.assertEquals("Now is ", fields[2]);
        Assert.assertEquals("b", fields[3]);
        Assert.assertEquals("the time<c>", fields[4]);
        Assert.assertEquals("foo", fields[5]);


        fields[5] = "foo";
        n = pat1.split("  <a>Now is <b>the time", fields, 5);
        Assert.assertEquals(5, n);
        Assert.assertEquals("  ", fields[0]);
        Assert.assertEquals("a", fields[1]);
        Assert.assertEquals("Now is ", fields[2]);
        Assert.assertEquals("b", fields[3]);
        Assert.assertEquals("the time", fields[4]);
        Assert.assertEquals("foo", fields[5]);


        n = pat1.split("  <a>Now is <b>the time<c>", fields, 4);
        Assert.assertEquals(4, n);
        Assert.assertEquals("  ", fields[0]);
        Assert.assertEquals("a", fields[1]);
        Assert.assertEquals("Now is ", fields[2]);
        Assert.assertEquals("the time<c>", fields[3]);



        re1 = regextst_openUTF8FromInvariant("([-,])".getBytes(), -1);
        pat1 = RegexPattern.compile(re1);
        n = pat1.split("1-10,20", fields, 10);
        Assert.assertEquals(5, n);
        Assert.assertEquals("1", fields[0]);
        Assert.assertEquals("-", fields[1]);
        Assert.assertEquals("10", fields[2]);
        Assert.assertEquals(",", fields[3]);
        Assert.assertEquals("20", fields[4]);



        //
        // split of a String based string, with library allocating output UTexts.
        //
        {

            RegexMatcher matcher = new RegexMatcher("(:)", Collections.emptySet());

            String[] splits = new String[10];
            int numFields = matcher.split("first:second:third", splits, splits.length);
            Assert.assertEquals(5, numFields);
            REGEX_ASSERT_UTEXT_INVARIANT("first".getBytes(), splits[0]);
            REGEX_ASSERT_UTEXT_INVARIANT(":".getBytes(), splits[1]);
            REGEX_ASSERT_UTEXT_INVARIANT("second".getBytes(), splits[2]);
            REGEX_ASSERT_UTEXT_INVARIANT(":".getBytes(), splits[3]);
            REGEX_ASSERT_UTEXT_INVARIANT("third".getBytes(), splits[4]);
            Assert.assertEquals(null, splits[5]);
        }


        //
        // RegexPattern.pattern() and patternText()
        //
        pat1 = RegexPattern.compile("");
        Assert.assertEquals("", pat1.pattern());
        REGEX_ASSERT_UTEXT_UTF8("".getBytes(), pat1.pattern());

    final byte[] helloWorldInvariant = "(Hello, world)*".getBytes();
        re1 = regextst_openUTF8FromInvariant(helloWorldInvariant, -1);
        pat1 = RegexPattern.compile(re1);
        REGEX_ASSERT_UNISTR("(Hello, world)*", pat1.pattern());
        REGEX_ASSERT_UTEXT_INVARIANT("(Hello, world)*".getBytes(), pat1.pattern());
    }


//---------------------------------------------------------------------------
//
//      Extended       A more thorough check for features of regex patterns
//                     The test cases are in a separate data file,
//                       source/tests/testdata/regextst.txt
//                     A description of the test data format is included in that file.
//
//---------------------------------------------------------------------------

    final char[] ReadAndConvertFile(final String path, final String charset) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/" + path)), charset)) {
            final int readLength = 1024;
            char[] buffer = new char[readLength];
            int bufferPosition = 0;
            int length;
            while ((length = reader.read(buffer, bufferPosition, readLength)) > 0) {
                bufferPosition += length;
                if (bufferPosition + readLength >= buffer.length) {
                    char[] newBuffer = new char[buffer.length * 2];
                    System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
                    buffer = newBuffer;
                }
            }
            char[] output = new char[bufferPosition];
            System.arraycopy(buffer, 0, output, 0, bufferPosition);
            return output;
        }
    }

    @Test
    public void Extended() throws IOException {
        byte[] tdd = new byte[2048];
    final String srcPath;

        int     lineNum = 0;

        //
        //  Open and read the test data file.
        //
        srcPath= "regextst.txt";

        char[] testData = ReadAndConvertFile(srcPath, "utf-8");

        //
        //  Put the test data into a StringBuffer
        //
        String testString = new String(testData);

        RegexMatcher quotedStuffMat = new RegexMatcher(("\\s*([\\'\\\"/])(.*?)\\1"), Collections.emptySet());
        RegexMatcher    commentMat  = new RegexMatcher(("\\s*(#.*)?$"), Collections.emptySet());
        RegexMatcher    flagsMat    = new RegexMatcher(("\\s*([ixsmdteDEGLMQvabtyYzZ2-9]*)([:letter:]*)"), Collections.emptySet());

        RegexMatcher lineMat = new RegexMatcher("(.*?)\\r?\\n", testString, Collections.emptySet());
        String testPattern;   // The pattern for test from the test file.
        String testFlags;     // the flags   for a test.
        String matchString;   // The marked up string to be used as input

        //
        //  Loop over the test data file, once per line.
        //
        while (lineMat.find()) {
            lineNum++;

            ReplaceableString testLine = new ReplaceableString(lineMat.group(1));
            if (testLine.length() == 0) {
                continue;
            }

            //
            // Parse the test line.  Skip blank and comment only lines.
            // Separate out the three main fields - pattern, flags, target.
            //

            commentMat.reset(testLine.toString());
            if (commentMat.lookingAt()) {
                // This line is a comment, or blank.
                continue;
            }

            //
            //  Pull out the pattern field, remove it from the test file line.
            //
            quotedStuffMat.reset(testLine.toString());
            if (quotedStuffMat.lookingAt()) {
                testPattern = quotedStuffMat.group(2);
                testLine.replace(0, quotedStuffMat.end(0), "");
            } else {
                throw new AssertionError("Bad pattern (missing quotes?)");
            }


            //
            //  Pull out the flags from the test file line.
            //
            flagsMat.reset(testLine.toString());
            flagsMat.lookingAt();                  // Will always match, possibly an empty string.
            testFlags = flagsMat.group(1);
            if (flagsMat.group(2).length() > 0) {
                throw new AssertionError("Bad Match flag. Scanning " + flagsMat.group(2).charAt(0));
            }
            testLine.replace(0, flagsMat.end(0), "");

            //
            //  Pull out the match string, as a whole.
            //    We'll process the <tags> later.
            //
            quotedStuffMat.reset(testLine.toString());
            if (quotedStuffMat.lookingAt()) {
                matchString = quotedStuffMat.group(2);
                testLine.replace(0, quotedStuffMat.end(0), "");
            } else {
                throw new AssertionError("Bad match string at test file");
            }

            //
            //  The only thing left from the input line should be an optional trailing comment.
            //
            commentMat.reset(testLine.toString());
            if (commentMat.lookingAt() == false) {
                throw new AssertionError("Unexpected characters at end of test line.");
            }

            //
            //  Run the test
            //
            regex_find(testPattern, testFlags, matchString, srcPath);
        }
    }



//---------------------------------------------------------------------------
//
//    regex_find(pattern, flags, inputString, lineNumber)
//
//         Function to run a single test from the Extended (data driven) tests.
//         See file test/testdata/regextst.txt for a description of the
//         pattern and inputString fields, and the allowed flags.
//         lineNumber is the source line in regextst.txt of the test.
//
//---------------------------------------------------------------------------


    //  Set a value into a UVector at position specified by a decimal number in
//   a StringBuffer.   This is a utility function needed by the actual test function,
//   which follows.
    static void set(final MutableVector32 vec, final int val, final String index) {

        int  idx = 0;
        for (int i=0; i<index.length(); i++) {
            int d= UCharacter.digit(index.charAt(i));
            if (d<0) {return;}
            idx = idx*10 + d;
        }
        while (vec.size()<idx+1) {vec.addElement(-1);}
        vec.setElementAt(val, idx);
    }

    static void setInt(final MutableVector32 vec, final int val, final int idx) {

        while (vec.size()<idx+1) {vec.addElement(-1);}
        vec.setElementAt(val, idx);
    }

    static IndexAndBoolean utextOffsetToNative(final StringWithOffset str, final int unistrOffset)
    {
        boolean couldFind = true;
        Util.utext_setNativeIndex(str, 0);
        int i = 0;
        while (i < unistrOffset) {
            int c = Util.utext_next32(str);
            if (c != U_SENTINEL) {
                i += Utf16Util.U16_LENGTH(c);
            } else {
                couldFind = false;
                break;
            }
        }
        int newNativeIndex = Math.toIntExact(Util.utext_getNativeIndex(str));
        return new IndexAndBoolean(newNativeIndex, couldFind);
    }

    static class IndexAndBoolean {
        final int i;
        final boolean b;

        IndexAndBoolean(final int i, final boolean b) {
            this.i = i;
            this.b = b;
        }
    }

    void regex_find(final String pattern,
                           final String flags,
                           final String inputString,
                           final String srcPath) {
        StringBuffer       unEscapedInput;
        ReplaceableString deTaggedInput = new ReplaceableString();

        byte[] patternChars  = null;
        byte[] inputChars = null;
        String               patternText    = "";
        StringWithOffset inputText      = new StringWithOffset("");

        RegexPattern        parsePat      = null;
        RegexMatcher        parseMatcher  = null;
        RegexPattern        callerPattern = null;
        RegexPattern UTF8Pattern = null;
        final RegexMatcher        matcher;
        final RegexMatcher UTF8Matcher;
        MutableVector32 groupStarts = new MutableVector32();
        MutableVector32             groupEnds = new MutableVector32();
        MutableVector32             groupStartsUTF8 = new MutableVector32();
        MutableVector32             groupEndsUTF8 = new MutableVector32();
        boolean               isMatch        = false, isUTF8Match = false;
        boolean               failed         = false;
        int             numFinds;
        int             i;
        boolean               useMatchesFunc   = false;
        boolean               useLookingAtFunc = false;
        int             regionStart      = -1;
        int             regionEnd        = -1;
        int             regionStartUTF8  = -1;
        int             regionEndUTF8    = -1;


        //
        //  Compile the caller's pattern
        //
        Collection<URegexpFlag> bflags = new ArrayList<>();
        if (flags.indexOf((char)0x69) >= 0)  { // 'i' flag
            bflags.add(UREGEX_CASE_INSENSITIVE);
        }
        if (flags.indexOf((char)0x78) >= 0)  { // 'x' flag
            bflags.add(UREGEX_COMMENTS);
        }
        if (flags.indexOf((char)0x73) >= 0)  { // 's' flag
            bflags.add(UREGEX_DOTALL);
        }
        if (flags.indexOf((char)0x6d) >= 0)  { // 'm' flag
            bflags.add(UREGEX_MULTILINE);
        }

        if (flags.indexOf((char)0x65) >= 0) { // 'e' flag
            bflags.add(UREGEX_ERROR_ON_UNKNOWN_ESCAPES);
        }
        if (flags.indexOf((char)0x44) >= 0) { // 'D' flag
            bflags.add(UREGEX_UNIX_LINES);
        }
        if (flags.indexOf((char)0x51) >= 0) { // 'Q' flag
            bflags.add(UREGEX_LITERAL);
        }


        try {
            callerPattern = RegexPattern.compile(pattern, bflags);
        } catch (UErrorException e) {
            if (flags.indexOf((char)0x45) >= 0) {  //  flags contain 'E'
                // Expected pattern compilation error.
                if (flags.indexOf((char)0x64) >= 0) {   // flags contain 'd'
                    LOGGER.info(() -> String.format("Pattern Compile returns \"%s\"", e));
                }
                return;
            } else {
                // Unexpected pattern compilation error.
                throw e;
            }
        }

        patternText = extractAndReadAsUtf8(pattern);

        try {
            UTF8Pattern = RegexPattern.compile(patternText, bflags);
        } catch (UErrorException e) {
            if (flags.indexOf((char)0x45) >= 0) {  //  flags contain 'E'
                // Expected pattern compilation error.
                if (flags.indexOf((char)0x64) >= 0) {   // flags contain 'd'
                    LOGGER.info(() -> String.format("Pattern Compile returns \"%s\"%n (UTF8)", e));
                }
                return;
            } else {
                // Unexpected pattern compilation error.
                throw e;
            }
        }

        if (flags.indexOf((char)0x64) >= 0) {  // 'd' flag
            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.info(callerPattern.pattern());
            }
        }

        if (flags.indexOf((char)0x45) >= 0) {  // 'E' flag
            throw new AssertionError("Expected, but did not get, a pattern compilation error for " + pattern);
        }


        //
        // Number of times find() should be called on the test string, default to 1
        //
        numFinds = 1;
        for (i=2; i<=9; i++) {
            if (flags.indexOf(Util.toCharExact(0x30 + i)) >= 0) {   // digit flag
                { Assert.assertEquals("More than one digit flag.  Scanning " + i + ".", 1, numFinds);
                }
                numFinds = i;
            }
        }

        // 'M' flag.  Use matches() instead of find()
        if (flags.indexOf((char)0x4d) >= 0) {
            useMatchesFunc = true;
        }
        if (flags.indexOf((char)0x4c) >= 0) {
            useLookingAtFunc = true;
        }

        //
        //  Find the tags in the input data, remove them, and record the group boundary
        //    positions.
        //
        parsePat = RegexPattern.compile("<(/?)(r|[0-9]+)>", Collections.emptySet());

        {
            String unescaped;
            try {
                unescaped = Utility.unescape(inputString);
            } catch (IllegalArgumentException e) {
                unescaped = inputString;
            }
            unEscapedInput = new StringBuffer(unescaped);
        }
        parseMatcher = parsePat.matcher(unEscapedInput.toString());
        while(parseMatcher.find()) {
            parseMatcher.appendReplacement(deTaggedInput, "");
            String groupNum = parseMatcher.group(2);
            if ("r".equals(groupNum)) {
                // <r> or </r>, a region specification within the string
                if ("/".equals(parseMatcher.group(1))) {
                    regionEnd = deTaggedInput.length();
                } else {
                    regionStart = deTaggedInput.length();
                }
            } else {
                // <digits> or </digits>, a group match boundary tag.
                if ("/".equals(parseMatcher.group(1))) {
                    set(groupEnds, deTaggedInput.length(), groupNum);
                } else {
                    set(groupStarts, deTaggedInput.length(), groupNum);
                }
            }
        }
        parseMatcher.appendTail(deTaggedInput);

        Assert.assertEquals("Mismatched <n> group tags in expected results.", 
                groupStarts.size(), groupEnds.size());
        Assert.assertFalse("mismatched <r> tags", (regionStart>=0 || regionEnd>=0) && (regionStart<0 || regionStart>regionEnd));

        //
        //  Configure the matcher according to the flags specified with this test.
        //
        matcher = callerPattern.matcher(deTaggedInput.toString());
/*
        if (flags.indexOf((char)0x74) >= 0) {   //  't' trace flag
            matcher.setTrace(true);
        }
*/

        inputText = new StringWithOffset(deTaggedInput.toString());

        UTF8Matcher = UTF8Pattern.matcher().reset(inputText.targetString);

        //
        //  Generate native indices for UTF8 versions of region and capture group info
        //
        if (UTF8Matcher != null) {
/*            if (flags.indexOf((char)0x74) >= 0) {   //  't' trace flag
                UTF8Matcher.setTrace(true);
            }*/
            if (regionStart>=0)    {
                regionStartUTF8 = utextOffsetToNative(inputText, regionStart).i;
            }
            if (regionEnd>=0)      {
                regionEndUTF8 = utextOffsetToNative(inputText, regionEnd).i;
            }

            //  Fill out the native index UVector info.
            //  Only need 1 loop, from above we know groupStarts.size() = groupEnds.size()
            for (i=0; i<groupStarts.size(); i++) {
                int  start = groupStarts.elementAti(i);
                //  -1 means there was no UVector slot and we won't be requesting that capture group for this test, don't bother inserting
                if (start >= 0) {
                    IndexAndBoolean startUTF8 = utextOffsetToNative(inputText, start);
                    Assert.assertTrue(String.format("Could not find native index for group start %d.  UTF16 index %d", i, start), startUTF8.b);
                    setInt(groupStartsUTF8, startUTF8.i, i);
                }

                int  end = groupEnds.elementAti(i);
                //  -1 means there was no UVector slot and we won't be requesting that capture group for this test, don't bother inserting
                if (end >= 0) {
                    IndexAndBoolean endUTF8 = utextOffsetToNative(inputText, end);
                    Assert.assertTrue(String.format("Could not find native index for group end %d.  UTF16 index %d", i, end), endUTF8.b);
                    setInt(groupEndsUTF8, endUTF8.i, i);
                }
            }
        }

        if (regionStart>=0) {
            matcher.region(regionStart, regionEnd);
            if (UTF8Matcher != null) {
                UTF8Matcher.region(regionStartUTF8, regionEndUTF8);
            }
        }
        if (flags.indexOf((char)0x61) >= 0) {   //  'a' anchoring bounds flag
            matcher.useAnchoringBounds(false);
            if (UTF8Matcher != null) {
                UTF8Matcher.useAnchoringBounds(false);
            }
        }
        if (flags.indexOf((char)0x62) >= 0) {   //  'b' transparent bounds flag
            matcher.useTransparentBounds(true);
            if (UTF8Matcher != null) {
                UTF8Matcher.useTransparentBounds(true);
            }
        }



        //
        // Do a find on the de-tagged input using the caller's pattern
        //     TODO: error on count>1 and not find().
        //           error on both matches() and lookingAt().
        //
        for (i=0; i<numFinds; i++) {
            if (useMatchesFunc) {
                isMatch = matcher.matches();
                if (UTF8Matcher != null) {
                    isUTF8Match = UTF8Matcher.matches();
                }
            } else  if (useLookingAtFunc) {
                isMatch = matcher.lookingAt();
                if (UTF8Matcher != null) {
                    isUTF8Match = UTF8Matcher.lookingAt();
                }
            } else {
                isMatch = matcher.find();
                if (UTF8Matcher != null) {
                    isUTF8Match = UTF8Matcher.find();
                }
            }
        }
/*        matcher.setTrace(false);
        if (UTF8Matcher != null) {
            UTF8Matcher.setTrace(false);
        }*/

        //
        // Match up the groups from the find() with the groups from the tags
        //

        // number of tags should match number of groups from find operation.
        // matcher.groupCount does not include group 0, the entire match, hence the +1.
        //   G option in test means that capture group data is not available in the
        //     expected results, so the check needs to be suppressed.
        Assert.assertFalse("Match expected, but none found.", isMatch == false && groupStarts.size() != 0);
        Assert.assertFalse("Match expected, but none found. (UTF8)", UTF8Matcher != null && isUTF8Match == false && groupStarts.size() != 0);
        LambdaAssert.assertFalse(() -> String.format("No match expected, but one found at position %d.", matcher.start()), isMatch && groupStarts.isEmpty());
        LambdaAssert.assertFalse(() -> String.format("No match expected, but one found at position %d (UTF-8).", UTF8Matcher.start()), UTF8Matcher != null && isUTF8Match && groupStarts.size() == 0);

        if (flags.indexOf((char)0x47 /*G*/) >= 0) {
            // Only check for match / no match.  Don't check capture groups.
            return;
        }

        for (i=0; i<=matcher.groupCount(); i++) {
            int finalI = i;
            if (groupStarts.isEmpty()) {
                Assert.assertThrows(IllegalStateException.class, () -> matcher.start(finalI));
            } else {
                int  expectedStart = (i >= groupStarts.size()? -1 : groupStarts.elementAti(i));
                Assert.assertEquals(String.format("Incorrect start position for group %d.  Expected %d, got %d",
                        finalI, expectedStart, matcher.start(finalI)), expectedStart, matcher.start(finalI));
            }
            if (groupStartsUTF8.isEmpty()) {
                Assert.assertThrows(IllegalStateException.class, () -> UTF8Matcher.start(finalI));
            } else {
                int  expectedStartUTF8 = (i >= groupStartsUTF8.size()? -1 : groupStartsUTF8.elementAti(i));
                Assert.assertFalse(String.format("Incorrect start position for group %d.  Expected %d, got %d (UTF8)",
                                finalI, expectedStartUTF8, UTF8Matcher.start(finalI)), UTF8Matcher != null && UTF8Matcher.start(finalI) != expectedStartUTF8);
            }

        }

        final int invalidIndex = i;
        Assert.assertThrows(expectedIllegalGroupException(!groupStarts.isEmpty()), () -> matcher.end(invalidIndex));
        Assert.assertThrows(expectedIllegalGroupException(!groupStartsUTF8.isEmpty()), () -> UTF8Matcher.end(invalidIndex));

        Assert.assertFalse(String.format("Expected %d capture groups, found %d.",
                        groupStarts.size()-1, matcher.groupCount()), matcher.groupCount()+1 < groupStarts.size());
        Assert.assertFalse(String.format("Expected %d capture groups, found %d. (UTF8)",
                        groupStarts.size()-1, UTF8Matcher.groupCount()), UTF8Matcher != null && UTF8Matcher.groupCount()+1 < groupStarts.size());

        Assert.assertFalse("requireEnd() returned true.  Expected false", (flags.indexOf((char)0x59) >= 0) &&   //  'Y' flag:  RequireEnd() == false
                matcher.requireEnd() == true);
        Assert.assertFalse("requireEnd() returned true.  Expected false (UTF8)", UTF8Matcher != null && (flags.indexOf((char)0x59) >= 0) &&   //  'Y' flag:  RequireEnd() == false
                UTF8Matcher.requireEnd() == true);

        Assert.assertFalse(String.format("requireEnd() returned false.  Expected true"), (flags.indexOf((char)0x79) >= 0) &&   //  'y' flag:  RequireEnd() == true
                matcher.requireEnd() == false);
        Assert.assertFalse(String.format("equireEnd() returned false.  Expected true (UTF8)"), UTF8Matcher != null && (flags.indexOf((char)0x79) >= 0) &&   //  'Y' flag:  RequireEnd() == false
                UTF8Matcher.requireEnd() == false);

        Assert.assertFalse(String.format("hitEnd() returned true.  Expected false"), (flags.indexOf((char)0x5A) >= 0) &&   //  'Z' flag:  hitEnd() == false
                matcher.hitEnd() == true);
        Assert.assertFalse(String.format("hitEnd() returned true.  Expected false (UTF8)"), UTF8Matcher != null && (flags.indexOf((char)0x5A) >= 0) &&   //  'Z' flag:  hitEnd() == false
                UTF8Matcher.hitEnd() == true);

        Assert.assertFalse("hitEnd() returned false.  Expected true", (flags.indexOf((char)0x7A) >= 0) &&   //  'z' flag:  hitEnd() == true
                matcher.hitEnd() == false);
            failed = true;
        Assert.assertFalse("hitEnd() returned false.  Expected true (UTF8)", UTF8Matcher != null && (flags.indexOf((char)0x7A) >= 0) &&   //  'z' flag:  hitEnd() == true
                UTF8Matcher.hitEnd() == false);
    }

    @SuppressWarnings("rawtypes")
    private static Class expectedIllegalGroupException(final boolean expectMatch) {
        return expectMatch ? IndexOutOfBoundsException.class : IllegalStateException.class;
    }


    //---------------------------------------------------------------------------
//
//      Errors     Check for error handling in patterns.
//
//---------------------------------------------------------------------------
    @Test
    public void Errors() {
        Assert.assertEquals(U_REGEX_SET_CONTAINS_STRING.getIndex() + 2, U_REGEX_MISSING_CLOSE_BRACKET.getIndex());

        //noinspection deprecation
        Assert.assertEquals(U_REGEX_STOPPED_BY_CALLER.getIndex() + 3, U_REGEX_ERROR_LIMIT.getIndex());

        // \escape sequences that aren't implemented yet.
        //regex_err("hex format \\x{abcd} not implemented", 1, 13, U_REGEX_UNIMPLEMENTED);

        // Missing close parentheses
        regex_err("Comment (?# with no close", 1, 25, U_REGEX_MISMATCHED_PAREN);
        regex_err("Capturing Parenthesis(...", 1, 25, U_REGEX_MISMATCHED_PAREN);
        regex_err("Grouping only parens (?: blah blah", 1, 34, U_REGEX_MISMATCHED_PAREN);

        // Extra close paren
        regex_err("Grouping only parens (?: blah)) blah", 1, 31, U_REGEX_MISMATCHED_PAREN);
        regex_err(")))))))", 1, 1, U_REGEX_MISMATCHED_PAREN);
        regex_err("(((((((", 1, 7, U_REGEX_MISMATCHED_PAREN);

        // Look-ahead, Look-behind
        //  TODO:  add tests for unbounded length look-behinds.
        regex_err("abc(?<@xyz).*", 1, 7, U_REGEX_RULE_SYNTAX);       // illegal construct

        // Attempt to use non-default flags
        {
            Collection<URegexpFlag> flags  = EnumSet.of(UREGEX_CANON_EQ,
                    UREGEX_COMMENTS, UREGEX_DOTALL,
                    UREGEX_MULTILINE);
            try {
                RegexPattern pat1 = RegexPattern.compile(".*", flags);
            } catch (UErrorException e) {
                Assert.assertEquals(U_REGEX_UNIMPLEMENTED, e.getErrorCode());
            }

        }


        // Quantifiers are allowed only after something that can be quantified.
        regex_err("+", 1, 1, U_REGEX_RULE_SYNTAX);
        regex_err("abc\ndef(*2)", 2, 5, U_REGEX_RULE_SYNTAX);
        regex_err("abc**", 1, 5, U_REGEX_RULE_SYNTAX);

        // Mal-formed {min,max} quantifiers
        regex_err("abc{a,2}",1,5, U_REGEX_BAD_INTERVAL);
        regex_err("abc{4,2}",1,8, U_REGEX_MAX_LT_MIN);
        regex_err("abc{1,b}",1,7, U_REGEX_BAD_INTERVAL);
        regex_err("abc{1,,2}",1,7, U_REGEX_BAD_INTERVAL);
        regex_err("abc{1,2a}",1,8, U_REGEX_BAD_INTERVAL);
        regex_err("abc{222222222222222222222}",1,14, U_REGEX_NUMBER_TOO_BIG);
        regex_err("abc{5,50000000000}", 1, 16, U_REGEX_NUMBER_TOO_BIG);        // Overflows int during scan
        regex_err("abc{5,687865858}", 1, 16, U_REGEX_NUMBER_TOO_BIG);          // Overflows regex binary format
        regex_err("abc{687865858,687865859}", 1, 24, U_REGEX_NUMBER_TOO_BIG);

        // Ticket 5389
        regex_err("*c", 1, 1, U_REGEX_RULE_SYNTAX);

        // Invalid Back Reference \0
        //    For ICU4C versions newer than 3.8, \0 introduces an octal escape.
        //
        regex_err("(ab)\\0", 1, 6, U_REGEX_BAD_ESCAPE_SEQUENCE);

    }

    //-------------------------------------------------------------------------------
//
//   PerlTests  - Run Perl's regular expression tests
//                The input file for this test is re_tests, the standard regular
//                expression test data distributed with the Perl source code.
//
//                Here is Perl's description of the test data file:
//
//        # The tests are in a separate file 't/op/re_tests'.
//        # Each line in that file is a separate test.
//        # There are five columns, separated by tabs.
//        #
//        # Column 1 contains the pattern, optionally enclosed in C<''>.
//        # Modifiers can be put after the closing C<'>.
//        #
//        # Column 2 contains the string to be matched.
//        #
//        # Column 3 contains the expected result:
//        #     y   expect a match
//        #     n   expect no match
//        #     c   expect an error
//        # B   test exposes a known bug in Perl, should be skipped
//        # b   test exposes a known bug in Perl, should be skipped if noamp
//        #
//        # Columns 4 and 5 are used only if column 3 contains C<y> or C<c>.
//        #
//        # Column 4 contains a string, usually C<$&>.
//        #
//        # Column 5 contains the expected result of double-quote
//        # interpolating that string after the match, or start of error message.
//        #
//        # Column 6, if present, contains a reason why the test is skipped.
//        # This is printed with "skipped", for harness to pick up.
//        #
//        # \n in the tests are interpolated, as are variables of the form ${\w+}.
//        #
//        # If you want to add a regular expression test that can't be expressed
//        # in this format, don't add it here: put it in op/pat.t instead.
//
//        For ICU4C, if field 3 contains an 'i', the test will be skipped.
//        The test exposes is some known incompatibility between ICU4C and Perl regexps.
//        (The i is in addition to whatever was there before.)
//
//-------------------------------------------------------------------------------
    @Test
    public void PerlTests() throws IOException {
        byte[] tdd = new byte[2048];
    final String srcPath;
        //
        //  Open and read the test data file.
        //
        srcPath= "re_tests.txt";

        char[] testData = ReadAndConvertFile(srcPath, "iso-8859-1");

        //
        //  Put the test data into a StringBuffer
        //
        StringBuffer testDataString = new StringBuffer(new String(testData));

        //
        //  Regex to break the input file into lines, and strip the new lines.
        //     One line per match, capture group one is the desired data.
        //
        RegexPattern linePat = RegexPattern.compile(("(.+?)[\\r\\n]+"), Collections.emptySet());
        RegexMatcher lineMat = linePat.matcher(testDataString.toString());

        //
        //  Regex to split a test file line into fields.
        //    There are six fields, separated by tabs.
        //
        RegexPattern fieldPat = RegexPattern.compile(("\\t"), Collections.emptySet());

        //
        //  Regex to identify test patterns with flag settings, and to separate them.
        //    Test patterns with flags look like 'pattern'i
        //    Test patterns without flags are not quoted:   pattern
        //   Coming out, capture group 2 is the pattern, capture group 3 is the flags.
        //
        RegexPattern flagPat = RegexPattern.compile(("('?)(.*)\\1(.*)"), Collections.emptySet());
        RegexMatcher flagMat = flagPat.matcher();

        //
        // The Perl tests reference several perl-isms, which are evaluated/substituted
        //   in the test data.  Not being perl, this must be done explicitly.  Here
        //   are string constants and REs for these constructs.
        //
        StringBuffer nulnulSrc = new StringBuffer("${nulnul}");
        String nulnul = "\\u0000\\u0000";
        nulnul = Utility.unescape(nulnul);

        StringBuffer ffffSrc = new StringBuffer("${ffff}");
        String ffff = "\\uffff";
        ffff = Utility.unescape(ffff);

        //  regexp for $-[0], $+[2], etc.
        RegexPattern groupsPat = RegexPattern.compile(("\\$([+\\-])\\[(\\d+)\\]"), Collections.emptySet());
        RegexMatcher groupsMat = groupsPat.matcher();

        //  regexp for $0, $1, $2, etc.
        RegexPattern cgPat = RegexPattern.compile(("\\$(\\d+)"), Collections.emptySet());
        RegexMatcher cgMat = cgPat.matcher();


        //
        // Main Loop for the Perl Tests, runs once per line from the
        //   test data file.
        //
        int  lineNum = 0;
        int  skippedUnimplementedCount = 0;
        while (lineMat.find()) { // TODO: Rework this into a parameterized test
            lineNum++;

            //
            //  Get a line, break it into its fields, do the Perl
            //    variable substitutions.
            //
            String line = lineMat.group(1);
            String[] fields = new String[7];
            Arrays.fill(fields, "");
            fieldPat.split(line, fields, 7);

            flagMat.reset(fields[0]);
            flagMat.matches();
            String pattern  = flagMat.group(2);
            pattern = pattern.replace("${bang}", "!");
            pattern = pattern.replace(nulnulSrc, ("\\u0000\\u0000"));
            pattern = pattern.replace(ffffSrc, ffff);

            //
            //  Identify patterns that include match flag settings,
            //    split off the flags, remove the extra quotes.
            //
            String flagStr = flagMat.group(3);
            Collection<URegexpFlag> flags =  new ArrayList<>();
        final char UChar_c = 0x63;  // Char constants for the flag letters.
        final char UChar_i = 0x69;  //   (Damn the lack of Unicode support in C)
        final char UChar_m = 0x6d;
        final char UChar_x = 0x78;
        final char UChar_y = 0x79;
            if (flagStr.indexOf(UChar_i) != -1) {
                flags.add(UREGEX_CASE_INSENSITIVE);
            }
            if (flagStr.indexOf(UChar_m) != -1) {
                flags.add(UREGEX_MULTILINE);
            }
            if (flagStr.indexOf(UChar_x) != -1) {
                flags.add(UREGEX_COMMENTS);
            }

            //
            // Compile the test pattern.
            //

            RegexPattern testPat;
            try {
                try {
                    testPat = RegexPattern.compile(pattern, flags);
                } catch (IllegalArgumentException e) {
                    throw new UErrorException(U_ILLEGAL_ARGUMENT_ERROR, e);
                }
            } catch (final UErrorException e) {
                if (e.getErrorCode() == U_REGEX_UNIMPLEMENTED) {
                    LOGGER.finest(() -> String.format("Not implemented"));
                    continue;
// TODO                    Assumptions.abort();
                } else {
                    // Some tests are supposed to generate errors.
                    //   Only report an error for tests that are supposed to succeed.
                    if (fields[2].indexOf(UChar_c) == -1  &&  // Compilation is not supposed to fail AND
                            fields[2].indexOf(UChar_i) == -1)     //   it's not an accepted ICU4C incompatibility
                    {
                        throw e;
                    }
                }
                continue;
            }

            if (fields[2].indexOf(UChar_i) >= 0) {
                // should skip this test.
                LOGGER.finest(() -> String.format("Skipped"));
                continue;
// TODO                    Assumptions.abort();
            }

            if (fields[2].indexOf(UChar_c) >= 0) {
                // This pattern should have caused a compilation error, but didn't/
                throw new AssertionError(String.format("line %d: Expected a pattern compile error, got success.", lineNum));
            }

            //
            // replace the Perl variables that appear in some of the
            //   match data strings.
            //
            String matchString = fields[1];
            matchString = matchString.replace(nulnulSrc, nulnul);
            matchString = matchString.replace(ffffSrc,   ffff);

            // Replace any \n in the match string with an actual new-line char.
            //  Don't do full unescape, as this unescapes more than Perl does, which
            //  causes other spurious failures in the tests.
            matchString = matchString.replace(("\\n"), "\n");



            //
            // Run the test, check for expected match/don't match result.
            //
            RegexMatcher testMat = testPat.matcher(matchString);
            boolean found = testMat.find();
            boolean expected = false;
            if (fields[2].indexOf(UChar_y) >=0) {
                expected = true;
            }
            Assert.assertEquals(String.format("Expected %smatch, got %smatch",
                    expected?"":"no ", found?"":"no " ), expected, found);

            // Don't try to check expected results if there is no match.
            //   (Some have stuff in the expected fields)
            if (!found) {


                continue;
            }

            //
            // Interpret the Perl expression from the fourth field of the data file,
            // building up a string from the results of the match.
            //   The Perl expression will contain references to the results of
            //     a regex match, including the matched string, capture group strings,
            //     group starting and ending indices, etc.
            //
            StringBuffer resultString = new StringBuffer();
            StringBuilder perlExpr = new StringBuilder(fields[3]);

            while (perlExpr.length() > 0) {
                //  Preferred usage.  Reset after any modification to input string.
                groupsMat.reset(perlExpr.toString());
                cgMat.reset(perlExpr.toString());

                if (perlExpr.toString().startsWith("$&")) {
                    resultString.append(testMat.group());
                    perlExpr.replace(0, 2, "");
                }

                else if (groupsMat.lookingAt()) {
                    // $-[0]   $+[2]  etc.
                    String digitString = groupsMat.group(2);
                    int[] t = {0};
                    int groupNum = Utility.parseNumber(digitString, t, 10);
                    String plusOrMinus = groupsMat.group(1);
                    int matchPosition;
                    if (plusOrMinus.compareTo("+") == 0) {
                        matchPosition = testMat.end(groupNum);
                    } else {
                        matchPosition = testMat.start(groupNum);
                    }
                    if (matchPosition != -1) {
                        Utility.appendNumber(resultString, matchPosition, 10, 0);
                    }
                    perlExpr.replace(0, groupsMat.end(), "");
                }

                else if (cgMat.lookingAt()) {
                    // $1, $2, $3, etc.
                    String digitString = cgMat.group(1);
                    int[] t = {0};
                    int groupNum = Utility.parseNumber(digitString, t, 10);
                    if (groupNum <= testMat.groupCount()) {
                        resultString.append(testMat.group(groupNum));
                    }

                    perlExpr.replace(0, cgMat.end(), "");
                }

                else if (perlExpr.toString().startsWith("@-")) {
                    int i;
                    for (i=0; i<=testMat.groupCount(); i++) {
                        if (i>0) {
                            resultString.append(" ");
                        }
                        Utility.appendNumber(resultString, testMat.start(i), 10, 0);
                    }
                    perlExpr.replace(0, 2, "");
                }

                else if (perlExpr.toString().startsWith("@+")) {
                    int i;
                    for (i=0; i<=testMat.groupCount(); i++) {
                        if (i>0) {
                            resultString.append(" ");
                        }
                        Utility.appendNumber(resultString, testMat.end(i), 10, 0);
                    }
                    perlExpr.replace(0, 2, "");
                }

                else if (perlExpr.toString().startsWith(("\\"))) {    // \Escape.  Take following char as a literal.
                    //           or as an escaped sequence (e.g. \n)
                    if (perlExpr.length() > 1) {
                        perlExpr.replace(0, 1, "");  // Remove the '\', but only if not last char.
                    }
                    char c = perlExpr.charAt(0);
                    switch (c) {
                        case 'n':   c = '\n'; break;
                        // add any other escape sequences that show up in the test expected results.
                    }
                    resultString.append(c);
                    perlExpr.replace(0, 1, "");
                }

                else  {
                    // Any characters from the perl expression that we don't explicitly
                    //  recognize before here are assumed to be literals and copied
                    //  as-is to the expected results.
                    resultString.append(perlExpr.charAt(0));
                    perlExpr.replace(0, 1, "");
                }
            }

            //
            // Expected Results Compare
            //
            String expectedS = fields[4];
            expectedS = expectedS.replace(nulnulSrc, nulnul);
            expectedS = expectedS.replace(ffffSrc,   ffff);
            expectedS = expectedS.replace(("\\n"), "\n");


            Assert.assertEquals(String.format("Line %d: Incorrect perl expression results.", lineNum), expectedS, resultString.toString());
        }
    }


    //-------------------------------------------------------------------------------
//
//   PerlTestsUTF8  Run Perl's regular expression tests on UTF-8-based UTexts
//                  (instead of using UnicodeStrings) to test the alternate engine.
//                  The input file for this test is re_tests, the standard regular
//                  expression test data distributed with the Perl source code.
//                  See PerlTests() for more information.
//
//-------------------------------------------------------------------------------
    @Test
    public void PerlTestsUTF8() throws IOException {
        byte[] tdd = new byte[2048];
    final String srcPath;
        String       patternText;
        byte[] patternChars = null;
        int     patternLength;
        int     patternCapacity = 0;
        String       inputText;
        byte[] inputChars = null;
        int     inputLength;
        int     inputCapacity = 0;

        //
        //  Open and read the test data file.
        //
        srcPath= "re_tests.txt";

        char[] testData = ReadAndConvertFile(srcPath, "iso-8859-1");

        //
        //  Put the test data into a StringBuffer
        //
        StringBuffer testDataString = new StringBuffer(new String(testData));

        //
        //  Regex to break the input file into lines, and strip the new lines.
        //     One line per match, capture group one is the desired data.
        //
        RegexPattern linePat = RegexPattern.compile(("(.+?)[\\r\\n]+"), Collections.emptySet());
        RegexMatcher lineMat = linePat.matcher(testDataString.toString());

        //
        //  Regex to split a test file line into fields.
        //    There are six fields, separated by tabs.
        //
        RegexPattern fieldPat = RegexPattern.compile(("\\t"), Collections.emptySet());

        //
        //  Regex to identify test patterns with flag settings, and to separate them.
        //    Test patterns with flags look like 'pattern'i
        //    Test patterns without flags are not quoted:   pattern
        //   Coming out, capture group 2 is the pattern, capture group 3 is the flags.
        //
        RegexPattern flagPat = RegexPattern.compile(("('?)(.*)\\1(.*)"), Collections.emptySet());
        RegexMatcher flagMat = flagPat.matcher();

        //
        // The Perl tests reference several perl-isms, which are evaluated/substituted
        //   in the test data.  Not being perl, this must be done explicitly.  Here
        //   are string constants and REs for these constructs.
        //
        StringBuffer nulnulSrc = new StringBuffer("${nulnul}");
        String nulnul = "\\u0000\\u0000";
        nulnul = Utility.unescape(nulnul);

        StringBuffer ffffSrc = new StringBuffer("${ffff}");
        String ffff = "\\uffff";
        ffff = Utility.unescape(ffff);

        //  regexp for $-[0], $+[2], etc.
        RegexPattern groupsPat = RegexPattern.compile(("\\$([+\\-])\\[(\\d+)\\]"), Collections.emptySet());
        RegexMatcher groupsMat = groupsPat.matcher();

        //  regexp for $0, $1, $2, etc.
        RegexPattern cgPat = RegexPattern.compile(("\\$(\\d+)"), Collections.emptySet());
        RegexMatcher cgMat = cgPat.matcher();


        //
        // Main Loop for the Perl Tests, runs once per line from the
        //   test data file.
        //
        int  lineNum = 0;
        int  skippedUnimplementedCount = 0;
        while (lineMat.find()) {
            lineNum++;

            //
            //  Get a line, break it into its fields, do the Perl
            //    variable substitutions.
            //
            String line = lineMat.group(1);
            String[] fields = new String[7];
            Arrays.fill(fields, "");
            fieldPat.split(line, fields, 7);

            flagMat.reset(fields[0]);
            flagMat.matches();
            String pattern  = flagMat.group(2);
            pattern = pattern.replace("${bang}", "!");
            pattern = pattern.replace(nulnulSrc, ("\\u0000\\u0000"));
            pattern = pattern.replace(ffffSrc, ffff);

            //
            //  Identify patterns that include match flag settings,
            //    split off the flags, remove the extra quotes.
            //
            String flagStr = flagMat.group(3);
            Collection<URegexpFlag> flags = new ArrayList<>();
        final char UChar_c = 0x63;  // Char constants for the flag letters.
        final char UChar_i = 0x69;  //   (Damn the lack of Unicode support in C)
        final char UChar_m = 0x6d;
        final char UChar_x = 0x78;
        final char UChar_y = 0x79;
            if (flagStr.indexOf(UChar_i) != -1) {
                flags.add(UREGEX_CASE_INSENSITIVE);
            }
            if (flagStr.indexOf(UChar_m) != -1) {
                flags.add(UREGEX_MULTILINE);
            }
            if (flagStr.indexOf(UChar_x) != -1) {
                flags.add(UREGEX_COMMENTS);
            }

            //
            // Put the pattern in a UTF-8 String
            //

            patternText = extractAndReadAsUtf8(pattern);

            //
            // Compile the test pattern.
            //
            RegexPattern testPat = null;
            try {
                try {
                    testPat = RegexPattern.compile(patternText, flags);
                } catch (IllegalArgumentException e) {
                    throw new UErrorException(U_ILLEGAL_ARGUMENT_ERROR, e);
                }
            } catch (final UErrorException e) {
                if (e.getErrorCode() == U_REGEX_UNIMPLEMENTED) {
                    LOGGER.finest(() -> String.format("Skipped"));
                    continue;
// TODO:                Assumptions.abort();
                } else {
                    // Some tests are supposed to generate errors.
                    //   Only report an error for tests that are supposed to succeed.
                    if (fields[2].indexOf(UChar_c) == -1 &&  // Compilation is not supposed to fail AND
                            fields[2].indexOf(UChar_i) == -1)     //   it's not an accepted ICU4C incompatibility
                    {
                        throw e;
                    }
                }
                continue;
            }

            if (fields[2].indexOf(UChar_i) >= 0) {
                // should skip this test.
                LOGGER.finest(() -> String.format("Skipped"));
                continue;
// TODO:                Assumptions.abort();
            }

            if (fields[2].indexOf(UChar_c) >= 0) {
                // This pattern should have caused a compilation error, but didn't/
                throw new AssertionError(String.format("line %d: Expected a pattern compile error, got success.", lineNum));
            }


            //
            // replace the Perl variables that appear in some of the
            //   match data strings.
            //
            String matchString = fields[1];
            matchString = matchString.replace(nulnulSrc, nulnul);
            matchString = matchString.replace(ffffSrc,   ffff);

            // Replace any \n in the match string with an actual new-line char.
            //  Don't do full unescape, as this unescapes more than Perl does, which
            //  causes other spurious failures in the tests.
            matchString = matchString.replace(("\\n"), "\n");

            //
            // Put the input in a UTF-8 String
            //

            inputText = extractAndReadAsUtf8(matchString);

            //
            // Run the test, check for expected match/don't match result.
            //
            RegexMatcher testMat = testPat.matcher().reset(inputText);
            boolean found = testMat.find();
            boolean expected = false;
            if (fields[2].indexOf(UChar_y) >=0) {
                expected = true;
            }
            Assert.assertEquals(String.format("line %d: Expected %smatch, got %smatch",
                    lineNum, expected?"":"no ", found?"":"no " ), found, expected);


            // Don't try to check expected results if there is no match.
            //   (Some have stuff in the expected fields)
            if (!found) {


                continue;
            }

            //
            // Interpret the Perl expression from the fourth field of the data file,
            // building up a string from the results of the match.
            //   The Perl expression will contain references to the results of
            //     a regex match, including the matched string, capture group strings,
            //     group starting and ending indices, etc.
            //
            StringBuffer resultString = new StringBuffer();
            StringBuilder perlExpr = new StringBuilder(fields[3]);

            while (perlExpr.length() > 0) {
                groupsMat.reset(perlExpr.toString());
                cgMat.reset(perlExpr.toString());

                if (perlExpr.toString().startsWith("$&")) {
                    resultString.append(testMat.group());
                    perlExpr.replace(0, 2, "");
                }

                else if (groupsMat.lookingAt()) {
                    // $-[0]   $+[2]  etc.
                    String digitString = groupsMat.group(2);
                    int[] t = { 0 };
                    int groupNum = Utility.parseNumber(digitString, t, 10);
                    String plusOrMinus = groupsMat.group(1);
                    int matchPosition;
                    if (plusOrMinus.compareTo("+") == 0) {
                        matchPosition = testMat.end(groupNum);
                    } else {
                        matchPosition = testMat.start(groupNum);
                    }
                    if (matchPosition != -1) {
                        Utility.appendNumber(resultString, matchPosition, 10, 0);
                    }
                    perlExpr.replace(0, groupsMat.end(), "");
                }

                else if (cgMat.lookingAt()) {
                    // $1, $2, $3, etc.
                    String digitString = cgMat.group(1);
                    int[] t = { 0 };
                    int groupNum = Utility.parseNumber(digitString, t, 10);
                    if (groupNum <= testMat.groupCount()) {
                        resultString.append(testMat.group(groupNum));
                    }

                    perlExpr.replace(0, cgMat.end(), "");
                }

                else if (perlExpr.toString().startsWith("@-")) {
                    int i;
                    for (i=0; i<=testMat.groupCount(); i++) {
                        if (i>0) {
                            resultString.append(" ");
                        }
                        Utility.appendNumber(resultString, testMat.start(i), 10, 0);
                    }
                    perlExpr.replace(0, 2, "");
                }

                else if (perlExpr.toString().startsWith("@+")) {
                    int i;
                    for (i=0; i<=testMat.groupCount(); i++) {
                        if (i>0) {
                            resultString.append(" ");
                        }
                        Utility.appendNumber(resultString, testMat.end(i), 10, 0);
                    }
                    perlExpr.replace(0, 2, "");
                }

                else if (perlExpr.toString().startsWith(("\\"))) {    // \Escape.  Take following char as a literal.
                    //           or as an escaped sequence (e.g. \n)
                    if (perlExpr.length() > 1) {
                        perlExpr.replace(0, 1, "");  // Remove the '\', but only if not last char.
                    }
                    char c = perlExpr.charAt(0);
                    switch (c) {
                        case 'n':   c = '\n'; break;
                        // add any other escape sequences that show up in the test expected results.
                    }
                    resultString.append(c);
                    perlExpr.replace(0, 1, "");
                }

                else  {
                    // Any characters from the perl expression that we don't explicitly
                    //  recognize before here are assumed to be literals and copied
                    //  as-is to the expected results.
                    resultString.append(perlExpr.charAt(0));
                    perlExpr.replace(0, 1, "");
                }
            }

            //
            // Expected Results Compare
            //
            String expectedS = fields[4];
            expectedS = expectedS.replace(nulnulSrc, nulnul);
            expectedS = expectedS.replace(ffffSrc,   ffff);
            expectedS = expectedS.replace(("\\n"), "\n");


            Assert.assertEquals(String.format("Line %d: Incorrect perl expression results.", lineNum), expectedS, resultString.toString());



        }
    }

    private static String extractAndReadAsUtf8(final String initialString) {
        byte[] utfBytes = initialString.getBytes(StandardCharsets.UTF_8);
        return utext_openUTF8(utfBytes, utfBytes.length);
    }


    //--------------------------------------------------------------
//
//  Bug6149   Verify limits to heap expansion for backtrack stack.
//             Use this pattern,
//                 "(a?){1,8000000}"
//             Note: was an unbounded upperbounds, but that now has loop-breaking enabled.
//                   This test is likely to be fragile, as further optimizations stop
//                   more cases of pointless looping in the match engine.
//
//---------------------------------------------------------------
    @Test
    public void Bug6149() {
        Set<URegexpFlag> flags=Collections.emptySet();


        RegexMatcher matcher = new RegexMatcher("(a?){1,8000000}", "xyz", flags);
        boolean result = false;
        REGEX_ASSERT_FAIL(() -> matcher.matches(), U_REGEX_STACK_OVERFLOW);
        Assert.assertFalse(result);
    }


//
//   Callbacks()    Test the callback function.
//                  When set, callbacks occur periodically during matching operations,
//                  giving the application code the ability to abort the operation
//                  before it's normal completion.
//

    static class callBackContext {
        private final RegexTest        test;
        private int          maxCalls;
        private int          numCalls;
        private int          lastSteps;

        callBackContext(final RegexTest test) {
            this.test = test;
        }

        void reset(final int max) {maxCalls=max; numCalls=0; lastSteps=0;}
    };

    static final URegexMatchCallback testCallBackFn = new URegexMatchCallback() {
        @Override
        public boolean onMatch(final Object context, final int steps) {
            callBackContext info = (callBackContext) context;
            Assert.assertEquals("incorrect steps in callback", steps, info.lastSteps + 1);
            info.lastSteps = steps;
            info.numCalls++;
            return (info.numCalls < info.maxCalls);
        }
    };

    @Test
    public void Callbacks() {
        {
            // Getter returns NULLs if no callback has been set

            //   The variables that the getter will fill in.
            //   Init to non-null values so that the action of the getter can be seen.
        final Object returnedContext;
            URegexMatchCallback returnedFn;


            RegexMatcher matcher = new RegexMatcher("x", Collections.emptySet());
            returnedFn = matcher.getMatchCallback();
            returnedContext = matcher.getMatchCallbackContext();
            Assert.assertNull(returnedFn);
            Assert.assertNull(returnedContext);
        }

        {
            // Set and Get work
            callBackContext cbInfo = new callBackContext(this);
            final Object returnedContext;
            URegexMatchCallback returnedFn;

            RegexMatcher matcher = new RegexMatcher(("((.)+\\2)+x"), Collections.emptySet());  // A pattern that can run long.
            matcher.setMatchCallback(testCallBackFn, cbInfo);
            returnedFn = matcher.getMatchCallback();
            returnedContext = matcher.getMatchCallbackContext();
            Assert.assertEquals(testCallBackFn, returnedFn);
            Assert.assertEquals(cbInfo, returnedContext);

            // A short-running match shouldn't invoke the callback

            cbInfo.reset(1);
            StringBuffer s = new StringBuffer("xxx");
            matcher.reset(s.toString());
            REGEX_ASSERT(matcher.matches());
            Assert.assertEquals(0, cbInfo.numCalls);

            // A medium-length match that runs long enough to invoke the
            //   callback, but not so long that the callback aborts it.

            cbInfo.reset(4);
            s = new StringBuffer("aaaaaaaaaaaaaaaaaaab");
            matcher.reset(s.toString());
            Assert.assertFalse(matcher.matches());
            REGEX_ASSERT(cbInfo.numCalls > 0);

            // A longer running match that the callback function will abort.

            cbInfo.reset(4);
            s = new StringBuffer("aaaaaaaaaaaaaaaaaaaaaaab");
            matcher.reset(s.toString());
            REGEX_ASSERT_FAIL(() -> matcher.matches(), U_REGEX_STOPPED_BY_CALLER);
            Assert.assertEquals(4, cbInfo.numCalls);

            // A longer running find that the callback function will abort.

            cbInfo.reset(4);
            s = new StringBuffer("aaaaaaaaaaaaaaaaaaaaaaab");
            matcher.reset(s.toString());
            REGEX_ASSERT_FAIL(() -> matcher.find(), U_REGEX_STOPPED_BY_CALLER);
            Assert.assertEquals(4, cbInfo.numCalls);
        }


    }


//
//   FindProgressCallbacks()    Test the find "progress" callback function.
//                  When set, the find progress callback will be invoked during a find operations
//                  after each return from a match attempt, giving the application the opportunity
//                  to terminate a long-running find operation before it's normal completion.
//

    static class progressCallBackContext {
        final RegexTest        test;
        long          lastIndex;
        int          maxCalls;
        int          numCalls;

        progressCallBackContext(final RegexTest test) {
            this.test = test;
        }

        void reset(int max) {maxCalls=max; numCalls=0;lastIndex=0;}
    };

// call-back function for find().
// Return true to continue the find().
// Return false to stop the find().
    static final URegexFindProgressCallback
    testProgressCallBackFn = (final Object context, long matchIndex) -> {
        progressCallBackContext  info = (progressCallBackContext) context;
        info.numCalls++;
        info.lastIndex = matchIndex;
//    info.test.infoln("ProgressCallback - matchIndex = %d, numCalls = %d\n", matchIndex, info.numCalls);
        return (info.numCalls < info.maxCalls);
    };

    @Test
    public void FindProgressCallbacks() {
        {
            // Getter returns NULLs if no callback has been set

            //   The variables that the getter will fill in.
            //   Init to non-null values so that the action of the getter can be seen.
        final Object returnedContext;
            URegexFindProgressCallback  returnedFn = testProgressCallBackFn;


            RegexMatcher matcher = new RegexMatcher("x", Collections.emptySet());
            returnedFn = matcher.getFindProgressCallback();
            returnedContext = matcher.getFindProgressCallbackContext();
            Assert.assertNull(returnedFn);
            Assert.assertNull(returnedContext);
        }

        {
            // Set and Get work
            progressCallBackContext cbInfo = new progressCallBackContext(this);
        final Object returnedContext;
            URegexFindProgressCallback  returnedFn;

            RegexMatcher matcher = new RegexMatcher(("((.)\\2)x"), Collections.emptySet());
            matcher.setFindProgressCallback(testProgressCallBackFn, cbInfo);
            returnedFn = matcher.getFindProgressCallback();
            returnedContext = matcher.getFindProgressCallbackContext();
            Assert.assertEquals(testProgressCallBackFn, returnedFn);
            Assert.assertEquals(cbInfo, returnedContext);

            // A find that matches on the initial position does NOT invoke the callback.

            cbInfo.reset(100);
            StringBuffer s = new StringBuffer("aaxxx");
            matcher.reset(s.toString());
            REGEX_ASSERT(matcher.find(0));
            Assert.assertEquals(0, cbInfo.numCalls);

            // A medium running find() that causes matcher.find() to invoke our callback for each index,
            //   but not so many times that we interrupt the operation.

            s = new StringBuffer("aaaaaaaaaaaaaaaaaaab");
            cbInfo.reset(s.length()); //  Some upper limit for number of calls that is greater than size of our input string
            matcher.reset(s.toString());
            REGEX_ASSERT(matcher.find(0)==false);
            REGEX_ASSERT(cbInfo.numCalls > 0 && cbInfo.numCalls < 25);

            // A longer running match that causes matcher.find() to invoke our callback which we cancel/interrupt at some point.

            StringBuilder s1 = new StringBuilder("aaaaaaaaaaaaaaaaaaaaaaab");
            cbInfo.reset(s1.length() - 5); //  Bail early somewhere near the end of input string
            matcher.reset(s1.toString());
            REGEX_ASSERT_FAIL(() -> matcher.find(0), U_REGEX_STOPPED_BY_CALLER);
            REGEX_ASSERT(cbInfo.numCalls == s1.length() - 5);

            // Now a match that will succeed, but after an interruption

            StringBuilder s2 = new StringBuilder("aaaaaaaaaaaaaa aaaaaaaaab xxx");
            cbInfo.reset(s2.length() - 10); //  Bail early somewhere near the end of input string
            matcher.reset(s2.toString());
            REGEX_ASSERT_FAIL(() -> matcher.find(0), U_REGEX_STOPPED_BY_CALLER);
            // Now retry the match from where left off
            cbInfo.maxCalls = 100; //  No callback limit

            REGEX_ASSERT(matcher.find(cbInfo.lastIndex));
        }


    }


//--------------------------------------------------------------
//
//  NamedCapture   Check basic named capture group functionality
//
//--------------------------------------------------------------
@Test
public void NamedCapture() {

        RegexPattern pat = RegexPattern.compile("abc()()(?<three>xyz)(de)(?<five>hmm)(?<six>oh)f\\k<five>", Collections.emptySet());
        int group = pat.groupNumberFromName("five");
        Assert.assertEquals(group, 5);
        group = pat.groupNumberFromName("three");
        Assert.assertEquals(group, 3);


        group = pat.groupNumberFromName("six");
        Assert.assertEquals(group, 6);

        REGEX_ASSERT_FAIL(() -> pat.groupNumberFromName("nosuch"), U_REGEX_INVALID_CAPTURE_GROUP_NAME);

        // ReplaceAll with named capture group.

        StringBuffer text = new StringBuffer("Substitution of <<quotes>> for <<double brackets>>");
        StringBuffer replacedText;

        {
            RegexMatcher m = new RegexMatcher("<<(?<mid>.+?)>>", text.toString(), Collections.emptySet());
            // m.pattern().dumpPattern();
            replacedText = m.replaceAll("'${mid}'");
            Assert.assertEquals("Substitution of 'quotes' for 'double brackets'", replacedText.toString());
        }

        // ReplaceAll, allowed capture group numbers.
        {
            text = new StringBuffer("abcmxyz");
            RegexMatcher m = new RegexMatcher("..(?<one>m)(.)(.)", text.toString(), Collections.emptySet());


            replacedText = m.replaceAll("<$0>");   // group 0, full match, is allowed.
            Assert.assertEquals("a<bcmxy>z", replacedText.toString());


            replacedText = m.replaceAll("<$1>");      // group 1 by number.
            Assert.assertEquals("a<m>z", replacedText.toString());


            replacedText = m.replaceAll("<${one}>");   // group 1 by name.
            Assert.assertEquals("a<m>z", replacedText.toString());


            replacedText = m.replaceAll("<$2>");   // group 2.
            Assert.assertEquals("a<x>z", replacedText.toString());


            replacedText = m.replaceAll("<$3>");
            Assert.assertEquals("a<y>z", replacedText.toString());


            Assert.assertThrows(IndexOutOfBoundsException.class, () -> m.replaceAll("<$4>"));


            replacedText = m.replaceAll("<$04>");      // group 0, leading 0,
            //                                                           trailing out-of-range 4 passes through.
            Assert.assertEquals("a<bcmxy4>z", replacedText.toString());


            replacedText = m.replaceAll(("<$000016>"));  // Consume leading zeroes. Don't consume digits                                                 //   that push group num out of range.
            Assert.assertEquals("a<m6>z", replacedText.toString());              //   This is group 1.


            replacedText = m.replaceAll(("<$3$2$1${one}>"));
            Assert.assertEquals("a<yxmm>z", replacedText.toString());


            replacedText = m.replaceAll(("$3$2$1${one}"));
            Assert.assertEquals("ayxmmz", replacedText.toString());


            REGEX_ASSERT_FAIL(() -> m.replaceAll(("<${noSuchName}>")), U_REGEX_INVALID_CAPTURE_GROUP_NAME);


            REGEX_ASSERT_FAIL(() -> m.replaceAll(("<${invalid-name}>")), U_REGEX_INVALID_CAPTURE_GROUP_NAME);


            REGEX_ASSERT_FAIL(() -> m.replaceAll(("<${one")), U_REGEX_INVALID_CAPTURE_GROUP_NAME);


            REGEX_ASSERT_FAIL(() -> m.replaceAll(("$not a capture group")), U_REGEX_INVALID_CAPTURE_GROUP_NAME);
        }
    }

    //--------------------------------------------------------------
//
//  NamedCaptureLimits   Patterns with huge numbers of named capture groups.
//                       The point is not so much what the exact limit is,
//                       but that a largish number doesn't hit bad non-linear performance,
//                       and that exceeding the limit fails cleanly.
//
//--------------------------------------------------------------
    // TODO: Mark this test as large
    // @Test
    void NamedCaptureLimits() {
    final int goodLimit = 1000000;     // Pattern w this many groups builds successfully.
    final int failLimit = 10000000;    // Pattern exceeds internal limits, fails to compile.
        String nnbuf;
        StringBuffer pattern = new StringBuffer();
        int nn;
        for (nn=1; nn<goodLimit; nn++) {
            nnbuf = String.format("(?<nn%d>)", nn);
            pattern.append(nnbuf);
        }
        RegexPattern pat = RegexPattern.compile(pattern.toString(), Collections.emptySet());
        for (nn=1; nn<goodLimit; nn++) {
            nnbuf = String.format("nn%d", nn);
            int groupNum = pat.groupNumberFromName(nnbuf);
            Assert.assertEquals(groupNum, nn);
            if (nn != groupNum) {
                break;
            }
        }

        Util.truncate(pattern, 0);
        for (nn=1; nn<failLimit; nn++) {
            nnbuf = String.format("(?<nn%d>)", nn);
            pattern.append(nnbuf);
        }

        REGEX_ASSERT_FAIL(() -> RegexPattern.compile(pattern.toString(), Collections.emptySet()), U_REGEX_PATTERN_TOO_BIG);
    }


    //--------------------------------------------------------------
//
//  Bug7651   Regex pattern that exceeds default operator stack depth in matcher.
//
//---------------------------------------------------------------
    @Test
    public void Bug7651() {
        String pattern1 = "((?<![A-Za-z0-9])[#\\uff03][A-Za-z0-9_][A-Za-z0-9_\\u00c0-\\u00d6\\u00c8-\\u00f6\\u00f8-\\u00ff]*|(?<![A-Za-z0-9_])[@\\uff20][A-Za-z0-9_]+(?:\\/[\\w-]+)?|(https?\\:\\/\\/|www\\.)\\S+(?<![\\!\\),\\.:;\\]\\u0080-\\uFFFF])|\\$[A-Za-z]+)";
        //  The following should exceed the default operator stack depth in the matcher, i.e. force the matcher to malloc instead of using fSmallData.
        //  It will cause a segfault if RegexMatcher tries to use fSmallData instead of malloc'ing the memory needed (see init2) for the pattern operator stack allocation.
        String pattern2 = "((https?\\:\\/\\/|www\\.)\\S+(?<![\\!\\),\\.:;\\]\\u0080-\\uFFFF])|(?<![A-Za-z0-9_])[\\@\\uff20][A-Za-z0-9_]+(?:\\/[\\w\\-]+)?|(?<![A-Za-z0-9])[\\#\\uff03][A-Za-z0-9_][A-Za-z0-9_\\u00c0-\\u00d6\\u00c8-\\u00f6\\u00f8-\\u00ff]*|\\$[A-Za-z]+)";
        StringBuffer s = new StringBuffer("#ff @abcd This is test");
        RegexPattern  REPattern = null;
        RegexMatcher  REMatcher = null;

        REPattern = RegexPattern.compile(pattern1, Collections.emptySet());
        REMatcher = REPattern.matcher(s.toString());
        REGEX_ASSERT(REMatcher.find());
        Assert.assertEquals(0, REMatcher.start());




        REPattern = RegexPattern.compile(pattern2, Collections.emptySet());
        REMatcher = REPattern.matcher(s.toString());
        REGEX_ASSERT(REMatcher.find());
        Assert.assertEquals(0, REMatcher.start());



    }

    @Test
    public void Bug7740() {

        RegexMatcher m = new RegexMatcher("(a)", "abcdef", Collections.emptySet());
        REGEX_ASSERT(m.lookingAt());
    }

// Bug 8479:  was crashing whith a Bogus StringBuffer as input.

    @Test
    public void Bug8479() {
        final RegexMatcher pMatcher = new RegexMatcher("\\Aboo\\z", EnumSet.of(UREGEX_DOTALL, UREGEX_CASE_INSENSITIVE));
        pMatcher.reset("");
        Assert.assertFalse(pMatcher.matches());
    }


    // Bug 7029
    @Test
    public void Bug7029() {


        final RegexMatcher pMatcher = new RegexMatcher(".", Collections.emptySet());
        String text = "abc.def";
        String[] splits = new String[10];
        int numFields = pMatcher.split(text, splits, 10);
        Assert.assertEquals(8, numFields);

    }

    // Bug 9283
//   This test is checking for the existence of any supplemental characters that case-fold
//   to a bmp character.
//
//   At the time of this writing there are none. If any should appear in a subsequent release
//   of Unicode, the code in regular expressions compilation that determines the longest
//   possible match for a literal string  will need to be enhanced.
//
//   See file regexcmp.cpp, case URX_STRING_I in RegexCompile.maxMatchLength()
//   for details on what to do in case of a failure of this test.
//
    @Test
    public void Bug9283() {

        UnicodeSet supplementalsWithCaseFolding = new UnicodeSet("[[:CWCF:]&[\\U00010000-\\U0010FFFF]]");
        int index;
        for (index=0; ; index++) {
            int[] c = { supplementalsWithCaseFolding.charAt(index) };
            if (c[0] == -1) {
                break;
            }
            StringBuffer cf = new StringBuffer(new String(c, 0, 1));
            Util.foldCase(cf);
            Assert.assertTrue(String.format("'%s' (\\u%08X) folded to %d characters", cf, c[0], cf.length()), cf.length() >= 2);
        }
    }

    @Test
    public void TestCaseInsensitiveStarters() {
        // Test that the data used by RegexCompile.findCaseInsensitiveStarters() hasn't
        //  become stale because of new Unicode characters.
        // If it is stale, rerun the generation tool
        //    https://github.com/unicode-org/icu/tree/main/tools/unicode/c/genregexcasing
        // and replace the embedded data in i18n/regexcmp.cpp

        for (int cp=0; cp<=0x10ffff; cp++) {
            if (!UCharacter.hasBinaryProperty(cp, CASE_SENSITIVE)) {
                continue;
            }
            UnicodeSet s = new UnicodeSet(cp, cp);
            s.closeOver(CASE_INSENSITIVE);
            UnicodeSetIterator setIter = new UnicodeSetIterator(s);
            while (setIter.next()) {
                final StringBuffer str = new StringBuffer(setIter.getString());
                int firstChar = Util.char32At(str, 0);
                UnicodeSet starters = new UnicodeSet();
                RegexCompile.findCaseInsensitiveStarters(firstChar, starters);
                Assert.assertTrue(String.format("CaseInsensitiveStarters for \\u%x is missing character \\u%x.", cp, firstChar), starters.contains(cp));
            }
        }
    }


    @Test
    public void TestBug11049() {
        // Original bug report: pattern with match start consisting of one of several individual characters,
        //  and the text being matched ending with a supplementary character. find() would read past the
        //  end of the input text when searching for potential match starting points.

        // To see the problem, the text must exactly fill an allocated buffer, so that valgrind will
        // detect the bad read.

        TestCase11049("A|B|C", "a string \\ud800\\udc00", false);
        TestCase11049("A|B|C", "string matches at end C", true);

        // Test again with a pattern starting with a single character,
        // which takes a different code path than starting with an OR expression,
        // but with similar logic.
        TestCase11049("C", "a string \\ud800\\udc00", false);
        TestCase11049("C", "string matches at end C", true);
    }

    // Run a single test case from TestBug11049(). Internal function.
    void TestCase11049(final String patternStr, final String dataStr, boolean expectMatch) {
        String patternString = Utility.unescape(patternStr);
        RegexPattern compiledPat = RegexPattern.compile(patternString, Collections.emptySet());

        String dataString = Utility.unescape(dataStr);
        char[] exactBuffer = new char[dataString.length()];
        exactBuffer = dataString.toCharArray();
        String ut = new String(exactBuffer, 0, dataString.length());

        RegexMatcher matcher = compiledPat.matcher();
        matcher.reset(ut);
        boolean result = matcher.find();
        Assert.assertEquals(String.format("expected %s, got %s. Pattern = \"%s\", text = \"%s\"",
                expectMatch, result, patternStr, dataStr), expectMatch, result);

        // Rerun test with UTF-8 input text. Won't see buffer overreads, but could see
        //   off-by-one on find() with match at the last code point.
        //   Size of the original char[]  data (invariant charset) will be <= than the equivalent UTF-8
        //   because Utility.unescape(string) will only shrink it.
        byte[] utf8Buffer = dataString.getBytes(StandardCharsets.UTF_8);
        ut = utext_openUTF8(utf8Buffer, -1);
        matcher.reset(ut);
        result = matcher.find();
        Assert.assertEquals(String.format("(UTF-8 check): expected %s, got %s. Pattern = \"%s\", text = \"%s\"",
                        expectMatch, result, patternStr, dataStr), expectMatch, result);
    }


    // TODO: Mark this test as large
    // @Test
    void TestBug11371() {
        {
            StringBuffer patternString = new StringBuffer();
            for (int i = 0; i < 8000000; i++) {
                patternString.append("()");
            }
            REGEX_ASSERT_FAIL(() -> RegexPattern.compile(patternString.toString(), Collections.emptySet()), U_REGEX_PATTERN_TOO_BIG);
        }
        {
            StringBuffer patternString = new StringBuffer("(");
            for (int i = 0; i < 20000000; i++) {
                patternString.append("A++");
            }
            patternString.append("){0}B++");
            REGEX_ASSERT_FAIL(() -> RegexPattern.compile(patternString.toString(), Collections.emptySet()), U_REGEX_PATTERN_TOO_BIG);
        }
        {
        // Pattern with too much string data, such that string indexes overflow operand data field size
        // in compiled instruction.

        StringBuffer patternString = new StringBuffer();
        while (patternString.length() < 0x00ffffff) {
            patternString.append("stuff and things dont you know, these are a few of my favorite strings\n");
        }
        patternString.append("X? trailing string");
        REGEX_ASSERT_FAIL(() -> RegexPattern.compile(patternString.toString(), Collections.emptySet()), U_REGEX_PATTERN_TOO_BIG);
        }
    }

    @Test
    public void TestBug11480() {
        // get capture group of a group that does not participate in the match.
        //        (Returns a zero length string, with nul termination,
        //         indistinguishable from a group with a zero length match.)


        // String API, length of match is 0 for non-participating matches.
        RegexMatcher matcher = new RegexMatcher("(A)|(B)", Collections.emptySet());
        matcher.reset("A");
        REGEX_ASSERT(matcher.lookingAt(0));

        // String API, Capture group 1 matches "A", position 0, length 1.
        String group;
        group = matcher.group(1);
        Assert.assertEquals(1, group.length());

        // Capture group 2, the (B), does not participate in the match.
        group = matcher.group(2);
        Assert.assertEquals(0, group.length());
        REGEX_ASSERT(matcher.start(2) == -1);
    }

    @Test
    public void TestBug12884() {
        // setTimeLimit() was not effective for empty sub-patterns with large {minimum counts}
        StringBuffer text = new StringBuffer("hello");

        RegexMatcher m = new RegexMatcher("(((((((){120}){11}){11}){11}){80}){11}){4}", text.toString(), Collections.emptySet());
        m.setTimeLimit(5);
        REGEX_ASSERT_FAIL(() -> m.find(), U_REGEX_TIME_OUT);

        // Non-greedy loops. They take a different code path during matching.

        RegexMatcher ngM = new RegexMatcher("(((((((){120}?){11}?){11}?){11}?){80}?){11}?){4}?", text.toString(), Collections.emptySet());
        ngM.setTimeLimit(5);
        REGEX_ASSERT_FAIL(() -> ngM.find(), U_REGEX_TIME_OUT);

        // String, wrapping non-UTF-16 text, also takes a different execution path.
        String text8 = "¿Qué es Unicode?  Unicode proporciona un número único para cada" +
        "carácter, sin importar la plataforma, sin importar el programa," +
        "sin importar el idioma.";

        String ut = extractAndReadAsUtf8(text8);
        m.reset(ut);
        REGEX_ASSERT_FAIL(() -> m.find(), U_REGEX_TIME_OUT);


        ngM.reset(ut);
        REGEX_ASSERT_FAIL(() -> ngM.find(), U_REGEX_TIME_OUT);
    }

// Bug 13631. A find() of a pattern with a zero length look-behind Assert
//            can cause a read past the end of the input text.
//            The failure is seen when running this test with Clang's Address Sanitizer.

    @Test
    public void TestBug13631() {
    final String[] pats = { "(?<!^)",
                "(?<=^)"
        };
        for (String pat : pats) {
            RegexMatcher matcher = new RegexMatcher(pat, Collections.emptySet());
            String ut = new String(new char[] {'a'}, 0, 1);
            matcher.reset(ut);
            while (matcher.find()) {
            }
        }
    }

    @Test
    public void TestBug20863() {
        // Test that patterns with a large number of named capture groups work correctly.
        //
        // The ticket was not for a bug per se, but to reduce memory usage by using lazy
        // construction of the map from capture names to numbers, and decreasing the
        // default size of the map.

        int GROUP_COUNT = 2000;
        List<String> groupNames = new ArrayList<>();
        for (int i = 0; i < GROUP_COUNT; ++i) {
            String name = "name" + i;
            groupNames.add(name);
        }

        StringBuilder patternString = new StringBuilder();
        for (String name : groupNames) {
            patternString.append("(?<");
            patternString.append(name);
            patternString.append(">.)");
        }

        RegexPattern pattern = RegexPattern.compile(patternString.toString());

        for (int i = 0; i < GROUP_COUNT; ++i) {
            int group = pattern.groupNumberFromName(groupNames.get(i));
            Assert.assertEquals(i + 1, group);
            // Note: group 0 is the overall match; group 1 is the first separate capture group.
        }

        // Verify that assignment of patterns with various combinations of named capture work.
        // Lazy creation of the internal named capture map changed the implementation logic here.
        {
            RegexPattern pat1 = RegexPattern.compile("abc");
            RegexPattern pat2 = RegexPattern.compile("a(?<name>b)c");

            Assert.assertNotEquals(pat1, pat2);
            Assert.assertEquals(1, pat2.groupNumberFromName("name"));
            Assert.assertEquals(1, pat2.groupNumberFromName("name"));
        }

        {
            RegexPattern pat1 = RegexPattern.compile("abc");
            RegexPattern pat2 = RegexPattern.compile("a(?<name>b)c");
            Assert.assertNotEquals(pat1, pat2);
            REGEX_ASSERT_FAIL(() -> pat1.groupNumberFromName("name"), U_REGEX_INVALID_CAPTURE_GROUP_NAME);
        }

        {
            RegexPattern pat1 = RegexPattern.compile("a(?<name1>b)c");
            RegexPattern pat2 = RegexPattern.compile("a(?<name2>b)c");
            Assert.assertNotEquals(pat1, pat2);
            Assert.assertEquals(1, pat1.groupNumberFromName("name1"));
            REGEX_ASSERT_FAIL(() -> pat1.groupNumberFromName("name2"), U_REGEX_INVALID_CAPTURE_GROUP_NAME);
        }
    }
}
