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
   **confirme** l'import, qui passe par l'aperçu habituel (`ShearwaterPlanDialog`).
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

## Autres modèles d'ordinateurs

Quand l'utilisateur demandera l'import Bluetooth d'un autre modèle (Mares Sirius, Perdix, Teric, etc.), **suivre ce même parcours** : une
nouvelle section de modèle sous « Importer depuis un ordinateur de plongée », avec Associer / Dissocier / import automatique. Ce que
chaque modèle apporte (le reste est commun) :

- le **filtre de recherche** (nom Bluetooth, service) et l'identification du modèle ;
- le **protocole** de lecture (sommaire des plongées, téléchargement) : pour les Shearwater, `ShearwaterBle.kt` ; les autres sont à écrire
  (voir libdivecomputer pour les protocoles connus) ;
- la **conversion** d'une plongée téléchargée en `ShearwaterDive` (voir `ShearwaterBleImport.toDive`), puis l'aperçu et la fusion habituels ;
- la détection des **plongées déjà dans le carnet**.

Commun à tous les modèles : stockage des associations, écran de réglages, détection, import avec confirmation ou automatique, jauge,
messages d'erreur, dissociation.

## Déjà en place (octobre à novembre 2026)

Couche Bluetooth Android (`AndroidBle.kt`, MTU demandée à 517), protocole Shearwater (`ShearwaterBle.kt`), conversion
(`ShearwaterBleImport.kt` : une bouteille par gaz, moyenne de profondeur comme Shearwater), fenêtre `ShearwaterBleDialog.kt` (recherche,
sommaire, nouvelles plongées cochées, téléchargement avec jauge, erreurs en haut, « Recommencer »), vérifié sur un Peregrine TX V104 Classic.
Détails dans le README, section « Téléchargement Bluetooth d'un Shearwater ».
