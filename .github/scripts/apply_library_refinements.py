from pathlib import Path

source_path = Path('app/src/main/java/ru/playsoftware/j2meloader/applist/LibraryComposeBridge.kt')
text = source_path.read_text()


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'Expected exactly one match, found {count}: {old[:80]!r}')
    text = text.replace(old, new, 1)


replace_once(
    'import android.content.Context\n',
    'import android.content.Context\nimport android.content.res.Configuration\n',
)
replace_once(
    'import androidx.compose.animation.expandVertically\n',
    'import androidx.compose.animation.expandHorizontally\nimport androidx.compose.animation.expandVertically\n',
)
replace_once(
    'import androidx.compose.animation.shrinkVertically\n',
    'import androidx.compose.animation.shrinkHorizontally\nimport androidx.compose.animation.shrinkVertically\n',
)
replace_once(
    'import androidx.compose.material3.NavigationBarItem\n',
    'import androidx.compose.material3.NavigationBarItem\nimport androidx.compose.material3.NavigationRail\nimport androidx.compose.material3.NavigationRailItem\n',
)
replace_once(
    'import androidx.compose.ui.platform.LocalContext\n',
    'import androidx.compose.ui.platform.LocalConfiguration\nimport androidx.compose.ui.platform.LocalContext\n',
)
replace_once(
    '    val isImeVisible = WindowInsets.isImeVisible\n',
    '    val isImeVisible = WindowInsets.isImeVisible\n'
    '    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE\n',
)

scaffold_start = text.index('    Scaffold(\n', text.index('fun LibraryScreen('))
old_scaffold_head = '    Scaffold(\n        modifier = modifier.fillMaxSize(),\n'
if not text.startswith(old_scaffold_head, scaffold_start):
    raise SystemExit('Scaffold head changed unexpectedly')
scaffold_prefix = '''    Row(modifier = modifier.fillMaxSize()) {
        if (isLandscape && !isImeVisible) {
            AnimatedVisibility(
                visible = showNavigationBar,
                enter = fadeIn(
                    animationSpec = tween(
                        durationMillis = LIBRARY_CHROME_ANIMATION_MILLIS,
                        easing = FastOutSlowInEasing,
                    ),
                ) + expandHorizontally(
                    animationSpec = tween(
                        durationMillis = LIBRARY_CHROME_ANIMATION_MILLIS,
                        easing = FastOutSlowInEasing,
                    ),
                    expandFrom = Alignment.Start,
                ),
                exit = fadeOut(
                    animationSpec = tween(
                        durationMillis = LIBRARY_CHROME_ANIMATION_MILLIS,
                        easing = FastOutSlowInEasing,
                    ),
                ) + shrinkHorizontally(
                    animationSpec = tween(
                        durationMillis = LIBRARY_CHROME_ANIMATION_MILLIS,
                        easing = FastOutSlowInEasing,
                    ),
                    shrinkTowards = Alignment.Start,
                ),
            ) {
                LibraryNavigationRail(
                    selected = destination,
                    onSelected = { selectedDestinationIndex = it.ordinal },
                )
            }
        }

        Scaffold(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
'''
text = text[:scaffold_start] + scaffold_prefix + text[scaffold_start + len(old_scaffold_head):]

bottom_bar_pos = text.index('        bottomBar = {', scaffold_start)
ime_guard_pos = text.index('            if (!isImeVisible) {', bottom_bar_pos)
text = text[:ime_guard_pos] + text[ime_guard_pos:].replace(
    '            if (!isImeVisible) {',
    '            if (!isLandscape && !isImeVisible) {',
    1,
)

dialogs_marker = '\n\n    appActions?.let { app ->'
dialogs_pos = text.index(dialogs_marker, scaffold_start)
text = text[:dialogs_pos] + '\n    }' + text[dialogs_pos:]

nav_marker = '''@Composable
private fun LibraryNavigationBar(
'''
rail_code = '''@Composable
private fun LibraryNavigationRail(
    selected: LibraryDestination,
    onSelected: (LibraryDestination) -> Unit,
) {
    NavigationRail {
        LibraryNavigationRailItem(
            destination = LibraryDestination.Apps,
            selected = selected,
            label = R.string.library_destination_apps,
            icon = R.drawable.ic_apps,
            onSelected = onSelected,
        )
        LibraryNavigationRailItem(
            destination = LibraryDestination.Collections,
            selected = selected,
            label = R.string.library_destination_collections,
            icon = R.drawable.ic_collections,
            onSelected = onSelected,
        )
        LibraryNavigationRailItem(
            destination = LibraryDestination.Options,
            selected = selected,
            label = R.string.library_destination_options,
            icon = R.drawable.ic_options,
            onSelected = onSelected,
        )
    }
}

@Composable
private fun ColumnScope.LibraryNavigationRailItem(
    destination: LibraryDestination,
    selected: LibraryDestination,
    label: Int,
    icon: Int,
    onSelected: (LibraryDestination) -> Unit,
) {
    val labelText = stringResource(label)
    NavigationRailItem(
        selected = destination == selected,
        onClick = { onSelected(destination) },
        icon = {
            Icon(
                painter = painterResource(icon),
                contentDescription = labelText,
            )
        },
        label = { Text(labelText) },
        alwaysShowLabel = false,
    )
}

'''
replace_once(nav_marker, rail_code + nav_marker)

old_snapshot = '''        snapshotFlow {
            if (state.layout == LibraryLayout.List) {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            } else {
                gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
            }
        }.collectLatest { (index, offset) ->
            if (index == 0 && offset == 0) {
                headerOffsetPx.value = 0f
                chromeHysteresis.reset()
                onFabVisibilityChanged(true)
                onNavigationVisibilityChanged(true)
            }
        }
'''
new_snapshot = '''        snapshotFlow {
            if (state.layout == LibraryLayout.List) {
                Triple(
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset,
                    listState.canScrollForward || listState.canScrollBackward,
                )
            } else {
                Triple(
                    gridState.firstVisibleItemIndex,
                    gridState.firstVisibleItemScrollOffset,
                    gridState.canScrollForward || gridState.canScrollBackward,
                )
            }
        }.collectLatest { (index, offset, canScroll) ->
            if (!canScroll || (index == 0 && offset == 0)) {
                headerOffsetPx.value = 0f
                chromeHysteresis.reset()
                onFabVisibilityChanged(true)
                onNavigationVisibilityChanged(true)
            }
        }
'''
replace_once(old_snapshot, new_snapshot)

old_scroll_guard = '''                val delta = available.y
                val height = headerHeightPx.value
                if (delta == 0f || height <= 0) return Offset.Zero

                val fullyHidden = headerOffsetPx.value <= -height.toFloat() + 0.5f
'''
new_scroll_guard = '''                val delta = available.y
                val height = headerHeightPx.value
                if (delta == 0f || height <= 0) return Offset.Zero

                val canScroll = if (state.layout == LibraryLayout.List) {
                    listState.canScrollForward || listState.canScrollBackward
                } else {
                    gridState.canScrollForward || gridState.canScrollBackward
                }
                if (!canScroll) {
                    if (headerOffsetPx.value != 0f || !chromeHysteresis.chromeVisible) {
                        headerOffsetPx.value = 0f
                        chromeHysteresis.reset()
                        onFabVisibilityChanged(true)
                        onNavigationVisibilityChanged(true)
                    }
                    return Offset.Zero
                }

                val fullyHidden = headerOffsetPx.value <= -height.toFloat() + 0.5f
'''
replace_once(old_scroll_guard, new_scroll_guard)

replace_once(
    '''private data class LibraryNormalizedIcon(
    val bitmap: ImageBitmap,
    val filterQuality: FilterQuality,
    val representativeColor: Color?,
    val kind: LibraryIconKind,
    val visualScale: Float,
    val foregroundLuminance: Float,
)
''',
    '''private data class LibraryNormalizedIcon(
    val bitmap: ImageBitmap,
    val filterQuality: FilterQuality,
    val representativeColor: Color?,
    val kind: LibraryIconKind,
    val visualScale: Float,
    val foregroundLuminance: Float,
    val letterboxColor: Color? = null,
)
''',
)
replace_once(
    '''                kind = analysis.kind,
                visualScale = visualScale,
                foregroundLuminance = analysis.foregroundLuminance,
''',
    '''                kind = analysis.kind,
                visualScale = visualScale,
                foregroundLuminance = analysis.foregroundLuminance,
                letterboxColor = if (analysis.kind == LibraryIconKind.Artwork) {
                    normalized.findLibraryLetterboxColor()
                } else {
                    null
                },
''',
)
replace_once(
    '''        // Preserve the existing fallback geometry/color so screenshot baselines stay focused
        // on real J2ME artwork rather than the test-only missing-icon path.
        representativeColor = if (normalizeSquareIcon) fallback.findAverageVisibleColor() else null,
        kind = LibraryIconKind.Fallback,
        visualScale = 1f,
''',
    '''        representativeColor = if (normalizeSquareIcon) fallback.findAverageVisibleColor() else null,
        kind = LibraryIconKind.Fallback,
        visualScale = LIBRARY_FALLBACK_VISUAL_SCALE,
''',
)

helper_marker = '''private fun Bitmap.findAverageVisibleColor(): Color? {
'''
helper_code = '''private fun Bitmap.findLibraryLetterboxColor(): Color? {
    if (width <= 0 || height <= 0) return null
    val aspectRatio = width.toFloat() / height.toFloat()
    if (kotlin.math.abs(aspectRatio - 1f) < LIBRARY_LETTERBOX_MIN_ASPECT_DELTA) return null
    val edgeColor = findUniformCornerBackgroundColor() ?: return null
    return Color(
        red = AndroidColor.red(edgeColor) / 255f,
        green = AndroidColor.green(edgeColor) / 255f,
        blue = AndroidColor.blue(edgeColor) / 255f,
        alpha = 1f,
    )
}

'''
replace_once(helper_marker, helper_code + helper_marker)
replace_once(
    'private const val LIBRARY_FOREGROUND_SCALE_RANGE = 0.18f\n',
    'private const val LIBRARY_FOREGROUND_SCALE_RANGE = 0.20f\n'
    'private const val LIBRARY_FALLBACK_VISUAL_SCALE = 0.86f\n'
    'private const val LIBRARY_FALLBACK_TINT_SCALE = 0.60f\n'
    'private const val LIBRARY_LETTERBOX_MIN_ASPECT_DELTA = 0.18f\n',
)

old_container = '''        val containerColor = if (
            iconRatio == LibraryIconRatio.Square &&
            icon?.kind != LibraryIconKind.Artwork
        ) {
            icon?.representativeColor?.let { representativeColor ->
                adaptiveLibrarySlotColor(
                    base = baseContainerColor,
                    accent = representativeColor,
                    foregroundLuminance = icon.foregroundLuminance,
                    tintStrength = if (icon.kind == LibraryIconKind.Fallback) {
                        1f
                    } else {
                        LIBRARY_FOREGROUND_TINT_SCALE
                    },
                )
            } ?: baseContainerColor
        } else {
            baseContainerColor
        }
'''
new_container = '''        val containerColor = when {
            iconRatio == LibraryIconRatio.Square &&
                icon?.kind == LibraryIconKind.Artwork &&
                icon.letterboxColor != null -> icon.letterboxColor
            iconRatio == LibraryIconRatio.Square &&
                icon?.kind != LibraryIconKind.Artwork -> {
                icon?.representativeColor?.let { representativeColor ->
                    adaptiveLibrarySlotColor(
                        base = baseContainerColor,
                        accent = representativeColor,
                        foregroundLuminance = icon.foregroundLuminance,
                        tintStrength = if (icon.kind == LibraryIconKind.Fallback) {
                            LIBRARY_FALLBACK_TINT_SCALE
                        } else {
                            LIBRARY_FOREGROUND_TINT_SCALE
                        },
                    )
                } ?: baseContainerColor
            }
            else -> baseContainerColor
        }
'''
replace_once(old_container, new_container)
source_path.write_text(text)

test_path = Path('app/src/androidTest/java/ru/playsoftware/j2meloader/applist/LibraryComposeTest.kt')
test = test_path.read_text()
marker = '''    @Test
    fun sortMenuExplainsCurrentDirection() {
'''
test_code = '''    @Test
    fun shortLibraryKeepsChromeVisibleWhenContentCannotScroll() {
        val actions = RecordingLibraryActions()
        setLibraryContent(
            state = LibraryUiState(
                loading = false,
                apps = listOf(
                    LibraryAppUiItem(7, "Demo MIDlet", "Example Vendor", "1.0", null, true),
                ),
            ),
            actions = actions,
        )

        composeRule.onNodeWithText("Demo MIDlet").performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Install").assertIsDisplayed()
        composeRule.onNodeWithText("Apps").assertIsDisplayed()
        composeRule.onNodeWithText("JL-Mod Plus Debug").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("App Sort Order").assertIsDisplayed()
    }

'''
if test.count(marker) != 1:
    raise SystemExit('Test insertion marker changed unexpectedly')
test = test.replace(marker, test_code + marker, 1)
test_path.write_text(test)
