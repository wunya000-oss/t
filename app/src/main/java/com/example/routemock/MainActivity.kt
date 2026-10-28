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

/** Material 3: на Android 12+ цвета берутся из обоев (Material You). */
@Composable
fun RouteMockTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    enum class Target { START, END }

    data class UiState(
        val start: LatLon? = null,
        val end: LatLon? = null,
        val target: Target = Target.START,
        val profile: RouteProfile = RouteProfile.CAR,
        val building: Boolean = false,
        val routeError: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()
    val sim = SimulationHub.state

    /** Тап по карте ставит точку текущей цели; после старта сам переключается на финиш. */
    fun tap(p: LatLon) {
        val t = _ui.value.target
        setPoint(t, p)
        if (t == Target.START && _ui.value.end == null) setTarget(Target.END)
    }

    fun setPoint(t: Target, p: LatLon) {
        if (sim.value.running) return
        _ui.update { if (t == Target.START) it.copy(start = p) else it.copy(end = p) }
        resetRoute()
    }

    fun setTarget(t: Target) = _ui.update { it.copy(target = t) }

    fun setProfile(p: RouteProfile) {
        _ui.update { it.copy(profile = p) }
        resetRoute()
    }

    fun setSpeed(kmh: Float) = SimulationHub.state.update { it.copy(speedKmh = kmh) }

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
                        pos = a,
                        error = null,
                        speedKmh = avgKmh?.coerceIn(1f, 150f) ?: it.speedKmh,
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(routeError = "Не удалось построить маршрут. ${e.message.orEmpty()}") }
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
        SimulationHub.state.update { it.copy(route = null, traveledM = 0.0, pos = null, error = null) }
    }
}
