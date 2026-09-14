package com.example.weatherwidget.work

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.weatherwidget.data.AirNowApi
import com.example.weatherwidget.data.LocationWeather
import com.example.weatherwidget.data.PirateWeatherApi
import com.example.weatherwidget.data.SavedLocation
import com.example.weatherwidget.data.Store
import com.example.weatherwidget.widget.DATA_VERSION_KEY
import com.example.weatherwidget.widget.REFRESHING_KEY
import com.example.weatherwidget.widget.WeatherWidget
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Fetches weather + AQI for every saved location, caches it, and redraws the widget. */
class RefreshWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        try {
            val pirateKey = Store.getPirateKey(ctx)
            val airNowKey = Store.getAirNowKey(ctx)
            val locations = Store.getLocations(ctx)

            if (locations.isEmpty()) {
                // Clear the cache so widgets stop rendering removed locations.
                Store.setCachedWeather(ctx, emptyList())
                return@withContext Result.success()
            }
            if (pirateKey.isEmpty()) {
                return@withContext Result.success()
            }

            // Raise the indicator on every instance for the duration of the fetch.
            // This also re-raises it when a queued (appended) refresh takes over
            // right after the previous run cleared it.
            setRefreshingAll(ctx, true)

            val previous = Store.getCachedWeather(ctx).associateBy { it.location.name }
            // Fetch all locations concurrently; one slow API no longer holds up the rest.
            val results = coroutineScope {
                locations.map { loc ->
                    async { fetchLocation(pirateKey, airNowKey, previous, loc) }
                }.awaitAll()
            }

            Store.setCachedWeather(ctx, results)
            // Never Result.retry(): a retry waiting out its backoff holds the unique
            // work slot, so later manual taps would be silently swallowed. Failures
            // keep the previous data (marked with an error); the next tap or the
            // hourly run is the retry.
            Result.success()
        } finally {
            // NonCancellable so this publish runs even if WorkManager stops the
            // worker mid-fetch. Writing through the per-instance Glance state is
            // what makes a still-alive widget session actually repaint.
            withContext(NonCancellable) {
                setRefreshingAll(ctx, false)
            }
        }
    }

    /**
     * Sets the per-instance refreshing flag everywhere and repaints. Clearing it
     * also bumps the data version so live sessions re-read the cache. Each write
     * is isolated: one just-removed widget's stale state store must not stop the
     * other instances from updating, nor fail the whole run.
     */
    private suspend fun setRefreshingAll(ctx: Context, refreshing: Boolean) {
        val ids = runCatching {
            GlanceAppWidgetManager(ctx).getGlanceIds(WeatherWidget::class.java)
        }.getOrDefault(emptyList())
        ids.forEach { id ->
            runCatching {
                updateAppWidgetState(ctx, id) { prefs ->
                    prefs[REFRESHING_KEY] = refreshing
                    if (!refreshing) {
                        prefs[DATA_VERSION_KEY] = (prefs[DATA_VERSION_KEY] ?: 0L) + 1L
                    }
                }
            }
        }
        runCatching { WeatherWidget().updateAll(ctx) }
    }

    private fun fetchLocation(
        pirateKey: String,
        airNowKey: String,
        previous: Map<String, LocationWeather>,
        loc: SavedLocation,
    ): LocationWeather {
        return try {
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
            // Expedited work starts promptly even under Doze/battery saver (a plain
            // enqueue can be deferred for minutes). No getForegroundInfo override is
            // needed: with minSdk 31 the pre-Android-12 foreground fallback never runs.
            // No network constraint either — an offline tap should fail fast into a
            // visible state instead of leaving the request and indicator pending.
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            // APPEND_OR_REPLACE: an in-flight refresh is never cancelled (impatient
            // tapping can't restart it), and a request made while one is running
            // chains a fresh run after it — so a refresh triggered by adding or
            // removing a location always ends up seeing the new list.
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "weather_refresh_now", ExistingWorkPolicy.APPEND_OR_REPLACE, request
            )
        }
    }
}
