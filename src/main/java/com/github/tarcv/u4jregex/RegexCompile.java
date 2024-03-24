// New code and changes are © 2024 TarCV
// © 2016 and later: Unicode, Inc. and others.
// License & terms of use: http://www.unicode.org/copyright.html
//
//
//  Copyright (C) 2002-2016 International Business Machines Corporation and others.
//  All Rights Reserved.
//
//  This file contains the regular expression compiler, which is responsible
//  for processing a regular expression pattern into the compiled form that
//  is used by the match finding engine.
//
package com.github.tarcv.u4jregex;

import com.ibm.icu.impl.PatternProps;
import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.text.UnicodeSet;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.github.tarcv.u4jregex.RegexCompile.EParenClass.*;
import static com.github.tarcv.u4jregex.RegexCompile.SetOperations.*;
import static com.github.tarcv.u4jregex.RegexImp.RESTACKFRAME_HDRCOUNT;
import static com.github.tarcv.u4jregex.Regex_PatternParseAction.doOpenNonCaptureParen;
import static com.github.tarcv.u4jregex.Regexcst.RegexStateNames;
import static com.github.tarcv.u4jregex.Regexcst.gRuleParseStateTable;
import static com.github.tarcv.u4jregex.StartOfMatch.*;
import static com.github.tarcv.u4jregex.UChar.*;
import static com.github.tarcv.u4jregex.UInvChar.uprv_isInvariantUString;
import static com.github.tarcv.u4jregex.URX.*;
import static com.github.tarcv.u4jregex.URegexpFlag.*;
import static com.github.tarcv.u4jregex.UrxOps.*;
import static com.github.tarcv.u4jregex.UrxOps.URX_TYPE;
import static com.github.tarcv.u4jregex.UrxOps.URX_VAL;
import static com.github.tarcv.u4jregex.Util.*;
import static com.ibm.icu.lang.UProperty.*;
import static com.ibm.icu.text.UnicodeSet.CASE_INSENSITIVE;

// Beware, any error thrown from this class might leave it or related RegexPattern in an invalid state,
// thus only static methods should be made public. Such methods should also set RegexPattern#isValid appropriately.
class RegexCompile {
    /**
     * The size of the state stack for
     * pattern parsing.  Corresponds roughly
     * to the depth of parentheses nesting
     * that is allowed in the rules.
     */
    private static final int kStackSize = 100;
    private static final Logger LOGGER = Logger.getLogger(RegexCompile.class.getName());

    private static class RegexPatternChar implements Cloneable {
        int fChar;
        boolean fQuoted;

        @Override
        public RegexPatternChar clone() {
            try {
                RegexPatternChar clone = (RegexPatternChar) super.clone();
                clone.fChar = fChar;
                clone.fQuoted = fQuoted;
                return clone;
            } catch (CloneNotSupportedException e) {
                throw new AssertionError();
            }
        }
    }

    /**
     * Categories of parentheses in pattern.<br/>
     * The category is saved in the compile-time parentheses stack frame, and
     * determines the code to be generated when the matching close ) is encountered.
     */
    enum EParenClass {
        plain(-1),               // No special handling
        capturing(-2),
        atomic(-3),
        lookAhead(-4),
        negLookAhead(-5),
        flags(-6),
        lookBehind(-7),
        lookBehindN(-8);

        private final int index;

        EParenClass(final int index) {
            this.index = index;
        }

        public static EParenClass getByIndex(final int index) {
            //noinspection OptionalGetWithoutIsPresent
            return Arrays.stream(values())
                    .filter(e -> e.index == index)
                    .findAny()
                    .get();
        }
    }

    private final RegexPattern fRXPat;

    //
    //  Data associated with low level character scanning
    //

    /**
     * Index of current character being processed
     * in the rule input string.
     */
    private long fScanIndex;
    /**
     * Scan is in a \Q...\E quoted region
     */
    private boolean fQuoteMode;
    /**
     * Scan is between a '\' and the following char.
     */
    private boolean fInBackslashQuote;
    /**
     * When scan is just after '(?',  inhibit #... to
     * end of line comments, in favor of (?#...) comments.
     */
    private boolean fEOLComments;
    /**
     * Line number in input file.
     */
    private long fLineNum;
    /**
     * Char position within the line.
     */
    private long fCharNum;
    /**
     * Previous char, needed to count CR-LF
     * as a single line, not two.
     */
    private int fLastChar;
    /**
     * Saved char, if we've scanned ahead.
     */
    private int fPeekChar;


    /**
     * Current char for parse state machine
     * processing.
     */
    private RegexPatternChar fC = new RegexPatternChar();

    /**
     * State stack, holds state pushes
     * and pops as specified in the state
     * transition rules.
     */
    private final /* uint16 */ int[] fStack = new int[kStackSize];
    /**
     * @see #fStack
     */
    private int fStackPtr;

    /**
     * Data associated with the generation of the pcode for the match engine
     * Match Flags.  (Case Insensitive, etc.)<br/>
     * Always has high bit (31) set so that flag values
     * on the paren stack are distinguished from relocatable
     * pcode addresses.
     */
    private int fModeFlags;
    /**
     * New flags, while compiling (?i, holds state
     * until last flag is scanned.
     */
    private int fNewModeFlags;
    /**
     * true for (?ismx, false for (?-ismx
     */
    private boolean fSetModeFlag;

    /**
     * Literal chars or strings from the pattern are accumulated here.
     * Once completed, meaning that some non-literal pattern
     * construct is encountered, the appropriate opcodes
     * to match the literal will be generated, and this
     * string will be cleared.
     */
    private final StringBuffer fLiteralChars = new StringBuffer();

    /**
     * Length of the input pattern string.
     */
    private long fPatternLength;

    /**
     * parentheses stack.  Each frame consists of
     * the positions of compiled pattern operations
     * needing fixup, followed by negative value.  The
     * first entry in each frame is the position of the
     * spot reserved for use when a quantifier
     * needs to add a SAVE at the start of a (block)
     * The negative value (-1, -2,...) indicates
     * the kind of paren that opened the frame.  Some
     * need special handling on close.
     */
    private final MutableVector32 fParenStack;


    /**
     * The position in the compiled pattern
     * of the slot reserved for a state save
     * at the start of the most recently processed
     * parenthesized block. Updated when processing
     * a close to the location for the corresponding open.
     */
    private int fMatchOpenParen;

    /**
     * The position in the pattern of the first
     * location after the most recently processed
     * parenthesized block.
     */
    private int fMatchCloseParen;

    /**
     * {lower, upper} interval quantifier values.
     */
    private int fIntervalLow;
    /**
     * Placed here temporarily, when pattern is
     * initially scanned.  Each new interval
     * encountered overwrites these values.<br/>
     * -1 for the upper interval value means none
     * was specified (unlimited occurrences.)
     */
    private int fIntervalUpper;

    /**
     * Stack of UnicodeSets, used while evaluating
     * (at compile time) set expressions within
     * the pattern.
     */
    private final ArrayDeque<UnicodeSet> fSetStack;
    /**
     * Stack of pending set operators (&&, --, union)
     */
    private final ArrayDeque<SetOperations> fSetOpStack;

    /**
     * The last single code point added to a set.
     * needed when "-y" is scanned, and we need
     * to turn "x-y" into a range.
     */
    private int fLastSetLiteral;

    /**
     * Named Capture, the group name is built up
     * in this string while being scanned.
     */
    private StringBuilder fCaptureName;


    /**
     * Constant values to be pushed onto fSetOpStack while scanning & evaluating [set expressions]<br/>
     * The high 16 bits are the operator precedence, and the low 16 are a code for the operation itself.
     */
    enum SetOperations {
        @SuppressWarnings("PointlessBitwiseExpression")
        setStart(0 << 16 | 1),
        setEnd(1 << 16 | 2),
        setNegation(2 << 16 | 3),
        setCaseClose(2 << 16 | 9),
        /**
         * '--' set difference operator
         */
        setDifference2(3 << 16 | 4),
        /**
         * '&&' set intersection operator
         */
        setIntersection2(3 << 16 | 5),
        /**
         * implicit union of adjacent items
         */
        setUnion(4 << 16 | 6),
        /**
         * '-', single dash difference op, for compatibility with old UnicodeSet.
         */
        setDifference1(4 << 16 | 7),
        /**
         * '&', single amp intersection op, for compatibility with old UnicodeSet.
         */
        setIntersection1(4 << 16 | 8);

        private final int index;

        SetOperations(final int index) {
            this.index = index;
        }

        public static SetOperations getByIndex(final int index) {
            //noinspection OptionalGetWithoutIsPresent
            return Arrays.stream(values())
                    .filter(e -> e.index == index)
                    .findAny()
                    .get();
        }
    }


//
//  Constructor.
//

    private RegexCompile(final RegexPattern rxp) {
        fParenStack = new MutableVector32();
        fSetStack = new ArrayDeque<>();
        fSetOpStack = new ArrayDeque<>();
        fRXPat = rxp;
        fScanIndex = 0;
        fLastChar = -1;
        fPeekChar = -1;
        fLineNum = 1;
        fCharNum = 0;
        fQuoteMode = false;
        fInBackslashQuote = false;
        {
            long combinedFlags = 0;
            for (URegexpFlag flag : fRXPat.fFlags) {
                combinedFlags |= flag.flag;
            }
            fModeFlags = Math.toIntExact(combinedFlags) | 0x80000000;
        }
        fEOLComments = true;

        fMatchOpenParen = -1;
        fMatchCloseParen = -1;
        fCaptureName = null;
        fLastSetLiteral = U_SENTINEL;
    }

    private static final char chAmp = 0x26;      // '&'
    private static final char chDash = 0x2d;      // '-'


    private static void addCategory(final UnicodeSet set, final long value) {
        set.addAll(new UnicodeSet().applyIntPropertyValue(GENERAL_CATEGORY_MASK, Math.toIntExact(value)));
    }


    /**
     * Compile regex pattern.<br/>The state machine for rexexp pattern parsing is here.
     * The state tables are hand-written in the file regexcst.txt,
     * and converted to the form used here by a perl
     * script regexcst.pl
     * <p>
     * This method must not be called directly as it expects rxp to be partially initialized
     */
    static void compile(final RegexPattern rxp, final String pat) {
        rxp.isValid = false;
        new RegexCompile(rxp).compileInternal(pat);
        rxp.isValid = true;
    }

    private void compileInternal(
            final String pat                 // Source pat to be compiled.
    ) {
        fStackPtr = 0;
        fStack[fStackPtr] = 0;

        // Prepare the RegexPattern object to receive the compiled pattern.
        if (fRXPat.fPattern == null ||
                !fRXPat.fPattern.targetString.equals(pat) ||
                fRXPat.fPattern.chunkOffset != 0) {
            throw new IllegalStateException();
        }

        // Initialize the pattern scanning state machine
        fPatternLength = Util.utext_nativeLength(pat);
        /* uint16 */int state = 1;
        Util.ArrayPointer<RegexTableEl> tableEl;

        // UREGEX_LITERAL force entire pattern to be treated as a literal string.
        if ((fModeFlags & UREGEX_LITERAL.flag) != 0) {
            fQuoteMode = true;
        }

        nextChar(fC);                        // Fetch the first char from the pattern string.

        //
        // Main loop for the regex pattern parsing state machine.
        //   Runs once per state transition.
        //   Each time through optionally performs, depending on the state table,
        //      - an advance to the the next pattern char
        //      - an action to be performed.
        //      - pushing or popping a state to/from the local state return stack.
        //   file regexcst.txt is the source for the state table.  The logic behind
        //     recongizing the pattern syntax is there, not here.
        //
        for (; ; ) {
            assert state != 0;

            // Find the state table element that matches the input char from the pattern, or the
            //    class of the input character.  Start with the first table row for this
            //    state, then linearly scan forward until we find a row that matches the
            //    character.  The last row for each state always matches all characters, so
            //    the search will stop there, if not before.
            //
            tableEl = new Util.ArrayPointer<>(gRuleParseStateTable, (state));
            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.finest(String.format("char, line, col = ('%s', %d, %d)    state=%s ", Util.safeCodepointToStr(fC.fChar), fLineNum, fCharNum, RegexStateNames[state]));
            }

            for (; ; ) {    // loop through table rows belonging to this state, looking for one
                //   that matches the current input char.
                LOGGER.finest(() -> ".");
                if (tableEl.get().fCharClass < 127 && !fC.fQuoted && tableEl.get().fCharClass == fC.fChar) {
                    // Table row specified an individual character, not a set, and
                    //   the input character is not quoted, and
                    //   the input character matched it.
                    break;
                }
                if (tableEl.get().fCharClass == 255) {
                    // Table row specified default, match anything character class.
                    break;
                }
                if (tableEl.get().fCharClass == 254 && fC.fQuoted) {
                    // Table row specified "quoted" and the char was quoted.
                    break;
                }
                if (tableEl.get().fCharClass == 253 && fC.fChar == (int) -1) {
                    // Table row specified eof and we hit eof on the input.
                    break;
                }

                if (tableEl.get().fCharClass >= 128 && tableEl.get().fCharClass < 240 &&   // Table specs a char class &&
                        fC.fQuoted == false &&                                       //   char is not escaped &&
                        fC.fChar != (int) -1) {                                   //   char is not EOF
                    assert tableEl.get().fCharClass <= 137;
                    if (RegexStaticSets.INSTANCE.fRuleSets[tableEl.get().fCharClass - 128].contains(fC.fChar)) {
                        // Table row specified a character class, or set of characters,
                        //   and the current char matches it.
                        break;
                    }
                }

                // No match on this row, advance to the next  row for this state,
                tableEl.next();
            }
            LOGGER.finest(() -> String.format(("\n")));

            //
            // We've found the row of the state table that matches the current input
            //   character from the rules string.
            // Perform any action specified  by this row in the state table.
            if (doParseActions(tableEl.get().fAction) == false) {
                // Break out of the state machine loop if the
                //   the action signalled some kind of error, or
                //   the action was to exit, occurs on normal end-of-rules-input.
                break;
            }

            if (tableEl.get().fPushState != 0) {
                fStackPtr++;
                if (fStackPtr >= kStackSize) {
                    LOGGER.finest(() -> String.format(("RegexCompile::parse() - state stack overflow.\n")));
                    throw error(UErrorCode.U_REGEX_INTERNAL_ERROR, null);
//                    fStackPtr--;
                }
                fStack[fStackPtr] = tableEl.get().fPushState;
            }

            //
            //  NextChar.  This is where characters are actually fetched from the pattern.
            //             Happens under control of the 'n' tag in the state table.
            //
            if (tableEl.get().fNextChar) {
                nextChar(fC);
            }

            // Get the next state from the table entry, or from the
            //   state stack if the next state was specified as "pop".
            if (tableEl.get().fNextState != 255) {
                state = tableEl.get().fNextState;
            } else {
                state = fStack[fStackPtr];
                fStackPtr--;
                if (fStackPtr < 0) {
                    // state stack underflow
                    // This will occur if the user pattern has mis-matched parentheses,
                    //   with extra close parens.
                    //
                    fStackPtr++;
                    throw error(UErrorCode.U_REGEX_MISMATCHED_PAREN, null);
                }
            }

        }

        //
        // The pattern has now been read and processed, and the compiled code generated.
        //

        //
        // The pattern's fFrameSize so far has accumulated the requirements for
        //   storage for capture parentheses, counters, etc. that are encountered
        //   in the pattern.  Add space for the two variables that are always
        //   present in the saved state:  the input string position (long) and
        //   the position in the compiled pattern.
        //
        allocateStackData(RESTACKFRAME_HDRCOUNT);

        //
        // Optimization pass 1: NOPs, back-references, and case-folding
        //
        stripNOPs();

        //
        // Get bounds for the minimum and maximum length of a string that this
        //   pattern can match.  Used to avoid looking for matches in strings that
        //   are too short.
        //
        fRXPat.fMinMatchLen = minMatchLength(3, fRXPat.fCompiledPat.size() - 1);

        //
        // Optimization pass 2: match start type
        //
        matchStartType();
    }


    /**
     * Do some action during regex pattern parsing.<br/>
     * Called by the parse state machine.<br/>
     * Generation of the match engine PCode happens here, or
     * in functions called from the parse actions defined here.
     */
    private boolean doParseActions(final Regex_PatternParseAction action) {
        boolean returnVal = true;

        switch (action) {

            case doPatStart:
                // Start of pattern compiles to:
                //0   SAVE   2        Fall back to position of FAIL
                //1   jmp    3
                //2   FAIL            Stop if we ever reach here.
                //3   NOP             Dummy, so start of pattern looks the same as
                //                    the start of an ( grouping.
                //4   NOP             Resreved, will be replaced by a save if there are
                //                    OR | operators at the top level
                appendOp(URX_STATE_SAVE, 2);
                appendOp(URX_JMP, 3);
                appendOp(URX_FAIL, 0);

                // Standard open nonCapture paren action emits the two NOPs and
                //   sets up the paren stack frame.
                doParseActions(doOpenNonCaptureParen);
                break;

            case doPatFinish:
                // We've scanned to the end of the pattern
                //  The end of pattern compiles to:
                //        URX_END
                //    which will stop the runtime match engine.
                //  Encountering end of pattern also behaves like a close paren,
                //   and forces fixups of the State Save at the beginning of the compiled pattern
                //   and of any OR operations at the top level.
                //
                handleCloseParen();
                if (!fParenStack.isEmpty()) {
                    // Missing close paren in pattern.
                    throw error(UErrorCode.U_REGEX_MISMATCHED_PAREN, null);
                }

                // add the END operation to the compiled pattern.
                appendOp(URX_END, 0);

                // Terminate the pattern compilation state machine.
                returnVal = false;
                break;


            case doOrOperator:
                // Scanning a '|', as in (A|B)
            {
                // Generate code for any pending literals preceding the '|'
                fixLiterals(false);

                // Insert a SAVE operation at the start of the pattern section preceding
                //   this OR at this level.  This SAVE will branch the match forward
                //   to the right hand side of the OR in the event that the left hand
                //   side fails to match and backtracks.  Locate the position for the
                //   save from the location on the top of the parentheses stack.
                int savePosition = fParenStack.popi();
                int op = Math.toIntExact(fRXPat.fCompiledPat.elementAti(savePosition));
                assert URX_TYPE(op) == URX_NOP;  // original contents of reserved location
                op = buildOp(URX_STATE_SAVE, fRXPat.fCompiledPat.size() + 1);
                fRXPat.fCompiledPat.setElementAt(op, savePosition);

                // Append an JMP operation into the compiled pattern.  The operand for
                //  the JMP will eventually be the location following the ')' for the
                //  group.  This will be patched in later, when the ')' is encountered.
                appendOp(URX_JMP, 0);

                // Push the position of the newly added JMP op onto the parentheses stack.
                // This registers if for fixup when this block's close paren is encountered.
                fParenStack.push(fRXPat.fCompiledPat.size() - 1);

                // Append a NOP to the compiled pattern.  This is the slot reserved
                //   for a SAVE in the event that there is yet another '|' following
                //   this one.
                appendOp(URX_NOP, 0);
                fParenStack.push(fRXPat.fCompiledPat.size() - 1);
            }
            break;


            case doBeginNamedCapture:
                // Scanning (?<letter.
                //   The first letter of the name will come through again under doConinueNamedCapture.
                fCaptureName = new StringBuilder();
                break;

            case doContinueNamedCapture:
                fCaptureName.appendCodePoint(fC.fChar);
                break;

            case doBadNamedCapture:
                throw error(UErrorCode.U_REGEX_INVALID_CAPTURE_GROUP_NAME, null);

            case doOpenCaptureParen:
                // Open Capturing Paren, possibly named.
                //   Compile to a
                //      - NOP, which later may be replaced by a save-state if the
                //         parenthesized group gets a * quantifier, followed by
                //      - START_CAPTURE  n    where n is stack frame offset to the capture group variables.
                //      - NOP, which may later be replaced by a save-state if there
                //             is an '|' alternation within the parens.
                //
                //    Each capture group gets three slots in the save stack frame:
                //         0: Capture Group start position (in input string being matched.)
                //         1: Capture Group end position.
                //         2: Start of Match-in-progress.
                //    The first two locations are for a completed capture group, and are
                //     referred to by back references and the like.
                //    The third location stores the capture start position when an START_CAPTURE is
                //      encountered.  This will be promoted to a completed capture when (and if) the corresponding
                //      END_CAPTURE is encountered.
            {
                fixLiterals();
                appendOp(URX_NOP, 0);
                int varsLoc = allocateStackData(3);    // Reserve three slots in match stack frame.
                appendOp(URX_START_CAPTURE, varsLoc);
                appendOp(URX_NOP, 0);

                // On the Parentheses stack, start a new frame and add the positions
                //   of the two NOPs.  Depending on what follows in the pattern, the
                //   NOPs may be changed to SAVE_STATE or JMP ops, with a target
                //   address of the end of the parenthesized group.
                // Match mode state
                fParenStack.push(fModeFlags);
                // Frame type.
                fParenStack.push(capturing.index);
                // The first  NOP location
                fParenStack.push(fRXPat.fCompiledPat.size() - 3);
                // The second NOP loc
                fParenStack.push(fRXPat.fCompiledPat.size() - 1);

                // Save the mapping from group number to stack frame variable position.
                fRXPat.fGroupMap.addElement(varsLoc);

                // If this is a named capture group, add the name.group number mapping.
                if (fCaptureName != null) {
                    fRXPat.initNamedCaptureMap();
                    int groupNumber = fRXPat.fGroupMap.size();
                    Long previousMapping = fRXPat.fNamedCaptureMap.put(fCaptureName.toString(), (long) groupNumber);
                    fCaptureName = null;    // hash table takes ownership of the name (key) string.
                    if (previousMapping != null && previousMapping > 0) {
                        throw error(UErrorCode.U_REGEX_INVALID_CAPTURE_GROUP_NAME, null);
                    }
                }
            }
            break;

            case doOpenNonCaptureParen:
                // Open non-caputuring (grouping only) Paren.
                //   Compile to a
                //      - NOP, which later may be replaced by a save-state if the
                //         parenthesized group gets a * quantifier, followed by
                //      - NOP, which may later be replaced by a save-state if there
                //             is an '|' alternation within the parens.
            {
                fixLiterals();
                appendOp(URX_NOP, 0);
                appendOp(URX_NOP, 0);

                // On the Parentheses stack, start a new frame and add the positions
                //   of the two NOPs.
                // Match mode state
                fParenStack.push(fModeFlags);
                // Begin a new frame.
                fParenStack.push(plain.index);
                // The first  NOP location
                fParenStack.push(fRXPat.fCompiledPat.size() - 2);
                // The second NOP loc
                fParenStack.push(fRXPat.fCompiledPat.size() - 1);
            }
            break;


            case doOpenAtomicParen:
                // Open Atomic Paren.  (?>
                //   Compile to a
                //      - NOP, which later may be replaced if the parenthesized group
                //         has a quantifier, followed by
                //      - STO_SP  save state stack position, so it can be restored at the ")"
                //      - NOP, which may later be replaced by a save-state if there
                //             is an '|' alternation within the parens.
            {
                fixLiterals();
                appendOp(URX_NOP, 0);
                int varLoc = allocateData(1);    // Reserve a data location for saving the state stack ptr.
                appendOp(URX_STO_SP, varLoc);
                appendOp(URX_NOP, 0);

                // On the Parentheses stack, start a new frame and add the positions
                //   of the two NOPs.  Depending on what follows in the pattern, the
                //   NOPs may be changed to SAVE_STATE or JMP ops, with a target
                //   address of the end of the parenthesized group.
                // Match mode state
                fParenStack.push(fModeFlags);
                // Frame type.
                fParenStack.push(atomic.index);
                // The first NOP
                fParenStack.push(fRXPat.fCompiledPat.size() - 3);
                // The second NOP
                fParenStack.push(fRXPat.fCompiledPat.size() - 1);
            }
            break;


            case doOpenLookAhead:
                // Positive Look-ahead   (?=  stuff  )
                //
                //   Note:   Addition of transparent input regions, with the need to
                //           restore the original regions when failing out of a lookahead
                //           block, complicated this sequence.  Some combined opcodes
                //           might make sense - or might not, lookahead aren't that common.
                //
                //      Caution:  min match length optimization knows about this
                //               sequence; don't change without making updates there too.
                //
                // Compiles to
                //    1    LA_START     dataLoc     Saves SP, Input Pos, Active input region.
                //    2.   STATE_SAVE   4            on failure of lookahead, goto 4
                //    3    JMP          6           continue ...
                //
                //    4.   LA_END                   Look Ahead failed.  Restore regions.
                //    5.   BACKTRACK                and back track again.
                //
                //    6.   NOP              reserved for use by quantifiers on the block.
                //                          Look-ahead can't have quantifiers, but paren stack
                //                             compile time conventions require the slot anyhow.
                //    7.   NOP              may be replaced if there is are '|' ops in the block.
                //    8.     code for parenthesized stuff.
                //    9.   LA_END
                //
                //  Four data slots are reserved, for saving state on entry to the look-around
                //    0:   stack pointer on entry.
                //    1:   input position on entry.
                //    2:   fActiveStart, the active bounds start on entry.
                //    3:   fActiveLimit, the active bounds limit on entry.
            {
                fixLiterals();
                int dataLoc = allocateData(4);
                appendOp(URX_LA_START, dataLoc);
                appendOp(URX_STATE_SAVE, fRXPat.fCompiledPat.size() + 2);
                appendOp(URX_JMP, fRXPat.fCompiledPat.size() + 3);
                appendOp(URX_LA_END, dataLoc);
                appendOp(URX_BACKTRACK, 0);
                appendOp(URX_NOP, 0);
                appendOp(URX_NOP, 0);

                // On the Parentheses stack, start a new frame and add the positions
                //   of the NOPs.
                // Match mode state
                fParenStack.push(fModeFlags);
                // Frame type.
                fParenStack.push(lookAhead.index);
                // The first  NOP location
                fParenStack.push(fRXPat.fCompiledPat.size() - 2);
                // The second NOP location
                fParenStack.push(fRXPat.fCompiledPat.size() - 1);
            }
            break;

            case doOpenLookAheadNeg:
                // Negated Lookahead.   (?! stuff )
                // Compiles to
                //    1.    LA_START    dataloc
                //    2.    SAVE_STATE  7         // Fail within look-ahead block restores to this state,
                //                                //   which continues with the match.
                //    3.    NOP                   // Std. Open Paren sequence, for possible '|'
                //    4.       code for parenthesized stuff.
                //    5.    LA_END                // Cut back stack, remove saved state from step 2.
                //    6.    BACKTRACK             // code in block succeeded, so neg. lookahead fails.
                //    7.    END_LA                // Restore match region, in case look-ahead was using
                //                                        an alternate (transparent) region.
                //  Four data slots are reserved, for saving state on entry to the look-around
                //    0:   stack pointer on entry.
                //    1:   input position on entry.
                //    2:   fActiveStart, the active bounds start on entry.
                //    3:   fActiveLimit, the active bounds limit on entry.
            {
                fixLiterals();
                int dataLoc = allocateData(4);
                appendOp(URX_LA_START, dataLoc);
                appendOp(URX_STATE_SAVE, 0);    // dest address will be patched later.
                appendOp(URX_NOP, 0);

                // On the Parentheses stack, start a new frame and add the positions
                //   of the StateSave and NOP.
                // Match mode state
                fParenStack.push(fModeFlags);
                // Frame type
                fParenStack.push(negLookAhead.index);
                // The STATE_SAVE location
                fParenStack.push(fRXPat.fCompiledPat.size() - 2);
                // The second NOP location
                fParenStack.push(fRXPat.fCompiledPat.size() - 1);

                // Instructions #5 - #7 will be added when the ')' is encountered.
            }
            break;

            case doOpenLookBehind: {
                //   Compile a (?<= look-behind open paren.
                //
                //          Compiles to
                //              0       URX_LB_START     dataLoc
                //              1       URX_LB_CONT      dataLoc
                //              2                        MinMatchLen
                //              3                        MaxMatchLen
                //              4       URX_NOP          Standard '(' boilerplate.
                //              5       URX_NOP          Reserved slot for use with '|' ops within (block).
                //              6         <code for LookBehind expression>
                //              7       URX_LB_END       dataLoc    # Check match len, restore input  len
                //              8       URX_LA_END       dataLoc    # Restore stack, input pos
                //
                //          Allocate a block of matcher data, to contain (when running a match)
                //              0:    Stack ptr on entry
                //              1:    Input Index on entry
                //              2:    fActiveStart, the active bounds start on entry.
                //              3:    fActiveLimit, the active bounds limit on entry.
                //              4:    Start index of match current match attempt.
                //          The first four items must match the layout of data for LA_START / LA_END

                // Generate match code for any pending literals.
                fixLiterals();

                // Allocate data space
                int dataLoc = allocateData(5);

                // Emit URX_LB_START
                appendOp(URX_LB_START, dataLoc);

                // Emit URX_LB_CONT
                appendOp(URX_LB_CONT, dataLoc);
                appendOp(URX_RESERVED_OP, 0);    // MinMatchLength.  To be filled later.
                appendOp(URX_RESERVED_OP, 0);    // MaxMatchLength.  To be filled later.

                // Emit the NOPs
                appendOp(URX_NOP, 0);
                appendOp(URX_NOP, 0);

                // On the Parentheses stack, start a new frame and add the positions
                //   of the URX_LB_CONT and the NOP.
                // Match mode state
                fParenStack.push(fModeFlags);
                // Frame type
                fParenStack.push(lookBehind.index);
                // The first NOP location
                fParenStack.push(fRXPat.fCompiledPat.size() - 2);
                // The 2nd   NOP location
                fParenStack.push(fRXPat.fCompiledPat.size() - 1);

                // The final two instructions will be added when the ')' is encountered.
            }

            break;

            case doOpenLookBehindNeg: {
                //   Compile a (?<! negated look-behind open paren.
                //
                //          Compiles to
                //              0       URX_LB_START     dataLoc    # Save entry stack, input len
                //              1       URX_LBN_CONT     dataLoc    # Iterate possible match positions
                //              2                        MinMatchLen
                //              3                        MaxMatchLen
                //              4                        continueLoc (9)
                //              5       URX_NOP          Standard '(' boilerplate.
                //              6       URX_NOP          Reserved slot for use with '|' ops within (block).
                //              7         <code for LookBehind expression>
                //              8       URX_LBN_END      dataLoc    # Check match len, cause a FAIL
                //              9       ...
                //
                //          Allocate a block of matcher data, to contain (when running a match)
                //              0:    Stack ptr on entry
                //              1:    Input Index on entry
                //              2:    fActiveStart, the active bounds start on entry.
                //              3:    fActiveLimit, the active bounds limit on entry.
                //              4:    Start index of match current match attempt.
                //          The first four items must match the layout of data for LA_START / LA_END

                // Generate match code for any pending literals.
                fixLiterals();

                // Allocate data space
                int dataLoc = allocateData(5);

                // Emit URX_LB_START
                appendOp(URX_LB_START, dataLoc);

                // Emit URX_LBN_CONT
                appendOp(URX_LBN_CONT, dataLoc);
                appendOp(URX_RESERVED_OP, 0);    // MinMatchLength.  To be filled later.
                appendOp(URX_RESERVED_OP, 0);    // MaxMatchLength.  To be filled later.
                appendOp(URX_RESERVED_OP, 0);    // Continue Loc.    To be filled later.

                // Emit the NOPs
                appendOp(URX_NOP, 0);
                appendOp(URX_NOP, 0);

                // On the Parentheses stack, start a new frame and add the positions
                //   of the URX_LB_CONT and the NOP.
                // Match mode state
                fParenStack.push(fModeFlags);
                // Frame type
                fParenStack.push(lookBehindN.index);
                // The first NOP location
                fParenStack.push(fRXPat.fCompiledPat.size() - 2);
                // The 2nd   NOP location
                fParenStack.push(fRXPat.fCompiledPat.size() - 1);

                // The final two instructions will be added when the ')' is encountered.
            }
            break;

            case doConditionalExpr:
                // Conditionals such as (?(1)a:b)
            case doPerlInline:
                // Perl inline-conditionals.  (?{perl code}a|b) We're not perl, no way to do them.
                throw error(UErrorCode.U_REGEX_UNIMPLEMENTED, null);


            case doCloseParen:
                handleCloseParen();
                if (fParenStack.size() <= 0) {
                    //  Extra close paren, or missing open paren.
                    throw error(UErrorCode.U_REGEX_MISMATCHED_PAREN, null);
                }
                break;

            case doNOP:
                break;


            case doBadOpenParenType:
            case doRuleError:
                throw error(UErrorCode.U_REGEX_RULE_SYNTAX, null);


            case doMismatchedParenErr:
                throw error(UErrorCode.U_REGEX_MISMATCHED_PAREN, null);

            case doPlus:
                //  Normal '+'  compiles to
                //     1.   stuff to be repeated  (already built)
                //     2.   jmp-sav 1
                //     3.   ...
                //
                //  Or, if the item to be repeated can match a zero length string,
                //     1.   STO_INP_LOC  data-loc
                //     2.      body of stuff to be repeated
                //     3.   JMP_SAV_X    2
                //     4.   ...

                //
                //  Or, if the item to be repeated is simple
                //     1.   Item to be repeated.
                //     2.   LOOP_SR_I    set number  (assuming repeated item is a set ref)
                //     3.   LOOP_C       stack location
            {
                int topLoc = blockTopLoc(false);        // location of item #1
                int frameLoc;

                // Check for simple constructs, which may get special optimized code.
                if (topLoc == fRXPat.fCompiledPat.size() - 1) {
                    int repeatedOp = Math.toIntExact(fRXPat.fCompiledPat.elementAti(topLoc));

                    if (URX_TYPE(repeatedOp) == URX_SETREF) {
                        // Emit optimized code for [char set]+
                        appendOp(URX_LOOP_SR_I, URX_VAL(repeatedOp));
                        frameLoc = allocateStackData(1);
                        appendOp(URX_LOOP_C, frameLoc);
                        break;
                    }

                    if (URX_TYPE(repeatedOp) == URX_DOTANY ||
                            URX_TYPE(repeatedOp) == URX_DOTANY_ALL ||
                            URX_TYPE(repeatedOp) == URX_DOTANY_UNIX) {
                        // Emit Optimized code for .+ operations.
                        int loopOpI = buildOp(URX_LOOP_DOT_I, 0);
                        if (URX_TYPE(repeatedOp) == URX_DOTANY_ALL) {
                            // URX_LOOP_DOT_I operand is a flag indicating ". matches any" mode.
                            loopOpI |= 1;
                        }
                        if ((fModeFlags & UREGEX_UNIX_LINES.flag) != 0) {
                            loopOpI |= 2;
                        }
                        appendOp(loopOpI);
                        frameLoc = allocateStackData(1);
                        appendOp(URX_LOOP_C, frameLoc);
                        break;
                    }

                }

                // General case.

                // Check for minimum match length of zero, which requires
                //    extra loop-breaking code.
                if (minMatchLength(topLoc, fRXPat.fCompiledPat.size() - 1) == 0) {
                    // Zero length match is possible.
                    // Emit the code sequence that can handle it.
                    insertOp(topLoc);
                    frameLoc = allocateStackData(1);

                    int op = buildOp(URX_STO_INP_LOC, frameLoc);
                    fRXPat.fCompiledPat.setElementAt(op, topLoc);

                    appendOp(URX_JMP_SAV_X, topLoc + 1);
                } else {
                    // Simpler code when the repeated body must match something non-empty
                    appendOp(URX_JMP_SAV, topLoc);
                }
            }
            break;

            case doNGPlus:
                //  Non-greedy '+?'  compiles to
                //     1.   stuff to be repeated  (already built)
                //     2.   state-save  1
                //     3.   ...
            {
                int topLoc = blockTopLoc(false);
                appendOp(URX_STATE_SAVE, topLoc);
            }
            break;


            case doOpt:
                // Normal (greedy) ? quantifier.
                //  Compiles to
                //     1. state save 3
                //     2.    body of optional block
                //     3. ...
                // Insert the state save into the compiled pattern, and we're done.
            {
                int saveStateLoc = blockTopLoc(true);
                int saveStateOp = buildOp(URX_STATE_SAVE, fRXPat.fCompiledPat.size());
                fRXPat.fCompiledPat.setElementAt(saveStateOp, saveStateLoc);
            }
            break;

            case doNGOpt:
                // Non-greedy ?? quantifier
                //   compiles to
                //    1.  jmp   4
                //    2.     body of optional block
                //    3   jmp   5
                //    4.  state save 2
                //    5    ...
                //  This code is less than ideal, with two jmps instead of one, because we can only
                //  insert one instruction at the top of the block being iterated.
            {
                int jmp1_loc = blockTopLoc(true);
                int jmp2_loc = fRXPat.fCompiledPat.size();

                int jmp1_op = buildOp(URX_JMP, jmp2_loc + 1);
                fRXPat.fCompiledPat.setElementAt(jmp1_op, jmp1_loc);

                appendOp(URX_JMP, jmp2_loc + 2);

                appendOp(URX_STATE_SAVE, jmp1_loc + 1);
            }
            break;


            case doStar:
                // Normal (greedy) * quantifier.
                // Compiles to
                //       1.   STATE_SAVE   4
                //       2.      body of stuff being iterated over
                //       3.   JMP_SAV      2
                //       4.   ...
                //
                // Or, if the body is a simple [Set],
                //       1.   LOOP_SR_I    set number
                //       2.   LOOP_C       stack location
                //       ...
                //
                // Or if this is a .*
                //       1.   LOOP_DOT_I    (. matches all mode flag)
                //       2.   LOOP_C        stack location
                //
                // Or, if the body can match a zero-length string, to inhibit infinite loops,
                //       1.   STATE_SAVE   5
                //       2.   STO_INP_LOC  data-loc
                //       3.      body of stuff
                //       4.   JMP_SAV_X    2
                //       5.   ...
            {
                // location of item #1, the STATE_SAVE
                int topLoc = blockTopLoc(false);
                int dataLoc = -1;

                // Check for simple *, where the construct being repeated
                //   compiled to single opcode, and might be optimizable.
                if (topLoc == fRXPat.fCompiledPat.size() - 1) {
                    int repeatedOp = Math.toIntExact(fRXPat.fCompiledPat.elementAti(topLoc));

                    if (URX_TYPE(repeatedOp) == URX_SETREF) {
                        // Emit optimized code for a [char set]*
                        int loopOpI = buildOp(URX_LOOP_SR_I, URX_VAL(repeatedOp));
                        fRXPat.fCompiledPat.setElementAt(loopOpI, topLoc);
                        dataLoc = allocateStackData(1);
                        appendOp(URX_LOOP_C, dataLoc);
                        break;
                    }

                    if (URX_TYPE(repeatedOp) == URX_DOTANY ||
                            URX_TYPE(repeatedOp) == URX_DOTANY_ALL ||
                            URX_TYPE(repeatedOp) == URX_DOTANY_UNIX) {
                        // Emit Optimized code for .* operations.
                        int loopOpI = buildOp(URX_LOOP_DOT_I, 0);
                        if (URX_TYPE(repeatedOp) == URX_DOTANY_ALL) {
                            // URX_LOOP_DOT_I operand is a flag indicating . matches any mode.
                            loopOpI |= 1;
                        }
                        if ((fModeFlags & UREGEX_UNIX_LINES.flag) != 0) {
                            loopOpI |= 2;
                        }
                        fRXPat.fCompiledPat.setElementAt(loopOpI, topLoc);
                        dataLoc = allocateStackData(1);
                        appendOp(URX_LOOP_C, dataLoc);
                        break;
                    }
                }

                // Emit general case code for this *
                // The optimizations did not apply.

                int saveStateLoc = blockTopLoc(true);
                int jmpOp = buildOp(URX_JMP_SAV, saveStateLoc + 1);

                // Check for minimum match length of zero, which requires
                //    extra loop-breaking code.
                if (minMatchLength(saveStateLoc, fRXPat.fCompiledPat.size() - 1) == 0) {
                    insertOp(saveStateLoc);
                    dataLoc = allocateStackData(1);

                    int op = buildOp(URX_STO_INP_LOC, dataLoc);
                    fRXPat.fCompiledPat.setElementAt(op, saveStateLoc + 1);
                    jmpOp = buildOp(URX_JMP_SAV_X, saveStateLoc + 2);
                }

                // Locate the position in the compiled pattern where the match will continue
                //   after completing the *.   (4 or 5 in the comment above)
                int continueLoc = fRXPat.fCompiledPat.size() + 1;

                // Put together the save state op and store it into the compiled code.
                int saveStateOp = buildOp(URX_STATE_SAVE, continueLoc);
                fRXPat.fCompiledPat.setElementAt(saveStateOp, saveStateLoc);

                // Append the URX_JMP_SAV or URX_JMPX operation to the compiled pattern.
                appendOp(jmpOp);
            }
            break;

            case doNGStar:
                // Non-greedy *? quantifier
                // compiles to
                //     1.   JMP    3
                //     2.      body of stuff being iterated over
                //     3.   STATE_SAVE  2
                //     4    ...
            {
                int jmpLoc = blockTopLoc(true);                   // loc  1.
                int saveLoc = fRXPat.fCompiledPat.size();        // loc  3.
                int jmpOp = buildOp(URX_JMP, saveLoc);
                fRXPat.fCompiledPat.setElementAt(jmpOp, jmpLoc);
                appendOp(URX_STATE_SAVE, jmpLoc + 1);
            }
            break;


            case doIntervalInit:
                // The '{' opening an interval quantifier was just scanned.
                // Init the counter variables that will accumulate the values as the digits
                //    are scanned.
                fIntervalLow = 0;
                fIntervalUpper = -1;
                break;

            case doIntevalLowerDigit:
                // Scanned a digit from the lower value of an {lower,upper} interval
            {
                int digitValue = UCharacter.digit(fC.fChar);
                assert digitValue >= 0;
                long val = (long) fIntervalLow * 10 + digitValue;
                if (val > Integer.MAX_VALUE) {
                    throw error(UErrorCode.U_REGEX_NUMBER_TOO_BIG, null);
                } else {
                    fIntervalLow = Math.toIntExact(val);
                }
            }
            break;

            case doIntervalUpperDigit:
                // Scanned a digit from the upper value of an {lower,upper} interval
            {
                if (fIntervalUpper < 0) {
                    fIntervalUpper = 0;
                }
                int digitValue = UCharacter.digit(fC.fChar);
                assert digitValue >= 0;
                long val = (long) fIntervalUpper * 10 + digitValue;
                if (val > Integer.MAX_VALUE) {
                    throw error(UErrorCode.U_REGEX_NUMBER_TOO_BIG, null);
                } else {
                    fIntervalUpper = Math.toIntExact(val);
                }
            }
            break;

            case doIntervalSame:
                // Scanned a single value interval like {27}.  Upper = Lower.
                fIntervalUpper = fIntervalLow;
                break;

            case doInterval:
                // Finished scanning a normal {lower,upper} interval.  Generate the code for it.
                if (compileInlineInterval() == false) {
                    compileInterval(URX_CTR_INIT, URX_CTR_LOOP);
                }
                break;

            case doPossessiveInterval:
                // Finished scanning a Possessive {lower,upper}+ interval.  Generate the code for it.
            {
                // Remember the loc for the top of the block being looped over.
                //   (Can not reserve a slot in the compiled pattern at this time, because
                //    compileInterval needs to reserve also, and blockTopLoc can only reserve
                //    once per block.)
                int topLoc = blockTopLoc(false);

                // Produce normal looping code.
                compileInterval(URX_CTR_INIT, URX_CTR_LOOP);

                // Surround the just-emitted normal looping code with a STO_SP ... LD_SP
                //  just as if the loop was inclosed in atomic parentheses.

                // First the STO_SP before the start of the loop
                insertOp(topLoc);

                int varLoc = allocateData(1);   // Reserve a data location for saving the
                int op = buildOp(URX_STO_SP, varLoc);
                fRXPat.fCompiledPat.setElementAt(op, topLoc);

                int loopOp = Math.toIntExact(fRXPat.fCompiledPat.popi());
                assert URX_TYPE(loopOp) == URX_CTR_LOOP && URX_VAL(loopOp) == topLoc;
                loopOp++;     // point LoopOp after the just-inserted STO_SP
                (fRXPat.fCompiledPat).push(loopOp);

                // Then the LD_SP after the end of the loop
                appendOp(URX_LD_SP, varLoc);
            }

            break;

            case doNGInterval:
                // Finished scanning a non-greedy {lower,upper}? interval.  Generate the code for it.
                compileInterval(URX_CTR_INIT_NG, URX_CTR_LOOP_NG);
                break;

            case doIntervalError:
                throw error(UErrorCode.U_REGEX_BAD_INTERVAL, null);

            case doLiteralChar:
                // We've just scanned a "normal" character from the pattern,
                literalChar(fC.fChar);
                break;


            case doEscapedLiteralChar:
                // We've just scanned an backslashed escaped character with  no
                //   special meaning.  It represents itself.
                if ((fModeFlags & UREGEX_ERROR_ON_UNKNOWN_ESCAPES.flag) != 0 &&
                        ((fC.fChar >= 0x41 && fC.fChar <= 0x5A) ||     // in [A-Z]
                                (fC.fChar >= 0x61 && fC.fChar <= 0x7a))) {   // in [a-z]
                    throw error(UErrorCode.U_REGEX_BAD_ESCAPE_SEQUENCE, null);
                }
                literalChar(fC.fChar);
                break;


            case doDotAny:
                // scanned a ".",  match any single character.
            {
                fixLiterals(false);
                if ((fModeFlags & UREGEX_DOTALL.flag) != 0) {
                    appendOp(URX_DOTANY_ALL, 0);
                } else if ((fModeFlags & UREGEX_UNIX_LINES.flag) != 0) {
                    appendOp(URX_DOTANY_UNIX, 0);
                } else {
                    appendOp(URX_DOTANY, 0);
                }
            }
            break;

            case doCaret: {
                fixLiterals(false);
                if ((fModeFlags & UREGEX_MULTILINE.flag) == 0 && (fModeFlags & UREGEX_UNIX_LINES.flag) == 0) {
                    appendOp(URX_CARET, 0);
                } else if ((fModeFlags & UREGEX_MULTILINE.flag) != 0 && (fModeFlags & UREGEX_UNIX_LINES.flag) == 0) {
                    appendOp(URX_CARET_M, 0);
                } else if ((fModeFlags & UREGEX_MULTILINE.flag) == 0 && (fModeFlags & UREGEX_UNIX_LINES.flag) != 0) {
                    appendOp(URX_CARET, 0);   // Only testing true start of input.
                } else if ((fModeFlags & UREGEX_MULTILINE.flag) != 0 && (fModeFlags & UREGEX_UNIX_LINES.flag) != 0) {
                    appendOp(URX_CARET_M_UNIX, 0);
                }
            }
            break;

            case doDollar: {
                fixLiterals(false);
                if ((fModeFlags & UREGEX_MULTILINE.flag) == 0 && (fModeFlags & UREGEX_UNIX_LINES.flag) == 0) {
                    appendOp(URX_DOLLAR, 0);
                } else if ((fModeFlags & UREGEX_MULTILINE.flag) != 0 && (fModeFlags & UREGEX_UNIX_LINES.flag) == 0) {
                    appendOp(URX_DOLLAR_M, 0);
                } else if ((fModeFlags & UREGEX_MULTILINE.flag) == 0 && (fModeFlags & UREGEX_UNIX_LINES.flag) != 0) {
                    appendOp(URX_DOLLAR_D, 0);
                } else if ((fModeFlags & UREGEX_MULTILINE.flag) != 0 && (fModeFlags & UREGEX_UNIX_LINES.flag) != 0) {
                    appendOp(URX_DOLLAR_MD, 0);
                }
            }
            break;

            case doBackslashA:
                fixLiterals(false);
                appendOp(URX_CARET, 0);
                break;

            case doBackslashB: {
                fixLiterals(false);
                UrxOps op = (fModeFlags & UREGEX_UWORD.flag) != 0 ? URX_BACKSLASH_BU : URX_BACKSLASH_B;
                appendOp(op, 1);
            }
            break;

            case doBackslashb: {
                fixLiterals(false);
                UrxOps op = ((fModeFlags & UREGEX_UWORD.flag) != 0) ? URX_BACKSLASH_BU : URX_BACKSLASH_B;
                appendOp(op, 0);
            }
            break;

            case doBackslashD:
                fixLiterals(false);
                appendOp(URX_BACKSLASH_D, 1);
                break;

            case doBackslashd:
                fixLiterals(false);
                appendOp(URX_BACKSLASH_D, 0);
                break;

            case doBackslashG:
                fixLiterals(false);
                appendOp(URX_BACKSLASH_G, 0);
                break;

            case doBackslashH:
                fixLiterals(false);
                appendOp(URX_BACKSLASH_H, 1);
                break;

            case doBackslashh:
                fixLiterals(false);
                appendOp(URX_BACKSLASH_H, 0);
                break;

            case doBackslashR:
                fixLiterals(false);
                appendOp(URX_BACKSLASH_R, 0);
                break;

            case doBackslashS:
                fixLiterals(false);
                appendOp(URX_STAT_SETREF_N, URX_ISSPACE_SET.getIndex());
                break;

            case doBackslashs:
                fixLiterals(false);
                appendOp(URX_STATIC_SETREF, URX_ISSPACE_SET.getIndex());
                break;

            case doBackslashV:
                fixLiterals(false);
                appendOp(URX_BACKSLASH_V, 1);
                break;

            case doBackslashv:
                fixLiterals(false);
                appendOp(URX_BACKSLASH_V, 0);
                break;

            case doBackslashW:
                fixLiterals(false);
                appendOp(URX_STAT_SETREF_N, URX_ISWORD_SET.getIndex());
                break;

            case doBackslashw:
                fixLiterals(false);
                appendOp(URX_STATIC_SETREF, URX_ISWORD_SET.getIndex());
                break;

            case doBackslashX:
                fixLiterals(false);
                appendOp(URX_BACKSLASH_X, 0);
                break;

            case doBackslashZ:
                fixLiterals(false);
                appendOp(URX_DOLLAR, 0);
                break;

            case doBackslashz:
                fixLiterals(false);
                appendOp(URX_BACKSLASH_Z, 0);
                break;

            case doEscapeError:
                throw error(UErrorCode.U_REGEX_BAD_ESCAPE_SEQUENCE, null);

            case doExit:
                fixLiterals(false);
                returnVal = false;
                break;

            case doProperty: {
                fixLiterals(false);
                UnicodeSet theSet = scanProp();
                compileSet(theSet);
            }
            break;

            case doNamedChar: {
                int c = scanNamedChar();
                literalChar(c);
            }
            break;


            case doBackRef:
                // BackReference.  Somewhat unusual in that the front-end can not completely parse
                //                 the regular expression, because the number of digits to be consumed
                //                 depends on the number of capture groups that have been defined.  So
                //                 we have to do it here instead.
            {
                int numCaptureGroups = fRXPat.fGroupMap.size();
                int groupNum = 0;
                int c = fC.fChar;

                for (; ; ) {
                    // Loop once per digit, for max allowed number of digits in a back reference.
                    int digit = UCharacter.digit(c);
                    groupNum = groupNum * 10 + digit;
                    if (groupNum >= numCaptureGroups) {
                        break;
                    }
                    c = peekCharLL();
                    if (c == U_SENTINEL || RegexStaticSets.INSTANCE.fRuleDigitsAlias.contains(c) == false) {
                        break;
                    }
                    nextCharLL();
                }

                // Scan of the back reference in the source regexp is complete.  Now generate
                //  the compiled code for it.
                // Because capture groups can be forward-referenced by back-references,
                //  we fill the operand with the capture group number.  At the end
                //  of compilation, it will be changed to the variable's location.
                assert groupNum > 0;  // Shouldn't happen.  '\0' begins an octal escape sequence,
                //    and shouldn't enter this code path at all.
                fixLiterals(false);
                if ((fModeFlags & UREGEX_CASE_INSENSITIVE.flag) != 0) {
                    appendOp(URX_BACKREF_I, groupNum);
                } else {
                    appendOp(URX_BACKREF, groupNum);
                }
            }
            break;

            case doBeginNamedBackRef:
                assert fCaptureName == null;
                fCaptureName = new StringBuilder();
                break;

            case doContinueNamedBackRef:
                fCaptureName.appendCodePoint(fC.fChar);
                break;

            case doCompleteNamedBackRef: {
                int groupNumber =
                        fRXPat.fNamedCaptureMap != null ? Math.toIntExact(fRXPat.fNamedCaptureMap.get(fCaptureName.toString())) : 0;
                if (groupNumber == 0) {
                    // Group name has not been defined.
                    //   Could be a forward reference. If we choose to support them at some
                    //   future time, extra mechanism will be required at this point.
                    throw error(UErrorCode.U_REGEX_INVALID_CAPTURE_GROUP_NAME, null);
                } else {
                    // Given the number, handle identically to a \n numbered back reference.
                    // See comments above, under doBackRef
                    fixLiterals(false);
                    if ((fModeFlags & UREGEX_CASE_INSENSITIVE.flag) != 0) {
                        appendOp(URX_BACKREF_I, groupNumber);
                    } else {
                        appendOp(URX_BACKREF, groupNumber);
                    }
                }
                fCaptureName = null;
                break;
            }

            case doPossessivePlus:
                // Possessive ++ quantifier.
                // Compiles to
                //       1.   STO_SP
                //       2.      body of stuff being iterated over
                //       3.   STATE_SAVE 5
                //       4.   JMP        2
                //       5.   LD_SP
                //       6.   ...
                //
                //  Note:  TODO:  This is pretty inefficient.  A mass of saved state is built up
                //                then unconditionally discarded.  Perhaps introduce a new opcode.  Ticket 6056
                //
            {
                // Emit the STO_SP
                int topLoc = blockTopLoc(true);
                int stoLoc = allocateData(1);  // Reserve the data location for storing save stack ptr.
                int op = buildOp(URX_STO_SP, stoLoc);
                fRXPat.fCompiledPat.setElementAt(op, topLoc);

                // Emit the STATE_SAVE
                appendOp(URX_STATE_SAVE, fRXPat.fCompiledPat.size() + 2);

                // Emit the JMP
                appendOp(URX_JMP, topLoc + 1);

                // Emit the LD_SP
                appendOp(URX_LD_SP, stoLoc);
            }
            break;

            case doPossessiveStar:
                // Possessive *+ quantifier.
                // Compiles to
                //       1.   STO_SP       loc
                //       2.   STATE_SAVE   5
                //       3.      body of stuff being iterated over
                //       4.   JMP          2
                //       5.   LD_SP        loc
                //       6    ...
                // TODO:  do something to cut back the state stack each time through the loop.
            {
                // Reserve two slots at the top of the block.
                int topLoc = blockTopLoc(true);
                insertOp(topLoc);

                // emit   STO_SP     loc
                int stoLoc = allocateData(1);    // Reserve the data location for storing save stack ptr.
                int op = buildOp(URX_STO_SP, stoLoc);
                fRXPat.fCompiledPat.setElementAt(op, topLoc);

                // Emit the SAVE_STATE   5
                int L7 = fRXPat.fCompiledPat.size() + 1;
                op = buildOp(URX_STATE_SAVE, L7);
                fRXPat.fCompiledPat.setElementAt(op, topLoc+1);

                // Append the JMP operation.
                appendOp(URX_JMP, topLoc + 1);

                // Emit the LD_SP       loc
                appendOp(URX_LD_SP, stoLoc);
            }
            break;

            case doPossessiveOpt:
                // Possessive  ?+ quantifier.
                //  Compiles to
                //     1. STO_SP      loc
                //     2. SAVE_STATE  5
                //     3.    body of optional block
                //     4. LD_SP       loc
                //     5. ...
                //
            {
                // Reserve two slots at the top of the block.
                int topLoc = blockTopLoc(true);
                insertOp(topLoc);

                // Emit the STO_SP
                int stoLoc = allocateData(1);   // Reserve the data location for storing save stack ptr.
                int op = buildOp(URX_STO_SP, stoLoc);
                fRXPat.fCompiledPat.setElementAt(op, topLoc);

                // Emit the SAVE_STATE
                int continueLoc = fRXPat.fCompiledPat.size() + 1;
                op = buildOp(URX_STATE_SAVE, continueLoc);
                fRXPat.fCompiledPat.setElementAt(op, topLoc+1);

                // Emit the LD_SP
                appendOp(URX_LD_SP, stoLoc);
            }
            break;


            case doBeginMatchMode:
                fNewModeFlags = fModeFlags;
                fSetModeFlag = true;
                break;

            case doMatchMode:   //  (?i)    and similar
            {
                long bit = 0;
                switch (fC.fChar) {
                    case 0x69: /* 'i' */
                        bit = UREGEX_CASE_INSENSITIVE.flag;
                        break;
                    case 0x64: /* 'd' */
                        bit = UREGEX_UNIX_LINES.flag;
                        break;
                    case 0x6d: /* 'm' */
                        bit = UREGEX_MULTILINE.flag;
                        break;
                    case 0x73: /* 's' */
                        bit = UREGEX_DOTALL.flag;
                        break;
                    case 0x75: /* 'u' */
                        bit = 0; /* Unicode casing */
                        break;
                    case 0x77: /* 'w' */
                        bit = UREGEX_UWORD.flag;
                        break;
                    case 0x78: /* 'x' */
                        bit = UREGEX_COMMENTS.flag;
                        break;
                    case 0x2d: /* '-' */
                        fSetModeFlag = false;
                        break;
                    default:
                        throw new IllegalStateException();  // Should never happen.  Other chars are filtered out
                        // by the scanner.
                }
                if (fSetModeFlag) {
                    fNewModeFlags |= Math.toIntExact(bit);
                } else {
                    fNewModeFlags &= ~(Math.toIntExact(bit));
                }
            }
            break;

            case doSetMatchMode:
                // Emit code to match any pending literals, using the not-yet changed match mode.
                fixLiterals();

                // We've got a (?i) or similar.  The match mode is being changed, but
                //   the change is not scoped to a parenthesized block.
                assert fNewModeFlags < 0;
                fModeFlags = fNewModeFlags;

                break;


            case doMatchModeParen:
                // We've got a (?i: or similar.  Begin a parenthesized block, save old
                //   mode flags so they can be restored at the close of the block.
                //
                //   Compile to a
                //      - NOP, which later may be replaced by a save-state if the
                //         parenthesized group gets a * quantifier, followed by
                //      - NOP, which may later be replaced by a save-state if there
                //             is an '|' alternation within the parens.
            {
                fixLiterals(false);
                appendOp(URX_NOP, 0);
                appendOp(URX_NOP, 0);

                // On the Parentheses stack, start a new frame and add the positions
                //   of the two NOPs (a normal non-capturing () frame, except for the
                //   saving of the original mode flags.)
                fParenStack.push(fModeFlags);
                // Frame Marker
                fParenStack.push(flags.index);
                // The first NOP
                fParenStack.push(fRXPat.fCompiledPat.size() - 2);
                // The second NOP
                fParenStack.push(fRXPat.fCompiledPat.size() - 1);

                // Set the current mode flags to the new values.
                assert fNewModeFlags < 0;
                fModeFlags = fNewModeFlags;
            }
            break;

            case doBadModeFlag:
                throw error(UErrorCode.U_REGEX_INVALID_FLAG, null);

            case doSuppressComments:
                // We have just scanned a '(?'.  We now need to prevent the character scanner from
                // treating a '#' as a to-the-end-of-line comment.
                //   (This Perl compatibility just gets uglier and uglier to do...)
                fEOLComments = false;
                break;


            case doSetAddAmp: {
                UnicodeSet set = fSetStack.peek();
                set.add(chAmp);
            }
            break;

            case doSetAddDash: {
                UnicodeSet set = fSetStack.peek();
                set.add(chDash);
            }
            break;

            case doSetBackslash_s: {
                UnicodeSet set = fSetStack.peek();
                set.addAll(RegexStaticSets.INSTANCE.fPropSets[URX_ISSPACE_SET.getIndex()]);
                break;
            }

            case doSetBackslash_S: {
                UnicodeSet set = fSetStack.peek();
                UnicodeSet SSet = new UnicodeSet();
                SSet.addAll(RegexStaticSets.INSTANCE.fPropSets[URX_ISSPACE_SET.getIndex()]).complement();
                set.addAll(SSet);
                break;
            }

            case doSetBackslash_d: {
                UnicodeSet set = fSetStack.peek();
                // TODO - make a static set, ticket 6058.
                addCategory(set, GC_ND_MASK);
                break;
            }

            case doSetBackslash_D: {
                UnicodeSet set = fSetStack.peek();
                UnicodeSet digits = new UnicodeSet();
                // TODO - make a static set, ticket 6058.
                digits.applyIntPropertyValue(GENERAL_CATEGORY_MASK, Math.toIntExact(GC_ND_MASK));
                digits.complement();
                set.addAll(digits);
                break;
            }

            case doSetBackslash_h: {
                UnicodeSet set = fSetStack.peek();
                UnicodeSet h = new UnicodeSet();
                h.applyIntPropertyValue(GENERAL_CATEGORY_MASK, Math.toIntExact(GC_ZS_MASK));
                h.add((int) 9);   // Tab
                set.addAll(h);
                break;
            }

            case doSetBackslash_H: {
                UnicodeSet set = fSetStack.peek();
                UnicodeSet h = new UnicodeSet();
                h.applyIntPropertyValue(GENERAL_CATEGORY_MASK, Math.toIntExact(GC_ZS_MASK));
                h.add((int) 9);   // Tab
                h.complement();
                set.addAll(h);
                break;
            }

            case doSetBackslash_v: {
                UnicodeSet set = fSetStack.peek();
                set.add((int) 0x0a, (int) 0x0d);  // add range
                set.add((int) 0x85);
                set.add((int) 0x2028, (int) 0x2029);
                break;
            }

            case doSetBackslash_V: {
                UnicodeSet set = fSetStack.peek();
                UnicodeSet v = new UnicodeSet();
                v.add((int) 0x0a, (int) 0x0d);  // add range
                v.add((int) 0x85);
                v.add((int) 0x2028, (int) 0x2029);
                v.complement();
                set.addAll(v);
                break;
            }

            case doSetBackslash_w: {
                UnicodeSet set = fSetStack.peek();
                set.addAll(RegexStaticSets.INSTANCE.fPropSets[URX_ISWORD_SET.getIndex()]);
                break;
            }

            case doSetBackslash_W: {
                UnicodeSet set = fSetStack.peek();
                UnicodeSet SSet = new UnicodeSet();
                SSet.addAll(RegexStaticSets.INSTANCE.fPropSets[URX_ISWORD_SET.getIndex()]).complement();
                set.addAll(SSet);
                break;
            }

            case doSetBegin: {
                fixLiterals(false);
                fSetStack.push(new UnicodeSet());
                fSetOpStack.push(setStart);
                if ((fModeFlags & UREGEX_CASE_INSENSITIVE.flag) != 0) {
                    fSetOpStack.push(setCaseClose);
                }
                break;
            }

            case doSetBeginDifference1:
                //  We have scanned something like [[abc]-[
                //  Set up a new UnicodeSet for the set beginning with the just-scanned '['
                //  Push a Difference operator, which will cause the new set to be subtracted from what
                //    went before once it is created.
                setPushOp(setDifference1.index);
                fSetOpStack.push(setStart);
                if ((fModeFlags & UREGEX_CASE_INSENSITIVE.flag) != 0) {
                    fSetOpStack.push(setCaseClose);
                }
                break;

            case doSetBeginIntersection1:
                //  We have scanned something like  [[abc]&[
                //   Need both the '&' operator and the open '[' operator.
                setPushOp(setIntersection1.index);
                fSetOpStack.push(setStart);
                if ((fModeFlags & UREGEX_CASE_INSENSITIVE.flag) != 0) {
                    fSetOpStack.push(setCaseClose);
                }
                break;

            case doSetBeginUnion:
                //  We have scanned something like  [[abc][
                //     Need to handle the union operation explicitly [[abc] | [
                setPushOp(setUnion.index);
                fSetOpStack.push(setStart);
                if ((fModeFlags & UREGEX_CASE_INSENSITIVE.flag) != 0) {
                    fSetOpStack.push(setCaseClose);
                }
                break;

            case doSetDifference2:
                // We have scanned something like [abc--
                //   Consider this to unambiguously be a set difference operator.
                setPushOp(setDifference2.index);
                break;

            case doSetEnd:
                // Have encountered the ']' that closes a set.
                //    Force the evaluation of any pending operations within this set,
                //    leave the completed set on the top of the set stack.
                setEval(setEnd.index);
                assert fSetOpStack.peek() == setStart;
                fSetOpStack.pop();
                break;

            case doSetFinish: {
                // Finished a complete set expression, including all nested sets.
                //   The close bracket has already triggered clearing out pending set operators,
                //    the operator stack should be empty and the operand stack should have just
                //    one entry, the result set.
                assert fSetOpStack.isEmpty();
                UnicodeSet theSet = fSetStack.pop();
                assert fSetStack.isEmpty();
                compileSet(theSet);
                break;
            }

            case doSetIntersection2:
                // Have scanned something like [abc&&
                setPushOp(setIntersection2.index);
                break;

            case doSetLiteral:
                // Union the just-scanned literal character into the set being built.
                //    This operation is the highest precedence set operation, so we can always do
                //    it immediately, without waiting to see what follows.  It is necessary to perform
                //    any pending '-' or '&' operation first, because these have the same precedence
                //    as union-ing in a literal'
            {
                setEval(setUnion.index);
                UnicodeSet s = fSetStack.peek();
                s.add(fC.fChar);
                fLastSetLiteral = fC.fChar;
                break;
            }

            case doSetLiteralEscaped:
                // A back-slash escaped literal character was encountered.
                // Processing is the same as with setLiteral, above, with the addition of
                //  the optional check for errors on escaped ASCII letters.
            {
                if ((fModeFlags & UREGEX_ERROR_ON_UNKNOWN_ESCAPES.flag) != 0 &&
                        ((fC.fChar >= 0x41 && fC.fChar <= 0x5A) ||     // in [A-Z]
                                (fC.fChar >= 0x61 && fC.fChar <= 0x7a))) {   // in [a-z]
                    throw error(UErrorCode.U_REGEX_BAD_ESCAPE_SEQUENCE, null);
                }
                setEval(setUnion.index);
                UnicodeSet s = fSetStack.peek();
                s.add(fC.fChar);
                fLastSetLiteral = fC.fChar;
                break;
            }

            case doSetNamedChar:
                // Scanning a \N{UNICODE CHARACTER NAME}
                //  Aside from the source of the character, the processing is identical to doSetLiteral,
                //    above.
            {
                int c = scanNamedChar();
                setEval(setUnion.index);
                UnicodeSet s = fSetStack.peek();
                s.add(c);
                fLastSetLiteral = c;
                break;
            }

            case doSetNamedRange:
                // We have scanned literal-\N{CHAR NAME}.  Add the range to the set.
                // The left character is already in the set, and is saved in fLastSetLiteral.
                // The right side needs to be picked up, the scan is at the 'N'.
                // Lower Limit > Upper limit being an error matches both Java
                //        and ICU UnicodeSet behavior.
            {
                int c = scanNamedChar();
                if ((fLastSetLiteral == U_SENTINEL || fLastSetLiteral > c)) {
                    throw error(UErrorCode.U_REGEX_INVALID_RANGE, null);
                }
                UnicodeSet s = fSetStack.peek();
                s.add(fLastSetLiteral, c);
                fLastSetLiteral = c;
                break;
            }


            case doSetNegate:
                // Scanned a '^' at the start of a set.
                // Push the negation operator onto the set op stack.
                // A twist for case-insensitive matching:
                //   the case closure operation must happen _before_ negation.
                //   But the case closure operation will already be on the stack if it's required.
                //   This requires checking for case closure, and swapping the stack order
                //    if it is present.
            {
                int tosOp = (int) fSetOpStack.peek().index;
                if (tosOp == setCaseClose.index) {
                    fSetOpStack.pop();
                    fSetOpStack.push(setNegation);
                    fSetOpStack.push(setCaseClose);
                } else {
                    fSetOpStack.push(setNegation);
                }
            }
            break;

            case doSetNoCloseError:
                throw error(UErrorCode.U_REGEX_MISSING_CLOSE_BRACKET, null);

            case doSetOpError:
                throw error(UErrorCode.U_REGEX_RULE_SYNTAX, null);   //  -- or && at the end of a set.  Illegal.

            case doSetPosixProp: {
                UnicodeSet s = scanPosixProp();
                if (s != null) {
                    UnicodeSet tos = fSetStack.peek();
                    tos.addAll(s);
                }  // else error.  scanProp() reported the error status already.
            }
            break;

            case doSetProp:
                //  Scanned a \p \P within [brackets].
            {
                UnicodeSet s = scanProp();
                if (s != null) {
                    UnicodeSet tos = fSetStack.peek();
                    tos.addAll(s);
                }  // else error.  scanProp() reported the error status already.
            }
            break;


            case doSetRange:
                // We have scanned literal-literal.  Add the range to the set.
                // The left character is already in the set, and is saved in fLastSetLiteral.
                // The right side is the current character.
                // Lower Limit > Upper limit being an error matches both Java
                //        and ICU UnicodeSet behavior.
            {

                if (fLastSetLiteral == U_SENTINEL || fLastSetLiteral > fC.fChar) {
                    throw error(UErrorCode.U_REGEX_INVALID_RANGE, null);
                }
                UnicodeSet s = fSetStack.peek();
                s.add(fLastSetLiteral, fC.fChar);
                break;
            }

            default:
                throw new IllegalStateException();
        }

        return returnVal;
    }


    /**
     * We've encountered a literal character from the pattern,
     * or an escape sequence that reduces to a character.<br/>
     * Add it to the string containing all literal chars/strings from
     * the pattern.
     */
    private void literalChar(final int c) {
        fLiteralChars.appendCodePoint(c);
    }


    /**
     * When compiling something that can follow a literal
     * string in a pattern, emit the code to match the
     * accumulated literal string.<br/>
     * Optionally, split the last char of the string off into
     * a single "ONE_CHAR" operation, so that quantifiers can
     * apply to that char alone.  Example:   abc*<br/>
     * The * must apply to the 'c' only.
     */
    private void fixLiterals() {
        fixLiterals(false);
    }

    private void fixLiterals(final boolean split) {

        // If no literal characters have been scanned but not yet had code generated
        //   for them, nothing needs to be done.
        if (fLiteralChars.length() == 0) {
            return;
        }

        int indexOfLastCodePoint = moveIndex32(fLiteralChars, fLiteralChars.length(), -1);
        int lastCodePoint = char32At(fLiteralChars, indexOfLastCodePoint);

        // Split:  We need to  ensure that the last item in the compiled pattern
        //     refers only to the last literal scanned in the pattern, so that
        //     quantifiers (*, +, etc.) affect only it, and not a longer string.
        //     Split before case folding for case insensitive matches.

        if (split) {
            truncate(fLiteralChars, indexOfLastCodePoint);
            fixLiterals(false);   // Recursive call, emit code to match the first part of the string.
            //  Note that the truncated literal string may be empty, in which case
            //  nothing will be emitted.

            literalChar(lastCodePoint);  // Re-add the last code point as if it were a new literal.
            fixLiterals(false);          // Second recursive call, code for the final code point.
            return;
        }

        // If we are doing case-insensitive matching, case fold the string.  This may expand
        //   the string, e.g. the German sharp-s turns into "ss"
        if ((fModeFlags & UREGEX_CASE_INSENSITIVE.flag) != 0) {
            foldCase(fLiteralChars);
            indexOfLastCodePoint = moveIndex32(fLiteralChars, fLiteralChars.length(), -1);
            lastCodePoint = char32At(fLiteralChars, indexOfLastCodePoint);
        }

        if (indexOfLastCodePoint == 0) {
            // Single character, emit a URX_ONECHAR op to match it.
            if (((fModeFlags & UREGEX_CASE_INSENSITIVE.flag) != 0) &&
                    UCharacter.hasBinaryProperty(lastCodePoint, CASE_SENSITIVE)) {
                appendOp(URX_ONECHAR_I, lastCodePoint);
            } else {
                appendOp(URX_ONECHAR, lastCodePoint);
            }
        } else {
            // Two or more chars, emit a URX_STRING to match them.
            if (fLiteralChars.length() > 0x00ffffff || fRXPat.fLiteralText.length() > 0x00ffffff) {
                throw error(UErrorCode.U_REGEX_PATTERN_TOO_BIG, null);
            }
            if ((fModeFlags & UREGEX_CASE_INSENSITIVE.flag) != 0) {
                appendOp(URX_STRING_I, fRXPat.fLiteralText.length());
            } else {
                // TODO here:  add optimization to split case sensitive strings of length two
                //             into two single char ops, for efficiency.
                appendOp(URX_STRING, fRXPat.fLiteralText.length());
            }
            appendOp(URX_STRING_LEN, fLiteralChars.length());

            // Add this string into the accumulated strings of the compiled pattern.
            fRXPat.fLiteralText.append(fLiteralChars);
        }

        remove(fLiteralChars);
    }


    private int buildOp(final UrxOps type, final int val) {
        UrxOps typeNew = type;
        if (val > 0x00ffffff) {
            throw new IllegalStateException();
        }
        if (val < 0) {
            if (!(typeNew == URX_RESERVED_OP_N || typeNew == URX_RESERVED_OP)) {
                throw new IllegalStateException();
            }
            if (URX_TYPE(val).index != 0xff) {
                throw new IllegalStateException();
            }
            typeNew = URX_RESERVED_OP_N;
        }
        return (typeNew.index << 24) | val;
    }


    /**
     * Append a new instruction onto the compiled pattern<br/>
     * Includes error checking, limiting the size of the
     * pattern to lengths that can be represented in the
     * 24 bit operand field of an instruction.
     */
    private void appendOp(final int op) {
        fRXPat.fCompiledPat.addElement((long) op);
        if (fRXPat.fCompiledPat.size() > 0x00fffff0) {
            throw error(UErrorCode.U_REGEX_PATTERN_TOO_BIG, null);
        }
    }

    /**
     * Append a new instruction onto the compiled pattern<br/>
     * Includes error checking, limiting the size of the
     * pattern to lengths that can be represented in the
     * 24 bit operand field of an instruction.
     */
    private void appendOp(final UrxOps type, final int val) {
        appendOp(buildOp(type, val));
    }


    /**
     * Insert a slot for a new opcode into the already
     * compiled pattern code.<br/>
     * Fill the slot with a NOP.  Our caller will replace it
     * with what they really wanted.
     */
    private void insertOp(final int where) {
        MutableVector64 code = fRXPat.fCompiledPat;
        assert where > 0 && where < code.size();

        int nop = buildOp(URX_NOP, 0);
        code.insertElementAt(nop, where);

        // Walk through the pattern, looking for any ops with targets that
        //  were moved down by the insert.  Fix them.
        int loc;
        for (loc = 0; loc < code.size(); loc++) {
            int op = Math.toIntExact(code.elementAti(loc));
            UrxOps opType = URX_TYPE(op);
            int opValue = URX_VAL(op);
            if ((opType == URX_JMP ||
                    opType == URX_JMPX ||
                    opType == URX_STATE_SAVE ||
                    opType == URX_CTR_LOOP ||
                    opType == URX_CTR_LOOP_NG ||
                    opType == URX_JMP_SAV ||
                    opType == URX_JMP_SAV_X ||
                    opType == URX_RELOC_OPRND) && opValue > where) {
                // Target location for this opcode is after the insertion point and
                //   needs to be incremented to adjust for the insertion.
                opValue++;
                op = buildOp(opType, opValue);
                code.setElementAt(op, loc);
            }
        }

        // Now fix up the parentheses stack.  All positive values in it are locations in
        //  the compiled pattern.   (Negative values are frame boundaries, and don't need fixing.)
        for (loc = 0; loc < fParenStack.size(); loc++) {
            int x = fParenStack.elementAti(loc);
            assert x < code.size();
            if (x > where) {
                x++;
                fParenStack.setElementAt(x, loc);
            }
        }

        if (fMatchCloseParen > where) {
            fMatchCloseParen++;
        }
        if (fMatchOpenParen > where) {
            fMatchOpenParen++;
        }
    }


    /**
     * Allocate storage in the matcher's static data area.<br/>
     * Return the index for the newly allocated data.<br/>
     * The storage won't actually exist until we are running a match
     * operation, but the storage indexes are inserted into various
     * opcodes while compiling the pattern.
     */
    private int allocateData(final int size) {
        if (size <= 0 || size > 0x100 || fRXPat.fDataSize < 0) {
            throw error(UErrorCode.U_REGEX_INTERNAL_ERROR, null);
//            return 0;
        }
        int dataIndex = fRXPat.fDataSize;
        fRXPat.fDataSize += size;
        if (fRXPat.fDataSize >= 0x00fffff0) {
            throw error(UErrorCode.U_REGEX_INTERNAL_ERROR, null);
        }
        return dataIndex;
    }


    /**
     * Allocate space in the back-tracking stack frame.<br/>
     * Return the index for the newly allocated data.<br/>
     * The frame indexes are inserted into various
     * opcodes while compiling the pattern, meaning that frame
     * size must be restricted to the size that will fit
     * as an operand (24 bits).
     */
    private int allocateStackData(final int size) {
        if (size <= 0 || size > 0x100 || fRXPat.fFrameSize < 0) {
            throw error(UErrorCode.U_REGEX_INTERNAL_ERROR, null);
//            return 0;
        }
        int dataIndex = fRXPat.fFrameSize;
        fRXPat.fFrameSize += size;
        if (fRXPat.fFrameSize >= 0x00fffff0) {
            throw error(UErrorCode.U_REGEX_INTERNAL_ERROR, null);
        }
        return dataIndex;
    }


    /**
     * Find or create a location in the compiled pattern
     * at the start of the operation or block that has
     * just been compiled.  Needed when a quantifier (* or
     * whatever) appears, and we need to add an operation
     * at the start of the thing being quantified.<br/>
     * (Parenthesized Blocks) have a slot with a NOP that
     * is reserved for this purpose.  .* or similar don't
     * and a slot needs to be added.<br/>
     *
     * @param reserveLoc true -  ensure that there is space to add an opcode
     *                   at the returned location.<br/>
     *                   false - just return the address,
     *                   do not reserve a location there.
     */
    private int blockTopLoc(final boolean reserveLoc) {
        int theLoc;
        fixLiterals(true);  // Emit code for any pending literals.
        //   If last item was a string, emit separate op for the its last char.
        if (fRXPat.fCompiledPat.size() == fMatchCloseParen) {
            // The item just processed is a parenthesized block.
            theLoc = fMatchOpenParen;   // A slot is already reserved for us.
            assert theLoc > 0;
            assert URX_TYPE(Math.toIntExact(fRXPat.fCompiledPat.elementAti(theLoc))) == URX_NOP;
        } else {
            // Item just compiled is a single thing, a ".", or a single char, a string or a set reference.
            // No slot for STATE_SAVE was pre-reserved in the compiled code.
            // We need to make space now.
            theLoc = fRXPat.fCompiledPat.size() - 1;
            int opAtTheLoc = Math.toIntExact(fRXPat.fCompiledPat.elementAti(theLoc));
            if (URX_TYPE(opAtTheLoc) == URX_STRING_LEN) {
                // Strings take two opcode, we want the position of the first one.
                // We can have a string at this point if a single character case-folded to two.
                theLoc--;
            }
            if (reserveLoc) {
                int nop = buildOp(URX_NOP, 0);
                fRXPat.fCompiledPat.insertElementAt(nop, theLoc);
            }
        }
        return theLoc;
    }


    /**
     * When compiling a close paren, we need to go back
     * and fix up any JMP or SAVE operations within the
     * parenthesized block that need to target the end
     * of the block.  The locations of these are kept on
     * the paretheses stack.<br/>
     * This function is called both when encountering a
     * real ) and at the end of the pattern.
     */
    private void handleCloseParen() {
        int patIdx;
        int patOp;
        if (fParenStack.size() <= 0) {
            throw error(UErrorCode.U_REGEX_MISMATCHED_PAREN, null);
        }

        // Emit code for any pending literals.
        fixLiterals(false);

        // Fixup any operations within the just-closed parenthesized group
        //    that need to reference the end of the (block).
        //    (The first one popped from the stack is an unused slot for
        //     alternation (OR) state save, but applying the fixup to it does no harm.)
        for (; ; ) {
            patIdx = fParenStack.popi();
            if (patIdx < 0) {
                // value < 0 flags the start of the frame on the paren stack.
                break;
            }
            assert patIdx > 0 && patIdx <= fRXPat.fCompiledPat.size();
            patOp = Math.toIntExact(fRXPat.fCompiledPat.elementAti(patIdx));
            assert URX_VAL(patOp) == 0;          // Branch target for JMP should not be set.
            patOp |= fRXPat.fCompiledPat.size();  // Set it now.
            fRXPat.fCompiledPat.setElementAt(patOp, patIdx);
            fMatchOpenParen = patIdx;
        }

        //  At the close of any parenthesized block, restore the match mode flags  to
        //  the value they had at the open paren.  Saved value is
        //  at the top of the paren stack.
        fModeFlags = fParenStack.popi();
        assert fModeFlags < 0;

        // DO any additional fixups, depending on the specific kind of
        // parentesized grouping this is

        switch (EParenClass.getByIndex(patIdx)) {
            case plain:
            case flags:
                // No additional fixups required.
                //   (Grouping-only parentheses)
                break;
            case capturing:
                // Capturing Parentheses.
                //   Insert a End Capture op into the pattern.
                //   The frame offset of the variables for this cg is obtained from the
                //       start capture op and put it into the end-capture op.
            {
                int captureOp = Math.toIntExact(fRXPat.fCompiledPat.elementAti(fMatchOpenParen + 1));
                assert URX_TYPE(captureOp) == URX_START_CAPTURE;

                int frameVarLocation = URX_VAL(captureOp);
                appendOp(URX_END_CAPTURE, frameVarLocation);
            }
            break;
            case atomic:
                // Atomic Parenthesis.
                //   Insert a LD_SP operation to restore the state stack to the position
                //   it was when the atomic parens were entered.
            {
                int stoOp = Math.toIntExact(fRXPat.fCompiledPat.elementAti(fMatchOpenParen + 1));
                assert URX_TYPE(stoOp) == URX_STO_SP;
                int stoLoc = URX_VAL(stoOp);
                appendOp(URX_LD_SP, stoLoc);
            }
            break;

            case lookAhead: {
                int startOp = Math.toIntExact(fRXPat.fCompiledPat.elementAti(fMatchOpenParen - 5));
                assert URX_TYPE(startOp) == URX_LA_START;
                int dataLoc = URX_VAL(startOp);
                appendOp(URX_LA_END, dataLoc);
            }
            break;

            case negLookAhead: {
                // See comment at doOpenLookAheadNeg
                int startOp = Math.toIntExact(fRXPat.fCompiledPat.elementAti(fMatchOpenParen - 1));
                assert URX_TYPE(startOp) == URX_LA_START;
                int dataLoc = URX_VAL(startOp);
                appendOp(URX_LA_END, dataLoc);
                appendOp(URX_BACKTRACK, 0);
                appendOp(URX_LA_END, dataLoc);

                // Patch the URX_SAVE near the top of the block.
                // The destination of the SAVE is the final LA_END that was just added.
                int saveOp = Math.toIntExact(fRXPat.fCompiledPat.elementAti(fMatchOpenParen));
                assert URX_TYPE(saveOp) == URX_STATE_SAVE;
                int dest = fRXPat.fCompiledPat.size() - 1;
                saveOp = buildOp(URX_STATE_SAVE, dest);
                // TODO: check it was add/insert not set in similar cases
                fRXPat.fCompiledPat.setElementAt((long) saveOp, fMatchOpenParen);
            }
            break;

            case lookBehind: {
                // See comment at doOpenLookBehind.

                // Append the URX_LB_END and URX_LA_END to the compiled pattern.
                int startOp = Math.toIntExact(fRXPat.fCompiledPat.elementAti(fMatchOpenParen - 4));
                assert URX_TYPE(startOp) == URX_LB_START;
                int dataLoc = URX_VAL(startOp);
                appendOp(URX_LB_END, dataLoc);
                appendOp(URX_LA_END, dataLoc);

                // Determine the min and max bounds for the length of the
                //  string that the pattern can match.
                //  An unbounded upper limit is an error.
                int patEnd = fRXPat.fCompiledPat.size() - 1;
                int minML = minMatchLength(fMatchOpenParen, patEnd);
                int maxML = maxMatchLength(fMatchOpenParen, patEnd);
                if (maxML == Integer.MAX_VALUE) {
                    throw error(UErrorCode.U_REGEX_LOOK_BEHIND_LIMIT, null);
//                    break;
                }
                if (URX_TYPE(maxML).index != 0) {
                    throw error(UErrorCode.U_REGEX_LOOK_BEHIND_LIMIT, null);
//                    break;
                }
                if (minML == Integer.MAX_VALUE) {
                    // This condition happens when no match is possible, such as with a
                    // [set] expression containing no elements.
                    // In principle, the generated code to evaluate the expression could be deleted,
                    // but it's probably not worth the complication.
                    minML = 0;
                }
                assert minML <= maxML;

                // Insert the min and max match len bounds into the URX_LB_CONT op that
                //  appears at the top of the look-behind block, at location fMatchOpenParen+1
                fRXPat.fCompiledPat.setElementAt((long) minML, fMatchOpenParen - 2);
                fRXPat.fCompiledPat.setElementAt((long) maxML, fMatchOpenParen - 1);

            }
            break;


            case lookBehindN: {
                // See comment at doOpenLookBehindNeg.

                // Append the URX_LBN_END to the compiled pattern.
                int startOp = Math.toIntExact(fRXPat.fCompiledPat.elementAti(fMatchOpenParen - 5));
                assert URX_TYPE(startOp) == URX_LB_START;
                int dataLoc = URX_VAL(startOp);
                appendOp(URX_LBN_END, dataLoc);

                // Determine the min and max bounds for the length of the
                //  string that the pattern can match.
                //  An unbounded upper limit is an error.
                int patEnd = fRXPat.fCompiledPat.size() - 1;
                int minML = minMatchLength(fMatchOpenParen, patEnd);
                int maxML = maxMatchLength(fMatchOpenParen, patEnd);
                if (URX_TYPE(maxML).index != 0) {
                    throw error(UErrorCode.U_REGEX_LOOK_BEHIND_LIMIT, null);
//                    break;
                }
                if (maxML == Integer.MAX_VALUE) {
                    throw error(UErrorCode.U_REGEX_LOOK_BEHIND_LIMIT, null);
//                    break;
                }
                if (minML == Integer.MAX_VALUE) {
                    // This condition happens when no match is possible, such as with a
                    // [set] expression containing no elements.
                    // In principle, the generated code to evaluate the expression could be deleted,
                    // but it's probably not worth the complication.
                    minML = 0;
                }

                assert minML <= maxML;

                // Insert the min and max match len bounds into the URX_LB_CONT op that
                //  appears at the top of the look-behind block, at location fMatchOpenParen+1
                fRXPat.fCompiledPat.setElementAt((long) minML, fMatchOpenParen - 3);
                fRXPat.fCompiledPat.setElementAt((long) maxML, fMatchOpenParen - 2);

                // Insert the pattern location to continue at after a successful match
                //  as the last operand of the URX_LBN_CONT
                int op = buildOp(URX_RELOC_OPRND, fRXPat.fCompiledPat.size());
                fRXPat.fCompiledPat.setElementAt((long) op, fMatchOpenParen - 1);
            }
            break;


            default:
                throw new IllegalStateException();
        }

        // remember the next location in the compiled pattern.
        // The compilation of Quantifiers will look at this to see whether its looping
        //   over a parenthesized block or a single item
        fMatchCloseParen = fRXPat.fCompiledPat.size();
    }


    /**
     * Compile the pattern operations for a reference to a
     * UnicodeSet.
     */
    private void compileSet(final UnicodeSet theSet) {
        if (theSet == null) {
            return;
        }
        //  Remove any strings from the set.
        //  There shouldn't be any, but just in case.
        //     (Case Closure can add them; if we had a simple case closure available that
        //      ignored strings, that would be better.)
        theSet.removeAllStrings();
        int setSizeI = theSet.size();

        switch (setSizeI) {
            case 0: {
                // Set of no elements.   Always fails to match.
                appendOp(URX_BACKTRACK, 0);
            }
            break;

            case 1: {
                // The set contains only a single code point.  Put it into
                //   the compiled pattern as a single char operation rather
                //   than a set, and discard the set itself.
                literalChar(theSet.charAt(0));
            }
            break;

            default: {
                //  The set contains two or more chars.  (the normal case)
                //  Put it into the compiled pattern as a set.
                theSet.freeze();
                int setNumber = fRXPat.fSets.size();
                fRXPat.fSets.add(theSet);
                appendOp(URX_SETREF, setNumber);
            }
        }
    }


    /**
     * Generate the code for a {min, max} style interval quantifier.<br/>
     * Except for the specific opcodes used, the code is the same
     * for all three types (greedy, non-greedy, possessive) of
     * intervals.  The opcodes are supplied as parameters.<br/>
     * (There are two sets of opcodes - greedy & possessive use the
     * same ones, while non-greedy has it's own.)<br/>
     * The code for interval loops has this form:<br/>
     * 0  CTR_INIT   counter loc (in stack frame)<br/>
     * 1             5  patt address of CTR_LOOP at bottom of block<br/>
     * 2             min count<br/>
     * 3             max count   (-1 for unbounded)<br/>
     * 4  ...        block to be iterated over<br/>
     * 5  CTR_LOOP<br/>
     * In
     */
    private void compileInterval(final UrxOps InitOp, final UrxOps LoopOp) {
        // The CTR_INIT op at the top of the block with the {n,m} quantifier takes
        //   four slots in the compiled code.  Reserve them.
        int topOfBlock = blockTopLoc(true);
        insertOp(topOfBlock);
        insertOp(topOfBlock);
        insertOp(topOfBlock);

        // The operands for the CTR_INIT opcode include the index in the matcher data
        //   of the counter.  Allocate it now. There are two data items
        //        counterLoc   -.Loop counter
        //               +1    -.Input index (for breaking non-progressing loops)
        //                          (Only present if unbounded upper limit on loop)
        int dataSize = fIntervalUpper < 0 ? 2 : 1;
        int counterLoc = allocateStackData(dataSize);

        int op = buildOp(InitOp, counterLoc);
        fRXPat.fCompiledPat.setElementAt((long) op, topOfBlock);

        // The second operand of CTR_INIT is the location following the end of the loop.
        //   Must put in as a URX_RELOC_OPRND so that the value will be adjusted if the
        //   compilation of something later on causes the code to grow and the target
        //   position to move.
        int loopEnd = fRXPat.fCompiledPat.size();
        op = buildOp(URX_RELOC_OPRND, loopEnd);
        fRXPat.fCompiledPat.setElementAt((long) op, topOfBlock + 1);

        // Followed by the min and max counts.
        fRXPat.fCompiledPat.setElementAt((long) fIntervalLow, topOfBlock + 2);
        fRXPat.fCompiledPat.setElementAt((long) fIntervalUpper, topOfBlock + 3);

        // Append the CTR_LOOP op.  The operand is the location of the CTR_INIT op.
        //   Goes at end of the block being looped over, so just append to the code so far.
        appendOp(LoopOp, topOfBlock);

        if ((fIntervalLow & 0xff000000) != 0 ||
                (fIntervalUpper > 0 && (fIntervalUpper & 0xff000000) != 0)) {
            throw error(UErrorCode.U_REGEX_NUMBER_TOO_BIG, null);
        }

        if (fIntervalLow > fIntervalUpper && fIntervalUpper != -1) {
            throw error(UErrorCode.U_REGEX_MAX_LT_MIN, null);
        }
    }


    private boolean compileInlineInterval() {
        if (fIntervalUpper > 10 || fIntervalUpper < fIntervalLow) {
            // Too big to inline.  Fail, which will cause looping code to be generated.
            //   (Upper < Lower picks up unbounded upper and errors, both.)
            return false;
        }

        int topOfBlock = blockTopLoc(false);
        if (fIntervalUpper == 0) {
            // Pathological case.  Attempt no matches, as if the block doesn't exist.
            // Discard the generated code for the block.
            // If the block included parens, discard the info pertaining to them as well.
            fRXPat.fCompiledPat.setSize(topOfBlock);
            if (fMatchOpenParen >= topOfBlock) {
                fMatchOpenParen = -1;
            }
            if (fMatchCloseParen >= topOfBlock) {
                fMatchCloseParen = -1;
            }
            return true;
        }

        if (topOfBlock != fRXPat.fCompiledPat.size() - 1 && fIntervalUpper != 1) {
            // The thing being repeated is not a single op, but some
            //   more complex block.  Do it as a loop, not inlines.
            //   Note that things "repeated" a max of once are handled as inline, because
            //     the one copy of the code already generated is just fine.
            return false;
        }

        // Pick up the opcode that is to be repeated
        //
        int op = Math.toIntExact(fRXPat.fCompiledPat.elementAti(topOfBlock));

        // Compute the pattern location where the inline sequence
        //   will end, and set up the state save op that will be needed.
        //
        int endOfSequenceLoc = fRXPat.fCompiledPat.size() - 1
                + fIntervalUpper + (fIntervalUpper - fIntervalLow);
        int saveOp = buildOp(URX_STATE_SAVE, endOfSequenceLoc);
        if (fIntervalLow == 0) {
            insertOp(topOfBlock);
            fRXPat.fCompiledPat.setElementAt((long) saveOp, topOfBlock);
        }


        //  Loop, emitting the op for the thing being repeated each time.
        //    Loop starts at 1 because one instance of the op already exists in the pattern,
        //    it was put there when it was originally encountered.
        int i;
        for (i = 1; i < fIntervalUpper; i++) {
            if (i >= fIntervalLow) {
                appendOp(saveOp);
            }
            appendOp(op);
        }
        return true;
    }


    /**
     * given a single code point from a pattern string, determine the
     * set of characters that could potentially begin a case-insensitive
     * match of a string beginning with that character, using full Unicode
     * case insensitive matching.<br/>
     * This is used in optimizing find().<br/>
     * closeOver(USET_CASE_INSENSITIVE) does most of what is needed, but
     * misses cases like this:<br/>
     * A string from the pattern begins with 'ss' (although all we know
     * in this context is that it begins with 's')<br/>
     * The pattern could match a string beginning with a German sharp-s
     * To the ordinary case closure for a character c, we add all other
     * characters cx where the case closure of cx includes a string form that begins
     * with the original character c.<br/>
     * This function could be made smarter. The full pattern string is available
     * and it would be possible to verify that the extra characters being added
     * to the starting set fully match, rather than having just a first-char of the
     * folded form match.
     */
    static void findCaseInsensitiveStarters(final int c, final UnicodeSet starterChars) {

// Below code is based on Machine Generated code.
// It may need updating with new versions of Unicode.
// Intltest test RegexTest::TestCaseInsensitiveStarters will fail if an update is needed.
// The update tool is here:
// https://github.com/unicode-org/icu/tree/main/tools/unicode/c/genregexcasing

// Machine Generated Data. Do not hand edit.
        int[] RECaseFixCodePoints = {
                0x61, 0x66, 0x68, 0x69, 0x6a, 0x73, 0x74, 0x77, 0x79, 0x2bc,
                0x3ac, 0x3ae, 0x3b1, 0x3b7, 0x3b9, 0x3c1, 0x3c5, 0x3c9, 0x3ce, 0x565,
                0x574, 0x57e, 0x1f00, 0x1f01, 0x1f02, 0x1f03, 0x1f04, 0x1f05, 0x1f06, 0x1f07,
                0x1f20, 0x1f21, 0x1f22, 0x1f23, 0x1f24, 0x1f25, 0x1f26, 0x1f27, 0x1f60, 0x1f61,
                0x1f62, 0x1f63, 0x1f64, 0x1f65, 0x1f66, 0x1f67, 0x1f70, 0x1f74, 0x1f7c, 0x110000};

        short[] RECaseFixStringOffsets = {
                0x0, 0x1, 0x6, 0x7, 0x8, 0x9, 0xd, 0xe, 0xf, 0x10,
                0x11, 0x12, 0x13, 0x17, 0x1b, 0x20, 0x21, 0x2a, 0x2e, 0x2f,
                0x30, 0x34, 0x35, 0x37, 0x39, 0x3b, 0x3d, 0x3f, 0x41, 0x43,
                0x45, 0x47, 0x49, 0x4b, 0x4d, 0x4f, 0x51, 0x53, 0x55, 0x57,
                0x59, 0x5b, 0x5d, 0x5f, 0x61, 0x63, 0x65, 0x66, 0x67, 0};

        short[] RECaseFixCounts = {
                0x1, 0x5, 0x1, 0x1, 0x1, 0x4, 0x1, 0x1, 0x1, 0x1,
                0x1, 0x1, 0x4, 0x4, 0x5, 0x1, 0x9, 0x4, 0x1, 0x1,
                0x4, 0x1, 0x2, 0x2, 0x2, 0x2, 0x2, 0x2, 0x2, 0x2,
                0x2, 0x2, 0x2, 0x2, 0x2, 0x2, 0x2, 0x2, 0x2, 0x2,
                0x2, 0x2, 0x2, 0x2, 0x2, 0x2, 0x1, 0x1, 0x1, 0};

        char[] RECaseFixData = {
                0x1e9a, 0xfb00, 0xfb01, 0xfb02, 0xfb03, 0xfb04, 0x1e96, 0x130, 0x1f0, 0xdf,
                0x1e9e, 0xfb05, 0xfb06, 0x1e97, 0x1e98, 0x1e99, 0x149, 0x1fb4, 0x1fc4, 0x1fb3,
                0x1fb6, 0x1fb7, 0x1fbc, 0x1fc3, 0x1fc6, 0x1fc7, 0x1fcc, 0x390, 0x1fd2, 0x1fd3,
                0x1fd6, 0x1fd7, 0x1fe4, 0x3b0, 0x1f50, 0x1f52, 0x1f54, 0x1f56, 0x1fe2, 0x1fe3,
                0x1fe6, 0x1fe7, 0x1ff3, 0x1ff6, 0x1ff7, 0x1ffc, 0x1ff4, 0x587, 0xfb13, 0xfb14,
                0xfb15, 0xfb17, 0xfb16, 0x1f80, 0x1f88, 0x1f81, 0x1f89, 0x1f82, 0x1f8a, 0x1f83,
                0x1f8b, 0x1f84, 0x1f8c, 0x1f85, 0x1f8d, 0x1f86, 0x1f8e, 0x1f87, 0x1f8f, 0x1f90,
                0x1f98, 0x1f91, 0x1f99, 0x1f92, 0x1f9a, 0x1f93, 0x1f9b, 0x1f94, 0x1f9c, 0x1f95,
                0x1f9d, 0x1f96, 0x1f9e, 0x1f97, 0x1f9f, 0x1fa0, 0x1fa8, 0x1fa1, 0x1fa9, 0x1fa2,
                0x1faa, 0x1fa3, 0x1fab, 0x1fa4, 0x1fac, 0x1fa5, 0x1fad, 0x1fa6, 0x1fae, 0x1fa7,
                0x1faf, 0x1fb2, 0x1fc2, 0x1ff2, 0};

// End of machine generated data.

        if (c < UCharacter.MIN_VALUE || c > UCharacter.MAX_VALUE) {
            // This function should never be called with an invalid input character.
            throw new IllegalStateException();
        } else if (UCharacter.hasBinaryProperty(c, CASE_SENSITIVE)) {
            int caseFoldedC = UCharacter.foldCase(c, UCharacter.FOLD_CASE_DEFAULT);
            starterChars.set(caseFoldedC, caseFoldedC);

            int i = 0;
            while (RECaseFixCodePoints[i] < c) {
                // Simple linear search through the sorted list of interesting code points.
                i++;
            }

            if (RECaseFixCodePoints[i] == c) {
                int dataIndex = RECaseFixStringOffsets[i];
                int numCharsToAdd = RECaseFixCounts[i];
                int cpToAdd;
                for (int j = 0; j < numCharsToAdd; j++) {
                    final IndexAndChar result = U16_NEXT_UNSAFE(RECaseFixData, dataIndex);
                    dataIndex = result.i;
                    cpToAdd = result.c;
                    starterChars.add(cpToAdd);
                }
            }

            starterChars.closeOver(CASE_INSENSITIVE);
            starterChars.removeAllStrings();
        } else {
            // Not a cased character. Just return it alone.
            starterChars.set(c, c);
        }
    }


    /**
     * Increment with overflow check.
     * val and delta will both be positive.
     */
    private static int safeIncrement(final int val, final int delta) {
        if (Integer.MAX_VALUE - val > delta) {
            return val + delta;
        } else {
            return Integer.MAX_VALUE;
        }
    }


    /**
     * Determine how a match can start.<br/>
     * Used to optimize find() operations.<br/>
     * Operation is very similar to minMatchLength().  Walk the compiled
     * pattern, keeping an on-going minimum-match-length.  For any
     * op where the min match coming in is zero, add that ops possible
     * starting matches to the possible starts for the overall pattern.
     */
    private void matchStartType() {
        int loc;                    // Location in the pattern of the current op being processed.
        int op;                     // The op being processed
        UrxOps opType;                 // The opcode type of the op
        int currentLen = 0;         // Minimum length of a match to this point (loc) in the pattern
        int numInitialStrings = 0;  // Number of strings encountered that could match at start.

        boolean atStart = true;         // True if no part of the pattern yet encountered
        //   could have advanced the position in a match.
        //   (Maximum match length so far == 0)

        // forwardedLength is a vector holding minimum-match-length values that
        //   are propagated forward in the pattern by JMP or STATE_SAVE operations.
        //   It must be one longer than the pattern being checked because some  ops
        //   will jmp to a end-of-block+1 location from within a block, and we must
        //   count those when checking the block.
        int end = fRXPat.fCompiledPat.size();
        MutableVector32 forwardedLength = new MutableVector32();
        forwardedLength.setSize(end + 1);
        for (loc = 3; loc < end; loc++) {
            forwardedLength.setElementAt(Integer.MAX_VALUE, loc);
        }

        for (loc = 3; loc < end; loc++) {
            op = Math.toIntExact(fRXPat.fCompiledPat.elementAti(loc));
            opType = URX_TYPE(op);

            // The loop is advancing linearly through the pattern.
            // If the op we are now at was the destination of a branch in the pattern,
            // and that path has a shorter minimum length than the current accumulated value,
            // replace the current accumulated value.
            if (forwardedLength.elementAti(loc) < currentLen) {
                currentLen = forwardedLength.elementAti(loc);
                assert currentLen >= 0 && currentLen < Integer.MAX_VALUE;
            }

            switch (opType) {
                // Ops that don't change the total length matched
                case URX_RESERVED_OP:
                case URX_END:
                case URX_FAIL:
                case URX_STRING_LEN:
                case URX_NOP:
                case URX_START_CAPTURE:
                case URX_END_CAPTURE:
                case URX_BACKSLASH_B:
                case URX_BACKSLASH_BU:
                case URX_BACKSLASH_G:
                case URX_BACKSLASH_Z:
                case URX_DOLLAR:
                case URX_DOLLAR_M:
                case URX_DOLLAR_D:
                case URX_DOLLAR_MD:
                case URX_RELOC_OPRND:
                case URX_STO_INP_LOC:
                case URX_BACKREF:         // BackRef.  Must assume that it might be a zero length match
                case URX_BACKREF_I:

                case URX_STO_SP:          // Setup for atomic or possessive blocks.  Doesn't change what can match.
                case URX_LD_SP:
                    break;

                case URX_CARET:
                    if (atStart) {
                        fRXPat.fStartType = START_START;
                    }
                    break;

                case URX_CARET_M:
                case URX_CARET_M_UNIX:
                    if (atStart) {
                        fRXPat.fStartType = START_LINE;
                    }
                    break;

                case URX_ONECHAR:
                    if (currentLen == 0) {
                        // This character could appear at the start of a match.
                        //   Add it to the set of possible starting characters.
                        fRXPat.fInitialChars.add(URX_VAL(op));
                        numInitialStrings += 2;
                    }
                    currentLen = safeIncrement(currentLen, 1);
                    atStart = false;
                    break;


                case URX_SETREF:
                    if (currentLen == 0) {
                        int sn = URX_VAL(op);
                        assert sn > 0 && sn < fRXPat.fSets.size();
                        final UnicodeSet s = fRXPat.fSets.get(sn);
                        fRXPat.fInitialChars.addAll(s);
                        numInitialStrings += 2;
                    }
                    currentLen = safeIncrement(currentLen, 1);
                    atStart = false;
                    break;

                case URX_LOOP_SR_I:
                    // [Set]*, like a SETREF, above, in what it can match,
                    //  but may not match at all, so currentLen is not incremented.
                    if (currentLen == 0) {
                        int sn = URX_VAL(op);
                        assert sn > 0 && sn < fRXPat.fSets.size();
                        final UnicodeSet s = fRXPat.fSets.get(sn);
                        fRXPat.fInitialChars.addAll(s);
                        numInitialStrings += 2;
                    }
                    atStart = false;
                    break;

                case URX_LOOP_DOT_I:
                    if (currentLen == 0) {
                        // .* at the start of a pattern.
                        //    Any character can begin the match.
                        fRXPat.fInitialChars.clear();
                        fRXPat.fInitialChars.complement();
                        numInitialStrings += 2;
                    }
                    atStart = false;
                    break;


                case URX_STATIC_SETREF:
                    if (currentLen == 0) {
                        int sn = URX_VAL(op);
                        assert sn > 0 && sn < URX_LAST_SET.getIndex();
                        final UnicodeSet s = RegexStaticSets.INSTANCE.fPropSets[sn];
                        fRXPat.fInitialChars.addAll(s);
                        numInitialStrings += 2;
                    }
                    currentLen = safeIncrement(currentLen, 1);
                    atStart = false;
                    break;


                case URX_STAT_SETREF_N:
                    if (currentLen == 0) {
                        int sn = URX_VAL(op);
                        UnicodeSet sc = new UnicodeSet();
                        sc.addAll(RegexStaticSets.INSTANCE.fPropSets[sn]).complement();
                        fRXPat.fInitialChars.addAll(sc);
                        numInitialStrings += 2;
                    }
                    currentLen = safeIncrement(currentLen, 1);
                    atStart = false;
                    break;


                case URX_BACKSLASH_D:
                    // Digit Char
                    if (currentLen == 0) {
                        UnicodeSet s = new UnicodeSet();
                        s.applyIntPropertyValue(GENERAL_CATEGORY_MASK, Math.toIntExact(GC_ND_MASK));
                        if (URX_VAL(op) != 0) {
                            s.complement();
                        }
                        fRXPat.fInitialChars.addAll(s);
                        numInitialStrings += 2;
                    }
                    currentLen = safeIncrement(currentLen, 1);
                    atStart = false;
                    break;


                case URX_BACKSLASH_H:
                    // Horiz white space
                    if (currentLen == 0) {
                        UnicodeSet s = new UnicodeSet();
                        s.applyIntPropertyValue(GENERAL_CATEGORY_MASK, Math.toIntExact(GC_ZS_MASK));
                        s.add((int) 9);   // Tab
                        if (URX_VAL(op) != 0) {
                            s.complement();
                        }
                        fRXPat.fInitialChars.addAll(s);
                        numInitialStrings += 2;
                    }
                    currentLen = safeIncrement(currentLen, 1);
                    atStart = false;
                    break;


                case URX_BACKSLASH_R:       // Any line ending sequence
                case URX_BACKSLASH_V:       // Any line ending code point, with optional negation
                    if (currentLen == 0) {
                        UnicodeSet s = new UnicodeSet();
                        s.add((int) 0x0a, (int) 0x0d);  // add range
                        s.add((int) 0x85);
                        s.add((int) 0x2028, (int) 0x2029);
                        if (URX_VAL(op) != 0) {
                            // Complement option applies to URX_BACKSLASH_V only.
                            s.complement();
                        }
                        fRXPat.fInitialChars.addAll(s);
                        numInitialStrings += 2;
                    }
                    currentLen = safeIncrement(currentLen, 1);
                    atStart = false;
                    break;


                case URX_ONECHAR_I:
                    // Case Insensitive Single Character.
                    if (currentLen == 0) {
                        int c = URX_VAL(op);
                        if (UCharacter.hasBinaryProperty(c, CASE_SENSITIVE)) {
                            UnicodeSet starters = new UnicodeSet(c, c);
                            starters.closeOver(CASE_INSENSITIVE);
                            // findCaseInsensitiveStarters(c, &starters);
                            //   For ONECHAR_I, no need to worry about text chars that expand on folding into strings.
                            //   The expanded folding can't match the pattern.
                            fRXPat.fInitialChars.addAll(starters);
                        } else {
                            // Char has no case variants.  Just add it as-is to the
                            //   set of possible starting chars.
                            fRXPat.fInitialChars.add(c);
                        }
                        numInitialStrings += 2;
                    }
                    currentLen = safeIncrement(currentLen, 1);
                    atStart = false;
                    break;


                case URX_BACKSLASH_X:   // Grapheme Cluster.  Minimum is 1, max unbounded.
                case URX_DOTANY_ALL:    // . matches one or two.
                case URX_DOTANY:
                case URX_DOTANY_UNIX:
                    if (currentLen == 0) {
                        // These constructs are all bad news when they appear at the start
                        //   of a match.  Any character can begin the match.
                        fRXPat.fInitialChars.clear();
                        fRXPat.fInitialChars.complement();
                        numInitialStrings += 2;
                    }
                    currentLen = safeIncrement(currentLen, 1);
                    atStart = false;
                    break;


                case URX_JMPX:
                    loc++;             // Except for extra operand on URX_JMPX, same as URX_JMP.
                    // U_FALLTHROUGH;
                case URX_JMP: {
                    int jmpDest = URX_VAL(op);
                    if (jmpDest < loc) {
                        // Loop of some kind.  Can safely ignore, the worst that will happen
                        //  is that we understate the true minimum length
                        currentLen = forwardedLength.elementAti(loc + 1);

                    } else {
                        // Forward jump.  Propagate the current min length to the target loc of the jump.
                        assert jmpDest <= end + 1;
                        if (forwardedLength.elementAti(jmpDest) > currentLen) {
                            forwardedLength.setElementAt(currentLen, jmpDest);
                        }
                    }
                }
                atStart = false;
                break;

                case URX_JMP_SAV:
                case URX_JMP_SAV_X:
                    // Combo of state save to the next loc, + jmp backwards.
                    //   Net effect on min. length computation is nothing.
                    atStart = false;
                    break;

                case URX_BACKTRACK:
                    // Fails are kind of like a branch, except that the min length was
                    //   propagated already, by the state save.
                    currentLen = forwardedLength.elementAti(loc + 1);
                    atStart = false;
                    break;


                case URX_STATE_SAVE: {
                    // State Save, for forward jumps, propagate the current minimum.
                    //             of the state save.
                    int jmpDest = URX_VAL(op);
                    if (jmpDest > loc) {
                        if (currentLen < forwardedLength.elementAti(jmpDest)) {
                            forwardedLength.setElementAt(currentLen, jmpDest);
                        }
                    }
                }
                atStart = false;
                break;


                case URX_STRING: {
                    loc++;
                    int stringLenOp = Math.toIntExact(fRXPat.fCompiledPat.elementAti(loc));
                    int stringLen = URX_VAL(stringLenOp);
                    assert URX_TYPE(stringLenOp) == URX_STRING_LEN;
                    assert stringLenOp >= 2;
                    if (currentLen == 0) {
                        // Add the starting character of this string to the set of possible starting
                        //   characters for this pattern.
                        int stringStartIdx = URX_VAL(op);
                        int c = char32At(fRXPat.fLiteralText, stringStartIdx);
                        fRXPat.fInitialChars.add(c);

                        // Remember this string.  After the entire pattern has been checked,
                        //  if nothing else is identified that can start a match, we'll use it.
                        numInitialStrings++;
                        fRXPat.fInitialStringIdx = stringStartIdx;
                        fRXPat.fInitialStringLen = stringLen;
                    }

                    currentLen = safeIncrement(currentLen, stringLen);
                    atStart = false;
                }
                break;

                case URX_STRING_I: {
                    // Case-insensitive string.  Unlike exact-match strings, we won't
                    //   attempt a string search for possible match positions.  But we
                    //   do update the set of possible starting characters.
                    loc++;
                    int stringLenOp = Math.toIntExact(fRXPat.fCompiledPat.elementAti(loc));
                    int stringLen = URX_VAL(stringLenOp);
                    assert URX_TYPE(stringLenOp) == URX_STRING_LEN;
                    assert stringLenOp >= 2;
                    if (currentLen == 0) {
                        // Add the starting character of this string to the set of possible starting
                        //   characters for this pattern.
                        int stringStartIdx = URX_VAL(op);
                        int c = char32At(fRXPat.fLiteralText, stringStartIdx);
                        UnicodeSet s = new UnicodeSet();
                        findCaseInsensitiveStarters(c, s);
                        fRXPat.fInitialChars.addAll(s);
                        numInitialStrings += 2;  // Matching on an initial string not possible.
                    }
                    currentLen = safeIncrement(currentLen, stringLen);
                    atStart = false;
                }
                break;

                case URX_CTR_INIT:
                case URX_CTR_INIT_NG: {
                    // Loop Init Ops.  These don't change the min length, but they are 4 word ops
                    //   so location must be updated accordingly.
                    // Loop Init Ops.
                    //   If the min loop count == 0
                    //      move loc forwards to the end of the loop, skipping over the body.
                    //   If the min count is > 0,
                    //      continue normal processing of the body of the loop.
                    int loopEndLoc = Math.toIntExact(fRXPat.fCompiledPat.elementAti(loc + 1));
                    loopEndLoc = URX_VAL(loopEndLoc);
                    int minLoopCount = Math.toIntExact(fRXPat.fCompiledPat.elementAti(loc + 2));
                    if (minLoopCount == 0) {
                        // Min Loop Count of 0, treat like a forward branch and
                        //   move the current minimum length up to the target
                        //   (end of loop) location.
                        assert loopEndLoc <= end + 1;
                        if (forwardedLength.elementAti(loopEndLoc) > currentLen) {
                            forwardedLength.setElementAt(currentLen, loopEndLoc);
                        }
                    }
                    loc += 3;  // Skips over operands of CTR_INIT
                }
                atStart = false;
                break;


                case URX_CTR_LOOP:
                case URX_CTR_LOOP_NG:
                    // Loop ops.
                    //  The jump is conditional, backwards only.
                    atStart = false;
                    break;

                case URX_LOOP_C:
                    // More loop ops.  These state-save to themselves.
                    //   don't change the minimum match
                    atStart = false;
                    break;


                case URX_LA_START:
                case URX_LB_START: {
                    // Look-around.  Scan forward until the matching look-ahead end,
                    //   without processing the look-around block.  This is overly pessimistic.

                    // Keep track of the nesting depth of look-around blocks.  Boilerplate code for
                    //   lookahead contains two LA_END instructions, so count goes up by two
                    //   for each LA_START.
                    int depth = (opType == URX_LA_START ? 2 : 1);
                    for (; ; ) {
                        loc++;
                        op = Math.toIntExact(fRXPat.fCompiledPat.elementAti(loc));
                        if (URX_TYPE(op) == URX_LA_START) {
                            depth += 2;
                        }
                        if (URX_TYPE(op) == URX_LB_START) {
                            depth++;
                        }
                        if (URX_TYPE(op) == URX_LA_END || URX_TYPE(op) == URX_LBN_END) {
                            depth--;
                            if (depth == 0) {
                                break;
                            }
                        }
                        if (URX_TYPE(op) == URX_STATE_SAVE) {
                            // Need this because neg lookahead blocks will FAIL to outside
                            //   of the block.
                            int jmpDest = URX_VAL(op);
                            if (jmpDest > loc) {
                                if (currentLen < forwardedLength.elementAti(jmpDest)) {
                                    forwardedLength.setElementAt(currentLen, jmpDest);
                                }
                            }
                        }
                        assert loc <= end;
                    }
                }
                break;

                case URX_LA_END:
                case URX_LB_CONT:
                case URX_LB_END:
                case URX_LBN_CONT:
                case URX_LBN_END:
                    throw new IllegalStateException();  // Shouldn't get here.  These ops should be
//  consumed by the scan in URX_LA_START and LB_START
                default:
                    throw new IllegalStateException();
            }

        }


        // We have finished walking through the ops.  Check whether some forward jump
        //   propagated a shorter length to location end+1.
        assert forwardedLength.size() == end + 1;
        // As the above assertion holds in this method, elementAti(end+1) from Icu4C would return 0
        if (0 < currentLen) {
            currentLen = 0;
        }

        // Sort out what we should check for when looking for candidate match start positions.
        // In order of preference,
        //     1.   Start of input text buffer.
        //     2.   A literal string.
        //     3.   Start of line in multi-line mode.
        //     4.   A single literal character.
        //     5.   A character from a set of characters.
        //
        if (fRXPat.fStartType == START_START) {
            // Match only at the start of an input text string.
            //    start type is already set.  We're done.
        } else if (numInitialStrings == 1 && fRXPat.fMinMatchLen > 0) {
            // Match beginning only with a literal string.
            int c = char32At(fRXPat.fLiteralText, fRXPat.fInitialStringIdx);
            assert fRXPat.fInitialChars.contains(c);
            fRXPat.fStartType = START_STRING;
            fRXPat.fInitialChar = c;
        } else if (fRXPat.fStartType == START_LINE) {
            // Match at start of line in Multi-Line mode.
            // Nothing to do here; everything is already set.
        } else if (fRXPat.fMinMatchLen == 0) {
            // Zero length match possible.  We could start anywhere.
            fRXPat.fStartType = START_NO_INFO;
        } else if (fRXPat.fInitialChars.size() == 1) {
            // All matches begin with the same char.
            fRXPat.fStartType = START_CHAR;
            fRXPat.fInitialChar = fRXPat.fInitialChars.charAt(0);
            assert fRXPat.fInitialChar != (int) -1;
        } else if (fRXPat.fInitialChars.contains((int) 0, (int) 0x10ffff) == false &&
                fRXPat.fMinMatchLen > 0) {
            // Matches start with a set of character smaller than the set of all chars.
            fRXPat.fStartType = START_SET;
        } else {
            // Matches can start with anything
            fRXPat.fStartType = START_NO_INFO;
        }

        return;
    }


    /**
     * Calculate the length of the shortest string that could
     * match the specified pattern.<br/>
     * Length is in 16 bit code units, not code points.<br/>
     * The calculated length may not be exact.  The returned
     * value may be shorter than the actual minimum; it must
     * never be longer.<br/>
     * start and end are the range of p-code operations to be
     * examined.  The endpoints are included in the range.
     */
    private int minMatchLength(final int start, final int end) {
        assert start <= end;
        assert end < fRXPat.fCompiledPat.size();


        int loc;
        int op;
        UrxOps opType;
        int currentLen = 0;


        // forwardedLength is a vector holding minimum-match-length values that
        //   are propagated forward in the pattern by JMP or STATE_SAVE operations.
        //   It must be one longer than the pattern being checked because some  ops
        //   will jmp to a end-of-block+1 location from within a block, and we must
        //   count those when checking the block.
        MutableVector32 forwardedLength = new MutableVector32();
        forwardedLength.setSize(end + 2);
        for (loc = start; loc <= end + 1; loc++) {
            forwardedLength.setElementAt(Integer.MAX_VALUE, loc);
        }

        for (loc = start; loc <= end; loc++) {
            op = Math.toIntExact(fRXPat.fCompiledPat.elementAti(loc));
            opType = URX_TYPE(op);

            // The loop is advancing linearly through the pattern.
            // If the op we are now at was the destination of a branch in the pattern,
            // and that path has a shorter minimum length than the current accumulated value,
            // replace the current accumulated value.
            // assert(currentLen>=0 && currentLen < Integer.MAX_VALUE);  // MinLength == Integer.MAX_VALUE for some
            //   no-match-possible cases.
            if (forwardedLength.elementAti(loc) < currentLen) {
                currentLen = forwardedLength.elementAti(loc);
                assert currentLen >= 0 && currentLen < Integer.MAX_VALUE;
            }

            switch (opType) {
                // Ops that don't change the total length matched
                case URX_RESERVED_OP:
                case URX_END:
                case URX_STRING_LEN:
                case URX_NOP:
                case URX_START_CAPTURE:
                case URX_END_CAPTURE:
                case URX_BACKSLASH_B:
                case URX_BACKSLASH_BU:
                case URX_BACKSLASH_G:
                case URX_BACKSLASH_Z:
                case URX_CARET:
                case URX_DOLLAR:
                case URX_DOLLAR_M:
                case URX_DOLLAR_D:
                case URX_DOLLAR_MD:
                case URX_RELOC_OPRND:
                case URX_STO_INP_LOC:
                case URX_CARET_M:
                case URX_CARET_M_UNIX:
                case URX_BACKREF:         // BackRef.  Must assume that it might be a zero length match
                case URX_BACKREF_I:

                case URX_STO_SP:          // Setup for atomic or possessive blocks.  Doesn't change what can match.
                case URX_LD_SP:

                case URX_JMP_SAV:
                case URX_JMP_SAV_X:
                    break;


                // Ops that match a minimum of one character (one or two 16 bit code units.)
                //
                case URX_ONECHAR:
                case URX_STATIC_SETREF:
                case URX_STAT_SETREF_N:
                case URX_SETREF:
                case URX_BACKSLASH_D:
                case URX_BACKSLASH_H:
                case URX_BACKSLASH_R:
                case URX_BACKSLASH_V:
                case URX_ONECHAR_I:
                case URX_BACKSLASH_X:   // Grapheme Cluster.  Minimum is 1, max unbounded.
                case URX_DOTANY_ALL:    // . matches one or two.
                case URX_DOTANY:
                case URX_DOTANY_UNIX:
                    currentLen = safeIncrement(currentLen, 1);
                    break;


                case URX_JMPX:
                    loc++;              // URX_JMPX has an extra operand, ignored here,
                    //   otherwise processed identically to URX_JMP.
                    //U_FALLTHROUGH;
                case URX_JMP: {
                    int jmpDest = URX_VAL(op);
                    if (jmpDest < loc) {
                        // Loop of some kind.  Can safely ignore, the worst that will happen
                        //  is that we understate the true minimum length
                        currentLen = forwardedLength.elementAti(loc + 1);
                    } else {
                        // Forward jump.  Propagate the current min length to the target loc of the jump.
                        assert jmpDest <= end + 1;
                        if (forwardedLength.elementAti(jmpDest) > currentLen) {
                            forwardedLength.setElementAt(currentLen, jmpDest);
                        }
                    }
                }
                break;

                case URX_BACKTRACK: {
                    // Back-tracks are kind of like a branch, except that the min length was
                    //   propagated already, by the state save.
                    currentLen = forwardedLength.elementAti(loc + 1);
                }
                break;


                case URX_STATE_SAVE: {
                    // State Save, for forward jumps, propagate the current minimum.
                    //             of the state save.
                    int jmpDest = URX_VAL(op);
                    if (jmpDest > loc) {
                        if (currentLen < forwardedLength.elementAti(jmpDest)) {
                            forwardedLength.setElementAt(currentLen, jmpDest);
                        }
                    }
                }
                break;


                case URX_STRING: {
                    loc++;
                    int stringLenOp = Math.toIntExact(fRXPat.fCompiledPat.elementAti(loc));
                    currentLen = safeIncrement(currentLen, URX_VAL(stringLenOp));
                }
                break;


                case URX_STRING_I: {
                    loc++;
                    // TODO: with full case folding, matching input text may be shorter than
                    //       the string we have here.  More smarts could put some bounds on it.
                    //       Assume a min length of one for now.  A min length of zero causes
                    //        optimization failures for a pattern like "string"+
                    // currentLen += URX_VAL(stringLenOp);
                    currentLen = safeIncrement(currentLen, 1);
                }
                break;

                case URX_CTR_INIT:
                case URX_CTR_INIT_NG: {
                    // Loop Init Ops.
                    //   If the min loop count == 0
                    //      move loc forwards to the end of the loop, skipping over the body.
                    //   If the min count is > 0,
                    //      continue normal processing of the body of the loop.
                    int loopEndLoc = Math.toIntExact(fRXPat.fCompiledPat.elementAti(loc + 1));
                    loopEndLoc = URX_VAL(loopEndLoc);
                    int minLoopCount = Math.toIntExact(fRXPat.fCompiledPat.elementAti(loc + 2));
                    if (minLoopCount == 0) {
                        loc = loopEndLoc;
                    } else {
                        loc += 3;  // Skips over operands of CTR_INIT
                    }
                }
                break;


                case URX_CTR_LOOP:
                case URX_CTR_LOOP_NG:
                    // Loop ops.
                    //  The jump is conditional, backwards only.
                    break;

                case URX_LOOP_SR_I:
                case URX_LOOP_DOT_I:
                case URX_LOOP_C:
                    // More loop ops.  These state-save to themselves.
                    //   don't change the minimum match - could match nothing at all.
                    break;


                case URX_LA_START:
                case URX_LB_START: {
                    // Look-around.  Scan forward until the matching look-ahead end,
                    //   without processing the look-around block.  This is overly pessimistic for look-ahead,
                    //   it assumes that the look-ahead match might be zero-length.
                    //   TODO:  Positive lookahead could recursively do the block, then continue
                    //          with the longer of the block or the value coming in.  Ticket 6060
                    int depth = (opType == URX_LA_START ? 2 : 1);
                    for (; ; ) {
                        loc++;
                        op = Math.toIntExact(fRXPat.fCompiledPat.elementAti(loc));
                        if (URX_TYPE(op) == URX_LA_START) {
                            // The boilerplate for look-ahead includes two LA_END instructions,
                            //    Depth will be decremented by each one when it is seen.
                            depth += 2;
                        }
                        if (URX_TYPE(op) == URX_LB_START) {
                            depth++;
                        }
                        if (URX_TYPE(op) == URX_LA_END) {
                            depth--;
                            if (depth == 0) {
                                break;
                            }
                        }
                        if (URX_TYPE(op) == URX_LBN_END) {
                            depth--;
                            if (depth == 0) {
                                break;
                            }
                        }
                        if (URX_TYPE(op) == URX_STATE_SAVE) {
                            // Need this because neg lookahead blocks will FAIL to outside
                            //   of the block.
                            int jmpDest = URX_VAL(op);
                            if (jmpDest > loc) {
                                if (currentLen < forwardedLength.elementAti(jmpDest)) {
                                    forwardedLength.setElementAt(currentLen, jmpDest);
                                }
                            }
                        }
                        assert loc <= end;
                    }
                }
                break;

                case URX_LA_END:
                case URX_LB_CONT:
                case URX_LB_END:
                case URX_LBN_CONT:
                case URX_LBN_END:
                    // Only come here if the matching URX_LA_START or URX_LB_START was not in the
                    //   range being sized, which happens when measuring size of look-behind blocks.
                    break;

                default:
                    throw new IllegalStateException();
            }

        }

        // We have finished walking through the ops.  Check whether some forward jump
        //   propagated a shorter length to location end+1.
        if (forwardedLength.elementAti(end + 1) < currentLen) {
            currentLen = forwardedLength.elementAti(end + 1);
            assert currentLen >= 0 && currentLen < Integer.MAX_VALUE;
        }

        return currentLen;
    }


    /**
     * Calculate the length of the longest string that could
     * match the specified pattern.<br/>
     * Length is in 16 bit code units, not code points.
     * The calculated length may not be exact.  The returned
     * value may be longer than the actual maximum; it must
     * never be shorter.<br/>
     * start, end: the range of the pattern to check.
     * end is inclusive.
     */
    private int maxMatchLength(final int start, final int end) {
        assert start <= end;
        assert end < fRXPat.fCompiledPat.size();

        int loc;
        int op;
        UrxOps opType;
        int currentLen = 0;
        MutableVector32 forwardedLength = new MutableVector32();
        forwardedLength.setSize(end + 1);

        for (loc = start; loc <= end; loc++) {
            forwardedLength.setElementAt(0, loc);
        }

        for (loc = start; loc <= end; loc++) {
            op = Math.toIntExact(fRXPat.fCompiledPat.elementAti(loc));
            opType = URX_TYPE(op);

            // The loop is advancing linearly through the pattern.
            // If the op we are now at was the destination of a branch in the pattern,
            // and that path has a longer maximum length than the current accumulated value,
            // replace the current accumulated value.
            if (forwardedLength.elementAti(loc) > currentLen) {
                currentLen = forwardedLength.elementAti(loc);
            }

            switch (opType) {
                // Ops that don't change the total length matched
                case URX_RESERVED_OP:
                case URX_END:
                case URX_STRING_LEN:
                case URX_NOP:
                case URX_START_CAPTURE:
                case URX_END_CAPTURE:
                case URX_BACKSLASH_B:
                case URX_BACKSLASH_BU:
                case URX_BACKSLASH_G:
                case URX_BACKSLASH_Z:
                case URX_CARET:
                case URX_DOLLAR:
                case URX_DOLLAR_M:
                case URX_DOLLAR_D:
                case URX_DOLLAR_MD:
                case URX_RELOC_OPRND:
                case URX_STO_INP_LOC:
                case URX_CARET_M:
                case URX_CARET_M_UNIX:

                case URX_STO_SP:          // Setup for atomic or possessive blocks.  Doesn't change what can match.
                case URX_LD_SP:

                case URX_LB_END:
                case URX_LB_CONT:
                case URX_LBN_CONT:
                case URX_LBN_END:
                    break;


                // Ops that increase that cause an unbounded increase in the length
                //   of a matched string, or that increase it a hard to characterize way.
                //   Call the max length unbounded, and stop further checking.
                case URX_BACKREF:         // BackRef.  Must assume that it might be a zero length match
                case URX_BACKREF_I:
                case URX_BACKSLASH_X:   // Grapheme Cluster.  Minimum is 1, max unbounded.
                    currentLen = Integer.MAX_VALUE;
                    break;


                // Ops that match a max of one character (possibly two 16 bit code units.)
                //
                case URX_STATIC_SETREF:
                case URX_STAT_SETREF_N:
                case URX_SETREF:
                case URX_BACKSLASH_D:
                case URX_BACKSLASH_H:
                case URX_BACKSLASH_R:
                case URX_BACKSLASH_V:
                case URX_ONECHAR_I:
                case URX_DOTANY_ALL:
                case URX_DOTANY:
                case URX_DOTANY_UNIX:
                    currentLen = safeIncrement(currentLen, 2);
                    break;

                // Single literal character.  Increase current max length by one or two,
                //       depending on whether the char is in the supplementary range.
                case URX_ONECHAR:
                    currentLen = safeIncrement(currentLen, 1);
                    if (URX_VAL(op) > 0x10000) {
                        currentLen = safeIncrement(currentLen, 1);
                    }
                    break;

                // Jumps.
                //
                case URX_JMP:
                case URX_JMPX:
                case URX_JMP_SAV:
                case URX_JMP_SAV_X: {
                    int jmpDest = URX_VAL(op);
                    if (jmpDest < loc) {
                        // Loop of some kind.  Max match length is unbounded.
                        currentLen = Integer.MAX_VALUE;
                    } else {
                        // Forward jump.  Propagate the current min length to the target loc of the jump.
                        if (forwardedLength.elementAti(jmpDest) < currentLen) {
                            forwardedLength.setElementAt(currentLen, jmpDest);
                        }
                        currentLen = 0;
                    }
                }
                break;

                case URX_BACKTRACK:
                    // back-tracks are kind of like a branch, except that the max length was
                    //   propagated already, by the state save.
                    currentLen = forwardedLength.elementAti(loc + 1);
                    break;


                case URX_STATE_SAVE: {
                    // State Save, for forward jumps, propagate the current minimum.
                    //               of the state save.
                    //             For backwards jumps, they create a loop, maximum
                    //               match length is unbounded.
                    int jmpDest = URX_VAL(op);
                    if (jmpDest > loc) {
                        if (currentLen > forwardedLength.elementAti(jmpDest)) {
                            forwardedLength.setElementAt(currentLen, jmpDest);
                        }
                    } else {
                        currentLen = Integer.MAX_VALUE;
                    }
                }
                break;


                case URX_STRING: {
                    loc++;
                    int stringLenOp = Math.toIntExact(fRXPat.fCompiledPat.elementAti(loc));
                    currentLen = safeIncrement(currentLen, URX_VAL(stringLenOp));
                    break;
                }

                case URX_STRING_I:
                    // TODO:  This code assumes that any user string that matches will be no longer
                    //        than our compiled string, with case insensitive matching.
                    //        Our compiled string has been case-folded already.
                    //
                    //        Any matching user string will have no more code points than our
                    //        compiled (folded) string.  Folding may add code points, but
                    //        not remove them.
                    //
                    //        There is a potential problem if a supplemental code point
                    //        case-folds to a BMP code point.  In this case our compiled string
                    //        could be shorter (in code units) than a matching user string.
                    //
                    //        At this time (Unicode 6.1) there are no such characters, and this case
                    //        is not being handled.  A test, intltest regex/Bug9283, will fail if
                    //        any problematic characters are added to Unicode.
                    //
                    //        If this happens, we can make a set of the BMP chars that the
                    //        troublesome supplementals fold to, scan our string, and bump the
                    //        currentLen one extra for each that is found.
                    //
                {
                    loc++;
                    int stringLenOp = Math.toIntExact(fRXPat.fCompiledPat.elementAti(loc));
                    currentLen = safeIncrement(currentLen, URX_VAL(stringLenOp));
                }
                break;

                case URX_CTR_INIT:
                case URX_CTR_INIT_NG:
                    // For Loops, recursively call this function on the pattern for the loop body,
                    //   then multiply the result by the maximum loop count.
                {
                    int loopEndLoc = URX_VAL(Math.toIntExact(fRXPat.fCompiledPat.elementAti(loc + 1)));
                    if (loopEndLoc == loc + 4) {
                        // Loop has an empty body. No affect on max match length.
                        // Continue processing with code after the loop end.
                        loc = loopEndLoc;
                        break;
                    }

                    int maxLoopCount = Math.toIntExact((fRXPat.fCompiledPat.elementAti(loc + 3)));
                    if (maxLoopCount == -1) {
                        // Unbounded Loop. No upper bound on match length.
                        currentLen = Integer.MAX_VALUE;
                        break;
                    }

                    assert loopEndLoc >= loc + 4;
                    long blockLen = maxMatchLength(loc + 4, loopEndLoc - 1);  // Recursive call.
                    long updatedLen = (long) currentLen + blockLen * maxLoopCount;
                    if (updatedLen >= Integer.MAX_VALUE) {
                        currentLen = Integer.MAX_VALUE;
                        break;
                    }
                    currentLen = Math.toIntExact(updatedLen);
                    loc = loopEndLoc;
                    break;
                }

                case URX_CTR_LOOP:
                case URX_CTR_LOOP_NG:
                    // These opcodes will be skipped over by code for URX_CTR_INIT.
                    // We shouldn't encounter them here.
                    throw new IllegalStateException();

                case URX_LOOP_SR_I:
                case URX_LOOP_DOT_I:
                case URX_LOOP_C:
                    // For anything to do with loops, make the match length unbounded.
                    currentLen = Integer.MAX_VALUE;
                    break;


                case URX_LA_START:
                case URX_LA_END:
                    // Look-ahead.  Just ignore, treat the look-ahead block as if
                    // it were normal pattern.  Gives a too-long match length,
                    //  but good enough for now.
                    break;

                // End of look-ahead ops should always be consumed by the processing at
                //  the URX_LA_START op.
                // throw new IllegalStateException();

                case URX_LB_START: {
                    // Look-behind.  Scan forward until the matching look-around end,
                    //   without processing the look-behind block.
                    int dataLoc = URX_VAL(op);
                    for (loc = loc + 1; loc <= end; ++loc) {
                        op = Math.toIntExact(fRXPat.fCompiledPat.elementAti(loc));
                        UrxOps opType1 = URX_TYPE(op);
                        if ((opType1 == URX_LA_END || opType1 == URX_LBN_END) && (URX_VAL(op) == dataLoc)) {
                            break;
                        }
                    }
                    assert loc <= end;
                }
                break;

                default:
                    throw new IllegalStateException();
            }


            if (currentLen == Integer.MAX_VALUE) {
                //  The maximum length is unbounded.
                //  Stop further processing of the pattern.
                break;
            }

        }
        return currentLen;

    }


    /**
     * Remove any NOP operations from the compiled pattern code.<br/>
     * Extra NOPs are inserted for some constructs during the initial
     * code generation to provide locations that may be patched later.
     * Many end up unneeded, and are removed by this function.<br/>
     * In order to minimize the number of passes through the pattern,
     * back-reference fixup is also performed here (adjusting
     * back-reference operands to point to the correct frame offsets).
     */
    private void stripNOPs() {
        int end = fRXPat.fCompiledPat.size();
        MutableVector32 deltas = new MutableVector32();

        // Make a first pass over the code, computing the amount that things
        //   will be offset at each location in the original code.
        int loc;
        int d = 0;
        for (loc = 0; loc < end; loc++) {
            deltas.addElement(d);
            int op = Math.toIntExact(fRXPat.fCompiledPat.elementAti(loc));
            if (URX_TYPE(op) == URX_NOP) {
                d++;
            }
        }

        // Make a second pass over the code, removing the NOPs by moving following
        //  code up, and patching operands that refer to code locations that
        //  are being moved.  The array of offsets from the first step is used
        //  to compute the new operand values.
        int src;
        int dst = 0;
        for (src = 0; src < end; src++) {
            int op = Math.toIntExact(fRXPat.fCompiledPat.elementAti(src));
            UrxOps opType = URX_TYPE(op);
            switch (opType) {
                case URX_NOP:
                    break;

                case URX_STATE_SAVE:
                case URX_JMP:
                case URX_CTR_LOOP:
                case URX_CTR_LOOP_NG:
                case URX_RELOC_OPRND:
                case URX_JMPX:
                case URX_JMP_SAV:
                case URX_JMP_SAV_X:
                    // These are instructions with operands that refer to code locations.
                {
                    int operandAddress = URX_VAL(op);
                    assert operandAddress >= 0 && operandAddress < deltas.size();
                    int fixedOperandAddress = operandAddress - deltas.elementAti(operandAddress);
                    op = buildOp(opType, fixedOperandAddress);
                    fRXPat.fCompiledPat.setElementAt((long) op, dst);
                    dst++;
                    break;
                }

                case URX_BACKREF:
                case URX_BACKREF_I: {
                    int where = URX_VAL(op);
                    if (where > fRXPat.fGroupMap.size()) {
                        throw error(UErrorCode.U_REGEX_INVALID_BACK_REF, null);
//                        break;
                    }
                    where = fRXPat.fGroupMap.elementAti(where - 1);
                    op = buildOp(opType, where);
                    fRXPat.fCompiledPat.setElementAt((long) op, dst);
                    dst++;

                    fRXPat.fNeedsAltInput = true;
                    break;
                }
                case URX_RESERVED_OP:
                case URX_RESERVED_OP_N:
                case URX_BACKTRACK:
                case URX_END:
                case URX_ONECHAR:
                case URX_STRING:
                case URX_STRING_LEN:
                case URX_START_CAPTURE:
                case URX_END_CAPTURE:
                case URX_STATIC_SETREF:
                case URX_STAT_SETREF_N:
                case URX_SETREF:
                case URX_DOTANY:
                case URX_FAIL:
                case URX_BACKSLASH_B:
                case URX_BACKSLASH_BU:
                case URX_BACKSLASH_G:
                case URX_BACKSLASH_X:
                case URX_BACKSLASH_Z:
                case URX_DOTANY_ALL:
                case URX_BACKSLASH_D:
                case URX_CARET:
                case URX_DOLLAR:
                case URX_CTR_INIT:
                case URX_CTR_INIT_NG:
                case URX_DOTANY_UNIX:
                case URX_STO_SP:
                case URX_LD_SP:
                case URX_STO_INP_LOC:
                case URX_LA_START:
                case URX_LA_END:
                case URX_ONECHAR_I:
                case URX_STRING_I:
                case URX_DOLLAR_M:
                case URX_CARET_M:
                case URX_CARET_M_UNIX:
                case URX_LB_START:
                case URX_LB_CONT:
                case URX_LB_END:
                case URX_LBN_CONT:
                case URX_LBN_END:
                case URX_LOOP_SR_I:
                case URX_LOOP_DOT_I:
                case URX_LOOP_C:
                case URX_DOLLAR_D:
                case URX_DOLLAR_MD:
                case URX_BACKSLASH_H:
                case URX_BACKSLASH_R:
                case URX_BACKSLASH_V:
                    // These instructions are unaltered by the relocation.
                    fRXPat.fCompiledPat.setElementAt((long) op, dst);
                    dst++;
                    break;

                default:
                    // Some op is unaccounted for.
                    throw new IllegalStateException();
            }
        }

        fRXPat.fCompiledPat.setSize(dst);
    }

    /**
     * Error         Report a rule parse error.
     */
    private RuntimeException error(final UErrorCode errorCode, /* nullable */ final RuntimeException cause) {
        // Hmm. fParseErr (UParseError) line & offset fields are int in public
        // API (see common/unicode/parseerr.h), while fLineNum and fCharNum are
        // long. If the values of the latter are out of range for the former,
        // set them to the appropriate "field not supported" values.
        int fParseErrLine;
        int fParseErrOffset;
        if (fLineNum > 0x7FFFFFFF) {
            fParseErrLine = 0;
            fParseErrOffset = -1;
        } else if (fCharNum > 0x7FFFFFFF) {
            fParseErrLine = Math.toIntExact(fLineNum);
            fParseErrOffset = -1;
        } else {
            fParseErrLine = Math.toIntExact(fLineNum);
            fParseErrOffset = Math.toIntExact(fCharNum);
        }

        // Fill in the context.
        //   Note: extractBetween() pins supplied indices to the string bounds.
        char[] fParseErrPreContext = new char[U_PARSE_CONTEXT_LEN];
        char[] fParseErrPostContext = new char[U_PARSE_CONTEXT_LEN];
        fRXPat.fPattern.getChars(
                Util.lowerBounded(Math.toIntExact(fScanIndex - U_PARSE_CONTEXT_LEN + 1), 0), Math.toIntExact(fScanIndex),
                fParseErrPreContext, 0
        );
        fRXPat.fPattern.getChars(
                Math.toIntExact(fScanIndex),
                Util.upperBounded(Math.toIntExact(fScanIndex + U_PARSE_CONTEXT_LEN - 1), fRXPat.fPattern.chunkLength),
                fParseErrPostContext, 0
        );
        throw new RegexParseException(
                errorCode,
                fParseErrLine,
                fParseErrOffset,
                fParseErrPreContext,
                fParseErrPostContext,
                cause
        );
    }


//
//  Assorted Unicode character constants.
//     Numeric because there is no portable way to enter them as literals.
//     (Think EBCDIC).
//

    /**
     * New lines, for terminating comments.
     */
    private static final char chCR = 0x0d;
    /**
     * Line Feed
     */
    private static final char chLF = 0x0a;
    /**
     * '#', introduces a comment.
     */
    private static final char chPound = 0x23;
    /**
     * '0'
     */
    private static final char chDigit0 = 0x30;
    /**
     * '9'
     */
    private static final char chDigit7 = 0x37;
    /**
     * ':'
     */
    private static final char chColon = 0x3A;
    /**
     * 'E'
     */
    private static final char chE = 0x45;
    /**
     * 'Q'
     */
    private static final char chQ = 0x51;
    //static final char   chN         = 0x4E;      // 'N'

    /**
     * 'P'
     */
    private static final char chP = 0x50;
    /**
     * '\'  introduces a char escape
     */
    private static final char chBackSlash = 0x5c;
//static final char   chLBracket  = 0x5b;      // '['

    /**
     * ']'
     */
    private static final char chRBracket = 0x5d;
    /**
     * '^'
     */
    private static final char chUp = 0x5e;
    private static final char chLowerP = 0x70;
    /**
     * '{'
     */
    private static final char chLBrace = 0x7b;
    /**
     * '}'
     */
    private static final char chRBrace = 0x7d;
    /**
     * NEL newline variant
     */
    private static final char chNEL = 0x85;
    /**
     * Unicode Line Separator
     */
    private static final char chLS = 0x2028;


    /**
     * Low Level Next Char from the regex pattern.<br/>
     * Get a char from the string, keep track of input position
     * for error reporting.
     */
    private int nextCharLL() {
        int ch;

        if (fPeekChar != -1) {
            ch = fPeekChar;
            fPeekChar = -1;
            return ch;
        }

        // assume we're already in the right place
        ch = Util.utext_next32(fRXPat.fPattern);
        if (ch == U_SENTINEL) {
            return ch;
        }

        if (ch == chCR ||
                ch == chNEL ||
                ch == chLS ||
                (ch == chLF && fLastChar != chCR)) {
            // Character is starting a new line.  Bump up the line number, and
            //  reset the column to 0.
            fLineNum++;
            fCharNum = 0;
        } else {
            // Character is not starting a new line.  Except in the case of a
            //   LF following a CR, increment the column position.
            if (ch != chLF) {
                fCharNum++;
            }
        }
        fLastChar = ch;
        return ch;
    }


    /**
     * Low Level Character Scanning, sneak a peek at the next
     * character without actually getting it.
     */
    private int peekCharLL() {
        if (fPeekChar == -1) {
            fPeekChar = nextCharLL();
        }
        return fPeekChar;
    }


    /**
     * for pattern scanning.  At this level, we handle stripping
     * out comments and processing some backslash character escapes.<br/>
     * The rest of the pattern grammar is handled at the next level up.
     */
    private void nextChar(final RegexPatternChar c) {
        tailRecursion:
        while (true) {
            fScanIndex = Util.utext_getNativeIndex(fRXPat.fPattern);
            c.fChar = nextCharLL();
            c.fQuoted = false;

            if (fQuoteMode) {
                c.fQuoted = true;
                if ((c.fChar == chBackSlash && peekCharLL() == chE && ((fModeFlags & UREGEX_LITERAL.flag) == 0)) ||
                        c.fChar == (int) -1) {
                    fQuoteMode = false;  //  Exit quote mode,
                    nextCharLL();        // discard the E
                    // nextChar(c);      // recurse to get the real next char
                    continue tailRecursion;  // Note: fuzz testing produced testcases that
                    //       resulted in stack overflow here.
                }
            } else if (fInBackslashQuote) {
                // The current character immediately follows a '\'
                // Don't check for any further escapes, just return it as-is.
                // Don't set c.fQuoted, because that would prevent the state machine from
                //    dispatching on the character.
                fInBackslashQuote = false;
            } else {
                // We are not in a \Q quoted region \E of the source.
                //
                if ((fModeFlags & UREGEX_COMMENTS.flag) != 0) {
                    //
                    // We are in free-spacing and comments mode.
                    //  Scan through any white space and comments, until we
                    //  reach a significant character or the end of input.
                    for (; ; ) {
                        if (c.fChar == (int) -1) {
                            break;     // End of Input
                        }
                        if (c.fChar == chPound && fEOLComments) {
                            // Start of a comment.  Consume the rest of it, until EOF or a new line
                            for (; ; ) {
                                c.fChar = nextCharLL();
                                if (c.fChar == (int) -1 ||  // EOF
                                        c.fChar == chCR ||
                                        c.fChar == chLF ||
                                        c.fChar == chNEL ||
                                        c.fChar == chLS) {
                                    break;
                                }
                            }
                        }
                        // TODO:  check what Java & Perl do with non-ASCII white spaces.  Ticket 6061.
                        if (PatternProps.isWhiteSpace(c.fChar) == false) {
                            break;
                        }
                        c.fChar = nextCharLL();
                    }
                }

                //
                //  check for backslash escaped characters.
                //
                if (c.fChar == chBackSlash) {
                    long pos = Util.utext_getNativeIndex(fRXPat.fPattern);
                    if (RegexStaticSets.INSTANCE.fUnescapeCharSet.contains(peekCharLL())) {
                        //
                        // A '\' sequence that should be handled by ICU's standard unescapeAt function.
                        //   Includes \\uxxxx, \n, \r, many others.
                        //   Return the single equivalent character.
                        //
                        nextCharLL();                 // get & discard the peeked char.
                        c.fQuoted = true;

                        if (UTEXT_FULL_TEXT_IN_CHUNK(fRXPat.fPattern, fPatternLength)) {
                            int endIndex = Math.toIntExact(pos);
                            CodeAndOffset unescapeResult = u_unescapeAt(endIndex, Math.toIntExact(fPatternLength), fRXPat.fPattern.targetString.toCharArray());
                            c.fChar = unescapeResult.code;
                            endIndex = unescapeResult.newOffset;

                            if (endIndex == pos) {
                                throw error(UErrorCode.U_REGEX_BAD_ESCAPE_SEQUENCE, null);
                            }
                            fCharNum += endIndex - pos;
                            Util.utext_setNativeIndex(fRXPat.fPattern, endIndex);
                        } else {
                            int offset = 0;
                            Util.utext_setNativeIndex(fRXPat.fPattern, Math.toIntExact(pos));
                            CodeAndOffset unescapeResult = u_unescapeAt(offset, fRXPat.fPattern);
                            c.fChar = unescapeResult.code;
                            offset = unescapeResult.newOffset;
                            // TODO: what values should 'context.lastOffset' and 'offset' vars have at this point?

                            if (offset == 0) {
                                throw error(UErrorCode.U_REGEX_BAD_ESCAPE_SEQUENCE, null);
                            }
                            utext_moveIndex32(fRXPat.fPattern, offset);
                            fCharNum += offset;
                        }
                    } else if (peekCharLL() == chDigit0) {
                        //  Octal Escape, using Java Regexp Conventions
                        //    which are \0 followed by 1-3 octal digits.
                        //    Different from ICU Unescape handling of Octal, which does not
                        //    require the leading 0.
                        //  Java also has the convention of only consuming 2 octal digits if
                        //    the three digit number would be > 0xff
                        //
                        c.fChar = 0;
                        nextCharLL();    // Consume the initial 0.
                        int index;
                        for (index = 0; index < 3; index++) {
                            int ch = peekCharLL();
                            if (ch < chDigit0 || ch > chDigit7) {
                                if (index == 0) {
                                    // \0 is not followed by any octal digits.
                                    throw error(UErrorCode.U_REGEX_BAD_ESCAPE_SEQUENCE, null);
                                }
                                break;
                            }
                            c.fChar <<= 3;
                            c.fChar += ch & 7;
                            if (c.fChar <= 255) {
                                nextCharLL();
                            } else {
                                // The last digit made the number too big.  Forget we saw it.
                                c.fChar >>>= 3;
                            }
                        }
                        c.fQuoted = true;
                    } else if (peekCharLL() == chQ) {
                        //  "\Q"  enter quote mode, which will continue until "\E"
                        fQuoteMode = true;
                        nextCharLL();        // discard the 'Q'.
                        // nextChar(c);      // recurse to get the real next char.
                        continue tailRecursion;  // Note: fuzz testing produced test cases that
                        //                            resulted in stack overflow here.
                    } else {
                        // We are in a '\' escape that will be handled by the state table scanner.
                        // Just return the backslash, but remember that the following char is to
                        //  be taken literally.
                        fInBackslashQuote = true;
                    }
                }
            }

            // re-enable # to end-of-line comments, in case they were disabled.
            // They are disabled by the parser upon seeing '(?', but this lasts for
            //  the fetching of the next character only.
            fEOLComments = true;

            // putc(c.fChar, stdout);
            return;
        }
    }


    /**
     * Get a int from a \N{UNICODE CHARACTER NAME} in the pattern.
     * The scan position will be at the 'N'.  On return
     * the scan position should be just after the '}'<br/>
     * Return the int
     */
    private int scanNamedChar() {
        nextChar(fC);
        if (fC.fChar != chLBrace) {
            throw error(UErrorCode.U_REGEX_PROPERTY_SYNTAX, null);
//            return 0;
        }

        StringBuffer charName = new StringBuffer();
        for (; ; ) {
            nextChar(fC);
            if (fC.fChar == chRBrace) {
                break;
            }
            if (fC.fChar == -1) {
                throw error(UErrorCode.U_REGEX_PROPERTY_SYNTAX, null);
//                return 0;
            }
            charName.appendCodePoint(fC.fChar);
        }

        char[] name = new char[100];
        if (!uprv_isInvariantUString(charName) ||
                (/*uint32*/ long) charName.length() >= name.length) {
            // All Unicode character names have only invariant characters.
            // The API to get a character, given a name, accepts only char *, forcing us to convert,
            //   which requires this error check
            throw error(UErrorCode.U_REGEX_PROPERTY_SYNTAX, null);
//            return 0;
        }

        int theChar = UCharacter.getCharFromName(charName.toString());
        if (theChar == -1) {
            throw error(UErrorCode.U_REGEX_PROPERTY_SYNTAX, null);
        }

        nextChar(fC);      // Continue overall regex pattern processing with char after the '}'
        return theChar;
    }


    /**
     * Construct a UnicodeSet from the text at the current scan
     * position, which will be of the form \p{whaterver}<br/>
     * The scan position will be at the 'p' or 'P'.  On return
     * the scan position should be just after the '}'<br/>
     * Return a UnicodeSet, constructed from the \P pattern,
     * or null if the pattern is invalid.
     */
    private UnicodeSet scanProp() {
        UnicodeSet uset;

        assert fC.fChar == chLowerP || fC.fChar == chP;
        boolean negated = (fC.fChar == chP);

        StringBuffer propertyName = new StringBuffer();
        nextChar(fC);
        if (fC.fChar != chLBrace) {
            throw error(UErrorCode.U_REGEX_PROPERTY_SYNTAX, null);
//            return null;
        }
        for (; ; ) {
            nextChar(fC);
            if (fC.fChar == chRBrace) {
                break;
            }
            if (fC.fChar == -1) {
                // Hit the end of the input string without finding the closing '}'
                throw error(UErrorCode.U_REGEX_PROPERTY_SYNTAX, null);
//                return null;
            }
            propertyName.appendCodePoint(fC.fChar);
        }
        uset = createSetForProperty(propertyName, negated);
        nextChar(fC);    // Move input scan to position following the closing '}'
        return uset;
    }

    /**
     * Construct a UnicodeSet from the text at the current scan
     * position, which is expected be of the form [:property expression:]
     * The scan position will be at the opening ':'.  On return
     * the scan position must be on the closing ']'
     * Return a UnicodeSet constructed from the pattern,
     * or null if this is not a valid POSIX-style set expression.<br/>
     * If not a property expression, restore the initial scan position
     * (to the opening ':')<br/>
     * Note:  the opening '[:' is not sufficient to guarantee that
     * this is a [:property:] expression.<br/>
     * [:'+=,] is a perfectly good ordinary set expression that
     * happens to include ':' as one of its characters.
     */
    private UnicodeSet scanPosixProp() {
        UnicodeSet uset = null;

        assert fC.fChar == chColon;

        // Save the scanner state.
        // TODO:  move this into the scanner, with the state encapsulated in some way.  Ticket 6062
        long savedScanIndex = fScanIndex;
        long savedNextIndex = Util.utext_getNativeIndex(fRXPat.fPattern);
        boolean savedQuoteMode = fQuoteMode;
        boolean savedInBackslashQuote = fInBackslashQuote;
        boolean savedEOLComments = fEOLComments;
        long savedLineNum = fLineNum;
        long savedCharNum = fCharNum;
        int savedLastChar = fLastChar;
        int savedPeekChar = fPeekChar;
        RegexPatternChar savedfC = fC.clone();

        // Scan for a closing ].   A little tricky because there are some perverse
        //   edge cases possible.  "[:abc\Qdef:] \E]"  is a valid non-property expression,
        //   ending on the second closing ].

        StringBuffer propName = new StringBuffer();
        boolean negated = false;

        // Check for and consume the '^' in a negated POSIX property, e.g.  [:^Letter:]
        nextChar(fC);
        if (fC.fChar == chUp) {
            negated = true;
            nextChar(fC);
        }

        // Scan for the closing ":]", collecting the property name along the way.
        boolean sawPropSetTerminator = false;
        for (; ; ) {
            propName.appendCodePoint(fC.fChar);
            nextChar(fC);
            if (fC.fQuoted || fC.fChar == -1) {
                // Escaped characters or end of input - either says this isn't a [:Property:]
                break;
            }
            if (fC.fChar == chColon) {
                nextChar(fC);
                if (fC.fChar == chRBracket) {
                    sawPropSetTerminator = true;
                }
                break;
            }
        }

        if (sawPropSetTerminator) {
            uset = createSetForProperty(propName, negated);
        } else {
            // No closing ":]".
            //  Restore the original scan position.
            //  The main scanner will retry the input as a normal set expression,
            //    not a [:Property:] expression.
            fScanIndex = savedScanIndex;
            fQuoteMode = savedQuoteMode;
            fInBackslashQuote = savedInBackslashQuote;
            fEOLComments = savedEOLComments;
            fLineNum = savedLineNum;
            fCharNum = savedCharNum;
            fLastChar = savedLastChar;
            fPeekChar = savedPeekChar;
            fC = savedfC;
            Util.utext_setNativeIndex(fRXPat.fPattern, Math.toIntExact(savedNextIndex));
        }
        return uset;
    }

    private static void addIdentifierIgnorable(final UnicodeSet set) {
        set.add(0, 8).add(0x0e, 0x1b).add(0x7f, 0x9f);
        addCategory(set, GC_CF_MASK);
    }

    /**
     * Create a Unicode Set from a Unicode Property expression.<br/>
     * This is common code underlying both \p{...} and [:...:] expressions.
     * Includes trying the Java "properties" that aren't supported as
     * normal ICU UnicodeSet properties
     */
    private UnicodeSet createSetForProperty(final StringBuffer propName, final boolean negated) {
        boolean negatedNew = negated;
        UnicodeSet set;
        try {
            // non-loop, exists to allow breaks from the block:
            //noinspection LoopStatementThatDoesntLoop
            do {
                //
                //  First try the property as we received it
                //
                /*uint32*/
                long usetFlags = 0;
                String setExpr = "[\\p{" +
                        propName +
                        "}]";
                if ((fModeFlags & UREGEX_CASE_INSENSITIVE.flag) != 0) {
                    usetFlags |= CASE_INSENSITIVE;
                }
                try {
                    set = new UnicodeSet(setExpr, Math.toIntExact(usetFlags));
                    break;
                } catch (IllegalArgumentException e) {
                    //  The incoming property wasn't directly recognized by ICU.
                }

                //  Check [:word:] and [:all:]. These are not recognized as a properties by ICU UnicodeSet.
                //     Java accepts 'word' with mixed case.
                //     Java accepts 'all' only in all lower case.

                if (propName.toString().equalsIgnoreCase("word")) {
                    set = RegexStaticSets.INSTANCE.fPropSets[URX_ISWORD_SET.getIndex()].cloneAsThawed();
                    break;
                }
                if (propName.toString().equals("all")) {
                    set = new UnicodeSet(0, 0x10ffff);
                    break;
                }


                //    Do Java InBlock expressions
                //
                StringBuffer mPropName = new StringBuffer(propName);
                if (mPropName.toString().startsWith("In") && mPropName.length() >= 3) {
                    set = new UnicodeSet();
                    set.applyPropertyAlias(
                            "Block", // Property with the leading "In" removed.
                            mPropName.substring(2)
                    );
                    break;
                }

                //  Check for the Java form "IsBooleanPropertyValue", which we will recast
                //  as "BooleanPropertyValue". The property value can be either a
                //  a General Category or a Script Name.

                if (propName.toString().startsWith("Is") && propName.length() >= 3) {
                    mPropName.delete(0, 2);      // Strip the "Is"
                    if (mPropName.indexOf("=") >= 0) {
                        // Reject any "Is..." property expression containing an '=', that is,
                        // any non-binary property expression.
                        throw error(UErrorCode.U_REGEX_PROPERTY_SYNTAX, null);
                    }

                    if (mPropName.toString().equalsIgnoreCase("assigned")) {
                        setTo(mPropName, "unassigned");
                        negatedNew = !negatedNew;
                    } else if (mPropName.toString().equalsIgnoreCase("TitleCase")) {
                        setTo(mPropName, "Titlecase_Letter");
                    }

                    mPropName.insert(0, "[\\p{");
                    mPropName.append("}]");
                    set = new UnicodeSet(mPropName.toString());

                    if (!set.isEmpty() && (usetFlags & CASE_INSENSITIVE) != 0) {
                        set.closeOver(CASE_INSENSITIVE);
                    }
                    break;

                }

                if (propName.toString().startsWith("java")) {
                    set = new UnicodeSet();
                    //
                    //  Try the various Java specific properties.
                    //   These all begin with "java"
                    //
                    switch (propName.toString()) {
                        case "javaDefined":
                            addCategory(set, GC_CN_MASK);
                            set.complement();
                            break;
                        case "javaDigit":
                            addCategory(set, GC_ND_MASK);
                            break;
                        case "javaIdentifierIgnorable":
                            addIdentifierIgnorable(set);
                            break;
                        case "javaISOControl":
                            set.add(0, 0x1F).add(0x7F, 0x9F);
                            break;
                        case "javaJavaIdentifierPart":
                            addCategory(set, GC_L_MASK);
                            addCategory(set, GC_SC_MASK);
                            addCategory(set, GC_PC_MASK);
                            addCategory(set, GC_ND_MASK);
                            addCategory(set, GC_NL_MASK);
                            addCategory(set, GC_MC_MASK);
                            addCategory(set, GC_MN_MASK);
                            addIdentifierIgnorable(set);
                            break;
                        case "javaJavaIdentifierStart":
                            addCategory(set, GC_L_MASK);
                            addCategory(set, GC_NL_MASK);
                            addCategory(set, GC_SC_MASK);
                            addCategory(set, GC_PC_MASK);
                            break;
                        case "javaLetter":
                            addCategory(set, GC_L_MASK);
                            break;
                        case "javaLetterOrDigit":
                            addCategory(set, GC_L_MASK);
                            addCategory(set, GC_ND_MASK);
                            break;
                        case "javaLowerCase":
                            addCategory(set, GC_LL_MASK);
                            break;
                        case "javaMirrored":
                            set.applyIntPropertyValue(BIDI_MIRRORED, 1);
                            break;
                        case "javaSpaceChar":
                            addCategory(set, GC_Z_MASK);
                            break;
                        case "javaSupplementaryCodePoint":
                            set.add(0x10000, UnicodeSet.MAX_VALUE);
                            break;
                        case "javaTitleCase":
                            addCategory(set, GC_LT_MASK);
                            break;
                        case "javaUnicodeIdentifierStart":
                            addCategory(set, GC_L_MASK);
                            addCategory(set, GC_NL_MASK);
                            break;
                        case "javaUnicodeIdentifierPart":
                            addCategory(set, GC_L_MASK);
                            addCategory(set, GC_PC_MASK);
                            addCategory(set, GC_ND_MASK);
                            addCategory(set, GC_NL_MASK);
                            addCategory(set, GC_MC_MASK);
                            addCategory(set, GC_MN_MASK);
                            addIdentifierIgnorable(set);
                            break;
                        case "javaUpperCase":
                            addCategory(set, GC_LU_MASK);
                            break;
                        case "javaValidCodePoint":
                            set.add(0, UnicodeSet.MAX_VALUE);
                            break;
                        case "javaWhitespace":
                            addCategory(set, GC_Z_MASK);
                            set.removeAll(new UnicodeSet().add(0xa0).add(0x2007).add(0x202f));
                            set.add(9, 0x0d).add(0x1c, 0x1f);
                            break;
                        default:
                            throw error(UErrorCode.U_REGEX_PROPERTY_SYNTAX, null);
                    }

                    if (!set.isEmpty() && (usetFlags & CASE_INSENSITIVE) != 0) {
                        set.closeOver(CASE_INSENSITIVE);
                    }
                    break;
                }

                // Unrecognized property. ICU didn't like it as it was, and none of the Java compatibility
                // extensions matched it.
                throw error(UErrorCode.U_REGEX_PROPERTY_SYNTAX, null);
            } while (false);   // End of do loop block. Code above breaks out of the block on success or hard failure.

            // ICU4C 70 adds emoji properties of strings, but as long as Java does not say how to
            // deal with properties of strings and character classes with strings, we ignore them.
            // Just in case something downstream might stumble over the strings,
            // we remove them from the set.
            // Note that when we support strings, the complement of a property (as with \P)
            // should be implemented as .complement().removeAllStrings() (code point complement).
            set.removeAllStrings();
            if (negatedNew) {
                set.complement();
            }
            return set;
        } catch (IllegalArgumentException e) {
            throw error(UErrorCode.U_REGEX_PROPERTY_SYNTAX, e);
        }
    }


    /**
     * Part of the evaluation of [set expressions].<br/>
     * Perform any pending (stacked) operations with precedence
     * equal or greater to that of the next operator encountered
     * in the expression.
     */
    private void setEval(final int nextOp) {
        UnicodeSet rightOperand;
        UnicodeSet leftOperand;
        for (; ; ) {
            assert fSetOpStack.isEmpty() == false;
            int pendingSetOperation = fSetOpStack.peek().index;
            if ((pendingSetOperation & 0xffff0000) < (nextOp & 0xffff0000)) {
                break;
            }
            fSetOpStack.pop();
            assert fSetStack.isEmpty() == false;
            rightOperand = (UnicodeSet) fSetStack.peek();
            // ICU4C 70 adds emoji properties of strings, but createSetForProperty() removes all strings
            // (see comments there).
            // We also do not yet support string literals in character classes,
            // so there should not be any strings.
            // Note that when we support strings, the complement of a set (as with ^ or \P)
            // should be implemented as .complement().removeAllStrings() (code point complement).
            assert !rightOperand.hasStrings();
            switch (SetOperations.getByIndex(pendingSetOperation)) {
                case setNegation:
                    rightOperand.complement();
                    break;
                case setCaseClose:
                    // TODO: need a simple close function.  Ticket 6065
                    rightOperand.closeOver(CASE_INSENSITIVE);
                    rightOperand.removeAllStrings();
                    break;
                case setDifference1:
                case setDifference2:
                    fSetStack.pop();
                    leftOperand = (UnicodeSet) fSetStack.peek();
                    leftOperand.removeAll(rightOperand);
                    break;
                case setIntersection1:
                case setIntersection2:
                    fSetStack.pop();
                    leftOperand = (UnicodeSet) fSetStack.peek();
                    leftOperand.retainAll(rightOperand);
                    break;
                case setUnion:
                    fSetStack.pop();
                    leftOperand = (UnicodeSet) fSetStack.peek();
                    leftOperand.addAll(rightOperand);
                    break;
                default:
                    throw new IllegalStateException();
            }
        }
    }

    private void setPushOp(int op) {
        setEval(op);
        fSetOpStack.push(SetOperations.getByIndex(op));
        UnicodeSet lpSet = new UnicodeSet();
        fSetStack.push(lpSet);
    }
}