package de.lanewise.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

enum class PlaceKind { HOME, WORK, FAVORITE }

/** A saved destination. Home and Work exist at most once each; favourites have a custom name. */
@Serializable
data class SavedPlace(
    val id: String = UUID.randomUUID().toString(),
    val kind: PlaceKind,
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
)

private val Context.placesStore: DataStore<Preferences> by preferencesDataStore(name = "places", scope = storeScope)

class PlacesRepository(private val context: Context) {
    private val key = stringPreferencesKey("places_json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SavedPlace.serializer())

    val places: Flow<List<SavedPlace>> = context.placesStore.data.map { p ->
        p[key]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()
    }

    /** Save a place. Saving a new Home/Work replaces the previous one. */
    suspend fun save(place: SavedPlace) = context.placesStore.edit { p ->
        val cur = p[key]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()
        val kept = cur.filter { it.id != place.id && (place.kind == PlaceKind.FAVORITE || it.kind != place.kind) }
        p[key] = json.encodeToString(serializer, kept + place)
    }

    suspend fun delete(id: String) = context.placesStore.edit { p ->
        val cur = p[key]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()
        p[key] = json.encodeToString(serializer, cur.filter { it.id != id })
    }
}
