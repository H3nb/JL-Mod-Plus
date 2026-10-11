package io.github.h3nb.jlmodplus.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdatesScreen(
    repository: UpdateRepository,
    onBack: () -> Unit,
    onOpenRelease: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val status = repository.status
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.updates_installed_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.updates_build_code, BuildConfig.VERSION_CODE),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (BuildConfig.DEBUG) {
                    Text(
                        stringResource(R.string.updates_debug_build),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.updates_channel), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = repository.channel == UpdateChannel.Stable,
                        onClick = {
                            repository.selectChannel(UpdateChannel.Stable)
                            scope.launch { repository.check(manual = true) }
                        },
                        label = { Text(stringResource(R.string.updates_stable)) },
                    )
                    FilterChip(
                        selected = repository.channel == UpdateChannel.Preview,
                        onClick = {
                            repository.selectChannel(UpdateChannel.Preview)
                            scope.launch { repository.check(manual = true) }
                        },
                        label = { Text(stringResource(R.string.updates_preview)) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.updates_automatic),
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Switch(
                    checked = repository.automaticChecks,
                    onCheckedChange = repository::configureAutomaticChecks,
                )
            }
            val message = when (status) {
                UpdateStatus.NotChecked -> R.string.updates_not_checked
                UpdateStatus.Checking -> R.string.updates_checking
                UpdateStatus.Current -> R.string.updates_current
                UpdateStatus.NoRelease -> R.string.updates_no_release
                UpdateStatus.NotInstallable -> R.string.updates_not_installable
                UpdateStatus.Failed -> R.string.updates_failed
                is UpdateStatus.Available -> R.string.updates_available
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
                if (status is UpdateStatus.Available) {
                    Text(
                        status.candidate.versionName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(onClick = { onOpenRelease(status.candidate.releaseUrl) }) {
                        Text(stringResource(R.string.updates_view_release))
                    }
                }
                if (repository.lastChecked != 0L) {
                    val checkedAt = DateFormat.getDateTimeInstance(
                        DateFormat.SHORT, DateFormat.SHORT
                    ).format(Date(repository.lastChecked))
                    Text(
                        stringResource(R.string.updates_last_checked, checkedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = { scope.launch { repository.check(manual = true) } },
                enabled = status != UpdateStatus.Checking,
            ) {
                Text(stringResource(R.string.updates_check))
            }
        }
    }
}
