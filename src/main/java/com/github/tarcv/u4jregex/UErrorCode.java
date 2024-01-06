package com.github.tarcv.u4jregex;

public enum UErrorCode { // TODO: Replace this class with existing exceptions from ICU4J or RegexParseException subclasses
    U_ZERO_ERROR,
    U_UNSUPPORTED_ERROR,
    U_ILLEGAL_ARGUMENT_ERROR,
    /*
     * Error codes in the range 0x10300-0x103ff are reserved for regular expression related errors.
     */
    U_REGEX_INTERNAL_ERROR(0x10300),       /**< An internal error (bug) was detected.              */
    U_REGEX_ERROR_START(0x10300),          /**< Start of codes indicating Regexp failures          */
    U_REGEX_RULE_SYNTAX,                  /**< Syntax error in regexp pattern.                    */
    U_REGEX_INVALID_STATE,                /**< RegexMatcher in invalid state for requested operation */
    U_REGEX_BAD_ESCAPE_SEQUENCE,          /**< Unrecognized backslash escape sequence in pattern  */
    U_REGEX_PROPERTY_SYNTAX,              /**< Incorrect Unicode property                         */
    U_REGEX_UNIMPLEMENTED,                /**< Use of regexp feature that is not yet implemented. */
    U_REGEX_MISMATCHED_PAREN,             /**< Incorrectly nested parentheses in regexp pattern.  */
    U_REGEX_NUMBER_TOO_BIG,               /**< Decimal number is too large.                       */
    U_REGEX_BAD_INTERVAL,                 /**< Error in {min,max} interval                        */
    U_REGEX_MAX_LT_MIN,                   /**< In {min,max}, max is less than min.                */
    U_REGEX_INVALID_BACK_REF,             /**< Back-reference to a non-existent capture group.    */
    U_REGEX_INVALID_FLAG,                 /**< Invalid value for match mode flags.                */
    U_REGEX_LOOK_BEHIND_LIMIT,            /**< Look-Behind pattern matches must have a bounded maximum length.    */
    U_REGEX_SET_CONTAINS_STRING,          /**< Regexps cannot have UnicodeSets containing strings.*/
    U_REGEX_OCTAL_TOO_BIG,                /**< Octal character constants must be <= 0377. @deprecated ICU4C 54. This error cannot occur. */
    U_REGEX_MISSING_CLOSE_BRACKET,        /**< Missing closing bracket on a bracket expression. */
    U_REGEX_INVALID_RANGE,                /**< In a character range [x-y], x is greater than y.   */
    U_REGEX_STACK_OVERFLOW,               /**< Regular expression backtrack stack overflow.       */
    U_REGEX_TIME_OUT,                     /**< Maximum allowed match time exceeded                */
    U_REGEX_STOPPED_BY_CALLER,            /**< Matching operation aborted by user callback fn.    */
    U_REGEX_PATTERN_TOO_BIG,              /**< Pattern exceeds limits on size or complexity. @stable ICU4C 55 */
    U_REGEX_INVALID_CAPTURE_GROUP_NAME,   /**< Invalid capture group name. @stable ICU4C 55 */
    /**
     * One more than the highest normal regular expression error code.
     * @deprecated ICU4C 58 The numeric value may change over time, see ICU4C ticket #12420.
     */
    U_REGEX_ERROR_LIMIT,
    ;

    private final int index;

    UErrorCode(final int index) {
        this.index = index;
    }

    UErrorCode() {
        this.index = -1;
    }

    public int getIndex() {
        if (index >= 0) {
            return index;
        } else {
            return UErrorCode.values()[ordinal() - 1].getIndex() + 1;
        }
    }
}