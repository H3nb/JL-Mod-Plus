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

import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import ru.playsoftware.j2meloader.memory.IMemoryLiveService;
import ru.playsoftware.j2meloader.memory.IMemoryScanCallback;
import ru.playsoftware.j2meloader.memory.IMemoryScanService;
import ru.playsoftware.j2meloader.memory.MemoryLiveService;
import ru.playsoftware.j2meloader.memory.MemoryScanContract;
import ru.playsoftware.j2meloader.memory.MemoryScanService;

/**
 * Debug-only GameGuardian-style UI process. All views, formatting and live UI GC stay in
 * :memory_ui; the target process contains only thin Binder services and libjlmem.so.
 */
public final class MemoryEditorOverlayService extends Service {
    public static final String ACTION_ATTACH =
            "ru.playsoftware.j2meloader.memory.debug.ATTACH_MEMORY_EDITOR";
    public static final String ACTION_SHOW =
            "ru.playsoftware.j2meloader.memory.debug.SHOW_MEMORY_EDITOR";

    private static final int MAX_TYPED_RESULTS = 100;
    private static final int TYPE_SLOTS = 8;
    private static final long LIVE_REFRESH_MS = 1000L;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final long[] groupAddress = new long[MAX_TYPED_RESULTS];
    private final long[] groupPreviousAddress = new long[MAX_TYPED_RESULTS];
    private final int[] groupAliasMask = new int[MAX_TYPED_RESULTS];
    private final int[] groupReadableMask = new int[MAX_TYPED_RESULTS];
    private final long[] groupValueBits = new long[MAX_TYPED_RESULTS * TYPE_SLOTS];
    private final int[] groupTrackingState = new int[MAX_TYPED_RESULTS];
    private final int[] groupConfidence = new int[MAX_TYPED_RESULTS];
    private final long[] groupRelocations = new long[MAX_TYPED_RESULTS];
    private final TextView[] resultRows = new TextView[MAX_TYPED_RESULTS];
    private final StringBuilder[] rowBuilders = new StringBuilder[MAX_TYPED_RESULTS];
    private final StringBuilder statusBuilder = new StringBuilder(320);
    private final StringBuilder editBuilder = new StringBuilder(128);

    private WindowManager windowManager;
    private TextView bubble;
    private WindowManager.LayoutParams bubbleParams;
    private FrameLayout overlay;
    private WindowManager.LayoutParams overlayParams;
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

    private IMemoryScanService scanner;
    private IMemoryLiveService liveService;
    private boolean scannerBound;
    private boolean liveBound;
    private boolean liveEnabled = true;
    private boolean panelVisible;
    private long generation;
    private long resultCount;
    private long operationId;
    private long lastRenderedOperation = -1L;
    private long lastTrackingOperation = -1L;
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

    private final Runnable applyPendingStatus = () -> {
        Bundle status = pendingStatus;
        pendingStatus = null;
        if (status != null) applyStatus(status);
    };

    private final Runnable liveRefresh = new Runnable() {
        @Override
        public void run() {
            if (!liveEnabled || !panelVisible) return;
            refreshResults();
            mainHandler.postDelayed(this, LIVE_REFRESH_MS);
        }
    };

    private final IMemoryScanCallback scannerCallback = new IMemoryScanCallback.Stub() {
        @Override
        public void onStatusChanged(Bundle status) {
            pendingStatus = status;
            mainHandler.removeCallbacks(applyPendingStatus);
            mainHandler.post(applyPendingStatus);
        }

        @Override
        public void onTargetClosed() {
            mainHandler.post(() -> {
                generation = 0L;
                state = MemoryScanContract.STATE_NO_TARGET;
                setNotice("MIDlet target closed");
                stopSelf();
            });
        }
    };

    private final ServiceConnection scannerConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            scanner = IMemoryScanService.Stub.asInterface(binder);
            try {
                Bundle caps = scanner.getCapabilities();
                generation = caps.getLong(MemoryScanContract.KEY_GENERATION, 0L);
                capability = caps.getString(MemoryScanContract.KEY_CAPABILITY, "PENDING");
                managedSelfTest = caps.getString(
                        MemoryScanContract.KEY_MANAGED_SELF_TEST, "RUNNING");
                scanner.registerCallback(scannerCallback);
                updateStatusUi();
            } catch (RemoteException error) {
                scannerFailure(error);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            scanner = null;
            capability = "DISCONNECTED";
            updateStatusUi();
        }

        @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
        @Override public void onNullBinding(ComponentName name) { onServiceDisconnected(name); }
    };

    private final ServiceConnection liveConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            liveService = IMemoryLiveService.Stub.asInterface(binder);
            if (MemoryScanContract.STATE_COMPLETE.equals(state)) refreshResults();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            liveService = null;
            setNotice("Live address tracker disconnected; scanner session is still available");
        }

        @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
        @Override public void onNullBinding(ComponentName name) { onServiceDisconnected(name); }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildBubble();
        buildOverlay();
        bindTargetServices();
        showBubble();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.canDrawOverlays(this)) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_SHOW.equals(intent.getAction())) showPanel();
        else if (bubble == null && windowManager != null) showBubble();
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        appliedOrientation = Configuration.ORIENTATION_UNDEFINED;
        applyAdaptiveLayoutIfNeeded();
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (scanner != null) {
            try { scanner.unregisterCallback(scannerCallback); } catch (RemoteException ignored) {}
        }
        if (scannerBound) runCatchingUnbind(scannerConnection);
        if (liveBound) runCatchingUnbind(liveConnection);
        scannerBound = false;
        liveBound = false;
        scanner = null;
        liveService = null;
        removeWindow(overlay);
        removeWindow(bubble);
        overlay = null;
        bubble = null;
        super.onDestroy();
    }

    private void bindTargetServices() {
        scannerBound = bindService(new Intent(this, MemoryScanService.class),
                scannerConnection, Context.BIND_AUTO_CREATE);
        liveBound = bindService(new Intent(this, MemoryLiveService.class),
                liveConnection, Context.BIND_AUTO_CREATE);
        if (!scannerBound) capability = "BIND_FAILED";
        updateStatusUi();
    }

    private void buildBubble() {
        bubble = text("MEM", 12f);
        bubble.setGravity(Gravity.CENTER);
        bubble.setPadding(dp(11), dp(8), dp(11), dp(8));
        bubble.setBackgroundColor(0xD0222222);
        bubble.setOnClickListener(v -> showPanel());
        bubble.setOnTouchListener(new BubbleDragListener());
        bubbleParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.END;
        bubbleParams.x = dp(10);
        bubbleParams.y = dp(80);
    }

    private void buildOverlay() {
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x1A000000);
        overlay.setClickable(true);
        overlay.setFocusable(true);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(10));
        panel.setBackgroundColor(0xB31B1B1B);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        panelParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        overlay.addView(panel, panelParams);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Memory Editor · libjlmem · target :midlet", 18f);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button close = button("Close");
        close.setOnClickListener(v -> hidePanel());
        header.addView(close);
        panel.addView(header);

        statusText = text("Connecting…", 12f);
        statusText.setTextIsSelectable(true);
        panel.addView(statusText);
        noticeText = text("", 12f);
        noticeText.setTextColor(0xFFFFCC80);
        panel.addView(noticeText);

        contentFrame = new FrameLayout(this);
        panel.addView(contentFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        buildMainPane();
        buildEditPane();
        buildDiagnosticsPane();
        applyAdaptiveLayoutIfNeeded();

        overlayParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
    }

    private void buildMainPane() {
        mainPane = new LinearLayout(this);
        mainPane.setOrientation(LinearLayout.VERTICAL);
        contentFrame.addView(mainPane, matchFrame());

        adaptiveBody = new LinearLayout(this);
        mainPane.addView(adaptiveBody, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(0, dp(4), dp(8), dp(4));
        controlsPane = new ScrollView(this);
        controlsPane.setFillViewport(true);
        controlsPane.addView(controls);
        adaptiveBody.addView(controlsPane);

        queryInput = new EditText(this);
        queryInput.setSingleLine(true);
        queryInput.setHint("Value");
        queryInput.setTextColor(Color.WHITE);
        queryInput.setHintTextColor(0xFFAAAAAA);
        queryInput.setInputType(InputType.TYPE_CLASS_TEXT);
        controls.addView(queryInput);

        LinearLayout selectors = horizontal();
        typeButton = button("Type: Auto");
        typeButton.setOnClickListener(v -> {
            selectedType = (selectedType + 1) % (MemoryScanContract.TYPE_FLOAT64 + 1);
            typeButton.setText("Type: " + MemoryScanContract.typeName(selectedType));
        });
        selectors.addView(typeButton, weighted());
        scopeButton = button("Scope: Java Fast");
        scopeButton.setOnClickListener(v -> {
            selectedScope = selectedScope == MemoryScanContract.SCOPE_JAVA_FAST
                    ? MemoryScanContract.SCOPE_JAVA_THOROUGH
                    : MemoryScanContract.SCOPE_JAVA_FAST;
            scopeButton.setText("Scope: " + MemoryScanContract.scopeName(selectedScope));
        });
        selectors.addView(scopeButton, weighted());
        controls.addView(selectors);

        LinearLayout searchActions = horizontal();
        newSearchButton = button("New Search");
        newSearchButton.setOnClickListener(v -> startSearch());
        searchActions.addView(newSearchButton, weighted());
        nextScanButton = button("Next Scan");
        nextScanButton.setOnClickListener(v -> nextScan());
        searchActions.addView(nextScanButton, weighted());
        controls.addView(searchActions);

        LinearLayout sessionActions = horizontal();
        cancelButton = button("Cancel");
        cancelButton.setOnClickListener(v -> cancelOperation());
        sessionActions.addView(cancelButton, weighted());
        Button clear = button("Clear");
        clear.setOnClickListener(v -> clearSearch());
        sessionActions.addView(clear, weighted());
        controls.addView(sessionActions);

        LinearLayout observationActions = horizontal();
        refreshButton = button("Refresh");
        refreshButton.setOnClickListener(v -> refreshResults());
        observationActions.addView(refreshButton, weighted());
        liveButton = button("Live: On");
        liveButton.setOnClickListener(v -> setLiveEnabled(!liveEnabled));
        observationActions.addView(liveButton, weighted());
        controls.addView(observationActions);

        Button diagnosticsButton = button("Diagnostics");
        diagnosticsButton.setOnClickListener(v -> showDiagnostics());
        controls.addView(diagnosticsButton);

        resultsPane = new LinearLayout(this);
        resultsPane.setOrientation(LinearLayout.VERTICAL);
        resultsPane.setPadding(dp(8), dp(4), 0, dp(4));
        adaptiveBody.addView(resultsPane);
        resultSummary = text("No retained candidates", 13f);
        resultsPane.addView(resultSummary);
        ScrollView resultScroll = new ScrollView(this);
        LinearLayout resultList = new LinearLayout(this);
        resultList.setOrientation(LinearLayout.VERTICAL);
        resultScroll.addView(resultList);
        resultsPane.addView(resultScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        for (int i = 0; i < MAX_TYPED_RESULTS; ++i) {
            final int rowIndex = i;
            TextView row = text("", 12f);
            row.setPadding(dp(4), dp(7), dp(4), dp(7));
            row.setVisibility(View.GONE);
            row.setOnClickListener(v -> openEdit(rowIndex));
            resultRows[i] = row;
            rowBuilders[i] = new StringBuilder(128);
            resultList.addView(row);
        }
    }

    private void buildEditPane() {
        editPane = new LinearLayout(this);
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
        replacementInput = new EditText(this);
        replacementInput.setHint("Replacement value");
        replacementInput.setSingleLine(true);
        replacementInput.setTextColor(Color.WHITE);
        replacementInput.setHintTextColor(0xFFAAAAAA);
        editPane.addView(replacementInput);
        LinearLayout actions = horizontal();
        Button apply = button("Apply exact typed write");
        apply.setOnClickListener(v -> applyEdit());
        actions.addView(apply, weighted());
        Button back = button("Back");
        back.setOnClickListener(v -> leaveSubPane());
        actions.addView(back, weighted());
        editPane.addView(actions);
    }

    private void buildDiagnosticsPane() {
        diagnosticsPane = new LinearLayout(this);
        diagnosticsPane.setOrientation(LinearLayout.VERTICAL);
        diagnosticsPane.setPadding(dp(8), dp(8), dp(8), dp(8));
        diagnosticsPane.setVisibility(View.GONE);
        contentFrame.addView(diagnosticsPane, matchFrame());
        diagnosticsPane.addView(text("Diagnostics · scanner + live address tracker", 18f));
        ScrollView scroll = new ScrollView(this);
        diagnosticsText = text("", 12f);
        diagnosticsText.setTextIsSelectable(true);
        scroll.addView(diagnosticsText);
        diagnosticsPane.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout actions = horizontal();
        Button copy = button("Copy");
        copy.setOnClickListener(v -> copyDiagnostics());
        actions.addView(copy, weighted());
        Button back = button("Back");
        back.setOnClickListener(v -> leaveSubPane());
        actions.addView(back, weighted());
        diagnosticsPane.addView(actions);
    }

    private void showBubble() {
        if (bubble == null || bubble.getParent() != null || !Settings.canDrawOverlays(this)) return;
        try { windowManager.addView(bubble, bubbleParams); } catch (RuntimeException ignored) {}
    }

    private void showPanel() {
        if (overlay == null || !Settings.canDrawOverlays(this)) return;
        removeWindow(bubble);
        if (overlay.getParent() == null) {
            try { windowManager.addView(overlay, overlayParams); } catch (RuntimeException error) {
                setNotice("Unable to show overlay: " + error.getClass().getSimpleName());
                showBubble();
                return;
            }
        }
        panelVisible = true;
        applyAdaptiveLayoutIfNeeded();
        refreshResults();
        scheduleLiveRefresh();
    }

    private void hidePanel() {
        panelVisible = false;
        mainHandler.removeCallbacks(liveRefresh);
        leaveSubPane();
        removeWindow(overlay);
        showBubble();
    }

    private void applyAdaptiveLayoutIfNeeded() {
        if (adaptiveBody == null) return;
        int orientation = getResources().getConfiguration().orientation;
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

    private void startSearch() {
        if (scanner == null || !ready()) {
            setNotice("Scanner is not ready; wait for native + Managed ART self-tests");
            return;
        }
        String query = queryInput.getText().toString().trim();
        if (query.isEmpty()) { setNotice("Enter a value first"); return; }
        try {
            if (liveService != null) liveService.resetVisibleTracking(generation);
            long id = scanner.startSearch(generation, query, selectedScope, selectedType);
            if (id < 0L) { setNotice("New Search rejected by target/session guard"); return; }
            operationId = id;
            state = MemoryScanContract.STATE_RUNNING;
            clearRenderedRows();
            updateStatusUi();
        } catch (RemoteException error) {
            scannerFailure(error);
        }
    }

    private void nextScan() {
        if (scanner == null || generation == 0L) return;
        String query = queryInput.getText().toString().trim();
        if (query.isEmpty()) { setNotice("Enter the new value before Next Scan"); return; }
        try {
            long id = scanner.refine(generation, query);
            if (id < 0L) { setNotice("Next Scan requires a completed search in this generation"); return; }
            operationId = id;
            state = MemoryScanContract.STATE_RUNNING;
            updateStatusUi();
        } catch (RemoteException error) {
            scannerFailure(error);
        }
    }

    private void cancelOperation() {
        if (scanner == null) return;
        try { scanner.cancelOperation(generation); } catch (RemoteException error) {
            scannerFailure(error);
        }
    }

    private void clearSearch() {
        if (scanner == null) return;
        try {
            scanner.clearSearch(generation);
            if (liveService != null) liveService.resetVisibleTracking(generation);
            clearRenderedRows();
            setNotice("Search clear requested");
        } catch (RemoteException error) {
            scannerFailure(error);
        }
    }

    private void setLiveEnabled(boolean enabled) {
        liveEnabled = enabled;
        liveButton.setText(enabled ? "Live: On" : "Live: Off");
        mainHandler.removeCallbacks(liveRefresh);
        if (enabled) {
            refreshResults();
            scheduleLiveRefresh();
        }
    }

    private void scheduleLiveRefresh() {
        mainHandler.removeCallbacks(liveRefresh);
        if (liveEnabled && panelVisible && MemoryScanContract.STATE_COMPLETE.equals(state)) {
            mainHandler.postDelayed(liveRefresh, LIVE_REFRESH_MS);
        }
    }

    private void refreshResults() {
        if (generation == 0L || !MemoryScanContract.STATE_COMPLETE.equals(state)) return;
        try {
            long[] page;
            int stride;
            if (liveService != null) {
                page = liveService.getLiveResultsPage(generation, 0, MAX_TYPED_RESULTS);
                stride = MemoryScanContract.LIVE_RESULT_STRIDE;
            } else if (scanner != null) {
                page = scanner.getResultsPage(generation, 0, MAX_TYPED_RESULTS);
                stride = MemoryScanContract.RAW_RESULT_STRIDE;
            } else {
                return;
            }
            renderResults(page, stride);
        } catch (RemoteException error) {
            scannerFailure(error);
        }
    }

    private void renderResults(long[] raw, int stride) {
        groupCount = 0;
        if (raw == null || raw.length == 0) { clearRenderedRows(); return; }
        int typedCount = (int) raw[0];
        int required = 1 + typedCount * stride;
        if (typedCount < 0 || typedCount > MAX_TYPED_RESULTS || required > raw.length) {
            setNotice("Malformed result page from target agent");
            return;
        }
        int index = 1;
        for (int i = 0; i < typedCount; ++i, index += stride) {
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
            if (stride == MemoryScanContract.LIVE_RESULT_STRIDE) {
                groupTrackingState[group] = (int) raw[index + 4];
                groupPreviousAddress[group] = raw[index + 5];
                groupConfidence[group] = (int) raw[index + 6];
                groupRelocations[group] = raw[index + 7];
            }
        }

        for (int i = 0; i < MAX_TYPED_RESULTS; ++i) {
            TextView row = resultRows[i];
            if (i >= groupCount) { row.setVisibility(View.GONE); continue; }
            StringBuilder out = rowBuilders[i];
            out.setLength(0);
            appendHex(out, groupAddress[i]);
            out.append("  ·  ");
            appendAliasNames(out, groupAliasMask[i]);
            out.append("  ·  ").append(MemoryScanContract.trackingStateName(groupTrackingState[i]));
            if (groupTrackingState[i] != MemoryScanContract.TRACK_UNTRACKED) {
                out.append(' ').append(groupConfidence[i]).append('%');
            }
            if (groupRelocations[i] > 0) out.append("  ·  rebind ").append(groupRelocations[i]);
            int displayType = firstReadableType(i);
            out.append('\n');
            if (displayType > 0) {
                appendValue(out, displayType, groupValueBits[i * TYPE_SLOTS + displayType]);
                if (Integer.bitCount(groupAliasMask[i]) > 1) {
                    out.append("  [").append(MemoryScanContract.typeName(displayType)).append(']');
                }
            } else {
                out.append("<identity stale / unreadable>");
            }
            if (groupPreviousAddress[i] != 0 && groupPreviousAddress[i] != groupAddress[i]) {
                out.append("\nprev ");
                appendHex(out, groupPreviousAddress[i]);
            }
            row.setText(out.toString());
            row.setVisibility(View.VISIBLE);
        }
        resultSummary.setText(resultCount + " typed candidates · " + groupCount
                + " addresses shown · live value/address " + (liveEnabled ? "ON" : "OFF"));
    }

    private int findOrCreateGroup(long address) {
        for (int i = 0; i < groupCount; ++i) if (groupAddress[i] == address) return i;
        if (groupCount >= MAX_TYPED_RESULTS) return -1;
        int slot = groupCount++;
        groupAddress[slot] = address;
        groupPreviousAddress[slot] = 0L;
        groupAliasMask[slot] = 0;
        groupReadableMask[slot] = 0;
        groupTrackingState[slot] = MemoryScanContract.TRACK_UNTRACKED;
        groupConfidence[slot] = 0;
        groupRelocations[slot] = 0L;
        return slot;
    }

    private int firstReadableType(int group) {
        int readable = groupReadableMask[group];
        int aliases = groupAliasMask[group];
        for (int type = MemoryScanContract.TYPE_INT8; type <= MemoryScanContract.TYPE_FLOAT64; ++type) {
            if ((readable & (1 << type)) != 0) return type;
        }
        for (int type = MemoryScanContract.TYPE_INT8; type <= MemoryScanContract.TYPE_FLOAT64; ++type) {
            if ((aliases & (1 << type)) != 0) return type;
        }
        return -1;
    }

    private void openEdit(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= groupCount) return;
        int tracking = groupTrackingState[rowIndex];
        if (tracking == MemoryScanContract.TRACK_AMBIGUOUS
                || tracking == MemoryScanContract.TRACK_LOST
                || tracking == MemoryScanContract.TRACK_SUSPECT) {
            setNotice("Refusing write: candidate identity is not stable");
            return;
        }
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
        for (int i = 0; i < MemoryScanContract.TYPE_FLOAT64; ++i) {
            if (++candidate > MemoryScanContract.TYPE_FLOAT64) candidate = MemoryScanContract.TYPE_INT8;
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
        editBuilder.append(" · ").append(MemoryScanContract.trackingStateName(
                groupTrackingState[editGroup]));
        editAddressText.setText(editBuilder.toString());
        editTypeButton.setText("Type: " + MemoryScanContract.typeName(editType)
                + (Integer.bitCount(groupAliasMask[editGroup]) > 1 ? " · tap alias" : ""));
        editBuilder.setLength(0);
        if ((groupReadableMask[editGroup] & (1 << editType)) != 0) {
            editBuilder.append("Current: ");
            appendValue(editBuilder, editType, groupValueBits[editGroup * TYPE_SLOTS + editType]);
        } else editBuilder.append("Current: <stale/unreadable>");
        editCurrentText.setText(editBuilder.toString());
    }

    private void applyEdit() {
        if (scanner == null || editGroup < 0) return;
        int tracking = groupTrackingState[editGroup];
        if (tracking == MemoryScanContract.TRACK_AMBIGUOUS
                || tracking == MemoryScanContract.TRACK_LOST
                || tracking == MemoryScanContract.TRACK_SUSPECT
                || (groupReadableMask[editGroup] & (1 << editType)) == 0) {
            setNotice("Refusing write: live identity is not safe");
            return;
        }
        editBuilder.setLength(0);
        appendValue(editBuilder, editType, groupValueBits[editGroup * TYPE_SLOTS + editType]);
        String expected = editBuilder.toString();
        String replacement = replacementInput.getText().toString().trim();
        if (replacement.isEmpty()) { setNotice("Enter a replacement value"); return; }
        try {
            Bundle result = scanner.editValue(
                    generation, groupAddress[editGroup], editType, expected, replacement);
            setNotice(result.getString(MemoryScanContract.KEY_MESSAGE, "Write completed"));
            leaveSubPane();
            refreshResults();
        } catch (RemoteException error) {
            scannerFailure(error);
        }
    }

    private void showDiagnostics() {
        mainPane.setVisibility(View.GONE);
        editPane.setVisibility(View.GONE);
        diagnosticsPane.setVisibility(View.VISIBLE);
        String live = "liveTracking=DISCONNECTED";
        if (liveService != null && generation != 0L) {
            try { live = liveService.getTrackingDiagnostics(generation); }
            catch (RemoteException ignored) { live = "liveTracking=REMOTE_ERROR"; }
        }
        diagnosticsText.setText(boundedDiagnostics(
                "uiProcess=:memory_ui\ntargetProcess=:midlet\nnativeBackbone=libjlmem.so\n"
                        + diagnostics + "\n" + live, 200));
    }

    private void copyDiagnostics() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(
                "Memory Editor diagnostics", diagnosticsText.getText()));
        setNotice("Diagnostics copied");
    }

    private void leaveSubPane() {
        mainPane.setVisibility(View.VISIBLE);
        editPane.setVisibility(View.GONE);
        diagnosticsPane.setVisibility(View.GONE);
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
                && lastTrackingOperation != operationId && liveService != null) {
            try { liveService.resetVisibleTracking(generation); } catch (RemoteException ignored) {}
            lastTrackingOperation = operationId;
        }
        if (MemoryScanContract.STATE_COMPLETE.equals(state)
                && lastRenderedOperation != operationId) {
            lastRenderedOperation = operationId;
            refreshResults();
        }
        scheduleLiveRefresh();
    }

    private void updateStatusUi() {
        if (statusText == null) return;
        statusBuilder.setLength(0);
        statusBuilder.append("UI :memory_ui · Agent :midlet · Self access: ")
                .append(capability)
                .append(" · Managed ART: ").append(managedSelfTest)
                .append(" · ").append(state);
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
        for (TextView row : resultRows) if (row != null) row.setVisibility(View.GONE);
        if (resultSummary != null) resultSummary.setText("No retained candidates");
    }

    private void scannerFailure(RemoteException error) {
        scanner = null;
        capability = "DISCONNECTED";
        setNotice("Target agent failed: " + error.getClass().getSimpleName());
        updateStatusUi();
    }

    private void setNotice(String value) {
        if (noticeText != null) noticeText.setText(value == null ? "" : value);
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private FrameLayout.LayoutParams matchFrame() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private TextView text(String value, float sizeSp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(Color.WHITE);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(8), dp(5), dp(8), dp(5));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int overlayWindowType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void removeWindow(View view) {
        if (view == null || view.getParent() == null || windowManager == null) return;
        try { windowManager.removeView(view); } catch (RuntimeException ignored) {}
    }

    private void runCatchingUnbind(ServiceConnection connection) {
        try { unbindService(connection); } catch (RuntimeException ignored) {}
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
        for (int type = MemoryScanContract.TYPE_INT8; type <= MemoryScanContract.TYPE_FLOAT64; ++type) {
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
            if (source.charAt(index++) == '\n') ++lines;
        }
        if (index >= source.length()) return source;
        return source.substring(0, index) + "\n… diagnostics truncated at " + maxLines + " lines";
    }

    private final class BubbleDragListener implements View.OnTouchListener {
        private float downRawX;
        private float downRawY;
        private int downX;
        private int downY;
        private boolean moved;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    downX = bubbleParams.x;
                    downY = bubbleParams.y;
                    moved = false;
                    return false;
                }
                case MotionEvent.ACTION_MOVE -> {
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (Math.abs(dx) + Math.abs(dy) > dp(6)) moved = true;
                    if (moved) {
                        bubbleParams.x = Math.max(0, downX - Math.round(dx));
                        bubbleParams.y = Math.max(0, downY + Math.round(dy));
                        try { windowManager.updateViewLayout(bubble, bubbleParams); }
                        catch (RuntimeException ignored) {}
                        return true;
                    }
                }
                case MotionEvent.ACTION_UP -> {
                    if (!moved) view.performClick();
                    return moved;
                }
                default -> { }
            }
            return false;
        }
    }
}
