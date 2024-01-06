package com.github.tarcv.u4jregex;

import com.ibm.icu.impl.UCaseProps;

import java.util.Arrays;
import java.util.Objects;

import static com.ibm.icu.impl.UCaseProps.MAX_STRING_LENGTH;
import static com.ibm.icu.lang.UCharacter.FOLD_CASE_DEFAULT;

class RegexImp {
    // number of UVector elements in the header
    static final int RESTACKFRAME_HDRCOUNT = 2;
}

abstract class IndexedView {
    protected final int baseOffset;

    IndexedView(final int baseOffset) {
        this.baseOffset = baseOffset;
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof IndexedView)) {
            return false;
        }
        IndexedView otherView = (IndexedView) obj;
        return getSource() == otherView.getSource()
                && baseOffset == otherView.baseOffset;
    }

    protected abstract Object getSource();
    abstract long get(int index);
    abstract void set(int index, long value);

    @Override
    public int hashCode() {
        return Objects.hash(getSource(), baseOffset);
    }

    public REStackFrame asREStackFrame(final int frameSize) {
        return new REStackFrame(this, frameSize);
    }
}
class CharArrayView {
    int baseOffset;
    final char[] source;

    CharArrayView(final char[] source, final int baseOffset) {
        this.baseOffset = baseOffset;
        //noinspection AssignmentOrReturnOfFieldWithMutableType
        this.source = source; // it is intended to be the link for the original array
    }

    public CharArrayView(final CharArrayView otherView, final int baseOffset) {
        this(otherView.source, otherView.baseOffset + baseOffset);
    }

    protected Object getSource() {
        //noinspection AssignmentOrReturnOfFieldWithMutableType
        return source; // should be the same array for the "reference" equality check
    }

    char get(final int index) {
        return source[baseOffset + index];
    }

    void set(final int index, final char value) {
        source[baseOffset + index] = value;
    }

    public int compareBaseOffsetWith(final CharArrayView otherView) {
        assert source == otherView.source;
        return Integer.compare(baseOffset, otherView.baseOffset);
    }

    public char next() {
        char result = get(0);
        baseOffset++;
        return result;
    }
}
class StringBufferView {
    int baseOffset;
    final StringBuffer source;

    StringBufferView(final StringBuffer source, final int baseOffset) {
        this.baseOffset = baseOffset;
        this.source = source; // it is intended to be the link for the original array
    }

    protected Object getSource() {
        return source; // should be the same array for the "reference" equality check
    }

    char get(final int index) {
        char[] dst = new char[1];
        source.getChars(index, index + 1, dst, 0);
        return dst[0];
    }

    void set(final int index, final char value) {
        source.setCharAt(index, value);
    }

    public char next() {
        char result = get(baseOffset);
        baseOffset++;
        return result;
    }

    public char[] toCharArray() {
        char[] dst = new char[source.length() - baseOffset];
        source.getChars(baseOffset, source.length(), dst, 0);
        return dst;
    }
}
class LongArrayView extends IndexedView {
    final long[] source;
    private boolean isValid = true;

    LongArrayView(final long[] source, final int baseOffset) {
        super(baseOffset);
        //noinspection AssignmentOrReturnOfFieldWithMutableType
        this.source = source; // it is intended to be the link for the original array
    }

    @Override
    protected Object getSource() {
        checkValid();
        //noinspection AssignmentOrReturnOfFieldWithMutableType
        return source; // should be the same array for the "reference" equality check
    }

    @Override
    long get(final int index) {
        checkValid();
        return source[baseOffset + index];
    }

    @Override
    void set(final int index, final long value) {
        checkValid();
        source[baseOffset + index] = value;
    }

    private void checkValid() {
        assert isValid;
    }

    public void invalidate() {
        isValid = false;
    }
}

class Vector64View extends IndexedView {
    final MutableVector64 source;

    Vector64View(final MutableVector64 source, final int baseOffset) {
        super(baseOffset);
        //noinspection AssignmentOrReturnOfFieldWithMutableType
        this.source = source; // it is intended to be the link for the original array
    }

    @Override
    protected Object getSource() {
        //noinspection AssignmentOrReturnOfFieldWithMutableType
        return source; // should be the same array for the "reference" equality check
    }

    @Override
    long get(final int index) {
        return source.elementAti(baseOffset + index);
    }

    @Override
    void set(final int index, final long value) {
        source.setElementAt(value, baseOffset + index);
    }
}

/**
 * Match Engine State Stack Frame Layout.
 */
class REStackFrame {
    private final IndexedView indexedView;
    private final int size;

    REStackFrame(final IndexedView indexedView, final int size) {
        this.indexedView = indexedView;
        this.size = size;
    }

    // Header

    /**
     * Position of next character in the input string
     */
    public long fInputIdx() {
        return indexedView.get(0);
    }
    public void setFInputIdx(final long value) {
        indexedView.set(0, value);
    }

    /**
     * Position of next Op in the compiled pattern
     * (long for UVector64, values fit in an int32_t)
     */
    public long fPatIdx() {
        return indexedView.get(1);
    }

    public void setFPatIdx(final long value) {
        indexedView.set(1, value);
    }


    // Remainder

    /**
     * Extra state, for capture group start/ends
     * atomic parentheses, repeat counts, etc.
     * Locations assigned at pattern compile time.
     * Variable-length array.
     */
    public long fExtra(final int index) {
        return indexedView.get(getFExtraIndex(index));
    }
    /**
     * Extra state, for capture group start/ends
     * atomic parentheses, repeat counts, etc.
     * Locations assigned at pattern compile time.
     * Variable-length array.
     */
    public void setFExtra(final int index, final long value) {
        indexedView.set(2 + index, value);
    }

    public int getFExtraSize() {
        return getFExtraSizeForFrameSize(size);
    }

    private int getFExtraSizeForFrameSize(final int fFrameSize) {
        return fFrameSize - 2;
    }

    private int getFExtraIndex(final int index) {
        assert index < getFExtraSize();
        return 2 + index;
    }

    public void setFrom(final REStackFrame newFP, final int fFrameSize) {
        setFInputIdx(newFP.fInputIdx());
        setFPatIdx(newFP.fPatIdx());
        for (int i = 0; i < getFExtraSizeForFrameSize(fFrameSize); i++) {
            setFExtra(i, newFP.fExtra(i));
        }
    }

    @Override
    public String toString() {
        StringBuilder fExtras = new StringBuilder();
        int fExtraSize = getFExtraSize();
        for (int i = 0; i < fExtraSize; i++) {
            if (i != 0) {
                fExtras.append(", ");
            }
            fExtras.append(fExtra(i));
        }
        return "REStackFrame{" +
                "fInputIdx=" + fInputIdx() +
                ", fPatIdx=" + fPatIdx() +
                ", fExtra[" + fExtraSize + "]={" + fExtras + "}" +
                '}';
    }

    public IndexedView asIndexedView() {
        return indexedView;
    }

    public long postIncrementFPatIdx() {
        long lastValue = fPatIdx();
        setFPatIdx(lastValue + 1);
        return lastValue;
    }
}

//
//  Access to Unicode Sets composite character properties
//     The sets are accessed by the match engine for things like \w (word boundary)
//
enum URX {
        URX_ISWORD_SET  (1),
        URX_ISALNUM_SET (2),
        URX_ISALPHA_SET (3),
        URX_ISSPACE_SET (4),

        URX_GC_NORMAL,          // Sets for finding grapheme cluster boundaries.
        URX_GC_EXTEND,
        URX_GC_CONTROL,
        URX_GC_L,
        URX_GC_LV,
        URX_GC_LVT,
        URX_GC_V,
        URX_GC_T,

        URX_LAST_SET,

        URX_NEG_SET     (0x800000)          // Flag bit to reverse sense of set
    ;
    //   membership test.

    private final int index;

    URX(int index) {
        this.index = index;
    }
    URX() {
        this.index = -1;
    }

    public int getIndex() {
        if (index >= 0) {
            return index;
        } else {
            return URX.values()[ordinal() - 1].getIndex() + 1;
        }
    }
};
//
//  Opcode types     In the compiled form of the regexp, these are the type, or opcodes,
//                   of the entries.
//
enum UrxOps {
    URX_RESERVED_OP(0),    // For multi-operand ops, most non-first words.
    URX_RESERVED_OP_N(255),  // For multi-operand ops, negative operand values.
    URX_BACKTRACK(1),    // Force a backtrack, as if a match test had failed.
    URX_END(2),
    URX_ONECHAR(3),    // Value field is the 21 bit unicode char to match
    URX_STRING(4),    // Value field is index of string start
    URX_STRING_LEN(5),    // Value field is string length (code units)
    URX_STATE_SAVE(6),    // Value field is pattern position to push
    URX_NOP(7),
    URX_START_CAPTURE(8),    // Value field is capture group number.
    URX_END_CAPTURE(9),    // Value field is capture group number
    URX_STATIC_SETREF(10),   // Value field is index of set in array of sets.
    URX_SETREF(11),   // Value field is index of set in array of sets.
    URX_DOTANY(12),
    URX_JMP(13),   // Value field is destination position in
    //   the pattern.
    URX_FAIL(14),   // Stop match operation,  No match.

    URX_JMP_SAV(15),   // Operand:  JMP destination location
    URX_BACKSLASH_B(16),   // Value field:  0:  \b    1:  \B
    URX_BACKSLASH_G(17),
    URX_JMP_SAV_X(18),   // Conditional JMP_SAV,
    //    Used in (x)+, breaks loop on zero length match.
    //    Operand:  Jmp destination.
    URX_BACKSLASH_X(19),
    URX_BACKSLASH_Z(20),   // \z   Unconditional end of line.

    URX_DOTANY_ALL(21),   // ., in the . matches any mode.
    URX_BACKSLASH_D(22),   // Value field:  0:  \d    1:  \D
    URX_CARET(23),   // Value field:  1:  multi-line mode.
    URX_DOLLAR(24),  // Also for \Z

    URX_CTR_INIT(25),   // Counter Inits for {Interval} loops.
    URX_CTR_INIT_NG(26),   //   2 kinds, normal and non-greedy.
    //   These are 4 word opcodes.  See description.
    //    First Operand:  Data loc of counter variable
    //    2nd   Operand:  Pat loc of the URX_CTR_LOOPx
    //                    at the end of the loop.
    //    3rd   Operand:  Minimum count.
    //    4th   Operand:  Max count, -1 for unbounded.

    URX_DOTANY_UNIX(27),   // '.' operator in UNIX_LINES mode, only \n marks end of line.

    URX_CTR_LOOP(28),   // Loop Ops for {interval} loops.
    URX_CTR_LOOP_NG(29),   //   Also in three flavors.
    //   Operand is loc of corresponding CTR_INIT.

    URX_CARET_M_UNIX(30),   // '^' operator, test for start of line in multi-line
    //      plus UNIX_LINES mode.

    URX_RELOC_OPRND(31),   // Operand value in multi-operand ops that refers
    //   back into compiled pattern code, and thus must
    //   be relocated when inserting/deleting ops in code.

    URX_STO_SP(32),   // Store the stack ptr.  Operand is location within
    //   matcher data (not stack data) to store it.
    URX_LD_SP(33),   // Load the stack pointer.  Operand is location
    //   to load from.
    URX_BACKREF(34),   // Back Reference.  Parameter is the index of the
    //   capture group variables in the state stack frame.
    URX_STO_INP_LOC(35),   // Store the input location.  Operand is location
    //   within the matcher stack frame.
    URX_JMPX(36),  // Conditional JMP.
    //   First Operand:  JMP target location.
    //   Second Operand:  Data location containing an
    //     input position.  If current input position ==
    //     saved input position, FAIL rather than taking
    //     the JMP
    URX_LA_START(37),   // Starting a LookAround expression.
    //   Save InputPos, SP and active region in static data.
    //   Operand:  Static data offset for the save
    URX_LA_END(38),   // Ending a Lookaround expression.
    //   Restore InputPos and Stack to saved values.
    //   Operand:  Static data offset for saved data.
    URX_ONECHAR_I(39),   // Test for case-insensitive match of a literal character.
    //   Operand:  the literal char.
    URX_STRING_I(40),   // Case insensitive string compare.
    //   First Operand:  Index of start of string in string literals
    //   Second Operand (next word in compiled code):
    //     the length of the string.
    URX_BACKREF_I(41),   // Case insensitive back reference.
    //   Parameter is the index of the
    //   capture group variables in the state stack frame.
    URX_DOLLAR_M(42),   // $ in multi-line mode.
    URX_CARET_M(43),   // ^ in multi-line mode.
    URX_LB_START(44),   // LookBehind Start.
    //   Parameter is data location
    URX_LB_CONT(45),   // LookBehind Continue.
    //   Param 0:  the data location
    //   Param 1:  The minimum length of the look-behind match
    //   Param 2:  The max length of the look-behind match
    URX_LB_END(46),   // LookBehind End.
    //   Parameter is the data location.
    //     Check that match ended at the right spot,
    //     Restore original input string len.
    URX_LBN_CONT(47),   // Negative LookBehind Continue
    //   Param 0:  the data location
    //   Param 1:  The minimum length of the look-behind match
    //   Param 2:  The max     length of the look-behind match
    //   Param 3:  The pattern loc following the look-behind block.
    URX_LBN_END(48),   // Negative LookBehind end
    //   Parameter is the data location.
    //   Check that the match ended at the right spot.
    URX_STAT_SETREF_N(49),   // Reference to a prebuilt set (e.g. \w), negated
    //   Operand is index of set in array of sets.
    URX_LOOP_SR_I(50),   // Init a [set]* loop.
    //   Operand is the sets index in array of user sets.
    URX_LOOP_C(51),   // Continue a [set]* or OneChar* loop.
    //   Operand is a matcher static data location.
    //   Must always immediately follow  LOOP_x_I instruction.
    URX_LOOP_DOT_I(52),   // .*, initialization of the optimized loop.
    //   Operand value:
    //      bit 0:
    //         0:  Normal (. doesn't match new-line) mode.
    //         1:  . matches new-line mode.
    //      bit 1:  controls what new-lines are recognized by this operation.
    //         0:  All Unicode New-lines
    //         1:  UNIX_LINES, \ u000a only.
    URX_BACKSLASH_BU(53),   // \b or \B in UREGEX_UWORD mode, using Unicode style
    //   word boundaries.
    URX_DOLLAR_D(54),   // $ end of input test, in UNIX_LINES mode.
    URX_DOLLAR_MD(55),   // $ end of input test, in MULTI_LINE and UNIX_LINES mode.
    URX_BACKSLASH_H(56),   // Value field:  0:  \h    1:  \H
    URX_BACKSLASH_R(57),   // Any line break sequence.
    URX_BACKSLASH_V(58)    // Value field:  0:  \v    1:  \V
    ;
    final int index;

    UrxOps(final int index) {
        this.index = index;
    }

//
//  Convenience macros for assembling and disassembling a compiled operation.
//
    static UrxOps URX_TYPE(int x) {
        int indexFixed = x >>> 24;
        //noinspection OptionalGetWithoutIsPresent
        return Arrays.stream(values())
                .filter(e -> e.index == indexFixed)
                .findAny()
                .get();
    }

    static int URX_VAL(int x) {
        return ((x) & 0xffffff);
    }
}

class CaseFoldingUTextIterator {
    private final Util.StringWithOffset fUText;
    private StringBuffer fFoldChars;
    private int            fFoldLength;
    private int            fFoldIndex = 0;

    CaseFoldingUTextIterator(final Util.StringWithOffset text) {
        fUText = text;
        fFoldLength = 0;
    }


    int next() {
        int foldedC;
        int originalC;
        if (fFoldChars == null) {
            fFoldChars = new StringBuffer();

            // We are not in a string folding of an earlier character.
            // Start handling the next char from the input UText.
            originalC = Util.utext_next32(fUText);
            if (originalC == Util.U_SENTINEL) {
                return originalC;
            }
            fFoldLength = UCaseProps.INSTANCE.toFullFolding(originalC, fFoldChars, 0);
            if (fFoldLength >= MAX_STRING_LENGTH || fFoldLength < 0) {
                // input code point folds to a single code point, possibly itself.
                // See comment in ucase.h for explanation of return values from ucase_toFullFoldings.
                if (fFoldLength < 0) {
                    fFoldLength = ~fFoldLength;
                }
                foldedC = (int ) fFoldLength;
                fFoldChars = null;
                return foldedC;
            }
            // String foldings fall through here.
            fFoldIndex = 0;
        }

        char[] chars = new char[fFoldLength];
        fFoldChars.getChars(0, fFoldLength, chars, 0);
        Util.IndexAndChar indexAndChar = Util.U16_NEXT(chars, fFoldIndex, fFoldLength);
        fFoldIndex = indexAndChar.i;
        foldedC = indexAndChar.c;
        if (fFoldIndex >= fFoldLength) {
            fFoldChars = null;
        }
        return foldedC;
    }


    boolean inExpansion() {
        return fFoldChars != null;
    }
}

class CaseFoldingUCharIterator {
            private final  char[] fChars;
    private long            fIndex;
    private final long            fLimit;
    private StringBuffer fFoldChars;
    private int            fFoldLength;
    private int            fFoldIndex;

    CaseFoldingUCharIterator(final char[] chars, final long start, final long limit) {
        //noinspection AssignmentOrReturnOfFieldWithMutableType
        fChars = chars;
        fIndex = start;
        fLimit = limit;
        fFoldChars = null;
        fFoldLength = (0);
    }

    int next() {
        int foldedC;
        int originalC;
        if (fFoldChars == null) {
            fFoldChars = new StringBuffer();

            // We are not in a string folding of an earlier character.
            // Start handling the next char from the input UText.
            if (fIndex >= fLimit) {
                return Util.U_SENTINEL;
            }
            Util.IndexAndChar indexAndChar = Util.U16_NEXT(fChars, Math.toIntExact(fIndex), Math.toIntExact(fLimit));
            fIndex = indexAndChar.i;
            originalC = indexAndChar.c;

            fFoldLength = UCaseProps.INSTANCE.toFullFolding(originalC, fFoldChars, FOLD_CASE_DEFAULT);
            if (fFoldLength >= MAX_STRING_LENGTH || fFoldLength < 0) {
                // input code point folds to a single code point, possibly itself.
                // See comment in ucase.h for explanation of return values from ucase_toFullFoldings.
                if (fFoldLength < 0) {
                    fFoldLength = ~fFoldLength;
                }
                foldedC = (int ) fFoldLength;
                fFoldChars = null;
                return foldedC;
            }
            // String foldings fall through here.
            fFoldIndex = 0;
        }

        char[] chars = new char[fFoldLength];
        fFoldChars.getChars(0, fFoldLength, chars, 0);
        Util.IndexAndChar indexAndChar = Util.U16_NEXT(chars, fFoldIndex, fFoldLength);
        fFoldIndex = indexAndChar.i;
        foldedC = indexAndChar.c;
        if (fFoldIndex >= fFoldLength) {
            fFoldChars = null;
        }
        return foldedC;
    }


    boolean inExpansion() {
        return fFoldChars != null;
    }

    @SuppressWarnings("SuspiciousGetterSetter")
    long getIndex() {
        return fIndex;
    }
}