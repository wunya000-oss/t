package com.example.routemock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private typealias Target = MainViewModel.Target

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteScreen(vm: MainViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val sim by vm.sim.collectAsState()
    val context = LocalContext.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) vm.start()
        else vm.showError("Нужно разрешение на доступ к местоположению")
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

    val route = sim.route
    val idle = !sim.running
    val remainM = route?.let { (it.totalM - sim.traveledM).coerceAtLeast(0.0) } ?: 0.0
    val finished = route != null && remainM < 1

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection).imePadding(),
        topBar = {
            LargeTopAppBar(
                title = { Text("Маршрут", fontWeight = FontWeight.SemiBold) },
                scrollBehavior = scroll,
                actions = {
                    IconButton(onClick = vm::clear, enabled = idle) {
                        Icon(Icons.Filled.Refresh, "Сбросить")
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Box(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (sim.running) {
                        Button(
                            onClick = vm::stop,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            Icon(Icons.Filled.Stop, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Остановить", style = MaterialTheme.typography.titleMedium)
                        }
                    } else {
                        Button(
                            onClick = onStart,
                            enabled = route != null,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (finished) "Начать заново" else "Запустить",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        },
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Подсказка следующего поворота
            route?.nextTurn(sim.traveledM)?.takeIf { sim.running }?.let { turn ->
                NextTurnCard(turn, remainM = turn.distM - sim.traveledM)
            }

            // Точки
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            CoordField("Старт", Icons.Filled.MyLocation, ui.startText, idle) {
                                vm.setText(Target.START, it)
                            }
                            CoordField("Финиш", Icons.Filled.Flag, ui.endText, idle) {
                                vm.setText(Target.END, it)
                            }
                        }
                        IconButton(onClick = vm::swap, enabled = idle) {
                            Icon(Icons.Filled.SwapVert, "Поменять местами")
                        }
                    }
                    Text(
                        "Формат: широта, долгота. Можно вставить координаты, скопированные из Google или Яндекс Карт.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        RouteProfile.entries.forEachIndexed { index, p ->
                            SegmentedButton(
                                selected = ui.profile == p,
                                onClick = { vm.setProfile(p) },
                                shape = SegmentedButtonDefaults.itemShape(index, RouteProfile.entries.size),
                                enabled = idle,
                                icon = {},
                            ) { Text(p.title, maxLines = 1) }
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
                }
            }

            // Маршрут
            if (route != null) {
                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val etaSec = remainM / (sim.speedKmh / 3.6)
                        Text(
                            "${fmtDist(route.totalM)} · около ${fmtDuration(route.totalM / (sim.speedKmh / 3.6))}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        RoutePreview(route, sim.pos, sim.traveledM)
                        LinearProgressIndicator(
                            progress = { (sim.traveledM / route.totalM).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            when {
                                sim.running && finished -> "На месте · позиция зафиксирована"
                                sim.running -> "В пути · осталось ${fmtDist(remainM)} · ${fmtDuration(etaSec)}"
                                finished -> "Маршрут пройден"
                                sim.traveledM > 0 -> "Пауза · осталось ${fmtDist(remainM)}"
                                else -> "Готово к запуску"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Скорость
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(20.dp)) {
                    var speedText by remember(sim.route) { mutableStateOf(sim.speedKmh.roundToInt().toString()) }
                    val shown = if (sim.running) sim.currentKmh else sim.speedKmh
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            shown.roundToInt().toString(),
                            style = MaterialTheme.typography.displayLarge.copy(fontSize = 72.sp, lineHeight = 76.sp),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "км/ч",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
                        )
                    }
                    Text(
                        if (sim.running) "Сейчас · цель ${sim.speedKmh.roundToInt()} км/ч" else "Заданная скорость",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Slider(
                        value = sim.speedKmh.coerceIn(1f, 300f),
                        onValueChange = {
                            vm.setSpeed(it)
                            speedText = it.roundToInt().toString()
                        },
                        valueRange = 1f..300f,
                    )
                    OutlinedTextField(
                        value = speedText,
                        onValueChange = { t ->
                            speedText = t
                            t.replace(',', '.').toFloatOrNull()?.takeIf { v -> v > 0f }?.let(vm::setSpeed)
                        },
                        label = { Text("Любая скорость, км/ч") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Реалистичное движение",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                "Плавный разгон, колебания скорости, замедление в поворотах, остановка в конце дороги",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = sim.realistic, onCheckedChange = vm::setRealistic)
                    }
                }
            }

            // Ошибка
            sim.error?.let { msg ->
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(msg, style = MaterialTheme.typography.bodyMedium)
                        if (sim.needsMockApp) {
                            Button(
                                onClick = { openDeveloperSettings(context) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                            ) {
                                Icon(Icons.Filled.Settings, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Настройки разработчика")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

private fun openDeveloperSettings(context: Context) {
    val flags = Intent.FLAG_ACTIVITY_NEW_TASK
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(flags))
    }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(flags)) }
    }
}

@Composable
private fun CoordField(
    label: String,
    icon: ImageVector,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val invalid = value.isNotBlank() && parseCoords(value) == null
    val support: (@Composable () -> Unit)? =
        if (invalid) ({ Text("Нужны два числа: широта, долгота") }) else null
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text("50.4501, 30.5234") },
        leadingIcon = { Icon(icon, null) },
        trailingIcon = {
            if (value.isEmpty()) {
                IconButton(onClick = { clipboard.getText()?.text?.let(onChange) }, enabled = enabled) {
                    Icon(Icons.Filled.ContentPaste, "Вставить")
                }
            } else {
                IconButton(onClick = { onChange("") }, enabled = enabled) {
                    Icon(Icons.Filled.Close, "Очистить")
                }
            }
        },
        isError = invalid,
        supportingText = support,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NextTurnCard(turn: Turn, remainM: Double) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Navigation, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    turn.text,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    "через ${fmtDist(remainM.coerceAtLeast(0.0))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

/** Схема маршрута без карты и без интернета: линия пути, старт, финиш и текущая позиция. */
private class Preview(route: Route) {
    private val minLat = route.points.minOf { it.lat }
    private val maxLat = route.points.maxOf { it.lat }
    private val minLon = route.points.minOf { it.lon }
    private val maxLon = route.points.maxOf { it.lon }
    private val kx = cos(Math.toRadians((minLat + maxLat) / 2))

    val spanX = max((maxLon - minLon) * kx, 1e-9).toFloat()
    val spanY = max(maxLat - minLat, 1e-9).toFloat()

    private val stride = max(1, route.points.size / 700)
    val ids: IntArray = (route.points.indices step stride).toList().toIntArray()
    val sample: List<LatLon> = ids.map { route.points[it] }

    fun px(p: LatLon): Float = ((p.lon - minLon) * kx).toFloat()
    fun py(p: LatLon): Float = (maxLat - p.lat).toFloat()
}

@Composable
private fun RoutePreview(route: Route, current: LatLon?, traveledM: Double) {
    val pv = remember(route) { Preview(route) }
    val track = MaterialTheme.colorScheme.outlineVariant
    val done = MaterialTheme.colorScheme.primary
    val onDone = MaterialTheme.colorScheme.onPrimary
    val startColor = MaterialTheme.colorScheme.tertiary
    val endColor = MaterialTheme.colorScheme.error

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().height(180.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pad = 24.dp.toPx()
            val scale = min((size.width - 2 * pad) / pv.spanX, (size.height - 2 * pad) / pv.spanY)
            val ox = (size.width - pv.spanX * scale) / 2
            val oy = (size.height - pv.spanY * scale) / 2
            fun map(p: LatLon) = Offset(ox + pv.px(p) * scale, oy + pv.py(p) * scale)

            val line = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

            val full = Path()
            pv.sample.forEachIndexed { i, p ->
                val o = map(p)
                if (i == 0) full.moveTo(o.x, o.y) else full.lineTo(o.x, o.y)
            }
            drawPath(full, track, style = line)

            if (traveledM > 0 && current != null) {
                val idx = route.indexAt(traveledM)
                val walked = Path()
                var started = false
                for (i in pv.ids.indices) {
                    if (pv.ids[i] > idx) break
                    val o = map(pv.sample[i])
                    if (!started) {
                        walked.moveTo(o.x, o.y)
                        started = true
                    } else {
                        walked.lineTo(o.x, o.y)
                    }
                }
                val c = map(current)
                if (started) walked.lineTo(c.x, c.y) else walked.moveTo(c.x, c.y)
                drawPath(walked, done, style = line)
            }

            drawCircle(startColor, 7.dp.toPx(), map(route.points.first()))
            drawCircle(endColor, 7.dp.toPx(), map(route.points.last()))
            current?.let {
                val c = map(it)
                drawCircle(done.copy(alpha = 0.25f), 16.dp.toPx(), c)
                drawCircle(onDone, 9.dp.toPx(), c)
                drawCircle(done, 6.dp.toPx(), c)
            }
        }
    }
}
