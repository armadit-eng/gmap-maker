package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class TileSource(val id: String, val label: String) {
    OSM("osm", "OpenStreetMap (tanpa key)"),
    CARTO("carto", "Carto Voyager (butuh API key)")
}

data class TileSettings(
    val source: TileSource = TileSource.OSM,
    val cartoKey: String = "",
    val urlTemplate: String = DEFAULT_CARTO_TEMPLATE
) {
    companion object {
        const val OSM_TEMPLATE = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        const val DEFAULT_CARTO_TEMPLATE =
            "https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png?key={key}"

        fun sanitizeTemplate(raw: String?): String {
            val trimmed = raw?.trim().orEmpty()
            return if (trimmed.isEmpty() || !trimmed.contains("{key}") || trimmed.contains("api_key=")) {
                DEFAULT_CARTO_TEMPLATE
            } else {
                trimmed
            }
        }
    }
}

private val Context.tileDataStore by preferencesDataStore(name = "gm_tiles")

class TileSettingsRepository(private val context: Context) {
    private val keySrc = stringPreferencesKey("src")
    private val keyCarto = stringPreferencesKey("key")
    private val keyUrl = stringPreferencesKey("url")

    val settingsFlow: Flow<TileSettings> = context.tileDataStore.data.map { prefs ->
        val srcId = prefs[keySrc] ?: TileSource.OSM.id
        val source = if (srcId == TileSource.CARTO.id) TileSource.CARTO else TileSource.OSM
        val cartoKey = prefs[keyCarto].orEmpty()
        val url = TileSettings.sanitizeTemplate(prefs[keyUrl])
        TileSettings(source = source, cartoKey = cartoKey, urlTemplate = url)
    }

    suspend fun save(settings: TileSettings): TileSettings {
        val cleanUrl = TileSettings.sanitizeTemplate(settings.urlTemplate)
        val clean = settings.copy(
            cartoKey = settings.cartoKey.trim(),
            urlTemplate = cleanUrl
        )
        context.tileDataStore.edit { prefs ->
            prefs[keySrc] = clean.source.id
            prefs[keyCarto] = clean.cartoKey
            prefs[keyUrl] = clean.urlTemplate
        }
        return clean
    }
}
