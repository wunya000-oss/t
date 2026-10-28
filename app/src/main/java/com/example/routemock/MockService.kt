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
import java.util.Random

/** Двигает телефон по маршруту, подменяя GPS и сетевой провайдер. */
class MockService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val lm by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    private val rnd = Random()

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
            val startM = SimulationHub.state.value.traveledM.takeIf { it < route.totalM } ?: 0.0
            val motion = MotionModel(route, startM)
            var last = SystemClock.elapsedRealtime()
            var noteAt = 0L
            // Цикл не завершается сам: на финише позиция фиксируется, пока не нажмут «Остановить».
            while (true) {
                val arrived = motion.arrived
                val (pos, course) = route.at(motion.distance)
                providers.forEach { push(it, pos, course, motion.speed) }
                val kmh = (motion.speed * 3.6).toFloat()
                SimulationHub.state.update {
                    it.copy(
                        running = true,
                        error = null,
                        needsMockApp = false,
                        traveledM = motion.distance,
                        pos = pos,
                        currentKmh = kmh,
                    )
                }

                val now = SystemClock.elapsedRealtime()
                if (now - noteAt > 2000) {
                    noteAt = now
                    refreshNotification(
                        if (arrived) "Вы на месте · нажмите «Остановить»"
                        else "Осталось ${fmtDist(route.totalM - motion.distance)} · ${kmh.toInt()} км/ч"
                    )
                }

                delay(TICK_MS)
                val after = SystemClock.elapsedRealtime()
                val dt = ((after - last) / 1000.0).coerceIn(0.05, 5.0)
                last = after
                if (!arrived) {
                    val st = SimulationHub.state.value
                    motion.step(dt, st.speedKmh, st.realistic)
                }
            }
        } catch (e: SecurityException) {
            SimulationHub.state.update {
                it.copy(
                    error = "Выберите RouteMock в настройках разработчика: «Приложение для имитации местоположения»",
                    needsMockApp = true,
                )
            }
        } catch (e: IllegalArgumentException) {
            SimulationHub.state.update { it.copy(error = "Тестовый провайдер недоступен: ${e.message}") }
        } finally {
            providers.forEach { runCatching { lm.setTestProviderEnabled(it, false); lm.removeTestProvider(it) } }
            SimulationHub.state.update { it.copy(running = false, currentKmh = 0f) }
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
            accuracy = 3f + rnd.nextFloat() * 2f
            bearing = course
            speed = speedMs.toFloat()
            speedAccuracyMetersPerSecond = 0.5f
            bearingAccuracyDegrees = 5f
            verticalAccuracyMeters = 5f
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
        private const val TICK_MS = 1000L
    }
}
