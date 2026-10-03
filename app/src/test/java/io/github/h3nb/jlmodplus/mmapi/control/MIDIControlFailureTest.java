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
    @Test public void deviceFailureReturnsMinusOneForLongAndFailsSilentlyForShort() {
        AtomicReference<Throwable> failure = new AtomicReference<>(new MediaException("MIDI queue full"));
        Library backend = failingBackend(failure);
        Player player = prefetched();
        MIDIControlImpl control = new MIDIControlImpl(player, backend, 1);
        assertEquals(-1, control.longMidiEvent(new byte[]{(byte) 0xf8}, 0, 1));
        control.shortMidiEvent(0x80, 60, 0);
        assertEquals(Player.PREFETCHED, player.getState());
    }

    @Test public void atomicQueueRejectionDoesNotClosePlayerAndDeliveryCanRecover() {
        AtomicInteger writes = new AtomicInteger();
        Library backend = (Library) Proxy.newProxyInstance(Library.class.getClassLoader(),
                new Class[]{Library.class}, (proxy, method, args) -> {
                    if (method.getName().equals("writeMIDI")) {
                        return writes.incrementAndGet() <= 2 ? -1 : (int) args[3];
                    }
                    throw new AssertionError("Queue rejection changed lifecycle: " + method.getName());
                });
        Player player = prefetched();
        MIDIControlImpl control = new MIDIControlImpl(player, backend, 1);
        control.shortMidiEvent(0x80, 60, 0);
        assertEquals(-1, control.longMidiEvent(new byte[]{(byte) 0xf8}, 0, 1));
        assertEquals(1, control.longMidiEvent(new byte[]{(byte) 0xf8}, 0, 1));
        assertEquals(Player.PREFETCHED, player.getState());
    }

    @Test public void outputDenialFailsSilentlyButValidationPrecedesDelivery() {
        AtomicInteger preparations = new AtomicInteger();
        AtomicInteger state = new AtomicInteger(Player.PREFETCHED);
        Player player = new BasePlayer() {
            @Override public int getState() { return state.get(); }
        };
        MIDIControlImpl control = new MIDIControlImpl(player,
                failingBackend(new AtomicReference<>(new AssertionError("Denied output attempted MIDI"))), 1,
                () -> {
                    preparations.incrementAndGet();
                    throw new IllegalStateException("Audio focus denied");
                });
        control.shortMidiEvent(0x90, 60, 100);
        assertEquals(-1, control.longMidiEvent(new byte[]{(byte) 0xf8}, 0, 1));
        assertEquals(Player.PREFETCHED, player.getState());
        assertEquals(2, preparations.get());
        assertThrows(IllegalArgumentException.class, () -> control.shortMidiEvent(0xf0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> control.shortMidiEvent(0x90, 128, 0));
        assertThrows(IllegalArgumentException.class,
                () -> control.longMidiEvent(new byte[1], Integer.MAX_VALUE, Integer.MAX_VALUE));
        state.set(Player.REALIZED);
        assertThrows(IllegalStateException.class, () -> control.shortMidiEvent(0x90, 60, 100));
        assertThrows(IllegalStateException.class,
                () -> control.longMidiEvent(new byte[]{(byte) 0xf8}, 0, 1));
        assertEquals(2, preparations.get());
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
