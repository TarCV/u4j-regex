package com.github.tarcv.u4jregex;

public enum StartOfMatch {
    START_NO_INFO,             // No hint available.
    START_CHAR,                // Match starts with a literal code point.
    START_SET,                 // Match starts with something matching a set.
    START_START,               // Match starts at start of buffer only (^ or \A)
    START_LINE,                // Match starts with ^ in multi-line mode.
    START_STRING               // Match starts with a literal string.
}
