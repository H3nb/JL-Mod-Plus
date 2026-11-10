/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package javax.microedition.lcdui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Executes the actual Android picker confirmation and the MIDP Form event boundary. */
@RunWith(AndroidJUnit4.class)
public class DateFieldInteractionTest {
    @Test
    public void uninitializedPreviewCanBeAcceptedWithoutChangingDateAndNotifiesOnce()
            throws Exception {
        DateField field = new DateField("Select a date", DateField.DATE);
        Form form = new Form("Date");
        AtomicInteger notifications = new AtomicInteger();
        CountDownLatch changed = new CountDownLatch(1);
        form.setItemStateListener(item -> {
            if (item == field) {
                notifications.incrementAndGet();
                changed.countDown();
            }
        });
        form.append(field);

        final Button[] button = new Button[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LinearLayout content = (LinearLayout) field.getItemContentView();
            button[0] = (Button) content.getChildAt(content.getChildCount() - 1);
            assertEquals(View.VISIBLE, button[0].getVisibility());
            assertNull(field.getDate());
            assertTrue(button[0].performClick());
            assertNotNull(field.getDate());
            assertEquals(View.GONE, button[0].getVisibility());
            // An already-initialized value must not trigger another guest event.
            button[0].performClick();
        });

        assertTrue("Guest ItemStateListener was not notified", changed.await(3, TimeUnit.SECONDS));
        assertEquals(1, notifications.get());
        // Programmatic clearing must not notify the guest or permanently hide the affordance.
        field.setDate(null);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertNull(field.getDate());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                assertEquals(View.VISIBLE, button[0].getVisibility()));
        assertEquals(1, notifications.get());
    }
}
