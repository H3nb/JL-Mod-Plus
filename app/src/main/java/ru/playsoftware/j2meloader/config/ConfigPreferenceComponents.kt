/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ru.playsoftware.j2meloader.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal enum class ConfigMessageLevel { Info, Warning, Danger }

@Composable
internal fun ConfigSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
  text = title,
  style = MaterialTheme.typography.labelLarge,
  color = MaterialTheme.colorScheme.primary,
  modifier = Modifier.padding(horizontal = 6.dp),
        )
        Surface(
  modifier = Modifier.fillMaxWidth(),
  shape = RoundedCornerShape(18.dp),
  color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
  Column(content = content)
        }
    }
}

@Composable
internal fun ConfigValuePreference(
    title: String,
    description: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    message: String? = null,
    messageLevel: ConfigMessageLevel = ConfigMessageLevel.Info,
) {
    Column(
        modifier = modifier
  .fillMaxWidth()
  .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
  .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
  text = title,
  style = MaterialTheme.typography.bodyLarge,
  color = if (enabled) MaterialTheme.colorScheme.onSurface
  else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
  text = description,
  style = MaterialTheme.typography.bodySmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (message != null) {
  ConfigInlineMessage(message, messageLevel)
        }
        Row(
  modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
  verticalAlignment = Alignment.CenterVertically,
        ) {
  Text(
      text = value,
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
      color = if (enabled) MaterialTheme.colorScheme.primary
      else MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
  )
  if (enabled) {
      Text(
text = "›",
style = MaterialTheme.typography.titleLarge,
color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
  }
        }
    }
}

@Composable
internal fun ConfigSwitchPreference(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    messageLevel: ConfigMessageLevel = ConfigMessageLevel.Info,
) {
    Row(
        modifier = modifier
  .fillMaxWidth()
  .heightIn(min = 64.dp)
  .clickable { onCheckedChange(!checked) }
  .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
  Text(text = title, style = MaterialTheme.typography.bodyLarge)
  Text(
      text = description,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  if (message != null) {
      ConfigInlineMessage(message, messageLevel)
  }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun ConfigChoicePreference(
    title: String,
    description: String,
    selected: String,
    options: List<String>,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    messageLevel: ConfigMessageLevel = ConfigMessageLevel.Info,
) {
    var dialogVisible by remember(selected, options) { mutableStateOf(false) }
    ConfigValuePreference(
        title = title,
        description = description,
        value = selected,
        enabled = options.isNotEmpty(),
        message = message,
        messageLevel = messageLevel,
        modifier = modifier,
        onClick = { dialogVisible = true },
    )
    if (dialogVisible) {
        ConfigChoiceDialog(
  title = title,
  description = description,
  selected = selected,
  options = options,
  onDismissRequest = { dialogVisible = false },
  onSelected = { index ->
      dialogVisible = false
      onSelected(index)
  },
        )
    }
}

@Composable
internal fun ConfigNumberPreference(
    title: String,
    description: String,
    value: String,
    fallbackLabel: String,
    keyboardType: KeyboardType,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    valueSuffix: String? = null,
) {
    var dialogVisible by remember(value) { mutableStateOf(false) }
    val display = buildString {
        append(value.ifEmpty { fallbackLabel })
        if (value.isNotEmpty() && valueSuffix != null) append(" ").append(valueSuffix)
    }
    ConfigValuePreference(
        title = title,
        description = description,
        value = display,
        modifier = modifier,
        onClick = { dialogVisible = true },
    )
    if (dialogVisible) {
        ConfigNumberDialog(
  title = title,
  description = description,
  initialValue = value,
  label = null,
  keyboardType = keyboardType,
  valueSuffix = valueSuffix,
  onDismissRequest = { dialogVisible = false },
  onConfirm = { next ->
      dialogVisible = false
      onValueChange(next)
  },
        )
    }
}

@Composable
internal fun ConfigColorPreference(
    title: String,
    description: String,
    value: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
  .fillMaxWidth()
  .clickable(role = Role.Button, onClick = onClick)
  .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
  text = description,
  style = MaterialTheme.typography.bodySmall,
  color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
  modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
  verticalAlignment = Alignment.CenterVertically,
  horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
  Box(
      modifier = Modifier
.size(24.dp)
.clip(RoundedCornerShape(6.dp))
.background(configColor(value)),
  )
  Text(
      text = "#${value.ifEmpty { "000000" }}",
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.primary,
  )
  Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ConfigActionPreference(
    title: String,
    description: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    emphasized: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (emphasized) MaterialTheme.colorScheme.errorContainer else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
  modifier = Modifier
      .fillMaxWidth()
      .clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 11.dp),
  verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
  Text(
      text = title,
      style = MaterialTheme.typography.bodyLarge,
      color = when {
emphasized -> MaterialTheme.colorScheme.onErrorContainer
destructive -> MaterialTheme.colorScheme.error
else -> MaterialTheme.colorScheme.onSurface
      },
  )
  Text(
      text = description,
      style = MaterialTheme.typography.bodySmall,
      color = if (emphasized) MaterialTheme.colorScheme.onErrorContainer
      else MaterialTheme.colorScheme.onSurfaceVariant,
  )
        }
    }
}

@Composable
internal fun ConfigDisclosurePreference(
    title: String,
    description: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
  .fillMaxWidth()
  .clickable { onExpandedChange(!expanded) }
  .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
  Text(text = title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
  Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
  text = if (expanded) "⌃" else "⌄",
  style = MaterialTheme.typography.titleMedium,
  color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
internal fun ConfigMessageBlock(text: String, level: ConfigMessageLevel = ConfigMessageLevel.Info) {
    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        ConfigInlineMessage(text, level)
    }
}

@Composable
private fun ConfigInlineMessage(text: String, level: ConfigMessageLevel) {
    val container = when (level) {
        ConfigMessageLevel.Info -> MaterialTheme.colorScheme.primaryContainer
        ConfigMessageLevel.Warning -> MaterialTheme.colorScheme.tertiaryContainer
        ConfigMessageLevel.Danger -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (level) {
        ConfigMessageLevel.Info -> MaterialTheme.colorScheme.onPrimaryContainer
        ConfigMessageLevel.Warning -> MaterialTheme.colorScheme.onTertiaryContainer
        ConfigMessageLevel.Danger -> MaterialTheme.colorScheme.onErrorContainer
    }
    val prefix = when (level) {
        ConfigMessageLevel.Info -> "ⓘ "
        ConfigMessageLevel.Warning -> "⚠ "
        ConfigMessageLevel.Danger -> "⚠ "
    }
    Surface(shape = RoundedCornerShape(10.dp), color = container) {
        Text(
  text = prefix + text,
  modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
  style = MaterialTheme.typography.bodySmall,
  color = content,
        )
    }
}

private fun configColor(value: String): Color {
    return try {
        Color((0xFF000000L or value.trim().removePrefix("#").toLong(16)).toULong())
    } catch (_: Throwable) {
        Color.Black
    }
}
