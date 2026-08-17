from pathlib import Path
import re


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))


def replace_resource(path, name, value):
    p = Path(path)
    text = p.read_text()
    pattern = re.compile(r'(<string\s+name="' + re.escape(name) + r'"[^>]*>)(.*?)(</string>)', re.S)
    matches = list(pattern.finditer(text))
    if len(matches) != 1:
        raise SystemExit(f"{path}: expected one resource {name}, got {len(matches)}")
    p.write_text(pattern.sub(lambda m: m.group(1) + value + m.group(3), text, count=1))


bridge = Path('app/src/main/java/ru/playsoftware/j2meloader/config/ConfigComposeBridge.kt')
text = bridge.read_text()

# Pager + system-back support without adding a new dependency.
text = text.replace(
    'import android.content.res.Configuration\n',
    'import android.content.res.Configuration\nimport androidx.activity.OnBackPressedCallback\nimport androidx.appcompat.app.AppCompatActivity\n',
    1,
)
text = text.replace(
    'import androidx.compose.foundation.background\n',
    'import androidx.compose.foundation.background\nimport androidx.compose.foundation.pager.HorizontalPager\nimport androidx.compose.foundation.pager.rememberPagerState\n',
    1,
)
text = text.replace('import androidx.compose.runtime.LaunchedEffect\n', '', 1)
text = text.replace(
    'import androidx.compose.runtime.Composable\n',
    'import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.DisposableEffect\n',
    1,
)
text = text.replace(
    'import androidx.compose.runtime.remember\n',
    'import androidx.compose.runtime.remember\nimport androidx.compose.runtime.rememberCoroutineScope\n',
    1,
)
text = text.replace(
    'import androidx.compose.ui.platform.LocalDensity\n',
    'import androidx.compose.ui.platform.LocalDensity\nimport androidx.compose.ui.platform.LocalView\n',
    1,
)
text = text.replace(
    'import ru.playsoftware.j2meloader.R\n',
    'import kotlinx.coroutines.launch\nimport ru.playsoftware.j2meloader.R\n',
    1,
)

start = text.index('    val form = state.form\n', text.index('internal fun ConfigScreen('))
end = text.index('\n    colorPicker?.let { request ->', start)
new_layout = '''    val form = state.form
    var pendingAction by remember { mutableStateOf<ConfigAction?>(null) }
    var systemPropertiesEditorVisible by rememberSaveable { mutableStateOf(false) }
    val updateForm: (ConfigFormState) -> Unit = { next ->
        events.onFormChanged(next)
    }

    val destinations = ConfigDestination.values().toList()
    val initialDestinationIndex = initialDestination
        ?.let { destinations.indexOf(it).takeIf { index -> index >= 0 } }
        ?: 0
    val pagerState = rememberPagerState(
        initialPage = initialDestinationIndex,
        pageCount = { destinations.size },
    )
    val pagerScope = rememberCoroutineScope()
    val selectedDestination = destinations.getOrElse(pagerState.currentPage) { destinations.first() }
    val selectDestination: (ConfigDestination) -> Unit = { destination ->
        val index = destinations.indexOf(destination)
        if (index >= 0 && index != pagerState.currentPage) {
            pagerScope.launch { pagerState.animateScrollToPage(index) }
        }
    }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    if (systemPropertiesEditorVisible) {
        ConfigSystemPropertiesPage(
            value = form.systemProperties,
            onBack = { systemPropertiesEditorVisible = false },
            onSave = { value ->
                systemPropertiesEditorVisible = false
                updateForm(form.toBuilder().systemProperties(value).build())
            },
            modifier = modifier,
        )
    } else {
        Row(modifier = modifier.fillMaxSize()) {
            if (isLandscape) {
                ConfigNavigationRail(
                    destinations = destinations,
                    selected = selectedDestination,
                    onSelected = selectDestination,
                )
            }

            Scaffold(
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    ConfigTopBar(
                        title = title,
                        isProfile = isProfile,
                        onBack = { menuActions?.onBack() },
                        onStart = { menuActions?.onStart() },
                    )
                },
                bottomBar = {
                    // Keep the destination bar from floating above the IME while editing text.
                    if (!isLandscape && !imeVisible) {
                        ConfigNavigationBar(
                            destinations = destinations,
                            selected = selectedDestination,
                            onSelected = selectDestination,
                        )
                    }
                },
            ) { padding ->
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .consumeWindowInsets(padding),
                ) { page ->
                    val pageScrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(pageScrollState)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            ConfigDestinationContent(
                                destination = destinations[page],
                                state = state,
                                form = form,
                                onFormChanged = updateForm,
                                events = events,
                                isProfile = isProfile,
                                onRequestAction = { pendingAction = it },
                                onEditSystemProperties = { systemPropertiesEditorVisible = true },
                                modifier = Modifier.widthIn(max = 880.dp),
                            )
                        }
                    }
                }
            }
        }
    }
'''
text = text[:start] + new_layout + text[end:]

text = text.replace(
    '''    isProfile: Boolean,
    onRequestAction: (ConfigAction) -> Unit,
    modifier: Modifier = Modifier,
''',
    '''    isProfile: Boolean,
    onRequestAction: (ConfigAction) -> Unit,
    onEditSystemProperties: () -> Unit,
    modifier: Modifier = Modifier,
''',
    1,
)
text = text.replace(
    '                SystemSection(form, onFormChanged, events, !isProfile, onRequestAction)\n',
    '                SystemSection(form, onFormChanged, events, !isProfile, onRequestAction, onEditSystemProperties)\n',
    1,
)
text = text.replace(
    '''    events: ConfigFormEvents,
    showClearData: Boolean,
    onRequestAction: (ConfigAction) -> Unit,
) {
''',
    '''    events: ConfigFormEvents,
    showClearData: Boolean,
    onRequestAction: (ConfigAction) -> Unit,
    onEditSystemProperties: () -> Unit,
) {
''',
    1,
)
text = text.replace(
    '''        ConfigSystemPropertiesPreference(
  value = form.systemProperties,
  onValueChange = { value -> onFormChanged(form.toBuilder().systemProperties(value).build()) },
        )
''',
    '''        ConfigSystemPropertiesPreference(
  value = form.systemProperties,
  onClick = onEditSystemProperties,
        )
''',
    1,
)

old_props_start = text.index('@Composable\nprivate fun ConfigSystemPropertiesPreference(')
old_props_end = text.index('\n@Composable\nprivate fun SettingActionRow(', old_props_start)
new_props = '''@Composable
private fun ConfigSystemPropertiesPreference(
    value: String,
    onClick: () -> Unit,
) {
    ConfigValuePreference(
        title = stringResource(R.string.config_edit_system_properties),
        description = stringResource(R.string.config_help_system_properties),
        value = stringResource(R.string.config_system_properties_value, value.lineSequence().count { it.isNotBlank() }),
        message = stringResource(R.string.config_system_properties_info),
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfigSystemPropertiesPage(
    value: String,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val hostView = LocalView.current
    DisposableEffect(hostView, onBack) {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        }
        (hostView.context as? AppCompatActivity)?.onBackPressedDispatcher?.addCallback(callback)
        onDispose { callback.remove() }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.PREF_SYS_PROPS)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(androidx.appcompat.R.string.abc_action_bar_up_description),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { onSave(draft) }) {
                        Text(stringResource(R.string.save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.config_help_system_properties_long),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ConfigMessageBlock(stringResource(R.string.config_system_properties_info))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
        }
    }
}
'''
text = text[:old_props_start] + new_props + text[old_props_end:]
bridge.write_text(text)

# Stronger hierarchy: section > option > description. Descriptions remain naturally wrapping.
components = Path('app/src/main/java/ru/playsoftware/j2meloader/config/ConfigPreferenceComponents.kt')
ctext = components.read_text()
ctext = ctext.replace(
    '''            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
''',
    '''            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
''',
    1,
)
ctext = ctext.replace(
    '            Text(text = title, style = MaterialTheme.typography.bodyLarge)\n',
    '            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)\n',
    1,
)
ctext = ctext.replace(
    '        Text(text = title, style = MaterialTheme.typography.bodyLarge)\n',
    '        Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)\n',
    1,
)
ctext = ctext.replace(
    '''                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
''',
    '''                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = when {
''',
    1,
)
ctext = ctext.replace(
    '            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)\n',
    '            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)\n',
    1,
)
components.write_text(ctext)

# Compose owns system-bar insets on the key-mapping screen. Do not pad the host View as well.
key_activity = Path('app/src/main/java/ru/playsoftware/j2meloader/settings/KeyMapperActivity.java')
ktext = key_activity.read_text()
ktext = ktext.replace('EdgeToEdgeCompat.enableIfSupported(this);', 'EdgeToEdgeCompat.enableForComposeSurface(this);', 1)
ktext = ktext.replace('\n\t\tEdgeToEdgeCompat.protectHostContent(this);', '', 1)
key_activity.write_text(ktext)

key_bridge = Path('app/src/main/java/ru/playsoftware/j2meloader/settings/KeyMapperComposeBridge.kt')
kctext = key_bridge.read_text()
kctext = kctext.replace(
    'import androidx.compose.foundation.layout.Spacer\n',
    'import androidx.compose.foundation.layout.Spacer\nimport androidx.compose.foundation.layout.WindowInsets\n',
    1,
)
kctext = kctext.replace(
    'import androidx.compose.foundation.layout.size\n',
    'import androidx.compose.foundation.layout.size\nimport androidx.compose.foundation.layout.safeDrawing\n',
    1,
)
kctext = kctext.replace(
    '''    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
''',
    '''    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
''',
    1,
)
key_bridge.write_text(kctext)

# Config-specific copy: universal app/MIDlet terminology and accurate event-queue semantics.
en_copy = {
    'config_delete_game_data': 'Delete app data',
    'config_delete_game_data_summary': 'Permanently delete saves and data created by this app',
    'config_message_reset_settings': 'Reset all emulator settings for this app to their defaults? App data and the custom button layout will not be deleted.',
    'config_message_delete_game_data': 'Permanently delete all saves and data created by this app? Emulator settings will not be deleted.',
    'profile_default_badge': 'Default for new apps',
    'profile_default_template_summary': 'Default template for new apps',
    'profile_templates_summary': 'Tap a template to apply it. Use the menu to rename, delete, or make it the default for new apps.',
    'profile_delete_template_message': 'Delete template “%1$s”? This does not delete any app data.',
    'config_help_orientation': 'Controls the screen orientation requested while the app is running.',
    'config_help_scale_type': 'Determines how the app screen fits into the available display area.',
    'config_help_scale_ratio': 'Adjusts display size without changing the app’s internal screen resolution.',
    'config_help_virtual_keyboard': 'Shows the J2ME keypad over the app screen.',
    'config_help_touch_input': 'Allows compatible apps to receive direct touchscreen input.',
    'config_help_background': 'Color shown around the app screen when it does not fill the display.',
    'config_help_skin': 'Optional image or frame rendered around the app screen.',
    'config_help_screen_gravity': 'Positions the app screen when unused space remains.',
    'config_help_screen_padding': 'Adds space between the app screen and the edge of the display area.',
    'config_help_immediate': 'Processes Java ME events as soon as they arrive instead of queuing them. May improve responsiveness, but can cause unpredictable behavior.',
    'config_help_shader': 'Applies a post-processing shader to the rendered app screen.',
    'config_help_force_fullscreen': 'Forces the app surface to use the full available display area.',
    'config_help_show_fps': 'Displays the current frame rate while the app is running.',
    'config_help_generic_toggle': 'Enable or disable this behavior for the current app.',
    'config_help_system_properties_long': 'Edit one property per line using “name: value”. Incorrect values can change application behavior, so modify these only when compatibility requires it.',
    'config_system_properties_info': 'Most apps do not need changes here.',
    'config_help_reset_layout': 'Restore the virtual keypad layout without resetting the app’s other emulator settings.',
    'config_help_skip_resume': 'Skip the Java ME resume callback when returning to the app. Use this only for compatibility with apps that misbehave after resume.',
}
id_copy = {
    'config_delete_game_data': 'Hapus data aplikasi',
    'config_delete_game_data_summary': 'Hapus permanen save dan data yang dibuat aplikasi ini',
    'config_message_reset_settings': 'Atur ulang semua pengaturan emulator untuk aplikasi ini ke bawaan? Data aplikasi dan tata letak tombol kustom tidak akan dihapus.',
    'config_message_delete_game_data': 'Hapus permanen semua save dan data yang dibuat aplikasi ini? Pengaturan emulator tidak akan dihapus.',
    'profile_default_badge': 'Bawaan untuk aplikasi baru',
    'profile_default_template_summary': 'Template default untuk aplikasi baru',
    'profile_templates_summary': 'Ketuk template untuk menerapkannya. Gunakan menu untuk mengganti nama, menghapus, atau menjadikannya default untuk aplikasi baru.',
    'profile_delete_template_message': 'Hapus template “%1$s”? Tindakan ini tidak menghapus data aplikasi.',
    'config_help_orientation': 'Menentukan orientasi layar yang diminta saat aplikasi berjalan.',
    'config_help_scale_type': 'Menentukan bagaimana layar aplikasi disesuaikan dengan area tampilan yang tersedia.',
    'config_help_scale_ratio': 'Menyesuaikan ukuran tampilan tanpa mengubah resolusi layar internal aplikasi.',
    'config_help_virtual_keyboard': 'Menampilkan keypad J2ME di atas layar aplikasi.',
    'config_help_touch_input': 'Mengizinkan aplikasi yang kompatibel menerima input langsung dari layar sentuh.',
    'config_help_background': 'Warna area di sekitar layar aplikasi ketika tidak memenuhi seluruh tampilan.',
    'config_help_skin': 'Gambar atau bingkai opsional yang ditampilkan di sekitar layar aplikasi.',
    'config_help_screen_gravity': 'Menentukan posisi layar aplikasi ketika masih ada ruang kosong.',
    'config_help_screen_padding': 'Menambahkan jarak antara layar aplikasi dan tepi area tampilan.',
    'config_help_immediate': 'Memproses event Java ME segera saat diterima tanpa antrean. Dapat meningkatkan respons, tetapi bisa menyebabkan perilaku yang tidak terduga.',
    'config_help_shader': 'Menerapkan shader pascaproses pada tampilan aplikasi.',
    'config_help_force_fullscreen': 'Memaksa permukaan aplikasi menggunakan seluruh area tampilan yang tersedia.',
    'config_help_show_fps': 'Menampilkan frame rate saat aplikasi berjalan.',
    'config_help_generic_toggle': 'Aktifkan atau nonaktifkan perilaku ini untuk aplikasi saat ini.',
    'config_help_system_properties_long': 'Edit satu properti per baris dengan format “nama: nilai”. Nilai yang salah dapat mengubah perilaku aplikasi, jadi ubah hanya jika diperlukan untuk kompatibilitas.',
    'config_system_properties_info': 'Sebagian besar aplikasi tidak memerlukan perubahan di sini.',
    'config_help_reset_layout': 'Pulihkan tata letak keypad virtual tanpa mengatur ulang pengaturan emulator aplikasi lainnya.',
    'config_help_skip_resume': 'Lewati callback resume Java ME saat kembali ke aplikasi. Gunakan hanya untuk kompatibilitas aplikasi yang bermasalah setelah resume.',
}
for name, value in en_copy.items():
    replace_resource('app/src/main/res/values/strings_config_redesign.xml', name, value)
for name, value in id_copy.items():
    replace_resource('app/src/main/res/values-in/strings_config_redesign.xml', name, value)

# Sentence-case Config labels. Hierarchy is expressed by typography rather than inconsistent capitalization.
en_labels = {
    'PREF_SCREEN_OPTIONS': 'Screen settings',
    'PREF_FONT_OPTIONS': 'Font',
    'pref_input_devices_title': 'Input devices',
    'PREF_SYS_PROPS': 'System properties',
    'pref_button_shape_round': 'Rounded rectangle',
    'pref_button_shape_title': 'Button shape',
    'PREF_FONT_ANTI_ALIASING': 'Anti-aliasing',
    'PREF_FONT_SIZE_IN_SP': 'Use scaled pixels',
    'PREF_FORCE_FULLSCREEN': 'Force fullscreen',
    'pref_graphics_mode_title': 'Graphics mode',
    'PREF_IMMEDIATE': 'Immediate processing mode',
    'PREF_LIMIT_FPS': 'Limit FPS',
    'pref_map_keys': 'Key mappings',
    'PREF_ORIENTATION': 'Screen orientation',
    'pref_screen_gravity': 'Screen position',
    'pref_screen_padding_title': 'Screen padding',
    'pref_screen_scale_type': 'Scale type',
    'pref_screen_scale_type_fill': 'Fill window (ignore aspect ratio)',
    'pref_screen_scale_type_fit': 'Fit to window',
    'pref_screen_scale_type_none': 'As is',
    'PREF_SHOW_FPS': 'Show FPS',
    'pref_skin_not_set': 'Not set',
    'pref_skip_resume_call': 'Skip resume callback',
    'PREF_TOUCH_INPUT': 'Touch input',
    'PREF_VIRTUAL_KEYBOARD_OPTIONS': 'Virtual keyboard',
    'PREF_VK_FEEDBACK': 'Haptic feedback',
    'PREF_VK_FORCE_OPACITY': 'Force opacity for off-screen keys',
    'PREF_VK_HIDE_DELAY': 'Hide delay',
    'PREF_VK_SEL_BACK': 'Buttons (pressed)',
    'PREF_VK_SEL_FORE': 'Labels (pressed)',
    'parallel_screen_redrawing': 'Parallel screen redraw',
    'RESET_LAYOUT_CMD': 'Reset key layout',
}
id_labels = {
    'PREF_SCREEN_OPTIONS': 'Pengaturan layar',
    'PREF_FONT_OPTIONS': 'Font',
    'pref_input_devices_title': 'Perangkat input',
    'PREF_SYS_PROPS': 'Properti sistem',
    'PREF_BACKGROUND': 'Latar belakang',
    'pref_button_shape_round': 'Kotak membulat',
    'pref_button_shape_title': 'Bentuk tombol',
    'PREF_FONT_ANTI_ALIASING': 'Anti-aliasing',
    'PREF_FONT_SIZE_IN_SP': 'Gunakan piksel berskala',
    'PREF_FORCE_FULLSCREEN': 'Paksa layar penuh',
    'pref_graphics_mode_title': 'Mode grafis',
    'PREF_IMMEDIATE': 'Mode pemrosesan langsung',
    'PREF_LIMIT_FPS': 'Batasi FPS',
    'pref_map_keys': 'Pemetaan tombol',
    'PREF_ORIENTATION': 'Orientasi layar',
    'pref_screen_gravity': 'Posisi layar',
    'pref_screen_padding_title': 'Jarak tepi layar',
    'pref_screen_scale_type': 'Jenis skala',
    'pref_screen_scale_type_fill': 'Layar penuh (abaikan rasio aspek)',
    'pref_screen_scale_type_fit': 'Sesuaikan dengan layar',
    'PREF_SHOW_FPS': 'Tampilkan FPS',
    'pref_skip_resume_call': 'Lewati callback resume',
    'PREF_TOUCH_INPUT': 'Input sentuh',
    'PREF_VIRTUAL_KEYBOARD_OPTIONS': 'Papan ketik virtual',
    'PREF_VK_FEEDBACK': 'Umpan balik haptik',
    'PREF_VK_FORCE_OPACITY': 'Paksa opasitas tombol di luar layar',
    'PREF_VK_HIDE_DELAY': 'Sembunyikan setelah',
    'PREF_VK_OUTLINE': 'Garis tepi',
    'PREF_VK_SEL_BACK': 'Tombol (ditekan)',
    'PREF_VK_SEL_FORE': 'Label (ditekan)',
    'parallel_screen_redrawing': 'Gambar ulang layar paralel',
    'RESET_LAYOUT_CMD': 'Atur ulang tata letak tombol',
}
for name, value in en_labels.items():
    replace_resource('app/src/main/res/values/strings.xml', name, value)
for name, value in id_labels.items():
    replace_resource('app/src/main/res/values-in/strings.xml', name, value)

# Keep the legacy help text semantically aligned with EventQueue immediate mode and universal terminology.
replace_resource(
    'app/src/main/res/values/strings.xml',
    'help_message',
    '&#8226; Enabling filtering in some cases can greatly reduce performance. Disable this option if the app is too slow.&lt;BR>&#8226; Immediate processing mode bypasses the normal Java ME event queue. It may improve responsiveness, but can cause unpredictable behavior.',
)
replace_resource(
    'app/src/main/res/values-in/strings.xml',
    'help_message',
    '&#8226; Mengaktifkan filter dalam beberapa kasus dapat sangat menurunkan kinerja. Nonaktifkan opsi ini jika aplikasi terlalu lambat.&lt;BR&gt;&#8226; Mode pemrosesan langsung melewati antrean event Java ME normal. Mode ini dapat meningkatkan respons, tetapi bisa menyebabkan perilaku yang tidak terduga.',
)

# Tests: new sentence case, universal copy, full-page editor and horizontal swipe.
test = Path('app/src/androidTest/java/ru/playsoftware/j2meloader/config/ConfigComposeTest.kt')
ttext = test.read_text()
ttext = ttext.replace(
    'import androidx.compose.ui.test.onNodeWithText\n',
    'import androidx.compose.ui.test.onNodeWithText\nimport androidx.compose.ui.test.onRoot\n',
    1,
)
ttext = ttext.replace(
    'import androidx.compose.ui.test.performTextReplacement\n',
    'import androidx.compose.ui.test.performTextReplacement\nimport androidx.compose.ui.test.performTouchInput\nimport androidx.compose.ui.test.swipeLeft\nimport androidx.compose.ui.test.swipeRight\n',
    1,
)
replacements = {
    '"Screen Orientation"': '"Screen orientation"',
    '"Scale Type"': '"Scale type"',
    '"Screen Options"': '"Screen settings"',
    '"Font Options"': '"Font"',
    '"Input Devices"': '"Input devices"',
    '"System Properties"': '"System properties"',
    '"Touch Input"': '"Touch input"',
    '"JL-Mod Plus factory configuration · Default for new games"': '"JL-Mod Plus factory configuration · Default for new apps"',
    '"Reset all emulator settings for this game to their defaults? Game data and the custom button layout will not be deleted."': '"Reset all emulator settings for this app to their defaults? App data and the custom button layout will not be deleted."',
    '"Delete game data"': '"Delete app data"',
    '"Permanently delete all saves and data created by this game? Emulator settings will not be deleted."': '"Permanently delete all saves and data created by this app? Emulator settings will not be deleted."',
    '"Reset Keylayout"': '"Reset key layout"',
}
for old, new in replacements.items():
    ttext = ttext.replace(old, new)

old_system_test = '''        composeRule.onNodeWithContentDescription("System").performClick()
        composeRule.onNodeWithText("Edit system properties").performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("microedition.platform: updated\\n")
        composeRule.onNodeWithText("OK").performClick()
        assertEquals("microedition.platform: updated\\n", events.lastForm?.systemProperties)
'''
new_system_test = '''        composeRule.onNodeWithContentDescription("System").performClick()
        composeRule.onNodeWithText("Edit system properties").performClick()
        composeRule.onNodeWithText("System properties").assertExists()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("microedition.platform: updated\\n")
        composeRule.onNodeWithText("Save").performClick()
        assertEquals("microedition.platform: updated\\n", events.lastForm?.systemProperties)
'''
if old_system_test not in ttext:
    raise SystemExit('system properties test block not found')
ttext = ttext.replace(old_system_test, new_system_test, 1)

marker = '''    @Test
    fun profileEditorKeepsGeneralSettingsWithoutProfileWorkflow() {
'''
swipe_test = '''    @Test
    fun configDestinationsSupportHorizontalSwipe() {
        composeRule.setContent {
            JLModPlusTheme {
                ConfigScreen(sampleState(), RecordingConfigEvents())
            }
        }

        composeRule.onNodeWithText("Essential settings").assertExists()
        composeRule.onRoot().performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Screen settings").assertExists()

        composeRule.onRoot().performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Essential settings").assertExists()
    }

'''
if marker not in ttext:
    raise SystemExit('swipe test marker not found')
ttext = ttext.replace(marker, swipe_test + marker, 1)
test.write_text(ttext)

# Add a golden for the full-page System Properties editor.
screenshot = Path('app/src/screenshotTest/kotlin/ru/playsoftware/j2meloader/config/ConfigScreenshotTest.kt')
stext = screenshot.read_text()
marker = '''@PreviewTest
@Preview(name = "Config system properties", widthDp = 360, heightDp = 800, showBackground = true)
'''
editor_preview = '''@PreviewTest
@Preview(name = "Config system properties editor", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
fun ConfigSystemPropertiesEditorScreenshot() {
    JLModPlusTheme {
        ConfigSystemPropertiesPage(
            value = "microedition.platform: Sony Ericsson C510i\\nmicroedition.profiles: MIDP2.0\\n",
            onBack = {},
            onSave = {},
        )
    }
}

'''
if marker not in stext:
    raise SystemExit('system screenshot marker not found')
stext = stext.replace(marker, editor_preview + marker, 1)
screenshot.write_text(stext)

# A source-level regression guards the key-mapper inset ownership contract.
inset_test = Path('app/src/test/java/ru/playsoftware/j2meloader/settings/KeyMapperInsetContractTest.java')
inset_test.parent.mkdir(parents=True, exist_ok=True)
inset_test.write_text('''/*\n * Licensed under the Apache License, Version 2.0 (the "License");\n */\npackage ru.playsoftware.j2meloader.settings;\n\nimport static org.junit.Assert.assertFalse;\nimport static org.junit.Assert.assertTrue;\n\nimport java.nio.charset.StandardCharsets;\nimport java.nio.file.Files;\nimport java.nio.file.Path;\n\nimport org.junit.Test;\n\npublic class KeyMapperInsetContractTest {\n    @Test\n    public void composeKeyMapperOwnsInsetsWithoutHostPadding() throws Exception {\n        String source = Files.readString(\n                Path.of("src/main/java/ru/playsoftware/j2meloader/settings/KeyMapperActivity.java"),\n                StandardCharsets.UTF_8);\n        assertTrue(source.contains("EdgeToEdgeCompat.enableForComposeSurface(this)"));\n        assertFalse(source.contains("EdgeToEdgeCompat.protectHostContent(this)"));\n    }\n}\n''')
