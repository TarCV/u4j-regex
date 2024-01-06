package com.github.tarcv.u4jregex;

@FunctionalInterface
public interface URegexFindProgressCallback {
    boolean onProgress(Object context, long matchIndex);
}
