# Import Bluetooth depuis un ordinateur de plongée : parcours à réaliser

Cahier des charges demandé par l'utilisateur (21 novembre 2026). **Pas encore réalisé** : aujourd'hui l'import passe par
Réglages > Données > « Importer d'un Shearwater en Bluetooth » (`ShearwaterBleDialog.kt`), qui cherche l'appareil à chaque fois.
Ce document décrit le parcours cible pour le Peregrine ; **c'est aussi la marche à suivre quand on ajoutera un autre modèle** d'ordinateur
de plongée (voir « Autres modèles »).

## Parcours voulu

**Réglages > « Importer depuis un ordinateur de plongée »**, avec une section par modèle, la première étant **Peregrine** :

1. **Associer** : un bouton « Associer un ordinateur de plongée » cherche les appareils Bluetooth, l'utilisateur touche son Peregrine, et
   l'association est **enregistrée** (adresse Bluetooth, nom, modèle, numéro de série ; la section affiche « Peregrine TX (977D8B42) »).
2. **Détection** : une fois associé, quand l'utilisateur **active le Bluetooth sur le Peregrine**, l'appli le voit et peut lancer
   l'import directement, sans nouvelle recherche ni choix de l'appareil.
3. **Import avec confirmation (par défaut)** : seules les **nouvelles plongées** sont proposées (celles qui ne sont pas déjà dans le
   carnet : clé `shearwater-ble:<série>:<heure>` ou plongée Shearwater Cloud du même jour, à moins d'une heure, à 0,3 m près) ; l'utilisateur
   **confirme** l'import, qui passe par l'aperçu habituel (`ImportPlanDialog`).
4. **Case « Importer automatiquement les nouvelles plongées »** : cochée, l'import se fait **sans demander de confirmation**. Une **jauge de
   progression** et un **message du traitement en cours** (connexion, lecture du sommaire, « Plongée 2 sur 3 · 40 % », ajout au carnet)
   doivent apparaître. À la fin, un message récapitulatif (« 3 plongées ajoutées, 1 fusionnée »).
5. **« Dissocier le Peregrine »** (le libellé le plus courant ; « Oublier » en variante) : **efface l'enregistrement**. L'association, la
   détection et le téléchargement automatique cessent. Redevenir possible : « Associer » à nouveau. Une confirmation avant d'effacer.

## Règles à respecter

- **Stockage** : l'association est un réglage de l'appli (`Settings.kt`, `settings.json`) : adresse, nom, identifiant de modèle, numéro de
  série, case « import automatique ». Prévoir une **liste** (plusieurs ordinateurs, un par modèle) plutôt qu'un champ unique. « Dissocier »
  supprime l'entrée ; rien d'autre ne subsiste.
- **Import automatique** : aucune boîte de dialogue ; les décisions par défaut de l'aperçu s'appliquent (rattachement des sites au nom
  proche accepté seulement s'il est sûr, sinon site créé ; fusion sans rien écraser). Les erreurs restent **visibles** (message en haut,
  comme dans la fenêtre actuelle) : coupure = message clair, pas de reprise automatique (le Peregrine cesse de transmettre dès que la
  liaison est coupée).
- **Un seul client à la fois** : l'ordinateur ne parle qu'à une appli ; Shearwater Cloud doit être fermé. Le dire dans l'interface.
- **Une connexion par activation du Bluetooth** (vu le 25 novembre 2026 sur un Perdix, V102 Classic) : après une coupure, même propre,
  l'ordinateur ne se signale plus et la connexion suivante échoue (« ordinateur introuvable ») tant que son Bluetooth n'est pas quitté
  puis réactivé. L'association **enchaîne donc l'import sur sa propre connexion**, sans la fermer.
- **Comportement voulu pour tout ordinateur** (confirmé le 26 novembre 2026) : relancer le Bluetooth de l'ordinateur, SubAtlas ouverte,
  affiche de lui-même la liste des nouvelles plongées ; sans relance, rien ne repart après un import. À vérifier pour chaque marque.
  Sur un Mares, une coupure de 5 s au moins est nécessaire.
- **Un import par apparition** (26 novembre 2026), selon `ComputerDriver.hidesAfterConnection` :
  - un **Mares** continue de s'annoncer après la connexion : tout import (détection, bouton, association) désarme la détection jusqu'à ce
    que l'ordinateur disparaisse ;
  - un **Shearwater** se tait après la connexion : il compte de nouveau dès qu'on le revoit, c'est-à-dire dès que son Bluetooth est
    réactivé, même aussitôt (exiger d'abord une disparition observée empêchait la détection après un import sur un Peregrine) ;
  - désarmée, la détection écoute l'ordinateur **sans interruption** (`BleTransport.waitForDeparture`) et le tient pour parti après
    5 s de silence : une coupure brève suffit. Pas de recherches courtes enchaînées : Android les refuse (« scanning too frequently »),
    l'appareil semblait parti et l'import repartait en boucle.
- **Noms selon l'appareil** (demandé le 25 novembre 2026) : la section et les messages portent le modèle de l'ordinateur connecté
  (`DiveComputer.displayName`), jamais un modèle écrit en dur. Une seule section, avec un bloc par ordinateur associé (plusieurs
  possibles depuis le 26 novembre 2026).
- **Écran allumé** pendant un téléchargement (`PlatformActions.setKeepScreenOn`, déjà en place).
- Tout fichier ou donnée qui modifie le carnet demande confirmation, **sauf** quand la case d'import automatique est cochée (c'est son objet).

## Détection : uniquement quand SubAtlas est ouverte (décision du 21 novembre 2026)

L'utilisateur ne veut la détection et l'import **que lorsque l'appli est ouverte** : pas de service en arrière-plan, pas de notification,
pas de CompanionDeviceManager. L'appli cherche l'adresse enregistrée (recherche Bluetooth filtrée sur l'adresse, ou connexion directe à
l'adresse) tant qu'elle est au premier plan, et propose l'import dès que le Peregrine apparaît (Bluetooth activé sur l'ordinateur).

- À vérifier : l'adresse (`EC:12:C5:6C:A1:FA` vue jusqu'ici) reste stable d'une session à l'autre.
- Le Peregrine n'est visible que lorsque son Bluetooth est activé ; un seul client à la fois (fermer Shearwater Cloud).
- iPhone : pas de couche Bluetooth aujourd'hui (non compilé).

## Réalisation (par étapes)

1. **Association** (fait le 21 novembre 2026) : section « Importer depuis un ordinateur de plongée > Peregrine » dans les Réglages, bouton
   « Associer un ordinateur de plongée » (recherche, choix, vérification de l'identité, enregistrement), état de l'association, bouton
   « Dissocier le Peregrine ». Données : `Settings.computers` (`DiveComputer`), écran `ComputerSettings.kt`.
2. **Détection appli ouverte + import avec confirmation** (réalisé le 21 novembre 2026, à vérifier avec un vrai Peregrine) :
   `ComputerWatcher` guette l'adresse associée, `ComputerImport` lit le sommaire et propose les nouvelles plongées, `ShearwaterSession`
   porte la connexion et le téléchargement.
3. **Case « Importer automatiquement les nouvelles plongées »** (réalisée, même remarque) : import sans confirmation, avec jauge et messages.

## Organisation du code (demandée le 26 novembre 2026)

Un seul bouton « Associer un ordinateur de plongée » et un seul parcours pour toutes les marques ; le code de chaque marque est à part.

| Rôle | Commun | Shearwater | Mares |
| --- | --- | --- | --- |
| Ce que l'appli voit d'un ordinateur (pilote, session, plongée listée) | `DiveComputers.kt` | `ShearwaterSession.kt` | `MaresSession.kt` |
| Protocole Bluetooth | `Ble.kt`, `AndroidBle.kt` | `ShearwaterBle.kt` | `MaresBle.kt` |
| Décodage d'une plongée | `ImportedDive.kt` (le résultat) | `ShearwaterBleImport.kt` | `MaresDive.kt` |
| Écrans et détection | `ComputerSettings.kt`, `ComputerImport.kt` | | |

**Ajouter une marque** : écrire son `ComputerDriver` (reconnaître l'appareil, ouvrir une `ComputerSession`), son protocole et son décodage
vers `ImportedDive`, inscrire le pilote dans `computerDrivers` et son service Bluetooth dans `AndroidBle.SERIAL_SERVICES`. L'association,
la détection, l'import avec confirmation ou automatique, la jauge, les messages et la dissociation sont communs.

La fenêtre « Importer d'un Shearwater en Bluetooth » (`ShearwaterBleDialog.kt`) est antérieure et reste propre aux Shearwater.

## Déjà en place (octobre à novembre 2026)

Couche Bluetooth Android (`AndroidBle.kt`, MTU demandée à 517), protocole Shearwater (`ShearwaterBle.kt`), conversion
(`ShearwaterBleImport.kt` : une bouteille par gaz, moyenne de profondeur comme Shearwater), fenêtre `ShearwaterBleDialog.kt` (recherche,
sommaire, nouvelles plongées cochées, téléchargement avec jauge, erreurs en haut, « Recommencer »), vérifié sur un Peregrine TX V104 Classic.
Détails dans le README, section « Téléchargement Bluetooth d'un Shearwater ».
