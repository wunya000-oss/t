package com.example.routemock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.min

/** Двигает телефон по маршруту, подменяя GPS и сетевой провайдер. */
class MockService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val lm by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Имитация местоположения", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (job?.isActive == true) job?.cancel() else stopSelf()
            return START_NOT_STICKY
        }
        goForeground("Запуск…")
        val route = SimulationHub.state.value.route
        if (route == null) {
            SimulationHub.state.update { it.copy(error = "Сначала постройте маршрут") }
            finishService()
        } else if (job?.isActive != true) {
            job = scope.launch { play(route) }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun play(route: Route) {
        try {
            providers.forEach { setupProvider(it) }
            var traveled = SimulationHub.state.value.traveledM.takeIf { it < route.totalM } ?: 0.0
            var last = SystemClock.elapsedRealtime()
            var noteAt = 0L
            // Цикл не завершается сам: на финише позиция фиксируется, пока не нажмут «Остановить».
            while (true) {
                val kmh = SimulationHub.state.value.speedKmh
                val arrived = traveled >= route.totalM
                val (pos, course) = route.at(traveled)
                providers.forEach { push(it, pos, course, if (arrived) 0.0 else kmh / 3.6) }
                SimulationHub.state.update { it.copy(running = true, error = null, traveledM = traveled, pos = pos) }

                val now = SystemClock.elapsedRealtime()
                if (now - noteAt > 2000) {
                    noteAt = now
                    refreshNotification(
                        if (arrived) "Вы на месте · нажмите «Остановить»"
                        else "Осталось ${fmtDist(route.totalM - traveled)} · ${kmh.toInt()} км/ч"
                    )
                }

                delay(TICK_MS)
                val after = SystemClock.elapsedRealtime()
                if (!arrived) traveled = min(route.totalM, traveled + kmh / 3.6 * (after - last) / 1000.0)
                last = after
            }
        } catch (e: SecurityException) {
            SimulationHub.state.update {
                it.copy(error = "Выберите RouteMock в настройках разработчика: «Приложение для имитации местоположения»")
            }
        } catch (e: IllegalArgumentException) {
            SimulationHub.state.update { it.copy(error = "Тестовый провайдер недоступен: ${e.message}") }
        } finally {
            providers.forEach { runCatching { lm.setTestProviderEnabled(it, false); lm.removeTestProvider(it) } }
            SimulationHub.state.update { it.copy(running = false) }
            finishService()
        }
    }

    private fun setupProvider(name: String) {
        runCatching { lm.removeTestProvider(name) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lm.addTestProvider(
                name,
                ProviderProperties.Builder()
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .build()
            )
        } else {
            @Suppress("DEPRECATION")
            lm.addTestProvider(
                name, false, false, false, false, true, true, true,
                Criteria.POWER_LOW, Criteria.ACCURACY_FINE
            )
        }
        lm.setTestProviderEnabled(name, true)
    }

    private fun push(provider: String, pos: LatLon, course: Float, speedMs: Double) {
        val loc = Location(provider).apply {
            latitude = pos.lat
            longitude = pos.lon
            altitude = 0.0
            accuracy = 3f
            bearing = course
            speed = speedMs.toFloat()
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        lm.setTestProviderLocation(provider, loc)
    }

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun refreshNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, MockService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Имитация местоположения")
            .setContentText(text)
            .setOngoing(true)
            .addAction(0, "Остановить", stop)
            .build()
    }

    private fun finishService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "com.example.routemock.STOP"
        private const val CHANNEL = "simulation"
        private const val NOTIF_ID = 1
        private const val TICK_MS = 500L
    }
}
