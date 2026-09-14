package com.example.weatherwidget.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.example.weatherwidget.MainActivity
import com.example.weatherwidget.data.HourForecast
import com.example.weatherwidget.data.LocationWeather
import com.example.weatherwidget.data.Store
import com.example.weatherwidget.work.RefreshWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Per-instance Glance state. Anything the widget renders dynamically must flow
// through this state rather than values captured in provideGlance: while a
// widget session is alive (~45s after a tap), update()/updateAll() only reload
// this state and recompose — provideGlance is NOT re-run, so captured values
// would freeze and a completion repaint would show stale data.
internal val FORECAST_PAGE_KEY = intPreferencesKey("forecast_page")
internal val REFRESHING_KEY = booleanPreferencesKey("refreshing")
internal val DATA_VERSION_KEY = longPreferencesKey("data_version")

class WeatherWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeatherWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshWorker.schedulePeriodic(context)
        RefreshWorker.refreshNow(context)
    }
}

class WeatherWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                WidgetContent()
            }
        }
    }
}

@Composable
private fun WidgetContent() {
    // Everything is read inside the composition so every recomposition sees the
    // latest values (see the note on the state keys above).
    val context = LocalContext.current
    currentState(DATA_VERSION_KEY) // subscribe: the worker bumps this on new data
    val refreshing = currentState(REFRESHING_KEY) ?: false
    val weather = Store.getCachedWeather(context)
    val updatedAt = Store.getLastUpdated(context)
    // Clamp to the cached data so a stored page can never render blank rows.
    val maxPage = ((weather.maxOfOrNull { it.hours.size } ?: 0) - 1).coerceAtLeast(0) / 6
    val page = (currentState(FORECAST_PAGE_KEY) ?: 0).coerceIn(0, maxPage)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(10.dp)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        if (weather.isEmpty()) {
            Text(
                "Tap to add API keys and locations",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp)
            )
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                items(weather) { w -> LocationRow(w, page) }
            }
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val allFailed = weather.isNotEmpty() && weather.all { it.error != null }
                val updatedText = when {
                    refreshing -> "Updating…"
                    updatedAt == 0L -> "Waiting for first update…"
                    allFailed -> "Update failed"
                    else -> "Updated " + SimpleDateFormat("h:mm a", Locale.US).format(Date(updatedAt))
                }
                Text(
                    updatedText,
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
                    modifier = GlanceModifier.defaultWeight()
                )
                // Generous padding on the clickable nodes: in Glance the padding is
                // part of the tappable view, so these approach the 48dp guideline
                // instead of the bare glyph's ~20dp.
                if (weather.any { it.hours.size > 6 }) {
                    Text(
                        "‹",
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp),
                        modifier = GlanceModifier
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                            .clickable(actionRunCallback<PageBackAction>())
                    )
                    Text(
                        "${page * 6 + 1}–${page * 6 + 6}h",
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp)
                    )
                    Text(
                        "›",
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp),
                        modifier = GlanceModifier
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                            .clickable(actionRunCallback<PageForwardAction>())
                    )
                }
                Text(
                    "↻",
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp),
                    modifier = GlanceModifier
                        .padding(horizontal = 9.dp, vertical = 6.dp)
                        .clickable(actionRunCallback<RefreshAction>())
                )
            }
        }
    }
}

@Composable
private fun LocationRow(w: LocationWeather, page: Int) {
    Column(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                w.location.name.substringBefore(","),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                w.tempF?.let { "$it°" } ?: "--°",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp)
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                w.aqi?.let { "AQI $it (${aqiInitials(w.aqiCategory, it)})" } ?: "AQI --",
                style = TextStyle(
                    color = ColorProvider(aqiColor(w.aqi)),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            w.hours.drop(page * 6).take(6).forEach { h ->
                HourCell(h, GlanceModifier.defaultWeight())
            }
        }
    }
}

@Composable
private fun HourCell(h: HourForecast, modifier: GlanceModifier) {
    // "2PM" becomes "2p" so the hour's temperature fits on the same line
    val hourLabel = SimpleDateFormat("ha", Locale.US)
        .format(Date(h.timeEpoch * 1000))
        .lowercase()
        .removeSuffix("m")
    val topLine = h.tempF?.let { "$hourLabel $it°" } ?: hourLabel
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            topLine,
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 10.sp)
        )
        Text(
            "${h.precipChancePct}%",
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        )
        Text(
            formatInches(h.precipInches),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 9.sp)
        )
    }
}

private fun formatInches(inches: Double): String =
    if (inches < 0.005) "—" else String.format(Locale.US, "%.2f\"", inches).removePrefix("0")

/** "Unhealthy for Sensitive Groups" -> "UFSG"; category from AQI ranges if AirNow omits it. */
private fun aqiInitials(category: String?, aqi: Int): String {
    val name = category ?: when {
        aqi <= 50 -> "Good"
        aqi <= 100 -> "Moderate"
        aqi <= 150 -> "Unhealthy for Sensitive Groups"
        aqi <= 200 -> "Unhealthy"
        aqi <= 300 -> "Very Unhealthy"
        else -> "Hazardous"
    }
    return name.split(" ").filter { it.isNotEmpty() }.joinToString("") { it.first().uppercase() }
}

/** Standard EPA AQI category colors. */
private fun aqiColor(aqi: Int?): Color = when {
    aqi == null -> Color(0xFF9E9E9E)
    aqi <= 50 -> Color(0xFF43A047)   // Good
    aqi <= 100 -> Color(0xFFF9A825)  // Moderate
    aqi <= 150 -> Color(0xFFEF6C00)  // Unhealthy for sensitive groups
    aqi <= 200 -> Color(0xFFD32F2F)  // Unhealthy
    aqi <= 300 -> Color(0xFF8E24AA)  // Very unhealthy
    else -> Color(0xFF7B1F2B)        // Hazardous
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { prefs -> prefs[REFRESHING_KEY] = true }
        WeatherWidget().update(context, glanceId) // instant "Updating…" feedback
        RefreshWorker.refreshNow(context)
    }
}

// Page state is per widget instance and pages wrap around the number of pages
// the cached data can actually fill, so every tap visibly responds; updating
// only the tapped instance keeps the repaint path minimal.
class PageBackAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val pages = pageCount(context)
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[FORECAST_PAGE_KEY] = ((prefs[FORECAST_PAGE_KEY] ?: 0) - 1).mod(pages)
        }
        WeatherWidget().update(context, glanceId)
    }
}

class PageForwardAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val pages = pageCount(context)
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[FORECAST_PAGE_KEY] = ((prefs[FORECAST_PAGE_KEY] ?: 0) + 1).mod(pages)
        }
        WeatherWidget().update(context, glanceId)
    }
}

/** Number of six-hour pages the cached data can fill (at least 1). */
private fun pageCount(context: Context): Int {
    val maxHours = Store.getCachedWeather(context).maxOfOrNull { it.hours.size } ?: 0
    return (maxHours - 1).coerceAtLeast(0) / 6 + 1
}
