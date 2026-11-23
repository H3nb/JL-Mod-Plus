package io.github.h3nb.jlmodplus.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.BuildConfig
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.PreferenceSwitchRow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdatesScreen(
    repository: UpdateRepository,
    onBack: () -> Unit,
    onOpenRelease: (String) -> Unit,
    onOpenDownload: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val status = repository.status
    val message = when (status) {
        UpdateStatus.NotChecked -> R.string.updates_not_checked
        UpdateStatus.Checking -> R.string.updates_checking
        UpdateStatus.Current -> R.string.updates_current
        UpdateStatus.NoRelease -> R.string.updates_no_release
        UpdateStatus.NotInstallable -> R.string.updates_not_installable
        UpdateStatus.Failed -> R.string.updates_failed
        is UpdateStatus.Available -> R.string.updates_available
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.updates_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .widthIn(max = 720.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            stringResource(R.string.updates_installed_version, BuildConfig.VERSION_NAME),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.updates_build_code, BuildConfig.VERSION_CODE),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (BuildConfig.DEBUG) {
                            Text(
                                stringResource(R.string.updates_debug_build),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column {
                        PreferenceSwitchRow(
                            title = stringResource(R.string.updates_automatic),
                            description = null,
                            checked = repository.automaticChecks,
                            onCheckedChange = repository::configureAutomaticChecks,
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                stringResource(message),
                                style = if (status is UpdateStatus.Available) {
                                    MaterialTheme.typography.titleSmall
                                } else {
                                    MaterialTheme.typography.bodyMedium
                                },
                                color = if (status is UpdateStatus.Available) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            if (repository.checking && status != UpdateStatus.Checking) {
                                Text(stringResource(R.string.updates_checking))
                            }
                            if (repository.refreshFailed && status != UpdateStatus.Failed) {
                                Text(stringResource(R.string.updates_failed))
                            }
                            if (status is UpdateStatus.Available) {
                                Text(
                                    status.candidate.versionName,
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    status.candidate.downloadUrl?.let { url ->
                                        Button(
                                            onClick = { onOpenDownload(url) },
                                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                        ) {
                                            Text(stringResource(R.string.updates_download_apk))
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = { onOpenRelease(status.candidate.releaseUrl) },
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    ) {
                                        Text(stringResource(R.string.updates_view_release))
                                    }
                                }
                            }
                            if (repository.lastChecked != 0L) {
                                val checkedAt = DateFormat.getDateTimeInstance(
                                    DateFormat.SHORT, DateFormat.SHORT,
                                ).format(Date(repository.lastChecked))
                                Text(
                                    stringResource(R.string.updates_last_checked, checkedAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(
                                onClick = { scope.launch { repository.check(manual = true) } },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                enabled = !repository.checking,
                            ) {
                                Text(stringResource(R.string.updates_check))
                            }
                        }
                    }
                }
            }
        }
    }
}
