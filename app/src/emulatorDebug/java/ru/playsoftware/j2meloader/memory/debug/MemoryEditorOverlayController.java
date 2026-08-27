/*
 * Copyright 2026 JL-Mod Plus contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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
import ru.playsoftware.j2meloader.memory.RemoteEngineStatus;
import ru.playsoftware.j2meloader.memory.RemoteMemoryScanService;

/** Debug-only classic View overlay attached directly to the running MicroActivity. */
final class MemoryEditorOverlayController {
    private static final int MAX_TYPED_RESULTS = 100;
    private static final int RESULT_STRIDE = MemoryScanContract.RAW_RESULT_STRIDE;
    private static final int TYPE_SLOTS = 8;
    private static final long LIVE_REFRESH_MS = 1500L;
    private static final String GC_COUNT_STAT = "art.gc.gc-count";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final MicroActivity activity;
    private FrameLayout host;
    private TextView trigger;
    private FrameLayout overlay;
    private FrameLayout content;
    private LinearLayout mainPane;
    private LinearLayout editPane;
    private LinearLayout diagnosticsPane;
    private LinearLayout adaptiveBody;
    private ScrollView controlsPane;
    private LinearLayout resultsPane;
    private TextView statusText;
    private TextView noticeText;
    private TextView resultSummary;
    private TextView diagnosticsText;
    private EditText queryInput;
    private EditText replacementInput;
    private Button typeButton;
    private Button scopeButton;
    private Button newSearchButton;
    private Button nextScanButton;
    private Button cancelButton;
    private Button refreshButton;
    private Button liveButton;
    private Button editTypeButton;
    private TextView editAddressText;
    private TextView editCurrentText;

    private Handler mainHandler;
    private IMemoryScanService service;
    private IMemoryScanCallback callback;
    private ServiceConnection connection;
    private boolean bound;
    private boolean remoteBackend;
    private boolean built;
    private boolean destroyed;
    private boolean liveEnabled;
    private long generation;
    private long resultCount;
    private long operationId;
    private long lastRenderedOperation = -1L;
    private long warmupGcDelta;
    private String capability = "PENDING";
    private String managedSelfTest = "RUNNING";
    private String state = MemoryScanContract.STATE_NO_TARGET;
    private String diagnostics = "";
    private int selectedType = MemoryScanContract.TYPE_AUTO;
    private int selectedScope = MemoryScanContract.SCOPE_JAVA_FAST;
    private int orientation = Configuration.ORIENTATION_UNDEFINED;
    private int editGroup = -1;
    private int editType = MemoryScanContract.TYPE_INT32;

    private long[] groupAddress;
    private int[] groupAliasMask;
    private int[] groupReadableMask;
    private long[] groupValueBits;
    private TextView[] rows;
    private StringBuilder[] rowBuilders;
    private StringBuilder scratch;
    private StringBuilder statusBuilder;
    private int groupCount;
    private Bundle pendingStatus;

    private Runnable applyPendingStatus;
    private Runnable liveRefresh;
    private Runnable targetClosed;

    MemoryEditorOverlayController(MicroActivity activity) {
        this.activity = activity;
    }

    boolean owns(Activity candidate) { return activity == candidate; }

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
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        lp.topMargin = dp(12);
        lp.rightMargin = dp(10);
        host.addView(trigger, lp);
        host.setOnApplyWindowInsetsListener((v, insets) -> {
            applyInsets(insets);
            return insets;
        });
        host.requestApplyInsets();
    }

    void destroy() {
        if (destroyed) return;
        destroyed = true;
        setLiveEnabled(false);
        if (mainHandler != null) mainHandler.removeCallbacksAndMessages(null);
        unbindScanner();
        if (host != null && host.getParent() instanceof ViewGroup parent) parent.removeView(host);
        host = null;
        overlay = null;
    }

    private void showOverlay() {
        if (destroyed) return;
        if (!built) warmUp();
        else ensurePreferredBackend();
        if (overlay == null) return;
        trigger.setVisibility(View.GONE);
        overlay.setVisibility(View.VISIBLE);
        applyAdaptiveLayout();
        overlay.requestFocus();
    }

    private void warmUp() {
        long before = runtimeGcCount();
        mainHandler = new Handler(Looper.getMainLooper());
        groupAddress = new long[MAX_TYPED_RESULTS];
        groupAliasMask = new int[MAX_TYPED_RESULTS];
        groupReadableMask = new int[MAX_TYPED_RESULTS];
        groupValueBits = new long[MAX_TYPED_RESULTS * TYPE_SLOTS];
        rows = new TextView[MAX_TYPED_RESULTS];
        rowBuilders = new StringBuilder[MAX_TYPED_RESULTS];
        scratch = new StringBuilder(128);
        statusBuilder = new StringBuilder(256);
        buildCallbacks();
        buildOverlay();
        warmupGcDelta = delta(before, runtimeGcCount());
        built = true;
        bindScanner();
        updateStatusUi();
    }

    private void buildCallbacks() {
        applyPendingStatus = () -> {
            Bundle value = pendingStatus;
            pendingStatus = null;
            if (value != null && !destroyed) applyStatus(value);
        };
        targetClosed = () -> {
            generation = 0L;
            state = MemoryScanContract.STATE_NO_TARGET;
            setNotice("MIDlet target closed; memory session discarded");
            hideOverlay();
        };
        liveRefresh = new Runnable() {
            @Override public void run() {
                if (!liveEnabled || destroyed || overlay == null
                        || overlay.getVisibility() != View.VISIBLE) return;
                refreshResults();
                mainHandler.postDelayed(this, LIVE_REFRESH_MS);
            }
        };
        callback = new IMemoryScanCallback.Stub() {
            @Override public void onStatusChanged(Bundle status) {
                pendingStatus = status;
                mainHandler.removeCallbacks(applyPendingStatus);
                mainHandler.post(applyPendingStatus);
            }
            @Override public void onTargetClosed() {
                mainHandler.removeCallbacks(targetClosed);
                mainHandler.post(targetClosed);
            }
        };
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (destroyed) return;
                service = IMemoryScanService.Stub.asInterface(binder);
                try {
                    Bundle caps = service.getCapabilities();
                    generation = caps.getLong(MemoryScanContract.KEY_GENERATION, 0L);
                    capability = caps.getString(MemoryScanContract.KEY_CAPABILITY, "PENDING");
                    managedSelfTest = caps.getString(MemoryScanContract.KEY_MANAGED_SELF_TEST, "RUNNING");
                    service.registerCallback(callback);
                    updateStatusUi();
                } catch (RemoteException error) { remoteFailure(error); }
            }
            @Override public void onServiceDisconnected(ComponentName name) {
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
        remoteBackend = RemoteEngineStatus.supported();
        Class<?> scanner = remoteBackend ? RemoteMemoryScanService.class : MemoryScanService.class;
        bound = activity.bindService(new Intent(activity, scanner), connection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            capability = "BIND_FAILED";
            setNotice("Unable to bind " + scanner.getSimpleName());
        }
    }

    private void ensurePreferredBackend() {
        if (!RemoteEngineStatus.supported() || remoteBackend || resultCount > 0L
                || MemoryScanContract.STATE_RUNNING.equals(state)) return;
        unbindScanner();
        generation = 0L;
        capability = "PENDING";
        managedSelfTest = "RUNNING";
        state = MemoryScanContract.STATE_NO_TARGET;
        bindScanner();
        setNotice("Switching idle Memory Editor to isolated :memory_engine backend");
    }

    private void unbindScanner() {
        IMemoryScanService current = service;
        if (current != null && callback != null) {
            try { current.unregisterCallback(callback); } catch (RemoteException ignored) {}
        }
        service = null;
        if (bound && connection != null) {
            try { activity.unbindService(connection); } catch (RuntimeException ignored) {}
        }
        bound = false;
    }

    private void buildOverlay() {
        overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(0x14000000);
        overlay.setVisibility(View.GONE);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        host.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(10));
        panel.setBackgroundColor(0xB31B1B1B);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        pp.setMargins(dp(8), dp(8), dp(8), dp(8));
        overlay.addView(panel, pp);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Memory Editor · remote raw memory", 18f);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button close = button("Close");
        close.setOnClickListener(v -> hideOverlay());
        header.addView(close);
        panel.addView(header);

        statusText = text("Warming up…", 12f);
        statusText.setTextIsSelectable(true);
        panel.addView(statusText);
        noticeText = text("", 12f);
        noticeText.setTextColor(0xFFFFCC80);
        panel.addView(noticeText);

        content = new FrameLayout(activity);
        panel.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        buildMainPane();
        buildEditPane();
        buildDiagnosticsPane();
        applyAdaptiveLayout();
    }

    private void buildMainPane() {
        mainPane = new LinearLayout(activity);
        mainPane.setOrientation(LinearLayout.VERTICAL);
        content.addView(mainPane, matchFrame());
        adaptiveBody = new LinearLayout(activity);
        mainPane.addView(adaptiveBody, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout controls = new LinearLayout(activity);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(0, dp(4), dp(8), dp(4));
        controlsPane = new ScrollView(activity);
        controlsPane.setFillViewport(true);
        controlsPane.addView(controls);
        adaptiveBody.addView(controlsPane);

        queryInput = new EditText(activity);
        queryInput.setSingleLine(true);
        queryInput.setHint("Value");
        queryInput.setTextColor(Color.WHITE);
        queryInput.setHintTextColor(0xFFAAAAAA);
        queryInput.setInputType(InputType.TYPE_CLASS_TEXT);
        controls.addView(queryInput);

        LinearLayout selectors = new LinearLayout(activity);
        typeButton = button("Type: Auto");
        typeButton.setOnClickListener(v -> {
            selectedType = (selectedType + 1) % (MemoryScanContract.TYPE_FLOAT64 + 1);
            typeButton.setText("Type: " + MemoryScanContract.typeName(selectedType));
        });
        selectors.addView(typeButton, weighted());
        scopeButton = button("Scope: Java Fast");
        scopeButton.setOnClickListener(v -> {
            selectedScope = selectedScope == MemoryScanContract.SCOPE_JAVA_FAST
                    ? MemoryScanContract.SCOPE_JAVA_THOROUGH : MemoryScanContract.SCOPE_JAVA_FAST;
            scopeButton.setText("Scope: " + MemoryScanContract.scopeName(selectedScope));
        });
        selectors.addView(scopeButton, weighted());
        controls.addView(selectors);

        LinearLayout searchActions = new LinearLayout(activity);
        newSearchButton = button("New Search");
        newSearchButton.setOnClickListener(v -> startSearch());
        searchActions.addView(newSearchButton, weighted());
        nextScanButton = button("Next Scan");
        nextScanButton.setOnClickListener(v -> nextScan());
        searchActions.addView(nextScanButton, weighted());
        controls.addView(searchActions);

        LinearLayout sessionActions = new LinearLayout(activity);
        cancelButton = button("Cancel");
        cancelButton.setOnClickListener(v -> cancelOperation());
        sessionActions.addView(cancelButton, weighted());
        Button clear = button("Clear");
        clear.setOnClickListener(v -> clearSearch());
        sessionActions.addView(clear, weighted());
        controls.addView(sessionActions);

        LinearLayout observe = new LinearLayout(activity);
        refreshButton = button("Refresh");
        refreshButton.setOnClickListener(v -> refreshResults());
        observe.addView(refreshButton, weighted());
        liveButton = button("Live: Off");
        liveButton.setOnClickListener(v -> setLiveEnabled(!liveEnabled));
        observe.addView(liveButton, weighted());
        controls.addView(observe);
        Button diag = button("Diagnostics");
        diag.setOnClickListener(v -> showDiagnostics());
        controls.addView(diag);

        resultsPane = new LinearLayout(activity);
        resultsPane.setOrientation(LinearLayout.VERTICAL);
        resultsPane.setPadding(dp(8), dp(4), 0, dp(4));
        adaptiveBody.addView(resultsPane);
        resultSummary = text("No retained candidates", 13f);
        resultsPane.addView(resultSummary);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        resultsPane.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        for (int i = 0; i < MAX_TYPED_RESULTS; ++i) {
            final int rowIndex = i;
            TextView row = text("", 12f);
            row.setPadding(dp(4), dp(7), dp(4), dp(7));
            row.setVisibility(View.GONE);
            row.setOnClickListener(v -> openEdit(rowIndex));
            rows[i] = row;
            rowBuilders[i] = new StringBuilder(96);
            list.addView(row);
        }
    }

    private void buildEditPane() {
        editPane = new LinearLayout(activity);
        editPane.setOrientation(LinearLayout.VERTICAL);
        editPane.setPadding(dp(8), dp(8), dp(8), dp(8));
        editPane.setVisibility(View.GONE);
        content.addView(editPane, matchFrame());
        editPane.addView(text("Edit typed value", 18f));
        editAddressText = text("", 13f);
        editAddressText.setTextIsSelectable(true);
        editPane.addView(editAddressText);
        editTypeButton = button("Type");
        editTypeButton.setOnClickListener(v -> cycleEditType());
        editPane.addView(editTypeButton);
        editCurrentText = text("", 14f);
        editPane.addView(editCurrentText);
        replacementInput = new EditText(activity);
        replacementInput.setSingleLine(true);
        replacementInput.setHint("Replacement value");
        replacementInput.setTextColor(Color.WHITE);
        replacementInput.setHintTextColor(0xFFAAAAAA);
        editPane.addView(replacementInput);
        LinearLayout actions = new LinearLayout(activity);
        Button apply = button("Apply exact typed write");
        apply.setOnClickListener(v -> applyEdit());
        actions.addView(apply, weighted());
        Button back = button("Back");
        back.setOnClickListener(v -> leaveSubPane());
        actions.addView(back, weighted());
        editPane.addView(actions);
    }

    private void buildDiagnosticsPane() {
        diagnosticsPane = new LinearLayout(activity);
        diagnosticsPane.setOrientation(LinearLayout.VERTICAL);
        diagnosticsPane.setPadding(dp(8), dp(8), dp(8), dp(8));
        diagnosticsPane.setVisibility(View.GONE);
        content.addView(diagnosticsPane, matchFrame());
        diagnosticsPane.addView(text("Diagnostics · bounded snapshot", 18f));
        ScrollView scroll = new ScrollView(activity);
        diagnosticsText = text("", 12f);
        diagnosticsText.setTextIsSelectable(true);
        scroll.addView(diagnosticsText);
        diagnosticsPane.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout actions = new LinearLayout(activity);
        Button copy = button("Copy");
        copy.setOnClickListener(v -> copyDiagnostics());
        actions.addView(copy, weighted());
        Button back = button("Back");
        back.setOnClickListener(v -> leaveSubPane());
        actions.addView(back, weighted());
        diagnosticsPane.addView(actions);
    }

    private void startSearch() {
        IMemoryScanService current = service;
        if (current == null || !ready()) { setNotice("Scanner is not ready"); return; }
        String query = queryInput.getText().toString().trim();
        if (query.isEmpty()) { setNotice("Enter a value first"); return; }
        try {
            long id = current.startSearch(generation, query, selectedScope, selectedType);
            if (id < 0L) { setNotice("New Search rejected by target/session guard"); return; }
            operationId = id;
            state = MemoryScanContract.STATE_RUNNING;
            clearRows();
            updateStatusUi();
        } catch (RemoteException error) { remoteFailure(error); }
    }

    private void nextScan() {
        IMemoryScanService current = service;
        if (current == null || generation == 0L) return;
        String query = queryInput.getText().toString().trim();
        if (query.isEmpty()) { setNotice("Enter the new value before Next Scan"); return; }
        try {
            long id = current.refine(generation, query);
            if (id < 0L) { setNotice("Next Scan requires an active search session"); return; }
            operationId = id;
            state = MemoryScanContract.STATE_RUNNING;
            updateStatusUi();
        } catch (RemoteException error) { remoteFailure(error); }
    }

    private void cancelOperation() {
        if (service == null) return;
        try { service.cancelOperation(generation); } catch (RemoteException error) { remoteFailure(error); }
    }

    private void clearSearch() {
        if (service == null) return;
        setLiveEnabled(false);
        try {
            service.clearSearch(generation);
            resultCount = 0L;
            clearRows();
        } catch (RemoteException error) { remoteFailure(error); }
    }

    private void refreshResults() {
        if (service == null || generation == 0L || !MemoryScanContract.STATE_COMPLETE.equals(state)) return;
        try { renderResults(service.getResultsPage(generation, 0, MAX_TYPED_RESULTS)); }
        catch (RemoteException error) { remoteFailure(error); }
    }

    private void renderResults(long[] raw) {
        groupCount = 0;
        if (raw == null || raw.length == 0) { clearRows(); return; }
        int typedCount = (int) raw[0];
        if (typedCount < 0 || typedCount > MAX_TYPED_RESULTS
                || 1 + typedCount * RESULT_STRIDE > raw.length) {
            setNotice("Malformed result page from scanner");
            return;
        }
        for (int i = 0, p = 1; i < typedCount; ++i, p += RESULT_STRIDE) {
            long address = raw[p];
            int type = (int) raw[p + 1];
            if (!MemoryScanContract.isCandidateType(type)) continue;
            int group = findOrCreateGroup(address);
            if (group < 0) continue;
            groupAliasMask[group] |= 1 << type;
            if (raw[p + 2] != 0L) {
                groupReadableMask[group] |= 1 << type;
                groupValueBits[group * TYPE_SLOTS + type] = raw[p + 3];
            }
        }
        for (int i = 0; i < MAX_TYPED_RESULTS; ++i) {
            TextView row = rows[i];
            if (i >= groupCount) { row.setVisibility(View.GONE); continue; }
            StringBuilder out = rowBuilders[i];
            out.setLength(0);
            appendHex(out, groupAddress[i]);
            out.append("  ·  ");
            appendAliasNames(out, groupAliasMask[i]);
            int type = firstReadableType(i);
            out.append('\n');
            if (type > 0 && (groupReadableMask[i] & (1 << type)) != 0) {
                appendValue(out, type, groupValueBits[i * TYPE_SLOTS + type]);
                if (Integer.bitCount(groupAliasMask[i]) > 1) {
                    out.append("  [").append(MemoryScanContract.typeName(type)).append(']');
                }
            } else out.append("<stale/unreadable>");
            row.setText(out.toString());
            row.setVisibility(View.VISIBLE);
        }
        resultSummary.setText(resultCount + " typed candidates · " + groupCount
                + " unique addresses shown · " + (remoteBackend ? "remote engine" : "local fallback"));
    }

    private int findOrCreateGroup(long address) {
        for (int i = 0; i < groupCount; ++i) if (groupAddress[i] == address) return i;
        if (groupCount >= MAX_TYPED_RESULTS) return -1;
        int slot = groupCount++;
        groupAddress[slot] = address;
        groupAliasMask[slot] = 0;
        groupReadableMask[slot] = 0;
        return slot;
    }

    private int firstReadableType(int group) {
        for (int type = MemoryScanContract.TYPE_INT8; type <= MemoryScanContract.TYPE_FLOAT64; ++type) {
            if ((groupReadableMask[group] & (1 << type)) != 0) return type;
        }
        for (int type = MemoryScanContract.TYPE_INT8; type <= MemoryScanContract.TYPE_FLOAT64; ++type) {
            if ((groupAliasMask[group] & (1 << type)) != 0) return type;
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
        for (int i = 0; i < MemoryScanContract.TYPE_FLOAT64; ++i) {
            candidate = candidate >= MemoryScanContract.TYPE_FLOAT64
                    ? MemoryScanContract.TYPE_INT8 : candidate + 1;
            if ((aliases & (1 << candidate)) != 0) {
                editType = candidate;
                updateEditPane();
                return;
            }
        }
    }

    private void updateEditPane() {
        scratch.setLength(0);
        appendHex(scratch, groupAddress[editGroup]);
        editAddressText.setText(scratch.toString());
        editTypeButton.setText("Type: " + MemoryScanContract.typeName(editType));
        scratch.setLength(0);
        scratch.append("Current: ");
        if ((groupReadableMask[editGroup] & (1 << editType)) != 0) {
            appendValue(scratch, editType, groupValueBits[editGroup * TYPE_SLOTS + editType]);
        } else scratch.append("<stale/unreadable>");
        editCurrentText.setText(scratch.toString());
    }

    private void applyEdit() {
        if (service == null || editGroup < 0) return;
        if ((groupReadableMask[editGroup] & (1 << editType)) == 0) {
            setNotice("Refusing write: selected alias is stale/unreadable");
            return;
        }
        scratch.setLength(0);
        appendValue(scratch, editType, groupValueBits[editGroup * TYPE_SLOTS + editType]);
        String replacement = replacementInput.getText().toString().trim();
        if (replacement.isEmpty()) { setNotice("Enter a replacement value"); return; }
        try {
            Bundle result = service.editValue(generation, groupAddress[editGroup], editType,
                    scratch.toString(), replacement);
            setNotice(result.getString(MemoryScanContract.KEY_MESSAGE, "Write completed"));
            leaveSubPane();
            refreshResults();
        } catch (RemoteException error) { remoteFailure(error); }
    }

    private void setLiveEnabled(boolean enabled) {
        liveEnabled = enabled;
        if (liveButton != null) liveButton.setText(enabled ? "Live: On" : "Live: Off");
        if (mainHandler != null && liveRefresh != null) mainHandler.removeCallbacks(liveRefresh);
        if (enabled && overlay != null && overlay.getVisibility() == View.VISIBLE) {
            refreshResults();
            mainHandler.postDelayed(liveRefresh, LIVE_REFRESH_MS);
        }
    }

    private void showDiagnostics() {
        mainPane.setVisibility(View.GONE);
        editPane.setVisibility(View.GONE);
        diagnosticsPane.setVisibility(View.VISIBLE);
        diagnosticsText.setText(boundedDiagnostics(diagnostics, 200));
    }

    private void copyDiagnostics() {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Memory Editor diagnostics", diagnosticsText.getText()));
        setNotice("Diagnostics copied");
    }

    private void applyStatus(Bundle status) {
        generation = status.getLong(MemoryScanContract.KEY_GENERATION, generation);
        capability = status.getString(MemoryScanContract.KEY_CAPABILITY, capability);
        managedSelfTest = status.getString(MemoryScanContract.KEY_MANAGED_SELF_TEST, managedSelfTest);
        state = status.getString(MemoryScanContract.KEY_STATE, state);
        resultCount = status.getLong(MemoryScanContract.KEY_RESULT_COUNT, resultCount);
        operationId = status.getLong(MemoryScanContract.KEY_OPERATION_ID, operationId);
        diagnostics = status.getString(MemoryScanContract.KEY_DIAGNOSTICS, diagnostics);
        selectedScope = status.getInt(MemoryScanContract.KEY_SCOPE, selectedScope);
        int type = status.getInt(MemoryScanContract.KEY_VALUE_TYPE, selectedType);
        if (MemoryScanContract.isSearchType(type)) selectedType = type;
        String query = status.getString(MemoryScanContract.KEY_QUERY, "");
        if (!query.isEmpty() && !queryInput.hasFocus()) queryInput.setText(query);
        typeButton.setText("Type: " + MemoryScanContract.typeName(selectedType));
        scopeButton.setText("Scope: " + MemoryScanContract.scopeName(selectedScope));
        updateStatusUi();
        if (MemoryScanContract.STATE_COMPLETE.equals(state) && lastRenderedOperation != operationId) {
            lastRenderedOperation = operationId;
            refreshResults();
        }
    }

    private void updateStatusUi() {
        if (statusText == null) return;
        statusBuilder.setLength(0);
        statusBuilder.append("Backend: ").append(remoteBackend ? ":memory_engine" : ":midlet fallback")
                .append(" · Raw access: ").append(capability)
                .append(" · Gate: ").append(managedSelfTest)
                .append(" · State: ").append(state)
                .append(" · UI warm-up GC Δ: ").append(warmupGcDelta);
        if (generation != 0L) statusBuilder.append(" · gen ").append(generation);
        statusText.setText(statusBuilder.toString());
        boolean ready = ready();
        boolean runningNow = MemoryScanContract.STATE_RUNNING.equals(state);
        newSearchButton.setEnabled(ready && !runningNow);
        nextScanButton.setEnabled(ready && MemoryScanContract.STATE_COMPLETE.equals(state) && resultCount > 0L);
        cancelButton.setEnabled(runningNow);
        refreshButton.setEnabled(MemoryScanContract.STATE_COMPLETE.equals(state) && resultCount > 0L);
        liveButton.setEnabled(MemoryScanContract.STATE_COMPLETE.equals(state) && resultCount > 0L);
    }

    private boolean ready() {
        return generation != 0L && "OK".equals(capability) && "PASS".equals(managedSelfTest);
    }

    private void hideOverlay() {
        if (overlay == null) return;
        setLiveEnabled(false);
        leaveSubPane();
        overlay.setVisibility(View.GONE);
        trigger.setVisibility(View.VISIBLE);
    }

    private void leaveSubPane() {
        if (mainPane == null) return;
        mainPane.setVisibility(View.VISIBLE);
        editPane.setVisibility(View.GONE);
        diagnosticsPane.setVisibility(View.GONE);
    }

    private void clearRows() {
        groupCount = 0;
        if (rows != null) for (TextView row : rows) if (row != null) row.setVisibility(View.GONE);
        if (resultSummary != null) resultSummary.setText("No retained candidates");
    }

    private void applyAdaptiveLayout() {
        if (adaptiveBody == null) return;
        int current = activity.getResources().getConfiguration().orientation;
        if (orientation == current) return;
        orientation = current;
        boolean landscape = current == Configuration.ORIENTATION_LANDSCAPE;
        adaptiveBody.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        if (landscape) {
            controlsPane.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 0.42f));
            resultsPane.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 0.58f));
        } else {
            controlsPane.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.42f));
            resultsPane.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.58f));
        }
    }

    private void applyInsets(WindowInsets insets) {
        if (trigger != null && trigger.getLayoutParams() instanceof FrameLayout.LayoutParams lp) {
            lp.topMargin = insets.getSystemWindowInsetTop() + dp(8);
            lp.rightMargin = insets.getSystemWindowInsetRight() + dp(8);
            trigger.setLayoutParams(lp);
        }
        if (overlay != null) overlay.setPadding(insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom());
    }

    private void remoteFailure(RemoteException error) {
        service = null;
        capability = "DISCONNECTED";
        setNotice("Scanner connection failed: " + error.getClass().getSimpleName());
        updateStatusUi();
    }

    private void setNotice(String value) { if (noticeText != null) noticeText.setText(value == null ? "" : value); }
    private TextView text(String value, float size) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
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
    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }
    private FrameLayout.LayoutParams matchFrame() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private long runtimeGcCount() {
        try {
            String value = Debug.getRuntimeStat(GC_COUNT_STAT);
            return value == null ? -1L : Long.parseLong(value);
        } catch (RuntimeException ignored) { return -1L; }
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
        int lines = 1, index = 0;
        while (index < source.length() && lines <= maxLines) {
            if (source.charAt(index++) == '\n') ++lines;
        }
        return index >= source.length() ? source
                : source.substring(0, index) + "\n… diagnostics truncated at " + maxLines + " lines";
    }
}
