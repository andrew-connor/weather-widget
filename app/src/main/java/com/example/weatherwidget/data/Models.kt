package com.example.weatherwidget.data

data class SavedLocation(
    val name: String,
    val lat: Double,
    val lon: Double,
)

data class HourForecast(
    val timeEpoch: Long,
    val precipChancePct: Int,
    val precipInches: Double,
    val tempF: Int? = null,
)

data class LocationWeather(
    val location: SavedLocation,
    val tempF: Int?,
    val hours: List<HourForecast>,
    val aqi: Int?,
    val aqiCategory: String?,
    val error: String? = null,
)

data class GeoResult(
    val label: String,
    val lat: Double,
    val lon: Double,
)

data class HourHistory(
    val timeEpoch: Long,
    val tempF: Int?,
    val rainInches: Double,
)

data class DayHistory(
    val hours: List<HourHistory>,
    val totalInches: Double,
    val timeZoneId: String?,
)

data class GaugeReading(
    val name: String,
    val distanceMiles: Double?,
    val inches: Double,
    val hoursReported: Int,
)
