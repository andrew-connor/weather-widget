package com.example.weatherwidget.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** All persistence: API keys, saved locations, and the cached weather the widget renders. */
object Store {
    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("weather_widget", Context.MODE_PRIVATE)

    fun getPirateKey(ctx: Context): String = prefs(ctx).getString("pirate_key", "") ?: ""
    fun setPirateKey(ctx: Context, v: String) =
        prefs(ctx).edit().putString("pirate_key", v.trim()).apply()

    fun getAirNowKey(ctx: Context): String = prefs(ctx).getString("airnow_key", "") ?: ""
    fun setAirNowKey(ctx: Context, v: String) =
        prefs(ctx).edit().putString("airnow_key", v.trim()).apply()

    fun getLocations(ctx: Context): List<SavedLocation> {
        val arr = JSONArray(prefs(ctx).getString("locations", "[]") ?: "[]")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SavedLocation(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"))
        }
    }

    fun setLocations(ctx: Context, locations: List<SavedLocation>) {
        val arr = JSONArray()
        locations.forEach {
            arr.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon))
        }
        prefs(ctx).edit().putString("locations", arr.toString()).apply()
    }

    fun getHistoryLocations(ctx: Context): List<SavedLocation> {
        val arr = JSONArray(prefs(ctx).getString("history_locations", "[]") ?: "[]")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SavedLocation(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"))
        }
    }

    fun setHistoryLocations(ctx: Context, locations: List<SavedLocation>) {
        val arr = JSONArray()
        locations.forEach {
            arr.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon))
        }
        prefs(ctx).edit().putString("history_locations", arr.toString()).apply()
    }

    fun getLastUpdated(ctx: Context): Long = prefs(ctx).getLong("last_updated", 0L)

    fun setCachedWeather(ctx: Context, list: List<LocationWeather>) {
        val arr = JSONArray()
        for (w in list) {
            val hours = JSONArray()
            w.hours.forEach {
                hours.put(
                    JSONObject()
                        .put("t", it.timeEpoch)
                        .put("p", it.precipChancePct)
                        .put("in", it.precipInches)
                        .put("ht", it.tempF ?: JSONObject.NULL)
                )
            }
            arr.put(
                JSONObject()
                    .put("name", w.location.name)
                    .put("lat", w.location.lat)
                    .put("lon", w.location.lon)
                    .put("tempF", w.tempF ?: JSONObject.NULL)
                    .put("aqi", w.aqi ?: JSONObject.NULL)
                    .put("aqiCategory", w.aqiCategory ?: JSONObject.NULL)
                    .put("error", w.error ?: JSONObject.NULL)
                    .put("hours", hours)
            )
        }
        prefs(ctx).edit()
            .putString("weather_cache", arr.toString())
            .putLong("last_updated", System.currentTimeMillis())
            .apply()
    }

    fun getCachedWeather(ctx: Context): List<LocationWeather> {
        return try {
            val arr = JSONArray(prefs(ctx).getString("weather_cache", "[]") ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val hoursArr = o.optJSONArray("hours") ?: JSONArray()
                LocationWeather(
                    location = SavedLocation(o.getString("name"), o.getDouble("lat"), o.getDouble("lon")),
                    tempF = if (o.isNull("tempF")) null else o.getInt("tempF"),
                    hours = (0 until hoursArr.length()).map { j ->
                        val h = hoursArr.getJSONObject(j)
                        HourForecast(
                            h.getLong("t"), h.getInt("p"), h.getDouble("in"),
                            if (h.isNull("ht")) null else h.getInt("ht"),
                        )
                    },
                    aqi = if (o.isNull("aqi")) null else o.getInt("aqi"),
                    aqiCategory = if (o.isNull("aqiCategory")) null else o.getString("aqiCategory"),
                    error = if (o.isNull("error")) null else o.getString("error"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
