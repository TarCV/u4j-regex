package com.github.tarcv.u4jregex;

import org.junit.Assert;

import java.util.function.Supplier;

public class LambdaAssert {
    public static void assertFalse(final Supplier<String> messageSupplier, final boolean actual) {
        try {
            Assert.assertFalse(actual);
        } catch (AssertionError e) {
            String oldMessage = e.getMessage();
            if (oldMessage == null) {
                throw new AssertionError(messageSupplier.get(), e);
            } else {
                throw new AssertionError(messageSupplier.get() + ": " + oldMessage, e);
            }
        }
    }
}
