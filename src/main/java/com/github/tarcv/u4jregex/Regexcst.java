package com.github.tarcv.u4jregex;

import static com.github.tarcv.u4jregex.Regex_PatternParseAction.*;

enum Regex_PatternParseAction {
    doSetBackslash_D,
    doBackslashh,
    doBackslashH,
    doSetLiteralEscaped,
    doOpenLookAheadNeg,
    doCompleteNamedBackRef,
    doPatStart,
    doBackslashS,
    doBackslashD,
    doNGStar,
    doNOP,
    doBackslashX,
    doSetLiteral,
    doContinueNamedCapture,
    doBackslashG,
    doBackslashR,
    doSetBegin,
    doSetBackslash_v,
    doPossessivePlus,
    doPerlInline,
    doBackslashZ,
    doSetAddAmp,
    doSetBeginDifference1,
    doIntervalError,
    doSetNegate,
    doIntervalInit,
    doSetIntersection2,
    doPossessiveInterval,
    doRuleError,
    doBackslashW,
    doContinueNamedBackRef,
    doOpenNonCaptureParen,
    doExit,
    doSetNamedChar,
    doSetBackslash_V,
    doConditionalExpr,
    doEscapeError,
    doBadOpenParenType,
    doPossessiveStar,
    doSetAddDash,
    doEscapedLiteralChar,
    doSetBackslash_w,
    doIntervalUpperDigit,
    doBackslashv,
    doSetBackslash_S,
    doSetNoCloseError,
    doSetProp,
    doBackslashB,
    doSetEnd,
    doSetRange,
    doMatchModeParen,
    doPlus,
    doBackslashV,
    doSetMatchMode,
    doBackslashz,
    doSetNamedRange,
    doOpenLookBehindNeg,
    doInterval,
    doBadNamedCapture,
    doBeginMatchMode,
    doBackslashd,
    doPatFinish,
    doNamedChar,
    doNGPlus,
    doSetDifference2,
    doSetBackslash_H,
    doCloseParen,
    doDotAny,
    doOpenCaptureParen,
    doEnterQuoteMode,
    doOpenAtomicParen,
    doBadModeFlag,
    doSetBackslash_d,
    doSetFinish,
    doProperty,
    doBeginNamedBackRef,
    doBackRef,
    doOpt,
    doDollar,
    doBeginNamedCapture,
    doNGInterval,
    doSetOpError,
    doSetPosixProp,
    doSetBeginIntersection1,
    doBackslashb,
    doSetBeginUnion,
    doIntevalLowerDigit,
    doSetBackslash_h,
    doStar,
    doMatchMode,
    doBackslashA,
    doOpenLookBehind,
    doPossessiveOpt,
    doOrOperator,
    doBackslashw,
    doBackslashs,
    doLiteralChar,
    doSuppressComments,
    doCaret,
    doIntervalSame,
    doNGOpt,
    doOpenLookAhead,
    doSetBackslash_W,
    doMismatchedParenErr,
    doSetBackslash_s,
    rbbiLastAction
};

/**
 * RegexTableEl       represents the structure of a row in the transition table
 * for the pattern parser state machine.
 */
class RegexTableEl {
    final Regex_PatternParseAction fAction;
    final /* uint8 */ short fCharClass;       // 0-127:    an individual ASCII character
    // 128-255:  character class index
    final /* uint8 */ short fNextState;       // 0-250:    normal next-state numbers
    // 255:      pop next-state from stack.
    final /* uint8 */ short fPushState;
    boolean fNextChar;

    RegexTableEl(
            final Regex_PatternParseAction fAction,
            final int fCharClass,
            final int fNextState,
            final int fPushState,
            final boolean fNextChar
    ) {
        this.fAction = fAction;
        this.fCharClass = (short) fCharClass;
        this.fNextState = (short) fNextState;
        this.fPushState = (short) fPushState;
        this.fNextChar = fNextChar;
    }
}

/**
 * Character classes for regex pattern scanning.
 */
class Regexcst {
    static final /*uint8*/ short kRuleSet_digit_char = 128;
    static final /*uint8*/ short kRuleSet_ascii_letter = 129;
    static final /*uint8*/ short kRuleSet_rule_char = 130;
    static final /*uint32*/ int kRuleSet_count = 131-128;

    static final RegexTableEl[] gRuleParseStateTable = new RegexTableEl[] {
            new RegexTableEl(doNOP, 0, 0, 0, true)
            , new RegexTableEl(doPatStart, 255, 2, 0, false)     //  1      start
            , new RegexTableEl(doLiteralChar, 254, 14, 0, true)     //  2      term
            , new RegexTableEl(doLiteralChar, 130, 14, 0, true)     //  3 
            , new RegexTableEl(doSetBegin, 91 /* [ */, 123, 205, true)     //  4 
            , new RegexTableEl(doNOP, 40 /* ( */, 27, 0, true)     //  5 
            , new RegexTableEl(doDotAny, 46 /* . */, 14, 0, true)     //  6 
            , new RegexTableEl(doCaret, 94 /* ^ */, 14, 0, true)     //  7 
            , new RegexTableEl(doDollar, 36 /* $ */, 14, 0, true)     //  8 
            , new RegexTableEl(doNOP, 92 /* \ */, 89, 0, true)     //  9 
            , new RegexTableEl(doOrOperator, 124 /* | */, 2, 0, true)     //  10 
            , new RegexTableEl(doCloseParen, 41 /* ) */, 255, 0, true)     //  11 
            , new RegexTableEl(doPatFinish, 253, 2, 0, false)     //  12 
            , new RegexTableEl(doRuleError, 255, 206, 0, false)     //  13 
            , new RegexTableEl(doNOP, 42 /* * */, 68, 0, true)     //  14      expr-quant
            , new RegexTableEl(doNOP, 43 /* + */, 71, 0, true)     //  15 
            , new RegexTableEl(doNOP, 63 /* ? */, 74, 0, true)     //  16 
            , new RegexTableEl(doIntervalInit, 123 /* new RegexTableEl( */, 77, 0, true)     //  17 
            , new RegexTableEl(doNOP, 40 /* ( */, 23, 0, true)     //  18 
            , new RegexTableEl(doNOP, 255, 20, 0, false)     //  19 
            , new RegexTableEl(doOrOperator, 124 /* | */, 2, 0, true)     //  20      expr-cont
            , new RegexTableEl(doCloseParen, 41 /* ) */, 255, 0, true)     //  21 
            , new RegexTableEl(doNOP, 255, 2, 0, false)     //  22 
            , new RegexTableEl(doSuppressComments, 63 /* ? */, 25, 0, true)     //  23      open-paren-quant
            , new RegexTableEl(doNOP, 255, 27, 0, false)     //  24 
            , new RegexTableEl(doNOP, 35 /* # */, 50, 14, true)     //  25      open-paren-quant2
            , new RegexTableEl(doNOP, 255, 29, 0, false)     //  26 
            , new RegexTableEl(doSuppressComments, 63 /* ? */, 29, 0, true)     //  27      open-paren
            , new RegexTableEl(doOpenCaptureParen, 255, 2, 14, false)     //  28 
            , new RegexTableEl(doOpenNonCaptureParen, 58 /* : */, 2, 14, true)     //  29      open-paren-extended
            , new RegexTableEl(doOpenAtomicParen, 62 /* > */, 2, 14, true)     //  30 
            , new RegexTableEl(doOpenLookAhead, 61 /* = */, 2, 20, true)     //  31 
            , new RegexTableEl(doOpenLookAheadNeg, 33 /* ! */, 2, 20, true)     //  32 
            , new RegexTableEl(doNOP, 60 /* < */, 46, 0, true)     //  33 
            , new RegexTableEl(doNOP, 35 /* # */, 50, 2, true)     //  34 
            , new RegexTableEl(doBeginMatchMode, 105 /* i */, 53, 0, false)     //  35 
            , new RegexTableEl(doBeginMatchMode, 100 /* d */, 53, 0, false)     //  36 
            , new RegexTableEl(doBeginMatchMode, 109 /* m */, 53, 0, false)     //  37 
            , new RegexTableEl(doBeginMatchMode, 115 /* s */, 53, 0, false)     //  38 
            , new RegexTableEl(doBeginMatchMode, 117 /* u */, 53, 0, false)     //  39 
            , new RegexTableEl(doBeginMatchMode, 119 /* w */, 53, 0, false)     //  40 
            , new RegexTableEl(doBeginMatchMode, 120 /* x */, 53, 0, false)     //  41 
            , new RegexTableEl(doBeginMatchMode, 45 /* - */, 53, 0, false)     //  42 
            , new RegexTableEl(doConditionalExpr, 40 /* ( */, 206, 0, true)     //  43 
            , new RegexTableEl(doPerlInline, 123 /* new RegexTableEl( */, 206, 0, true)     //  44 
            , new RegexTableEl(doBadOpenParenType, 255, 206, 0, false)     //  45 
            , new RegexTableEl(doOpenLookBehind, 61 /* = */, 2, 20, true)     //  46      open-paren-lookbehind
            , new RegexTableEl(doOpenLookBehindNeg, 33 /* ! */, 2, 20, true)     //  47 
            , new RegexTableEl(doBeginNamedCapture, 129, 64, 0, false)     //  48 
            , new RegexTableEl(doBadOpenParenType, 255, 206, 0, false)     //  49 
            , new RegexTableEl(doNOP, 41 /* ) */, 255, 0, true)     //  50      paren-comment
            , new RegexTableEl(doMismatchedParenErr, 253, 206, 0, false)     //  51 
            , new RegexTableEl(doNOP, 255, 50, 0, true)     //  52 
            , new RegexTableEl(doMatchMode, 105 /* i */, 53, 0, true)     //  53      paren-flag
            , new RegexTableEl(doMatchMode, 100 /* d */, 53, 0, true)     //  54 
            , new RegexTableEl(doMatchMode, 109 /* m */, 53, 0, true)     //  55 
            , new RegexTableEl(doMatchMode, 115 /* s */, 53, 0, true)     //  56 
            , new RegexTableEl(doMatchMode, 117 /* u */, 53, 0, true)     //  57 
            , new RegexTableEl(doMatchMode, 119 /* w */, 53, 0, true)     //  58 
            , new RegexTableEl(doMatchMode, 120 /* x */, 53, 0, true)     //  59 
            , new RegexTableEl(doMatchMode, 45 /* - */, 53, 0, true)     //  60 
            , new RegexTableEl(doSetMatchMode, 41 /* ) */, 2, 0, true)     //  61 
            , new RegexTableEl(doMatchModeParen, 58 /* : */, 2, 14, true)     //  62 
            , new RegexTableEl(doBadModeFlag, 255, 206, 0, false)     //  63 
            , new RegexTableEl(doContinueNamedCapture, 129, 64, 0, true)     //  64      named-capture
            , new RegexTableEl(doContinueNamedCapture, 128, 64, 0, true)     //  65 
            , new RegexTableEl(doOpenCaptureParen, 62 /* > */, 2, 14, true)     //  66 
            , new RegexTableEl(doBadNamedCapture, 255, 206, 0, false)     //  67 
            , new RegexTableEl(doNGStar, 63 /* ? */, 20, 0, true)     //  68      quant-star
            , new RegexTableEl(doPossessiveStar, 43 /* + */, 20, 0, true)     //  69 
            , new RegexTableEl(doStar, 255, 20, 0, false)     //  70 
            , new RegexTableEl(doNGPlus, 63 /* ? */, 20, 0, true)     //  71      quant-plus
            , new RegexTableEl(doPossessivePlus, 43 /* + */, 20, 0, true)     //  72 
            , new RegexTableEl(doPlus, 255, 20, 0, false)     //  73 
            , new RegexTableEl(doNGOpt, 63 /* ? */, 20, 0, true)     //  74      quant-opt
            , new RegexTableEl(doPossessiveOpt, 43 /* + */, 20, 0, true)     //  75 
            , new RegexTableEl(doOpt, 255, 20, 0, false)     //  76 
            , new RegexTableEl(doNOP, 128, 79, 0, false)     //  77      interval-open
            , new RegexTableEl(doIntervalError, 255, 206, 0, false)     //  78 
            , new RegexTableEl(doIntevalLowerDigit, 128, 79, 0, true)     //  79      interval-lower
            , new RegexTableEl(doNOP, 44 /* , */, 83, 0, true)     //  80 
            , new RegexTableEl(doIntervalSame, 125 /* ) */, 86, 0, true)     //  81 
            , new RegexTableEl(doIntervalError, 255, 206, 0, false)     //  82 
            , new RegexTableEl(doIntervalUpperDigit, 128, 83, 0, true)     //  83      interval-upper
            , new RegexTableEl(doNOP, 125 /* ) */, 86, 0, true)     //  84 
            , new RegexTableEl(doIntervalError, 255, 206, 0, false)     //  85 
            , new RegexTableEl(doNGInterval, 63 /* ? */, 20, 0, true)     //  86      interval-type
            , new RegexTableEl(doPossessiveInterval, 43 /* + */, 20, 0, true)     //  87 
            , new RegexTableEl(doInterval, 255, 20, 0, false)     //  88 
            , new RegexTableEl(doBackslashA, 65 /* A */, 2, 0, true)     //  89      backslash
            , new RegexTableEl(doBackslashB, 66 /* B */, 2, 0, true)     //  90 
            , new RegexTableEl(doBackslashb, 98 /* b */, 2, 0, true)     //  91 
            , new RegexTableEl(doBackslashd, 100 /* d */, 14, 0, true)     //  92 
            , new RegexTableEl(doBackslashD, 68 /* D */, 14, 0, true)     //  93 
            , new RegexTableEl(doBackslashG, 71 /* G */, 2, 0, true)     //  94 
            , new RegexTableEl(doBackslashh, 104 /* h */, 14, 0, true)     //  95 
            , new RegexTableEl(doBackslashH, 72 /* H */, 14, 0, true)     //  96 
            , new RegexTableEl(doNOP, 107 /* k */, 115, 0, true)     //  97 
            , new RegexTableEl(doNamedChar, 78 /* N */, 14, 0, false)     //  98 
            , new RegexTableEl(doProperty, 112 /* p */, 14, 0, false)     //  99 
            , new RegexTableEl(doProperty, 80 /* P */, 14, 0, false)     //  100 
            , new RegexTableEl(doBackslashR, 82 /* R */, 14, 0, true)     //  101 
            , new RegexTableEl(doEnterQuoteMode, 81 /* Q */, 2, 0, true)     //  102 
            , new RegexTableEl(doBackslashS, 83 /* S */, 14, 0, true)     //  103 
            , new RegexTableEl(doBackslashs, 115 /* s */, 14, 0, true)     //  104 
            , new RegexTableEl(doBackslashv, 118 /* v */, 14, 0, true)     //  105 
            , new RegexTableEl(doBackslashV, 86 /* V */, 14, 0, true)     //  106 
            , new RegexTableEl(doBackslashW, 87 /* W */, 14, 0, true)     //  107 
            , new RegexTableEl(doBackslashw, 119 /* w */, 14, 0, true)     //  108 
            , new RegexTableEl(doBackslashX, 88 /* X */, 14, 0, true)     //  109 
            , new RegexTableEl(doBackslashZ, 90 /* Z */, 2, 0, true)     //  110 
            , new RegexTableEl(doBackslashz, 122 /* z */, 2, 0, true)     //  111 
            , new RegexTableEl(doBackRef, 128, 14, 0, true)     //  112 
            , new RegexTableEl(doEscapeError, 253, 206, 0, false)     //  113 
            , new RegexTableEl(doEscapedLiteralChar, 255, 14, 0, true)     //  114 
            , new RegexTableEl(doBeginNamedBackRef, 60 /* < */, 117, 0, true)     //  115      named-backref
            , new RegexTableEl(doBadNamedCapture, 255, 206, 0, false)     //  116 
            , new RegexTableEl(doContinueNamedBackRef, 129, 119, 0, true)     //  117      named-backref-2
            , new RegexTableEl(doBadNamedCapture, 255, 206, 0, false)     //  118 
            , new RegexTableEl(doContinueNamedBackRef, 129, 119, 0, true)     //  119      named-backref-3
            , new RegexTableEl(doContinueNamedBackRef, 128, 119, 0, true)     //  120 
            , new RegexTableEl(doCompleteNamedBackRef, 62 /* > */, 14, 0, true)     //  121 
            , new RegexTableEl(doBadNamedCapture, 255, 206, 0, false)     //  122 
            , new RegexTableEl(doSetNegate, 94 /* ^ */, 126, 0, true)     //  123      set-open
            , new RegexTableEl(doSetPosixProp, 58 /* : */, 128, 0, false)     //  124 
            , new RegexTableEl(doNOP, 255, 126, 0, false)     //  125 
            , new RegexTableEl(doSetLiteral, 93 /* ] */, 141, 0, true)     //  126      set-open2
            , new RegexTableEl(doNOP, 255, 131, 0, false)     //  127 
            , new RegexTableEl(doSetEnd, 93 /* ] */, 255, 0, true)     //  128      set-posix
            , new RegexTableEl(doNOP, 58 /* : */, 131, 0, false)     //  129 
            , new RegexTableEl(doRuleError, 255, 206, 0, false)     //  130 
            , new RegexTableEl(doSetEnd, 93 /* ] */, 255, 0, true)     //  131      set-start
            , new RegexTableEl(doSetBeginUnion, 91 /* [ */, 123, 148, true)     //  132 
            , new RegexTableEl(doNOP, 92 /* \ */, 191, 0, true)     //  133 
            , new RegexTableEl(doNOP, 45 /* - */, 137, 0, true)     //  134 
            , new RegexTableEl(doNOP, 38 /* & */, 139, 0, true)     //  135 
            , new RegexTableEl(doSetLiteral, 255, 141, 0, true)     //  136 
            , new RegexTableEl(doRuleError, 45 /* - */, 206, 0, false)     //  137      set-start-dash
            , new RegexTableEl(doSetAddDash, 255, 141, 0, false)     //  138 
            , new RegexTableEl(doRuleError, 38 /* & */, 206, 0, false)     //  139      set-start-amp
            , new RegexTableEl(doSetAddAmp, 255, 141, 0, false)     //  140 
            , new RegexTableEl(doSetEnd, 93 /* ] */, 255, 0, true)     //  141      set-after-lit
            , new RegexTableEl(doSetBeginUnion, 91 /* [ */, 123, 148, true)     //  142 
            , new RegexTableEl(doNOP, 45 /* - */, 178, 0, true)     //  143 
            , new RegexTableEl(doNOP, 38 /* & */, 169, 0, true)     //  144 
            , new RegexTableEl(doNOP, 92 /* \ */, 191, 0, true)     //  145 
            , new RegexTableEl(doSetNoCloseError, 253, 206, 0, false)     //  146 
            , new RegexTableEl(doSetLiteral, 255, 141, 0, true)     //  147 
            , new RegexTableEl(doSetEnd, 93 /* ] */, 255, 0, true)     //  148      set-after-set
            , new RegexTableEl(doSetBeginUnion, 91 /* [ */, 123, 148, true)     //  149 
            , new RegexTableEl(doNOP, 45 /* - */, 171, 0, true)     //  150 
            , new RegexTableEl(doNOP, 38 /* & */, 166, 0, true)     //  151 
            , new RegexTableEl(doNOP, 92 /* \ */, 191, 0, true)     //  152 
            , new RegexTableEl(doSetNoCloseError, 253, 206, 0, false)     //  153 
            , new RegexTableEl(doSetLiteral, 255, 141, 0, true)     //  154 
            , new RegexTableEl(doSetEnd, 93 /* ] */, 255, 0, true)     //  155      set-after-range
            , new RegexTableEl(doSetBeginUnion, 91 /* [ */, 123, 148, true)     //  156 
            , new RegexTableEl(doNOP, 45 /* - */, 174, 0, true)     //  157 
            , new RegexTableEl(doNOP, 38 /* & */, 176, 0, true)     //  158 
            , new RegexTableEl(doNOP, 92 /* \ */, 191, 0, true)     //  159 
            , new RegexTableEl(doSetNoCloseError, 253, 206, 0, false)     //  160 
            , new RegexTableEl(doSetLiteral, 255, 141, 0, true)     //  161 
            , new RegexTableEl(doSetBeginUnion, 91 /* [ */, 123, 148, true)     //  162      set-after-op
            , new RegexTableEl(doSetOpError, 93 /* ] */, 206, 0, false)     //  163 
            , new RegexTableEl(doNOP, 92 /* \ */, 191, 0, true)     //  164 
            , new RegexTableEl(doSetLiteral, 255, 141, 0, true)     //  165 
            , new RegexTableEl(doSetBeginIntersection1, 91 /* [ */, 123, 148, true)     //  166      set-set-amp
            , new RegexTableEl(doSetIntersection2, 38 /* & */, 162, 0, true)     //  167 
            , new RegexTableEl(doSetAddAmp, 255, 141, 0, false)     //  168 
            , new RegexTableEl(doSetIntersection2, 38 /* & */, 162, 0, true)     //  169      set-lit-amp
            , new RegexTableEl(doSetAddAmp, 255, 141, 0, false)     //  170 
            , new RegexTableEl(doSetBeginDifference1, 91 /* [ */, 123, 148, true)     //  171      set-set-dash
            , new RegexTableEl(doSetDifference2, 45 /* - */, 162, 0, true)     //  172 
            , new RegexTableEl(doSetAddDash, 255, 141, 0, false)     //  173 
            , new RegexTableEl(doSetDifference2, 45 /* - */, 162, 0, true)     //  174      set-range-dash
            , new RegexTableEl(doSetAddDash, 255, 141, 0, false)     //  175 
            , new RegexTableEl(doSetIntersection2, 38 /* & */, 162, 0, true)     //  176      set-range-amp
            , new RegexTableEl(doSetAddAmp, 255, 141, 0, false)     //  177 
            , new RegexTableEl(doSetDifference2, 45 /* - */, 162, 0, true)     //  178      set-lit-dash
            , new RegexTableEl(doSetAddDash, 91 /* [ */, 141, 0, false)     //  179 
            , new RegexTableEl(doSetAddDash, 93 /* ] */, 141, 0, false)     //  180 
            , new RegexTableEl(doNOP, 92 /* \ */, 183, 0, true)     //  181 
            , new RegexTableEl(doSetRange, 255, 155, 0, true)     //  182 
            , new RegexTableEl(doSetOpError, 115 /* s */, 206, 0, false)     //  183      set-lit-dash-escape
            , new RegexTableEl(doSetOpError, 83 /* S */, 206, 0, false)     //  184 
            , new RegexTableEl(doSetOpError, 119 /* w */, 206, 0, false)     //  185 
            , new RegexTableEl(doSetOpError, 87 /* W */, 206, 0, false)     //  186 
            , new RegexTableEl(doSetOpError, 100 /* d */, 206, 0, false)     //  187 
            , new RegexTableEl(doSetOpError, 68 /* D */, 206, 0, false)     //  188 
            , new RegexTableEl(doSetNamedRange, 78 /* N */, 155, 0, false)     //  189 
            , new RegexTableEl(doSetRange, 255, 155, 0, true)     //  190 
            , new RegexTableEl(doSetProp, 112 /* p */, 148, 0, false)     //  191      set-escape
            , new RegexTableEl(doSetProp, 80 /* P */, 148, 0, false)     //  192 
            , new RegexTableEl(doSetNamedChar, 78 /* N */, 141, 0, false)     //  193 
            , new RegexTableEl(doSetBackslash_s, 115 /* s */, 155, 0, true)     //  194 
            , new RegexTableEl(doSetBackslash_S, 83 /* S */, 155, 0, true)     //  195 
            , new RegexTableEl(doSetBackslash_w, 119 /* w */, 155, 0, true)     //  196 
            , new RegexTableEl(doSetBackslash_W, 87 /* W */, 155, 0, true)     //  197 
            , new RegexTableEl(doSetBackslash_d, 100 /* d */, 155, 0, true)     //  198 
            , new RegexTableEl(doSetBackslash_D, 68 /* D */, 155, 0, true)     //  199 
            , new RegexTableEl(doSetBackslash_h, 104 /* h */, 155, 0, true)     //  200 
            , new RegexTableEl(doSetBackslash_H, 72 /* H */, 155, 0, true)     //  201 
            , new RegexTableEl(doSetBackslash_v, 118 /* v */, 155, 0, true)     //  202 
            , new RegexTableEl(doSetBackslash_V, 86 /* V */, 155, 0, true)     //  203 
            , new RegexTableEl(doSetLiteralEscaped, 255, 141, 0, true)     //  204 
            , new RegexTableEl(doSetFinish, 255, 14, 0, false)     //  205      set-finish
            , new RegexTableEl(doExit, 255, 206, 0, true)     //  206      errorDeath
    };

    static final String[] RegexStateNames = new String[]

    {
        "",
                "start",
                "term",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "expr-quant",
                "",
                "",
                "",
                "",
                "",
                "expr-cont",
                "",
                "",
                "open-paren-quant",
                "",
                "open-paren-quant2",
                "",
                "open-paren",
                "",
                "open-paren-extended",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "open-paren-lookbehind",
                "",
                "",
                "",
                "paren-comment",
                "",
                "",
                "paren-flag",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "named-capture",
                "",
                "",
                "",
                "quant-star",
                "",
                "",
                "quant-plus",
                "",
                "",
                "quant-opt",
                "",
                "",
                "interval-open",
                "",
                "interval-lower",
                "",
                "",
                "",
                "interval-upper",
                "",
                "",
                "interval-type",
                "",
                "",
                "backslash",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "named-backref",
                "",
                "named-backref-2",
                "",
                "named-backref-3",
                "",
                "",
                "",
                "set-open",
                "",
                "",
                "set-open2",
                "",
                "set-posix",
                "",
                "",
                "set-start",
                "",
                "",
                "",
                "",
                "",
                "set-start-dash",
                "",
                "set-start-amp",
                "",
                "set-after-lit",
                "",
                "",
                "",
                "",
                "",
                "",
                "set-after-set",
                "",
                "",
                "",
                "",
                "",
                "",
                "set-after-range",
                "",
                "",
                "",
                "",
                "",
                "",
                "set-after-op",
                "",
                "",
                "",
                "set-set-amp",
                "",
                "",
                "set-lit-amp",
                "",
                "set-set-dash",
                "",
                "",
                "set-range-dash",
                "",
                "set-range-amp",
                "",
                "set-lit-dash",
                "",
                "",
                "",
                "",
                "set-lit-dash-escape",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "set-escape",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "set-finish",
                "errorDeath",
                ""
    }

    ;
}