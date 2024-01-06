// New code and changes are © 2024 TarCV
// © 2016 and later: Unicode, Inc. and others.
// License & terms of use: http://www.unicode.org/copyright.html
/*
 *******************************************************************************
 * Copyright (C) 2014, International Business Machines Corporation and
 * others. All Rights Reserved.
 *******************************************************************************
 *
 * created on: 2014feb10
 * created by: Markus W. Scherer
 */
package com.github.tarcv.u4jregex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

// TODO: There must be a Java class for a growable array of longs without auto-boxing to Long?!
// Keep the API parallel to the C++ version for ease of porting. Port methods only as needed.
// If & when we start using something else, we might keep this as a thin wrapper for porting.
final class MutableVector64 {
    private final Supplier<RuntimeException> overflowExceptionProvider;
    private final List<LongArrayView> validViews = new ArrayList<>();

    // Porting helpers. Meant to be inlined as soon as possible and thus not be called from anywhere.
    private int capacity() {
        return buffer.length;
    }

    private int count() {
        return length;
    }
    @SuppressWarnings("SuspiciousGetterSetter")
    private void setCount(final int newCount) {
        length = newCount;
    }
    private long[] elements() {
        return buffer;
    }
    @SuppressWarnings("SuspiciousGetterSetter")
    private void setElements(final long[] newElements) {
        buffer = newElements;
    }

    public MutableVector64() {
        this(VectorOverflowException::new);
    }

    public MutableVector64(final Supplier<RuntimeException> overflowExceptionProvider) {
        this.overflowExceptionProvider = overflowExceptionProvider;
    }

    public boolean isEmpty() { return length == 0; }
    public int size() { return length; }
    public long elementAti(final int i) { return buffer[i]; }

    public void addElement(final long e) {
        ensureAppendCapacity();
        buffer[length++] = e;
    }
    public void setElementAt(final long elem, final int index) { buffer[index] = elem; }
    public void insertElementAt(final long elem, final int index) {
        ensureAppendCapacity();
        System.arraycopy(buffer, index, buffer, index + 1, length - index);
        buffer[index] = elem;
        ++length;
    }
    public void removeAllElements() {
        for (LongArrayView view : validViews) {
            view.invalidate();
        }
        length = 0;
    }

    private void ensureAppendCapacity() {
        expandCapacity(length + 1);
    }

    private void expandCapacity(final int minimumCapacity) {
        if (minimumCapacity < 0) {
            throw new IllegalArgumentException();
        }
        if (buffer.length >= minimumCapacity) {
            return;
        }
        if (maxCapacity>0 && minimumCapacity>maxCapacity) {
            throw overflowExceptionProvider.get();
        }
        int newCap = buffer.length <= 0xffff ? 4 * buffer.length : 2 * buffer.length;
        if (newCap < minimumCapacity) {
            newCap = minimumCapacity;
        }
        if (maxCapacity > 0 && newCap > maxCapacity) {
            newCap = maxCapacity;
        }
        long[] newElems = new long[newCap];
        System.arraycopy(buffer, 0, newElems, 0, buffer.length);
        buffer = newElems;
        assert newCap == buffer.length;
    }
    private long[] buffer = new long[32];
    private int length = 0;

    private int maxCapacity = 0;

    void setMaxCapacity(final int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException();
        }
        maxCapacity = limit;
        if (buffer.length <= maxCapacity || maxCapacity == 0) {
            // Current capacity is within the new limit.
            return;
        }

        // New maximum capacity is smaller than the current size.
        // Realloc the storage to the new, smaller size.
        long[] newElems = new long[maxCapacity];
        System.arraycopy(buffer, 0, newElems, 0, maxCapacity);
        buffer = newElems;
        assert maxCapacity == buffer.length;
        if (length > buffer.length) {
            length = buffer.length;
        }
    }

    void ensureCapacity(final int minimumCapacity) {
        if (minimumCapacity < 0) {
            throw new IllegalArgumentException();
        }
        if (buffer.length < minimumCapacity) {
            expandCapacity(minimumCapacity);
        }
    }

    REStackFrame reserveBlock(final int size) {
        ensureCapacity(length + size);
        Vector64View rp = new Vector64View(this, length);
        length += size;
        return new REStackFrame(rp, size);
    }

    REStackFrame getLastBlock(final int size) {
        return new REStackFrame(
                new Vector64View(this, length - size),
                size
        );
    }

    REStackFrame popFrame(final int size) {
        if (length < size) {
            throw new IllegalStateException();
        }
        length -= size;
        if (length < 0) {
            length = 0;
        }
        return new REStackFrame(
                new Vector64View(this, length - size),
                size
        );
    }

    public void setSize(final int newSize) {
        if (newSize < 0) {
            return;
        }
        if (newSize > length) {
            ensureCapacity(newSize);
            Arrays.fill(buffer, length, newSize, 0);
        }
        length = newSize;
    }

    public long popi() {
        long result = 0;
        if (length > 0) {
            length = length - 1;
            result = buffer[length];
        }
        return result;
    }

    public long push(final long i) {
        addElement(i);
        return i;
    }

    IndexedView getBufferView(final int offset) {
        LongArrayView view = new LongArrayView(buffer, offset);
        validViews.add(view);
        return view;
    }
}
