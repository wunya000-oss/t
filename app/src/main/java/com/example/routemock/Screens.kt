package com.example.routemock

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import kotlin.math.roundToInt

private typealias Target = MainViewModel.Target

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteScreen(vm: MainViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val sim by vm.sim.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Target?>(null) }
    var follow by remember { mutableStateOf(true) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) vm.start()
    }
    val onStart: () -> Unit = {
        val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (hasLocation) {
            vm.start()
        } else {
            permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Маршрут", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    IconButton(onClick = vm::clear, enabled = !sim.running) {
                        Icon(Icons.Filled.Refresh, "Сбросить")
                    }
                },
            )
        },
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            RouteMap(
                modifier = Modifier.fillMaxSize(),
                start = ui.start,
                end = ui.end,
                route = sim.route,
                current = sim.pos,
                follow = follow && sim.running,
                onTap = vm::tap,
            )

            sim.route?.nextTurn(sim.traveledM)?.takeIf { sim.running }?.let { turn ->
                NextTurnCard(
                    turn = turn,
                    remainM = turn.distM - sim.traveledM,
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                )
            }

            if (sim.running) {
                SmallFloatingActionButton(
                    onClick = { follow = !follow },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
                    containerColor = if (follow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (follow) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                ) {
                    Icon(Icons.Filled.MyLocation, "Следовать за точкой")
                }
            }

            ControlPanel(
                modifier = Modifier.align(Alignment.BottomCenter),
                ui = ui,
                sim = sim,
                vm = vm,
                onStart = onStart,
                onEdit = { editing = it },
            )
        }
    }

    editing?.let { target ->
        CoordsDialog(
            title = if (target == Target.START) "Точка старта" else "Точка финиша",
            initial = if (target == Target.START) ui.start else ui.end,
            onDismiss = { editing = null },
            onConfirm = {
                vm.setPoint(target, it)
                editing = null
            },
        )
    }
}

@Composable
private fun ControlPanel(
    modifier: Modifier,
    ui: MainViewModel.UiState,
    sim: SimState,
    vm: MainViewModel,
    onStart: () -> Unit,
    onEdit: (Target) -> Unit,
) {
    val idle = !sim.running
    val route = sim.route
    val remainM = route?.let { (it.totalM - sim.traveledM).coerceAtLeast(0.0) } ?: 0.0
    val status = when {
        route == null -> "Выберите точки и постройте маршрут"
        sim.running && remainM < 1 -> "На месте · позиция зафиксирована"
        sim.running -> "В пути · осталось ${fmtDist(remainM)}"
        remainM < 1 -> "Маршрут пройден"
        sim.traveledM > 0 -> "Пауза · осталось ${fmtDist(remainM)}"
        else -> "Готово к запуску"
    }

    Surface(
        modifier = modifier.fillMaxWidth().heightIn(max = 460.dp),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 3.dp,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = ui.target == Target.START,
                    onClick = { vm.setTarget(Target.START) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    enabled = idle,
                ) { Text("Старт") }
                SegmentedButton(
                    selected = ui.target == Target.END,
                    onClick = { vm.setTarget(Target.END) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    enabled = idle,
                ) { Text("Финиш") }
            }
            Text(
                "Нажмите на карту или введите координаты",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PointRow("Старт", Icons.Filled.MyLocation, ui.start, idle) { onEdit(Target.START) }
            PointRow("Финиш", Icons.Filled.Flag, ui.end, idle) { onEdit(Target.END) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RouteProfile.entries.forEach { p ->
                    FilterChip(
                        selected = ui.profile == p,
                        onClick = { vm.setProfile(p) },
                        label = { Text(p.title) },
                        enabled = idle,
                    )
                }
            }

            FilledTonalButton(
                onClick = vm::buildRoute,
                enabled = idle && !ui.building && ui.start != null && ui.end != null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (ui.building) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Directions, null)
                }
                Spacer(Modifier.width(8.dp))
                Text("Построить маршрут")
            }
            ui.routeError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            route?.let {
                val minutes = (it.totalM / (sim.speedKmh / 3.6) / 60).roundToInt()
                Text(
                    "${fmtDist(it.totalM)} · около $minutes мин",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("Скорость: ${sim.speedKmh.roundToInt()} км/ч", style = MaterialTheme.typography.titleMedium)
            Slider(value = sim.speedKmh, onValueChange = vm::setSpeed, valueRange = 1f..150f)

            if (route != null) {
                LinearProgressIndicator(
                    progress = { (sim.traveledM / route.totalM).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            sim.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            if (sim.running) {
                Button(
                    onClick = vm::stop,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.Stop, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Остановить")
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = route != null,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (route != null && remainM < 1) "Начать заново" else "Запустить")
                }
            }
        }
    }
}

@Composable
private fun PointRow(title: String, icon: ImageVector, point: LatLon?, enabled: Boolean, onEdit: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    point?.let { String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon) } ?: "Не выбрано",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            IconButton(onClick = onEdit, enabled = enabled) {
                Icon(Icons.Filled.Edit, "Ввести координаты")
            }
        }
    }
}

@Composable
private fun NextTurnCard(turn: Turn, remainM: Double, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Navigation, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    turn.text,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "через ${fmtDist(remainM)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun CoordsDialog(
    title: String,
    initial: LatLon?,
    onDismiss: () -> Unit,
    onConfirm: (LatLon) -> Unit,
) {
    var lat by remember { mutableStateOf(initial?.let { String.format(Locale.US, "%.6f", it.lat) } ?: "") }
    var lon by remember { mutableStateOf(initial?.let { String.format(Locale.US, "%.6f", it.lon) } ?: "") }
    val parsed = parseLatLon(lat, lon)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = lat,
                    onValueChange = { lat = it },
                    label = { Text("Широта, от −90 до 90") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = lon,
                    onValueChange = { lon = it },
                    label = { Text("Долгота, от −180 до 180") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) { Text("Готово") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

private fun parseLatLon(lat: String, lon: String): LatLon? {
    val a = lat.replace(',', '.').trim().toDoubleOrNull() ?: return null
    val b = lon.replace(',', '.').trim().toDoubleOrNull() ?: return null
    return if (a in -90.0..90.0 && b in -180.0..180.0) LatLon(a, b) else null
}
