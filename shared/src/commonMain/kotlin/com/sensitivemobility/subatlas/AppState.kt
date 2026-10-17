package com.sensitivemobility.subatlas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock

private const val SITES_FILE = "sites.json"
private const val CHECKLISTS_FILE = "checklists_v2.json"
private const val CENTERS_FILE = "centers.json"
private const val DIVES_FILE = "dives.json"
private const val SETTINGS_FILE = "settings.json"

/** Plus récente d'abord. */
internal val diveOrder: Comparator<Dive> =
    compareByDescending<Dive> { it.date }.thenByDescending { it.startTime.orEmpty() }.thenByDescending { it.id }

class AppState(private val storage: Storage, private val systemLanguage: String) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val siteSerializer = ListSerializer(DiveSite.serializer())
    private val checklistSerializer = ListSerializer(Checklist.serializer())
    private val centerSerializer = ListSerializer(DiveCenter.serializer())
    private val diveSerializer = ListSerializer(Dive.serializer())

    val sites = mutableStateListOf<DiveSite>()
    val checklists = mutableStateListOf<Checklist>()
    val centers = mutableStateListOf<DiveCenter>()
    val dives = mutableStateListOf<Dive>()

    var settings by mutableStateOf(Settings())
        private set

    init {
        settings = load(SETTINGS_FILE, Settings.serializer()) ?: Settings()
        sites += load(SITES_FILE, siteSerializer) ?: emptyList()
        centers += load(CENTERS_FILE, centerSerializer) ?: emptyList()
        dives += load(DIVES_FILE, diveSerializer) ?: emptyList()
        checklists += load(CHECKLISTS_FILE, checklistSerializer) ?: defaultChecklists().also {
            storage.write(CHECKLISTS_FILE, json.encodeToString(checklistSerializer, it))
        }
    }

    private fun <T> load(file: String, serializer: kotlinx.serialization.KSerializer<T>): T? =
        storage.read(file)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    private fun saveSites() = storage.write(SITES_FILE, json.encodeToString(siteSerializer, sites.toList()))
    private fun saveChecklists() =
        storage.write(CHECKLISTS_FILE, json.encodeToString(checklistSerializer, checklists.toList()))

    private fun saveDives() = storage.write(DIVES_FILE, json.encodeToString(diveSerializer, dives.toList()))

    private fun saveCenters() = storage.write(CENTERS_FILE, json.encodeToString(centerSerializer, centers.toList()))

    private var lastId = Clock.System.now().toEpochMilliseconds()
    fun newId(): Long = maxOf(lastId + 1, Clock.System.now().toEpochMilliseconds()).also { lastId = it }

    // --- Réglages ---
    fun updateSettings(transform: (Settings) -> Settings) {
        settings = transform(settings)
        storage.write(SETTINGS_FILE, json.encodeToString(Settings.serializer(), settings))
    }

    // --- Export / import ---
    fun exportJson(): String = BackupCodec.encode(sites.toList(), centers.toList(), dives.toList(), checklists.toList(), Clock.System.now().toString())

    /**
     * @param replace `true` : remplace toutes les données ; `false` : fusionne (un élément de même identifiant écrase
     * l'existant, les autres sont ajoutés).
     */
    fun importData(data: ImportData, replace: Boolean) {
        if (replace) {
            sites.clear()
            checklists.clear()
            // Une sauvegarde antérieure aux centres n'en parle pas : on garde alors ceux qu'on a.
            if (data.centers != null) centers.clear()
            // Idem pour les plongées d'une sauvegarde antérieure à leur ajout.
            if (data.dives != null) dives.clear()
        }
        data.sites.forEach { s ->
            val i = sites.indexOfFirst { it.id == s.id }
            if (i >= 0) sites[i] = s else sites.add(s)
        }
        data.checklists.forEach { c ->
            val i = checklists.indexOfFirst { it.id == c.id }
            if (i >= 0) checklists[i] = c else checklists.add(c)
        }
        data.centers?.forEach { c ->
            val i = centers.indexOfFirst { it.id == c.id }
            if (i >= 0) centers[i] = c else centers.add(c)
        }
        data.dives?.forEach { d ->
            val i = dives.indexOfFirst { it.id == d.id }
            if (i >= 0) dives[i] = d else dives.add(d)
        }
        // Les identifiants viennent de l'horloge : on évite d'en redonner un déjà présent dans les données importées.
        val maxId = (data.sites.map { it.id } + data.centers.orEmpty().map { it.id } + data.dives.orEmpty().map { it.id } + data.checklists.flatMap { c -> listOf(c.id) + c.items.map { it.id } }).maxOrNull()
        if (maxId != null) lastId = maxOf(lastId, maxId)
        saveSites()
        saveCenters()
        saveDives()
        saveChecklists()
    }

    // --- Sites ---
    fun site(id: Long) = sites.firstOrNull { it.id == id }

    fun saveSite(site: DiveSite) {
        val i = sites.indexOfFirst { it.id == site.id }
        if (i >= 0) sites[i] = site else sites.add(site)
        saveSites()
    }

    /** Supprime aussi les plongées faites sur ce site. */
    fun deleteSite(id: Long) {
        sites.removeAll { it.id == id }
        saveSites()
        if (dives.removeAll { it.siteId == id }) saveDives()
    }

    // --- Plongées partagées ---
    /**
     * Crée la plongée reçue dans le carnet. Le site est retrouvé par son nom (et son pays) ou par sa proximité (300 m) ;
     * sinon il est créé. Renvoie l'identifiant de la nouvelle plongée.
     */
    fun addSharedDive(shared: SharedDive): Long {
        val lat = shared.site.lat
        val lon = shared.site.lon
        val site = sites.firstOrNull {
            it.name.equals(shared.site.name, ignoreCase = true) && it.country.equals(shared.site.country, ignoreCase = true)
        } ?: sites.firstOrNull {
            val la = it.latitude
            val lo = it.longitude
            lat != null && lon != null && la != null && lo != null && distanceMeters(lat, lon, la, lo) < 300.0
        } ?: DiveSite(id = newId(), name = shared.site.name, latitude = lat, longitude = lon, country = shared.site.country, type = shared.site.type)
            .also { saveSite(it) }
        val dive = Dive(
            id = newId(),
            siteId = site.id,
            date = shared.date,
            startTime = shared.start,
            endTime = shared.end,
            tanks = listOf(Tank(maxDepthMeters = shared.depth)),
            waterTempC = shared.tempC,
            visibilityMeters = shared.visibility,
        )
        saveDive(dive)
        return dive.id
    }

    // --- Plongées ---
    fun dive(id: Long) = dives.firstOrNull { it.id == id }

    fun divesAt(siteId: Long) = dives.filter { it.siteId == siteId }.sortedWith(diveOrder)

    fun saveDive(dive: Dive) {
        val i = dives.indexOfFirst { it.id == dive.id }
        if (i >= 0) dives[i] = dive else dives.add(dive)
        saveDives()
    }

    fun deleteDive(id: Long) {
        dives.removeAll { it.id == id }
        saveDives()
    }

    // --- Centres de plongée ---
    fun center(id: Long) = centers.firstOrNull { it.id == id }

    fun saveCenter(center: DiveCenter) {
        val i = centers.indexOfFirst { it.id == center.id }
        if (i >= 0) centers[i] = center else centers.add(center)
        saveCenters()
    }

    fun deleteCenter(id: Long) {
        centers.removeAll { it.id == id }
        saveCenters()
    }

    // --- Checklists ---
    fun checklist(id: Long) = checklists.firstOrNull { it.id == id }

    fun addChecklist(title: String): Long {
        val c = Checklist(newId(), title)
        checklists.add(c)
        saveChecklists()
        return c.id
    }

    fun deleteChecklist(id: Long) {
        checklists.removeAll { it.id == id }
        saveChecklists()
    }

    private fun update(id: Long, transform: (Checklist) -> Checklist) {
        val i = checklists.indexOfFirst { it.id == id }
        if (i >= 0) {
            checklists[i] = transform(checklists[i])
            saveChecklists()
        }
    }

    fun renameChecklist(id: Long, title: String) = update(id) { it.copy(title = title) }

    fun addItem(listId: Long, text: String) =
        update(listId) { it.copy(items = it.items + ChecklistItem(newId(), text)) }

    /** À vérifier → Oui → Non → Non mais OK → à vérifier. */
    fun cycleItem(listId: Long, itemId: Long) = update(listId) { c ->
        c.copy(items = c.items.map {
            if (it.id != itemId) it else it.copy(
                state = when (it.state) {
                    ItemState.TODO -> ItemState.YES
                    ItemState.YES -> ItemState.NO
                    ItemState.NO -> ItemState.NO_BUT_OK
                    ItemState.NO_BUT_OK -> ItemState.TODO
                },
            )
        })
    }

    fun moveItem(listId: Long, from: Int, to: Int) = update(listId) { c ->
        if (from !in c.items.indices || to !in c.items.indices || from == to) c
        else c.copy(items = c.items.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun deleteItem(listId: Long, itemId: Long) =
        update(listId) { c -> c.copy(items = c.items.filterNot { it.id == itemId }) }

    fun resetChecklist(listId: Long) =
        update(listId) { c -> c.copy(items = c.items.map { it.copy(state = ItemState.TODO) }) }

    /** Créées au premier lancement, dans la langue de l'appli à ce moment-là. */
    private fun defaultChecklists(): List<Checklist> {
        var n = newId()
        return stringsFor(settings.language, systemLanguage).defaultChecklists.map { (title, items) ->
            Checklist(id = n++, title = title, items = items.map { ChecklistItem(n++, it) })
        }
    }
}
