/* Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0. */
package io.github.h3nb.jlmodplus.memory

import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.AdaptiveDialogContent
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout
import io.github.h3nb.jlmodplus.ui.availableWindowHeightDp
import io.github.h3nb.jlmodplus.ui.availableWindowWidthDp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.os.ConfigurationCompat
import java.text.NumberFormat
import java.util.Locale

/** One visibility owner for the workspace and every editor-owned dialog/menu window. */
@Stable
internal class MemoryEditorOverlayState(
    initialOpacity: Float = DEFAULT_OPACITY,
    private val persistOpacity: (Float) -> Unit = {},
) {
    var peeking by mutableStateOf(false)
    var opacity by mutableFloatStateOf(initialOpacity.coerceIn(MIN_OPACITY, 1f))
    val layers = mutableStateListOf<Any>()

    fun saveOpacity() = persistOpacity(opacity)

    companion object {
        const val DEFAULT_OPACITY = 0.97f
        const val MIN_OPACITY = 0f
    }
}

internal val LocalMemoryEditorOverlay = compositionLocalOf<MemoryEditorOverlayState?> { null }

@Composable
internal fun MemoryEditorOverlayScope(
    state: MemoryEditorOverlayState? = null,
    content: @Composable (MemoryEditorOverlayState) -> Unit,
) {
    val inherited = LocalMemoryEditorOverlay.current
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    val overlay = state ?: inherited ?: remember(context, preview) {
        // Owned only by the memory-engine process; avoid another cache of the app's
        // cross-process configuration. Save on release, never on each slider movement.
        val preferences = if (preview) null else context.getSharedPreferences("memory_editor_ui", 0)
        MemoryEditorOverlayState(
            initialOpacity = preferences?.getFloat("panel_opacity", MemoryEditorOverlayState.DEFAULT_OPACITY)
                ?: MemoryEditorOverlayState.DEFAULT_OPACITY,
            persistOpacity = { preferences?.edit()?.putFloat("panel_opacity", it)?.apply() },
        )
    }
    CompositionLocalProvider(LocalMemoryEditorOverlay provides overlay) { content(overlay) }
}

/** Consume Back before a hidden text field can use it to change its selection. */
internal fun Modifier.memoryEditorPeekKeys(overlay: MemoryEditorOverlayState): Modifier =
    onPreviewKeyEvent { event ->
        if (!overlay.peeking) false else {
            // Consume both halves; restoring on down would let up dismiss the restored form.
            if (event.key == Key.Back && event.type == KeyEventType.KeyUp) overlay.peeking = false
            true
        }
    }

/** Block hidden actions while retaining the mounted form, focus, selection and scroll. */
@Composable
internal fun BoxScope.MemoryEditorPeekShield(overlay: MemoryEditorOverlayState) {
    if (overlay.peeking) {
        Box(Modifier.matchParentSize().pointerInput(Unit) {
            awaitEachGesture {
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        })
    }
}

/** Same safe-window coordinates in the workspace and every modal, outside all scroll areas. */
@Composable
internal fun MemoryEditorPeekDock(
    overlay: MemoryEditorOverlayState,
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (leadingContent != null && !overlay.peeking) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                leadingContent()
            }
        }
        Spacer(Modifier.weight(1f))
        val description = stringResource(
            if (overlay.peeking) R.string.memory_editor_peek_underlay_active
            else R.string.memory_editor_peek_underlay,
        )
        Box(
            modifier = Modifier.size(48.dp)
                .testTag("memory-editor-peek")
                .semantics {
                    contentDescription = description
                    role = Role.Button
                    onClick {
                        overlay.peeking = !overlay.peeking
                        true
                    }
                }
                .pointerInput(overlay) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        down.consume()
                        overlay.peeking = true
                        try {
                            // Keep the hold while the finger drifts instead of flashing the panels back.
                            do {
                                val pointer = awaitPointerEvent(PointerEventPass.Initial)
                                    .changes.firstOrNull { it.id == down.id }
                                pointer?.consume()
                            } while (pointer?.pressed == true)
                        } finally {
                            overlay.peeking = false
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (overlay.peeking) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Full safe-window shell leaves Peek reachable even above nested editor dialogs. */
@Composable
internal fun MemoryEditorDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxDialogWidth: Dp = 720.dp,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    textScrollable: Boolean = true,
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    dismissButtonBelowWrappedActions: Boolean = false,
) {
    MemoryEditorOverlayScope { overlay ->
        val layer = remember { Any() }
        DisposableEffect(overlay, layer) {
            overlay.layers.add(layer)
            onDispose {
                overlay.layers.remove(layer)
                overlay.peeking = false
            }
        }
        val windowWidth = availableWindowWidthDp()
        val windowHeight = availableWindowHeightDp()
        Dialog(
            onDismissRequest = { if (overlay.peeking) overlay.peeking = false else onDismissRequest() },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            MemoryEditorWindowDimming(overlay.peeking)
            Box(
                Modifier.size(windowWidth, windowHeight).windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .memoryEditorPeekKeys(overlay),
            ) {
                Box(Modifier.widthIn(max = 960.dp).fillMaxSize().align(Alignment.Center)) {
                    Column(Modifier.fillMaxSize()) {
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            val layout = adaptiveDialogLayout(maxWidth, maxHeight, maxDialogWidth = maxDialogWidth).copy(maxHeight = maxHeight)
                            Box(Modifier.matchParentSize().pointerInput(onDismissRequest, overlay.peeking) {
                                detectTapGestures(onTap = { if (!overlay.peeking) onDismissRequest() })
                            })
                            Surface(
                                modifier = layout.modifier.then(modifier)
                                    .alpha(if (overlay.peeking) 0f else 1f)
                                    .testTag("adaptive-dialog-surface")
                                    .then(if (overlay.peeking) Modifier.clearAndSetSemantics {} else Modifier)
                                    .pointerInput(Unit) { detectTapGestures {} },
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ) {
                                AdaptiveDialogContent(
                                    compact = layout.maxHeight < 320.dp,
                                    title = title, text = text, textScrollable = textScrollable,
                                    confirmButton = confirmButton, dismissButton = dismissButton,
                                    dismissButtonBelowWrappedActions = dismissButtonBelowWrappedActions,
                                )
                            }
                        }
                        Spacer(Modifier.height(48.dp))
                    }
                    MemoryEditorPeekShield(overlay)
                    if (overlay.layers.lastOrNull() == layer) {
                        MemoryEditorPeekDock(overlay, Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }
}

@Composable
internal fun MemoryEditorOpacityDialog(overlay: MemoryEditorOverlayState, onDismiss: () -> Unit) {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT
    val percentFormat = remember(locale) { NumberFormat.getPercentInstance(locale) }
    val opacityLabel = stringResource(R.string.memory_editor_panel_opacity)
    val close = { overlay.saveOpacity(); onDismiss() }
    MemoryEditorDialog(
        onDismissRequest = close,
        maxDialogWidth = 400.dp,
        title = { Text(opacityLabel) },
        text = {
            Column {
                Text(stringResource(R.string.memory_editor_panel_opacity_help))
                Text(percentFormat.format(overlay.opacity))
                Slider(
                    value = overlay.opacity,
                    onValueChange = { overlay.opacity = it },
                    onValueChangeFinished = overlay::saveOpacity,
                    valueRange = MemoryEditorOverlayState.MIN_OPACITY..1f,
                    modifier = Modifier.semantics {
                        contentDescription = opacityLabel
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(android.R.string.ok)) } },
    )
}

private data class MemoryEditorWindowBaseline(
    val flags: Int,
    val dimAmount: Float,
)

/** Keep dialog-window presentation consistent with the editor host, including during Peek. */
@Composable
private fun MemoryEditorWindowDimming(
    peeking: Boolean,
) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    val baseline = remember(view, window) {
        window?.attributes?.let { attributes ->
            MemoryEditorWindowBaseline(
                flags = attributes.flags,
                dimAmount = attributes.dimAmount,
            )
        }
    }

    DisposableEffect(view, window, baseline) {
        if (window == null) {
            onDispose {}
        } else {
            // Compose Dialog has its own focused Android window. The host Activity's
            // navigation-bar policy does not propagate when that window gains focus.
            val decor = window.decorView
            val observer = decor.viewTreeObserver
            val hideNavigation = {
                WindowInsetsControllerCompat(window, decor).apply {
                    systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.navigationBars())
                }
            }
            val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                if (hasFocus) hideNavigation()
            }
            observer.addOnWindowFocusChangeListener(listener)
            hideNavigation()
            onDispose {
                if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
                if (baseline != null) applyMemoryEditorWindowState(window, baseline, peeking = false)
            }
        }
    }
    SideEffect {
        if (window != null && baseline != null) {
            applyMemoryEditorWindowState(window, baseline, peeking)
        }
    }
}

private fun applyMemoryEditorWindowState(
    window: android.view.Window,
    baseline: MemoryEditorWindowBaseline,
    peeking: Boolean,
) {
    val targetDimAmount = if (peeking) 0f else baseline.dimAmount
    val targetDimEnabled = !peeking &&
        baseline.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0
    val currentFlags = window.attributes.flags
    val currentDimEnabled = currentFlags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0
    if (currentDimEnabled != targetDimEnabled) {
        if (targetDimEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }
    val attributes = window.attributes
    if (attributes.dimAmount != targetDimAmount) {
        window.attributes = attributes.apply {
            dimAmount = targetDimAmount
        }
    }
}

