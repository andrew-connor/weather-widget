package com.example.weatherwidget.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Net {
    fun getJson(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "WeatherWidget/1.0 (personal use)")
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IOException("HTTP $code: ${body.take(200)}")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }
}
