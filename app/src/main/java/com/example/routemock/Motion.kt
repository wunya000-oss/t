package com.example.routemock

import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Модель движения по маршруту. Вместо постоянной скорости даёт «живую»:
 *  - плавный разгон с места и торможение (ограничены ускорением профиля);
 *  - случайные плавные колебания скорости вокруг заданной (процесс Орнштейна — Уленбека);
 *  - замедление перед поворотами и в них (по кривизне геометрии маршрута);
 *  - плавная остановка точно в конце дороги.
 */
class MotionModel(private val route: Route, startM: Double) {
    var distance = startM
        private set
    var speed = 0.0 // м/с
        private set

    val arrived: Boolean get() = distance >= route.totalM

    private var noise = 0.0
    private val rnd = Random()

    /** Продвигает модель на [dt] секунд. */
    fun step(dt: Double, targetKmh: Float, realistic: Boolean) {
        var left = dt
        while (left > 1e-6 && !arrived) {
            val h = min(left, SUB_STEP)
            advance(h, targetKmh / 3.6, realistic)
            left -= h
        }
    }

    private fun advance(h: Double, cruise: Double, realistic: Boolean) {
        val p = route.profile
        val remaining = route.totalM - distance

        val v1 = if (realistic) {
            noise += -noise / NOISE_TAU * h + p.jitter * sqrt(2 * h / NOISE_TAU) * rnd.nextGaussian()
            noise = noise.coerceIn(-2 * p.jitter, 2 * p.jitter)

            val wanted = min(cruise * (1 + noise), min(cornerLimit(p), stopLimit(p, remaining)))
            val accelNow = p.accel * (1 - 0.6 * min(1.0, speed / 40.0)) // на высокой скорости разгон слабее
            (speed + (wanted - speed).coerceIn(-p.decel * h, accelNow * h)).coerceAtLeast(0.0)
        } else {
            cruise
        }

        distance += (speed + v1) / 2 * h
        speed = v1
        if (route.totalM - distance < 0.3) {
            distance = route.totalM
            speed = 0.0
        }
    }

    /** Скорость, при которой успеем плавно остановиться у конца дороги. */
    private fun stopLimit(p: RouteProfile, remaining: Double): Double =
        max(sqrt(2 * p.decel * 0.8 * remaining), 0.5)

    /** Предел скорости с учётом поворотов впереди: тормозим заранее, комфортным замедлением. */
    private fun cornerLimit(p: RouteProfile): Double {
        val caps = route.cornerCaps
        val comfort = p.decel * 0.7
        val look = min(((speed * speed) / (2 * comfort) / Route.CAP_STEP).toInt() + 3, 10000)
        val i0 = (distance / Route.CAP_STEP).toInt()
        var limit = Double.MAX_VALUE
        for (k in 0..look) {
            val i = i0 + k
            if (i >= caps.size) break
            val cap = caps[i]
            if (cap == Float.MAX_VALUE) continue
            val d = max(0.0, i * Route.CAP_STEP - distance)
            limit = min(limit, sqrt(cap.toDouble() * cap + 2 * comfort * d))
        }
        return limit
    }

    private companion object {
        const val SUB_STEP = 0.1
        const val NOISE_TAU = 8.0
    }
}
