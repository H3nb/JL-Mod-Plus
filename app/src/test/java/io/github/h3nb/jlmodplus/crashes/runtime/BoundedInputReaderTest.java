/*
 * Copyright 2026 H3NB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.h3nb.jlmodplus.crashes.runtime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;

public class BoundedInputReaderTest {
    @Test
    public void smallerInputIsNotTruncated() throws Exception {
        byte[] input = new byte[]{1, 2, 3};
        BoundedInputReader.Result result = BoundedInputReader.read(
                new ByteArrayInputStream(input), 8);

        assertArrayEquals(input, result.data);
        assertFalse(result.truncated);
    }

    @Test
    public void exactLimitIsNotTruncated() throws Exception {
        byte[] input = new byte[]{1, 2, 3, 4};
        BoundedInputReader.Result result = BoundedInputReader.read(
                new ByteArrayInputStream(input), 4);

        assertArrayEquals(input, result.data);
        assertFalse(result.truncated);
    }

    @Test
    public void largerInputIsTruncatedAtLimit() throws Exception {
        byte[] input = new byte[]{1, 2, 3, 4, 5, 6};
        BoundedInputReader.Result result = BoundedInputReader.read(
                new ByteArrayInputStream(input), 4);

        assertArrayEquals(new byte[]{1, 2, 3, 4}, result.data);
        assertTrue(result.truncated);
    }
}
