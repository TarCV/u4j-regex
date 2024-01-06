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

// TODO: There must be a Java class for a growable array of ints without auto-boxing to Integer?!
// Keep the API parallel to the C++ version for ease of porting. Port methods only as needed.
// If & when we start using something else, we might keep this as a thin wrapper for porting.
final class MutableVector32 {
    // Porting helpers. Meant to be inlined as soon as possible and thus not to be called from anywhere.
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
    private int[] elements() {
        return buffer;
    }
    @SuppressWarnings("SuspiciousGetterSetter")
    private void setElements(final int[] newElements) {
        buffer = newElements;
    }

    public MutableVector32() {
        this(-1);
    }

    public MutableVector32(final int initialCapacity) {
        int fixedCapacity = initialCapacity;
        if (fixedCapacity < 1) {
            fixedCapacity = 32;
        }
        buffer = new int[fixedCapacity];
    }
    public boolean isEmpty() { return length == 0; }
    public int size() { return length; }
    public int elementAti(final int i) { return buffer[i]; }
    public void addElement(final int e) {
        ensureAppendCapacity();
        buffer[length++] = e;
    }
    public void setElementAt(final int elem, final int index) { buffer[index] = elem; }
    public void insertElementAt(final int elem, final int index) {
        ensureAppendCapacity();
        System.arraycopy(buffer, index, buffer, index + 1, length - index);
        buffer[index] = elem;
        ++length;
    }
    public void removeAllElements() {
        length = 0;
    }

    private void ensureAppendCapacity() {
        ensureCapacity(length + 1);
    }

    void ensureCapacity(final int minimumCapacity) {
        if (minimumCapacity < 0) {
            throw new IllegalArgumentException();
        }
        if (buffer.length < minimumCapacity) {
            expandCapacity(minimumCapacity);
        }
    }

    void expandCapacity(final int minimumCapacity) {
        if (minimumCapacity < 0) {
            throw new IllegalArgumentException();
        }
        if (buffer.length >= minimumCapacity) {
            return;
        }
        int newCap = buffer.length <= 0xffff ? 4 * buffer.length : 2 * buffer.length;
        if (newCap < minimumCapacity) {
            newCap = minimumCapacity;
        }
        int[] newElems = new int[newCap];
        System.arraycopy(buffer, 0, newElems, 0, length);
        buffer = newElems;
        assert newCap == buffer.length;
    }

    private int[] buffer;
    private int length = 0;

    public int popi() {
        int result = 0;
        if (length > 0) {
            length--;
            result = buffer[length];
        }
        return result;
    }

    public int push(final int i) {
        addElement(i);
        return i;
    }

    public void removeElementAt(final int index) {
        if (index >= 0) {
            for (int i = index; i< length -1; ++i) {
                buffer[i] = buffer[i+1];
            }
            length = length - 1;
        }
    }

    /**
     * Change the size of this vector as follows: If newSize is smaller,
     * then truncate the array, possibly deleting held elements for i >=
     * newSize.  If newSize is larger, grow the array, filling in new
     * slots with 0.
     */
    void setSize(final int newSize) {
        int i;
        if (newSize < 0) {
            return;
        }
        if (newSize > length) {
            ensureCapacity(newSize);
            for (i= length; i<newSize; ++i) {
                buffer[i] = 0;
            }
        }
        length = newSize;
    }
}
