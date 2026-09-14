package com.example.weatherwidget.data

import java.io.IOException
import java.net.URLEncoder
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pirate Weather forecast API (Dark Sky compatible).
 * https://docs.pirateweather.net/en/latest/API/
 */
object PirateWeatherApi {
    fun fetch(apiKey: String, lat: Double, lon: Double): Pair<Int?, List<HourForecast>> {
        val url = "https://api.pirateweather.net/forecast/$apiKey/$lat,$lon" +
            "?units=us&exclude=minutely,daily,alerts"
        val json = JSONObject(Net.getJson(url))

        val currently = json.optJSONObject("currently")
        val tempF = if (currently != null && currently.has("temperature")) {
            currently.getDouble("temperature").roundToInt()
        } else null

        val hours = mutableListOf<HourForecast>()
        val data = json.optJSONObject("hourly")?.optJSONArray("data")
        if (data != null) {
            val nowEpoch = System.currentTimeMillis() / 1000
            for (i in 0 until data.length()) {
                val h = data.getJSONObject(i)
                val time = h.optLong("time")
                if (time + 3600 <= nowEpoch) continue // skip hours already fully in the past
                val chancePct = (h.optDouble("precipProbability", 0.0) * 100).roundToInt()
                // With units=us these are all in inches; prefer the accumulation
                // fields, fall back to intensity (in/hr ~= inches over one hour).
                val inches = when {
                    h.has("liquidAccumulation") -> h.getDouble("liquidAccumulation")
                    h.has("precipAccumulation") -> h.getDouble("precipAccumulation")
                    else -> h.optDouble("precipIntensity", 0.0)
                }
                val hourTempF = if (h.has("temperature")) h.getDouble("temperature").roundToInt() else null
                hours.add(HourForecast(time, chancePct, inches, hourTempF))
                if (hours.size == 24) break
            }
        }
        return tempF to hours
    }
}

/**
 * Hourly conditions for the past 24 hours, from Open-Meteo (keyless).
 * Pirate Weather's Time Machine proved unreliable for recent hours: it
 * returned partial days, then began rejecting requests outright (2026-07).
 */
object HistoryApi {
    fun fetchPast24h(lat: Double, lon: Double): DayHistory {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&hourly=temperature_2m,precipitation&past_days=2&forecast_days=1" +
            "&temperature_unit=fahrenheit&precipitation_unit=inch" +
            "&timeformat=unixtime&timezone=auto"
        val json = JSONObject(Net.getJson(url))
        val tz = json.optString("timezone").ifEmpty { null }
        val hourly = json.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temps = hourly.optJSONArray("temperature_2m")
        val precs = hourly.optJSONArray("precipitation")
        val now = System.currentTimeMillis() / 1000
        val cutoff = now - 24 * 3600
        val out = mutableListOf<HourHistory>()
        for (i in 0 until times.length()) {
            val t = times.getLong(i)
            if (t < cutoff || t > now) continue
            val tempF = if (temps == null || temps.isNull(i)) null
            else temps.getDouble(i).roundToInt()
            val rain = if (precs == null || precs.isNull(i)) 0.0 else precs.getDouble(i)
            out.add(HourHistory(t, tempF, rain))
        }
        out.sortByDescending { it.timeEpoch } // newest first
        return DayHistory(out, out.sumOf { it.rainInches }, tz)
    }
}

/**
 * Synoptic Data: measured rain-gauge readings from stations near a point.
 * Uses the standard timeseries service with hourly gauge accumulations and
 * sums them locally — the pre-summed "precip" service needs a paid tier.
 * https://docs.synopticdata.com/
 */
object GaugeApi {
    fun fetchPast24h(token: String, lat: Double, lon: Double): List<GaugeReading> {
        val url = "https://api.synopticdata.com/v2/stations/timeseries" +
            "?radius=$lat,$lon,25&limit=8&recent=1440" +
            "&vars=precip_accum_one_hour&units=english&token=$token"
        val json = JSONObject(Net.getJson(url))
        val summary = json.optJSONObject("SUMMARY")
        if (summary != null && summary.optInt("RESPONSE_CODE", 1) != 1) {
            throw IOException(summary.optString("RESPONSE_MESSAGE", "Synoptic error"))
        }
        val stations = json.optJSONArray("STATION") ?: return emptyList()
        val out = mutableListOf<GaugeReading>()
        for (i in 0 until stations.length()) {
            val s = stations.getJSONObject(i)
            val obs = s.optJSONObject("OBSERVATIONS") ?: continue
            // Values arrive as e.g. "precip_accum_one_hour_set_1"; a station
            // with a second sensor gets _set_2, so match by prefix.
            val key = obs.keys().asSequence()
                .firstOrNull { it.startsWith("precip_accum_one_hour") } ?: continue
            val values = obs.optJSONArray(key) ?: continue
            val times = obs.optJSONArray("date_time") ?: continue
            // Most stations report the one-hour accumulation once per hour,
            // but some networks report sub-hourly; bucket by hour and keep
            // each hour's largest reading so nothing is double-counted. The
            // bucket count doubles as the coverage figure ("22 of 24 hrs").
            val byHour = HashMap<String, Double>()
            val n = minOf(values.length(), times.length())
            for (j in 0 until n) {
                if (values.isNull(j)) continue
                val hour = times.optString(j).take(13) // "2026-07-07T14"
                if (hour.length < 13) continue
                val v = values.optDouble(j, 0.0)
                val prev = byHour[hour]
                if (prev == null || v > prev) byHour[hour] = v
            }
            if (byHour.isEmpty()) continue
            val name = s.optString("NAME").ifEmpty { s.optString("STID", "Station") }
            val d = s.optDouble("DISTANCE", Double.NaN)
            out.add(
                GaugeReading(
                    name,
                    if (d.isNaN()) null else d,
                    byHour.values.sum(),
                    minOf(byHour.size, 24),
                )
            )
        }
        return out.sortedBy { it.distanceMiles ?: Double.MAX_VALUE }.take(5)
    }
}

/**
 * AirNow current observations API. Returns the worst AQI across the reported
 * pollutants (that is how airnow.gov itself summarizes a location).
 * https://docs.airnowapi.org/
 */
object AirNowApi {
    fun fetch(apiKey: String, lat: Double, lon: Double): Pair<Int?, String?> {
        val url = "https://www.airnowapi.org/aq/observation/latLong/current/" +
            "?format=application/json&latitude=$lat&longitude=$lon&distance=50&API_KEY=$apiKey"
        val arr = JSONArray(Net.getJson(url))
        var worstAqi: Int? = null
        var category: String? = null
        for (i in 0 until arr.length()) {
            val obs = arr.getJSONObject(i)
            val aqi = obs.optInt("AQI", -1)
            if (aqi >= 0 && aqi > (worstAqi ?: -1)) {
                worstAqi = aqi
                category = obs.optJSONObject("Category")?.optString("Name")
            }
        }
        return worstAqi to category
    }
}

/**
 * Location search, all without API keys:
 *  - street addresses -> US Census geocoder (exact coordinates for the address)
 *  - city names and most postal codes -> Open-Meteo's geocoder
 *  - US ZIP codes Open-Meteo misses -> Zippopotam.us
 */
object GeocodingApi {
    fun search(query: String): List<GeoResult> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        val isZip = trimmed.matches(Regex("\\d{5}"))
        // Queries starting with a house number ("123 Main St, ...") are street
        // addresses, not cities or ZIPs.
        if (!isZip && Regex("^\\d+\\s+\\S+").containsMatchIn(trimmed)) {
            val matches = censusAddress(trimmed)
            if (matches.isNotEmpty()) return matches
        }
        val results = openMeteo(trimmed)
        if (results.isNotEmpty()) return results
        if (isZip) return zippopotam(trimmed)
        return censusAddress(trimmed)
    }

    private fun censusAddress(address: String): List<GeoResult> {
        return try {
            val q = URLEncoder.encode(address, "UTF-8")
            val url = "https://geocoding.geo.census.gov/geocoder/locations/onelineaddress" +
                "?address=$q&benchmark=Public_AR_Current&format=json"
            val matches = JSONObject(Net.getJson(url))
                .getJSONObject("result")
                .optJSONArray("addressMatches") ?: return emptyList()
            (0 until matches.length()).map { i ->
                val m = matches.getJSONObject(i)
                val c = m.getJSONObject("coordinates")
                GeoResult(prettyAddress(m.getString("matchedAddress")), c.getDouble("y"), c.getDouble("x"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The Census geocoder returns ALL-CAPS addresses; title-case them for display. */
    private fun prettyAddress(s: String): String =
        s.split(" ").joinToString(" ") { word ->
            val core = word.trimEnd(',')
            val cased =
                if (core.length <= 2) core.uppercase()
                else core.lowercase().replaceFirstChar { it.titlecase() }
            cased + word.substring(core.length)
        }

    private fun openMeteo(query: String): List<GeoResult> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=$q&count=10&language=en&format=json"
        val json = JSONObject(Net.getJson(url))
        val arr = json.optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<GeoResult>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val label = listOf(r.optString("name"), r.optString("admin1"), r.optString("country_code"))
                .filter { it.isNotEmpty() }
                .joinToString(", ")
            out.add(GeoResult(label, r.getDouble("latitude"), r.getDouble("longitude")))
        }
        return out
    }

    private fun zippopotam(zip: String): List<GeoResult> {
        return try {
            val json = JSONObject(Net.getJson("https://api.zippopotam.us/us/$zip"))
            val place = json.getJSONArray("places").getJSONObject(0)
            val label = "${place.getString("place name")}, ${place.getString("state abbreviation")} $zip"
            listOf(GeoResult(label, place.getString("latitude").toDouble(), place.getString("longitude").toDouble()))
        } catch (e: Exception) {
            emptyList()
        }
    }
}
