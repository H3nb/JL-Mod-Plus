package io.github.h3nb.jlmodplus.config

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import io.github.h3nb.jlmodplus.util.EdgeToEdgeCompat
import java.io.File
import java.util.concurrent.Executors

/**
 * A MIDlet chooses a set here, but edits to the set itself affect all MIDlets.
 * No image decode or folder scan happens on the Compose/UI thread.
 */
class ControlSkinManagerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_WORKDIR = "control_skin_workdir"
        const val EXTRA_SELECTED = "control_skin_selected"
        const val EXTRA_KEY_SHAPE = "control_skin_shape"
        const val EXTRA_KEY_BG = "control_skin_bg"
        const val EXTRA_KEY_BG_PRESSED = "control_skin_bg_pressed"
        const val EXTRA_KEY_FG = "control_skin_fg"
        const val EXTRA_KEY_FG_PRESSED = "control_skin_fg_pressed"
        const val EXTRA_KEY_OUTLINE = "control_skin_outline"

        private val numeric = listOf(
            listOf("1", "2", "3"), listOf("4", "5", "6"),
            listOf("7", "8", "9"), listOf("star", "0", "pound"),
        )
        private val navigation = listOf(
            listOf("up_left", "up", "up_right"),
            listOf("left", "fire", "right"),
            listOf("down_left", "down", "down_right"),
        )
        private val other = listOf(
            listOf("soft_left", "soft_right"),
            listOf("a", "b", "c"), listOf("d", "menu"),
        )
    }

    private data class Images(val normal: Bitmap?, val pressed: Bitmap?, val mode: String)
    private data class ImportTarget(val setId: String, val key: String, val pressed: Boolean)
    private enum class NameAction { CREATE, RENAME, DUPLICATE }
    private data class NamePrompt(val action: NameAction, val setId: String?, val initial: String)

    private lateinit var workdir: File
    private val worker = Executors.newSingleThreadExecutor()
    private var sets by mutableStateOf<List<ControlSkinStore.SetInfo>>(emptyList())
    private var counts by mutableStateOf<Map<String, Int>>(emptyMap())
    private var previews by mutableStateOf<Map<String, Images>>(emptyMap())
    private var editingId by mutableStateOf<String?>(null)
    private var selectedId by mutableStateOf<String?>(null)
    private var selectedKey by mutableStateOf<String?>(null)
    private var menuId by mutableStateOf<String?>(null)
    private var namePrompt by mutableStateOf<NamePrompt?>(null)
    private var deleteId by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var importTarget: ImportTarget? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = importTarget
        importTarget = null
        if (target == null || uri == null) return@registerForActivityResult
        val mime = contentResolver.getType(uri)
        val suffix = when (mime) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> null
        }
        if (suffix == null) {
            notifyError(getString(R.string.control_skin_invalid_image))
            return@registerForActivityResult
        }
        perform {
            contentResolver.openInputStream(uri)?.use {
                ControlSkinStore.importImage(workdir, target.setId, target.key, target.pressed, it, suffix)
            } ?: error("Cannot read image")
            target.setId
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_WORKDIR) ?: run { finish(); return }
        workdir = File(path)
        selectedId = if (savedInstanceState?.containsKey("selected") == true)
            savedInstanceState.getString("selected") else intent.getStringExtra(EXTRA_SELECTED)
        editingId = savedInstanceState?.getString("editing")
        selectedKey = savedInstanceState?.getString("key")
        importTarget = savedInstanceState?.getString("import_id")?.let { id ->
            val key = savedInstanceState.getString("import_key")
            if (key == null) null else ImportTarget(id, key, savedInstanceState.getBoolean("import_pressed"))
        }
        EdgeToEdgeCompat.enableForComposeSurface(this)
        val view = ComposeView(this)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        view.setContent { JLModPlusTheme { ManagerScreen() } }
        setContentView(view)
        perform { editingId }
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putString("selected", selectedId)
        state.putString("editing", editingId)
        state.putString("key", selectedKey)
        importTarget?.let {
            state.putString("import_id", it.setId)
            state.putString("import_key", it.key)
            state.putBoolean("import_pressed", it.pressed)
        }
        super.onSaveInstanceState(state)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun notifyError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** Serializes writes, refreshes list/counts and decodes previews off the UI thread. */
    private fun perform(action: () -> String?) {
        if (busy) return
        busy = true
        worker.execute {
            try {
                val nextEditor = action()
                val listing = ControlSkinStore.list(workdir)
                val summary = listing.associate { set ->
                    set.id to ControlSkinStore.KEYS.count { key ->
                        ControlSkinStore.image(set.folder, key, false) != null
                    }
                }
                val folder = listing.firstOrNull { it.id == nextEditor }?.folder
                val images = if (folder == null) emptyMap() else ControlSkinStore.KEYS.associateWith { key ->
                    Images(
                        thumbnail(ControlSkinStore.image(folder, key, false)),
                        thumbnail(ControlSkinStore.image(folder, key, true)),
                        ControlSkinStore.mode(folder, key),
                    )
                }
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    sets = listing
                    counts = summary
                    previews = images
                    editingId = if (folder != null) nextEditor else null
                    if (editingId == null) selectedKey = null
                    busy = false
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        busy = false
                        notifyError(error.message ?: getString(R.string.control_skin_operation_failed))
                    }
                }
            }
        }
    }

    private fun openSet(id: String) {
        if (busy) return
        editingId = id
        selectedKey = null
        perform { id }
    }

    private fun choose(id: String?) {
        selectedId = id
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SELECTED, id))
        finish()
    }

    private fun editName(action: NameAction, id: String?, initial: String = "") {
        namePrompt = NamePrompt(action, id, initial)
    }

    private fun applyName(prompt: NamePrompt, name: String) {
        perform {
            when (prompt.action) {
                NameAction.CREATE -> {
                    val created = ControlSkinStore.create(workdir, name)
                    // Creating a set from a MIDlet also selects it, but keeps editing open.
                    runOnUiThread {
                        selectedId = created.id
                        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SELECTED, created.id))
                    }
                    created.id
                }
                NameAction.RENAME -> {
                    val id = requireNotNull(prompt.setId)
                    ControlSkinStore.rename(workdir, id, name)
                    editingId
                }
                NameAction.DUPLICATE -> {
                    val id = requireNotNull(prompt.setId)
                    ControlSkinStore.duplicate(workdir, id, name).id
                }
            }
        }
    }

    private fun removeSet(id: String) {
        perform {
            ControlSkinStore.delete(workdir, id)
            runOnUiThread {
                if (selectedId == id) {
                    selectedId = null
                    // Back from the library must propagate deletion to the MIDlet's draft.
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SELECTED, null as String?))
                }
            }
            null
        }
    }

    private fun updateMode(setId: String, key: String, mode: String) {
        perform {
            ControlSkinStore.setMode(workdir, setId, key, mode)
            setId
        }
    }

    private fun removeImage(setId: String, key: String, pressed: Boolean) {
        perform {
            ControlSkinStore.removeImage(workdir, setId, key, pressed)
            setId
        }
    }

    private fun pickImage(setId: String, key: String, pressed: Boolean) {
        importTarget = ImportTarget(setId, key, pressed)
        picker.launch(arrayOf("image/png", "image/webp"))
    }

    private fun back() {
        if (busy) return
        if (selectedKey != null) selectedKey = null
        else if (editingId != null) {
            editingId = null
            previews = emptyMap()
        } else finish()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ManagerScreen() {
        BackHandler(enabled = busy || editingId != null) { if (!busy) back() }
        val currentSet = sets.firstOrNull { it.id == editingId }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(currentSet?.name ?: stringResource(R.string.control_skin_title),
                        maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = ::back, enabled = !busy) {
                            Icon(painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        if (currentSet == null) {
                            IconButton(onClick = { editName(NameAction.CREATE, null) }, enabled = !busy) {
                                Icon(painterResource(R.drawable.ic_add),
                                    contentDescription = stringResource(R.string.control_skin_create))
                            }
                        } else {
                            Box {
                                IconButton(onClick = { menuId = currentSet.id }, enabled = !busy) {
                                    Icon(painterResource(R.drawable.ic_more_vert),
                                        contentDescription = stringResource(R.string.control_skin_manage))
                                }
                                SetMenu(currentSet, menuId == currentSet.id, onDismiss = { menuId = null })
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (currentSet == null) {
                    Library(modifier = Modifier.fillMaxSize())
                } else {
                    Editor(modifier = Modifier.fillMaxSize())
                }
                if (busy) LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                )
            }
        }

        NameDialog()
        val target = deleteId?.let { id -> sets.firstOrNull { it.id == id } }
        if (target != null) {
            AlertDialog(
                onDismissRequest = { deleteId = null },
                title = { Text(stringResource(R.string.control_skin_delete)) },
                text = { Text(stringResource(R.string.control_skin_delete_warning)) },
                confirmButton = {
                    TextButton(onClick = { deleteId = null; removeSet(target.id) }) {
                        Text(stringResource(R.string.control_skin_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteId = null }) { Text(stringResource(android.R.string.cancel)) }
                },
            )
        }

        val key = selectedKey
        if (currentSet != null && key != null) {
            ButtonSheet(currentSet, key)
        }
    }

    @Composable
    private fun Library(modifier: Modifier = Modifier) {
        LazyColumn(
            modifier = modifier,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                SetRow(
                    name = stringResource(R.string.control_skin_default),
                    description = null,
                    selected = selectedId == null,
                    onSelect = { choose(null) },
                    onMenu = null,
                )
            }
            items(sets, key = { it.id }) { set ->
                SetRow(
                    name = set.name,
                    description = stringResource(R.string.control_skin_count, counts[set.id] ?: 0),
                    selected = selectedId == set.id,
                    onSelect = { choose(set.id) },
                    onMenu = { menuId = set.id },
                    menu = {
                        SetMenu(set, menuId == set.id, onDismiss = { menuId = null })
                    },
                )
            }
            if (sets.isEmpty() && !busy) {
                item {
                    Text(stringResource(R.string.control_skin_empty),
                        modifier = Modifier.padding(top = 16.dp, start = 8.dp, end = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    @Composable
    private fun SetRow(
        name: String,
        description: String?,
        selected: Boolean,
        onSelect: () -> Unit,
        onMenu: (() -> Unit)?,
        menu: (@Composable () -> Unit)? = null,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .selectable(selected = selected, enabled = !busy, role = Role.RadioButton, onClick = onSelect)
                .heightIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = !busy)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(name, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onMenu != null) {
                Box {
                    IconButton(onClick = onMenu, enabled = !busy) {
                        Icon(painterResource(R.drawable.ic_more_vert),
                            contentDescription = stringResource(R.string.control_skin_manage_named, name))
                    }
                    menu?.invoke()
                }
            }
        }
    }

    @Composable
    private fun SetMenu(
        set: ControlSkinStore.SetInfo,
        expanded: Boolean,
        onDismiss: () -> Unit,
    ) {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.control_skin_edit)) },
                onClick = { onDismiss(); openSet(set.id) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.control_skin_rename)) },
                onClick = { onDismiss(); editName(NameAction.RENAME, set.id, set.name) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.control_skin_duplicate)) },
                onClick = { onDismiss(); editName(NameAction.DUPLICATE, set.id, set.name + " Copy") },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.control_skin_delete)) },
                onClick = { onDismiss(); deleteId = set.id },
            )
        }
    }

    @Composable
    private fun Editor(modifier: Modifier) {
        Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            KeyGroup(stringResource(R.string.control_skin_numeric), numeric)
            KeyGroup(stringResource(R.string.control_skin_navigation), navigation)
            KeyGroup(stringResource(R.string.control_skin_other), other)
        }
    }

    @Composable
    private fun KeyGroup(title: String, rows: List<List<String>>) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            rows.forEach { keys ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    keys.forEach { key ->
                        val visual = previews[key]
                        Surface(
                            modifier = Modifier.weight(1f).height(84.dp)
                                .clickable(enabled = !busy, role = Role.Button) { selectedKey = key }
                                .semantics {
                                    contentDescription = getString(R.string.control_skin_edit_named,
                                        when (key) {
                                            "star" -> "*"
                                            "pound" -> "#"
                                            else -> key.replace('_', ' ')
                                        })
                                },
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = if (visual?.normal != null)
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                        ) {
                            Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                                KeyPreview(key, visual, pressed = false, modifier = Modifier.size(58.dp))
                            }
                        }
                    }
                    repeat(3 - keys.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    @Composable
    private fun KeyPreview(key: String, images: Images?, pressed: Boolean, modifier: Modifier) {
        val bitmap = if (pressed && images?.pressed != null && images.normal != null)
            images.pressed else images?.normal
        val full = images?.mode == "full" && images.normal != null
        val shape = when (intent.getIntExtra(EXTRA_KEY_SHAPE, 2)) {
            0 -> androidx.compose.foundation.shape.CircleShape
            1 -> androidx.compose.ui.graphics.RectangleShape
            else -> androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
        }
        Box(modifier, contentAlignment = Alignment.Center) {
            if (!full) {
                val bg = intent.getIntExtra(
                    if (pressed) EXTRA_KEY_BG_PRESSED else EXTRA_KEY_BG, 0x3F3F3F)
                val fg = intent.getIntExtra(
                    if (pressed) EXTRA_KEY_FG_PRESSED else EXTRA_KEY_FG, 0xFFFFFF)
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = shape,
                    color = Color(0xFF000000.toInt() or bg),
                    border = BorderStroke(1.dp, Color(0xFF000000.toInt()
                        or intent.getIntExtra(EXTRA_KEY_OUTLINE, 0xFFFFFF))),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (bitmap == null) {
                            Text(label(key), color = Color(0xFF000000.toInt() or fg),
                                style = if (key.startsWith("soft_") || key == "fire")
                                    MaterialTheme.typography.labelMedium
                                else MaterialTheme.typography.titleMedium,
                                maxLines = 1)
                        } else {
                            Image(bitmap.asImageBitmap(), contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(0.8f))
                        }
                    }
                }
            } else if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        alpha = if (pressed && images.pressed == null) 0.75f else 1f
                    })
            }
        }
    }

    @Composable
    private fun label(key: String): String = when (key) {
        "star" -> "*"
        "pound" -> "#"
        "up_left" -> "↖"
        "up" -> "↑"
        "up_right" -> "↗"
        "left" -> "←"
        "right" -> "→"
        "down_left" -> "↙"
        "down" -> "↓"
        "down_right" -> "↘"
        "soft_left" -> stringResource(R.string.control_skin_soft_left)
        "soft_right" -> stringResource(R.string.control_skin_soft_right)
        "fire" -> stringResource(R.string.control_skin_fire)
        "menu" -> stringResource(R.string.control_skin_menu)
        else -> key.uppercase()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ButtonSheet(set: ControlSkinStore.SetInfo, key: String) {
        val images = previews[key]
        val mode = images?.mode ?: "icon"
        val normalAvailable = images?.normal != null
        ModalBottomSheet(onDismissRequest = { selectedKey = null }) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.78f).dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.control_skin_edit_named, label(key)),
                    style = MaterialTheme.typography.titleLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        "icon" to stringResource(R.string.control_skin_icon),
                        "full" to stringResource(R.string.control_skin_full),
                    ).forEachIndexed { index, (value, title) ->
                        SegmentedButton(
                            selected = mode == value,
                            onClick = { updateMode(set.id, key, value) },
                            enabled = !busy,
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        ) { Text(title, maxLines = 1) }
                    }
                }
                ImageSlot(
                    title = stringResource(R.string.control_skin_normal_image),
                    key = key, images = images, pressed = false,
                    hasImage = normalAvailable,
                    enabled = !busy,
                    onPick = { pickImage(set.id, key, false) },
                    onRemove = { removeImage(set.id, key, false) },
                )
                ImageSlot(
                    title = stringResource(R.string.control_skin_pressed_image),
                    key = key, images = images, pressed = true,
                    hasImage = images?.pressed != null,
                    enabled = !busy && normalAvailable,
                    onPick = { pickImage(set.id, key, true) },
                    onRemove = { removeImage(set.id, key, true) },
                    hint = if (!normalAvailable) stringResource(R.string.control_skin_pressed_requires_normal)
                        else if (images?.pressed == null) stringResource(R.string.control_skin_pressed_auto)
                        else null,
                )
            }
        }
    }

    @Composable
    private fun ImageSlot(
        title: String,
        key: String,
        images: Images?,
        pressed: Boolean,
        hasImage: Boolean,
        enabled: Boolean,
        onPick: () -> Unit,
        onRemove: () -> Unit,
        hint: String? = null,
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                KeyPreview(key, images, pressed, Modifier.size(68.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    TextButton(onClick = onPick, enabled = enabled) {
                        Text(stringResource(if (hasImage) R.string.control_skin_change_image
                            else R.string.control_skin_pick_image))
                    }
                    hint?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (hasImage) {
                    IconButton(onClick = onRemove, enabled = !busy) {
                        Icon(painterResource(R.drawable.ic_delete),
                            contentDescription = stringResource(R.string.control_skin_remove_image))
                    }
                }
            }
        }
    }

    @Composable
    private fun NameDialog() {
        val prompt = namePrompt ?: return
        var input by androidx.compose.runtime.remember(prompt) { mutableStateOf(prompt.initial) }
        AlertDialog(
            onDismissRequest = { namePrompt = null },
            title = {
                Text(stringResource(when (prompt.action) {
                    NameAction.CREATE -> R.string.control_skin_create
                    NameAction.RENAME -> R.string.control_skin_rename
                    NameAction.DUPLICATE -> R.string.control_skin_duplicate
                }))
            },
            text = {
                OutlinedTextField(input, { input = it }, singleLine = true,
                    label = { Text(stringResource(R.string.control_skin_name)) })
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && input.isNotBlank(),
                    onClick = { namePrompt = null; applyName(prompt, input) },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { namePrompt = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    private fun thumbnail(file: File?): Bitmap? {
        if (file == null || !file.isFile() || file.length() > 8L * 1024 * 1024) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 128) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } catch (_: Exception) { null }
    }
}
