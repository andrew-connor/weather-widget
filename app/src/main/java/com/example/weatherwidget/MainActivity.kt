package com.example.weatherwidget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.weatherwidget.data.DayHistory
import com.example.weatherwidget.data.GaugeApi
import com.example.weatherwidget.data.GaugeReading
import com.example.weatherwidget.data.GeoResult
import com.example.weatherwidget.data.GeocodingApi
import com.example.weatherwidget.data.HistoryApi
import com.example.weatherwidget.data.SavedLocation
import com.example.weatherwidget.data.Store
import com.example.weatherwidget.work.RefreshWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RefreshWorker.schedulePeriodic(this)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
fun AppRoot() {
    var tab by remember { mutableStateOf(0) }
    // Keep content out from under the status bar, gesture bar, and camera
    // cutout — Android 15+ draws apps edge-to-edge by default.
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Widget setup") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Past 24 hours") })
        }
        when (tab) {
            0 -> AppScreen()
            1 -> HistoryScreen()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pirateKey by remember { mutableStateOf(Store.getPirateKey(context)) }
    var airNowKey by remember { mutableStateOf(Store.getAirNowKey(context)) }
    var synopticToken by remember { mutableStateOf(Store.getSynopticToken(context)) }
    var locations by remember { mutableStateOf(Store.getLocations(context)) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeoResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun saveKeys() {
        Store.setPirateKey(context, pirateKey)
        Store.setAirNowKey(context, airNowKey)
        Store.setSynopticToken(context, synopticToken)
        status = "API keys saved"
        RefreshWorker.refreshNow(context)
    }

    fun search() {
        if (query.isBlank()) return
        searching = true
        status = null
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                try {
                    GeocodingApi.search(query)
                } catch (e: Exception) {
                    status = "Search failed: ${e.message}"
                    emptyList()
                }
            }
            results = found
            searching = false
            if (found.isEmpty() && status == null) status = "No matches found"
        }
    }

    fun addLocation(r: GeoResult) {
        if (locations.none { it.name == r.label }) {
            locations = locations + SavedLocation(r.label, r.lat, r.lon)
            Store.setLocations(context, locations)
            RefreshWorker.refreshNow(context)
        }
        results = emptyList()
        query = ""
        status = "Added ${r.label} — the widget will update in a few seconds"
    }

    fun removeLocation(loc: SavedLocation) {
        locations = locations - loc
        Store.setLocations(context, locations)
        RefreshWorker.refreshNow(context)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("API keys", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = pirateKey,
                    onValueChange = { pirateKey = it },
                    label = { Text("Pirate Weather API key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = airNowKey,
                    onValueChange = { airNowKey = it },
                    label = { Text("AirNow API key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = synopticToken,
                    onValueChange = { synopticToken = it },
                    label = { Text("Synoptic API token (rain gauges, optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { saveKeys() }) { Text("Save keys") }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Add a location", style = MaterialTheme.typography.titleMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Address, ZIP code, or city") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { search() }, enabled = !searching) {
                        Text(if (searching) "…" else "Search")
                    }
                }
            }
        }

        items(results) { r ->
            Card(onClick = { addLocation(r) }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(r.label, modifier = Modifier.weight(1f))
                    Text("Add", color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        item {
            Text("Saved locations (${locations.size})", style = MaterialTheme.typography.titleMedium)
        }

        if (locations.isEmpty()) {
            item {
                Text("None yet — search above to add one.", style = MaterialTheme.typography.bodyMedium)
            }
        }

        items(locations) { loc ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(loc.name, modifier = Modifier.weight(1f))
                    TextButton(onClick = { removeLocation(loc) }) { Text("Remove") }
                }
            }
        }

        item {
            Button(onClick = {
                RefreshWorker.refreshNow(context)
                status = "Refreshing — the widget should update in a few seconds"
            }) { Text("Refresh widget now") }
        }

        status?.let { s ->
            item { Text(s, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeoResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    // First use: start the recents list off with the widget's saved locations.
    var recents by remember {
        mutableStateOf(
            Store.getHistoryLocations(context).ifEmpty {
                Store.getLocations(context).also { Store.setHistoryLocations(context, it) }
            }
        )
    }
    var selected by remember { mutableStateOf<SavedLocation?>(null) }
    var history by remember { mutableStateOf<DayHistory?>(null) }
    var gauges by remember { mutableStateOf<List<GaugeReading>>(emptyList()) }
    var gaugeNote by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun load(loc: SavedLocation) {
        selected = loc
        loading = true
        history = null
        gauges = emptyList()
        gaugeNote = null
        status = null
        results = emptyList()
        query = ""
        recents = listOf(loc) + recents.filter { it.name != loc.name }
        Store.setHistoryLocations(context, recents)
        scope.launch {
            val fetched = withContext(Dispatchers.IO) {
                try {
                    HistoryApi.fetchPast24h(loc.lat, loc.lon)
                } catch (e: Exception) {
                    status = "Couldn't load history: ${e.message}"
                    null
                }
            }
            history = fetched
            if (fetched != null) {
                val token = Store.getSynopticToken(context)
                if (token.isEmpty()) {
                    gaugeNote = "Add a Synoptic API token on the Widget setup tab to see nearby gauge readings."
                } else {
                    val g = withContext(Dispatchers.IO) {
                        try {
                            GaugeApi.fetchPast24h(token, loc.lat, loc.lon)
                        } catch (e: Exception) {
                            gaugeNote = "Gauge readings unavailable: ${e.message}"
                            emptyList()
                        }
                    }
                    gauges = g
                    if (g.isEmpty() && gaugeNote == null) {
                        gaugeNote = "No reporting gauges found within 25 miles."
                    }
                }
            }
            loading = false
        }
    }

    fun search() {
        if (query.isBlank()) return
        searching = true
        status = null
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                try {
                    GeocodingApi.search(query)
                } catch (e: Exception) {
                    status = "Search failed: ${e.message}"
                    emptyList()
                }
            }
            results = found
            searching = false
            if (found.isEmpty() && status == null) status = "No matches found"
        }
    }

    fun removeRecent(loc: SavedLocation) {
        recents = recents.filter { it.name != loc.name }
        Store.setHistoryLocations(context, recents)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Address, ZIP code, or city") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { search() }, enabled = !searching) {
                    Text(if (searching) "…" else "Search")
                }
            }
        }

        items(results) { r ->
            Card(
                onClick = { load(SavedLocation(r.label, r.lat, r.lon)) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(r.label, modifier = Modifier.weight(1f))
                    Text("View", color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        status?.let { s ->
            item { Text(s, color = MaterialTheme.colorScheme.primary) }
        }

        if (loading) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }
            }
        }

        selected?.let { loc ->
            history?.let { day ->
                item {
                    Column {
                        Text(loc.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Past 24 hours — total rainfall " +
                                String.format(Locale.US, "%.2f in", day.totalInches),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                val maxRain = (day.hours.maxOfOrNull { it.rainInches } ?: 0.0).coerceAtLeast(0.01)
                val hourFormat = SimpleDateFormat("EEE ha", Locale.US).apply {
                    day.timeZoneId?.let { timeZone = TimeZone.getTimeZone(it) }
                }
                items(day.hours) { h ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Text(
                            hourFormat.format(Date(h.timeEpoch * 1000))
                                .replace("AM", "am").replace("PM", "pm"),
                            modifier = Modifier.width(84.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            h.tempF?.let { "$it°" } ?: "--",
                            modifier = Modifier.width(44.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            if (h.rainInches < 0.005) "—"
                            else String.format(Locale.US, "%.2f\"", h.rainInches),
                            modifier = Modifier.width(56.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Box(modifier = Modifier.weight(1f).height(6.dp)) {
                            if (h.rainInches >= 0.005) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(
                                            (h.rainInches / maxRain).toFloat().coerceIn(0.06f, 1f)
                                        )
                                        .fillMaxHeight()
                                        .background(
                                            MaterialTheme.colorScheme.primary,
                                            RoundedCornerShape(3.dp)
                                        )
                                )
                            }
                        }
                    }
                }

                item {
                    Text(
                        "Nearby rain gauges — measured 24 h totals",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                gaugeNote?.let { note ->
                    item { Text(note, style = MaterialTheme.typography.bodyMedium) }
                }
                items(gauges) { g ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Text(
                            g.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            g.distanceMiles?.let { String.format(Locale.US, "%.1f mi", it) } ?: "",
                            modifier = Modifier.width(64.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Column(modifier = Modifier.width(80.dp)) {
                            Text(
                                String.format(Locale.US, "%.2f\"", g.inches),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "${g.hoursReported} of 24 hrs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item { Text("Previous locations", style = MaterialTheme.typography.titleMedium) }

        if (recents.isEmpty()) {
            item {
                Text("None yet — search above.", style = MaterialTheme.typography.bodyMedium)
            }
        }

        items(recents) { loc ->
            Card(onClick = { load(loc) }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(loc.name, modifier = Modifier.weight(1f))
                    TextButton(onClick = { removeRecent(loc) }) { Text("Remove") }
                }
            }
        }
    }
}
