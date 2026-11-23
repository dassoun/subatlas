package com.sensitivemobility.subatlas

/** Textes de la synchronisation avec SubAtlas.com (français et anglais, comme le reste de l'appli : voir [Strings.sync]). */
interface SyncStrings {
    val title: String
    val hint: String
    val serverLabel: String
    val codeLabel: String
    val codeHelp: String
    val connect: String
    val connecting: String
    val useEmulator: String
    val linkReceived: String
    fun connectedAs(name: String): String
    fun lastSync(date: String): String
    val neverSynced: String
    val syncNow: String
    val disconnect: String
    val disconnectTitle: String
    val disconnectMessage: String
    val cancelSync: String
    val close: String

    // Erreurs
    val errInvalidServer: String
    val errInsecure: String
    val errInvalidCode: String
    val errNetwork: String
    val errExpired: String
    fun errRateLimited(seconds: Int): String
    fun errApi(code: String, message: String): String

    // Progression
    val phaseReading: String
    val phaseSites: String
    val phaseApplying: String
    val phaseSendingSites: String
    val phaseSendingDives: String
    val phaseProfiles: String
    val phasePhotos: String
    val phaseDeleting: String
    fun waiting(seconds: Int): String
    fun progress(done: Int, total: Int): String

    // Aperçu
    val previewTitle: String
    val previewIntro: String
    val upToDate: String
    val fullSyncNote: String
    fun toSend(dives: Int, sites: Int): String
    fun toReceive(dives: Int): String
    fun matchedLine(n: Int): String
    fun deletionsLine(n: Int): String
    fun conflictsLine(n: Int): String
    fun profilesLine(send: Int, receive: Int): String
    fun photosLine(send: Int, receive: Int, remove: Int): String
    val siteLinksTitle: String
    val siteLinksHint: String
    fun siteLinkRow(remote: String, local: String): String
    val removeFromAppTitle: String
    val removeFromAppHint: String
    val deleteFromSiteTitle: String
    val deleteFromSiteHint: String
    val keptTitle: String
    val keptHint: String
    val conflictsTitle: String
    val conflictsHint: String
    val conflictBoth: String
    val conflictDeletedHere: String
    val keepSite: String
    val keepApp: String
    val decideLater: String
    val removePhotos: String
    val runSync: String

    // Rapport
    val reportTitle: String
    val reportInterrupted: String
    val reportDone: String
    fun reportSent(dives: Int, sites: Int): String
    fun reportReceived(n: Int): String
    fun reportMatched(n: Int): String
    fun reportRemoved(fromApp: Int, fromSite: Int): String
    fun reportProfiles(sent: Int, received: Int): String
    fun reportPhotos(sent: Int, received: Int, removed: Int): String
    fun reportConflicts(resolved: Int, pending: Int): String
    fun reportKept(n: Int): String
    fun reportRejected(n: Int): String
    val reportFullSync: String
    val reportNothing: String
}

object FrSyncStrings : SyncStrings {
    override val title = "Synchronisation SubAtlas.com"
    override val hint = "Facultatif. Envoyez vos plongées (avec leurs sites, courbes et photos) sur votre compte SubAtlas.com et recevez celles du site. Rien ne part en arrière-plan : tout se fait quand vous appuyez sur « Synchroniser », et rien n'est envoyé, reçu, remplacé ni supprimé sans votre confirmation."
    override val serverLabel = "Adresse du serveur"
    override val codeLabel = "Code de connexion"
    override val codeHelp = "Sur le site : Réglages > Appareils et API > « Connecter un appareil »."
    override val connect = "Connecter"
    override val connecting = "Connexion…"
    override val useEmulator = "Émulateur : http://10.0.2.2:8080"
    override val linkReceived = "Lien de connexion reçu : vérifiez l'adresse, puis appuyez sur « Connecter »."
    override fun connectedAs(name: String) = "Connecté : $name"
    override fun lastSync(date: String) = "Dernière synchronisation : $date"
    override val neverSynced = "Pas encore synchronisé"
    override val syncNow = "Synchroniser"
    override val disconnect = "Se déconnecter"
    override val disconnectTitle = "Se déconnecter ?"
    override val disconnectMessage = "L'appli ne synchronisera plus tant que vous ne vous reconnectez pas. Vos plongées restent dans l'appli et sur le site."
    override val cancelSync = "Interrompre"
    override val close = "Fermer"

    override val errInvalidServer = "Adresse du serveur invalide."
    override val errInsecure = "Connexion non chiffrée refusée : utilisez une adresse en https://."
    override val errInvalidCode = "Code refusé ou expiré. Générez-en un nouveau sur le site."
    override val errNetwork = "Impossible de joindre le serveur. Vérifiez la connexion et l'adresse."
    override val errExpired = "Session expirée ou révoquée : reconnectez l'appareil."
    override fun errRateLimited(seconds: Int) = "Trop de requêtes : réessayez dans $seconds s."
    override fun errApi(code: String, message: String) = "Le serveur a répondu : $message ($code)"

    override val phaseReading = "Lecture des modifications du site"
    override val phaseSites = "Lecture des sites"
    override val phaseApplying = "Réception"
    override val phaseSendingSites = "Envoi des sites"
    override val phaseSendingDives = "Envoi des plongées"
    override val phaseProfiles = "Courbes"
    override val phasePhotos = "Photos"
    override val phaseDeleting = "Suppressions"
    override fun waiting(seconds: Int) = "Limite du serveur atteinte : reprise dans $seconds s"
    override fun progress(done: Int, total: Int) = if (total > 0) "$done / $total" else "$done"

    override val previewTitle = "Synchronisation : aperçu"
    override val previewIntro = "Rien n'a encore été modifié. Vérifiez, puis confirmez."
    override val upToDate = "Tout est à jour."
    override val fullSyncNote = "Synchronisation complète du site (première synchronisation, ou curseur périmé)."
    override fun toSend(dives: Int, sites: Int) = "À envoyer : $dives plongée${if (dives > 1) "s" else ""}, $sites site${if (sites > 1) "s" else ""}."
    override fun toReceive(dives: Int) = "À recevoir : $dives plongée${if (dives > 1) "s" else ""}."
    override fun matchedLine(n: Int) = "Déjà présentes (rapprochées) : $n."
    override fun deletionsLine(n: Int) = "Suppressions : $n."
    override fun conflictsLine(n: Int) = "Conflits : $n."
    override fun profilesLine(send: Int, receive: Int) = "Courbes : $send à envoyer, $receive à recevoir."
    override fun photosLine(send: Int, receive: Int, remove: Int) = "Photos : $send à envoyer, $receive à recevoir" + (if (remove > 0) ", $remove à retirer du site." else ".")
    override val siteLinksTitle = "Sites rapprochés"
    override val siteLinksHint = "Un site du site web ressemble à un site de votre carnet. Décochez pour créer un nouveau site à la place."
    override fun siteLinkRow(remote: String, local: String) = "« $remote » = « $local »"
    override val removeFromAppTitle = "Supprimées sur le site"
    override val removeFromAppHint = "Cochez celles à retirer de l'appli. Les autres sont gardées dans l'appli et ne seront plus synchronisées."
    override val deleteFromSiteTitle = "Supprimées dans l'appli"
    override val deleteFromSiteHint = "Cochez celles à supprimer aussi du site. Les autres restent sur le site et ne seront pas reproposées."
    override val keptTitle = "Gardées dans l'appli"
    override val keptHint = "Supprimées sur le site, mais modifiées dans l'appli depuis la dernière synchronisation : elles sont gardées et ne seront plus synchronisées."
    override val conflictsTitle = "Conflits"
    override val conflictsHint = "Modifiées des deux côtés. Choisissez la version à garder ; sans choix, rien n'est modifié et le conflit reviendra."
    override val conflictBoth = "modifiée dans l'appli et sur le site"
    override val conflictDeletedHere = "supprimée dans l'appli, modifiée sur le site"
    override val keepSite = "Garder la version du site"
    override val keepApp = "Garder celle de l'appli"
    override val decideLater = "Décider plus tard"
    override val removePhotos = "Retirer du site les photos supprimées dans l'appli"
    override val runSync = "Synchroniser"

    override val reportTitle = "Rapport de synchronisation"
    override val reportInterrupted = "Synchronisation interrompue. Relancez-la : elle reprendra là où elle s'est arrêtée, sans doublon."
    override val reportDone = "Synchronisation terminée."
    override fun reportSent(dives: Int, sites: Int) = "Envoyées : $dives plongée${if (dives > 1) "s" else ""}, $sites site${if (sites > 1) "s" else ""}."
    override fun reportReceived(n: Int) = "Reçues : $n plongée${if (n > 1) "s" else ""}."
    override fun reportMatched(n: Int) = "Rapprochées : $n."
    override fun reportRemoved(fromApp: Int, fromSite: Int) = "Supprimées : $fromApp dans l'appli, $fromSite sur le site."
    override fun reportProfiles(sent: Int, received: Int) = "Courbes : $sent envoyées, $received reçues."
    override fun reportPhotos(sent: Int, received: Int, removed: Int) = "Photos : $sent envoyées, $received reçues, $removed retirées du site."
    override fun reportConflicts(resolved: Int, pending: Int) = "Conflits : $resolved résolus, $pending en attente."
    override fun reportKept(n: Int) = "Gardées dans l'appli (supprimées sur le site) : $n."
    override fun reportRejected(n: Int) = "Refusées par le site : $n"
    override val reportFullSync = "Synchronisation complète du site effectuée."
    override val reportNothing = "Rien à synchroniser."
}

object EnSyncStrings : SyncStrings {
    override val title = "SubAtlas.com sync"
    override val hint = "Optional. Send your dives (with their sites, profiles and photos) to your SubAtlas.com account and receive the ones from the website. Nothing runs in the background: it all happens when you tap “Sync”, and nothing is sent, received, replaced or deleted without your confirmation."
    override val serverLabel = "Server address"
    override val codeLabel = "Connection code"
    override val codeHelp = "On the website: Settings > Devices and API > “Connect a device”."
    override val connect = "Connect"
    override val connecting = "Connecting…"
    override val useEmulator = "Emulator: http://10.0.2.2:8080"
    override val linkReceived = "Connection link received: check the address, then tap “Connect”."
    override fun connectedAs(name: String) = "Connected: $name"
    override fun lastSync(date: String) = "Last sync: $date"
    override val neverSynced = "Not synced yet"
    override val syncNow = "Sync"
    override val disconnect = "Disconnect"
    override val disconnectTitle = "Disconnect?"
    override val disconnectMessage = "The app will stop syncing until you connect again. Your dives stay in the app and on the website."
    override val cancelSync = "Stop"
    override val close = "Close"

    override val errInvalidServer = "Invalid server address."
    override val errInsecure = "Unencrypted connection refused: use an https:// address."
    override val errInvalidCode = "Code refused or expired. Generate a new one on the website."
    override val errNetwork = "Cannot reach the server. Check your connection and the address."
    override val errExpired = "Session expired or revoked: connect the device again."
    override fun errRateLimited(seconds: Int) = "Too many requests: try again in $seconds s."
    override fun errApi(code: String, message: String) = "The server answered: $message ($code)"

    override val phaseReading = "Reading changes from the website"
    override val phaseSites = "Reading sites"
    override val phaseApplying = "Receiving"
    override val phaseSendingSites = "Sending sites"
    override val phaseSendingDives = "Sending dives"
    override val phaseProfiles = "Profiles"
    override val phasePhotos = "Photos"
    override val phaseDeleting = "Deletions"
    override fun waiting(seconds: Int) = "Server limit reached: resuming in $seconds s"
    override fun progress(done: Int, total: Int) = if (total > 0) "$done / $total" else "$done"

    override val previewTitle = "Sync: preview"
    override val previewIntro = "Nothing has been changed yet. Check, then confirm."
    override val upToDate = "Everything is up to date."
    override val fullSyncNote = "Full sync of the website (first sync, or the cursor expired)."
    override fun toSend(dives: Int, sites: Int) = "To send: $dives dive${if (dives != 1) "s" else ""}, $sites site${if (sites != 1) "s" else ""}."
    override fun toReceive(dives: Int) = "To receive: $dives dive${if (dives != 1) "s" else ""}."
    override fun matchedLine(n: Int) = "Already present (matched): $n."
    override fun deletionsLine(n: Int) = "Deletions: $n."
    override fun conflictsLine(n: Int) = "Conflicts: $n."
    override fun profilesLine(send: Int, receive: Int) = "Profiles: $send to send, $receive to receive."
    override fun photosLine(send: Int, receive: Int, remove: Int) = "Photos: $send to send, $receive to receive" + (if (remove > 0) ", $remove to remove from the site." else ".")
    override val siteLinksTitle = "Matched sites"
    override val siteLinksHint = "A website site looks like a site in your logbook. Untick to create a new site instead."
    override fun siteLinkRow(remote: String, local: String) = "“$remote” = “$local”"
    override val removeFromAppTitle = "Deleted on the website"
    override val removeFromAppHint = "Tick the ones to remove from the app. The others are kept in the app and will no longer be synced."
    override val deleteFromSiteTitle = "Deleted in the app"
    override val deleteFromSiteHint = "Tick the ones to delete from the website too. The others stay on the website and won't be offered again."
    override val keptTitle = "Kept in the app"
    override val keptHint = "Deleted on the website but modified in the app since the last sync: they are kept and will no longer be synced."
    override val conflictsTitle = "Conflicts"
    override val conflictsHint = "Changed on both sides. Choose the version to keep; with no choice nothing is changed and the conflict comes back."
    override val conflictBoth = "changed in the app and on the website"
    override val conflictDeletedHere = "deleted in the app, changed on the website"
    override val keepSite = "Keep the website version"
    override val keepApp = "Keep the app version"
    override val decideLater = "Decide later"
    override val removePhotos = "Remove from the website the photos deleted in the app"
    override val runSync = "Sync"

    override val reportTitle = "Sync report"
    override val reportInterrupted = "Sync interrupted. Run it again: it resumes where it stopped, without duplicates."
    override val reportDone = "Sync finished."
    override fun reportSent(dives: Int, sites: Int) = "Sent: $dives dive${if (dives != 1) "s" else ""}, $sites site${if (sites != 1) "s" else ""}."
    override fun reportReceived(n: Int) = "Received: $n dive${if (n != 1) "s" else ""}."
    override fun reportMatched(n: Int) = "Matched: $n."
    override fun reportRemoved(fromApp: Int, fromSite: Int) = "Deleted: $fromApp in the app, $fromSite on the website."
    override fun reportProfiles(sent: Int, received: Int) = "Profiles: $sent sent, $received received."
    override fun reportPhotos(sent: Int, received: Int, removed: Int) = "Photos: $sent sent, $received received, $removed removed from the website."
    override fun reportConflicts(resolved: Int, pending: Int) = "Conflicts: $resolved resolved, $pending pending."
    override fun reportKept(n: Int) = "Kept in the app (deleted on the website): $n."
    override fun reportRejected(n: Int) = "Refused by the website: $n"
    override val reportFullSync = "Full website sync done."
    override val reportNothing = "Nothing to sync."
}
