/* SPDX-License-Identifier: Apache-2.0 */
package javax.microedition.midlet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class PlatformPackageLinkTest {
    @Test public void identifiesPackageLinksWithoutTreatingAllUrlsAsInstallerRequests() {
        assertTrue(PlatformPackageLink.isPackageLink("https://example.com/download/WonderZoo.jar"));
        assertTrue(PlatformPackageLink.isPackageLink("http://example.com/game.jad?key=42"));
        assertTrue(PlatformPackageLink.isPackageLink(
                "https://example.com/en/buffer_mwap.jsp?floc=%2Fload-game%2F54K%2FWonderZoo.jar"));
        assertTrue(PlatformPackageLink.isPackageLink("file://localhost/root/example.KJX"));
        assertFalse(PlatformPackageLink.isPackageLink("https://example.com/news"));
        assertFalse(PlatformPackageLink.isPackageLink("https://example.com/wonder.jar/info"));
        assertFalse(PlatformPackageLink.isPackageLink("tel:12345"));
        assertFalse(PlatformPackageLink.isPackageLink(""));
        assertFalse(PlatformPackageLink.isPackageLink(null));
    }
}
