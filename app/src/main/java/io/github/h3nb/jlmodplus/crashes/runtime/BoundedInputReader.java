/*
 * Copyright 2026 H3NB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.h3nb.jlmodplus.crashes.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Reads at most a fixed number of bytes while detecting whether data was truncated. */
final class BoundedInputReader {
    private BoundedInputReader() {
    }

    static Result read(InputStream input, int maxBytes) throws IOException {
        if (input == null) {
            throw new NullPointerException("input");
        }
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must be non-negative");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        int remaining = maxBytes;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) {
                return new Result(output.toByteArray(), false);
            }
            if (read == 0) {
                continue;
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }

        boolean truncated = input.read() >= 0;
        return new Result(output.toByteArray(), truncated);
    }

    static final class Result {
        final byte[] data;
        final boolean truncated;

        Result(byte[] data, boolean truncated) {
            this.data = data;
            this.truncated = truncated;
        }
    }
}
