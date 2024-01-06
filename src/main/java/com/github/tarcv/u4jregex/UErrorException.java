package com.github.tarcv.u4jregex;

public class UErrorException extends RuntimeException { // TODO: Remove this class
    private final UErrorCode status;

    public UErrorException(UErrorCode status) {
        this.status = status;
    }

    public UErrorException(UErrorCode status, Throwable cause) {
        super(cause);
        this.status = status;
    }

    public UErrorCode getErrorCode() {
        return status;
    }

    @Override
    public String toString() {
        return "UErrorException{" +
                "status=" + status +
                '}';
    }
}
