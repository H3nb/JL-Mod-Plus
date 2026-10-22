/* SPDX-License-Identifier: Apache-2.0 */
package org.microemu.microedition.io;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import javax.microedition.io.ConnectionNotFoundException;

public class PushRegistryUnsupportedTest {
    private final PushRegistryImpl registry = new PushRegistryImpl();

    @Test public void unsupportedConnectionDoesNotPretendToRegister() {
        assertThrows(ConnectionNotFoundException.class,
                () -> registry.registerConnection("socket://:5000", "example.TestMidlet", "*"));
        assertArrayEquals(new String[0], registry.listConnections(false));
        assertArrayEquals(new String[0], registry.listConnections(true));
        assertNull(registry.getMIDlet("socket://:5000"));
        assertNull(registry.getFilter("socket://:5000"));
        assertFalse(registry.unregisterConnection("socket://:5000"));
    }

    @Test public void invalidArgumentsUseSpecifiedExceptionTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.registerConnection(null, "example.TestMidlet", "*"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.registerConnection("socket://:5000", "example.TestMidlet", null));
        assertThrows(IllegalArgumentException.class,
                () -> registry.registerConnection("", "example.TestMidlet", "*"));
        assertThrows(ClassNotFoundException.class,
                () -> registry.registerConnection("socket://:5000", null, "*"));
        assertThrows(ClassNotFoundException.class, () -> registry.registerAlarm(null, 1L));
        assertThrows(ConnectionNotFoundException.class,
                () -> registry.registerAlarm("example.TestMidlet", 1L));
        assertFalse(registry.unregisterConnection(null));
        assertNull(registry.getMIDlet(null));
        assertNull(registry.getFilter(null));
    }
}
