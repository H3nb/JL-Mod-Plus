/* Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0. */
package io.github.h3nb.jlmodplus.memory

import android.os.Build
import android.view.RoundedCorner
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.waterfall
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
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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

/** Dialog controls stay inside visible system bars, cutouts and waterfall edges. */
@Composable
internal fun memoryEditorSafeWindowInsets(): WindowInsets = WindowInsets.safeDrawing.union(WindowInsets.waterfall)

/** Data/list content can use the header's cutout strip and the hidden navigation edge. */
@Composable
internal fun memoryEditorWorkspaceInsets(): WindowInsets = WindowInsets.safeDrawing.union(WindowInsets.waterfall)

/**
 * In a stacked header, the title and close affordance occupy the two edges.
 * A centered punch-hole should not push both below the full-width cutout inset.
 * If a cutout reaches either control lane, retain the safe top inset instead.
 */
@Composable
internal fun memoryEditorTitleTopPadding(): Dp {
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    val view = LocalView.current
    val cutout = ViewCompat.getRootWindowInsets(view)?.displayCutout
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    val screenWidth = view.width.takeIf { it > 0 }
        ?: (LocalConfiguration.current.screenWidthDp * density.density).toInt()
    val conflictsWithEdgeControls = screenWidth <= 0 || cutout?.boundingRects.orEmpty().any { bounds ->
        val left = bounds.left - location[0]
        val right = bounds.right - location[0]
        left < screenWidth * 0.45f || right > screenWidth * 0.80f
    }
    return with(density) {
        (if (conflictsWithEdgeControls) WindowInsets.safeDrawing.getTop(density) else statusTop).toDp()
    }
}

/** Round bottom corners require moving buttons inward, not raising the entire dock. */
@Composable
internal fun memoryEditorBottomCornerPadding(): PaddingValues {
    val density = LocalDensity.current
    val view = LocalView.current
    val insets = if (Build.VERSION.SDK_INT >= 31) view.rootWindowInsets else null
    val leftRadius = insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
    val rightRadius = insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0
    return with(density) {
        PaddingValues.Absolute(
            left = maxOf(8.dp, leftRadius.toDp()),
            right = maxOf(8.dp, rightRadius.toDp()),
            top = 0.dp,
            bottom = 0.dp,
        )
    }
}

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
    centerContent: (@Composable () -> Unit)? = null,
) {
    // Workspace controls need their own theme surface over the live game.
    // Dialogs keep the footer transparent so Peek does not introduce a second panel.
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (overlay.peeking || centerContent == null) Color.Transparent
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (centerContent != null) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (leadingContent != null && !overlay.peeking) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                            leadingContent()
                        }
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (!overlay.peeking) centerContent()
                }
            } else {
                if (leadingContent != null && !overlay.peeking) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                        leadingContent()
                    }
                }
                Spacer(Modifier.weight(1f))
            }
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
                Modifier.size(windowWidth, windowHeight).windowInsetsPadding(memoryEditorSafeWindowInsets())
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .memoryEditorPeekKeys(overlay),
            ) {
                Box(Modifier.widthIn(max = 960.dp).fillMaxSize().align(Alignment.Center)) {
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxSize().testTag("memory-editor-dialog-viewport"),
                    ) {
                        Box(Modifier.matchParentSize().pointerInput(onDismissRequest, overlay.peeking) {
                            detectTapGestures(onTap = { if (!overlay.peeking) onDismissRequest() })
                        })
                        // On wide windows Peek fits entirely beside the form, so dialogs
                        // can use the full height and remain centered on the screen.
                        // Otherwise reserve only its actual 48 dp bottom touch target.
                        val peekGutter = 48.dp + 8.dp +
                            memoryEditorBottomCornerPadding()
                                .calculateRightPadding(LocalLayoutDirection.current)
                        val sidePeek = maxWidth - peekGutter * 2 >= minOf(maxDialogWidth, 600.dp)
                        val dialogMaxWidth = if (sidePeek) {
                            minOf(maxDialogWidth, maxWidth - peekGutter * 2)
                        } else maxDialogWidth
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxSize().then(
                                if (sidePeek) Modifier else Modifier.padding(bottom = 48.dp),
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            val layout = adaptiveDialogLayout(maxWidth, maxHeight, maxDialogWidth = dialogMaxWidth)
                                .copy(maxHeight = (maxHeight - 8.dp).coerceAtLeast(0.dp))
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
                    }
                    MemoryEditorPeekShield(overlay)
                    if (overlay.layers.lastOrNull() == layer) {
                        MemoryEditorPeekDock(
                            overlay,
                            Modifier.align(Alignment.BottomCenter)
                                .padding(memoryEditorBottomCornerPadding()),
                        )
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

