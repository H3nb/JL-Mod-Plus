/* SPDX-License-Identifier: Apache-2.0 */
package org.microemu.microedition.io;
import static org.junit.Assert.*;
import org.junit.Test;
import javax.microedition.io.ConnectionNotFoundException;

public class PushRegistryUnsupportedTest {
    private final PushRegistryImpl impl = new PushRegistryImpl();
    @Test public void unsupportedRegistrationIsNeverReportedSuccessful() {
        assertThrows(ConnectionNotFoundException.class,
                () -> impl.registerConnection("socket://:1234", "example.App", "*"));
        assertThrows(ConnectionNotFoundException.class,
                () -> impl.registerConnection("datagram://:5678", "example.App", "*"));
        assertArrayEquals(new String[0], impl.listConnections(false));
        assertNull(impl.getMIDlet("socket://:1234"));
        assertNull(impl.getFilter("socket://:1234"));
        assertFalse(impl.unregisterConnection("socket://:1234"));
    }
    @Test public void nullArgumentsUseSpecifiedExceptions() {
        assertThrows(IllegalArgumentException.class, () -> impl.registerConnection(null, "App", "*"));
        assertThrows(IllegalArgumentException.class, () -> impl.registerConnection("socket://:1", "App", null));
        assertThrows(ClassNotFoundException.class, () -> impl.registerConnection("socket://:1", null, "*"));
        assertThrows(IllegalArgumentException.class, () -> impl.registerConnection("bad-url", "App", "*"));
        assertThrows(ClassNotFoundException.class, () -> impl.registerAlarm(null, 123L));
    }
}
