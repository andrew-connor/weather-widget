package com.example.weatherwidget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.example.weatherwidget.data.GeoResult
import com.example.weatherwidget.data.GeocodingApi
import com.example.weatherwidget.data.SavedLocation
import com.example.weatherwidget.data.Store
import com.example.weatherwidget.work.RefreshWorker
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
                    AppScreen()
                }
            }
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
    var locations by remember { mutableStateOf(Store.getLocations(context)) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeoResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun saveKeys() {
        Store.setPirateKey(context, pirateKey)
        Store.setAirNowKey(context, airNowKey)
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
            Text("Weather Widget", style = MaterialTheme.typography.headlineSmall)
        }

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
