package com.example.toyolclicker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.toyolclicker.ui.theme.ToyolClickerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ToyolClickerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    SettingsScreen(
                        modifier = Modifier.padding(innerPadding),
                        state = state,
                        onEvent = {
                            when (it) {
                                is SettingsEvent.OnServiceRunningChange -> viewModel.onServiceRunningChange(it.isRunning)
                                is SettingsEvent.OnServiceTypeChange -> viewModel.onServiceTypeChange(it.type, it.isChecked)
                                is SettingsEvent.OnToKliaChange -> viewModel.onToKliaChange(it.isChecked)
                                is SettingsEvent.OnFromKliaChange -> viewModel.onFromKliaChange(it.isChecked)
                                is SettingsEvent.OnManualPriceEnabledChange -> viewModel.onManualPriceEnabledChange(it.isEnabled)
                                is SettingsEvent.OnManualPriceChange -> viewModel.onManualPriceChange(it.price)
                                is SettingsEvent.OnTimeSelectionChange -> viewModel.onTimeSelectionChange(it.selection)
                                is SettingsEvent.OnManualHourChange -> viewModel.onManualHourChange(it.hour, it.isChecked)
                                is SettingsEvent.OnRefreshIntervalChange -> viewModel.onRefreshIntervalChange(it.interval)
                            }
                        }
                    )
                }
            }
        }
    }
}

// Sealed interface for UI events
sealed interface SettingsEvent {
    data class OnServiceRunningChange(val isRunning: Boolean) : SettingsEvent
    data class OnServiceTypeChange(val type: String, val isChecked: Boolean) : SettingsEvent
    data class OnToKliaChange(val isChecked: Boolean) : SettingsEvent
    data class OnFromKliaChange(val isChecked: Boolean) : SettingsEvent
    data class OnManualPriceEnabledChange(val isEnabled: Boolean) : SettingsEvent
    data class OnManualPriceChange(val price: String) : SettingsEvent
    data class OnTimeSelectionChange(val selection: String) : SettingsEvent
    data class OnManualHourChange(val hour: Int, val isChecked: Boolean) : SettingsEvent
    data class OnRefreshIntervalChange(val interval: String) : SettingsEvent
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    state: SettingsState,
    onEvent: (SettingsEvent) -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // Permission Buttons
        Button(onClick = {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            context.startActivity(intent)
        }) {
            Text("Enable Accessibility Service")
        }
        Button(onClick = {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            context.startActivity(intent)
        }) {
            Text("Enable Floating Button")
        }

        HorizontalDivider()

        // Main Start/Stop Button
        Button(
            onClick = { onEvent(SettingsEvent.OnServiceRunningChange(!state.isServiceRunning)) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.isServiceRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (state.isServiceRunning) "Stop Service" else "Start Service")
        }

        HorizontalDivider()

        // Service Type
        Text("Service Type", style = MaterialTheme.typography.titleMedium)
        state.serviceTypes.keys.forEach { type ->
            val isChecked = state.serviceTypes[type] ?: false
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = { onEvent(SettingsEvent.OnServiceTypeChange(type, it)) }
                )
                Text(type, modifier = Modifier.padding(start = 8.dp))
            }
        }

        HorizontalDivider()

        // Job Type
        Text("Job Type", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.toKlia,
                onCheckedChange = { onEvent(SettingsEvent.OnToKliaChange(it)) }
            )
            Text("To KLIA", modifier = Modifier.padding(start = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.fromKlia,
                onCheckedChange = { onEvent(SettingsEvent.OnFromKliaChange(it)) }
            )
            Text("From KLIA", modifier = Modifier.padding(start = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.manualPriceEnabled,
                onCheckedChange = { onEvent(SettingsEvent.OnManualPriceEnabledChange(it)) }
            )
            OutlinedTextField(
                value = state.manualPrice,
                onValueChange = { onEvent(SettingsEvent.OnManualPriceChange(it)) },
                label = { Text("Harga Manual (e.g. 50)") },
                modifier = Modifier.padding(start = 8.dp),
                enabled = state.manualPriceEnabled
            )
        }

        HorizontalDivider()

        // Pick-up Time
        Text("Pick-up Time", style = MaterialTheme.typography.titleMedium)
        Row {
            RadioButton(
                selected = state.timeSelection == "Random",
                onClick = { onEvent(SettingsEvent.OnTimeSelectionChange("Random")) }
            )
            Text("Random", modifier = Modifier.align(Alignment.CenterVertically))
            Spacer(modifier = Modifier.width(16.dp))
            RadioButton(
                selected = state.timeSelection == "Manual",
                onClick = { onEvent(SettingsEvent.OnTimeSelectionChange("Manual")) }
            )
            Text("Manual", modifier = Modifier.align(Alignment.CenterVertically))
        }

        val hours = (0..23).toList()
        FlowRow(
            maxItemsInEachRow = 6,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            hours.forEach { hour ->
                val isChecked = state.manualHours[hour] ?: false
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = { onEvent(SettingsEvent.OnManualHourChange(hour, it)) },
                        enabled = state.timeSelection == "Manual"
                    )
                    Text(hour.toString().padStart(2, '0'))
                }
            }
        }

        HorizontalDivider()

        // Refresh Interval
        Text("Refresh Interval", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.refreshInterval,
            onValueChange = { onEvent(SettingsEvent.OnRefreshIntervalChange(it)) },
            label = { Text("Interval (ms)") },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowRow(
    modifier: Modifier = Modifier,
    maxItemsInEachRow: Int,
    content: @Composable () -> Unit
) {
    Layout(
        content = content,
        modifier = modifier
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        val rows = mutableListOf<List<Placeable>>()
        var currentRow = mutableListOf<Placeable>()
        var currentWidth = 0

        placeables.forEach { placeable ->
            if (currentWidth + placeable.width > constraints.maxWidth || currentRow.size >= maxItemsInEachRow) {
                rows.add(currentRow)
                currentRow = mutableListOf(placeable)
                currentWidth = placeable.width
            } else {
                currentRow.add(placeable)
                currentWidth += placeable.width
            }
        }
        if (currentRow.isNotEmpty()) {
            rows.add(currentRow)
        }

        val height = rows.sumOf { row -> row.maxOfOrNull { it.height } ?: 0 }
        layout(constraints.maxWidth, height) {
            var y = 0
            rows.forEach { row ->
                val rowHeight = row.maxOfOrNull { it.height } ?: 0
                var x = 0
                row.forEach { placeable ->
                    placeable.placeRelative(x, y)
                    x += placeable.width
                }
                y += rowHeight
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    ToyolClickerTheme {
        SettingsScreen(
            state = SettingsState(),
            onEvent = {}
        )
    }
}
