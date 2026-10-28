package com.example.routemock

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RouteMockTheme { RouteScreen() } }
    }
}

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF3E5BA9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE1FF),
    onPrimaryContainer = Color(0xFF001551),
    secondaryContainer = Color(0xFFDCE2F9),
    tertiary = Color(0xFF00696B),
)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFB5C4FF),
    onPrimary = Color(0xFF042978),
    primaryContainer = Color(0xFF24408F),
    onPrimaryContainer = Color(0xFFDBE1FF),
    secondaryContainer = Color(0xFF404659),
    tertiary = Color(0xFF4CDADC),
)

/** Material 3: на Android 12+ цвета берутся из обоев (Material You). */
@Composable
fun RouteMockTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    enum class Target { START, END }

    data class UiState(
        val startText: String = "",
        val endText: String = "",
        val profile: RouteProfile = RouteProfile.CAR,
        val building: Boolean = false,
        val routeError: String? = null,
    ) {
        val start: LatLon? get() = parseCoords(startText)
        val end: LatLon? get() = parseCoords(endText)
    }

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()
    val sim = SimulationHub.state

    fun setText(t: Target, text: String) {
        if (sim.value.running) return
        _ui.update {
            if (t == Target.START) it.copy(startText = text, routeError = null)
            else it.copy(endText = text, routeError = null)
        }
        if (sim.value.route != null) resetRoute()
    }

    fun swap() {
        if (sim.value.running) return
        _ui.update { it.copy(startText = it.endText, endText = it.startText, routeError = null) }
        resetRoute()
    }

    fun setProfile(p: RouteProfile) {
        if (sim.value.running) return
        _ui.update { it.copy(profile = p, routeError = null) }
        resetRoute()
    }

    fun setSpeed(kmh: Float) = SimulationHub.state.update { it.copy(speedKmh = kmh) }

    fun setRealistic(on: Boolean) = SimulationHub.state.update { it.copy(realistic = on) }

    fun showError(message: String) =
        SimulationHub.state.update { it.copy(error = message, needsMockApp = false) }

    fun clear() {
        if (sim.value.running) return
        _ui.value = UiState(profile = _ui.value.profile)
        resetRoute()
    }

    fun buildRoute() {
        val s = _ui.value
        val a = s.start ?: return
        val b = s.end ?: return
        viewModelScope.launch {
            _ui.update { it.copy(building = true, routeError = null) }
            try {
                val route = s.profile.fetch(a, b)
                // Скорость по умолчанию — средняя по маршруту из OSRM; слайдером её можно поменять.
                val avgKmh = if (route.durationS > 0) (route.totalM / route.durationS * 3.6).toFloat() else null
                SimulationHub.state.update {
                    it.copy(
                        route = route,
                        traveledM = 0.0,
                        pos = route.points.first(),
                        error = null,
                        needsMockApp = false,
                        speedKmh = avgKmh?.coerceAtLeast(1f) ?: it.speedKmh,
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(routeError = "Не удалось построить маршрут: ${e.message.orEmpty()}") }
            } finally {
                _ui.update { it.copy(building = false) }
            }
        }
    }

    fun start() {
        ContextCompat.startForegroundService(app, Intent(app, MockService::class.java))
    }

    fun stop() {
        app.startService(Intent(app, MockService::class.java).setAction(MockService.ACTION_STOP))
    }

    private fun resetRoute() {
        SimulationHub.state.update {
            it.copy(route = null, traveledM = 0.0, pos = null, error = null, needsMockApp = false, currentKmh = 0f)
        }
    }
}
