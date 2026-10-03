// SPDX-License-Identifier: Apache-2.0
package javax.microedition.media;

import static org.junit.Assert.*;

import org.junit.Test;

public class InternalMetaDataTest {
    @Test public void demuxedKeysAreUniqueAndInvalidKeysThrowWhileValidUnavailableValuesRemainNull() {
        InternalMetaData metadata = new InternalMetaData();
        metadata.updateDemuxerMetaData(new String[]{"TITLE", "Nada 🟢", "title", "Later",
                "track", "2", "copyright", null});
        assertArrayEquals(new String[]{"title", "tracknum", "copyright"}, metadata.getKeys());
        assertEquals("Later", metadata.getKeyValue("title"));
        assertNull(metadata.getKeyValue("copyright"));
        for (String invalid : new String[]{null, "TITLE", "missing"}) {
            try { metadata.getKeyValue(invalid); fail("Invalid metadata key accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        metadata.updateDemuxerMetaData(new String[]{"artist", "Replacement"});
        try { metadata.getKeyValue("title"); fail("Old metadata key retained"); }
        catch (IllegalArgumentException expected) { }
    }
}
