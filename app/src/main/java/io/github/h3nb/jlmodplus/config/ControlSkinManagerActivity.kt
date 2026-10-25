package io.github.h3nb.jlmodplus.config

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import io.github.h3nb.jlmodplus.util.EdgeToEdgeCompat
import java.io.File
import java.util.concurrent.Executors

/** Global skin asset editor; returns only the selected set ID to the MIDlet config draft. */
class ControlSkinManagerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_WORKDIR = "control_skin_workdir"
        const val EXTRA_SELECTED = "control_skin_selected"
    }

    private lateinit var workdir: File
    private val worker = Executors.newSingleThreadExecutor()
    private var sets by mutableStateOf<List<ControlSkinStore.SetInfo>>(emptyList())
    private var editingId by mutableStateOf<String?>(null)
    private var activeId by mutableStateOf<String?>(null)
    private var selectedKey by mutableStateOf<String?>(null)
    private var operation by mutableStateOf<String?>(null)
    private var nameDraft by mutableStateOf("")
    private var deletePrompt by mutableStateOf(false)
    private var generation by mutableIntStateOf(0)
    private var fileTarget: Triple<String, String, Boolean>? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = fileTarget
        fileTarget = null
        if (uri == null || target == null) return@registerForActivityResult
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
        modify {
            contentResolver.openInputStream(uri)?.use {
                ControlSkinStore.importImage(workdir, target.first, target.second, target.third, it, suffix)
            } ?: error("Cannot read image")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val directory = intent.getStringExtra(EXTRA_WORKDIR)
        if (directory == null) { finish(); return }
        workdir = File(directory)
        activeId = savedInstanceState?.getString("active") ?: intent.getStringExtra(EXTRA_SELECTED)
        editingId = savedInstanceState?.getString("editing")
        selectedKey = savedInstanceState?.getString("key")
        EdgeToEdgeCompat.enableForComposeSurface(this)
        val view = ComposeView(this)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        view.setContent { JLModPlusTheme { Screen() } }
        setContentView(view)
        reload()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("active", activeId)
        outState.putString("editing", editingId)
        outState.putString("key", selectedKey)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun notifyError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun modify(action: () -> Unit) {
        worker.execute {
            try {
                action()
                val updated = ControlSkinStore.list(workdir)
                runOnUiThread { sets = updated; generation++ }
            } catch (e: Exception) {
                runOnUiThread { notifyError(e.message ?: getString(R.string.control_skin_operation_failed)) }
            }
        }
    }

    private fun reload() {
        modify { }
    }

    private fun select(id: String?) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SELECTED, id))
        finish()
    }

    private fun useSet(id: String?) { activeId = id; select(id) }

    private fun create(name: String) {
        modify {
            val created = ControlSkinStore.create(workdir, name)
            runOnUiThread { editingId = created.id }
        }
    }

    private fun rename(name: String) {
        val id = editingId ?: return
        modify { ControlSkinStore.rename(workdir, id, name) }
    }

    private fun duplicate(name: String) {
        val id = editingId ?: return
        modify {
            val copied = ControlSkinStore.duplicate(workdir, id, name)
            runOnUiThread { editingId = copied.id }
        }
    }

    private fun delete() {
        val id = editingId ?: return
        modify {
            ControlSkinStore.delete(workdir, id)
            runOnUiThread {
                if (activeId == id) activeId = null
                editingId = null
                selectedKey = null
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        val currentSet = sets.firstOrNull { it.id == editingId }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(currentSet?.name ?: stringResource(R.string.control_skin_title)) },
                    navigationIcon = {
                        TextButton(onClick = {
                            if (editingId != null) { editingId = null; selectedKey = null }
                            else finish()
                        }) { Text(stringResource(R.string.action_back)) }
                    },
                )
            },
        ) { padding ->
            if (currentSet == null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = {
                        operation = "create"
                        nameDraft = ""
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.control_skin_create))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { useSet(null) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.control_skin_default), modifier = Modifier.weight(1f))
                        if (activeId == null) Text("✓")
                    }
                    sets.forEach { set ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(set.name, modifier = Modifier.weight(1f))
                            if (activeId == set.id) Text("✓")
                            TextButton(onClick = { editingId = set.id }) {
                                Text(stringResource(R.string.control_skin_edit))
                            }
                            TextButton(onClick = { useSet(set.id) }) {
                                Text(stringResource(R.string.control_skin_use))
                            }
                        }
                    }
                }
            } else {
                val currentId = currentSet.id
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(stringResource(R.string.control_skin_global_note),
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { useSet(currentId) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.control_skin_use))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            operation = "rename"; nameDraft = currentSet.name
                        }) { Text(stringResource(R.string.control_skin_rename)) }
                        TextButton(onClick = {
                            operation = "duplicate"; nameDraft = currentSet.name + " Copy"
                        }) { Text(stringResource(R.string.control_skin_duplicate)) }
                        TextButton(onClick = { deletePrompt = true }) {
                            Text(stringResource(R.string.control_skin_delete))
                        }
                    }
                    Text(stringResource(R.string.control_skin_buttons),
                        style = MaterialTheme.typography.titleMedium)
                    ControlSkinStore.KEYS.toList().chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { key ->
                                val image = remember(currentId, key, generation) {
                                    ControlSkinStore.image(currentSet.folder, key, false)?.let(::thumbnail)
                                }
                                Column(
                                    modifier = Modifier.weight(1f).clickable { selectedKey = key }.padding(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    if (image != null) {
                                        Image(image.asImageBitmap(), contentDescription = null,
                                            modifier = Modifier.size(48.dp))
                                    } else {
                                        Text(key.uppercase(), modifier = Modifier.padding(14.dp))
                                    }
                                    Text(key.replace('_', ' '), style = MaterialTheme.typography.labelSmall,
                                        maxLines = 2)
                                }
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        if (operation != null) {
            val action = operation!!
            AlertDialog(
                onDismissRequest = { operation = null },
                title = { Text(stringResource(
                    when (action) {
                        "rename" -> R.string.control_skin_rename
                        "duplicate" -> R.string.control_skin_duplicate
                        else -> R.string.control_skin_create
                    },
                )) },
                text = {
                    OutlinedTextField(nameDraft, { nameDraft = it },
                        label = { Text(stringResource(R.string.control_skin_name)) },
                        singleLine = true)
                },
                confirmButton = {
                    TextButton(enabled = nameDraft.isNotBlank(), onClick = {
                        val name = nameDraft
                        operation = null
                        when (action) {
                            "rename" -> rename(name)
                            "duplicate" -> duplicate(name)
                            else -> create(name)
                        }
                    }) { Text(stringResource(android.R.string.ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { operation = null }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }
        if (deletePrompt) {
            AlertDialog(
                onDismissRequest = { deletePrompt = false },
                title = { Text(stringResource(R.string.control_skin_delete)) },
                text = { Text(stringResource(R.string.control_skin_delete_warning)) },
                confirmButton = {
                    TextButton(onClick = { deletePrompt = false; delete() }) {
                        Text(stringResource(R.string.control_skin_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deletePrompt = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }

        val key = selectedKey
        if (currentSet != null && key != null) {
            val setId = currentSet.id
            val mode = remember(setId, key, generation) { ControlSkinStore.mode(currentSet.folder, key) }
            val normal = remember(setId, key, generation) {
                ControlSkinStore.image(currentSet.folder, key, false)?.let(::thumbnail)
            }
            val pressed = remember(setId, key, generation) {
                ControlSkinStore.image(currentSet.folder, key, true)?.let(::thumbnail)
            }
            AlertDialog(
                onDismissRequest = { selectedKey = null },
                title = { Text("key_$key") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.control_skin_mode))
                        Row {
                            TextButton(onClick = { modify { ControlSkinStore.setMode(workdir, setId, key, "icon") } }) {
                                Text((if (mode == "icon") "✓ " else "") +
                                    stringResource(R.string.control_skin_icon))
                            }
                            TextButton(onClick = { modify { ControlSkinStore.setMode(workdir, setId, key, "full") } }) {
                                Text((if (mode == "full") "✓ " else "") +
                                    stringResource(R.string.control_skin_full))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            normal?.let {
                                Image(it.asImageBitmap(), contentDescription = null,
                                    modifier = Modifier.size(48.dp))
                            }
                            TextButton(onClick = {
                                fileTarget = Triple(setId, key, false)
                                picker.launch(arrayOf("image/png", "image/webp"))
                            }) { Text(stringResource(R.string.control_skin_normal_image)) }
                            if (normal != null) {
                                TextButton(onClick = { modify { ControlSkinStore.removeImage(workdir, setId, key, false) } }) {
                                    Text(stringResource(R.string.control_skin_remove_image))
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            pressed?.let {
                                Image(it.asImageBitmap(), contentDescription = null,
                                    modifier = Modifier.size(48.dp))
                            }
                            TextButton(onClick = {
                                fileTarget = Triple(setId, key, true)
                                picker.launch(arrayOf("image/png", "image/webp"))
                            }) { Text(stringResource(R.string.control_skin_pressed_image)) }
                            if (pressed != null) {
                                TextButton(onClick = { modify { ControlSkinStore.removeImage(workdir, setId, key, true) } }) {
                                    Text("×")
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedKey = null }) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            )
        }
    }

    private fun thumbnail(file: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048) null
        else {
            val opts = BitmapFactory.Options()
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 96) sample *= 2
            opts.inSampleSize = sample
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }
    } catch (_: Exception) { null }
}
