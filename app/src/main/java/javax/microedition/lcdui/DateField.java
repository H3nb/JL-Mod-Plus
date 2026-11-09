/*
 * Copyright 2018 Nikita Shakarun
 * Copyright 2022-2026 Yury Kharchenko
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

import android.content.Context;
import android.view.View;
import android.widget.DatePicker;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.TimePicker;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import io.github.h3nb.jlmodplus.R;
import javax.microedition.lcdui.event.SimpleEvent;
import javax.microedition.util.ContextHolder;

/**
 * MIDP 2.0 DateField: a nullable logical value, independent of the Android picker preview.
 * Programmatic changes update the widgets without producing guest ItemStateListener events.
 */
public class DateField extends Item {
    public static final int DATE = 1;
    public static final int TIME = 2;
    public static final int DATE_TIME = 3;
    private static final long DAY_MILLIS = 86_400_000L;

    private final Object valueLock = new Object();
    private final Calendar calendar;
    private int mode;
    private boolean initialized;

    private volatile LinearLayout layout;
    private DatePicker datePicker;
    private TimePicker timePicker;
    private TextView unsetLabel;
    /** Accessed only on the Android UI thread. */
    private boolean syncingWidgets;

    private final SimpleEvent syncWidgetsEvent = new SimpleEvent() {
        @Override
        public void process() {
            syncWidgets();
        }
    };

    public DateField(String label, int mode) {
        this(label, mode, null);
    }

    public DateField(String label, int mode, TimeZone timeZone) {
        validateMode(mode);
        TimeZone zone = timeZone == null ? TimeZone.getDefault() : (TimeZone) timeZone.clone();
        calendar = Calendar.getInstance(zone);
        // A picker needs preview coordinates; this does not initialize the MIDP value.
        calendar.setTimeInMillis(System.currentTimeMillis());
        this.mode = mode;
        setLabel(label);
    }

    public Date getDate() {
        synchronized (valueLock) {
            if (!initialized) return null;
            long millis = calendar.getTimeInMillis();
            if (mode == TIME) {
                // MIDP TIME values are anchored in the first epoch day; the picker uses
                // the requested time zone to interpret the clock fields.
                millis %= DAY_MILLIS;
                if (millis < 0) millis += DAY_MILLIS;
            }
            return new Date(millis);
        }
    }

    public int getInputMode() {
        synchronized (valueLock) {
            return mode;
        }
    }

    public void setDate(Date date) {
        synchronized (valueLock) {
            if (date == null || (mode == TIME
                    && (date.getTime() < 0L || date.getTime() >= DAY_MILLIS))) {
                initialized = false;
            } else {
                calendar.setTime(date);
                normalizeLocked();
                initialized = true;
            }
        }
        postWidgetSync();
    }

    public void setInputMode(int mode) {
        validateMode(mode);
        synchronized (valueLock) {
            this.mode = mode;
            if (initialized) normalizeLocked();
        }
        postWidgetSync();
    }

    private static void validateMode(int mode) {
        if (mode != DATE && mode != TIME && mode != DATE_TIME) {
            throw new IllegalArgumentException("Invalid DateField mode: " + mode);
        }
    }

    /** Called under valueLock; only the fields relevant to the selected MIDP mode survive. */
    private void normalizeLocked() {
        if (mode == TIME) {
            calendar.set(Calendar.YEAR, 1970);
            calendar.set(Calendar.MONTH, Calendar.JANUARY);
            calendar.set(Calendar.DAY_OF_MONTH, 1);
        } else if (mode == DATE) {
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
        }
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }

    private void postWidgetSync() {
        if (layout != null) ViewHandler.postEvent(syncWidgetsEvent);
    }

    private void syncWidgets() {
        if (layout == null || datePicker == null || timePicker == null) return;
        final Calendar snapshot;
        final int activeMode;
        final boolean hasValue;
        synchronized (valueLock) {
            snapshot = (Calendar) calendar.clone();
            activeMode = mode;
            hasValue = initialized;
        }

        syncingWidgets = true;
        try {
            datePicker.updateDate(snapshot.get(Calendar.YEAR), snapshot.get(Calendar.MONTH),
                    snapshot.get(Calendar.DAY_OF_MONTH));
            timePicker.setCurrentHour(snapshot.get(Calendar.HOUR_OF_DAY));
            timePicker.setCurrentMinute(snapshot.get(Calendar.MINUTE));
            datePicker.setVisibility(activeMode == TIME ? View.GONE : View.VISIBLE);
            timePicker.setVisibility(activeMode == DATE ? View.GONE : View.VISIBLE);
            unsetLabel.setVisibility(hasValue ? View.GONE : View.VISIBLE);
        } finally {
            syncingWidgets = false;
        }
    }

    private void onUserDate(int year, int month, int day) {
        if (syncingWidgets) return;
        boolean changed;
        synchronized (valueLock) {
            if (mode == TIME) return;
            boolean wasInitialized = initialized;
            long previous = calendar.getTimeInMillis();
            calendar.set(year, month, day);
            normalizeLocked();
            initialized = true;
            changed = !wasInitialized || previous != calendar.getTimeInMillis();
        }
        onUserChange(changed);
    }

    private void onUserTime(int hour, int minute) {
        if (syncingWidgets) return;
        boolean changed;
        synchronized (valueLock) {
            if (mode == DATE) return;
            boolean wasInitialized = initialized;
            long previous = calendar.getTimeInMillis();
            calendar.set(Calendar.HOUR_OF_DAY, hour);
            calendar.set(Calendar.MINUTE, minute);
            normalizeLocked();
            initialized = true;
            changed = !wasInitialized || previous != calendar.getTimeInMillis();
        }
        onUserChange(changed);
    }

    private void onUserChange(boolean changed) {
        if (!changed) return;
        if (unsetLabel != null) unsetLabel.setVisibility(View.GONE);
        // Existing Item/Form event ownership sends this on the serialized LCDUI guest queue.
        postStateChanged();
    }

    @Override
    View getItemContentView() {
        if (layout == null) {
            Context context = ContextHolder.getActivity();
            layout = new LinearLayout(context);
            layout.setOrientation(LinearLayout.VERTICAL);

            unsetLabel = new TextView(context);
            unsetLabel.setText(R.string.date_field_not_set);
            layout.addView(unsetLabel);

            datePicker = new DatePicker(context);
            timePicker = new TimePicker(context);
            syncingWidgets = true;
            try {
                Calendar snapshot;
                synchronized (valueLock) {
                    snapshot = (Calendar) calendar.clone();
                }
                datePicker.init(snapshot.get(Calendar.YEAR), snapshot.get(Calendar.MONTH),
                        snapshot.get(Calendar.DAY_OF_MONTH), this::onUserDate);
                timePicker.setCurrentHour(snapshot.get(Calendar.HOUR_OF_DAY));
                timePicker.setCurrentMinute(snapshot.get(Calendar.MINUTE));
                timePicker.setOnTimeChangedListener((picker, hour, minute) ->
                        onUserTime(hour, minute));
            } finally {
                syncingWidgets = false;
            }
            layout.addView(datePicker);
            layout.addView(timePicker);
            syncWidgets();
        }
        return layout;
    }

    @Override
    void clearItemContentView() {
        layout = null;
        datePicker = null;
        timePicker = null;
        unsetLabel = null;
    }
}
