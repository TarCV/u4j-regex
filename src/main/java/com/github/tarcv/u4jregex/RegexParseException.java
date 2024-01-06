package com.github.tarcv.u4jregex;

public class RegexParseException extends UErrorException {
    private final int line;
    private final int offset;
    private final char[] preContext;
    private final char[] postContext;

    public RegexParseException(UErrorCode errorCode, int line, int offset, char[] preContext, char[] postContext, Throwable cause) {
        super(errorCode, cause);
        this.line = line;
        this.offset = offset;
        this.preContext = preContext;
        this.postContext = postContext;
    }

    public int getLine() {
        return line;
    }

    public int getOffset() {
        return offset;
    }

    public char[] getPreContext() {
        return preContext;
    }

    public char[] getPostContext() {
        return postContext;
    }
}
