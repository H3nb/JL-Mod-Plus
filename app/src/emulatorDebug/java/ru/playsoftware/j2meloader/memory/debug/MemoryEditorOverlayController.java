/*
 * Copyright 2026 JL-Mod Plus contributors
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

package ru.playsoftware.j2meloader.memory.debug;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import javax.microedition.shell.MicroActivity;

import ru.playsoftware.j2meloader.memory.IMemoryScanCallback;
import ru.playsoftware.j2meloader.memory.IMemoryScanService;
import ru.playsoftware.j2meloader.memory.MemoryScanContract;
import ru.playsoftware.j2meloader.memory.MemoryScanService;

/**
 * Debug-only same-Activity Memory Editor overlay.
 *
 * <p>Before first use only the controller, host and tiny MEM trigger exist. First open allocates all
 * managed working state and the complete reusable View hierarchy, then starts the scanner service
 * and its self-tests. New Search remains disabled until that warm-up is complete, so editor-driven
 * allocation/GC cannot invalidate an address that has already been bound by this editor.</p>
 *
 * <p>After a search, normal close/reopen on the same orientation is visibility/focus only. Result
 * observation is manual by default; Live mode is explicit because formatting live values creates
 * managed text.</p>
 */
final class MemoryEditorOverlayController {
    private static final int MAX_TYPED_RESULTS = 100;
    private static final int RESULT_STRIDE = 4;
    private static final int TYPE_SLOTS = 8;
    private static final long LIVE_REFRESH_MS = 1500L;
    private static final String GC_COUNT_STAT = "art.gc.gc-count";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final MicroActivity activity;

    // Cold state: only these objects exist before the user first opens Memory Editor.
    private FrameLayout host;
    private TextView trigger;
    private boolean built;
    private boolean destroyed;

    // Warm state: allocated together before New Search can become enabled.
    private Handler mainHandler;
    private long[] groupAddress;
    private int[] groupAliasMask;
    private int[] groupReadableMask;
    private long[] groupValueBits;
    private TextView[] resultRows;
    private StringBuilder[] rowBuilders;
    private StringBuilder statusBuilder;
    private StringBuilder editBuilder;
    private Runnable applyPendingStatus;
    private Runnable targetClosed;
    private Runnable liveRefresh;
    private IMemoryScanCallback callback;
    private ServiceConnection connection;

    private FrameLayout overlay;
    private LinearLayout adaptiveBody;
    private ScrollView controlsPane;
    private LinearLayout resultsPane;
    private FrameLayout contentFrame;
    private LinearLayout mainPane;
    private LinearLayout editPane;
    private LinearLayout diagnosticsPane;
    private EditText queryInput;
    private Button typeButton;
    private Button scopeButton;
    private Button newSearchButton;
    private Button nextScanButton;
    private Button cancelButton;
    private Button refreshButton;
    private Button liveButton;
    private TextView statusText;
    private TextView resultSummary;
    private TextView noticeText;
    private TextView diagnosticsText;
    private Button editTypeButton;
    private TextView editAddressText;
    private TextView editCurrentText;
    private EditText replacementInput;

    private IMemoryScanService service;
    private boolean bound;
    private boolean liveEnabled;
    private long generation;
    private long resultCount;
    private long operationId;
    private long lastRenderedOperation = -1L;
    private long warmupGcDelta;
    private int selectedType = MemoryScanContract.TYPE_AUTO;
    private int selectedScope = MemoryScanContract.SCOPE_JAVA_FAST;
    private int appliedOrientation = Configuration.ORIENTATION_UNDEFINED;
    private int groupCount;
    private int editGroup = -1;
    private int editType = MemoryScanContract.TYPE_INT32;
    private String capability = "PENDING";
    private String managedSelfTest = "RUNNING";
    private String state = MemoryScanContract.STATE_NO_TARGET;
    private String diagnostics = "";
    private Bundle pendingStatus;

    MemoryEditorOverlayController(MicroActivity activity) {
        this.activity = activity;
    }

    boolean owns(Activity candidate) {
        return activity == candidate;
    }

    void attach() {
        if (destroyed || host != null) return;
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup parent)) return;

        host = new FrameLayout(activity);
        host.setClipChildren(false);
        host.setClipToPadding(false);
        parent.addView(host, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        trigger = new TextView(activity);
        trigger.setText("MEM");
        trigger.setTextColor(Color.WHITE);
        trigger.setTextSize(12f);
        trigger.setGravity(Gravity.CENTER);
        trigger.setPadding(dp(10), dp(7), dp(10), dp(7));
        trigger.setBackgroundColor(0xB8222222);
        trigger.setOnClickListener(v -> showOverlay());
        FrameLayout.LayoutParams triggerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        triggerParams.topMargin = dp(12);
        triggerParams.rightMargin = dp(10);
        host.addView(trigger, triggerParams);

        host.setOnApplyWindowInsetsListener((v, insets) -> {
            applyInsets(insets);
            return insets;
        });
        host.requestApplyInsets();
    }

    void destroy() {
        if (destroyed) return;
        destroyed = true;
        liveEnabled = false;
        if (mainHandler != null) mainHandler.removeCallbacksAndMessages(null);
        IMemoryScanService current = service;
        if (current != null && callback != null) {
            try { current.unregisterCallback(callback); } catch (RemoteException ignored) {}
        }
        if (bound && connection != null) {
            try { activity.unbindService(connection); } catch (RuntimeException ignored) {}
        }
        bound = false;
        service = null;
        if (host != null && host.getParent() instanceof ViewGroup parent) parent.removeView(host);
        host = null;
        overlay = null;
    }

    private void showOverlay() {
        if (destroyed) return;
        if (!built) warmUp();
        if (overlay == null) return;
        trigger.setVisibility(View.GONE);
        overlay.setVisibility(View.VISIBLE);
        // This is allocation-free for normal same-orientation round trips.
        applyAdaptiveLayoutIfNeeded();
        overlay.requestFocus();
    }

    private void warmUp() {
        long before = runtimeGcCount();
        allocateWarmState();
        buildOverlay();
        long after = runtimeGcCount();
        warmupGcDelta = delta(before, after);
        built = true;
        updateStatusUi();
        bindScanner();
    }

    private void allocateWarmState() {
        mainHandler = new Handler(Looper.getMainLooper());
        groupAddress = new long[MAX_TYPED_RESULTS];
        groupAliasMask = new int[MAX_TYPED_RESULTS];
        groupReadableMask = new int[MAX_TYPED_RESULTS];
        groupValueBits = new long[MAX_TYPED_RESULTS * TYPE_SLOTS];
        resultRows = new TextView[MAX_TYPED_RESULTS];
        rowBuilders = new StringBuilder[MAX_TYPED_RESULTS];
        statusBuilder = new StringBuilder(256);
        editBuilder = new StringBuilder(96);

        applyPendingStatus = () -> {
            Bundle status = pendingStatus;
            pendingStatus = null;
            if (status != null && !destroyed) applyStatus(status);
        };
        targetClosed = () -> {
            if (destroyed) return;
            generation = 0L;
            state = MemoryScanContract.STATE_NO_TARGET;
            setNotice("MIDlet target closed; memory session discarded");
            hideOverlay();
        };
        liveRefresh = new Runnable() {
            @Override
            public void run() {
                if (!liveEnabled || destroyed || overlay == null
                        || overlay.getVisibility() != View.VISIBLE) return;
                refreshResults();
                mainHandler.postDelayed(this, LIVE_REFRESH_MS);
            }
        };
        callback = new IMemoryScanCallback.Stub() {
            @Override
            public void onStatusChanged(Bundle status) {
                pendingStatus = status;
                mainHandler.removeCallbacks(applyPendingStatus);
                mainHandler.post(applyPendingStatus);
            }

            @Override
            public void onTargetClosed() {
                mainHandler.removeCallbacks(targetClosed);
                mainHandler.post(targetClosed);
            }
        };
        connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                if (destroyed) return;
                service = IMemoryScanService.Stub.asInterface(binder);
                try {
                    Bundle caps = service.getCapabilities();
                    generation = caps.getLong(MemoryScanContract.KEY_GENERATION, 0L);
                    capability = caps.getString(MemoryScanContract.KEY_CAPABILITY, "PENDING");
                    managedSelfTest = caps.getString(
                            MemoryScanContract.KEY_MANAGED_SELF_TEST, "RUNNING");
                    service.registerCallback(callback);
                    updateStatusUi();
                } catch (RemoteException error) {
                    remoteFailure(error);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                service = null;
                capability = "DISCONNECTED";
                updateStatusUi();
            }

            @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
            @Override public void onNullBinding(ComponentName name) { onServiceDisconnected(name); }
        };
    }

    private void bindScanner() {
        if (bound || destroyed) return;
        Intent intent = new Intent(activity, MemoryScanService.class);
        bound = activity.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            capability = "BIND_FAILED";
            setNotice("Unable to bind MemoryScanService");
        }
    }

    private void buildOverlay() {
        overlay = new FrameLayout(activity);
        // Light dim + 70% panel opacity keeps the running MIDlet visibly present underneath.
        overlay.setBackgroundColor(0x1A000000);
        overlay.setVisibility(View.GONE);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        host.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(10));
        panel.setBackgroundColor(0xB31B1B1B);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        panelParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        overlay.addView(panel, panelParams);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Memory Editor · raw :midlet memory", 18f);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button close = button("Close");
        close.setOnClickListener(v -> hideOverlay());
        header.addView(close);
        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusText = text("Warming up…", 12f);
        statusText.setTextIsSelectable(true);
        panel.addView(statusText);
        noticeText = text("", 12f);
        noticeText.setTextColor(0xFFFFCC80);
        panel.addView(noticeText);

        contentFrame = new FrameLayout(activity);
        panel.addView(contentFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        buildMainPane();
        buildEditPane();
        buildDiagnosticsPane();
        applyAdaptiveLayoutIfNeeded();
    }

    private void buildMainPane() {
        mainPane = new LinearLayout(activity);
        mainPane.setOrientation(LinearLayout.VERTICAL);
        contentFrame.addView(mainPane, matchFrame());

        adaptiveBody = new LinearLayout(activity);
        mainPane.addView(adaptiveBody, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout controls = new LinearLayout(activity);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(0, dp(4), dp(8), dp(4));
        controlsPane = new ScrollView(activity);
        controlsPane.setFillViewport(true);
        controlsPane.addView(controls, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        adaptiveBody.addView(controlsPane);

        queryInput = new EditText(activity);
        queryInput.setSingleLine(true);
        queryInput.setHint("Value");
        queryInput.setTextColor(Color.WHITE);
        queryInput.setHintTextColor(0xFFAAAAAA);
        queryInput.setInputType(InputType.TYPE_CLASS_TEXT);
        controls.addView(queryInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout selectors = new LinearLayout(activity);
        selectors.setOrientation(LinearLayout.HORIZONTAL);
        typeButton = button("Type: Auto");
        typeButton.setOnClickListener(v -> {
            selectedType = (selectedType + 1) % (MemoryScanContract.TYPE_FLOAT64 + 1);
            typeButton.setText("Type: " + MemoryScanContract.typeName(selectedType));
        });
        selectors.addView(typeButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        scopeButton = button("Scope: Java Fast");
        scopeButton.setOnClickListener(v -> {
            selectedScope = selectedScope == MemoryScanContract.SCOPE_JAVA_FAST
                    ? MemoryScanContract.SCOPE_JAVA_THOROUGH
                    : MemoryScanContract.SCOPE_JAVA_FAST;
            scopeButton.setText("Scope: " + MemoryScanContract.scopeName(selectedScope));
        });
        selectors.addView(scopeButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(selectors);

        LinearLayout searchActions = new LinearLayout(activity);
        newSearchButton = button("New Search");
        newSearchButton.setOnClickListener(v -> startSearch());
        searchActions.addView(newSearchButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nextScanButton = button("Next Scan");
        nextScanButton.setOnClickListener(v -> nextScan());
        searchActions.addView(nextScanButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(searchActions);

        LinearLayout sessionActions = new LinearLayout(activity);
        cancelButton = button("Cancel");
        cancelButton.setOnClickListener(v -> cancelOperation());
        sessionActions.addView(cancelButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button clear = button("Clear");
        clear.setOnClickListener(v -> clearSearch());
        sessionActions.addView(clear, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(sessionActions);

        LinearLayout observationActions = new LinearLayout(activity);
        refreshButton = button("Refresh");
        refreshButton.setOnClickListener(v -> refreshResults());
        observationActions.addView(refreshButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        liveButton = button("Live: Off");
        liveButton.setOnClickListener(v -> setLiveEnabled(!liveEnabled));
        observationActions.addView(liveButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(observationActions);

        Button diagnosticsButton = button("Diagnostics");
        diagnosticsButton.setOnClickListener(v -> showDiagnostics());
        controls.addView(diagnosticsButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        resultsPane = new LinearLayout(activity);
        resultsPane.setOrientation(LinearLayout.VERTICAL);
        resultsPane.setPadding(dp(8), dp(4), 0, dp(4));
        adaptiveBody.addView(resultsPane);

        resultSummary = text("No retained candidates", 13f);
        resultsPane.addView(resultSummary);
        ScrollView resultScroll = new ScrollView(activity);
        LinearLayout resultList = new LinearLayout(activity);
        resultList.setOrientation(LinearLayout.VERTICAL);
        resultScroll.addView(resultList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        resultsPane.addView(resultScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        for (int i = 0; i < MAX_TYPED_RESULTS; i++) {
            final int rowIndex = i;
            TextView row = text("", 12f);
            row.setPadding(dp(4), dp(7), dp(4), dp(7));
            row.setVisibility(View.GONE);
            row.setOnClickListener(v -> openEdit(rowIndex));
            resultRows[i] = row;
            rowBuilders[i] = new StringBuilder(96);
            resultList.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void buildEditPane() {
        editPane = new LinearLayout(activity);
        editPane.setOrientation(LinearLayout.VERTICAL);
        editPane.setPadding(dp(8), dp(8), dp(8), dp(8));
        editPane.setVisibility(View.GONE);
        contentFrame.addView(editPane, matchFrame());

        editPane.addView(text("Edit typed value", 18f));
        editAddressText = text("", 13f);
        editAddressText.setTextIsSelectable(true);
        editPane.addView(editAddressText);
        editTypeButton = button("Type");
        editTypeButton.setOnClickListener(v -> cycleEditType());
        editPane.addView(editTypeButton);
        editCurrentText = text("", 14f);
        editCurrentText.setTextIsSelectable(true);
        editPane.addView(editCurrentText);
        replacementInput = new EditText(activity);
        replacementInput.setHint("Replacement value");
        replacementInput.setSingleLine(true);
        replacementInput.setTextColor(Color.WHITE);
        replacementInput.setHintTextColor(0xFFAAAAAA);
        editPane.addView(replacementInput);

        LinearLayout actions = new LinearLayout(activity);
        Button apply = button("Apply exact typed write");
        apply.setOnClickListener(v -> applyEdit());
        actions.addView(apply, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button back = button("Back");
        back.setOnClickListener(v -> leaveSubPane());
        actions.addView(back, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        editPane.addView(actions);
    }

    private void buildDiagnosticsPane() {
        diagnosticsPane = new LinearLayout(activity);
        diagnosticsPane.setOrientation(LinearLayout.VERTICAL);
        diagnosticsPane.setPadding(dp(8), dp(8), dp(8), dp(8));
        diagnosticsPane.setVisibility(View.GONE);
        contentFrame.addView(diagnosticsPane, matchFrame());

        diagnosticsPane.addView(text("Diagnostics · bounded snapshot", 18f));
        ScrollView scroll = new ScrollView(activity);
        diagnosticsText = text("", 12f);
        diagnosticsText.setTextIsSelectable(true);
        scroll.addView(diagnosticsText, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        diagnosticsPane.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout actions = new LinearLayout(activity);
        Button copy = button("Copy");
        copy.setOnClickListener(v -> copyDiagnostics());
        actions.addView(copy, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button back = button("Back");
        back.setOnClickListener(v -> leaveSubPane());
        actions.addView(back, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        diagnosticsPane.addView(actions);
    }

    private void applyInsets(WindowInsets insets) {
        if (trigger != null && trigger.getLayoutParams() instanceof FrameLayout.LayoutParams lp) {
            lp.topMargin = insets.getSystemWindowInsetTop() + dp(8);
            lp.rightMargin = insets.getSystemWindowInsetRight() + dp(8);
            trigger.setLayoutParams(lp);
        }
        if (overlay != null) {
            overlay.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
        }
    }

    private void applyAdaptiveLayoutIfNeeded() {
        if (adaptiveBody == null) return;
        int orientation = activity.getResources().getConfiguration().orientation;
        if (orientation == appliedOrientation) return;
        appliedOrientation = orientation;
        boolean landscape = orientation == Configuration.ORIENTATION_LANDSCAPE;
        adaptiveBody.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        if (landscape) {
            controlsPane.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 0.42f));
            resultsPane.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 0.58f));
        } else {
            controlsPane.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.42f));
            resultsPane.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.58f));
        }
    }

    private void hideOverlay() {
        if (overlay == null) return;
        setLiveEnabled(false);
        leaveSubPane();
        overlay.setVisibility(View.GONE);
        if (trigger != null) trigger.setVisibility(View.VISIBLE);
    }

    private void leaveSubPane() {
        if (mainPane == null) return;
        mainPane.setVisibility(View.VISIBLE);
        editPane.setVisibility(View.GONE);
        diagnosticsPane.setVisibility(View.GONE);
    }

    private void startSearch() {
        IMemoryScanService current = service;
        if (current == null || !ready()) {
            setNotice("Scanner is not ready; wait for native + Managed ART self-tests");
            return;
        }
        String query = queryInput.getText().toString().trim();
        if (query.isEmpty()) {
            setNotice("Enter a value first");
            return;
        }
        try {
            long id = current.startSearch(generation, query, selectedScope, selectedType);
            if (id < 0L) {
                setNotice("New Search rejected by the target/session guard");
                return;
            }
            operationId = id;
            state = MemoryScanContract.STATE_RUNNING;
            clearRenderedRows();
            updateStatusUi();
        } catch (RemoteException error) {
            remoteFailure(error);
        }
    }

    private void nextScan() {
        IMemoryScanService current = service;
        if (current == null || generation == 0L) return;
        String query = queryInput.getText().toString().trim();
        if (query.isEmpty()) {
            setNotice("Enter the new value before Next Scan");
            return;
        }
        try {
            long id = current.refine(generation, query);
            if (id < 0L) {
                setNotice("Next Scan requires a completed search from this same MIDlet generation");
                return;
            }
            operationId = id;
            state = MemoryScanContract.STATE_RUNNING;
            updateStatusUi();
        } catch (RemoteException error) {
            remoteFailure(error);
        }
    }

    private void cancelOperation() {
        IMemoryScanService current = service;
        if (current == null) return;
        try {
            current.cancelOperation(generation);
        } catch (RemoteException error) {
            remoteFailure(error);
        }
    }

    private void clearSearch() {
        IMemoryScanService current = service;
        if (current == null) return;
        setLiveEnabled(false);
        try {
            current.clearSearch(generation);
            clearRenderedRows();
            setNotice("Search clear requested");
        } catch (RemoteException error) {
            remoteFailure(error);
        }
    }

    private void setLiveEnabled(boolean enabled) {
        liveEnabled = enabled;
        if (liveButton != null) liveButton.setText(enabled ? "Live: On" : "Live: Off");
        if (mainHandler != null && liveRefresh != null) mainHandler.removeCallbacks(liveRefresh);
        if (enabled && overlay != null && overlay.getVisibility() == View.VISIBLE) {
            refreshResults();
            mainHandler.postDelayed(liveRefresh, LIVE_REFRESH_MS);
            setNotice("Live observation enabled; manual Refresh is lower-GC");
        }
    }

    private void refreshResults() {
        IMemoryScanService current = service;
        if (current == null || generation == 0L
                || !MemoryScanContract.STATE_COMPLETE.equals(state)) return;
        try {
            long[] raw = current.getResultsPage(generation, 0, MAX_TYPED_RESULTS);
            renderResults(raw);
        } catch (RemoteException error) {
            remoteFailure(error);
        }
    }

    private void renderResults(long[] raw) {
        groupCount = 0;
        if (raw == null || raw.length == 0) {
            clearRenderedRows();
            return;
        }
        int typedCount = (int) raw[0];
        int required = 1 + typedCount * RESULT_STRIDE;
        if (typedCount < 0 || typedCount > MAX_TYPED_RESULTS || required > raw.length) {
            setNotice("Malformed result page from scanner");
            return;
        }
        int index = 1;
        for (int i = 0; i < typedCount; i++, index += RESULT_STRIDE) {
            long address = raw[index];
            int type = (int) raw[index + 1];
            boolean readable = raw[index + 2] != 0L;
            long bits = raw[index + 3];
            if (!MemoryScanContract.isCandidateType(type)) continue;
            int group = findOrCreateGroup(address);
            if (group < 0) continue;
            groupAliasMask[group] |= 1 << type;
            if (readable) {
                groupReadableMask[group] |= 1 << type;
                groupValueBits[group * TYPE_SLOTS + type] = bits;
            }
        }

        for (int i = 0; i < MAX_TYPED_RESULTS; i++) {
            TextView row = resultRows[i];
            if (i >= groupCount) {
                row.setVisibility(View.GONE);
                continue;
            }
            StringBuilder out = rowBuilders[i];
            out.setLength(0);
            appendHex(out, groupAddress[i]);
            out.append("  ·  ");
            appendAliasNames(out, groupAliasMask[i]);
            int displayType = firstReadableType(i);
            if (displayType > 0) {
                out.append('\n');
                appendValue(out, displayType, groupValueBits[i * TYPE_SLOTS + displayType]);
                if (Integer.bitCount(groupAliasMask[i]) > 1) {
                    out.append("  [").append(MemoryScanContract.typeName(displayType)).append(']');
                }
            } else {
                out.append("\n<stale/unreadable>");
            }
            row.setText(out.toString());
            row.setVisibility(View.VISIBLE);
        }
        resultSummary.setText(resultCount + " typed candidates · " + groupCount
                + " unique addresses shown · refresh is manual by default");
    }

    private int findOrCreateGroup(long address) {
        for (int i = 0; i < groupCount; i++) {
            if (groupAddress[i] == address) return i;
        }
        if (groupCount >= MAX_TYPED_RESULTS) return -1;
        int slot = groupCount++;
        groupAddress[slot] = address;
        groupAliasMask[slot] = 0;
        groupReadableMask[slot] = 0;
        return slot;
    }

    private int firstReadableType(int group) {
        int readable = groupReadableMask[group];
        int aliases = groupAliasMask[group];
        for (int type = MemoryScanContract.TYPE_INT8; type <= MemoryScanContract.TYPE_FLOAT64; type++) {
            if ((readable & (1 << type)) != 0) return type;
        }
        for (int type = MemoryScanContract.TYPE_INT8; type <= MemoryScanContract.TYPE_FLOAT64; type++) {
            if ((aliases & (1 << type)) != 0) return type;
        }
        return -1;
    }

    private void openEdit(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= groupCount) return;
        editGroup = rowIndex;
        editType = firstReadableType(rowIndex);
        if (editType < MemoryScanContract.TYPE_INT8) return;
        mainPane.setVisibility(View.GONE);
        diagnosticsPane.setVisibility(View.GONE);
        editPane.setVisibility(View.VISIBLE);
        replacementInput.setText("");
        updateEditPane();
    }

    private void cycleEditType() {
        if (editGroup < 0) return;
        int aliases = groupAliasMask[editGroup];
        int candidate = editType;
        for (int i = 0; i < MemoryScanContract.TYPE_FLOAT64; i++) {
            candidate++;
            if (candidate > MemoryScanContract.TYPE_FLOAT64) {
                candidate = MemoryScanContract.TYPE_INT8;
            }
            if ((aliases & (1 << candidate)) != 0) {
                editType = candidate;
                updateEditPane();
                return;
            }
        }
    }

    private void updateEditPane() {
        editBuilder.setLength(0);
        appendHex(editBuilder, groupAddress[editGroup]);
        editAddressText.setText(editBuilder.toString());
        editTypeButton.setText("Type: " + MemoryScanContract.typeName(editType)
                + (Integer.bitCount(groupAliasMask[editGroup]) > 1
                        ? " · tap to change alias" : ""));
        editBuilder.setLength(0);
        if ((groupReadableMask[editGroup] & (1 << editType)) != 0) {
            editBuilder.append("Current: ");
            appendValue(editBuilder, editType, groupValueBits[editGroup * TYPE_SLOTS + editType]);
        } else {
            editBuilder.append("Current: <stale/unreadable>");
        }
        editCurrentText.setText(editBuilder.toString());
    }

    private void applyEdit() {
        IMemoryScanService current = service;
        if (current == null || editGroup < 0) return;
        if ((groupReadableMask[editGroup] & (1 << editType)) == 0) {
            setNotice("Refusing write: selected alias is stale/unreadable");
            return;
        }
        editBuilder.setLength(0);
        appendValue(editBuilder, editType, groupValueBits[editGroup * TYPE_SLOTS + editType]);
        String expected = editBuilder.toString();
        String replacement = replacementInput.getText().toString().trim();
        if (replacement.isEmpty()) {
            setNotice("Enter a replacement value");
            return;
        }
        try {
            Bundle result = current.editValue(
                    generation, groupAddress[editGroup], editType, expected, replacement);
            setNotice(result.getString(MemoryScanContract.KEY_MESSAGE, "Write completed"));
            leaveSubPane();
            refreshResults();
        } catch (RemoteException error) {
            remoteFailure(error);
        }
    }

    private void showDiagnostics() {
        mainPane.setVisibility(View.GONE);
        editPane.setVisibility(View.GONE);
        diagnosticsPane.setVisibility(View.VISIBLE);
        diagnosticsText.setText(boundedDiagnostics(diagnostics, 200));
    }

    private void copyDiagnostics() {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(
                Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(
                "Memory Editor diagnostics", diagnosticsText.getText()));
        setNotice("Diagnostics copied");
    }

    private void applyStatus(Bundle status) {
        generation = status.getLong(MemoryScanContract.KEY_GENERATION, generation);
        capability = status.getString(MemoryScanContract.KEY_CAPABILITY, capability);
        managedSelfTest = status.getString(
                MemoryScanContract.KEY_MANAGED_SELF_TEST, managedSelfTest);
        state = status.getString(MemoryScanContract.KEY_STATE, state);
        resultCount = status.getLong(MemoryScanContract.KEY_RESULT_COUNT, resultCount);
        operationId = status.getLong(MemoryScanContract.KEY_OPERATION_ID, operationId);
        diagnostics = status.getString(MemoryScanContract.KEY_DIAGNOSTICS, diagnostics);
        selectedScope = status.getInt(MemoryScanContract.KEY_SCOPE, selectedScope);
        int sessionType = status.getInt(MemoryScanContract.KEY_VALUE_TYPE, selectedType);
        if (MemoryScanContract.isSearchType(sessionType)) selectedType = sessionType;
        String currentQuery = status.getString(MemoryScanContract.KEY_QUERY, "");
        if (!currentQuery.isEmpty() && !queryInput.hasFocus()) queryInput.setText(currentQuery);
        typeButton.setText("Type: " + MemoryScanContract.typeName(selectedType));
        scopeButton.setText("Scope: " + MemoryScanContract.scopeName(selectedScope));
        updateStatusUi();
        if (MemoryScanContract.STATE_COMPLETE.equals(state)
                && lastRenderedOperation != operationId) {
            lastRenderedOperation = operationId;
            refreshResults();
        }
    }

    private void updateStatusUi() {
        if (statusText == null) return;
        statusBuilder.setLength(0);
        statusBuilder.append("Self access: ").append(capability)
                .append(" · Managed ART: ").append(managedSelfTest)
                .append(" · State: ").append(state)
                .append(" · UI warm-up GC Δ: ").append(warmupGcDelta);
        if (generation != 0L) statusBuilder.append(" · gen ").append(generation);
        statusText.setText(statusBuilder.toString());
        boolean ready = ready();
        newSearchButton.setEnabled(ready && !MemoryScanContract.STATE_RUNNING.equals(state));
        nextScanButton.setEnabled(ready && MemoryScanContract.STATE_COMPLETE.equals(state));
        cancelButton.setEnabled(MemoryScanContract.STATE_RUNNING.equals(state));
        refreshButton.setEnabled(MemoryScanContract.STATE_COMPLETE.equals(state));
        liveButton.setEnabled(MemoryScanContract.STATE_COMPLETE.equals(state));
    }

    private boolean ready() {
        return generation != 0L && "OK".equals(capability) && "PASS".equals(managedSelfTest);
    }

    private void clearRenderedRows() {
        groupCount = 0;
        if (resultRows == null || resultRows[0] == null) return;
        for (TextView row : resultRows) row.setVisibility(View.GONE);
        resultSummary.setText("No retained candidates");
    }

    private void remoteFailure(RemoteException error) {
        service = null;
        capability = "DISCONNECTED";
        setNotice("Scanner connection failed: " + error.getClass().getSimpleName());
        updateStatusUi();
    }

    private void setNotice(String message) {
        if (noticeText != null) noticeText.setText(message == null ? "" : message);
    }

    private TextView text(String value, float sizeSp) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(Color.WHITE);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(activity);
        button.setText(value);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(8), dp(5), dp(8), dp(5));
        return button;
    }

    private FrameLayout.LayoutParams matchFrame() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private long runtimeGcCount() {
        try {
            String value = Debug.getRuntimeStat(GC_COUNT_STAT);
            return value == null ? -1L : Long.parseLong(value);
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    private static long delta(long before, long after) {
        return before < 0L || after < 0L ? -1L : Math.max(0L, after - before);
    }

    private static void appendHex(StringBuilder out, long value) {
        out.append("0x");
        boolean started = false;
        for (int shift = 60; shift >= 0; shift -= 4) {
            int nibble = (int) ((value >>> shift) & 0xF);
            if (nibble != 0 || started || shift == 0) {
                out.append(HEX[nibble]);
                started = true;
            }
        }
    }

    private static void appendAliasNames(StringBuilder out, int mask) {
        boolean first = true;
        for (int type = MemoryScanContract.TYPE_INT8;
                type <= MemoryScanContract.TYPE_FLOAT64; type++) {
            if ((mask & (1 << type)) == 0) continue;
            if (!first) out.append(" · ");
            out.append(MemoryScanContract.typeName(type));
            first = false;
        }
    }

    private static void appendValue(StringBuilder out, int type, long bits) {
        switch (type) {
            case MemoryScanContract.TYPE_INT8 -> out.append((byte) bits);
            case MemoryScanContract.TYPE_INT16 -> out.append((short) bits);
            case MemoryScanContract.TYPE_UINT16 -> out.append((int) (bits & 0xFFFFL));
            case MemoryScanContract.TYPE_INT32 -> out.append((int) bits);
            case MemoryScanContract.TYPE_INT64 -> out.append(bits);
            case MemoryScanContract.TYPE_FLOAT32 -> out.append(Float.intBitsToFloat((int) bits));
            case MemoryScanContract.TYPE_FLOAT64 -> out.append(Double.longBitsToDouble(bits));
            default -> out.append('?');
        }
    }

    private static String boundedDiagnostics(String source, int maxLines) {
        if (source == null || source.isEmpty()) return "No diagnostics yet";
        int lines = 1;
        int index = 0;
        while (index < source.length() && lines <= maxLines) {
            if (source.charAt(index++) == '\n') lines++;
        }
        if (index >= source.length()) return source;
        return source.substring(0, index)
                + "\n… diagnostics truncated at " + maxLines + " lines";
    }
}
