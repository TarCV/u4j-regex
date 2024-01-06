package com.github.tarcv.u4jregex;

@FunctionalInterface
public interface URegexMatchCallback {
    boolean onMatch(Object context, int steps);
}
