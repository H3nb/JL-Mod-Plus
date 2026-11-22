/*
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

package io.github.h3nb.jlmodplus.applist

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.get
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.h3nb.jlmodplus.BuildConfig
import io.github.h3nb.jlmodplus.MainActivity
import androidx.lifecycle.Lifecycle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.librarydb.LibraryQuickView
import io.github.h3nb.jlmodplus.updates.UpdateCandidate
import io.github.h3nb.jlmodplus.updates.UpdateRepository
import io.github.h3nb.jlmodplus.updates.UpdateStatus
import io.github.h3nb.jlmodplus.ui.GlassSystemBarScrim
import io.github.h3nb.jlmodplus.ui.InlineSearchField
import io.github.h3nb.jlmodplus.ui.availableWindowWidthDp
import io.github.h3nb.jlmodplus.ui.availableWindowHeightDp
import io.github.h3nb.jlmodplus.ui.PAGE_TRAVEL_DP
import io.github.h3nb.jlmodplus.ui.pageEnter
import io.github.h3nb.jlmodplus.ui.pageExit
import io.github.h3nb.jlmodplus.ui.clearNavigationFocusOnTouch
import io.github.h3nb.jlmodplus.ui.showNavigationFocusForKey
import kotlin.math.roundToInt
import kotlin.math.abs

enum class LibraryControllerCommand {
    MoveUp,
    MoveDown,
    MoveLeft,
    MoveRight,
    Activate,
    OpenActions,
    Back,
    NextTab,
    PreviousTab,

    ;

}

data class LibraryControllerEvent(
    val sequence: Long,
    val command: LibraryControllerCommand,
)

fun interface LibraryControllerKeyEventHandler {
    fun onControllerKeyEvent(event: KeyEvent): Boolean
}

data class LibraryAppUiItem(
    val id: Int,
    val title: String,
    val author: String,
    val version: String,
    val iconPath: String?,
    val canReinstall: Boolean,
    val description: String = "",
    val iconRevision: Long = 0L,
    val favorite: Boolean = false,
    val sourceTitle: String = title,
    val sourceAuthor: String = author,
    val sourceVersion: String = version,
    val sourceDescription: String = description,
    val playCount: Long = 0L,
    val totalPlayTimeMs: Long = 0L,
    /** Stable Room identity; [id] remains a transient Compose/host bridge id. */
    val databaseId: Long = id.toLong(),
    val defaultDescription: String = description,
    val portraitIconPath: String? = null,
    val hasCustomIcon: Boolean = false,
    val addedAt: Long? = null,
    val lastPlayedAt: Long? = null,
)

private data class MetadataRestoreRequest(
    val anchor: LibraryScrollAnchor,
    val databaseId: Long,
    val title: String,
    val author: String,
    val description: String,
    /**
     * The raw LazyList/LazyGrid viewport at the moment the editor opened. The stable-id anchor
     * is preferable when rows are reordered, but this snapshot is the exact visual fallback for
     * a metadata save/back that only changes layout/insets.
     */
    val viewport: LibraryViewportSnapshot? = null,
)

internal data class LibraryViewportSnapshot(
    val layout: LibraryLayout,
    val firstVisibleItemIndex: Int,
    val firstVisibleItemOffsetPx: Int,
)

private data class LibraryProjectionKey(
    val revision: Long,
    val apps: List<LibraryAppUiItem>,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
    controllerEvents: Flow<LibraryControllerEvent>? = null,
    controllerKeyEventHandler: LibraryControllerKeyEventHandler? = null,
    onControllerBackAvailabilityChanged: (Boolean) -> Unit = {},
    initialSelectionState: LibrarySelectionState = LibrarySelectionState(),
    noticeHost: @Composable () -> Unit = {},
) {
    var navigationState by rememberSaveable(stateSaver = LibraryNavigationState.Saver) {
        mutableStateOf(LibraryNavigationState())
    }
    val destination = navigationState.destination
    val selectDestination: (LibraryDestinationKey) -> Unit = { section ->
        navigationState = navigationState.copy(destination = section)
    }
    val context = LocalContext.current.applicationContext
    // Screenshot previews have no persistent preferences or active network stack.
    val isInspection = LocalInspectionMode.current
    val updateRepository = if (isInspection) null else remember(context) {
        UpdateRepository.get(context)
    }
    val hostActivity = LocalActivity.current as? MainActivity
    val hostLifecycle = hostActivity?.lifecycle
    // FragmentManager is not Compose state; mirror only the dialog visibility via its
    // lifecycle callbacks, so dismissal immediately re-evaluates a deferred update.
    val fragmentManager = hostActivity?.supportFragmentManager
    val fragmentDialogVisible by produceState(initialValue = false, fragmentManager) {
        if (fragmentManager == null) return@produceState
        fun updateVisibility() {
            value = fragmentManager.fragments.any { fragment ->
                fragment is DialogFragment &&
                    fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
        }
        val observer = object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                if (f is DialogFragment) updateVisibility()
            }
            override fun onFragmentStopped(fm: FragmentManager, f: Fragment) {
                if (f is DialogFragment) updateVisibility()
            }
        }
        fragmentManager.registerFragmentLifecycleCallbacks(observer, false)
        updateVisibility()
        awaitDispose { fragmentManager.unregisterFragmentLifecycleCallbacks(observer) }
    }
    val hostResumed = hostLifecycle?.currentStateFlow?.collectAsState()?.value
        ?.isAtLeast(Lifecycle.State.RESUMED) == true
    LaunchedEffect(hostResumed, updateRepository?.automaticChecks) {
        val repository = updateRepository ?: return@LaunchedEffect
        if (BuildConfig.DEBUG || !hostResumed || !repository.automaticChecks) return@LaunchedEffect
        while (true) {
            repository.check(manual = false)
            delay(repository.nextAutomaticDelayMillis())
        }
    }

    var appActions by remember { mutableStateOf<LibraryAppUiItem?>(null) }
    var detailsTarget by remember { mutableStateOf<LibraryAppUiItem?>(null) }
    var renameTarget by remember { mutableStateOf<LibraryAppUiItem?>(null) }
    var metadataTarget by remember { mutableStateOf<LibraryAppUiItem?>(null) }
    var metadataBackRequest by remember { mutableIntStateOf(0) }
    var metadataCapturedAnchor by remember { mutableStateOf<LibraryScrollAnchor?>(null) }
    var metadataCapturedViewport by remember { mutableStateOf<LibraryViewportSnapshot?>(null) }
    var metadataRestoreRequest by remember { mutableStateOf<MetadataRestoreRequest?>(null) }
    // The metadata editor is an overlay. Keep the underlying list viewport and scaffold chrome
    // fixed while its focus/IME state changes, otherwise closing the editor changes the list
    // padding before the saved anchor can be restored.
    var metadataViewportLocked by remember { mutableStateOf(false) }
    var metadataImeWasVisible by remember { mutableStateOf(false) }
    var metadataRestoreAnchorKey by remember { mutableIntStateOf(0) }
    var appActionsCollectionId by remember { mutableStateOf<Long?>(null) }
    var deleteTarget by remember { mutableStateOf<LibraryAppUiItem?>(null) }
    var installSourceMenuVisible by remember { mutableStateOf(false) }
    var notificationsPageVisible by rememberSaveable { mutableStateOf(false) }
    // Keep full-screen navigation blocking until the outgoing page finishes fading away.
    val notificationsPageMotion = remember { MutableTransitionState(false) }
    notificationsPageMotion.targetState = notificationsPageVisible
    val auxiliaryPageOnScreen =
        notificationsPageMotion.currentState || notificationsPageMotion.targetState
    var updatePrompt by remember { mutableStateOf<UpdateCandidate?>(null) }
    var sessionDismissedUpdate by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingBulkDeleteIds by remember { mutableStateOf<Set<Long>?>(null) }
    var selectionState by rememberSaveable(stateSaver = LibrarySelectionState.Saver) {
        mutableStateOf(initialSelectionState)
    }
    var controllerFocusVisible by rememberSaveable { mutableStateOf(false) }
    var controllerFocusedAppId by rememberSaveable { mutableStateOf<Long?>(null) }
    var controllerFocusedAppIndex by rememberSaveable { mutableIntStateOf(0) }
    val appsViewport = rememberLibraryViewportState(state.libraryScope)
    // Search has a temporary query; the Browse projection must remain independent.
    LaunchedEffect(state.libraryScope, state.appliedFilter) {
        if (state.appliedFilter.isNotEmpty()) actions.onSearch("")
    }
    val collectionsViewport = rememberLibraryViewportState(state.libraryScope)
    val collectionViewport = rememberLibraryViewportState(state.libraryScope, navigationState.selectedCollectionId)
    val collectionPickerViewport = rememberLibraryViewportState(state.libraryScope, navigationState.selectedCollectionId)
    val appsListState = appsViewport.listState
    val appsGridState = appsViewport.gridState
    val showInstallFab = if (navigationState.selectedCollectionId == null) {
        appsViewport.chromeVisible
    } else collectionViewport.chromeVisible
    // Search is restored only within the Library context that owns its query.
    var searchOpen by rememberSaveable(
        state.libraryScope, destination, navigationState.selectedCollectionId,
    ) { mutableStateOf(false) }
    var searchQuery by rememberSaveable(
        state.libraryScope, destination, navigationState.selectedCollectionId,
    ) { mutableStateOf("") }
    val searchKeyboard = LocalSoftwareKeyboardController.current
    val isImeVisible = WindowInsets.isImeVisible
    val scaffoldInsets = WindowInsets.safeDrawing
        .exclude(WindowInsets.ime)
        .only(WindowInsetsSides.Bottom)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val collectionsHost = actions as? LibraryCollectionsHost
    LaunchedEffect(searchOpen, destination, state.libraryScope, state.databaseControlsReady,
        state.projectionRevision) {
        if (searchOpen && destination == LibraryDestinationKey.Apps && state.databaseControlsReady) {
            collectionsHost?.onPrepareSearchApps()
        }
    }
    val collectionDialogState = collectionsHost?.collectionsStore()?.state?.collectAsState()?.value
    val collectionDialogVisible = collectionDialogState?.addTarget != null ||
        collectionDialogState?.bulkAddTargetAppIds != null
    val bulkActions = actions as? LibraryBulkActions
    val selectedCollectionMatchesLibrary =
        navigationState.selectedCollectionId != null &&
            (
                state.libraryScope.isEmpty() ||
                    navigationState.selectedCollectionScope == state.libraryScope
                )
    val activeCollectionId = navigationState.selectedCollectionId.takeIf {
        selectedCollectionMatchesLibrary
    }
    val activeCollection = collectionDialogState?.collections?.firstOrNull {
        it.id == activeCollectionId
    }
    val activeMembers = collectionDialogState?.members?.takeIf {
        it.collectionId == activeCollectionId
    }
    val sortedMembers by produceState<List<LibraryAppUiItem>?>(
        initialValue = null, activeCollectionId, activeMembers?.revision, state.sortVariant,
    ) {
        if (activeCollectionId == null || activeMembers == null) return@produceState
        value = withContext(Dispatchers.Default) {
            val job = currentCoroutineContext()
            projectCollectionApps(activeMembers.members, "", state.sortVariant) {
                job.ensureActive()
            }
        }
    }
    val projectedMemberIds = remember(sortedMembers) {
        sortedMembers.orEmpty().map(LibraryAppUiItem::databaseId)
    }
    val displayedState = if (activeCollectionId == null) state else state.copy(
        apps = sortedMembers.orEmpty(),
        projectedAppIds = projectedMemberIds,
        availableAppIds = activeMembers?.memberIds.orEmpty(),
        quickView = LibraryQuickView.All,
        appliedFilter = "",
        loading = state.loading || sortedMembers == null,
        projectionRevision = activeMembers?.revision ?: state.projectionRevision,
    )
    LaunchedEffect(
        navigationState.selectedCollectionId,
        selectedCollectionMatchesLibrary,
        activeCollection?.id,
        activeMembers?.collectionId,
        collectionDialogState?.ready,
        state.libraryScope,
    ) {
        val selectedId = navigationState.selectedCollectionId
        when {
            selectedId != null && !selectedCollectionMatchesLibrary -> {
                navigationState = navigationState.copy(
                    selectedCollectionId = null,
                    selectedCollectionScope = null,
                    collectionManageApps = false,
                )
                collectionsHost?.onDismissCollectionMembers()
            }
            selectedId != null && collectionDialogState?.ready == true &&
                activeCollection == null -> {
                navigationState = navigationState.copy(
                    selectedCollectionId = null,
                    selectedCollectionScope = null,
                    collectionManageApps = false,
                )
                collectionsHost?.onDismissCollectionMembers()
            }
            activeCollection != null && activeMembers == null -> {
                collectionsHost?.onOpenCollection(activeCollection.id)
            }
        }
    }
    val selectionMatchesDestination = when (destination) {
        LibraryDestinationKey.Apps -> selectionState.collectionId == activeCollectionId
        LibraryDestinationKey.Collections -> false
    }
    val collectionManageAppsActive =
        destination == LibraryDestinationKey.Apps &&
            activeCollection != null &&
            navigationState.collectionManageApps
    val selectionActiveHere =
        selectionState.isActive && selectionMatchesDestination && !collectionManageAppsActive && !searchOpen
    val currentNavigationState by rememberUpdatedState(navigationState)
    val currentControllerState by rememberUpdatedState(state)
    val currentControllerDestination by rememberUpdatedState(destination)
    val currentControllerAppActions by rememberUpdatedState(appActions)
    val currentControllerRenameTarget by rememberUpdatedState(renameTarget)
    val currentControllerMetadataTarget by rememberUpdatedState(metadataTarget)
    val currentControllerDetailsTarget by rememberUpdatedState(detailsTarget)
    val currentControllerDeleteTarget by rememberUpdatedState(deleteTarget)
    val currentControllerNotificationsPageVisible by rememberUpdatedState(
        notificationsPageMotion.currentState || notificationsPageMotion.targetState,
    )
    val currentControllerUpdatePrompt by rememberUpdatedState(updatePrompt)
    val currentControllerBulkDeleteIds by rememberUpdatedState(pendingBulkDeleteIds)
    val currentControllerSelectionActive by rememberUpdatedState(selectionActiveHere)
    val currentControllerCollectionDialogVisible by rememberUpdatedState(collectionDialogVisible)
    val currentControllerInstallMenuVisible by rememberUpdatedState(installSourceMenuVisible)
    val currentControllerOtherModalVisible by rememberUpdatedState(
        renameTarget != null || metadataTarget != null || detailsTarget != null ||
            deleteTarget != null || pendingBulkDeleteIds != null ||
            auxiliaryPageOnScreen || collectionDialogVisible || installSourceMenuVisible ||
            updatePrompt != null || searchOpen,
    )
    val currentControllerSearchOpen by rememberUpdatedState(searchOpen)
    val currentControllerCollectionId by rememberUpdatedState(activeCollectionId)
    val currentControllerImeVisible by rememberUpdatedState(isImeVisible)

    LaunchedEffect(selectionState, activeCollectionId, activeMembers?.revision, state.generation) {
        if (activeCollectionId == null || activeMembers == null ||
            !selectionState.isActive || selectionState.collectionId != activeCollectionId
        ) return@LaunchedEffect
        val retained = selectionState.retainAvailable(
            state.generation, activeMembers.memberIds, collectionId = activeCollectionId,
        )
        if (retained != selectionState) selectionState = retained
    }
    LaunchedEffect(state.generation, state.databaseControlsReady) {
        if (!state.databaseControlsReady) return@LaunchedEffect
        val retained = selectionState.retainGeneration(state.generation)
        if (retained != selectionState) selectionState = retained
    }
    LaunchedEffect(
        state.generation,
        state.availableAppIds,
        state.databaseControlsReady,
        selectionState.isActive,
        selectionState.collectionId,
    ) {
        if (
            !state.databaseControlsReady ||
            !selectionState.isActive ||
            selectionState.collectionId != null
        ) {
            return@LaunchedEffect
        }
        val retained = selectionState.retainAvailable(
            state.generation,
            state.availableAppIds,
        )
        if (retained != selectionState) selectionState = retained
    }

    val projectionKey = remember(state) {
        LibraryProjectionKey(state.projectionRevision, state.apps)
    }
    LaunchedEffect(
        state.generation,
        projectionKey,
        state.appliedFilter,
        state.sortVariant,
        state.layout,
    ) {
        if (state.apps.isEmpty()) return@LaunchedEffect
        val focus = reconcileLibraryControllerFocus(
            state.projectedAppIds,
            controllerFocusedAppId,
            controllerFocusedAppIndex,
        )
        controllerFocusedAppIndex = focus.index
        controllerFocusedAppId = focus.databaseId
    }

    suspend fun moveControllerFocus(command: LibraryControllerCommand) {
        val currentState = currentControllerState
        if (currentControllerDestination != LibraryDestinationKey.Apps ||
            currentControllerCollectionId != null ||
            currentState.apps.isEmpty() || currentControllerSelectionActive
        ) return
        val apps = currentState.apps
        val currentFocus = reconcileLibraryControllerFocus(
            currentState.projectedAppIds,
            controllerFocusedAppId,
            controllerFocusedAppIndex,
        )
        val currentIndex = currentFocus.index
        val columns = if (currentState.layout == LibraryLayout.Grid) {
            val visible = appsGridState.layoutInfo.visibleItemsInfo.filter { it.index > 0 }
            val first = visible.firstOrNull()
            if (first == null) {
                1
            } else {
                visible.count { abs(it.offset.y - first.offset.y) <= 1 }.coerceAtLeast(1)
            }
        } else {
            1
        }
        val nextIndex = moveLibraryControllerFocusIndex(
            currentIndex = currentIndex,
            itemCount = apps.size,
            layout = currentState.layout,
            command = command,
            columnCount = columns,
        )
        controllerFocusedAppIndex = nextIndex
        controllerFocusedAppId = currentState.projectedAppIds[nextIndex]
        // A controller command takes over from the preceding touch gesture or fling.
        if (currentState.layout == LibraryLayout.List) {
            appsListState.stopScroll(MutatePriority.UserInput)
        } else {
            appsGridState.stopScroll(MutatePriority.UserInput)
        }
        // The list placeholder and the overlay both follow the *visible* header height.
        // Reserving the original full height would reintroduce a controller-only empty gap.
        val visibleHeaderHeight = (
            appsViewport.headerHeightPx.intValue + appsViewport.headerOffsetPx.floatValue
        ).coerceAtLeast(0f).roundToInt()
        val focusOffset = -visibleHeaderHeight
        val targetIndex = nextIndex + 1
        // Touch can leave the retained focus far outside the viewport. Reveal it immediately
        // rather than animating through unrelated rows; nearby controller movement stays smooth.
        if (currentState.layout == LibraryLayout.List) {
            if (appsListState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }) {
                appsListState.animateScrollToItem(targetIndex, focusOffset)
            } else {
                appsListState.scrollToItem(targetIndex, focusOffset)
            }
        } else {
            if (appsGridState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }) {
                appsGridState.animateScrollToItem(targetIndex, focusOffset)
            } else {
                appsGridState.scrollToItem(targetIndex, focusOffset)
            }
        }
    }

    BackHandler(
        enabled = destination == LibraryDestinationKey.Apps && selectionActiveHere,
    ) {
        selectionState = selectionState.clear()
    }
    val availableUpdate = (updateRepository?.status as? UpdateStatus.Available)?.candidate
    LaunchedEffect(hostResumed) {
        if (!hostResumed) {
            sessionDismissedUpdate = null
            updatePrompt = null
        }
    }


    val currentEditorIdentity by rememberUpdatedState(state.libraryScope to metadataTarget?.databaseId)
    val metadataApp = remember(displayedState.apps, metadataTarget) {
        metadataTarget?.let { target ->
            displayedState.apps.firstOrNull { it.databaseId == target.databaseId } ?: target
        }
    }
    val metadataRestoreReady = remember(
        displayedState.apps, metadataRestoreRequest, displayedState.loading,
    ) {
        metadataRestoreRequest?.let { request ->
            val current = displayedState.apps.firstOrNull {
                it.databaseId == request.databaseId
            }
            !displayedState.loading && (current == null || (
                current.title == request.title &&
                    current.author == request.author &&
                    current.defaultDescription == request.description
                ))
        } ?: false
    }

    fun captureAppsAnchor(excludedDatabaseId: Long? = null): LibraryScrollAnchor? {
        val currentViewport = if (activeCollectionId == null) appsViewport else collectionViewport
        val currentApps = displayedState.apps
        val visible: Pair<Int, Int>? = if (state.layout == LibraryLayout.List) {
            val items = currentViewport.listState.layoutInfo.visibleItemsInfo.filter { it.index > 0 }
            (items.firstOrNull { item ->
                val fallbackIndex = (item.index - 1).coerceAtLeast(0)
                currentApps.getOrNull(fallbackIndex)?.databaseId != excludedDatabaseId
            } ?: items.firstOrNull())?.let { it.index to it.offset }
        } else {
            val items = currentViewport.gridState.layoutInfo.visibleItemsInfo.filter { it.index > 0 }
            (items.firstOrNull { item ->
                val fallbackIndex = (item.index - 1).coerceAtLeast(0)
                currentApps.getOrNull(fallbackIndex)?.databaseId != excludedDatabaseId
            } ?: items.firstOrNull())?.let { it.index to it.offset.y }
        }
        if (visible == null) return null
        val fallbackIndex = (visible.first - 1).coerceAtLeast(0)
        return LibraryScrollAnchor(
            generation = state.generation,
            stableItemId = currentApps.getOrNull(fallbackIndex)?.databaseId,
            offsetPx = -visible.second,
            fallbackIndex = fallbackIndex,
            libraryScope = state.libraryScope.takeIf(String::isNotEmpty),
        )
    }

    fun captureAppsViewport(): LibraryViewportSnapshot? {
        val currentViewport = if (activeCollectionId == null) appsViewport else collectionViewport
        return if (state.layout == LibraryLayout.List) {
            if (currentViewport.listState.layoutInfo.totalItemsCount == 0) null
            else LibraryViewportSnapshot(
                layout = LibraryLayout.List,
                firstVisibleItemIndex = currentViewport.listState.firstVisibleItemIndex,
                firstVisibleItemOffsetPx = currentViewport.listState.firstVisibleItemScrollOffset,
            )
        } else if (currentViewport.gridState.layoutInfo.totalItemsCount == 0) null
        else LibraryViewportSnapshot(
            layout = LibraryLayout.Grid,
            firstVisibleItemIndex = currentViewport.gridState.firstVisibleItemIndex,
            firstVisibleItemOffsetPx = currentViewport.gridState.firstVisibleItemScrollOffset,
        )
    }

    fun openMetadataEditor(app: LibraryAppUiItem) {
        metadataRestoreRequest = null
        metadataViewportLocked = true
        metadataImeWasVisible = isImeVisible
        metadataCapturedAnchor = if (destination == LibraryDestinationKey.Apps) {
            captureAppsAnchor(app.databaseId)
        } else null
        metadataCapturedViewport = if (destination == LibraryDestinationKey.Apps) {
            captureAppsViewport()
        } else null
        metadataBackRequest = 0
        metadataTarget = app
    }

    fun closeMetadataEditor(restoreRequest: MetadataRestoreRequest? = null) {
        // Treat a plain back as a viewport-preserving return as well. The editor is an overlay;
        // removing it can still trigger a list remeasure even when no metadata was changed.
        val request = restoreRequest ?: metadataTarget?.let { app ->
            metadataCapturedAnchor?.let { anchor ->
                MetadataRestoreRequest(
                    anchor = anchor,
                    databaseId = app.databaseId,
                    title = app.title,
                    author = app.author,
                    description = app.defaultDescription,
                    viewport = metadataCapturedViewport,
                )
            }
        }
        metadataRestoreRequest = request
        if (request != null) {
            metadataRestoreAnchorKey++
        }
        metadataCapturedAnchor = null
        metadataCapturedViewport = null
        metadataTarget = null
        metadataBackRequest = 0
    }

    LaunchedEffect(controllerEvents, appsViewport) {
        controllerEvents?.collect { event ->
            if (currentControllerAppActions != null) return@collect
            if (currentControllerDetailsTarget != null &&
                event.command != LibraryControllerCommand.Back) return@collect
            if (currentControllerCollectionId != null &&
                event.command != LibraryControllerCommand.Back
            ) return@collect
            controllerFocusVisible = true
            if (currentControllerOtherModalVisible && event.command != LibraryControllerCommand.Back) {
                return@collect
            }
            try {
                when (event.command) {
                    LibraryControllerCommand.MoveUp,
                    LibraryControllerCommand.MoveDown,
                    LibraryControllerCommand.MoveLeft,
                    LibraryControllerCommand.MoveRight,
                    -> moveControllerFocus(event.command)
                    LibraryControllerCommand.Activate -> {
                        val currentState = currentControllerState
                        if (currentControllerDestination != LibraryDestinationKey.Apps ||
                            currentState.apps.isEmpty()
                        ) return@collect
                        val focus = reconcileLibraryControllerFocus(
                            currentState.projectedAppIds,
                            controllerFocusedAppId,
                            controllerFocusedAppIndex,
                        )
                        controllerFocusedAppIndex = focus.index
                        controllerFocusedAppId = focus.databaseId
                        val app = currentState.apps[focus.index]
                        if (currentControllerSelectionActive) {
                            selectionState = selectionState.toggle(currentState.generation, app.databaseId)
                        } else {
                            actions.onOpenApp(app.id)
                        }
                    }
                    LibraryControllerCommand.OpenActions -> {
                        val currentState = currentControllerState
                        if (currentControllerDestination != LibraryDestinationKey.Apps ||
                            currentState.apps.isEmpty() || currentControllerSelectionActive
                        ) return@collect
                        val focus = reconcileLibraryControllerFocus(
                            currentState.projectedAppIds,
                            controllerFocusedAppId,
                            controllerFocusedAppIndex,
                        )
                        controllerFocusedAppIndex = focus.index
                        controllerFocusedAppId = focus.databaseId
                        appActions = currentState.apps[focus.index]
                        appActionsCollectionId = null
                    }
                    LibraryControllerCommand.Back -> when {
                        currentControllerUpdatePrompt != null -> {
                            sessionDismissedUpdate = updateRepository?.candidateKey(currentControllerUpdatePrompt!!)
                            updatePrompt = null
                        }
                        currentControllerCollectionDialogVisible -> Unit
                        currentControllerInstallMenuVisible -> installSourceMenuVisible = false
                        currentControllerRenameTarget != null -> renameTarget = null
                        currentControllerMetadataTarget != null -> metadataBackRequest++
                        currentControllerDetailsTarget != null -> detailsTarget = null
                        currentControllerDeleteTarget != null -> deleteTarget = null
                        currentControllerBulkDeleteIds != null -> pendingBulkDeleteIds = null
                        currentControllerNotificationsPageVisible -> notificationsPageVisible = false
                        currentControllerSelectionActive -> selectionState = selectionState.clear()
                        currentControllerSearchOpen -> {
                            if (currentControllerImeVisible) searchKeyboard?.hide()
                            else {
                                searchOpen = false
                                searchQuery = ""
                            }
                        }
                        currentControllerDestination == LibraryDestinationKey.Apps &&
                            currentNavigationState.collectionManageApps -> {
                            collectionsHost?.collectionsStore()?.dismissPicker()
                            navigationState = currentNavigationState.copy(collectionManageApps = false)
                        }
                        currentControllerDestination == LibraryDestinationKey.Apps &&
                            currentNavigationState.selectedCollectionId != null -> {
                            navigationState = currentNavigationState.copy(
                                selectedCollectionId = null,
                                selectedCollectionScope = null,
                                collectionManageApps = false,
                                destination = LibraryDestinationKey.Apps,
                            )
                            collectionsHost?.onDismissCollectionMembers()
                        }
                        currentControllerDestination != LibraryDestinationKey.Apps ->
                            selectDestination(LibraryDestinationKey.Apps)
                        else -> Unit
                    }
                    LibraryControllerCommand.NextTab,
                    LibraryControllerCommand.PreviousTab,
                    -> Unit
                }
            } catch (interrupted: CancellationException) {
                // Touch may interrupt a scroll without cancelling the controller subscription.
                // Preserve cancellation when the screen or workdir itself was disposed.
                currentCoroutineContext().ensureActive()
            }
        }
    }

    LaunchedEffect(state.libraryScope) { detailsTarget = null }
    BackHandler(enabled = destination == LibraryDestinationKey.Apps &&
        activeCollectionId != null && !selectionActiveHere && !searchOpen &&
        !collectionManageAppsActive) {
        navigationState = navigationState.copy(
            selectedCollectionId = null,
            selectedCollectionScope = null,
        )
        collectionsHost?.onDismissCollectionMembers()
    }
    BackHandler(enabled = destination != LibraryDestinationKey.Apps) {
        selectDestination(LibraryDestinationKey.Apps)
    }
    LaunchedEffect(destination, activeCollectionId, state.libraryScope) {
        when (destination) {
            LibraryDestinationKey.Apps -> {
                if (activeCollectionId == null) appsViewport.chromeVisible = true
                else collectionViewport.chromeVisible = true
            }
            LibraryDestinationKey.Collections -> {
                collectionsViewport.chromeVisible = true
                collectionViewport.chromeVisible = true
            }
        }
        if (destination != LibraryDestinationKey.Apps) {
            appActions = null
            appActionsCollectionId = null
        }
    }
    LaunchedEffect(
        destination,
        navigationState.selectedCollectionId,
        selectionState.isActive,
        selectionState.collectionId,
        navigationState.collectionManageApps,
    ) {
        if (
            selectionState.isActive &&
            (!selectionMatchesDestination || collectionManageAppsActive)
        ) {
            selectionState = selectionState.clear()
        }
    }

    LaunchedEffect(metadataViewportLocked, metadataTarget, isImeVisible, metadataRestoreRequest) {
        if (!metadataViewportLocked || metadataTarget != null || isImeVisible
            || metadataRestoreRequest != null
        ) {
            return@LaunchedEffect
        }
        // Focus removal and the final inset dispatch happen asynchronously after the overlay is
        // removed. Release the lock only after that dispatch has had one frame to settle.
        withFrameNanos { }
        metadataViewportLocked = false
    }

    val imeHidesLibraryChrome = isImeVisible && (!metadataViewportLocked || metadataImeWasVisible)
    val libraryOverlayVisible = appActions != null || renameTarget != null ||
        detailsTarget != null || metadataApp != null
        || deleteTarget != null || pendingBulkDeleteIds != null
        || auxiliaryPageOnScreen
    val libraryModalVisible = libraryOverlayVisible || updatePrompt != null ||
        collectionDialogVisible || collectionManageAppsActive
    LaunchedEffect(libraryModalVisible, installSourceMenuVisible, selectionActiveHere, searchOpen, destination) {
        onControllerBackAvailabilityChanged(
            libraryModalVisible || installSourceMenuVisible || selectionActiveHere || searchOpen ||
                activeCollectionId != null || destination != LibraryDestinationKey.Apps,
        )
    }
    val updateDialogReady = hostResumed && destination == LibraryDestinationKey.Apps &&
        state.databaseControlsReady && !isImeVisible &&
        hostActivity?.isHostDialogVisible() == false && !fragmentDialogVisible &&
        !collectionDialogVisible && !installSourceMenuVisible &&
        !libraryOverlayVisible && !selectionActiveHere && !searchOpen &&
        !collectionManageAppsActive
    LaunchedEffect(availableUpdate, updateDialogReady, updateRepository?.automaticChecks,
        sessionDismissedUpdate, updateRepository?.channel) {
        val repository = updateRepository
        val candidate = availableUpdate
        if (BuildConfig.DEBUG || repository == null || !repository.automaticChecks ||
            !updateDialogReady || candidate == null || repository.isHandled(candidate) ||
            sessionDismissedUpdate == repository.candidateKey(candidate)) {
            updatePrompt = null
        } else {
            updatePrompt = candidate
        }
    }

    val libraryContentModifier = if (!libraryModalVisible) {
        Modifier
    } else {
        Modifier.clearAndSetSemantics { }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clearNavigationFocusOnTouch { controllerFocusVisible = false }
            .showNavigationFocusForKey { controllerFocusVisible = true },
    ) {
        Scaffold(
            modifier = libraryContentModifier.fillMaxSize(),
            contentWindowInsets = scaffoldInsets,
            snackbarHost = noticeHost,
            bottomBar = {
                val collectionId = selectionState.collectionId
                if (
                    selectionActiveHere &&
                    !imeHidesLibraryChrome &&
                    bulkActions != null
                ) {
                    val collectionHost = collectionsHost
                    val inCollection = collectionId != null && collectionHost != null
                    LibrarySelectionBottomBar(
                        enabled = selectionState.selectedAppIds.isNotEmpty(),
                        onDelete = { pendingBulkDeleteIds = selectionState.selectedAppIds },
                        contextActionIcon = if (inCollection) {
                            R.drawable.ic_remove_from_collection
                        } else {
                            R.drawable.ic_add_to_collection
                        },
                        contextActionLabel = stringResource(
                            if (inCollection) {
                                R.string.library_collection_remove_from_current
                            } else {
                                R.string.library_bulk_add_collection
                            },
                        ),
                        onContextAction = {
                            if (collectionId != null && collectionHost != null) {
                                collectionHost.onRemoveAppsFromCollection(
                                    selectionState.selectedAppIds,
                                    collectionId,
                                )
                            } else {
                                bulkActions.onAddSelectedToCollection(selectionState.selectedAppIds)
                            }
                        },
                        onReinstall = { bulkActions.onReinstallSelected(selectionState.selectedAppIds) },
                        onExport = { bulkActions.onExportSelectedBundle(selectionState.selectedAppIds) },
                    )
                }

            },
            floatingActionButton = {
                if (
                    !imeHidesLibraryChrome &&
                    destination == LibraryDestinationKey.Apps &&
                    !selectionActiveHere && !searchOpen && !collectionManageAppsActive
                ) {
                    AnimatedVisibility(
                        modifier = Modifier.windowInsetsPadding(
                            WindowInsets.safeDrawing
                                .exclude(WindowInsets.ime)
                                .only(WindowInsetsSides.Horizontal),
                        ),
                        visible = showInstallFab,
                        enter = fadeIn(
                            animationSpec = tween(
                                durationMillis = LIBRARY_CHROME_ANIMATION_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                        ) + scaleIn(
                            initialScale = 0.86f,
                            animationSpec = tween(
                                durationMillis = LIBRARY_CHROME_ANIMATION_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                        ) + slideInVertically(
                            animationSpec = tween(
                                durationMillis = LIBRARY_CHROME_ANIMATION_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                            initialOffsetY = { it / 2 },
                        ),
                        exit = fadeOut(
                            animationSpec = tween(
                                durationMillis = LIBRARY_CHROME_ANIMATION_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                        ) + scaleOut(
                            targetScale = 0.86f,
                            animationSpec = tween(
                                durationMillis = LIBRARY_CHROME_ANIMATION_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                        ) + slideOutVertically(
                            animationSpec = tween(
                                durationMillis = LIBRARY_CHROME_ANIMATION_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                            targetOffsetY = { it / 2 },
                        ),
                    ) {
                        Box {
                            val addToCollection = activeCollection != null
                            val installLabel = stringResource(
                                if (addToCollection) R.string.library_collection_add_apps
                                else R.string.install,
                            )
                            val openFab = {
                                if (addToCollection) {
                                    navigationState = navigationState.copy(collectionManageApps = true)
                                } else installSourceMenuVisible = true
                            }
                            val showInstallText = availableWindowWidthDp() >= 360.dp &&
                                availableWindowHeightDp() >= 480.dp && density.fontScale < 1.5f
                            if (showInstallText) {
                                ExtendedFloatingActionButton(
                                    onClick = openFab,
                                    modifier = Modifier.semantics { contentDescription = installLabel },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_add),
                                            contentDescription = null,
                                        )
                                    },
                                    text = { Text(installLabel) },
                                )
                            } else {
                                FloatingActionButton(onClick = openFab) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_add),
                                        contentDescription = installLabel,
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = installSourceMenuVisible && !addToCollection,
                                onDismissRequest = { installSourceMenuVisible = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.bulk_install_choose_files)) },
                                    leadingIcon = {
                                        Icon(painterResource(R.drawable.ic_file_picker_file), contentDescription = null)
                                    },
                                    onClick = {
                                        installSourceMenuVisible = false
                                        actions.onInstall()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.bulk_install_choose_folder)) },
                                    leadingIcon = {
                                        Icon(painterResource(R.drawable.ic_folder), contentDescription = null)
                                    },
                                    onClick = {
                                        installSourceMenuVisible = false
                                        actions.onInstallFolder()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.library_action_import_bundle)) },
                                    leadingIcon = {
                                        Icon(painterResource(R.drawable.ic_file_upload), contentDescription = null)
                                    },
                                    onClick = {
                                        installSourceMenuVisible = false
                                        actions.onImportAppBundle()
                                    },
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            fun viewportPadding(): PaddingValues = padding
            val travelPx = with(density) { PAGE_TRAVEL_DP.dp.roundToPx() }
            AnimatedContent(
                targetState = destination,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val direction = if (targetState == LibraryDestinationKey.Apps) -1 else 1
                    pageEnter(direction, travelPx) togetherWith pageExit(direction, travelPx)
                },
                label = "Library context",
            ) { displayedDestination ->
                when (displayedDestination) {
                        LibraryDestinationKey.Apps -> LibraryAppsDestination(
                            state = displayedState,
                            scaffoldPadding = if (selectionActiveHere && destination == LibraryDestinationKey.Apps) {
                                padding
                            } else {
                                viewportPadding()
                            },
                            viewportState = if (activeCollectionId == null) {
                                appsViewport
                            } else collectionViewport,
                            returnAnchor = metadataRestoreRequest?.anchor,
                            returnViewport = metadataRestoreRequest?.viewport,
                            returnAnchorKey = metadataRestoreAnchorKey,
                            returnAnchorReady = metadataRestoreReady && !isImeVisible,
                            onReturnAnchorConsumed = { metadataRestoreRequest = null },
                            freezeViewport = metadataViewportLocked,
                            preserveImePaddingWhileFrozen = metadataImeWasVisible,
                            selectionState = if (selectionState.collectionId == activeCollectionId) {
                                selectionState
                            } else LibrarySelectionState(),
                            controllerFocusedDatabaseId = controllerFocusedAppId
                                .takeIf { controllerFocusVisible },
                            onOpenApp = { id ->
                                if (searchOpen) {
                                    searchKeyboard?.hide()
                                    searchOpen = false
                                    searchQuery = ""
                                }
                                actions.onOpenApp(id)
                            },
                            onOpenActions = {
                                if (searchOpen) {
                                    searchKeyboard?.hide()
                                    searchOpen = false
                                    searchQuery = ""
                                }
                                appActions = it
                                appActionsCollectionId = activeCollectionId
                            },
                            onQuickView = { view ->
                                if (activeCollectionId != null) {
                                    selectionState = selectionState.clear()
                                    appsListState.requestScrollToItem(0)
                                    appsGridState.requestScrollToItem(0)
                                    navigationState = navigationState.copy(
                                        selectedCollectionId = null,
                                        selectedCollectionScope = null,
                                        collectionManageApps = false,
                                    )
                                    collectionsHost?.onDismissCollectionMembers()
                                }
                                if (state.quickView != view) actions.onQuickView(view)
                            },
                            onFavorite = actions::onFavorite,
                            onToggleSelection = { app ->
                                selectionState = selectionState.toggle(
                                    state.generation, app.databaseId,
                                    collectionId = activeCollectionId,
                                )
                            },
                            onExitSelection = {
                                selectionState = selectionState.clear()
                            },
                            onSelectAll = {
                                selectionState = selectionState.selectVisible(
                                    state.generation,
                                    displayedState.projectedAppIds,
                                    collectionId = activeCollectionId,
                                )
                            },
                            onUnselectAll = {
                                selectionState = selectionState.unselectVisible(
                                    state.generation,
                                    displayedState.projectedAppIds,
                                    collectionId = activeCollectionId,
                                )
                            },
                            onSort = actions::onSort,
                            onLayoutChange = actions::onLayoutChange,
                            onRetry = actions::onRetryLibrary,
                            onFabVisibilityChanged = {},
                            onNavigationVisibilityChanged = {},
                            collections = collectionDialogState?.collections.orEmpty(),
                            selectedCollection = activeCollection,
                            onManageCollectionMembers = {
                                navigationState = navigationState.copy(collectionManageApps = true)
                            },
                            onSelectAllApps = {
                                if (activeCollectionId != null) {
                                    selectionState = selectionState.clear()
                                    navigationState = navigationState.copy(
                                        selectedCollectionId = null,
                                        selectedCollectionScope = null,
                                        collectionManageApps = false,
                                    )
                                    collectionsHost?.onDismissCollectionMembers()
                                }
                                if (state.quickView != LibraryQuickView.All) {
                                    actions.onQuickView(LibraryQuickView.All)
                                }
                            },
                            onSelectCollection = { collectionId ->
                                if (activeCollectionId != collectionId) {
                                    selectionState = selectionState.clear()
                                    navigationState = navigationState.copy(
                                        destination = LibraryDestinationKey.Apps,
                                        selectedCollectionId = collectionId,
                                        selectedCollectionScope =
                                            state.libraryScope.takeIf(String::isNotEmpty),
                                        collectionManageApps = false,
                                    )
                                }
                            },
                            onManageCollections = {
                                selectionState = selectionState.clear()
                                navigationState = navigationState.copy(
                                    destination = LibraryDestinationKey.Collections,
                                    collectionManageApps = false,
                                )
                            },
                            onOpenSearch = { searchOpen = true },
                            onOpenNotifications = { notificationsPageVisible = true },
                            onOpenGlobalSettings = actions::onOpenSettings,
                            searchActive = searchOpen && destination == LibraryDestinationKey.Apps,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { searchQuery = it },
                            onCloseSearch = {
                                searchKeyboard?.hide()
                                searchOpen = false
                                searchQuery = ""
                            },
                            searchApps = if (activeCollectionId != null) {
                                displayedState.apps
                            } else if (state.quickView == LibraryQuickView.All &&
                                collectionDialogState?.allAppsPrepared == true
                            ) collectionDialogState.allApps else state.apps,
                            searchAppsPrepared = if (activeCollectionId != null) {
                                sortedMembers != null
                            } else state.quickView != LibraryQuickView.All ||
                                collectionsHost == null || collectionDialogState?.allAppsPrepared == true,
                            resolveUiId = actions::resolveAppUiId,
                            controllerEvents = controllerEvents,
                            active = destination == LibraryDestinationKey.Apps &&
                                !collectionManageAppsActive,
                        )
                        LibraryDestinationKey.Collections -> if (collectionsHost != null) {
                            LibraryCollectionsManagementDestination(
                                host = collectionsHost,
                                libraryState = state,
                                scaffoldPadding = viewportPadding(),
                                viewportState = collectionsViewport,
                                selectedCollectionId = activeCollectionId,
                                active = destination == LibraryDestinationKey.Collections,
                                onBack = { selectDestination(LibraryDestinationKey.Apps) },
                                onOpenCollection = { collectionId ->
                                    selectionState = selectionState.clear()
                                    navigationState = navigationState.copy(
                                        destination = LibraryDestinationKey.Apps,
                                        selectedCollectionId = collectionId,
                                        selectedCollectionScope =
                                            state.libraryScope.takeIf(String::isNotEmpty),
                                        collectionManageApps = false,
                                    )
                                },
                            )
                        } else {
                            LibraryCollectionsDestination(padding)
                        }

                }
            }
        }

        if (collectionManageAppsActive && collectionsHost != null &&
            activeCollection != null
        ) {
            Box(
                modifier = Modifier.fillMaxSize().zIndex(3f)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            ) {
                LibraryCollectionMembershipEditor(
                    host = collectionsHost,
                    collection = activeCollection,
                    members = activeMembers,
                    libraryState = state,
                    viewportState = collectionPickerViewport,
                    onBack = {
                        collectionsHost.collectionsStore().dismissPicker()
                        collectionPickerViewport.collectionProjection = null
                        navigationState = navigationState.copy(collectionManageApps = false)
                    },
                )
            }
        }

        val auxiliaryPageOffset = with(density) { PAGE_TRAVEL_DP.dp.roundToPx() }
        val auxiliaryEnter = pageEnter(1, auxiliaryPageOffset)
        val auxiliaryExit = pageExit(-1, auxiliaryPageOffset)
        AnimatedVisibility(
            visibleState = notificationsPageMotion,
            modifier = Modifier.fillMaxSize().zIndex(4f),
            enter = auxiliaryEnter,
            exit = auxiliaryExit,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize()
                    .then(if (notificationsPageVisible) Modifier else Modifier.clearAndSetSemantics {}),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                LibraryNotificationsScreen(onBack = { notificationsPageVisible = false })
                if (!notificationsPageVisible) {
                    Box(
                        Modifier.matchParentSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {}
                            .clearAndSetSemantics {},
                    )
                }
            }
        }
        detailsTarget?.let { original ->
            val app = displayedState.apps.firstOrNull {
                it.databaseId == original.databaseId
            } ?: original
            Surface(
                modifier = Modifier.fillMaxSize().zIndex(3f),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                key(state.libraryScope, app.databaseId) {
                    LibraryMidletDetailsScreen(
                        app = app,
                        libraryScope = state.libraryScope,
                        iconRatio = state.iconRatio,
                        iconShape = state.iconShape,
                        enhancedIcons = state.enhancedIcons,
                        onBack = { detailsTarget = null },
                        onEditMetadata = if (state.databaseControlsReady) {
                            { openMetadataEditor(app) }
                        } else null,
                        onLoad = { callback -> actions.onLoadMidletDetails(app.id, callback) },
                    )
                }
            }
        }
        metadataApp?.let { app ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .zIndex(4f),
            ) {
                key(state.libraryScope, app.databaseId) {
                    LibraryMetadataEditorScreen(
                    app = app,
                    onBack = ::closeMetadataEditor,
                    backRequest = metadataBackRequest,
                    onLoadDescriptions = { callback ->
                        actions.onLoadLocalizedDescriptions(app.id, callback)
                    },
                    onSaveLocalized = { title, vendor, description, translations, mainIcon, portraitIcon, onResult ->
                        val restoreRequest = metadataCapturedAnchor?.let { anchor ->
                            MetadataRestoreRequest(
                                anchor = anchor,
                                databaseId = app.databaseId,
                                title = title,
                                author = vendor,
                                description = description,
                                viewport = metadataCapturedViewport,
                            )
                        }
                        actions.onUpdateMetadata(
                            app.id,
                            title,
                            vendor,
                            app.version,
                            description,
                            translations,
                            mainIcon,
                            portraitIcon,
                        ) { _, error ->
                            onResult(error == null)
                            if (error == null &&
                                currentEditorIdentity == (state.libraryScope to app.databaseId)) {
                                closeMetadataEditor(restoreRequest)
                            }
                        }
                    },
                    iconShape = state.iconShape,
                    enhancedIcons = state.enhancedIcons,
                    onPickIcon = { portrait, callback ->
                        actions.onPickIconByDatabaseId(app.databaseId, portrait, callback)
                    },
                    onPrepareOriginalIcon = { callback ->
                        actions.onPrepareOriginalIcon(app.databaseId, callback)
                    },
                    )
                }
            }
        }
    }

    appActions?.let { app ->
        // Capture the collection context before AppActionsDialog dismisses itself. Dialog actions
        // call onDismiss() before their action callback, and onDismiss clears appActionsCollectionId.
        val collectionId = appActionsCollectionId
        AppActionsDialog(
            app = app,
            controllerEvents = controllerEvents,
            onControllerKeyEvent = controllerKeyEventHandler?.let { handler ->
                { event -> handler.onControllerKeyEvent(event) }
            },
            onDismiss = {
                appActions = null
                appActionsCollectionId = null
            },
            onShortcut = if (state.canAddShortcut) {
                { actions.onAddShortcut(app.id) }
            } else {
                null
            },
            onSettings = { actions.onOpenAppSettings(app.id) },
            onAddToCollection = if (state.databaseControlsReady && collectionsHost != null) {
                { collectionsHost.onRequestAddToCollection(app.id) }
            } else {
                null
            },
            onRemoveFromCollection = if (
                state.databaseControlsReady && collectionsHost != null && collectionId != null
            ) {
                {
                    collectionsHost.onRemoveAppFromCollection(
                        app.id,
                        collectionId,
                    )
                }
            } else {
                null
            },
            onExportAppBundle = if (state.databaseControlsReady) {
                { actions.onExportAppBundle(app.id) }
            } else {
                null
            },
            onReinstall = { actions.onReinstall(app.id) },
            onDelete = { deleteTarget = app },
            onFavorite = if (state.databaseControlsReady) {
                { actions.onFavorite(app.id, !app.favorite) }
            } else null,
            iconRatio = state.iconRatio,
            iconShape = state.iconShape,
            enhancedIcons = state.enhancedIcons,
            onDetails = { detailsTarget = app },
            onSelect = if (searchOpen) null else {
                {
                    selectionState = selectionState.enter(
                        state.generation,
                        app.databaseId,
                        collectionId = collectionId,
                    )
                }
            },
        )
    }
    renameTarget?.let { app ->
        RenameAppDialog(
            app = app,
            onControllerKeyEvent = controllerKeyEventHandler?.let { handler ->
                { event -> handler.onControllerKeyEvent(event) }
            },
            onDismiss = { renameTarget = null },
            onConfirm = { title ->
                renameTarget = null
                actions.onRename(app.id, title)
            },
        )
    }
    deleteTarget?.let { app ->
        AlertDialog(
            onControllerKeyEvent = controllerKeyEventHandler?.let { handler ->
                { event -> handler.onControllerKeyEvent(event) }
            },
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.action_context_delete)) },
            text = { Text(stringResource(R.string.message_delete)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    actions.onDelete(app.id)
                }) {
                    Text(
                        text = stringResource(R.string.action_context_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    pendingBulkDeleteIds?.let { selectedIds ->
        AlertDialog(
            onControllerKeyEvent = controllerKeyEventHandler?.let { handler ->
                { event -> handler.onControllerKeyEvent(event) }
            },
            onDismissRequest = { pendingBulkDeleteIds = null },
            title = { Text(stringResource(R.string.library_bulk_delete_apps)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.library_bulk_delete_confirmation,
                        selectedIds.size,
                        selectedIds.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingBulkDeleteIds = null
                        bulkActions?.onDeleteSelected(selectedIds)
                    },
                ) {
                    Text(
                        text = stringResource(R.string.library_bulk_delete_apps),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBulkDeleteIds = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    collectionsHost?.let { LibraryCollectionsDialogHost(it, controllerEvents) }
    updatePrompt?.let { candidate ->
        val repository = updateRepository
        if (repository != null) {
            AlertDialog(
                onControllerKeyEvent = controllerKeyEventHandler?.let { handler ->
                    { event -> handler.onControllerKeyEvent(event) }
                },
                onDismissRequest = {
                    sessionDismissedUpdate = repository.candidateKey(candidate)
                    updatePrompt = null
                },
                title = { Text(stringResource(R.string.updates_available)) },
                text = {
                    Text(stringResource(
                        R.string.updates_dialog_message,
                        candidate.versionName,
                        BuildConfig.VERSION_NAME,
                    ))
                },
                confirmButton = {
                    TextButton(onClick = {
                        updatePrompt = null
                        val opened = candidate.downloadUrl?.let(actions::onOpenUpdateDownload)
                            ?: actions.onOpenUpdateRelease(candidate.releaseUrl)
                        if (opened) {
                            repository.handle(candidate)
                        } else {
                            sessionDismissedUpdate = repository.candidateKey(candidate)
                        }
                    }) {
                        Text(stringResource(R.string.updates_download_apk))
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            updatePrompt = null
                            repository.handle(candidate)
                        }) {
                            Text(stringResource(R.string.updates_skip_version))
                        }
                        TextButton(onClick = {
                            updatePrompt = null
                            if (actions.onOpenUpdateRelease(candidate.releaseUrl)) {
                                repository.handle(candidate)
                            } else {
                                sessionDismissedUpdate = repository.candidateKey(candidate)
                            }
                        }) {
                            Text(stringResource(R.string.updates_view_release))
                        }
                    }
                },
            )
        }
    }
}

// Require enough scroll progress to survive reclaiming the bottom navigation height. Without
// this guard, a list/grid with only one or two rows of overflow becomes non-scrollable as soon
// as the footer disappears, which immediately re-shows the chrome and causes a visible flicker.
internal const val LIBRARY_CHROME_HIDE_DISTANCE_DP = 80f
// The footer reclaims roughly one navigation-bar height. Require more than a row or two of
// remaining content before hiding chrome so the viewport resize cannot immediately make the
// list non-scrollable and start a hide/show loop.
internal const val LIBRARY_CHROME_MIN_SCROLL_ROOM_DP = 160f
private const val LIBRARY_CHROME_ANIMATION_MILLIS = 220
private const val LIBRARY_RETURN_ANCHOR_SETTLE_FRAMES = 2
internal val LibraryGridHorizontalPadding = 12.dp

internal class LibraryChromeScrollHysteresis(
    hideDistancePx: Float,
    revealDistancePx: Float,
    initiallyVisible: Boolean = true,
) {
    private val hideThreshold = hideDistancePx.coerceAtLeast(1f)
    private val revealThreshold = revealDistancePx.coerceAtLeast(1f)
    private var forwardDistance = 0f
    private var reverseDistance = 0f

    var chromeVisible: Boolean = initiallyVisible
        private set

    fun reset(): Boolean? {
        forwardDistance = 0f
        reverseDistance = 0f
        return if (chromeVisible) {
            null
        } else {
            chromeVisible = true
            true
        }
    }

    fun onScrollDelta(delta: Float): Boolean? {
        if (delta < 0f) {
            reverseDistance = 0f
            forwardDistance += -delta
            if (chromeVisible && forwardDistance >= hideThreshold) {
                chromeVisible = false
                forwardDistance = 0f
                return false
            }
        } else if (delta > 0f) {
            forwardDistance = 0f
            reverseDistance += delta
            if (!chromeVisible && reverseDistance >= revealThreshold) {
                chromeVisible = true
                reverseDistance = 0f
                return true
            }
        }
        return null
    }
}

@Composable
internal fun LibraryAppsDestination(
    state: LibraryUiState,
    scaffoldPadding: PaddingValues,
    viewportState: LibraryViewportState = rememberLibraryViewportState(),
    returnAnchor: LibraryScrollAnchor? = null,
    returnViewport: LibraryViewportSnapshot? = null,
    returnAnchorKey: Any? = Unit,
    returnAnchorReady: Boolean = true,
    onReturnAnchorConsumed: () -> Unit = {},
    selectionState: LibrarySelectionState = LibrarySelectionState(),
    controllerFocusedDatabaseId: Long? = null,
    onOpenApp: (Int) -> Unit,
    onOpenActions: (LibraryAppUiItem) -> Unit,
    onQuickView: (LibraryQuickView) -> Unit,
    onFavorite: (Int, Boolean) -> Unit,
    onToggleSelection: (LibraryAppUiItem) -> Unit = {},
    onExitSelection: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    onUnselectAll: () -> Unit = {},
    onSort: (Int) -> Unit,
    onLayoutChange: (LibraryLayout) -> Unit = {},
    onRetry: () -> Unit,
    onFabVisibilityChanged: (Boolean) -> Unit,
    onNavigationVisibilityChanged: (Boolean) -> Unit,
    collections: List<LibraryCollectionUiItem> = emptyList(),
    selectedCollection: LibraryCollectionUiItem? = null,
    onManageCollectionMembers: () -> Unit = {},
    onSelectAllApps: () -> Unit = {},
    onSelectCollection: (Long) -> Unit = {},
    onManageCollections: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onOpenGlobalSettings: () -> Unit = {},
    searchActive: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    onCloseSearch: () -> Unit = {},
    searchApps: List<LibraryAppUiItem> = state.apps,
    searchAppsPrepared: Boolean = true,
    resolveUiId: (Long) -> Int? = { null },
    controllerEvents: Flow<LibraryControllerEvent>? = null,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    freezeViewport: Boolean = false,
    preserveImePaddingWhileFrozen: Boolean = false,
    active: Boolean = true,
) {
    val sortFocus = remember { FocusRequester() }
    val listState = viewportState.listState
    val gridState = viewportState.gridState
    val searchListState = rememberLazyListState()
    val searchGridState = rememberLazyGridState()
    val matchingApps by produceState<List<LibraryAppUiItem>?>(
        initialValue = null, searchActive, searchQuery, searchApps, searchAppsPrepared, state.sortVariant,
    ) {
        if (!searchActive || searchQuery.isBlank() || !searchAppsPrepared) return@produceState
        value = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            projectCollectionApps(
                rows = searchApps,
                filter = searchQuery,
                sortVariant = state.sortVariant,
                checkActive = { context.ensureActive() },
            )
        }
    }
    val shownApps = if (searchActive && searchQuery.isNotBlank()) matchingApps else state.apps
    val visibleApps = shownApps.orEmpty()
    LaunchedEffect(searchActive, searchQuery) {
        if (searchActive) {
            searchListState.scrollToItem(0)
            searchGridState.scrollToItem(0)
        }
    }
    var savedHeaderOffset by remember(viewportState) { mutableStateOf<Float?>(null) }
    LaunchedEffect(searchActive) {
        if (searchActive) {
            savedHeaderOffset = viewportState.headerOffsetPx.floatValue
            viewportState.headerOffsetPx.floatValue = 0f
            viewportState.chromeVisible = true
        } else {
            savedHeaderOffset?.let { viewportState.headerOffsetPx.floatValue = it }
            savedHeaderOffset = null
        }
    }
    var sortVisible by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (!active) sortVisible = false
    }
    val currentActive by rememberUpdatedState(active)
    // Read eligibility when an action arrives, retaining callback identity across tab changes.
    val openApp: (Int) -> Unit = { if (currentActive) onOpenApp(it) }
    val openActions: (LibraryAppUiItem) -> Unit = { if (currentActive) onOpenActions(it) }
    val toggleSelection: (LibraryAppUiItem) -> Unit = { if (currentActive) onToggleSelection(it) }
    val favoriteApp: (Int, Boolean) -> Unit = { id, favorite ->
        if (currentActive) onFavorite(id, favorite)
    }
    val retry: () -> Unit = { if (currentActive) onRetry() }
    val headerHeightPx = viewportState.headerHeightPx
    val headerOffsetPx = viewportState.headerOffsetPx
    val searchFocusedDatabaseId = rememberLibraryInlineSearchControllerFocus(
        active = active && (searchActive || selectedCollection != null),
        apps = visibleApps,
        layout = state.layout,
        listState = if (searchActive) searchListState else listState,
        gridState = if (searchActive) searchGridState else gridState,
        headerHeightPx = headerHeightPx.intValue,
        events = controllerEvents,
        resolveUiId = resolveUiId,
        onOpenApp = openApp,
        onOpenActions = openActions,
    )
    val currentOnFabVisibilityChanged by rememberUpdatedState(onFabVisibilityChanged)
    val currentOnNavigationVisibilityChanged by rememberUpdatedState(onNavigationVisibilityChanged)
    val activeFabVisibilityChanged: (Boolean) -> Unit = remember(viewportState) {
        { visible ->
            viewportState.chromeVisible = visible
            if (currentActive) currentOnFabVisibilityChanged(visible)
        }
    }
    val activeNavigationVisibilityChanged: (Boolean) -> Unit = remember(viewportState) {
        { visible ->
            viewportState.chromeVisible = visible
            if (currentActive) currentOnNavigationVisibilityChanged(visible)
        }
    }

    // The list/grid stays mounted while metadata and other overlays are open. A save request is
    // held until the projection contains the submitted metadata, while a plain back leaves the
    // existing LazyListState/LazyGridState untouched. This prevents a second scrollToItem from
    // nudging the content after a row has been remeasured.
    LaunchedEffect(
        state.layout,
        state.generation,
        state.libraryScope,
        returnAnchorKey,
        returnAnchorReady,
    ) {
        if (returnAnchor == null || !returnAnchorReady) return@LaunchedEffect
        // Room projection readiness does not imply that the changed row has completed its
        // final measure. Wait for two bounded frames before taking the viewport snapshot.
        repeat(LIBRARY_RETURN_ANCHOR_SETTLE_FRAMES) { withFrameNanos { } }
        val anchor = resolveLibraryScrollAnchor(returnAnchor, state.projectedAppIds)
        // A raw viewport snapshot is the exact visual contract for returning from an overlay.
        // Prefer it over the stable-id resolution because a Room projection can remeasure or
        // reorder rows after the editor closes, before the stable anchor has been consumed.
        val targetIndex = if (returnViewport?.layout == state.layout) {
            val maxIndex = state.apps.size.coerceAtLeast(0)
            returnViewport.firstVisibleItemIndex.coerceIn(0, maxIndex)
        } else {
            anchor.index + 1 // the header occupies item index zero
        }
        val targetOffset = if (returnViewport?.layout == state.layout) {
            returnViewport.firstVisibleItemOffsetPx
        } else {
            anchor.offsetPx
        }
        if (state.layout == LibraryLayout.List) {
            if (listState.firstVisibleItemIndex != targetIndex ||
                listState.firstVisibleItemScrollOffset != targetOffset
            ) {
                listState.scrollToItem(targetIndex, targetOffset)
            }
            withFrameNanos { }
            if (listState.firstVisibleItemIndex != targetIndex ||
                listState.firstVisibleItemScrollOffset != targetOffset
            ) {
                listState.scrollToItem(targetIndex, targetOffset)
            }
        } else if (
            gridState.firstVisibleItemIndex != targetIndex ||
            gridState.firstVisibleItemScrollOffset != targetOffset
        ) {
            gridState.scrollToItem(targetIndex, targetOffset)
            withFrameNanos { }
            if (gridState.firstVisibleItemIndex != targetIndex ||
                gridState.firstVisibleItemScrollOffset != targetOffset
            ) {
                gridState.scrollToItem(targetIndex, targetOffset)
            }
        }
        onReturnAnchorConsumed()
    }

    val listModifier = Modifier
        .fillMaxSize()
        .then(
            if (!freezeViewport || preserveImePaddingWhileFrozen) {
                Modifier.imePadding()
            } else {
                Modifier
            },
        )
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
    val renderHeader: @Composable (Modifier, Boolean) -> Unit = { headerModifier, interactive ->
        LibraryAppsHeader(
            modifier = headerModifier,
            headerOffsetPx = headerOffsetPx,
            dismissFocusRequester = sortFocus,
            state = state,
            sortVisible = active && sortVisible,
            onSortVisibilityChanged = { sortVisible = currentActive && it },
            // Request the beginning of the incoming Lazy projection before it remeasures.
            // Unlike search focus, a quick-filter switch never resets the floating header.
            onQuickView = { selected ->
                if (currentActive && selected != state.quickView) {
                    if (selectionState.isActive) onExitSelection()
                    if (state.layout == LibraryLayout.List) listState.requestScrollToItem(0)
                    else gridState.requestScrollToItem(0)
                    onQuickView(selected)
                }
            },
            onSort = { if (currentActive) onSort(it) },
            onLayoutChange = { if (currentActive) onLayoutChange(it) },
            selectionState = selectionState,
            onExitSelection = { if (currentActive) onExitSelection() },
            onSelectAll = { if (currentActive) onSelectAll() },
            onUnselectAll = { if (currentActive) onUnselectAll() },
            title = title,
            onBack = onBack?.let { back -> { if (currentActive) back() } },
            collections = collections,
            selectedCollection = selectedCollection,
            onSelectAllApps = onSelectAllApps,
            onSelectCollection = onSelectCollection,
            onManageCollections = onManageCollections,
            onOpenSearch = onOpenSearch,
            onOpenNotifications = onOpenNotifications,
            onOpenGlobalSettings = onOpenGlobalSettings,
            searchActive = searchActive,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            onCloseSearch = onCloseSearch,
            interactive = interactive,
        )
    }
    val headerScrollConnection = rememberLibraryScrollChrome(
        viewport = viewportState,
        layout = state.layout,
        headerHeightPx = headerHeightPx,
        enabled = active && !freezeViewport && !searchActive,
        onVisibilityChanged = { visible ->
            activeFabVisibilityChanged(visible)
            activeNavigationVisibilityChanged(visible)
        },
    )
    val headerHidden by remember(headerHeightPx, headerOffsetPx) { derivedStateOf {
        headerHeightPx.intValue > 0 && headerOffsetPx.floatValue <= -headerHeightPx.intValue + 0.5f
    } }
    Box(
        modifier = listModifier
            .clipToBounds()
            .nestedScroll(headerScrollConnection),
    ) {
        if (state.layout == LibraryLayout.Grid) {
            // Compute columns before the outer gutter to preserve the grid density.
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed((maxWidth / state.gridIconSize.minCellWidth).toInt().coerceAtLeast(1)),
                    modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = LibraryGridHorizontalPadding),
                    state = if (state.loading || state.errorMessage != null) rememberLazyGridState()
                        else if (searchActive) searchGridState else gridState,
                    contentPadding = scaffoldPadding,
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        if (headerHeightPx.intValue == 0) {
                            renderHeader(
                                Modifier
                                    .alpha(0f)
                                    .clearAndSetSemantics { },
                                false,
                            )
                        } else {
                            LibraryChromeSpacer(headerHeightPx, headerOffsetPx)
                        }
                    }
                    when {
                        state.errorMessage != null -> item(span = { GridItemSpan(maxLineSpan) }) {
                            LibraryErrorState(retry)
                        }
                        state.loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                            LibraryLoadingState(state)
                        }
                        searchActive && searchQuery.isNotBlank() && shownApps == null ->
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().heightIn(min = 160.dp),
                                    contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            }
                        visibleApps.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                            if (selectedCollection != null && !searchActive) {
                                LibraryCollectionBrowseEmptyState(onManageCollectionMembers)
                            } else {
                                LibraryEmptyState(if (searchActive) searchQuery else state.appliedFilter)
                            }
                        }
                        else -> items(visibleApps, key = { it.databaseId }) { app ->
                            LibraryGridItem(
                                app = app,
                                iconRatio = state.iconRatio,
                                iconShape = state.iconShape,
                                enhancedIcons = state.enhancedIcons,
                                hideTitle = state.hideGridTitles,
                                gridSpacing = state.gridSpacing.value,
                                gridIconSize = state.gridIconSize,
                                onOpenApp = { id ->
                                    if (searchActive) {
                                        val live = resolveUiId(app.databaseId) ?: id.takeIf { it != 0 }
                                        if (live != null) openApp(live)
                                    } else openApp(id)
                                },
                                onOpenActions = { item ->
                                    if (searchActive) {
                                        val live = resolveUiId(app.databaseId) ?: item.id.takeIf { it != 0 }
                                        if (live != null) openActions(item.copy(id = live))
                                    } else openActions(item)
                                },
                                controllerFocused = if (searchActive ||
                                    selectedCollection != null
                                ) {
                                    app.databaseId == searchFocusedDatabaseId
                                } else {
                                    app.databaseId == controllerFocusedDatabaseId &&
                                        !selectionState.isActive
                                },
                                selectionMode = selectionState.isActive,
                                selected = app.databaseId in selectionState.selectedAppIds,
                                onToggleSelection = toggleSelection,
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = if (state.loading || state.errorMessage != null) rememberLazyListState()
                else if (searchActive) searchListState else listState,
                contentPadding = scaffoldPadding,
            ) {
                item {
                    if (headerHeightPx.intValue == 0) {
                        renderHeader(
                            Modifier
                                .alpha(0f)
                                .clearAndSetSemantics { },
                            false,
                        )
                    } else {
                        LibraryChromeSpacer(headerHeightPx, headerOffsetPx)
                    }
                }
                when {
                    state.errorMessage != null -> item { LibraryErrorState(retry) }
                    state.loading -> item { LibraryLoadingState(state) }
                    searchActive && searchQuery.isNotBlank() && shownApps == null ->
                        item {
                            Box(Modifier.fillMaxWidth().heightIn(min = 160.dp),
                                contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        }
                    visibleApps.isEmpty() -> item {
                        if (selectedCollection != null && !searchActive) {
                            LibraryCollectionBrowseEmptyState(onManageCollectionMembers)
                        } else {
                            LibraryEmptyState(if (searchActive) searchQuery else state.appliedFilter)
                        }
                    }
                    else -> items(visibleApps, key = { it.databaseId }) { app ->
                        LibraryListItem(
                            app = app,
                            iconRatio = state.iconRatio,
                            iconShape = state.iconShape,
                            enhancedIcons = state.enhancedIcons,
                            onOpenApp = { id ->
                                if (searchActive) {
                                    val live = resolveUiId(app.databaseId) ?: id.takeIf { it != 0 }
                                    if (live != null) openApp(live)
                                } else openApp(id)
                            },
                            onOpenActions = { item ->
                                if (searchActive) {
                                    val live = resolveUiId(app.databaseId) ?: item.id.takeIf { it != 0 }
                                    if (live != null) openActions(item.copy(id = live))
                                } else openActions(item)
                            },
                            onFavorite = { id, favorite ->
                                if (searchActive) {
                                    val live = resolveUiId(app.databaseId) ?: id.takeIf { it != 0 }
                                    if (live != null) favoriteApp(live, favorite)
                                } else favoriteApp(id, favorite)
                            },
                            controllerFocused = if (searchActive ||
                                selectedCollection != null
                            ) {
                                app.databaseId == searchFocusedDatabaseId
                            } else {
                                app.databaseId == controllerFocusedDatabaseId &&
                                    !selectionState.isActive
                            },
                            favoriteEnabled = state.databaseControlsReady,
                            selectionMode = selectionState.isActive,
                            selected = app.databaseId in selectionState.selectedAppIds,
                            onToggleSelection = toggleSelection,
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .graphicsLayer { translationY = headerOffsetPx.floatValue }
                .background(MaterialTheme.colorScheme.background)
                .then(
                    if (!headerHidden) {
                        Modifier
                    } else {
                        Modifier.clearAndSetSemantics { }
                    },
                )
                .onSizeChanged { headerHeightPx.intValue = it.height },
        ) {
            renderHeader(Modifier, active)
        }
        val scrimVisible by remember(headerOffsetPx) { derivedStateOf { headerOffsetPx.floatValue < -1f } }
        GlassSystemBarScrim(visible = scrimVisible)
    }
}

internal fun LazyListState.hasLibraryChromeScrollRoom(minScrollRoomPx: Float): Boolean {
    if (!canScrollForward) return false
    val layout = layoutInfo
    val lastVisible = layout.visibleItemsInfo.lastOrNull() ?: return false
    val remaining = lastVisible.offset + lastVisible.size - layout.viewportEndOffset
    val unmeasuredRows = layout.totalItemsCount - lastVisible.index - 1
    return remaining + unmeasuredRows * lastVisible.size > minScrollRoomPx
}

internal fun LazyGridState.hasLibraryChromeScrollRoom(minScrollRoomPx: Float): Boolean {
    if (!canScrollForward) return false
    val layout = layoutInfo
    val lastVisible = layout.visibleItemsInfo.lastOrNull() ?: return false
    val remaining = lastVisible.offset.y + lastVisible.size.height - layout.viewportEndOffset
    val columns = layout.visibleItemsInfo.maxOfOrNull { it.column + 1 }?.coerceAtLeast(1) ?: 1
    val unmeasuredItems = layout.totalItemsCount - lastVisible.index - 1
    val unmeasuredRows = (unmeasuredItems + columns - 1) / columns
    return remaining + unmeasuredRows * lastVisible.size.height > minScrollRoomPx
}

@Composable
private fun LibrarySelectionBottomBar(
    enabled: Boolean,
    onDelete: () -> Unit,
    contextActionIcon: Int,
    contextActionLabel: String,
    onContextAction: () -> Unit,
    onReinstall: () -> Unit,
    onExport: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibrarySelectionAction(
                icon = R.drawable.ic_delete,
                label = stringResource(R.string.library_selection_action_delete),
                onClick = onDelete,
                enabled = enabled,
                destructive = true,
            )
            LibrarySelectionAction(
                icon = contextActionIcon,
                label = stringResource(R.string.library_selection_action_collection),
                onClick = onContextAction,
                enabled = enabled,
                description = contextActionLabel,
            )
            LibrarySelectionAction(
                icon = R.drawable.ic_file_download,
                label = stringResource(R.string.library_selection_action_export),
                onClick = onExport,
                enabled = enabled,
            )
            LibrarySelectionAction(
                icon = R.drawable.ic_reinstall,
                label = stringResource(R.string.library_selection_action_reinstall),
                onClick = onReinstall,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun RowScope.LibrarySelectionAction(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    destructive: Boolean = false,
    description: String = label,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(MaterialTheme.shapes.large)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun JlModPlusWordmark(
    showDebugMark: Boolean,
    modifier: Modifier = Modifier,
) {
    val productName = stringResource(R.string.about_product_name)
    val appLabel = stringResource(R.string.app_name)
    val accentStart = productName.lastIndexOf(' ')
        .takeIf { it >= 0 && it + 1 < productName.length }
        ?.plus(1)
        ?: 0
    val wordmark = buildAnnotatedString {
        append(productName)
        addStyle(
            SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            ),
            start = accentStart,
            end = productName.length,
        )
    }

    FlowRow(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = appLabel
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = wordmark,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showDebugMark) {
            Surface(
                modifier = Modifier.align(Alignment.CenterVertically),
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = stringResource(R.string.app_debug_badge),
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryAppsHeader(
    modifier: Modifier = Modifier,
    headerOffsetPx: MutableFloatState,
    dismissFocusRequester: FocusRequester,
    state: LibraryUiState,
    sortVisible: Boolean,
    onSortVisibilityChanged: (Boolean) -> Unit,
    onQuickView: (LibraryQuickView) -> Unit,
    onSort: (Int) -> Unit,
    onLayoutChange: (LibraryLayout) -> Unit = {},
    selectionState: LibrarySelectionState = LibrarySelectionState(),
    onExitSelection: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    onUnselectAll: () -> Unit = {},
    title: String? = null,
    onBack: (() -> Unit)? = null,
    collections: List<LibraryCollectionUiItem> = emptyList(),
    selectedCollection: LibraryCollectionUiItem? = null,
    onSelectAllApps: () -> Unit = {},
    onSelectCollection: (Long) -> Unit = {},
    onManageCollections: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onOpenGlobalSettings: () -> Unit = {},
    searchActive: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    onCloseSearch: () -> Unit = {},
    interactive: Boolean = true,
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var scopePickerVisible by remember { mutableStateOf(false) }
    val scopeWindowHeight = availableWindowHeightDp()
    val useScopeDialog = scopeWindowHeight < 480.dp
    val scopeMaxHeight = (scopeWindowHeight - if (useScopeDialog) 64.dp else 136.dp)
        .coerceAtLeast(120.dp)
    val scopeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scopeSheetCoroutine = rememberCoroutineScope()
    fun closeScopeThen(action: () -> Unit) {
        if (useScopeDialog) {
            scopePickerVisible = false
            action()
        } else {
            scopeSheetCoroutine.launch {
                scopeSheetState.hide()
                scopePickerVisible = false
                action()
            }
        }
    }
    val sortEntries = stringArrayResource(R.array.pref_app_sort_entries).toList()
    val projectionKey = remember(state) {
        LibraryProjectionKey(state.projectionRevision, state.apps)
    }
    val visibleAppIds = if (selectionState.isActive) state.projectedAppIds else emptyList()
    val selectedSort = state.sortVariant and Int.MAX_VALUE
    val ascending = state.sortVariant >= 0
    val compactHeaderActions = LocalDensity.current.fontScale >= 1.5f &&
        availableWindowWidthDp() < 600.dp &&
        !selectionState.isActive && title == null && onBack == null
    val titleGate = rememberLibraryHeaderActionGate(headerOffsetPx)
    val searchGate = rememberLibraryHeaderActionGate(headerOffsetPx)
    val titleActionsEnabled = interactive && titleGate.enabled.value
    val searchActionsEnabled = interactive && searchGate.enabled.value
    LaunchedEffect(sortVisible, searchActionsEnabled) {
        if (interactive && sortVisible && !searchActionsEnabled) {
            onSortVisibilityChanged(false)
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        AnimatedContent(
            targetState = searchActive && !selectionState.isActive && title == null && onBack == null,
            modifier = Modifier.fillMaxWidth()
                .then(titleGate.positionModifier)
                .then(if (titleActionsEnabled) Modifier else Modifier.clearAndSetSemantics {}),
            transitionSpec = {
                (fadeIn(tween(180)) + slideInHorizontally(
                    animationSpec = tween(220), initialOffsetX = { it / 12 },
                )) togetherWith (fadeOut(tween(120)) + slideOutHorizontally(
                    animationSpec = tween(180), targetOffsetX = { -it / 12 },
                ))
            },
            label = "Library search header",
        ) { searching ->
            if (searching) {
                InlineSearchField(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = titleActionsEnabled,
                    onDismissInput = { focusManager.clearFocus() },
                    onClose = onCloseSearch,
                    autoFocus = interactive,
                    placeholder = stringResource(R.string.library_search_placeholder),
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selectionState.isActive || onBack != null) {
                        IconButton(
                            onClick = if (selectionState.isActive) onExitSelection else requireNotNull(onBack),
                            enabled = titleActionsEnabled,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.library_back),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    when {
                        selectionState.isActive -> {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.library_selection_count,
                                    selectionState.selectedCount,
                                    selectionState.selectedCount,
                                ),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.headlineSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        title != null -> {
                            Text(
                                text = title,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.headlineSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        else -> {
                            JlModPlusWordmark(
                                showDebugMark = BuildConfig.DEBUG,
                                modifier = Modifier.weight(1f),
                            )
                            if (!compactHeaderActions) {
                                LibraryHeaderActions(
                                    onOpenSearch = onOpenSearch,
                                    onOpenNotifications = onOpenNotifications,
                                    onOpenSettings = onOpenGlobalSettings,
                                    enabled = titleActionsEnabled,
                                )
                            }
                        }
                    }
                    if (selectionState.isActive) {
                        val allVisibleSelected = remember(projectionKey, selectionState.selectedAppIds) {
                            selectionState.isAllVisibleSelected(visibleAppIds)
                        }
                        val selectionToggleLabel = stringResource(
                            if (allVisibleSelected) {
                                R.string.library_selection_unselect_all
                            } else {
                                R.string.library_selection_select_all
                            },
                        )
                        IconButton(
                            onClick = if (allVisibleSelected) onUnselectAll else onSelectAll,
                            enabled = visibleAppIds.isNotEmpty() && titleActionsEnabled,
                            modifier = Modifier.semantics {
                                contentDescription = selectionToggleLabel
                            },
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (allVisibleSelected) {
                                        R.drawable.ic_deselect
                                    } else {
                                        R.drawable.ic_select_all
                                    },
                                ),
                                contentDescription = null,
                            )
                        }
                    }
                }

            }
        }
        AnimatedVisibility(
            visible = compactHeaderActions && !searchActive,
            enter = fadeIn(tween(180)) + expandVertically(animationSpec = tween(220)),
            exit = fadeOut(tween(120)) + shrinkVertically(animationSpec = tween(180)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    .then(titleGate.positionModifier)
                    .then(if (titleActionsEnabled) Modifier else Modifier.clearAndSetSemantics {}),
                horizontalArrangement = Arrangement.End,
            ) {
                LibraryHeaderActions(
                    onOpenSearch = onOpenSearch,
                    onOpenNotifications = onOpenNotifications,
                    onOpenSettings = onOpenGlobalSettings,
                    enabled = titleActionsEnabled,
                )
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .then(searchGate.positionModifier)
                .then(if (searchActionsEnabled) Modifier else Modifier.clearAndSetSemantics {}),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .testTag("library_scope_picker")
                        .clickable(
                            enabled = searchActionsEnabled,
                            role = Role.Button,
                            onClick = { scopePickerVisible = true },
                        )
                        .padding(start = 16.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        painter = painterResource(
                            if (selectedCollection != null) R.drawable.ic_folder else when (state.quickView) {
                                LibraryQuickView.All -> R.drawable.ic_apps
                                LibraryQuickView.Favorites -> R.drawable.ic_star
                                LibraryQuickView.RecentlyPlayed -> R.drawable.ic_history
                                LibraryQuickView.RecentlyAdded -> R.drawable.ic_recently_added
                            },
                        ),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = selectedCollection?.name ?: stringResource(
                            when (state.quickView) {
                                LibraryQuickView.All -> R.string.library_destination_apps
                                LibraryQuickView.Favorites -> R.string.library_filter_favorites
                                LibraryQuickView.RecentlyPlayed -> R.string.library_filter_recently_opened
                                LibraryQuickView.RecentlyAdded -> R.string.library_filter_recently_added
                            },
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_keyboard_arrow_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Box(
                    modifier = Modifier.width(1.dp).height(24.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Box {
                    IconButton(
                        onClick = { onSortVisibilityChanged(true) },
                        enabled = searchActionsEnabled,
                        modifier = Modifier
                            .size(48.dp)
                            .focusRequester(dismissFocusRequester)
                            .focusProperties { canFocus = searchActionsEnabled },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_sort),
                            contentDescription = stringResource(R.string.library_sort),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LibrarySortMenu(
                        expanded = sortVisible && searchActionsEnabled,
                        entries = sortEntries,
                        selectedSort = selectedSort,
                        ascending = ascending,
                        onDismissRequest = { onSortVisibilityChanged(false) },
                        onSelected = { index ->
                            onSortVisibilityChanged(false)
                            onSort(index)
                        },
                    )
                }
                IconButton(
                    onClick = {
                        onLayoutChange(
                            if (state.layout == LibraryLayout.List) LibraryLayout.Grid
                            else LibraryLayout.List,
                        )
                    },
                    enabled = searchActionsEnabled,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        painter = painterResource(
                            if (state.layout == LibraryLayout.List) R.drawable.ic_editor_grid
                            else R.drawable.ic_view_list,
                        ),
                        contentDescription = stringResource(
                            if (state.layout == LibraryLayout.List) R.string.library_view_grid
                            else R.string.library_view_list,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (scopePickerVisible) {
        val scopeContent: @Composable (Boolean) -> Unit = { insideSheet ->
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val minimumTileWidth = if (LocalDensity.current.fontScale >= 1.5f) 220.dp else 148.dp
                val columns = ((maxWidth - 32.dp + 8.dp) / (minimumTileWidth + 8.dp))
                    .toInt().coerceAtLeast(1)
                val views = listOf(
                    Triple(LibraryQuickView.All, R.string.library_destination_apps, R.drawable.ic_apps),
                    Triple(LibraryQuickView.Favorites, R.string.library_filter_favorites, R.drawable.ic_star),
                    Triple(LibraryQuickView.RecentlyPlayed, R.string.library_filter_recently_opened, R.drawable.ic_history),
                    Triple(LibraryQuickView.RecentlyAdded, R.string.library_filter_recently_added, R.drawable.ic_recently_added),
                )
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = scopeMaxHeight)
                        .then(
                            if (insideSheet) Modifier.windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                            ) else Modifier
                        ),
                ) {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                    ) {
                        items((views.size + columns - 1) / columns, key = { "view-$it" }) { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                repeat(columns) { column ->
                                    val view = views.getOrNull(row * columns + column)
                                    if (view == null) {
                                        Spacer(Modifier.weight(1f))
                                    } else {
                                        LibraryScopeChoiceTile(
                                            title = stringResource(view.second),
                                            icon = view.third,
                                            selected = selectedCollection == null && state.quickView == view.first,
                                            enabled = view.first == LibraryQuickView.All || state.databaseControlsReady,
                                            onClick = {
                                                closeScopeThen {
                                                    if (view.first == LibraryQuickView.All) {
                                                        if (selectedCollection == null) onQuickView(LibraryQuickView.All)
                                                        else onSelectAllApps()
                                                    } else {
                                                        onQuickView(view.first)
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        if (collections.isNotEmpty()) {
                            item(key = "collections-heading") {
                                Text(
                                    text = stringResource(R.string.library_destination_collections),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(
                                        start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp,
                                    ),
                                )
                            }
                        }
                        items((collections.size + columns - 1) / columns, key = { "collection-row-$it" }) { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                repeat(columns) { column ->
                                    val collection = collections.getOrNull(row * columns + column)
                                    if (collection == null) {
                                        Spacer(Modifier.weight(1f))
                                    } else {
                                        LibraryScopeChoiceTile(
                                            title = collection.name,
                                            supporting = pluralStringResource(
                                                R.plurals.library_collection_member_count,
                                                collection.appCount,
                                                collection.appCount,
                                            ),
                                            icon = R.drawable.ic_folder,
                                            selected = selectedCollection?.id == collection.id,
                                            onClick = {
                                                closeScopeThen { onSelectCollection(collection.id) }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    ListItem(
                        headlineContent = {
                            Text(
                                stringResource(R.string.library_collection_manage),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.ic_collections),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth()
                            .padding(top = 6.dp, bottom = 4.dp, start = 8.dp, end = 8.dp)
                            .clip(MaterialTheme.shapes.large)
                            .clickable { closeScopeThen(onManageCollections) },
                    )
                }
            }
        }
        if (useScopeDialog) {
            AlertDialog(
                onDismissRequest = { scopePickerVisible = false },
                textScrollable = false,
                text = { scopeContent(false) },
                confirmButton = null,
            )
        } else {
            ModalBottomSheet(
                onDismissRequest = { scopePickerVisible = false },
                sheetState = scopeSheetState,
                contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
            ) {
                scopeContent(true)
            }
        }
    }

}

@Composable
private fun LibraryHeaderActions(
    onOpenSearch: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenSettings: () -> Unit,
    enabled: Boolean,
    hasUnreadNotifications: Boolean = false,
) {
    IconButton(onClick = onOpenSearch, enabled = enabled) {
        Icon(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = stringResource(R.string.library_search_placeholder),
        )
    }
    val notificationsLabel = stringResource(R.string.library_notifications_title)
    IconButton(
        onClick = onOpenNotifications,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = notificationsLabel },
    ) {
        Box(modifier = Modifier.size(24.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_notifications),
                contentDescription = null,
            )
            if (hasUnreadNotifications) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd)
                        .size(7.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
    IconButton(onClick = onOpenSettings, enabled = enabled) {
        Icon(
            painter = painterResource(R.drawable.ic_settings),
            contentDescription = stringResource(R.string.action_settings),
        )
    }
}

@Composable
private fun RowScope.LibraryScopeChoiceTile(
    title: String,
    icon: Int,
    selected: Boolean,
    onClick: () -> Unit,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh
    val foreground = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 64.dp)
            .clip(MaterialTheme.shapes.large).background(background)
            .semantics { this.selected = selected }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = foreground.copy(alpha = if (enabled) 1f else 0.38f),
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = foreground.copy(alpha = if (enabled) 1f else 0.38f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun LibraryLoadingState(state: LibraryUiState) {
    val loadingDescription = stringResource(R.string.loading_apps)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.semantics {
                contentDescription = loadingDescription
            },
        )
        Text(
            text = if (state.loadingTotal > 0) {
                stringResource(
                    R.string.library_indexing_progress,
                    state.loadingCompleted,
                    state.loadingTotal,
                )
            } else {
                loadingDescription
            },
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.loadingStorageKey.isNotBlank()) {
            Text(
                text = stringResource(R.string.library_indexing_current, state.loadingStorageKey),
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LibraryErrorState(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.library_load_error_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.library_load_failed_message),
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(
            onClick = onRetry,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.library_retry))
        }
    }
}

@Composable
private fun LibraryCollectionBrowseEmptyState(onAddApps: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.library_collection_members_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onAddApps) {
            Icon(
                painter = painterResource(R.drawable.ic_add_to_collection),
                contentDescription = null,
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.library_collection_add_apps))
        }
    }
}

@Composable
private fun LibraryEmptyState(appliedFilter: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text = if (appliedFilter.isEmpty()) {
                stringResource(R.string.no_data_for_display)
            } else {
                stringResource(R.string.msg_no_matches, appliedFilter)
            },
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun LibraryCollectionsDestination(scaffoldPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(scaffoldPadding)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.library_destination_collections),
            style = MaterialTheme.typography.headlineSmall,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.library_collections_unavailable_title),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.library_collections_empty_message),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun RenameAppDialog(
    app: LibraryAppUiItem,
    onControllerKeyEvent: ((KeyEvent) -> Boolean)? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(app.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(
            TextFieldValue(
                text = app.title,
                selection = TextRange(app.title.length),
            ),
        )
    }
    val valid = value.text.trim().isNotEmpty()
    AlertDialog(
        textScrollable = false,
        onControllerKeyEvent = onControllerKeyEvent,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_context_rename)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                isError = !valid,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.text.trim()) },
                enabled = valid,
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
