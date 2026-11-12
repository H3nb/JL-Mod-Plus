// SPDX-License-Identifier: Apache-2.0
package javax.microedition.lcdui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextureUploadRetryTest {
    @Test
    void temporaryFailureAllowsTheSameImageAfterCooldown() {
        TextureUploadRetry retry = new TextureUploadRetry();
        long now = 1_000_000_000L;
        retry.recordFailure(240, 320, 1, true, now);

        assertTrue(retry.shouldSkip(240, 320, 1, now + 499_000_000L));
        assertFalse(retry.shouldSkip(240, 320, 1, now + 500_000_000L));

        retry.recordFailure(240, 320, 1, true, now + 500_000_000L);
        assertTrue(retry.shouldSkip(240, 320, 1, now + 800_000_000L));
        retry.reset();
        assertFalse(retry.shouldSkip(240, 320, 1, now + 800_000_000L));
    }

    @Test
    void permanentFailureAppliesOnlyToTheFailedSurface() {
        TextureUploadRetry retry = new TextureUploadRetry();
        retry.recordFailure(2400, 3200, 10, false, 0L);

        assertTrue(retry.shouldSkip(2400, 3200, 10, Long.MAX_VALUE - 1));
        assertFalse(retry.shouldSkip(2400, 3200, 1, 1L));
        assertFalse(retry.shouldSkip(240, 320, 10, 1L));
    }
}
