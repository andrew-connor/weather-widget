package com.example.weatherwidget.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.weatherwidget.data.AirNowApi
import com.example.weatherwidget.data.LocationWeather
import com.example.weatherwidget.data.PirateWeatherApi
import com.example.weatherwidget.data.Store
import com.example.weatherwidget.widget.WeatherWidget
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fetches weather + AQI for every saved location, caches it, and redraws the widget. */
class RefreshWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val pirateKey = Store.getPirateKey(ctx)
        val airNowKey = Store.getAirNowKey(ctx)
        val locations = Store.getLocations(ctx)

        if (pirateKey.isEmpty() || locations.isEmpty()) {
            WeatherWidget().updateAll(ctx)
            return@withContext Result.success()
        }

        val previous = Store.getCachedWeather(ctx).associateBy { it.location.name }
        val results = locations.map { loc ->
            try {
                val (tempF, hours) = PirateWeatherApi.fetch(pirateKey, loc.lat, loc.lon)
                var aqi: Int? = null
                var aqiCategory: String? = null
                if (airNowKey.isNotEmpty()) {
                    try {
                        val r = AirNowApi.fetch(airNowKey, loc.lat, loc.lon)
                        aqi = r.first
                        aqiCategory = r.second
                    } catch (_: Exception) {
                        // Keep the weather data even if the AQI call fails.
                    }
                }
                LocationWeather(loc, tempF, hours, aqi, aqiCategory)
            } catch (e: Exception) {
                // Keep showing the previous data for this location rather than blanking it.
                previous[loc.name]?.copy(error = e.message)
                    ?: LocationWeather(loc, null, emptyList(), null, null, e.message)
            }
        }

        Store.setCachedWeather(ctx, results)
        Store.setForecastPage(ctx, 0) // fresh data lands back on the next six hours
        WeatherWidget().updateAll(ctx)

        if (results.any { it.error != null } && runAttemptCount < 2) Result.retry()
        else Result.success()
    }

    companion object {
        fun schedulePeriodic(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(60, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "weather_refresh", ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }

        fun refreshNow(ctx: Context) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "weather_refresh_now", ExistingWorkPolicy.REPLACE, request
            )
        }
    }
}
