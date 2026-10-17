package com.sensitivemobility.subatlas

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.datetime.LocalDate

/**
 * Tous les textes de l'appli. Pour ajouter une langue : implémenter cette interface (le compilateur signale chaque texte
 * manquant), puis l'ajouter à [AppLanguage] et à [stringsFor].
 */
interface Strings {
    // Navigation
    val tabSites: String
    val tabChecklists: String
    val tabSettings: String
    val sitesTitle: String
    val checklistsTitle: String
    val settingsTitle: String

    // Actions communes
    val ok: String
    val cancel: String
    val delete: String
    val edit: String
    val save: String
    val rename: String

    // Sites
    val newSite: String
    val editSite: String
    val noSites: String
    val addSite: String
    fun maxDepthShort(meters: Int): String
    fun access(access: SiteAccess): String
    fun position(coords: String): String
    fun accessLine(access: SiteAccess): String
    fun maxDepthLine(meters: Int): String
    fun notesLine(notes: String): String
    val openInMaps: String
    fun confirmDelete(name: String): String
    val siteName: String
    val locating: String
    val useMyPosition: String
    val permissionDenied: String
    val locationUnavailable: String
    val latitude: String
    val longitude: String
    val maxDepthField: String
    val notesField: String

    // Check-lists
    val statusOk: String
    fun statusKo(missing: Int): String
    fun statusInProgress(toCheck: Int): String
    val newChecklist: String
    val titleField: String
    val addItem: String
    val resetAll: String
    val deleteChecklist: String
    val newItem: String
    val textField: String
    val stateYes: String
    val stateNo: String
    val stateNoButOk: String

    /** Check-lists créées au premier lancement : (titre, éléments). */
    val defaultChecklists: List<Pair<String, List<String>>>

    // Centres de plongée
    val tabCenters: String
    val centersTitle: String
    val newCenter: String
    val editCenter: String
    val noCenters: String
    val addCenter: String
    val centerName: String
    val phone: String
    val email: String
    val website: String
    val centerNotesField: String
    val addressLabel: String
    val streetField: String
    val addressLine2Field: String
    val postalCodeField: String
    val cityField: String
    val invalidEmail: String
    fun phoneLine(phone: String): String
    fun emailLine(email: String): String
    fun websiteLine(website: String): String
    val call: String
    val sendEmail: String
    val openWebsite: String

    // Plongées
    val tabDives: String
    val divesTitle: String
    val newDive: String
    val editDive: String
    val noDives: String
    val addDive: String
    fun formatDate(date: LocalDate): String
    fun diveCount(count: Int): String
    fun tankCount(count: Int): String
    fun minutesShort(minutes: Int): String
    val diveSite: String
    val country: String
    val languageCode: String
    val siteTypeLabel: String
    fun siteType(type: SiteType): String
    fun sortLabel(sort: DiveSort): String
    fun shareWithCheckbox(name: String): String
    val shareTitle: String
    fun shareIntro(name: String): String
    val shareBySms: String
    val shareByEmail: String
    val shareOther: String
    val shareLater: String
    fun shareButton(name: String): String
    val shareSubject: String
    fun shareMessage(site: String, date: String, link: String, download: String): String
    val contactsDenied: String
    val sharedTitle: String
    val sharedLead: String
    val sharedAdd: String
    val sharedIgnore: String
    val sharedInvalid: String
    val pasteSharedButton: String
    val pasteSharedTitle: String
    val pasteSharedHint: String
    val retry: String
    val diveDate: String
    val startTime: String
    val endTime: String
    val notSet: String
    val clear: String
    val chooseSite: String
    val createSite: String
    val nearbySites: String
    val sitesByName: String
    val searchingPosition: String
    val positionFound: String
    val positionNotFound: String
    val noPositionShort: String
    val positionNotSet: String
    val saveMyPosition: String
    val positionOptionalHint: String
    fun distanceAway(text: String): String
    val tanksSection: String
    fun tankTitle(number: Int): String
    val addTank: String
    val removeTank: String
    val volume: String
    val gas: String
    val gasAir: String
    val gasNitrox: String
    val gasTrimix: String
    val o2Field: String
    val heField: String
    val pressureStart: String
    val pressureEnd: String
    val depthLabel: String
    val avgDepthLabel: String
    val avgDepthHint: String
    val sacLabel: String
    val conditionsSection: String
    val waterTemp: String
    val visibility: String
    val weight: String
    val suit: String
    fun suitType(type: SuitType): String
    val thickness: String
    val buddy: String
    val diveNotesField: String
    fun labelled(label: String, value: String): String
    fun consumedLine(liters: Int): String
    val durationLabel: String
    val siteDivesTitle: String
    val addDiveHere: String
    fun confirmDeleteSiteWithDives(name: String, diveCount: Int): String
    fun confirmDeleteDive(site: String, date: String): String

    // Réglages
    val themeSection: String
    val themeSystem: String
    val themeLight: String
    val themeDark: String
    val languageSection: String
    val languageSystem: String
    val dataSection: String
    val dataHint: String
    val exportData: String
    val importData: String

    // Export / import
    val dataExported: String
    fun exportFailed(detail: String): String
    fun importFailed(detail: String): String
    val unknownError: String
    fun importContains(sites: Int, centers: Int?, dives: Int?, checklists: Int): String
    fun importSkipped(count: Int): String
    val importMergeHelp: String
    val importReplace: String
    val importMerge: String
    val dataImported: String
    val dataReplaced: String
    fun backupError(error: BackupException): String
}

object FrStrings : Strings {
    override val tabSites = "Sites"
    override val tabChecklists = "Check-lists"
    override val tabSettings = "Paramètres"
    override val sitesTitle = "Sites de plongée"
    override val checklistsTitle = "Check-lists"
    override val settingsTitle = "Paramètres"

    override val ok = "OK"
    override val cancel = "Annuler"
    override val delete = "Supprimer"
    override val edit = "Modifier"
    override val save = "Enregistrer"
    override val rename = "Renommer"

    override val newSite = "Nouveau site"
    override val editSite = "Modifier le site"
    override val noSites = "Aucun site pour l'instant.\nAppuyez sur « Ajouter un site » pour enregistrer votre position GPS."
    override val addSite = "Ajouter un site"
    override fun maxDepthShort(meters: Int) = "max $meters m"
    override fun access(access: SiteAccess) = when (access) {
        SiteAccess.SHORE -> "Depuis le bord"
        SiteAccess.BOAT -> "En bateau"
    }
    override fun position(coords: String) = "Position : $coords"
    override fun accessLine(access: SiteAccess) = "Accès : ${access(access)}"
    override fun maxDepthLine(meters: Int) = "Profondeur max : $meters m"
    override fun notesLine(notes: String) = "Notes : $notes"
    override val openInMaps = "Ouvrir dans la carte"
    override fun confirmDelete(name: String) = "Supprimer « $name » ?"
    override val siteName = "Nom du site"
    override val locating = "Recherche du signal GPS…"
    override val useMyPosition = "Utiliser ma position GPS"
    override val permissionDenied = "Permission de localisation refusée."
    override val locationUnavailable = "Position indisponible. Activez le GPS et réessayez."
    override val latitude = "Latitude"
    override val longitude = "Longitude"
    override val maxDepthField = "Profondeur max (m)"
    override val notesField = "Notes (courant, visibilité, faune…)"

    override val statusOk = "✓ LISTE OK"
    override fun statusKo(missing: Int) = "✗ LISTE KO  ($missing manquant${if (missing > 1) "s" else ""})"
    override fun statusInProgress(toCheck: Int) = "… EN COURS  ($toCheck à vérifier)"
    override val newChecklist = "Nouvelle check-list"
    override val titleField = "Titre"
    override val addItem = "Ajouter un élément"
    override val resetAll = "Tout remettre à zéro"
    override val deleteChecklist = "Supprimer la check-list"
    override val newItem = "Nouvel élément"
    override val textField = "Texte"
    override val stateYes = "Oui"
    override val stateNo = "Non"
    override val stateNoButOk = "Non mais OK"

    override val defaultChecklists = listOf(
        "Équipement plongée (voyage)" to listOf(
            "Combinaison", "Palmes", "Masque", "Tuba", "Gilet stabilisateur", "Détendeur", "Ordinateur", "Parachute", "Phare", "Ardoise",
        ),
        "Briefing et sécurité" to listOf(
            "Plan de plongée (profondeur, durée, palier)",
            "Signes de communication rappelés",
            "Pression de remontée convenue",
            "Binôme vérifié (buddy check)",
            "Point de sortie / conditions météo",
        ),
    )

    override val tabCenters = "Centres"
    override val centersTitle = "Centres de plongée"
    override val newCenter = "Nouveau centre"
    override val editCenter = "Modifier le centre"
    override val noCenters = "Aucun centre pour l'instant.\nAppuyez sur « Ajouter un centre » pour référencer un club ou une école de plongée."
    override val addCenter = "Ajouter un centre"
    override val centerName = "Nom de l'enseigne"
    override val phone = "Téléphone"
    override val email = "E-mail"
    override val website = "Site web"
    override val centerNotesField = "Notes (tarifs, horaires, langues parlées…)"
    override val addressLabel = "Adresse"
    override val streetField = "Numéro et rue"
    override val addressLine2Field = "Complément d'adresse"
    override val postalCodeField = "Code postal"
    override val cityField = "Ville"
    override val invalidEmail = "Adresse e-mail invalide"
    override fun phoneLine(phone: String) = "Téléphone : $phone"
    override fun emailLine(email: String) = "E-mail : $email"
    override fun websiteLine(website: String) = "Site web : $website"
    override val call = "Appeler"
    override val sendEmail = "Écrire un e-mail"
    override val openWebsite = "Ouvrir le site web"

    override val tabDives = "Plongées"
    override val divesTitle = "Mes plongées"
    override val newDive = "Nouvelle plongée"
    override val editDive = "Modifier la plongée"
    override val noDives = "Aucune plongée pour l'instant.\nAppuyez sur « Ajouter une plongée » pour l'enregistrer dans votre carnet."
    override val addDive = "Ajouter une plongée"
    private val monthsFr = listOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.")
    override fun formatDate(date: LocalDate) = "${date.day} ${monthsFr[date.month.ordinal]} ${date.year}"
    override fun diveCount(count: Int) = "$count plongée${if (count > 1) "s" else ""}"
    override fun tankCount(count: Int) = "$count bouteille${if (count > 1) "s" else ""}"
    override fun minutesShort(minutes: Int) =
        if (minutes < 60) "$minutes min" else "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}"
    override val diveSite = "Site"
    override val retry = "Réessayer"
    override val country = "Pays"
    override val languageCode = "fr"
    override val siteTypeLabel = "Type de site"
    override fun siteType(type: SiteType) = when (type) {
        SiteType.SEA -> "Mer"
        SiteType.LAKE -> "Lac"
        SiteType.RIVER -> "Rivière"
        SiteType.QUARRY -> "Carrière"
        SiteType.PIT -> "Fosse"
        SiteType.CAVE -> "Grotte"
        SiteType.POOL -> "Piscine"
        SiteType.OTHER -> "Autre"
    }
    override fun sortLabel(sort: DiveSort) = when (sort) {
        DiveSort.DATE_DESC -> "Date (récentes)"
        DiveSort.DATE_ASC -> "Date (anciennes)"
        DiveSort.SITE -> "Site"
        DiveSort.COUNTRY -> "Pays"
        DiveSort.DEPTH_DESC -> "Profondeur max"
        DiveSort.DURATION_DESC -> "Durée"
        DiveSort.SAC_ASC -> "SAC (croissant)"
    }
    override fun shareWithCheckbox(name: String) = "Proposer de partager cette plongée avec $name"
    override val shareTitle = "Partager la plongée"
    override fun shareIntro(name: String) =
        "Envoyez la plongée à $name : si l'application est installée, un appui suffit pour l'ajouter à son carnet ; sinon le message lui propose de la télécharger."
    override val shareBySms = "Envoyer par SMS"
    override val shareByEmail = "Envoyer par e-mail"
    override val shareOther = "Autres applications…"
    override val shareLater = "Plus tard"
    override fun shareButton(name: String) = "Partager avec $name"
    override val shareSubject = "Plongée partagée"
    override fun shareMessage(site: String, date: String, link: String, download: String) =
        "Plongée du $date – $site.\nAjoute-la à ton carnet SubAtlas d'un appui : $link\nPas encore l'appli ? Télécharge-la : $download"
    override val contactsDenied = "Accès aux contacts refusé : seuls les binômes déjà saisis sont proposés."
    override val sharedTitle = "Plongée partagée"
    override val sharedLead = "On vous propose d'ajouter cette plongée à votre carnet :"
    override val sharedAdd = "Ajouter à mon carnet"
    override val sharedIgnore = "Ignorer"
    override val sharedInvalid = "Ce lien de plongée n'est pas valide."
    override val pasteSharedButton = "Coller une plongée reçue"
    override val pasteSharedTitle = "Plongée reçue"
    override val pasteSharedHint = "Collez ici le lien ou le message reçu"
    override val diveDate = "Date"
    override val startTime = "Début"
    override val endTime = "Fin"
    override val notSet = "Non renseigné"
    override val clear = "Effacer"
    override val chooseSite = "Choisir un site"
    override val createSite = "＋ Créer un nouveau site"
    override val nearbySites = "Sites triés du plus proche au plus lointain"
    override val sitesByName = "Position GPS indisponible : sites triés par nom"
    override val searchingPosition = "Recherche de votre position…"
    override val positionFound = "Position GPS trouvée"
    override val positionNotFound = "Position GPS non trouvée"
    override val noPositionShort = "position inconnue"
    override val positionNotSet = "Position non renseignée"
    override val saveMyPosition = "Enregistrer ma position GPS actuelle"
    override val positionOptionalHint = "Les coordonnées sont facultatives : vous pourrez les ajouter plus tard."
    override fun distanceAway(text: String) = "à $text"
    override val tanksSection = "Bouteilles"
    override fun tankTitle(number: Int) = "Bouteille $number"
    override val addTank = "Ajouter une bouteille"
    override val removeTank = "Retirer"
    override val volume = "Volume"
    override val gas = "Mélange"
    override val gasAir = "Air"
    override val gasNitrox = "Nitrox"
    override val gasTrimix = "Trimix"
    override val o2Field = "O₂ (%)"
    override val heField = "He (%)"
    override val pressureStart = "Pression début"
    override val pressureEnd = "Pression fin"
    override val depthLabel = "Profondeur max"
    override val avgDepthLabel = "Profondeur moyenne"
    override val avgDepthHint = "Avec les pressions, le volume des bouteilles et la durée, elle permet de calculer votre SAC (consommation à la surface)."
    override val sacLabel = "SAC"
    override val conditionsSection = "Conditions et matériel"
    override val waterTemp = "Température de l'eau"
    override val visibility = "Visibilité"
    override val weight = "Lestage"
    override val suit = "Combinaison"
    override fun suitType(type: SuitType) = when (type) {
        SuitType.WET -> "Humide"
        SuitType.SEMI_DRY -> "Semi-étanche"
        SuitType.DRY -> "Étanche"
    }
    override val thickness = "Épaisseur de la combinaison"
    override val buddy = "Binôme"
    override val diveNotesField = "Notes (observations, faune, ressenti…)"
    override fun labelled(label: String, value: String) = "$label : $value"
    override fun consumedLine(liters: Int) = "Consommé : $liters L"
    override val durationLabel = "Durée"
    override val siteDivesTitle = "Plongées sur ce site"
    override val addDiveHere = "Ajouter une plongée ici"
    override fun confirmDeleteSiteWithDives(name: String, diveCount: Int) =
        "Supprimer « $name » et ses $diveCount plongée${if (diveCount > 1) "s" else ""} ?"
    override fun confirmDeleteDive(site: String, date: String) = "Supprimer la plongée du $date sur « $site » ?"

    override val themeSection = "Thème"
    override val themeSystem = "Système"
    override val themeLight = "Clair"
    override val themeDark = "Sombre"
    override val languageSection = "Langue"
    override val languageSystem = "Système"
    override val dataSection = "Données"
    override val dataHint = "Enregistrez vos plongées, sites et check-lists dans un fichier, ou restaurez-les depuis une sauvegarde."
    override val exportData = "Exporter les données"
    override val importData = "Importer des données"

    override val dataExported = "Données exportées."
    override fun exportFailed(detail: String) = "Export impossible : $detail."
    override fun importFailed(detail: String) = "Import impossible : $detail."
    override val unknownError = "erreur inconnue"
    override fun importContains(sites: Int, centers: Int?, dives: Int?, checklists: Int): String {
        val parts = listOfNotNull(
            "$sites site${if (sites > 1) "s" else ""}",
            centers?.let { "$it centre${if (it > 1) "s" else ""} de plongée" },
            dives?.let { "$it plongée${if (it > 1) "s" else ""}" },
            "$checklists check-list${if (checklists > 1) "s" else ""}",
        )
        return "Ce fichier contient ${parts.dropLast(1).joinToString(", ")} et ${parts.last()}."
    }
    override fun importSkipped(count: Int) = "$count élément(s) illisible(s) seront ignorés."
    override val importMergeHelp = "Fusionner garde vos données et ajoute celles du fichier (à identifiant égal, le fichier l'emporte)."
    override val importReplace = "Tout remplacer (efface vos données actuelles)"
    override val importMerge = "Fusionner"
    override val dataImported = "Données importées."
    override val dataReplaced = "Données remplacées."
    override fun backupError(error: BackupException) = when (error.error) {
        BackupError.INVALID_JSON -> "Ce fichier n'est pas une sauvegarde SubAtlas (JSON invalide)."
        BackupError.NOT_A_BACKUP -> "Ce fichier n'est pas une sauvegarde SubAtlas."
        BackupError.NEWER_VERSION ->
            "Cette sauvegarde vient d'une version plus récente de SubAtlas (format ${error.version}). Mettez l'application à jour pour l'importer."
        BackupError.UNSUPPORTED_VERSION -> "Format de sauvegarde ${error.version} non pris en charge."
    }
}

object EnStrings : Strings {
    override val tabSites = "Sites"
    override val tabChecklists = "Checklists"
    override val tabSettings = "Settings"
    override val sitesTitle = "Dive sites"
    override val checklistsTitle = "Checklists"
    override val settingsTitle = "Settings"

    override val ok = "OK"
    override val cancel = "Cancel"
    override val delete = "Delete"
    override val edit = "Edit"
    override val save = "Save"
    override val rename = "Rename"

    override val newSite = "New site"
    override val editSite = "Edit site"
    override val noSites = "No sites yet.\nTap “Add a site” to save your GPS position."
    override val addSite = "Add a site"
    override fun maxDepthShort(meters: Int) = "max $meters m"
    override fun access(access: SiteAccess) = when (access) {
        SiteAccess.SHORE -> "From shore"
        SiteAccess.BOAT -> "By boat"
    }
    override fun position(coords: String) = "Position: $coords"
    override fun accessLine(access: SiteAccess) = "Access: ${access(access)}"
    override fun maxDepthLine(meters: Int) = "Max depth: $meters m"
    override fun notesLine(notes: String) = "Notes: $notes"
    override val openInMaps = "Open in maps"
    override fun confirmDelete(name: String) = "Delete “$name”?"
    override val siteName = "Site name"
    override val locating = "Looking for GPS signal…"
    override val useMyPosition = "Use my GPS position"
    override val permissionDenied = "Location permission denied."
    override val locationUnavailable = "Position unavailable. Turn on GPS and try again."
    override val latitude = "Latitude"
    override val longitude = "Longitude"
    override val maxDepthField = "Max depth (m)"
    override val notesField = "Notes (current, visibility, wildlife…)"

    override val statusOk = "✓ LIST OK"
    override fun statusKo(missing: Int) = "✗ LIST KO  ($missing missing)"
    override fun statusInProgress(toCheck: Int) = "… IN PROGRESS  ($toCheck to check)"
    override val newChecklist = "New checklist"
    override val titleField = "Title"
    override val addItem = "Add an item"
    override val resetAll = "Reset everything"
    override val deleteChecklist = "Delete checklist"
    override val newItem = "New item"
    override val textField = "Text"
    override val stateYes = "Yes"
    override val stateNo = "No"
    override val stateNoButOk = "No but OK"

    override val defaultChecklists = listOf(
        "Dive gear (travel)" to listOf(
            "Wetsuit", "Fins", "Mask", "Snorkel", "BCD", "Regulator", "Dive computer", "Surface marker buoy", "Dive light", "Slate",
        ),
        "Briefing and safety" to listOf(
            "Dive plan (depth, duration, safety stop)",
            "Hand signals reviewed",
            "Agreed ascent pressure",
            "Buddy check done",
            "Exit point / weather conditions",
        ),
    )

    override val tabCenters = "Centers"
    override val centersTitle = "Dive centers"
    override val newCenter = "New center"
    override val editCenter = "Edit center"
    override val noCenters = "No centers yet.\nTap “Add a center” to list a dive club or school."
    override val addCenter = "Add a center"
    override val centerName = "Business name"
    override val phone = "Phone"
    override val email = "Email"
    override val website = "Website"
    override val centerNotesField = "Notes (prices, hours, languages spoken…)"
    override val addressLabel = "Address"
    override val streetField = "Street address"
    override val addressLine2Field = "Address line 2"
    override val postalCodeField = "Postal code"
    override val cityField = "City"
    override val invalidEmail = "Invalid email address"
    override fun phoneLine(phone: String) = "Phone: $phone"
    override fun emailLine(email: String) = "Email: $email"
    override fun websiteLine(website: String) = "Website: $website"
    override val call = "Call"
    override val sendEmail = "Send an email"
    override val openWebsite = "Open website"

    override val tabDives = "Dives"
    override val divesTitle = "My dives"
    override val newDive = "New dive"
    override val editDive = "Edit dive"
    override val noDives = "No dives yet.\nTap “Add a dive” to record it in your logbook."
    override val addDive = "Add a dive"
    private val monthsEn = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    override fun formatDate(date: LocalDate) = "${monthsEn[date.month.ordinal]} ${date.day}, ${date.year}"
    override fun diveCount(count: Int) = "$count dive${if (count != 1) "s" else ""}"
    override fun tankCount(count: Int) = "$count tank${if (count != 1) "s" else ""}"
    override fun minutesShort(minutes: Int) =
        if (minutes < 60) "$minutes min" else "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}"
    override val diveSite = "Site"
    override val retry = "Retry"
    override val country = "Country"
    override val languageCode = "en"
    override val siteTypeLabel = "Site type"
    override fun siteType(type: SiteType) = when (type) {
        SiteType.SEA -> "Sea"
        SiteType.LAKE -> "Lake"
        SiteType.RIVER -> "River"
        SiteType.QUARRY -> "Quarry"
        SiteType.PIT -> "Pit"
        SiteType.CAVE -> "Cave"
        SiteType.POOL -> "Pool"
        SiteType.OTHER -> "Other"
    }
    override fun sortLabel(sort: DiveSort) = when (sort) {
        DiveSort.DATE_DESC -> "Date (newest)"
        DiveSort.DATE_ASC -> "Date (oldest)"
        DiveSort.SITE -> "Site"
        DiveSort.COUNTRY -> "Country"
        DiveSort.DEPTH_DESC -> "Max depth"
        DiveSort.DURATION_DESC -> "Duration"
        DiveSort.SAC_ASC -> "SAC (low to high)"
    }
    override fun shareWithCheckbox(name: String) = "Offer to share this dive with $name"
    override val shareTitle = "Share the dive"
    override fun shareIntro(name: String) =
        "Send the dive to $name: if the app is installed, one tap adds it to their logbook; otherwise the message invites them to download it."
    override val shareBySms = "Send by SMS"
    override val shareByEmail = "Send by email"
    override val shareOther = "Other apps…"
    override val shareLater = "Later"
    override fun shareButton(name: String) = "Share with $name"
    override val shareSubject = "Shared dive"
    override fun shareMessage(site: String, date: String, link: String, download: String) =
        "Dive on $date – $site.\nAdd it to your SubAtlas logbook with one tap: $link\nDon't have the app yet? Download it: $download"
    override val contactsDenied = "Contacts access denied: only buddies you already entered are suggested."
    override val sharedTitle = "Shared dive"
    override val sharedLead = "You are invited to add this dive to your logbook:"
    override val sharedAdd = "Add to my logbook"
    override val sharedIgnore = "Ignore"
    override val sharedInvalid = "This dive link is not valid."
    override val pasteSharedButton = "Paste a received dive"
    override val pasteSharedTitle = "Received dive"
    override val pasteSharedHint = "Paste the link or message you received here"
    override val diveDate = "Date"
    override val startTime = "Start"
    override val endTime = "End"
    override val notSet = "Not set"
    override val clear = "Clear"
    override val chooseSite = "Choose a site"
    override val createSite = "＋ Create a new site"
    override val nearbySites = "Sites sorted from nearest to farthest"
    override val sitesByName = "GPS position unavailable: sites sorted by name"
    override val searchingPosition = "Looking for your position…"
    override val positionFound = "GPS position found"
    override val positionNotFound = "GPS position not found"
    override val noPositionShort = "position unknown"
    override val positionNotSet = "Position not set"
    override val saveMyPosition = "Save my current GPS position"
    override val positionOptionalHint = "Coordinates are optional: you can add them later."
    override fun distanceAway(text: String) = "$text away"
    override val tanksSection = "Tanks"
    override fun tankTitle(number: Int) = "Tank $number"
    override val addTank = "Add a tank"
    override val removeTank = "Remove"
    override val volume = "Volume"
    override val gas = "Gas mix"
    override val gasAir = "Air"
    override val gasNitrox = "Nitrox"
    override val gasTrimix = "Trimix"
    override val o2Field = "O₂ (%)"
    override val heField = "He (%)"
    override val pressureStart = "Start pressure"
    override val pressureEnd = "End pressure"
    override val depthLabel = "Max depth"
    override val avgDepthLabel = "Average depth"
    override val avgDepthHint = "Together with the pressures, the tank volumes and the duration, it lets the app compute your SAC (surface air consumption)."
    override val sacLabel = "SAC"
    override val conditionsSection = "Conditions and gear"
    override val waterTemp = "Water temperature"
    override val visibility = "Visibility"
    override val weight = "Weights"
    override val suit = "Exposure suit"
    override fun suitType(type: SuitType) = when (type) {
        SuitType.WET -> "Wetsuit"
        SuitType.SEMI_DRY -> "Semi-dry"
        SuitType.DRY -> "Drysuit"
    }
    override val thickness = "Suit thickness"
    override val buddy = "Buddy"
    override val diveNotesField = "Notes (observations, wildlife, how it felt…)"
    override fun labelled(label: String, value: String) = "$label: $value"
    override fun consumedLine(liters: Int) = "Gas used: $liters L"
    override val durationLabel = "Duration"
    override val siteDivesTitle = "Dives at this site"
    override val addDiveHere = "Add a dive here"
    override fun confirmDeleteSiteWithDives(name: String, diveCount: Int) =
        "Delete “$name” and its $diveCount dive${if (diveCount != 1) "s" else ""}?"
    override fun confirmDeleteDive(site: String, date: String) = "Delete the dive of $date at “$site”?"

    override val themeSection = "Theme"
    override val themeSystem = "System"
    override val themeLight = "Light"
    override val themeDark = "Dark"
    override val languageSection = "Language"
    override val languageSystem = "System"
    override val dataSection = "Data"
    override val dataHint = "Save your dives, sites and checklists to a file, or restore them from a backup."
    override val exportData = "Export data"
    override val importData = "Import data"

    override val dataExported = "Data exported."
    override fun exportFailed(detail: String) = "Export failed: $detail."
    override fun importFailed(detail: String) = "Import failed: $detail."
    override val unknownError = "unknown error"
    override fun importContains(sites: Int, centers: Int?, dives: Int?, checklists: Int): String {
        val parts = listOfNotNull(
            "$sites site${if (sites != 1) "s" else ""}",
            centers?.let { "$it dive center${if (it != 1) "s" else ""}" },
            dives?.let { "$it dive${if (it != 1) "s" else ""}" },
            "$checklists checklist${if (checklists != 1) "s" else ""}",
        )
        val list = if (parts.size == 2) "${parts[0]} and ${parts[1]}" else parts.dropLast(1).joinToString(", ") + ", and " + parts.last()
        return "This file contains $list."
    }
    override fun importSkipped(count: Int) = "$count unreadable item(s) will be skipped."
    override val importMergeHelp = "Merge keeps your data and adds the file's (when ids match, the file wins)."
    override val importReplace = "Replace everything (erases your current data)"
    override val importMerge = "Merge"
    override val dataImported = "Data imported."
    override val dataReplaced = "Data replaced."
    override fun backupError(error: BackupException) = when (error.error) {
        BackupError.INVALID_JSON -> "This file is not a SubAtlas backup (invalid JSON)."
        BackupError.NOT_A_BACKUP -> "This file is not a SubAtlas backup."
        BackupError.NEWER_VERSION ->
            "This backup comes from a newer version of SubAtlas (format ${error.version}). Update the app to import it."
        BackupError.UNSUPPORTED_VERSION -> "Backup format ${error.version} is not supported."
    }
}

/** La langue « système » retombe sur l'anglais quand elle n'est pas prise en charge. */
fun stringsFor(language: AppLanguage, systemLanguage: String): Strings = when (language) {
    AppLanguage.FR -> FrStrings
    AppLanguage.EN -> EnStrings
    AppLanguage.SYSTEM -> if (systemLanguage.lowercase().startsWith("fr")) FrStrings else EnStrings
}

val LocalStrings = staticCompositionLocalOf<Strings> { EnStrings }
