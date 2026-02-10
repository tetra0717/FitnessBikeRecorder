package com.fbreco.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class GeocodingResult(
    val name: String,
    val country: String?,
    val city: String?,
    val latitude: Double,
    val longitude: Double,
)

@Singleton
class GeocodingService @Inject constructor() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String): List<GeocodingResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val url = "https://photon.komoot.io/api/?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=5"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()
            parseGeoJson(body)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseGeoJson(json: String): List<GeocodingResult> {
        val root = JSONObject(json)
        val features = root.getJSONArray("features")
        return (0 until features.length()).map { i ->
            val feature = features.getJSONObject(i)
            val geometry = feature.getJSONObject("geometry")
            val coordinates = geometry.getJSONArray("coordinates")
            val properties = feature.getJSONObject("properties")
            GeocodingResult(
                name = properties.optString("name", "Unknown"),
                country = properties.optString("country", "").ifEmpty { null },
                city = properties.optString("city", "").ifEmpty { null },
                latitude = coordinates.getDouble(1),
                longitude = coordinates.getDouble(0),
            )
        }
    }
}
