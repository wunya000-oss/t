package com.example.routemock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

data class Turn(val distM: Double, val text: String)

/** Маршрут по дорогам: геометрия, длина и подсказки поворотов вдоль пути. */
class Route(val points: List<LatLon>, maneuvers: List<Pair<LatLon, String>>, val durationS: Double) {
    private val cum = DoubleArray(points.size)
    val totalM: Double
    val turns: List<Turn>

    init {
        for (i in 1 until points.size) cum[i] = cum[i - 1] + haversine(points[i - 1], points[i])
        totalM = cum.last()
        turns = maneuvers.map { (pos, text) ->
            Turn(cum[points.indices.minBy { haversine(points[it], pos) }], text)
        }
    }

    /** Точка и курс на расстоянии [d] метров от старта. */
    fun at(d: Double): Pair<LatLon, Float> {
        val x = d.coerceIn(0.0, totalM)
        val r = cum.binarySearch(x)
        val i = (if (r >= 0) r else -r - 2).coerceIn(0, points.size - 2)
        val a = points[i]
        val b = points[i + 1]
        val seg = cum[i + 1] - cum[i]
        val t = if (seg > 0) (x - cum[i]) / seg else 0.0
        val pos = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
        return pos to bearing(a, b).toFloat()
    }

    fun nextTurn(traveled: Double): Turn? = turns.firstOrNull { it.distM > traveled + 1 }
}

enum class RouteProfile(val title: String, private val base: String) {
    CAR("Авто", "https://router.project-osrm.org/route/v1/driving/"),
    BIKE("Велосипед", "https://routing.openstreetmap.de/routed-bike/route/v1/driving/"),
    FOOT("Пешком", "https://routing.openstreetmap.de/routed-foot/route/v1/driving/");

    /** Запрашивает маршрут по дорогам без ключа API (OSRM). */
    suspend fun fetch(a: LatLon, b: LatLon): Route = withContext(Dispatchers.IO) {
        val url = "$base${a.lon},${a.lat};${b.lon},${b.lat}?overview=full&geometries=geojson&steps=true"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "RouteMock/1.0")
        }
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        check(json.optString("code") == "Ok") { "маршрут не найден" }

        val route = json.getJSONArray("routes").getJSONObject(0)
        val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
        val points = List(coords.length()) { i ->
            coords.getJSONArray(i).let { LatLon(it.getDouble(1), it.getDouble(0)) }
        }
        val steps = route.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
        val maneuvers = List(steps.length()) { i ->
            val m = steps.getJSONObject(i).getJSONObject("maneuver")
            val loc = m.getJSONArray("location")
            LatLon(loc.getDouble(1), loc.getDouble(0)) to phrase(m.getString("type"), m.optString("modifier"))
        }
        Route(points, maneuvers, route.optDouble("duration", 0.0))
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
    type == "roundabout" || type == "rotary" -> "Поезжайте по кругу"
    modifier == "uturn" -> "Развернитесь"
    modifier == "straight" -> "Продолжайте прямо"
    else -> DIRS[modifier]?.let { "Поверните $it" } ?: "Продолжайте движение"
}

data class SimState(
    val route: Route? = null,
    val running: Boolean = false,
    val traveledM: Double = 0.0,
    val speedKmh: Float = 40f,
    val pos: LatLon? = null,
    val error: String? = null,
)

/** Общее состояние между UI и фоновым сервисом. */
object SimulationHub {
    val state = MutableStateFlow(SimState())
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
    if (m < 1000) "${m.roundToInt()} м" else "%.1f км".format(m / 1000)
