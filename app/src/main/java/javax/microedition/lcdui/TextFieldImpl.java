/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2019 Nikita Shakarun
 * Copyright 2021-2026 Yury Kharchenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javax.microedition.lcdui;

import static android.text.InputType.TYPE_CLASS_NUMBER;
import static android.text.InputType.TYPE_CLASS_PHONE;
import static android.text.InputType.TYPE_CLASS_TEXT;
import static android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL;
import static android.text.InputType.TYPE_NUMBER_FLAG_SIGNED;
import static android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD;
import static android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
import static android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS;
import static android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE;
import static android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
import static android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
import static android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;
import static android.text.InputType.TYPE_TEXT_VARIATION_URI;

import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.appcompat.widget.AppCompatEditText;

/** MIDP value owner with an optional Android editor bound to the active screen. */
class TextFieldImpl {
    private final MidpTextModel model = new MidpTextModel();
    private volatile EditText textview;
    private volatile int caret;
    private boolean updatingView;
    private boolean textBox;

    void setString(String value) {
        model.setString(value);
        refreshEditor(false);
    }

    void insert(String value, int position) {
        model.insert(value, position);
        refreshEditor(false);
    }

    void insert(char[] data, int offset, int length, int position) {
        model.insert(data, offset, length, position);
        refreshEditor(false);
    }

    String getString() { return model.getString(); }
    int size() { return model.size(); }

    void setChars(char[] data, int offset, int length) {
        model.setChars(data, offset, length);
        refreshEditor(false);
    }

    int getChars(char[] data) { return model.getChars(data); }

    int setMaxSize(int requested) {
        int assigned = model.setMaxSize(requested);
        refreshEditor(true);
        return assigned;
    }

    int getMaxSize() { return model.getMaxSize(); }

    void setConstraints(int requested) {
        model.setConstraints(requested);
        refreshEditor(true);
    }

    int getConstraints() { return model.getConstraints(); }

    int getCaretPosition() { return Math.min(caret, model.size()); }

    void delete(int offset, int length) {
        model.delete(offset, length);
        refreshEditor(false);
    }

    EditText getView(Context context, Item item) { return getView(context, item, false); }

    EditText getView(Context context, Item item, boolean isTextBox) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("MIDP text view must be created on the Android UI thread");
        }
        if (textview == null) {
            textBox = isTextBox;
            EditText editor = new AppCompatEditText(context);
            textview = editor;
            configureEditor(editor);
            applyText(editor);
            editor.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable editable) {
                    if (textview != editor || updatingView) return;
                    String candidate = editable.toString();
                    caret = Math.max(0, editor.getSelectionEnd());
                    if (model.acceptUserText(candidate)) {
                        if (item != null) item.postStateChanged();
                    } else if (!candidate.equals(model.getString()) &&
                            !MidpTextModel.incompleteDraft(candidate, model.getConstraints())) {
                        // Invalid paste, over-capacity data or a concurrent guest edit.
                        applyText(editor);
                    }
                }
            });
            editor.setOnFocusChangeListener((view, focused) -> {
                if (!focused && textview == editor) applyText(editor);
            });
        }
        return textview;
    }

    private void refreshEditor(boolean configurationChanged) {
        EditText editor = textview;
        if (editor == null) return;
        Runnable refresh = () -> {
            if (textview != editor) return; // A replacement host must not be modified by stale events.
            if (configurationChanged) configureEditor(editor);
            applyText(editor);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) refresh.run();
        else ViewHandler.postEvent(refresh);
    }

    private void applyText(EditText editor) {
        String desired = model.getString();
        if (editor.getText() != null && desired.contentEquals(editor.getText())) return;
        int position = editor.getSelectionEnd();
        updatingView = true;
        try {
            editor.setText(desired);
            caret = Math.min(Math.max(position, 0), desired.length());
            editor.setSelection(caret);
        } finally {
            updatingView = false;
        }
    }

    private void configureEditor(EditText editor) {
        int settings = model.getConstraints();
        int kind = settings & TextField.CONSTRAINT_MASK;
        boolean password = (settings & TextField.PASSWORD) != 0;
        int inputType = switch (kind) {
            case TextField.EMAILADDR -> TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
            case TextField.NUMERIC -> TYPE_CLASS_NUMBER | TYPE_NUMBER_FLAG_SIGNED;
            case TextField.PHONENUMBER -> TYPE_CLASS_PHONE;
            case TextField.URL -> TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_URI;
            case TextField.DECIMAL -> TYPE_CLASS_NUMBER | TYPE_NUMBER_FLAG_SIGNED | TYPE_NUMBER_FLAG_DECIMAL;
            default -> TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_MULTI_LINE;
        };
        if (password) {
            if (kind == TextField.NUMERIC || kind == TextField.DECIMAL)
                inputType |= TYPE_NUMBER_VARIATION_PASSWORD;
            else if (kind != TextField.PHONENUMBER)
                inputType = TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD;
        }
        if ((inputType & android.text.InputType.TYPE_MASK_CLASS) == TYPE_CLASS_TEXT) {
            if ((settings & (TextField.NON_PREDICTIVE | TextField.SENSITIVE | TextField.PASSWORD)) != 0)
                inputType |= TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            if (!password && (settings & TextField.INITIAL_CAPS_WORD) != 0)
                inputType |= TYPE_TEXT_FLAG_CAP_WORDS;
            if (!password && (settings & TextField.INITIAL_CAPS_SENTENCE) != 0)
                inputType |= TYPE_TEXT_FLAG_CAP_SENTENCES;
        }
        updatingView = true;
        try {
            editor.setInputType(inputType);
            editor.setSingleLine(kind != TextField.ANY);
            editor.setHorizontallyScrolling(false);
            if (kind == TextField.ANY) editor.setMaxLines(textBox ? Integer.MAX_VALUE : 3);
            editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(model.getMaxSize())});
            boolean editable = (settings & TextField.UNEDITABLE) == 0;
            editor.setFocusableInTouchMode(editable);
            editor.setFocusable(editable);
            // Read-only text may be selectable, but copying an obscured password must not reveal it.
            editor.setTextIsSelectable(!editable && !password);
            int options = editor.getImeOptions() & ~EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    (settings & (TextField.PASSWORD | TextField.SENSITIVE)) != 0) {
                options |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
            }
            editor.setImeOptions(options);
            // setSingleLine/setTextIsSelectable may reset the TextView transformer.
            // Apply masking last, and remove it when PASSWORD is disabled.
            editor.setTransformationMethod(password ? PasswordTransformationMethod.getInstance() : null);
        } finally {
            updatingView = false;
        }
    }

    void clearScreenView() {
        EditText editor = textview;
        if (editor == null) return;
        // Detach first: callbacks from a released Android view cannot mutate the new MIDP model.
        textview = null;
        // Guest setCurrent() can detach a Screen off the Android UI thread.
        // Reject callbacks immediately; clean up the old View on its owner thread.
        Runnable detach = () -> editor.setOnFocusChangeListener(null);
        if (Looper.myLooper() == Looper.getMainLooper()) detach.run();
        else ViewHandler.postEvent(detach);
    }
}
