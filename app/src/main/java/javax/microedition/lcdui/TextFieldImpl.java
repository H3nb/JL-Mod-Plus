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
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.appcompat.widget.AppCompatEditText;

/** Android presentation of an authoritative MIDP text value. */
class TextFieldImpl {
    private final TextFieldValue value = new TextFieldValue();
    private volatile EditText textview;
    private volatile int caret;
    /** Only accessed on the main Android thread. Programmatic EditText changes are not guest input. */
    private boolean applyingHostState;
    private int displayedConstraints = Integer.MIN_VALUE;
    private int displayedMaxSize = -1;

    void setString(String text) {
        value.setString(text);
        postRefresh();
    }

    void insert(String src, int pos) {
        value.insert(src, pos);
        postRefresh();
    }

    void insert(char[] data, int offset, int length, int position) {
        value.insert(data, offset, length, position);
        postRefresh();
    }

    void setChars(char[] data, int offset, int length) {
        value.setChars(data, offset, length);
        postRefresh();
    }

    String getString() { return value.getString(); }
    int size() { return value.size(); }

    int setMaxSize(int maxSize) {
        int assigned = value.setMaxSize(maxSize);
        postRefresh();
        return assigned;
    }

    int getMaxSize() { return value.getMaxSize(); }

    void setConstraints(int constraints) {
        value.setConstraints(constraints);
        postRefresh();
    }

    int getConstraints() { return value.getConstraints(); }

    int getChars(char[] data) { return value.getChars(data); }

    int getCaretPosition() {
        return Math.min(Math.max(caret, 0), value.size());
    }

    void delete(int offset, int length) {
        value.delete(offset, length);
        postRefresh();
    }

    private void postRefresh() {
        if (textview != null) ViewHandler.postEvent(this::refreshView);
    }

    /** Runs only on the Android main thread; always reads the newest guest state. */
    private void refreshView() {
        EditText editor = textview;
        if (editor == null) return;
        applyingHostState = true;
        try {
            if (displayedConstraints != value.getConstraints()) {
                configureInput(editor);
            }
            if (displayedMaxSize != value.getMaxSize()) {
                editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(value.getMaxSize())});
                displayedMaxSize = value.getMaxSize();
            }
            String wanted = value.getString();
            if (!editor.getText().toString().equals(wanted)) {
                int selection = editor.getSelectionEnd();
                editor.setText(wanted);
                editor.setSelection(Math.max(0, Math.min(selection, wanted.length())));
                caret = editor.getSelectionEnd();
            }
        } finally {
            applyingHostState = false;
        }
    }

    private void configureInput(EditText editor) {
        int constraints = value.getConstraints();
        int base = constraints & TextField.CONSTRAINT_MASK;
        int type = switch (base) {
            case TextField.EMAILADDR -> TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
            case TextField.NUMERIC -> TYPE_CLASS_NUMBER | TYPE_NUMBER_FLAG_SIGNED;
            case TextField.PHONENUMBER -> TYPE_CLASS_PHONE;
            case TextField.URL -> TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_URI;
            case TextField.DECIMAL -> TYPE_CLASS_NUMBER | TYPE_NUMBER_FLAG_SIGNED | TYPE_NUMBER_FLAG_DECIMAL;
            default -> TYPE_CLASS_TEXT;
        };
        boolean password = (constraints & TextField.PASSWORD) != 0;
        if (password) {
            type = base == TextField.NUMERIC || base == TextField.DECIMAL
                    ? (type | TYPE_NUMBER_VARIATION_PASSWORD)
                    : (TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            if ((constraints & (TextField.NON_PREDICTIVE | TextField.SENSITIVE)) != 0) {
                type |= TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            }
            if ((constraints & TextField.INITIAL_CAPS_WORD) != 0) {
                type |= TYPE_TEXT_FLAG_CAP_WORDS;
            }
            if ((constraints & TextField.INITIAL_CAPS_SENTENCE) != 0) {
                type |= TYPE_TEXT_FLAG_CAP_SENTENCES;
            }
        }
        // A TextBox is a full-screen editor: even long URLs should wrap instead of clipping.
        // ANY must also allow actual newline characters under MIDP.
        if (editor instanceof GuestEditText guest && guest.textBox
                && (type & TYPE_CLASS_TEXT) == TYPE_CLASS_TEXT) {
            type |= TYPE_TEXT_FLAG_MULTI_LINE;
        }
        editor.setSingleLine(false);
        if (editor.getInputType() != type) editor.setInputType(type);
        editor.setHorizontallyScrolling(false);
        editor.setMaxLines(editor instanceof GuestEditText guest && guest.textBox ? 50 : 4);
        editor.setEnabled((constraints & TextField.UNEDITABLE) == 0);
        editor.setTransformationMethod(password ? PasswordTransformationMethod.getInstance() : null);
        int imeOptions = editor.getImeOptions() & ~EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && (constraints & (TextField.SENSITIVE | TextField.PASSWORD)) != 0) {
            imeOptions |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        }
        editor.setImeOptions(imeOptions);
        displayedConstraints = constraints;
    }

    EditText getView(Context context, Item item) {
        if (textview != null) return textview;
        GuestEditText editor = new GuestEditText(context, item == null);
        textview = editor;
        applyingHostState = true;
        try {
            configureInput(editor);
            editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(value.getMaxSize())});
            displayedMaxSize = value.getMaxSize();
            editor.setText(value.getString());
            editor.setSelection(editor.length());
            caret = editor.length();
        } finally {
            applyingHostState = false;
        }
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable edit) {
                if (applyingHostState || editor != textview) return;
                String candidate = edit.toString();
                String previous = value.getString();
                if (candidate.length() <= value.getMaxSize()
                        && TextFieldValue.legal(candidate, value.getConstraints())) {
                    value.setString(candidate);
                    if (item != null && !candidate.equals(previous)) item.postStateChanged();
                }
                // An incomplete edit (e.g. a numeric '-' prefix) stays in the IME view only.
                // Guest getters continue to expose the last valid MIDP value.
            }
        });
        editor.setOnFocusChangeListener((v, focused) -> {
            if (!focused && editor == textview) refreshView();
        });
        return editor;
    }

    void clearScreenView() {
        textview = null; // Old TextWatchers can no longer publish into the guest model.
        displayedConstraints = Integer.MIN_VALUE;
        displayedMaxSize = -1;
    }

    private final class GuestEditText extends AppCompatEditText {
        final boolean textBox;
        GuestEditText(Context context, boolean textBox) {
            super(context);
            this.textBox = textBox;
        }
        @Override protected void onSelectionChanged(int start, int end) {
            super.onSelectionChanged(start, end);
            if (this == textview) caret = Math.max(0, end);
        }
    }
}
