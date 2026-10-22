/* SPDX-License-Identifier: Apache-2.0 */
package javax.microedition.lcdui;

import static org.junit.Assert.*;
import android.text.InputType;
import android.widget.EditText;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MidpStageCTextDeviceTest {
    @Test public void textboxAnyIsMultilineAndUserInputReachesMidpValue() {
        final TextFieldImpl model = new TextFieldImpl();
        model.setMaxSize(50);
        model.setConstraints(TextField.ANY);
        final EditText[] view = new EditText[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            view[0] = model.getView(InstrumentationRegistry.getInstrumentation().getTargetContext(), null, true);
            assertTrue((view[0].getInputType() & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0);
            view[0].setText("first\nsecond");
            assertEquals("first\nsecond", model.getString());
            model.setString("guest\nupdate");
            assertEquals("guest\nupdate", view[0].getText().toString());
        });
    }

    @Test public void incompleteNumericDraftNeverLeaksToGuestAndOldViewsCannotOverwrite() {
        final TextFieldImpl model = new TextFieldImpl();
        model.setMaxSize(16);
        model.setConstraints(TextField.NUMERIC);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            EditText editor = model.getView(InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
            editor.setText("-");
            assertEquals("", model.getString());
            editor.setText("-15");
            assertEquals("-15", model.getString());
            model.clearScreenView();
            editor.setText("12345");
            assertEquals("-15", model.getString());
        });
    }

    @Test public void apiChangesDoNotSendUserItemStateEvents() {
        final TextFieldImpl model = new TextFieldImpl();
        model.setMaxSize(30);
        model.setConstraints(TextField.ANY);
        final CountingItem item = new CountingItem();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            EditText editor = model.getView(InstrumentationRegistry.getInstrumentation().getTargetContext(), item);
            model.setString("guest");
            assertEquals(0, item.events);
            editor.setText("user");
            assertEquals(1, item.events);
            model.insert("!", 4);
            assertEquals(1, item.events);
        });
    }

    @Test public void sensitiveInputDisablesImeLearningAndCanBeRestored() {
        final TextFieldImpl model = new TextFieldImpl();
        model.setMaxSize(30);
        model.setConstraints(TextField.ANY | TextField.PASSWORD);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            EditText editor = model.getView(InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
            assertTrue((editor.getImeOptions() & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0);
            assertNotNull(editor.getTransformationMethod());
            model.setConstraints(TextField.ANY | TextField.PASSWORD | TextField.UNEDITABLE);
            assertFalse(editor.isTextSelectable());
            model.setConstraints(TextField.ANY);
            assertEquals(0, editor.getImeOptions() & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            assertNull(editor.getTransformationMethod());
        });
    }

    private static final class CountingItem extends Item {
        int events;
        @Override void postStateChanged() { events++; }
        @Override View getItemContentView() { return null; }
        @Override void clearItemContentView() {}
    }

    @Test public void programmaticChangesDoNotBounceThroughEditorAsUserEdits() {
        final TextFieldImpl model = new TextFieldImpl();
        model.setMaxSize(20);
        model.setConstraints(TextField.ANY);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            EditText editor = model.getView(InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
            model.setString("A");
            assertEquals("A", editor.getText().toString());
            model.insert("B", 1);
            assertEquals("AB", model.getString());
            assertEquals("AB", editor.getText().toString());
            model.setMaxSize(1);
            assertEquals("A", editor.getText().toString());
            assertEquals("A", model.getString());
        });
    }
}
