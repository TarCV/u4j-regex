package com.github.tarcv.u4jregex;

import com.ibm.icu.text.UnicodeSet;

enum RegexStaticSets { // 'enum' here implements the singleton pattern
    INSTANCE;  // Ptr to all lazily initialized constant
//   shared sets.

    // "Rule Char" Characters are those with special meaning, and therefore
//    need to be escaped to appear as literals in a regexp.
    final static String gRuleSet_rule_chars = "*?+[(){}^$|\\.";

    //
//   The backslash escape characters that ICU's unescape() function should be able to handle.
//
    final static String gUnescapeChars = "acefnrtuUx";

    //
//  Unicode Set pattern for Regular Expression  \w
//
    final static String gIsWordPattern = "[\\p{Alphabetic}\\p{M}\\p{Nd}\\p{Pc}\\u200c\\u200d]";

    //
//  Unicode Set Definitions for Regular Expression  \s
//
    final static  String gIsSpacePattern = "[\\p{WhiteSpace}]";

    //
//  UnicodeSets used in implementation of Grapheme Cluster detection, \X
//
    final static String gGC_ControlPattern = "[[:Zl:][:Zp:][:Cc:][:Cf:]-[:Grapheme_Extend:]]";
    final static String gGC_ExtendPattern  = "[\\p{Grapheme_Extend}]";
    final static String gGC_LPattern       = "[\\p{Hangul_Syllable_Type=L}]";
    final static String gGC_VPattern       = "[\\p{Hangul_Syllable_Type=V}]";
    final static String gGC_TPattern       = "[\\p{Hangul_Syllable_Type=T}]";
    final static String gGC_LVPattern      = "[\\p{Hangul_Syllable_Type=LV}]";
    final static String gGC_LVTPattern     = "[\\p{Hangul_Syllable_Type=LVT}]";


    final UnicodeSet[] fPropSets = new UnicodeSet[URX.URX_LAST_SET.getIndex()];      // The sets for common regex items, e.g. \s

    final UnicodeSet[] fRuleSets = new UnicodeSet[Regexcst.kRuleSet_count];    // Sets used while parsing regexp patterns.
    final UnicodeSet fUnescapeCharSet;             // Set of chars handled by unescape when
    //   encountered with a \ in a pattern.
    final UnicodeSet fRuleDigitsAlias;
    final String fEmptyText;                  // An empty string, to be used when a matcher
    //   is created with no input.

    RegexStaticSets() {
        // Initialize the shared static sets to their correct values.
        fUnescapeCharSet = new UnicodeSet().addAll(gUnescapeChars).freeze();
        fPropSets[URX.URX_ISWORD_SET.getIndex()] = new UnicodeSet().applyPattern(gIsWordPattern).freeze();
        fPropSets[URX.URX_ISSPACE_SET.getIndex()] = new UnicodeSet().applyPattern(gIsSpacePattern).freeze();
        fPropSets[URX.URX_GC_EXTEND.getIndex()] = new UnicodeSet().applyPattern(gGC_ExtendPattern).freeze();
        fPropSets[URX.URX_GC_CONTROL.getIndex()] = new UnicodeSet().applyPattern(gGC_ControlPattern).freeze();
        fPropSets[URX.URX_GC_L.getIndex()] = new UnicodeSet().applyPattern(gGC_LPattern).freeze();
        fPropSets[URX.URX_GC_V.getIndex()] = new UnicodeSet().applyPattern(gGC_VPattern).freeze();
        fPropSets[URX.URX_GC_T.getIndex()] = new UnicodeSet().applyPattern(gGC_TPattern).freeze();
        fPropSets[URX.URX_GC_LV.getIndex()] = new UnicodeSet().applyPattern(gGC_LVPattern).freeze();
        fPropSets[URX.URX_GC_LVT.getIndex()] = new UnicodeSet().applyPattern(gGC_LVTPattern).freeze();


        //
        //  "Normal" is the set of characters that don't need special handling
        //            when finding grapheme cluster boundaries.
        //
        fPropSets[URX.URX_GC_NORMAL.getIndex()] = new UnicodeSet().complement()
                .remove(0xac00, 0xd7a4)
                .removeAll(fPropSets[URX.URX_GC_CONTROL.getIndex()])
                .removeAll(fPropSets[URX.URX_GC_L.getIndex()])
                .removeAll(fPropSets[URX.URX_GC_V.getIndex()])
                .removeAll(fPropSets[URX.URX_GC_T.getIndex()])
                .freeze();

        // Sets used while parsing rules, but not referenced from the parse state table
        fRuleSets[Regexcst.kRuleSet_rule_char-128] = new UnicodeSet()
                .addAll(gRuleSet_rule_chars).complement().freeze();

        fRuleSets[Regexcst.kRuleSet_digit_char-128] = new UnicodeSet().add('0', '9').freeze();
        fRuleSets[Regexcst.kRuleSet_ascii_letter-128] = new UnicodeSet().add('A', 'Z').add('a', 'z').freeze();
        fRuleDigitsAlias = fRuleSets[Regexcst.kRuleSet_digit_char-128];

        // Finally, initialize an empty UText string for utility purposes
        fEmptyText = "";
    }
}