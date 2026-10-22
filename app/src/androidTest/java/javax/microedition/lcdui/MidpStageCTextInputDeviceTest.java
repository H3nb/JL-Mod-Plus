/* SPDX-License-Identifier: Apache-2.0 */
package javax.microedition.lcdui;

import static org.junit.Assert.*;

import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.widget.EditText;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Uses the real Android EditText adapter, not just the Java-side MIDP model. */
@RunWith(AndroidJUnit4.class)
public class MidpStageCTextInputDeviceTest {
    private final android.app.Instrumentation instrumentation =
            InstrumentationRegistry.getInstrumentation();

    @Test public void textBoxUrlWrapsAndProgrammaticChangesDoNotEmitItemState() {
        TextFieldImpl editorModel = new TextFieldImpl();
        editorModel.setMaxSize(5524);
        editorModel.setConstraints(TextField.URL);
        editorModel.setString("https://example.com/really/long/url/that/should/wrap.jar");

        final EditText[] box = new EditText[1];
        instrumentation.runOnMainSync(() -> box[0] = editorModel.getView(
                instrumentation.getTargetContext(), null));
        assertNotNull(box[0]);
        assertTrue((box[0].getInputType() & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0);
        assertEquals(editorModel.getString().length(), box[0].getSelectionEnd());

        editorModel.setString("http://example.org/new");
        instrumentation.waitForIdleSync();
        assertEquals("http://example.org/new", box[0].getText().toString());
        assertEquals(editorModel.getString(), box[0].getText().toString());
        editorModel.clearScreenView();
    }

    @Test public void numericDraftNeverLeaksInvalidValueToMidp() {
        TextFieldImpl model = new TextFieldImpl();
        model.setMaxSize(8);
        model.setConstraints(TextField.NUMERIC);
        final EditText[] view = new EditText[1];
        instrumentation.runOnMainSync(() ->
                view[0] = model.getView(instrumentation.getTargetContext(), null));

        instrumentation.runOnMainSync(() -> view[0].setText("-"));
        assertEquals("", model.getString());
        assertEquals("-", view[0].getText().toString());
        instrumentation.runOnMainSync(() -> view[0].setText("-12"));
        assertEquals("-12", model.getString());
        model.setString("-7");
        instrumentation.waitForIdleSync();
        assertEquals("-7", view[0].getText().toString());
        assertEquals("-7", model.getString());
        model.clearScreenView();
    }

    @Test public void guestUpdatesDoNotEmitFormItemStateWhileUserUpdatesDo() throws Exception {
        TextFieldImpl model = new TextFieldImpl();
        model.setMaxSize(32);
        model.setConstraints(TextField.ANY);
        Item item = new StringItem(null, "observer");
        Form form = new Form("Form", new Item[]{item});
        AtomicInteger updates = new AtomicInteger();
        CountDownLatch changed = new CountDownLatch(1);
        form.setItemStateListener(changedItem -> {
            if (changedItem == item) {
                updates.incrementAndGet();
                changed.countDown();
            }
        });
        final EditText[] view = new EditText[1];
        instrumentation.runOnMainSync(() ->
                view[0] = model.getView(instrumentation.getTargetContext(), item));

        model.setString("programmatic");
        instrumentation.waitForIdleSync();
        assertEquals(0, updates.get());
        instrumentation.runOnMainSync(() -> view[0].setText("from user"));
        assertTrue("User edit should post an ItemStateListener callback",
                changed.await(2, TimeUnit.SECONDS));
        assertEquals(1, updates.get());
        assertEquals("from user", model.getString());
        model.clearScreenView();
    }

    @Test public void privacyModeIsReversible() {
        TextFieldImpl model = new TextFieldImpl();
        model.setMaxSize(32);
        final EditText[] view = new EditText[1];
        instrumentation.runOnMainSync(() ->
                view[0] = model.getView(instrumentation.getTargetContext(), null));
        model.setConstraints(TextField.ANY | TextField.PASSWORD);
        instrumentation.waitForIdleSync();
        assertTrue(view[0].getTransformationMethod() instanceof PasswordTransformationMethod);
        model.setConstraints(TextField.ANY);
        instrumentation.waitForIdleSync();
        assertFalse(view[0].getTransformationMethod() instanceof PasswordTransformationMethod);
        model.clearScreenView();
    }
}
