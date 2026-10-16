/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.h3nb.jlmodplus.crashes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class ManualLogCaptureTest {
    @Test
    public void tailKeepsLatestBytesWithoutGrowingWithInput() {
        ManualLogCapture.Tail tail = new ManualLogCapture.Tail(4);
        tail.write("abcdef".getBytes(StandardCharsets.UTF_8), 6);
        assertEquals("cdef", new String(tail.bytes(), StandardCharsets.UTF_8));
        assertTrue(tail.truncated);
    }

    @Test
    public void recordsAreCountedSeparatelyFromMultilineStackFrames() {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 520; i++) {
            log.append("10-16 15:00:00.123  1122  3344 E Test: record ").append(i).append('\n');
            if (i == 519) log.append("    at com.example.Game.run(Game.java:42)\n");
        }
        ManualLogCapture.Parsed parsed = ManualLogCapture.parseRecent(
                log.toString().getBytes(StandardCharsets.UTF_8), false);
        assertEquals(500, parsed.records);
        assertTrue(parsed.truncated);
        assertFalse(parsed.text.contains("record 0\n"));
        assertTrue(parsed.text.contains("record 519\n"));
        assertTrue(parsed.text.contains("at com.example.Game.run"));
    }

    @Test
    public void countReflectsRecordsAfterPayloadIsTrimmed() {
        String first = "10-16 15:00:00.123  100  200 I Demo: old\\n";
        String second = "10-16 15:00:01.123  100  200 I Demo: new\\n";
        assertEquals(2, ManualLogCapture.countRecords(first + second));
        assertEquals(1, ManualLogCapture.countRecords(second));
        assertEquals(0, ManualLogCapture.countRecords("partial tail\\n"));
    }

    @Test
    public void truncatedInputDiscardsIncompleteFirstRecord() {
        String log = "partial tail\n10-16 15:00:00.123  100  200 I Demo: valid\n";
        ManualLogCapture.Parsed parsed = ManualLogCapture.parseRecent(
                log.getBytes(StandardCharsets.UTF_8), true);
        assertEquals(1, parsed.records);
        assertFalse(parsed.text.contains("partial tail"));
        assertTrue(parsed.truncated);
    }
}
