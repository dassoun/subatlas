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
    val mergePrevious: String
    val noMerge: String
    val mergeAction: String
    val mergeTitle: String
    fun mergeMessage(earlier: String, later: String): String
    val edit: String
    val save: String
    val rename: String

    // Sites
    val newSite: String
    val editSite: String
    val noSites: String
    val searchSites: String
    val noSiteFound: String
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
    val editItem: String
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
    val unsavedTitle: String
    val unsavedMessage: String
    val unsavedDiscard: String
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
    val diveKindLabel: String
    fun diveKind(kind: DiveKind): String
    val kindOtherField: String
    val groupSection: String
    val personField: String
    val addPerson: String
    val shareGroupCheckbox: String
    val shareWithPeople: String
    val shareIntroMany: String
    val usedMinutesLabel: String
    val importShearwater: String
    val shearwaterTitle: String
    fun shearwaterFound(total: Int, created: Int, merged: Int): String
    val shearwaterNone: String
    val shearwaterInvalid: String
    val shearwaterConfirm: String
    fun shearwaterDone(created: Int, merged: Int): String
    val shearwaterNewTag: String
    val shearwaterMergeTag: String
    val shearwaterMergeHelp: String
    fun shearwaterMore(count: Int): String
    fun shearwaterPresent(count: Int): String
    val advancedSection: String
    val devSection: String
    val deleteNoGps: String
    val deleteNoGpsHint: String
    val deleteNoGpsTitle: String
    fun deleteNoGpsMessage(sites: Int, dives: Int): String
    val deleteNoGpsDoneTitle: String
    fun deleteNoGpsDone(count: Int): String
    val deleteAllDives: String
    val deleteAllDivesHint: String
    val deleteAllTitle: String
    fun deleteAllMessage(count: Int): String
    val deleteAllConfirm: String
    val deleteAllDoneTitle: String
    fun deleteAllDone(count: Int): String
    fun shearwaterSkipped(count: Int): String
    val shearwaterReading: String
    val computerLabel: String
    val profileTitle: String
    val profileDepthLegend: String
    val profilePressureLegend: String
    val seriesNdl: String
    val seriesDeco: String
    val seriesCns: String
    val seriesPpo2: String
    val seriesTts: String
    val ascentRateLabel: String
    fun ascentOver(limit: Int, seconds: Int): String
    val ascentFine: String
    fun shareWithCheckbox(name: String): String
    val shareTitle: String
    fun shareIntro(name: String): String
    val shareBySms: String
    val shareByEmail: String
    val shareFileButton: String
    val shareFileIntroNone: String
    val pickContact: String
    val shareOther: String
    val shareLater: String
    fun shareButton(name: String): String
    val shareSubject: String
    fun shareMessage(site: String, date: String, link: String): String
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
    val avgDepthShort: String
    val avgDepthHint: String
    val sacLabel: String
    val conditionsSection: String
    val notesLabel: String
    val findCoords: String
    val findCoordsTitle: String
    val findCoordsSearching: String
    val findCoordsNone: String
    val findCoordsFailed: String
    val findCoordsCredit: String
    fun duplicatesButton(count: Int): String
    val duplicatesTitle: String
    val duplicatesNone: String
    val duplicatesHelp: String
    val mergeKeepHint: String
    fun mergeSitesAction(count: Int): String
    val notDuplicates: String
    val mergeSitesTitle: String
    fun mergeSitesMessage(keeper: String, removed: Int, dives: Int): String
    val mergeExportFirst: String
    val mergeSitesConfirm: String
    val mergeWithOther: String
    val mergePickTitle: String
    fun mergePickHelp(keeper: String): String
    fun importAttachSimilar(count: Int): String
    val pressureLabel: String
    val consumedLabel: String
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
    val kindColorsSection: String
    val kindColorsHint: String
    val colorNone: String
    val ascentSection: String
    val ascentHint: String
    fun ascentLevel(i: Int): String
    fun ascentLimit(v: Int): String
    val ascentReset: String
    val tabSafety: String
    val safetyTitle: String
    val emergencyTitle: String
    val emergencyEurope: String
    val emergencySamu: String
    val emergencySea: String
    val safetyDisclaimer: String
    val safetySourceNote: String
    val safetyOpenSource: String
    val safetySeeProcedure: String
    val safetyCards: List<SafetyCard>
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
    override val mergePrevious = "Fusionner avec la précédente"
    override val noMerge = "Ne pas fusionner"
    override val mergeAction = "Fusionner"
    override val mergeTitle = "Fusionner les plongées ?"
    override fun mergeMessage(earlier: String, later: String) =
        "La plongée du $later sera fusionnée dans celle du $earlier : une seule plongée, du début de la première à la fin de la seconde, avec les deux courbes mises bout à bout. La plongée du $later disparaîtra. Cette action est irréversible."
    override val edit = "Modifier"
    override val save = "Enregistrer"
    override val rename = "Renommer"

    override val newSite = "Nouveau site"
    override val editSite = "Modifier le site"
    override val searchSites = "Rechercher un site"
    override val noSiteFound = "Aucun site ne correspond."
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
    override val resetAll = "Réinitialiser"
    override val editItem = "Modifier l'élément"
    override val deleteChecklist = "Supprimer la check-list"
    override val newItem = "Nouvel élément"
    override val textField = "Texte"
    override val stateYes = "Oui"
    override val stateNo = "Non"
    override val stateNoButOk = "Non mais OK"

    override val defaultChecklists = listOf(
        "Équipement plongée (voyage)" to listOf(
            "Combinaison", "Palmes", "Masque", "Tuba", "Gilet stabilisateur", "Détendeur", "Ordinateur", "Parachute", "Phare", "Ardoise",
            "Cintre", "Chaussons", "Poncho", "Tongs", "Shaker",
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
    override val unsavedTitle = "Modifications non enregistrées"
    override val unsavedMessage = "Enregistrer cette plongée avant de passer à une autre ?"
    override val unsavedDiscard = "Ignorer"
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
        DiveSort.DATE -> "Date"
        DiveSort.SITE -> "Site"
        DiveSort.COUNTRY -> "Pays"
        DiveSort.DEPTH -> "Prof. max"
        DiveSort.DURATION -> "Durée"
        DiveSort.SAC -> "SAC"
    }
    override val diveKindLabel = "Type de plongée"
    override fun diveKind(kind: DiveKind) = when (kind) {
        DiveKind.LEARNING -> "Formation"
        DiveKind.EXPLORATION -> "Exploration"
        DiveKind.SUPERVISING -> "Encadrement"
        DiveKind.TEACHING -> "Enseignement"
        DiveKind.AUTONOMOUS -> "Autonomie"
        DiveKind.OTHER -> "Autre"
    }
    override val kindOtherField = "Précisez le type de plongée"
    override val groupSection = "Palanquée"
    override val personField = "Nom"
    override val addPerson = "Ajouter une personne"
    override val shareGroupCheckbox = "Proposer de partager cette plongée avec la palanquée"
    override val shareWithPeople = "Partager avec…"
    override val shareIntroMany =
        "Envoie le fichier de la plongée, avec toutes ses données, à chacun : il l'ouvre avec SubAtlas pour l'ajouter à son carnet."
    override val usedMinutesLabel = "Durée d'utilisation"
    override val importShearwater = "Importer depuis Shearwater"
    override val shearwaterTitle = "Import Shearwater"
    override fun shearwaterFound(total: Int, created: Int, merged: Int) =
        "$total plongée${if (total > 1) "s" else ""} dans ce fichier : $created nouvelle${if (created > 1) "s" else ""}, $merged à fusionner avec une plongée du carnet."
    override val shearwaterNone = "Aucune plongée trouvée dans ce fichier."
    override val shearwaterInvalid = "Ce fichier n'est pas un export Shearwater Cloud lisible."
    override val shearwaterConfirm = "Importer"
    override fun shearwaterDone(created: Int, merged: Int) =
        "$created plongée${if (created > 1) "s" else ""} ajoutée${if (created > 1) "s" else ""}, $merged fusionnée${if (merged > 1) "s" else ""}."
    override val shearwaterNewTag = "nouvelle"
    override val shearwaterMergeTag = "fusion"
    override val shearwaterMergeHelp =
        "Une plongée du carnet à la même date et à moins d'une heure est complétée avec les données de l'ordinateur et reçoit la courbe ; rien de ce que vous avez saisi n'est écrasé."
    override fun shearwaterMore(count: Int) = "… et $count autres plongées"
    override val advancedSection = "Avancé"
    override val devSection = "Développeur"
    override val deleteNoGps = "Effacer les sites sans GPS"
    override val deleteNoGpsHint = "Supprime tous les sites sans coordonnées GPS. Les plongées sont conservées."
    override val deleteNoGpsTitle = "Effacer les sites sans GPS ?"
    override fun deleteNoGpsMessage(sites: Int, dives: Int) =
        "$sites site${if (sites > 1) "s" else ""} sans coordonnées GPS ${if (sites > 1) "seront supprimés" else "sera supprimé"}. Les $dives plongée${if (dives > 1) "s" else ""} rattachée${if (dives > 1) "s" else ""} ${if (dives > 1) "sont conservées" else "est conservée"} mais n'${if (dives > 1) "auront" else "aura"} plus de site. Cette opération est IRRÉVERSIBLE."
    override val deleteNoGpsDoneTitle = "Sites effacés"
    override fun deleteNoGpsDone(count: Int) = "$count site${if (count > 1) "s" else ""} supprimé${if (count > 1) "s" else ""}."
    override val deleteAllDives = "Effacer les plongées"
    override val deleteAllDivesHint = "Supprime définitivement toutes les plongées du carnet. Les sites, centres et listes de contrôle sont conservés."
    override val deleteAllTitle = "Effacer toutes les plongées ?"
    override fun deleteAllMessage(count: Int) =
        "Les $count plongée${if (count > 1) "s" else ""} du carnet (courbes, bouteilles, notes…) seront supprimées définitivement. Cette opération est IRRÉVERSIBLE : il n'y a ni corbeille ni annulation. Pense à exporter tes données avant."
    override val deleteAllConfirm = "Tout effacer"
    override val deleteAllDoneTitle = "Plongées effacées"
    override fun deleteAllDone(count: Int) = "Les $count plongée${if (count > 1) "s" else ""} ${if (count > 1) "ont" else "a"} été effacée${if (count > 1) "s" else ""}."
    override fun shearwaterPresent(count: Int) = "$count plongée${if (count > 1) "s" else ""} déjà créée${if (count > 1) "s" else ""} / fusionnée${if (count > 1) "s" else ""}"
    override fun shearwaterSkipped(count: Int) = "$count plongée${if (count > 1) "s" else ""} du fichier illisible${if (count > 1) "s" else ""}, ignorée${if (count > 1) "s" else ""}."
    override val shearwaterReading = "Lecture du fichier…"
    override val computerLabel = "Ordinateur"
    override val profileTitle = "Profil de plongée"
    override val profileDepthLegend = "Profondeur (m)"
    override val profilePressureLegend = "Pression (bar)"
    override val seriesNdl = "LND"
    override val seriesDeco = "Déco"
    override val seriesCns = "CNS"
    override val seriesPpo2 = "PPO2"
    override val seriesTts = "TTS"
    override val ascentRateLabel = "Vitesse de remontée"
    override fun ascentOver(limit: Int, seconds: Int) = "au-dessus de $limit m/min pendant ${if (seconds >= 60) "${seconds / 60} min ${seconds % 60} s" else "$seconds s"}"
    override val ascentFine = "toujours sous la limite"
    override fun shareWithCheckbox(name: String) = "Proposer de partager cette plongée avec $name"
    override val shareTitle = "Partager la plongée"
    override fun shareIntro(name: String) =
        "Envoie à $name un fichier avec toutes les données de la plongée (courbe, bouteilles, conditions, équipement, notes) : il l'ouvre avec SubAtlas pour l'ajouter à son carnet."
    override val shareBySms = "Envoyer par SMS"
    override val shareByEmail = "Envoyer par e-mail"
    override val shareFileButton = "Envoyer le fichier de la plongée"
    override val shareFileIntroNone = "Envoie un fichier avec toutes les données de la plongée (courbe, bouteilles, conditions, équipement, notes). Ton binôme l'ouvre avec SubAtlas pour l'ajouter à son carnet."
    override val pickContact = "Choisir dans le répertoire"
    override val shareOther = "Autres applications…"
    override val shareLater = "Plus tard"
    override fun shareButton(name: String) = "Partager avec $name"
    override val shareSubject = "Plongée partagée"
    override fun shareMessage(site: String, date: String, link: String) =
        "Plongée du $date – $site.\nOuvre ce lien pour l'ajouter à ton carnet SubAtlas (la page te propose de télécharger l'appli si besoin) : $link"
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
    override val avgDepthShort = "Prof. moyenne"
    override val avgDepthHint = "Avec les pressions, le volume des bouteilles et la durée, elle permet de calculer votre SAC (consommation à la surface)."
    override val sacLabel = "SAC"
    override val conditionsSection = "Conditions et matériel"
    override val notesLabel = "Notes"
    override val findCoords = "Rechercher les coordonnées"
    override val findCoordsTitle = "Choisis le bon lieu"
    override val findCoordsSearching = "Recherche en cours…"
    override val findCoordsNone = "Aucun résultat. Essaie un nom plus court ou sans accent, ou ajoute le pays."
    override val findCoordsFailed = "Recherche impossible : vérifie ta connexion internet et réessaie."
    override val findCoordsCredit = "Données © contributeurs OpenStreetMap"
    override fun duplicatesButton(count: Int) = "Sites en double ($count)"
    override val duplicatesTitle = "Sites en double"
    override val duplicatesNone = "Aucun doublon probable."
    override val duplicatesHelp = "Ces sites ont des noms proches. Choisis celui à conserver : les plongées des autres lui seront rattachées, puis les autres seront supprimés."
    override val mergeKeepHint = "Touche un site pour le conserver. Décoche ceux qui ne sont pas des doublons."
    override fun mergeSitesAction(count: Int) = "Fusionner ces $count sites"
    override val notDuplicates = "Ce ne sont pas des doublons"
    override val mergeSitesTitle = "Fusionner les sites ?"
    override fun mergeSitesMessage(keeper: String, removed: Int, dives: Int) =
        "« $keeper » est conservé.\n\nPlongées rattachées : $dives\nSites supprimés : $removed\n\n" +
            "Ce qui manque au site conservé (position, pays, type, notes) est complété avec les autres. " +
            "Cette opération est IRRÉVERSIBLE : pense à exporter tes données avant."
    override val mergeExportFirst = "Exporter d'abord"
    override val mergeSitesConfirm = "Fusionner"
    override val mergeWithOther = "Fusionner avec un autre site…"
    override val mergePickTitle = "Fusionner avec quel site ?"
    override fun mergePickHelp(keeper: String) = "Le site choisi sera supprimé et ses plongées rattachées à « $keeper »."
    override fun importAttachSimilar(count: Int) = "Rattacher $count site${if (count > 1) "s" else ""} à un site existant au nom proche"
    override val pressureLabel = "Pression"
    override val consumedLabel = "Consommé"
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
        "Supprimer « $name » ? Ses $diveCount plongée${if (diveCount > 1) "s" else ""} ${if (diveCount > 1) "seront conservées" else "sera conservée"} sans site."
    override fun confirmDeleteDive(site: String, date: String) = "Supprimer la plongée du $date sur « $site » ?"

    override val themeSection = "Thème"
    override val themeSystem = "Système"
    override val themeLight = "Clair"
    override val themeDark = "Sombre"
    override val languageSection = "Langue"
    override val kindColorsSection = "Couleurs des plongées"
    override val kindColorsHint = "Couleur de fond des cartes de la liste, selon le type de plongée."
    override val colorNone = "Aucune"
    override val ascentSection = "Vitesse de remontée"
    override val ascentHint = "La courbe change de couleur quand la vitesse de remontée dépasse chaque seuil."
    override fun ascentLevel(i: Int) = "Seuil $i"
    override fun ascentLimit(v: Int) = "au-delà de $v m/min"
    override val ascentReset = "Valeurs par défaut"
    override val tabSafety = "Sécurité"
    override val safetyTitle = "Procédures de sécurité"
    override val emergencyTitle = "Urgence : appeler"
    override val emergencyEurope = "112 · Numéro d'urgence européen"
    override val emergencySamu = "15 · SAMU"
    override val emergencySea = "196 · CROSS (urgence en mer)"
    override val safetyDisclaimer = "Aide-mémoire fondé sur les préconisations fédérales pour la plongée à l'air avec ordinateur. Il ne remplace ni la formation, ni les consignes du directeur de plongée, ni les secours."
    override val safetySourceNote = "Source : FFESSM, fiche guide technique « Préconisations fédérales en cas de remontées anormales en plongée avec ordinateur » (validation CDN du 13 octobre 2024)."
    override val safetyOpenSource = "Ouvrir le document officiel"
    override val safetySeeProcedure = "Voir la procédure de remontée rapide"
    override val safetyCards = listOf(
        SafetyCard(
            id = SAFETY_FAST_ASCENT,
            title = "Remontée rapide",
            summary = "Plus de 15 m/min entre 30 m et la surface : se réimmerger dans les 3 minutes.",
            sections = listOf(
                SafetySection("Définition", listOf("Remontée à plus de 15 m/min, entre 30 m et la surface, sur une distance d'au moins 10 m.")),
                SafetySection(
                    "Conduite à tenir",
                    listOf(
                        "Se réimmerger dans un délai maximum de 3 minutes, au minimum à mi-profondeur de la plongée.",
                        "Y rester 5 minutes.",
                        "Remonter, en ajoutant aux éventuels paliers indiqués par l'ordinateur ou la table : 1 minute à 6 m, puis 5 minutes à 3 m.",
                    ),
                    numbered = true,
                ),
                SafetySection(
                    "Si la réimmersion est impossible",
                    listOf("Mise sous oxygène et évacuation vers un centre hyperbare."),
                ),
                SafetySection(
                    "Exemples (plongée à 30 m)",
                    listOf(
                        "Sans palier affiché : redescendre à 15 m minimum pendant 5 min, puis 1 min à 6 m et 5 min à 3 m.",
                        "Avec 3 min à 3 m affichées : redescendre à 15 m minimum pendant 5 min, puis 1 min à 6 m et 8 min à 3 m.",
                    ),
                ),
            ),
        ),
        SafetyCard(
            id = "deco-interrupted",
            title = "Palier obligatoire interrompu",
            summary = "Se réimmerger dans les 3 minutes et ajouter 3 minutes au palier de 3 m.",
            sections = listOf(
                SafetySection(
                    "Conduite à tenir",
                    listOf(
                        "Se réimmerger dans les 3 minutes.",
                        "Suivre les indications du moyen de décompression utilisé, en ajoutant 3 minutes au palier de 3 m.",
                    ),
                    numbered = true,
                ),
                SafetySection(
                    "Si la réimmersion est impossible",
                    listOf(
                        "Signe d'un possible accident, OU plus de 3 minutes de paliers non réalisés : mise sous oxygène et déclenchement des secours.",
                        "Pas de signe d'un possible accident, ET moins de 3 minutes de paliers non réalisés : période d'observation de 3 heures et interdiction de replonger pendant 24 heures. Au moindre signe pouvant évoquer un accident : déclenchement des secours.",
                    ),
                ),
            ),
        ),
        SafetyCard(
            id = "yoyo",
            title = "Remontées multiples (yoyos)",
            summary = "Si possible, ne pas dépasser le seuil de 6 m ; limites recommandées par la CTN.",
            sections = listOf(
                SafetySection(
                    "Conduite à tenir",
                    listOf("En cas de remontées multiples, elles ne doivent pas, si possible, dépasser le seuil de 6 m."),
                ),
                SafetySection(
                    "Maximum recommandé par la CTN",
                    listOf("4 remontées de 20 m", "3 remontées de 25 m ou de 30 m", "2 remontées de 40 m"),
                ),
            ),
        ),
    )
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
    override val mergePrevious = "Merge with previous"
    override val noMerge = "Do not merge"
    override val mergeAction = "Merge"
    override val mergeTitle = "Merge the dives?"
    override fun mergeMessage(earlier: String, later: String) =
        "The dive of $later will be merged into the one of $earlier: a single dive, from the start of the first to the end of the second, with both profiles joined. The dive of $later will disappear. This cannot be undone."
    override val edit = "Edit"
    override val save = "Save"
    override val rename = "Rename"

    override val newSite = "New site"
    override val editSite = "Edit site"
    override val searchSites = "Search a site"
    override val noSiteFound = "No matching site."
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
    override val resetAll = "Reset all"
    override val editItem = "Edit item"
    override val deleteChecklist = "Delete checklist"
    override val newItem = "New item"
    override val textField = "Text"
    override val stateYes = "Yes"
    override val stateNo = "No"
    override val stateNoButOk = "No but OK"

    override val defaultChecklists = listOf(
        "Dive gear (travel)" to listOf(
            "Wetsuit", "Fins", "Mask", "Snorkel", "BCD", "Regulator", "Dive computer", "Surface marker buoy", "Dive light", "Slate",
            "Hanger", "Booties", "Poncho", "Flip-flops", "Shaker",
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
    override val unsavedTitle = "Unsaved changes"
    override val unsavedMessage = "Save this dive before moving to another one?"
    override val unsavedDiscard = "Discard"
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
        DiveSort.DATE -> "Date"
        DiveSort.SITE -> "Site"
        DiveSort.COUNTRY -> "Country"
        DiveSort.DEPTH -> "Max depth"
        DiveSort.DURATION -> "Duration"
        DiveSort.SAC -> "SAC"
    }
    override val diveKindLabel = "Dive type"
    override fun diveKind(kind: DiveKind) = when (kind) {
        DiveKind.LEARNING -> "Training"
        DiveKind.EXPLORATION -> "Exploration"
        DiveKind.SUPERVISING -> "Supervising"
        DiveKind.TEACHING -> "Teaching"
        DiveKind.AUTONOMOUS -> "Autonomous"
        DiveKind.OTHER -> "Other"
    }
    override val kindOtherField = "Describe the dive type"
    override val groupSection = "Group"
    override val personField = "Name"
    override val addPerson = "Add a person"
    override val shareGroupCheckbox = "Offer to share this dive with the group"
    override val shareWithPeople = "Share with…"
    override val shareIntroMany =
        "Send the dive file, with all its data, to each person: they open it with SubAtlas to add it to their logbook."
    override val usedMinutesLabel = "Time on this tank"
    override val importShearwater = "Import from Shearwater"
    override val shearwaterTitle = "Shearwater import"
    override fun shearwaterFound(total: Int, created: Int, merged: Int) =
        "$total dive${if (total != 1) "s" else ""} in this file: $created new, $merged to merge with a logbook dive."
    override val shearwaterNone = "No dive found in this file."
    override val shearwaterInvalid = "This file is not a readable Shearwater Cloud export."
    override val shearwaterConfirm = "Import"
    override fun shearwaterDone(created: Int, merged: Int) = "$created dive${if (created != 1) "s" else ""} added, $merged merged."
    override val shearwaterNewTag = "new"
    override val shearwaterMergeTag = "merge"
    override val shearwaterMergeHelp =
        "A logbook dive on the same date and within one hour is completed with the computer's data and gets the profile; nothing you typed is overwritten."
    override fun shearwaterMore(count: Int) = "… and $count more dives"
    override val advancedSection = "Advanced"
    override val devSection = "Developer"
    override val deleteNoGps = "Delete sites without GPS"
    override val deleteNoGpsHint = "Deletes every site without GPS coordinates. Dives are kept."
    override val deleteNoGpsTitle = "Delete sites without GPS?"
    override fun deleteNoGpsMessage(sites: Int, dives: Int) =
        "$sites site${if (sites != 1) "s" else ""} without GPS coordinates will be deleted. The $dives attached dive${if (dives != 1) "s" else ""} will be kept but left without a site. This CANNOT be undone."
    override val deleteNoGpsDoneTitle = "Sites deleted"
    override fun deleteNoGpsDone(count: Int) = "$count site${if (count != 1) "s" else ""} deleted."
    override val deleteAllDives = "Delete all dives"
    override val deleteAllDivesHint = "Permanently deletes every dive in the logbook. Sites, centers and checklists are kept."
    override val deleteAllTitle = "Delete all dives?"
    override fun deleteAllMessage(count: Int) =
        "The $count dive${if (count != 1) "s" else ""} in the logbook (profiles, tanks, notes…) will be permanently deleted. This CANNOT be undone: there is no trash and no undo. Consider exporting your data first."
    override val deleteAllConfirm = "Delete everything"
    override val deleteAllDoneTitle = "Dives deleted"
    override fun deleteAllDone(count: Int) = "$count dive${if (count != 1) "s" else ""} ${if (count != 1) "were" else "was"} deleted."
    override fun shearwaterPresent(count: Int) = "$count dive${if (count != 1) "s" else ""} already created / merged"
    override fun shearwaterSkipped(count: Int) = "$count unreadable dive${if (count != 1) "s" else ""} in the file skipped."
    override val shearwaterReading = "Reading the file…"
    override val computerLabel = "Dive computer"
    override val profileTitle = "Dive profile"
    override val profileDepthLegend = "Depth (m)"
    override val profilePressureLegend = "Pressure (bar)"
    override val seriesNdl = "NDL"
    override val seriesDeco = "Deco"
    override val seriesCns = "CNS"
    override val seriesPpo2 = "PPO2"
    override val seriesTts = "TTS"
    override val ascentRateLabel = "Ascent rate"
    override fun ascentOver(limit: Int, seconds: Int) = "above $limit m/min for ${if (seconds >= 60) "${seconds / 60} min ${seconds % 60} s" else "$seconds s"}"
    override val ascentFine = "always under the limit"
    override fun shareWithCheckbox(name: String) = "Offer to share this dive with $name"
    override val shareTitle = "Share the dive"
    override fun shareIntro(name: String) =
        "Send $name a file with all the dive's data (profile, tanks, conditions, gear, notes): they open it with SubAtlas to add it to their logbook."
    override val shareBySms = "Send by SMS"
    override val shareByEmail = "Send by email"
    override val shareFileButton = "Send the dive file"
    override val shareFileIntroNone = "Sends a file with all the dive's data (profile, tanks, conditions, gear, notes). Your buddy opens it with SubAtlas to add it to their logbook."
    override val pickContact = "Pick from contacts"
    override val shareOther = "Other apps…"
    override val shareLater = "Later"
    override fun shareButton(name: String) = "Share with $name"
    override val shareSubject = "Shared dive"
    override fun shareMessage(site: String, date: String, link: String) =
        "Dive on $date – $site.\nOpen this link to add it to your SubAtlas logbook (the page offers to download the app if you need it): $link"
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
    override val avgDepthShort = "Avg depth"
    override val avgDepthHint = "Together with the pressures, the tank volumes and the duration, it lets the app compute your SAC (surface air consumption)."
    override val sacLabel = "SAC"
    override val conditionsSection = "Conditions and gear"
    override val notesLabel = "Notes"
    override val findCoords = "Find coordinates"
    override val findCoordsTitle = "Pick the right place"
    override val findCoordsSearching = "Searching…"
    override val findCoordsNone = "No result. Try a shorter name, without accents, or add the country."
    override val findCoordsFailed = "Search failed: check your internet connection and try again."
    override val findCoordsCredit = "Data © OpenStreetMap contributors"
    override fun duplicatesButton(count: Int) = "Duplicate sites ($count)"
    override val duplicatesTitle = "Duplicate sites"
    override val duplicatesNone = "No likely duplicates."
    override val duplicatesHelp = "These sites have similar names. Pick the one to keep: the dives of the others are moved to it, then the others are deleted."
    override val mergeKeepHint = "Tap a site to keep it. Untick the ones that are not duplicates."
    override fun mergeSitesAction(count: Int) = "Merge these $count sites"
    override val notDuplicates = "These are not duplicates"
    override val mergeSitesTitle = "Merge the sites?"
    override fun mergeSitesMessage(keeper: String, removed: Int, dives: Int) =
        "\"$keeper\" is kept.\n\nDives moved: $dives\nSites deleted: $removed\n\n" +
            "Whatever the kept site lacks (position, country, type, notes) is filled in from the others. " +
            "This is IRREVERSIBLE: export your data first."
    override val mergeExportFirst = "Export first"
    override val mergeSitesConfirm = "Merge"
    override val mergeWithOther = "Merge with another site…"
    override val mergePickTitle = "Merge with which site?"
    override fun mergePickHelp(keeper: String) = "The chosen site will be deleted and its dives moved to \"$keeper\"."
    override fun importAttachSimilar(count: Int) = "Attach $count site${if (count > 1) "s" else ""} to an existing site with a similar name"
    override val pressureLabel = "Pressure"
    override val consumedLabel = "Used"
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
        "Delete “$name”? Its $diveCount dive${if (diveCount != 1) "s" else ""} will be kept without a site."
    override fun confirmDeleteDive(site: String, date: String) = "Delete the dive of $date at “$site”?"

    override val themeSection = "Theme"
    override val themeSystem = "System"
    override val themeLight = "Light"
    override val themeDark = "Dark"
    override val languageSection = "Language"
    override val kindColorsSection = "Dive colours"
    override val kindColorsHint = "Background colour of the cards in the dive list, by dive type."
    override val colorNone = "None"
    override val ascentSection = "Ascent rate"
    override val ascentHint = "The curve changes colour when the ascent rate goes above each threshold."
    override fun ascentLevel(i: Int) = "Threshold $i"
    override fun ascentLimit(v: Int) = "above $v m/min"
    override val ascentReset = "Reset to defaults"
    override val tabSafety = "Safety"
    override val safetyTitle = "Safety procedures"
    override val emergencyTitle = "Emergency: call"
    override val emergencyEurope = "112 · European emergency number"
    override val emergencySamu = "15 · SAMU (medical emergency, France)"
    override val emergencySea = "196 · CROSS (emergency at sea, France)"
    override val safetyDisclaimer = "Memo based on the French federation's recommendations for air diving with a dive computer. It replaces neither training, nor the dive director's instructions, nor the emergency services."
    override val safetySourceNote = "Source: FFESSM, technical guide sheet \"Federal recommendations for abnormal ascents when diving with a computer\" (validated by the CDN on 13 October 2024). In French."
    override val safetyOpenSource = "Open the official document"
    override val safetySeeProcedure = "See the fast ascent procedure"
    override val safetyCards = listOf(
        SafetyCard(
            id = SAFETY_FAST_ASCENT,
            title = "Fast ascent",
            summary = "Over 15 m/min between 30 m and the surface: go back down within 3 minutes.",
            sections = listOf(
                SafetySection("Definition", listOf("An ascent faster than 15 m/min, between 30 m and the surface, over a distance of at least 10 m.")),
                SafetySection(
                    "What to do",
                    listOf(
                        "Go back down within 3 minutes at most, to at least half the dive's depth.",
                        "Stay there for 5 minutes.",
                        "Come back up, adding to any stops shown by the computer or table: 1 minute at 6 m, then 5 minutes at 3 m.",
                    ),
                    numbered = true,
                ),
                SafetySection(
                    "If going back down is impossible",
                    listOf("Give oxygen and evacuate to a hyperbaric centre."),
                ),
                SafetySection(
                    "Examples (30 m dive)",
                    listOf(
                        "No stop shown: go back down to 15 m or deeper for 5 min, then 1 min at 6 m and 5 min at 3 m.",
                        "3 min at 3 m shown: go back down to 15 m or deeper for 5 min, then 1 min at 6 m and 8 min at 3 m.",
                    ),
                ),
            ),
        ),
        SafetyCard(
            id = "deco-interrupted",
            title = "Interrupted mandatory stop",
            summary = "Go back down within 3 minutes and add 3 minutes to the 3 m stop.",
            sections = listOf(
                SafetySection(
                    "What to do",
                    listOf(
                        "Go back down within 3 minutes.",
                        "Follow the decompression device's indications, adding 3 minutes to the 3 m stop.",
                    ),
                    numbered = true,
                ),
                SafetySection(
                    "If going back down is impossible",
                    listOf(
                        "Sign of a possible accident, OR more than 3 minutes of stops not done: give oxygen and call the emergency services.",
                        "No sign of a possible accident, AND less than 3 minutes of stops not done: observe for 3 hours and no diving for 24 hours. At the slightest sign that could suggest an accident: call the emergency services.",
                    ),
                ),
            ),
        ),
        SafetyCard(
            id = "yoyo",
            title = "Multiple ascents (yo-yos)",
            summary = "If possible, do not go above 6 m; limits recommended by the CTN.",
            sections = listOf(
                SafetySection(
                    "What to do",
                    listOf("With multiple ascents, they should, if possible, not go above the 6 m threshold."),
                ),
                SafetySection(
                    "Maximum recommended by the CTN",
                    listOf("4 ascents from 20 m", "3 ascents from 25 m or 30 m", "2 ascents from 40 m"),
                ),
            ),
        ),
    )
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
