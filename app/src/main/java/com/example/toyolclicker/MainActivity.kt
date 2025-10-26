package com.example.toyolclicker

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
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
                        onEvent = viewModel::handleEvent
                    )
                }
            }
        }
    }
}

// V2.2: Updated sealed interface for UI events
sealed interface SettingsEvent {
    data class OnServiceTypeChange(val type: String, val isChecked: Boolean) : SettingsEvent
    data class OnJobTargetChange(val serviceType: String, val update: (JobTarget) -> JobTarget) : SettingsEvent
    data class OnTimeSelectionChange(val selection: String) : SettingsEvent
    data class OnManualHourChange(val hour: Int, val isChecked: Boolean) : SettingsEvent
    data class OnRefreshIntervalChange(val interval: String) : SettingsEvent
}

// V2.2: Updated handleEvent function
fun SettingsViewModel.handleEvent(event: SettingsEvent) {
    when (event) {
        is SettingsEvent.OnServiceTypeChange -> onServiceTypeChange(event.type, event.isChecked)
        is SettingsEvent.OnJobTargetChange -> onJobTargetChange(event.serviceType, event.update)
        is SettingsEvent.OnTimeSelectionChange -> onTimeSelectionChange(event.selection)
        is SettingsEvent.OnManualHourChange -> onManualHourChange(event.hour, event.isChecked)
        is SettingsEvent.OnRefreshIntervalChange -> onRefreshIntervalChange(event.interval)
    }
}

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

        // Permission and Control Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Button(onClick = {
                val intent = Intent("com.example.toyolclicker.SHOW_BUTTON")
                intent.setPackage(context.packageName) // Make the broadcast explicit
                context.sendBroadcast(intent)
            }) {
                Text("Show Floating Button")
            }
            Button(onClick = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                context.startActivity(intent)
            }) {
                Text("Accessibility Settings")
            }
        }

        HorizontalDivider()

        // V2.1 & V2.2: Service Types & Personalized Job Targets (including distance)
        Text("Service Types & Job Targets", style = MaterialTheme.typography.titleMedium)
        state.serviceTypes.keys.forEach { serviceType ->
            val isServiceChecked = state.serviceTypes[serviceType] ?: false
            val target = state.jobTargets[serviceType] ?: JobTarget()

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Row 1: Main toggle for the service type
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = isServiceChecked,
                            onCheckedChange = { onEvent(SettingsEvent.OnServiceTypeChange(serviceType, it)) }
                        )
                        Text(serviceType, style = MaterialTheme.typography.titleSmall)
                    }

                    // Row 2: Toggle for enabling personalized targets
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(modifier = Modifier.width(48.dp)) // Indent
                        Checkbox(
                            checked = target.isEnabled,
                            onCheckedChange = { isEnabled ->
                                onEvent(SettingsEvent.OnJobTargetChange(serviceType) { it.copy(isEnabled = isEnabled) })
                            },
                            enabled = isServiceChecked
                        )
                        Text("Enable Personalized Targets", style = MaterialTheme.typography.bodySmall)
                    }

                    // Collapsible section for the actual targets
                    AnimatedVisibility(visible = isServiceChecked && target.isEnabled) {
                        Column(
                            modifier = Modifier.padding(start = 56.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // To KLIA
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = target.toKlia,
                                    onCheckedChange = { isChecked ->
                                        onEvent(SettingsEvent.OnJobTargetChange(serviceType) { it.copy(toKlia = isChecked) })
                                    }
                                )
                                Text("To KLIA")
                            }
                            // From KLIA
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = target.fromKlia,
                                    onCheckedChange = { isChecked ->
                                        onEvent(SettingsEvent.OnJobTargetChange(serviceType) { it.copy(fromKlia = isChecked) })
                                    }
                                )
                                Text("From KLIA")
                            }
                            // Manual Price
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = target.manualPriceEnabled,
                                    onCheckedChange = { isEnabled ->
                                        onEvent(SettingsEvent.OnJobTargetChange(serviceType) { it.copy(manualPriceEnabled = isEnabled) })
                                    }
                                )
                                OutlinedTextField(
                                    value = target.manualPrice,
                                    onValueChange = { price ->
                                        onEvent(SettingsEvent.OnJobTargetChange(serviceType) { it.copy(manualPrice = price) })
                                    },
                                    label = { Text("Min Price") },
                                    enabled = target.manualPriceEnabled,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                            // V2.2: Personalized Distance Filter
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = target.distanceFilterEnabled,
                                    onCheckedChange = { isEnabled ->
                                        onEvent(SettingsEvent.OnJobTargetChange(serviceType) { it.copy(distanceFilterEnabled = isEnabled) })
                                    }
                                )
                                OutlinedTextField(
                                    value = target.maxPickupDistance,
                                    onValueChange = { distance ->
                                        onEvent(SettingsEvent.OnJobTargetChange(serviceType) { it.copy(maxPickupDistance = distance) })
                                    },
                                    label = { Text("Max Dist (km)") },
                                    enabled = target.distanceFilterEnabled,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        // Pick-up Time (Global)
        Text("Global Pick-up Time Filter", style = MaterialTheme.typography.titleMedium)
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

        // Manual hour selection grid
        FlowRow(maxItemsInEachRow = 6, modifier = Modifier.padding(top = 8.dp)) {
            (0..23).forEach { hour ->
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

        // Refresh Interval (Global)
        Text("Global Refresh Interval", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.refreshInterval,
            onValueChange = { onEvent(SettingsEvent.OnRefreshIntervalChange(it)) },
            label = { Text("Interval (ms)") },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// Using a custom layout for flow row, no changes needed here
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
