// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.mmapi.control;

import static org.junit.Assert.*;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.microedition.media.BasePlayer;
import javax.microedition.media.MediaException;
import javax.microedition.media.Player;

import io.github.h3nb.jlmodplus.mmapi.synth.Library;

public class MIDIControlFailureTest {
    @Test public void deviceFailureReturnsMinusOneForLongAndExplicitRuntimeErrorForShort() {
        AtomicReference<Throwable> failure = new AtomicReference<>(new MediaException("MIDI queue full"));
        Library backend = failingBackend(failure);
        MIDIControlImpl control = new MIDIControlImpl(prefetched(), backend, 1);
        assertEquals(-1, control.longMidiEvent(new byte[]{(byte) 0xf8}, 0, 1));
        IllegalStateException shortFailure = assertThrows(IllegalStateException.class,
                () -> control.shortMidiEvent(0x80, 60, 0));
        assertNotNull(shortFailure.getCause());
    }

    @Test public void zeroLengthEventDoesNotActivateOutputAndAllocationFailureIsNotHidden() {
        AtomicInteger preparations = new AtomicInteger();
        OutOfMemoryError oom = new OutOfMemoryError("Test backend allocation failure");
        AtomicReference<Throwable> failure = new AtomicReference<>(oom);
        MIDIControlImpl control = new MIDIControlImpl(prefetched(), failingBackend(failure), 1,
                preparations::incrementAndGet);
        assertEquals(0, control.longMidiEvent(new byte[0], 0, 0));
        assertEquals(0, preparations.get());
        assertSame(oom, assertThrows(OutOfMemoryError.class,
                () -> control.longMidiEvent(new byte[]{(byte) 0xf8}, 0, 1)));
        assertSame(oom, assertThrows(OutOfMemoryError.class,
                () -> control.shortMidiEvent(0x80, 60, 0)));
    }

    private static Player prefetched() {
        return new BasePlayer() {
            @Override public int getState() { return Player.PREFETCHED; }
        };
    }

    private static Library failingBackend(AtomicReference<Throwable> failure) {
        return (Library) Proxy.newProxyInstance(Library.class.getClassLoader(), new Class[]{Library.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("writeMIDI")) throw failure.get();
                    throw new AssertionError("Unexpected backend operation: " + method.getName());
                });
    }
}
