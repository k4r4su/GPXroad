# Audit de portage Kotlin Multiplatform — GPXroad iOS (it32)

Inventaire des fichiers Swift de `iosApp/GPXroad/` (174 fichiers, état v0.0.32) en vue d'un
partage de la logique avec Android. Livrable du point 1 de la fiche it32, rédigé AVANT tout code
de portage.

Catégories :

- **A — portable tel quel** : logique pure (géométrie, règles, structures de données). Seules
  dépendances : `Foundation` pour les maths et `CLLocationCoordinate2D` comme simple couple
  lat/lon.
- **B — portable avec adaptation** : logique valide, mais mêlée à des types ou API Apple
  (`URLSession`, `Codable` + `FileManager`, `Date`/`UUID`, `UserDefaults`, `XMLParser`) ou à de
  l'état d'affichage (`ObservableObject`/`@Published`). Équivalents KMP : Ktor (réseau),
  kotlinx.serialization (JSON), kotlinx-datetime (dates), kotlin.uuid (UUID), kotlinx-io/okio
  (fichiers), multiplatform-settings (préférences).
- **C — reste natif** : SwiftUI, MapLibre, UIKit, capteurs, permissions, trousseau, voix,
  export PDF, textes localisés.

Marqueur **⚠︎ refactor** : la logique est mêlée à l'état d'affichage. Il faut d'abord la séparer
(type pur + `ObservableObject` mince), dans une itération dédiée et AVANT le portage, jamais
pendant.

## Constats transverses (à régler une fois pour toutes)

1. **Distance géodésique : `CLLocation.distance(from:)` n'est pas reproductible.** Toute la
   géométrie de trace passe par `RoadbookAnalyzer.distanceMeters` (distances cumulées, fusion,
   ralliement). Ce qu'on a mesuré à it32 :
   - ce n'est ni une sphère de rayon fixe, ni Vincenty, ni la formule du rayon de courbure
     moyen. L'écart avec Vincenty est de 4·10⁻⁸ m sur 18 m, mais de 11,6 m sur 18 km ;
   - ce n'est même PAS déterministe dans le simulateur : une même paire de points donne deux
     résultats selon le moment (3,44308 m puis 3,44367 m), jusqu'à 1,6·10⁻⁴ d'écart relatif
     sur un segment court.

   Décision du pilote :
   - `expect fun geodesicDistanceMeters` ;
   - `actual` iOS = `CLLocation` via l'interop Kotlin/Native → Road Book identique à celui
     validé (it28) ;
   - `actual` Android = Vincenty WGS84.

   **Tranché à it33** : Vincenty sur iOS ET Android (« une seule formule, se faire moins chier à
   développer les deux versions »). Effet mesuré sur 15 traces réelles × 2 sens avec les réglages
   par défaut : 1 virage sur 7 722 ancré 8 points GPX plus tôt, distances ±2,5 m.
2. **`CLLocationCoordinate2D` partout** (≈ 60 fichiers). En commun : un type `LatLon`. La
   conversion se fait à la frontière Swift, pas dans la logique.
3. **Libellés localisés dans des types de logique** (`TurnDirection.label`,
   `RoadbookTier.label`, catalogue de repères) et **noms de SF Symbols**
   (`RoadbookTier.systemImageName`). Ils restent côté Swift, en extensions. Le module partagé
   expose des énumérations, jamais du texte affiché. Les traductions it31 restent natives :
   `Localizable.strings` d'un côté, `strings.xml` de l'autre, mêmes clés.
4. **Constantes** : `NavigationConstants` est pure (A). `RideConstants`,
   `MapEngineConstants`, `OfflineConstants` et `RoadBookConstants` mélangent seuils métier et
   types UI (`MLN…`, `Color`, `CGFloat`). Il faudra les scinder : les seuils partent en
   commun, le visuel reste natif.
5. **Formats persistés** : caches JSON (`RoadbookMapMatchCache`,
   `RoadbookLandmarkDataCache`, `Tracks/index.json`, `folders.json`, journal
   d'enregistrement). Un portage doit relire les fichiers existants octet pour octet :
   mêmes noms de champs, dates au même format que `JSONEncoder` par défaut (secondes depuis
   2001). Tests de relecture de fichiers réels obligatoires avant bascule.
6. **Identifiants** : `Checkpoint.id` est un UUID déterministe dérivé de la position.
   L'algorithme est simple et portable, mais c'est un invariant (fix it25) à couvrir par un test
   de parité dédié.

## Inventaire

Taille = lignes. Les vues SwiftUI pures (≈ 75 fichiers, noms en `*View`, `*Sheet`, `*Button`,
`*Pill`, `*Banner`…) sont toutes en **C** et regroupées en fin de document.

### Ride / Road Book — cœur géométrique

| Fichier | Lignes | Cat. | Dépendances problématiques / note |
|---|---|---|---|
| Ride/RoadbookAnalyzer.swift | 489 | **A** | `CLLocation` pour la distance (constat 1). Partie géométrique = **pilote it32**. La fusion des manœuvres Valhalla est pure elle aussi (portable ensuite). |
| Ride/TrackProjector.swift | 207 | **A** | Idem distance. Projection équirectangulaire pure. Partiellement dans le pilote. |
| Ride/RoadbookTier.swift | 122 | **A** | Énumération + rotations = pures. `label` et `systemImageName` restent Swift (constat 3). |
| Ride/Checkpoint.swift | 120 | **A** | UUID déterministe (constat 6). `label` et `systemImageName` restent Swift. |
| Config/NavigationConstants.swift | 106 | **A** | Seuils du Road Book. |
| RoadBook/RoadbookExtractor.swift | 71 | **A** | Chaîne Analyzer → manœuvres ordonnées, sens de parcours. |
| RoadBook/RoadbookManeuver.swift | 26 | **A** | `UUID` → kotlin.uuid. |
| RoadBook/RoadbookLiveProgress.swift | 73 | **A** | Manœuvre à venir en mode Assisté. |
| RoadBook/RoadbookOffTrack.swift | 41 | **A** | `Date` pour l'horodatage → Instant. |
| Ride/OffTrackDetector.swift | 15 | **A** | Hystérésis hors-trace (règle unique it30). |
| Ride/GuidanceTarget.swift | 28 | **A** | |
| Ride/ResumeGuidance.swift | 41 | **A** | `Date` → Instant. |
| Ride/RideCameraFollowPolicy.swift | 41 | **A** | |
| Ride/DirectionChevronComputer.swift | 89 | **A** | `CGPoint` → type local. |
| RoadBook/RoadbookPictogramGeometry.swift | 32 | **A** | Géométrie des pictogrammes (le dessin reste natif). |
| RoadBook/RoadbookCityEntries.swift | 188 | **A** | Distance (constat 1). Libellés en clés FR (it31) : l'affichage reste natif. |
| RoadBook/RoadbookVisibleLandmarks.swift | 361 | **A** | Sélecteur de repères + fusion Road Book. `Codable` → kotlinx.serialization (format du cache, constat 5). |
| RoadBook/RoadbookLandmark.swift | 312 | **B** | Catalogue et classification OSM purs, mais libellés et emoji localisés mélangés : séparer le catalogue (commun) des libellés (natifs). |
| RoadBook/RoadbookDownloadMeter.swift | 104 | **A** | `Date` → Instant. |
| RoadBook/DistanceUnit.swift, RoadbookReadingMode.swift | 30, 31 | **A** | Énumérations de réglages. |
| RoadBook/RoadBookConstants.swift | 220 | **B** | Seuils + `CGFloat` de mise en page PDF (constat 4). |
| RoadBook/RoadbookPalette.swift | 141 | **B** | Résolution jour/nuit (calcul solaire) pure ; `Color` natif. |
| RoadBook/RoadbookDebugDump.swift | 55 | **C** | `os.Logger`. Le formatage des lignes pourrait être commun. |
| Nav/ValhallaManeuverType.swift | 155 | **A** | Énumération Valhalla + règles `roadbookTier`/`roadbookDirection`. |
| Rendering/SlopeAnalyzer.swift | 72 | **A** | Distance (constat 1). |
| Rendering/TrackMetricsCalculator.swift | 107 | **A** | Distance. |
| Rendering/TrackThumbnailGeometry.swift | 89 | **A** | `CGPoint` → type local. |

### Modèles, bibliothèque, enregistrement

| Fichier | Lignes | Cat. | Dépendances problématiques / note |
|---|---|---|---|
| Models/GPXPoint.swift | 22 | **B** | `UUID`, `Date`, `Codable` (format de `index.json`). |
| Models/GPXTrack.swift | 123 | **B** | Idem ; `reordered()` pur (Trace sacrée) ; `traversalKey`. |
| Services/GPXParser.swift | 132 | **B** | `XMLParser` (Apple) → parseur XML KMP (xmlutil) ou lecteur maison. Dates ISO-8601. |
| Recording/GPXExporter.swift | 51 | **B** | Formatage des dates ; génération XML pure. |
| Services/LibraryStore.swift | 308 | **B ⚠︎ refactor** | `ObservableObject` + `FileManager` + `UserDefaults`. Invariants it10/it19 (trace active unique, réconciliation des ids orphelins) à extraire dans un type pur. |
| Services/LibraryFolders.swift | 108 | **B** | Logique des dossiers pure ; persistance `folders.json`. |
| Services/LibraryConstants.swift | 20 | **A** | |
| Views/TrackActivation.swift | 107 | **B** | `TrackActivationPolicy` pure (A). Le modificateur de vue reste natif. |
| Recording/RideRecorder.swift | 373 | **B ⚠︎ refactor** | Journal JSONL + règles d'état = portables. `CLLocationManager` en arrière-plan = C (service natif de chaque côté). |
| Recording/RecordingPromptPolicy.swift | 26 | **A** | |
| Recording/RecordingConstants.swift | 61 | **A** | |
| Recording/UnsavedRideStore.swift | 105 | **B ⚠︎ refactor** | `ObservableObject` + fichiers. |
| Waypoints/RollingWaypoint.swift | 77 | **B ⚠︎ refactor** | Idem. |
| Waypoints/WaypointCategory.swift, WaypointConstants.swift, Nav/WaypointCategoryExtensions.swift | 48 | **A** | |
| Ride/TrackRideSettings.swift | 56 | **B ⚠︎ refactor** | Réglages par trace, fichier JSON. |
| Ride/BlockageLogStore.swift | 64 | **B** | Fichier + `Codable`. |

### Réseau et routage (hors périmètre it32 — itération ultérieure)

| Fichier | Lignes | Cat. | Dépendances problématiques / note |
|---|---|---|---|
| Ride/ValhallaRoutingService.swift | 196 | **B** | `URLSession` → Ktor. Authentification Basic lue du trousseau (C) : identifiants injectés. |
| Ride/ValhallaMapMatchingService.swift | 222 | **B** | Idem. Décodage de `/trace_route` et `intermediateManeuvers` purs = portables en premier. |
| Nav/ValhallaNavigationService.swift | 189 | **B** | Idem. |
| Ride/RoutingProvider.swift | 92 | **B** | `RoutingProviderResolver` pur (A) ; fournisseurs OSRM/Valhalla = Ktor. |
| Ride/DetourRoutingService.swift | 135 | **B** | OSRM public, `URLSession`. |
| Nav/NavRoute.swift | 160 | **B** | Modèles purs + client `URLSession`. |
| Nav/NominatimGeocodingService.swift | 155 | **B** | `URLSession`, User-Agent, 1 requête/s. |
| Nav/SpeedLimitService.swift, TrafficService.swift | 82 | **B** | Overpass / stub. |
| RoadBook/RoadbookLandmarkOverpassService.swift | 316 | **B** | Construction de requête + `parse` = purs (A) ; transport = Ktor. |
| RoadBook/RoadbookLandmarkLoader.swift | 341 | **B ⚠︎ refactor** | Orchestration par tronçons, cache, progression + `@Published`. |
| RoadBook/RoadbookLandmarkDataCache.swift, Ride/RoadbookMapMatchCache.swift | 170 | **B** | Caches disque (constat 5). |
| Ride/RoutingActivityMonitor.swift | 42 | **B** | `ObservableObject` mince. |
| Sync/SharedBlockage.swift | 120 | **B** | `Codable` + `Date` (format du serveur). |
| Sync/SharedBlockageSyncService.swift | 81 | **B** | `URLSession`. |
| Sync/SharedBlockageSyncCoordinator.swift | 114 | **B ⚠︎ refactor** | Planification + `UserDefaults` + `@Published`. |
| Sync/SharedBlockageStore.swift, SharedBlockageConstants.swift | 51 | **B** / **A** | |
| Sync/AnonymousReporterID.swift | 23 | **B** | `UserDefaults` → multiplatform-settings. |

### Hors-ligne et carte

| Fichier | Lignes | Cat. | Dépendances problématiques / note |
|---|---|---|---|
| Offline/TileCoordinate.swift | 72 | **A** | Maths de tuiles. |
| Offline/CorridorPrecacheEstimator.swift, OfflineTileEstimator.swift | 90 | **A** | |
| Offline/TileSource.swift | 81 | **A** | |
| Offline/DownloadedRegion.swift | 101 | **B ⚠︎ refactor** | Store + fichier. |
| Offline/TileCacheStore.swift | 83 | **B** | `FileManager`. |
| Offline/TileDownloadQueue.swift | 108 | **B ⚠︎ refactor** | `URLSession` + `@Published`. |
| Offline/VectorPackageStore.swift | 161 | **B ⚠︎ refactor** | Téléchargement, `UserDefaults`, fichiers. |
| Offline/TileCacheURLProtocol.swift | 92 | **C** | `URLProtocol` + MapLibre. |
| Map/MapSourceResolver.swift, MapSourceSelection.swift | 68 | **A** | Résolution pure (existence de fichier injectée). |
| Map/MapColorFlavor.swift | 113 | **A** | Palettes. |
| Map/ColorFlavorPatcher.swift | 160 | **B** | `[String: Any]` → `JsonElement`. |
| Map/MapEngineConstants.swift, MapLibreBootstrap.swift, MapProvider.swift, RideMapLibreView.swift, MapLoadStatus.swift | — | **C** | MapLibre natif (MapLibre Android existe mais s'intègre différemment). |

### Réglages

| Fichier | Lignes | Cat. | Dépendances problématiques / note |
|---|---|---|---|
| Settings/RideSettingsStore.swift | 430 | **C** | `UserDefaults` + `@Published`, 40+ réglages. Les valeurs par défaut et bornes pourraient devenir communes plus tard. |
| Settings/SpeedUnit.swift, ControlsSide.swift, MapThemePreset.swift, Ride/ZoomPreset.swift | 107 | **A** | Énumérations (`ZoomPreset` lit `UserDefaults` : l'isoler). |
| Ride/ValhallaKeychainStore.swift | 72 | **C** | Trousseau iOS (Keystore côté Android). |
| RoadBook/RoadbookPDFOptions.swift | 55 | **B** | Options pures, `CGFloat`. |

### Orchestration Ride

| Fichier | Lignes | Cat. | Dépendances problématiques / note |
|---|---|---|---|
| Ride/RideSessionManager.swift | 1574 | **B ⚠︎ refactor lourd** | Machine d'état GPS / Road Book / détour / reprise, mêlée à `@Published`, `UIApplication`, `Task`. À découper en plusieurs types purs (état, transitions, lissage de vitesse, zoom auto) avant tout portage. Dernier de la liste. |
| Ride/RideConstants.swift | 389 | **B** | Seuils (communs) + MapLibre/SwiftUI (natifs) : scinder (constat 4). |
| Nav/GoToGuidance.swift | 65 | **A** | |
| Nav/RideMode.swift | 22 | **B** | Énumération A + store `ObservableObject`. |
| Nav/NavFavoritesStore.swift, NavSearchHistoryStore.swift | 138 | **B ⚠︎ refactor** | Stores + fichiers. |
| Nav/NavConstants.swift | 65 | **A** | |
| Ride/DebugReplayDriver.swift | 108 | **C** | Outil de debug (timers, `@Published`). |

### Reste natif (C)

- **Application** : App/* (`GPXroadApp`, `AppNavigationState`, `AppLanguage`, `L10n`, `AppVersion`,
  `SplashScreenView`).
- **Toutes les vues SwiftUI** : Views/*, Ride/*View*, RoadBook/*View*, Settings/*View*,
  Offline/*View*, Nav/*View*, Onboarding, Tutorial/TutorialView. S'y ajoutent
  `Ride/RideOverlayLayout`, `Views/RidePanelStyle`, `Rendering/TraceAppearance`,
  `RoadBook/RoadbookPictograms` et `RoadBook/RoadbookFocusedView`.
- **Plateforme** : `Services/LocationManager` (CoreLocation), `Services/NetworkMonitor` (Network),
  `Services/IdleTimerCoordinator` et `Views/TabBarAppearance` (UIKit),
  `Nav/NavVoiceAnnouncer` (AVSpeech), `RoadBook/RoadbookPDFExporter` (UIKit PDF).
- **Contenu texte** : `Tutorial/TutorialContent` et `Offline/OfflineExplainerText` (textes
  localisés — traduits nativement).

## Bilan

| Catégorie | Fichiers | ≈ Lignes |
|---|---|---|
| A — tel quel | ~45 | ~3 500 |
| B — avec adaptation (dont ⚠︎ refactor) | ~45 (13) | ~6 000 (dont 1 574 pour `RideSessionManager`) |
| C — natif | ~85 | ~12 000 |

La logique métier réellement partageable représente environ 40 % du code. Elle est concentrée
dans le Road Book, le routage et les formats de fichiers, c'est-à-dire ce qu'on ne veut surtout
pas voir diverger entre les deux apps.

## Résultat du pilote (it32)

**Verdict : approche validée.** La chaîne de build fonctionne de bout en bout, la parité est
atteinte, et le coût est faible. Kotlin Multiplatform reste l'approche retenue pour la suite.

- **Chaîne de build** :
  - Kotlin → `GPXroadShared.xcframework` (statique) → Swift : `RoadbookAnalyzer` l'utilise par
    défaut, `print(greeting())` au lancement ;
  - Kotlin → `androidApp` (Compose) : APK de 11,5 Mo ;
  - tests Kotlin verts sur simulateur iOS ET JVM Android (21 tests, dont 20 portés des tests
    Swift).
- **Parité** (`SharedRoadbookParityTests`, Swift natif vs Kotlin) :
  - 400 traces aléatoires, 15 traces réelles du propriétaire × 2 sens × 3 jeux de réglages,
    les géométries des tests du Road Book ;
  - 39 861 événements structurellement identiques (nombre, palier, sens, index, coordonnée) ;
  - écarts max : 0,008° d'angle et 1,9·10⁻⁴ de distance relative. Ils viennent du
    non-déterminisme de `CLLocation` (constat 1) et de `sin`/`cos` fusionnés en
    `__sincos_stret` (un ulp d'écart, comme Swift `-O`) ;
  - la suite iOS complète (495 tests, dont le garde-fou du jalon it28) passe avec le moteur
    Kotlin.
- **Coût** :
  - framework de 1,9 Mo par architecture avant élagage ;
  - 20 000 points : Kotlin 29-37 ms contre 65 ms en Swift Debug ;
  - build iOS sans changement Kotlin : +0,1 s ;
  - après une modification Kotlin : 11 s de reconstruction du framework ;
  - premier build d'un clone neuf : plusieurs minutes (téléchargement de Gradle et de
    Kotlin/Native, ~2 Go).
- **Pièges rencontrés, corrigés et documentés** (CLAUDE.md, section Monorepo) :
  - Xcode liait l'ancien framework dans le build même qui le reconstruisait → sorties du
    script déclarées ;
  - `generic/platform=iOS Simulator` compile aussi x86_64, que le framework n'a pas ;
  - le BOM Compose 2026.09 impose compileSdk 37.
- **Reste à valider sur le terrain** : Road Book d'une vraie sortie avec le moteur Kotlin (défaut)
  puis le Swift natif (Réglages > Avancé), comparés. L'implémentation Swift n'est supprimée
  qu'après cette validation.

## Ordre de portage suggéré

1. **Pilote (it32, FAIT — voir ci-dessus)** : géométrie du Road Book. `RoadbookAnalyzer` sans la fusion Valhalla, plus
   `TrackProjector` (distances cumulées, interpolation, projection), `TierThresholds`/paliers et
   les seuils de `NavigationConstants`.
   Pourquoi ce choix : zéro réseau, zéro état, une seule dépendance Apple (la distance,
   constat 1) qui tranche d'entrée la question la plus risquée, et des tests Swift existants
   comme référence (`RoadbookInflectionTests` et les tests géométriques de
   `RoadbookCheckpointReliabilityTests`).
2. **Reste du Road Book pur (it33, FAIT)** : fusion Valhalla, `RoadbookExtractor`, `RoadbookLiveProgress`,
   `OffTrackDetector`/`RoadbookOffTrack`, `RoadbookCityEntries`, `RoadbookLandmarkSelector` +
   `RoadbookEntry.merge`, catalogue de repères sans libellés, `ValhallaManeuverType`.
   Garde-fou : `RoadbookStableRegressionTests` exécuté contre le module partagé.
   Fait à it33 : Road Book IDENTIQUE avant/après sur 15 traces réelles × 2 sens. La distance est
   désormais Vincenty sur iOS et Android (constat 1 tranché : une seule formule). It34 : analyse
   des ronds-points (`RoundaboutAnalyzer`) et paliers du compte à rebours (`DistanceCountdown`)
   écrits DIRECTEMENT en Kotlin ; décodage Overpass des ronds-points resté Swift (étape 4). Reste Swift :
   `TrackProjector`, chemin chaud du Ride, à porter avec l'étape 7.
3. **Modèles et formats** : `GPXPoint`/`GPXTrack` (kotlinx.serialization, relecture de
   `index.json` réels), `GPXParser`/`GPXExporter`.
4. **Décodage des réponses réseau** (pur) : Overpass `parse`, Valhalla `/trace_route` et
   `/route`, Nominatim, OSRM.
5. **Transport réseau** (Ktor) : Valhalla, Overpass, Nominatim, OSRM, serveur de points bloqués.
6. **Stores** (après refactor ⚠︎) : bibliothèque, dossiers, caches, réglages par trace, reprise
   d'enregistrement.
7. **`RideSessionManager`** : découpage en types purs, puis portage. Le plus coûteux, en
   dernier.

Rien en C n'est à porter. Android réimplémente sa couche UI (Compose), carte (MapLibre Android),
localisation (FusedLocation ou LocationManager, service de premier plan pour l'enregistrement),
voix (TextToSpeech), stockage sécurisé (Keystore) et PDF (`PdfDocument`).
