package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.music.bitchord.R
import com.music.bitchord.data.sources.EclipseSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.eclipse.EclipseManifest
import com.music.bitchord.data.sources.eclipse.EclipseSetting
import com.music.bitchord.ui.components.IosSwitch

/**
 * The settings form an Eclipse addon declares in its manifest, drawn by the
 * host: `select`, `toggle`, `text` and `number` fields, with `perNetwork`
 * fields shown twice (Wi-Fi and mobile data). The values go back to the addon
 * as query parameters on every request; only values that differ from the
 * manifest's default are stored, so a default the addon later changes still
 * reaches listeners who never touched it.
 */
@Composable
fun EclipseSettingsDialog(
    config: SourceConfig,
    onDismiss: () -> Unit,
) {
    var manifest by remember { mutableStateOf<EclipseManifest?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val wifi = remember { mutableStateMapOf<String, String>().apply { putAll(config.eclipseSettings) } }
    val cell = remember { mutableStateMapOf<String, String>().apply { putAll(config.eclipseCellularSettings) } }
    var notices by remember { mutableStateOf(config.eclipseNotices) }

    LaunchedEffect(config.id) {
        val source = SourceRegistry.instance(config.id) as? EclipseSource
        if (source == null) {
            failure = "Not available"
            return@LaunchedEffect
        }
        source.manifest().fold(onSuccess = { manifest = it }, onFailure = { failure = it.message ?: "Failed" })
    }

    fun save() {
        val declared = manifest?.settings.orEmpty().associateBy { it.key }
        fun differing(values: Map<String, String>, perNetworkOnly: Boolean) =
            values.filter { (key, value) ->
                val setting = declared[key] ?: return@filter false
                if (perNetworkOnly && !setting.perNetwork) return@filter false
                value != (setting.defaultValue ?: "")
            }
        val latest = SourceRegistry.config(config.id) ?: config
        SourceRegistry.update(
            latest.copy(
                eclipseSettings = differing(wifi, perNetworkOnly = false),
                eclipseCellularSettings = differing(cell, perNetworkOnly = true),
                eclipseNotices = notices,
            ),
        )
        // Answers shaped by the old values describe a different question now.
        (SourceRegistry.instance(config.id) as? EclipseSource)?.clearCompletedTrackCalls()
        onDismiss()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = config.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.eclipse_settings),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Column(
                    Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val m = manifest
                    when {
                        failure != null -> Text(
                            stringResource(R.string.eclipse_settings_unreachable, failure.orEmpty()),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        m == null -> Text(
                            stringResource(R.string.eclipse_settings_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> {
                            if (m.settings.none { it.key.isNotBlank() } && !m.sendsNotices) {
                                Text(
                                    stringResource(R.string.eclipse_settings_none),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            m.settings.filter { it.key.isNotBlank() }.forEach { setting ->
                                SettingField(setting, wifi, cell)
                            }
                            if (m.sendsNotices) {
                                HorizontalDivider()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            stringResource(R.string.eclipse_notices),
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        Text(
                                            stringResource(R.string.eclipse_notices_help),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    IosSwitch(checked = notices, onCheckedChange = { notices = it })
                                }
                            }
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = {
                        wifi.clear(); cell.clear(); notices = true
                    }) { Text(stringResource(R.string.eclipse_reset_defaults)) }
                    Row {
                        TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                        TextButton(onClick = ::save, enabled = manifest != null) {
                            Text(stringResource(android.R.string.ok))
                        }
                    }
                }
            }
        }
    }
}

/** One declared setting. A `perNetwork` one is drawn twice, so each connection can have its own value. */
@Composable
private fun SettingField(
    setting: EclipseSetting,
    wifi: MutableMap<String, String>,
    cell: MutableMap<String, String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(setting.title, style = MaterialTheme.typography.bodyLarge)
        if (setting.help.isNotBlank()) {
            Text(
                setting.help,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val fallback = setting.defaultValue.orEmpty()
        if (setting.perNetwork) {
            Text(stringResource(R.string.eclipse_on_wifi), style = MaterialTheme.typography.labelMedium)
            SettingControl(setting, value = wifi[setting.key] ?: fallback) { wifi[setting.key] = it }
            Text(stringResource(R.string.eclipse_on_mobile), style = MaterialTheme.typography.labelMedium)
            SettingControl(
                setting,
                // Until changed, mobile data follows the Wi-Fi value.
                value = cell[setting.key] ?: wifi[setting.key] ?: fallback,
            ) { cell[setting.key] = it }
        } else {
            SettingControl(setting, value = wifi[setting.key] ?: fallback) { wifi[setting.key] = it }
        }
    }
}

@Composable
private fun SettingControl(setting: EclipseSetting, value: String, onChange: (String) -> Unit) {
    when (setting.kind) {
        EclipseSetting.Kind.TOGGLE -> IosSwitch(
            checked = value.equals("true", ignoreCase = true) || value == "1",
            onCheckedChange = { onChange(it.toString()) },
        )
        EclipseSetting.Kind.SELECT -> Column {
            setting.options.forEach { option ->
                val optionValue = option.stringValue ?: return@forEach
                val selected = optionValue == value
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onChange(optionValue) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        option.labelText.orEmpty(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    if (selected) Text("✓", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        EclipseSetting.Kind.NUMBER -> OutlinedTextField(
            value = value,
            onValueChange = { typed -> onChange(typed.filter { it.isDigit() || it == '.' || it == '-' }) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            placeholder = { Text(setting.placeholder) },
            supportingText = {
                val bounds = listOfNotNull(setting.min?.let { "min $it" }, setting.max?.let { "max $it" })
                if (bounds.isNotEmpty()) Text(bounds.joinToString(" · "))
            },
            modifier = Modifier.fillMaxWidth(),
        )
        EclipseSetting.Kind.TEXT -> OutlinedTextField(
            value = value,
            onValueChange = { typed -> onChange(setting.maxLength?.let { typed.take(it) } ?: typed) },
            singleLine = true,
            placeholder = { Text(setting.placeholder) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
