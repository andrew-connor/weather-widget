# Weather Widget

A personal Android home/lock screen widget showing, for any number of saved locations:

- Current temperature (°F)
- Chance of precipitation and expected rainfall for each of the next six hours, with the forecast temperature per hour
- Current air quality index (AQI) from AirNow with the category initialism, color-coded with the standard EPA scale

The app also has a "Past 24 hours" view for any searched location: hourly temperature and rainfall, the 24-hour rainfall total, and measured totals from nearby rain gauges.

Weather data (forecast and past 24 hours) comes from the [Pirate Weather API](https://pirateweather.net); air quality from the [AirNow API](https://docs.airnowapi.org); rain-gauge observations from the [Synoptic Data API](https://synopticdata.com). Location search (street address, ZIP code, or city) uses the US Census geocoder, Open-Meteo geocoder, and Zippopotam — none of which require a key.

## Setup

1. Get free API keys from [Pirate Weather](https://pirateweather.net) and [AirNow](https://docs.airnowapi.org/account/request/), and optionally a free public token from [Synoptic Data](https://synopticdata.com) for the rain-gauge readings.
2. Build and install the app (see below), open it, paste in both keys, and save.
3. Search for locations and add the ones you want.
4. Add the **Weather Widget** to your home screen. On Pixel phones (Android 16 QPR2+), it can also be added to the lock screen via Settings → Display & touch → Lock screen → Widgets on lock screen.

Keys are stored only in the app's private preferences on the device; they are never hardcoded or committed.

## Building

Open the project in Android Studio, let it sync, and press Run with a device connected (USB debugging enabled). The widget refreshes hourly in the background via WorkManager, and on demand from the app or the widget's refresh button.

Note: `build.gradle.kts` redirects build output to `D:/gradle-builds/weather_widget/` because the source tree lives in a cloud-synced folder; adjust or remove that block for a different machine.

## Stack

Kotlin, Jetpack Glance (widget UI), Jetpack Compose (app UI), WorkManager, no third-party runtime dependencies for networking or JSON (HttpURLConnection + org.json).
