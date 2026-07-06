package com.example.weatherwidget.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
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
import androidx.glance.background
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
        val weather = Store.getCachedWeather(context)
        val updatedAt = Store.getLastUpdated(context)
        provideContent {
            GlanceTheme {
                WidgetContent(weather, updatedAt)
            }
        }
    }
}

@Composable
private fun WidgetContent(weather: List<LocationWeather>, updatedAt: Long) {
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
                items(weather) { w -> LocationRow(w) }
            }
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val updatedText = if (updatedAt > 0) {
                    "Updated " + SimpleDateFormat("h:mm a", Locale.US).format(Date(updatedAt))
                } else "Waiting for first update…"
                Text(
                    updatedText,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp)
                )
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    "↻",
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                    modifier = GlanceModifier
                        .padding(horizontal = 8.dp)
                        .clickable(actionRunCallback<RefreshAction>())
                )
            }
        }
    }
}

@Composable
private fun LocationRow(w: LocationWeather) {
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
                w.aqi?.let { "AQI $it" } ?: "AQI --",
                style = TextStyle(
                    color = ColorProvider(aqiColor(w.aqi)),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            w.hours.forEach { h ->
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
        RefreshWorker.refreshNow(context)
    }
}
