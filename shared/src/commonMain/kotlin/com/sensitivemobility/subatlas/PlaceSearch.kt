package com.sensitivemobility.subatlas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Un lieu trouvé par la recherche : de quoi le reconnaître et ses coordonnées. */
data class PlaceResult(val title: String, val detail: String, val latitude: Double, val longitude: Double)

/**
 * Recherche de coordonnées par nom de lieu, via le service Nominatim d'OpenStreetMap.
 *
 * Usage poli, comme l'exige la politique d'OpenStreetMap : une seule requête par appui sur le bouton (jamais de recherche
 * à la frappe), identifiée par un nom d'appli générique. Renvoie `null` si la requête échoue (pas de réseau, service
 * indisponible), et une liste vide s'il n'y a aucun résultat.
 */
suspend fun searchPlaces(actions: PlatformActions, query: String, language: String): List<PlaceResult>? {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&addressdetails=1" +
        "&accept-language=${urlEncode(language)}&q=${urlEncode(q)}"
    val body = actions.httpGet(url, "SubAtlas/1.0 (dive log app)") ?: return null
    return try {
        Json.parseToJsonElement(body).jsonArray.mapNotNull(::placeFrom)
    } catch (e: Exception) {
        null
    }
}

private fun placeFrom(element: kotlinx.serialization.json.JsonElement): PlaceResult? {
    val o = element as? JsonObject ?: return null
    val lat = o["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
    val lon = o["lon"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
    val display = o["display_name"]?.jsonPrimitive?.content.orEmpty()
    // Le titre est la première partie du nom complet ; le reste (région, pays) sert à départager les homonymes.
    val parts = display.split(", ")
    val title = o["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: parts.firstOrNull().orEmpty()
    val detail = parts.drop(1).takeLast(3).joinToString(", ")
    return PlaceResult(title, detail, lat, lon)
}
