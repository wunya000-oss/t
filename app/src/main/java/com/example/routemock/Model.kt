package com.example.routemock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

data class Turn(val distM: Double, val text: String)

/** Маршрут по дорогам: геометрия, длина, подсказки поворотов и ограничения скорости в поворотах. */
class Route(
    rawPoints: List<LatLon>,
    steps: List<Pair<Double, String>>,
    stepsTotalM: Double,
    val durationS: Double,
    val profile: RouteProfile,
) {
    /** Геометрия без дублирующихся точек (они ломают расчёт курса). */
    val points: List<LatLon> = rawPoints.filterIndexed { i, p ->
        i == 0 || haversine(rawPoints[i - 1], p) > 0.05
    }

    private val cum = DoubleArray(points.size)
    val totalM: Double
    val turns: List<Turn>

    init {
        for (i in 1 until points.size) cum[i] = cum[i - 1] + haversine(points[i - 1], points[i])
        totalM = cum.last()
        val k = if (stepsTotalM > 0) totalM / stepsTotalM else 1.0
        turns = steps.map { (d, text) -> Turn((d * k).coerceIn(0.0, totalM), text) }
    }

    /** Индекс сегмента, на котором лежит расстояние [d]. */
    fun indexAt(d: Double): Int {
        val r = cum.binarySearch(d.coerceIn(0.0, totalM))
        return (if (r >= 0) r else -r - 2).coerceIn(0, points.size - 2)
    }

    /** Точка и курс на расстоянии [d] метров от старта. */
    fun at(d: Double): Pair<LatLon, Float> {
        val x = d.coerceIn(0.0, totalM)
        val i = indexAt(x)
        val a = points[i]
        val b = points[i + 1]
        val seg = cum[i + 1] - cum[i]
        val t = if (seg > 0) (x - cum[i]) / seg else 0.0
        val pos = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
        return pos to bearing(a, b).toFloat()
    }

    fun nextTurn(traveled: Double): Turn? = turns.firstOrNull { it.distM > traveled + 1 }

    /**
     * Допустимая скорость (м/с) в каждой точке пути с шагом [CAP_STEP] м.
     * Считается по кривизне: чем резче поворот, тем ниже предел. Float.MAX_VALUE — прямой участок.
     */
    val cornerCaps: FloatArray by lazy(LazyThreadSafetyMode.NONE) {
        val n = (totalM / CAP_STEP).toInt() + 2
        val bearings = FloatArray(n) { at(it * CAP_STEP).second }
        FloatArray(n) { i ->
            val lo = max(i - 1, 0)
            val hi = min(i + 1, n - 1)
            val span = (hi - lo) * CAP_STEP
            val diff = abs((bearings[hi] - bearings[lo] + 540f) % 360f - 180f)
            if (diff < MIN_TURN_DEG || span <= 0.0) {
                Float.MAX_VALUE
            } else {
                val kappa = Math.toRadians(diff.toDouble()) / span
                sqrt(profile.lateralAcc / kappa).coerceAtLeast(profile.minCornerMs).toFloat()
            }
        }
    }

    companion object {
        const val CAP_STEP = 10.0
        const val MIN_TURN_DEG = 3f
    }
}

enum class RouteProfile(
    val title: String,
    private val base: String,
    /** Разгон, м/с² */
    val accel: Double,
    /** Торможение, м/с² */
    val decel: Double,
    /** Допустимое поперечное ускорение в повороте, м/с² */
    val lateralAcc: Double,
    /** Минимальная скорость в самом крутом повороте, м/с */
    val minCornerMs: Double,
    /** Амплитуда случайных колебаний скорости (доля от заданной) */
    val jitter: Double,
) {
    CAR("Авто", "https://router.project-osrm.org/route/v1/driving/", 1.8, 2.8, 2.2, 3.5, 0.06),
    BIKE("Велосипед", "https://routing.openstreetmap.de/routed-bike/route/v1/driving/", 1.0, 2.0, 1.6, 2.5, 0.07),
    FOOT("Пешком", "https://routing.openstreetmap.de/routed-foot/route/v1/driving/", 0.7, 1.5, 40.0, 0.8, 0.08);

    /** Запрашивает маршрут по дорогам без ключа API (OSRM). */
    suspend fun fetch(a: LatLon, b: LatLon): Route = withContext(Dispatchers.IO) {
        val url = "$base${a.lon},${a.lat};${b.lon},${b.lat}?overview=full&geometries=geojson&steps=true"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 40_000
            setRequestProperty("User-Agent", "RouteMock/1.1")
        }
        try {
            val http = conn.responseCode
            val stream = if (http in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            check(json != null) { "сервер вернул HTTP $http" }
            val code = json.optString("code")
            check(code == "Ok") {
                when (code) {
                    "NoRoute" -> "между точками нет дороги"
                    "NoSegment" -> "рядом с точкой нет дороги"
                    else -> json.optString("message").ifBlank { "HTTP $http" }
                }
            }

            val route = json.getJSONArray("routes").getJSONObject(0)
            val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
            val points = List(coords.length()) { i ->
                coords.getJSONArray(i).let { LatLon(it.getDouble(1), it.getDouble(0)) }
            }
            val steps = route.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
            val list = ArrayList<Pair<Double, String>>(steps.length())
            var acc = 0.0
            for (i in 0 until steps.length()) {
                val s = steps.getJSONObject(i)
                val m = s.getJSONObject("maneuver")
                list.add(acc to phrase(m.getString("type"), m.optString("modifier")))
                acc += s.optDouble("distance", 0.0)
            }
            val result = Route(points, list, acc, route.optDouble("duration", 0.0), this@RouteProfile)
            check(result.points.size >= 2 && result.totalM >= 5.0) { "точки совпадают" }
            result
        } finally {
            conn.disconnect()
        }
    }
}

private val DIRS = mapOf(
    "left" to "налево",
    "right" to "направо",
    "slight left" to "плавно налево",
    "slight right" to "плавно направо",
    "sharp left" to "резко налево",
    "sharp right" to "резко направо",
)

private fun phrase(type: String, modifier: String): String = when {
    type == "depart" -> "Начните движение"
    type == "arrive" -> "Вы прибыли"
    type == "roundabout" || type == "rotary" || type == "roundabout turn" -> "Круговое движение"
    modifier == "uturn" -> "Развернитесь"
    type == "on ramp" -> "Выезжайте на трассу"
    type == "off ramp" -> "Съезжайте с трассы"
    type == "merge" -> "Перестройтесь"
    type == "fork" -> when (modifier) {
        "left", "slight left", "sharp left" -> "На развилке левее"
        "right", "slight right", "sharp right" -> "На развилке правее"
        else -> "На развилке прямо"
    }
    modifier == "straight" -> "Продолжайте прямо"
    else -> DIRS[modifier]?.let { "Поверните $it" } ?: "Продолжайте движение"
}

data class SimState(
    val route: Route? = null,
    val running: Boolean = false,
    val traveledM: Double = 0.0,
    /** Скорость, заданная ползунком, км/ч */
    val speedKmh: Float = 40f,
    /** Фактическая скорость в данный момент, км/ч */
    val currentKmh: Float = 0f,
    /** Плавный разгон, замедление в поворотах, колебания скорости */
    val realistic: Boolean = true,
    val pos: LatLon? = null,
    val error: String? = null,
    val needsMockApp: Boolean = false,
)

/** Общее состояние между UI и фоновым сервисом. */
object SimulationHub {
    val state = MutableStateFlow(SimState())
}

private val NUMBER = Regex("""-?\d+(?:[.,]\d+)?""")

/** Понимает «50.4501, 30.5234», «50.4501 30.5234», «50,4501; 30,5234» и т.п. */
fun parseCoords(text: String): LatLon? {
    val nums = NUMBER.findAll(text.replace('−', '-')).map { it.value.replace(',', '.').toDoubleOrNull() }.toList()
    if (nums.size != 2) return null
    val a = nums[0] ?: return null
    val b = nums[1] ?: return null
    return if (a in -90.0..90.0 && b in -180.0..180.0) LatLon(a, b) else null
}

fun haversine(a: LatLon, b: LatLon): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * 6_371_000.0 * asin(sqrt(h))
}

private fun bearing(a: LatLon, b: LatLon): Double {
    val la1 = Math.toRadians(a.lat)
    val la2 = Math.toRadians(b.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val y = sin(dLon) * cos(la2)
    val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

fun fmtDist(m: Double): String =
    if (m < 1000) "${m.roundToInt()} м" else "%.1f км".format(java.util.Locale.US, m / 1000)

fun fmtDuration(sec: Double): String {
    val min = (sec / 60).roundToInt()
    return if (min < 60) "$min мин" else "${min / 60} ч ${min % 60} мин"
}
