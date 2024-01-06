package com.github.tarcv.u4jregex;

public enum URegexpFlag {
    /** Forces normalization of pattern and strings.
     Not implemented yet, just a placeholder. */
    UREGEX_CANON_EQ(128),

    /**
     * Enable case insensitive matching.  @since 74.2
     */
    UREGEX_CASE_INSENSITIVE(2),

    /**
     * Allow white space and comments within patterns  @since 74.2
     */
    UREGEX_COMMENTS(4),

    /**
     * If set, '.' matches line terminators,  otherwise '.' matching stops at line end.
     *
     * @since 74.2
     */
    UREGEX_DOTALL(32),

    /**
     * If set, treat the entire pattern as a literal string.
     * Metacharacters or escape sequences in the input sequence will be given
     * no special meaning.
     * <p>
     * The flag UREGEX_CASE_INSENSITIVE retains its impact
     * on matching when used in conjunction with this flag.
     * The other flags become superfluous.
     *
     * @since 74.2
     */
    UREGEX_LITERAL(16),

    /**
     * Control behavior of "$" and "^"
     * If set, recognize line terminators within string,
     * otherwise, match only at start and end of input string.
     *
     * @since 74.2
     */
    UREGEX_MULTILINE(8),

    /**
     * Unix-only line endings.
     * When this mode is enabled, only \\u000a is recognized as a line ending
     * in the behavior of ., ^, and $.
     *
     * @since 74.2
     */
    UREGEX_UNIX_LINES(1),

    /**
     * Unicode word boundaries.
     * If set, \b uses the Unicode TR 29 definition of word boundaries.
     * Warning: Unicode word boundaries are quite different from
     * traditional regular expression word boundaries.  See
     * http://unicode.org/reports/tr29/#Word_Boundaries
     *
     * @since 74.2
     */
    UREGEX_UWORD(256),

    /**
     * Error on Unrecognized backslash escapes.
     * If set, fail with an error on patterns that contain
     * backslash-escaped ASCII letters without a known special
     * meaning.  If this flag is not set, these
     * escaped letters represent themselves.
     *
     * @since 74.2
     */
    UREGEX_ERROR_ON_UNKNOWN_ESCAPES(512);

    final long flag;

    URegexpFlag(final int flag) {
        this.flag = flag;
    }
}
