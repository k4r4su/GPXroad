# GPXroad

> « Le but de l'app c'est d'afficher une trace de façon simple, pouvoir la suivre, la reprendre plus loin si besoin. »

GPXroad est une application iOS (et bientôt Android) pour suivre une trace GPX en moto, à vélo ou à pied — sur route comme hors-piste. Pas de compte, pas de cloud, pas de fonctionnalités superflues : tu charges une trace, tu la suis, et si tu t'arrêtes en chemin tu la reprends là où tu en étais. Tout fonctionne hors-ligne une fois la carte téléchargée.

## Fonctionnalités principales

**Suivre une trace**
- Carte en mode cap-en-haut (comme un GPS moto) ou nord-en-haut, au choix
- Zoom qui s'adapte automatiquement à la vitesse — plus serré à l'arrêt, plus large en roulant
- Roadbook : une bannière annonce les virages à l'avance, avec une icône selon leur intensité (léger, prononcé, fort, très serré) — « demi-tour » seulement quand on repart vraiment sur la même route, jamais pour une épingle
- Détection hors-trace : un indicateur compact et discret te le signale sans jamais masquer la carte ni effacer ta trace
- Si tu t'écartes franchement, l'app recalcule seule un itinéraire de liaison pour te ramener sur la trace, avec une bannière dédiée qui indique la distance restante
- « Reprendre ici » : tu peux reprendre le guidage depuis n'importe quel point de la trace, même après un détour
- Hors trace, la bannière « Rejoindre la trace » t'y ramène par la route, sans jamais te renvoyer en arrière
- Textes et symboles de la carte restent lisibles en mode cap-en-haut, quel que soit ton cap
- Avertissement de pente : un panneau triangle apparaît sur la carte aux endroits de forte montée ou descente

**Road Book**
- Un onglet qui présente la trace comme un roadbook papier de rallye : liste des directions, ou mode « assisté GPS » avec le prochain virage en grand et la suite en dessous
- Uniquement des repères visibles depuis la route : panneaux, ponts, églises, stations-service, entrées de village… choisis catégorie par catégorie dans les réglages
- Le prochain élément affiché est toujours le plus proche, virage ou repère
- Ronds-points dessinés avec toutes leurs routes, numéro de sortie et route de sortie (→ D 419)
- Si tu t'écartes de la trace, le Road Book te guide par la route jusqu'à elle, virage par virage, vers le point le plus proche devant toi
- Un tap sur une étape montre l'endroit sur la carte, qui y reste jusqu'à « Me recentrer »
- Export PDF à imprimer

**Cartes hors-ligne**
- Téléchargement automatique du corridor autour d'une trace avant de partir
- Téléchargement manuel d'une zone plus large (avec estimation de taille en direct)
- Le contour des zones déjà téléchargées reste visible sur la carte
- Plusieurs palettes de couleur (standard, contraste élevé, terreux) sur le même fond de carte, plus un thème relief

**Une trace, jamais modifiée**
- Le fichier GPX chargé n'est jamais recalculé ni réécrit
- Le sens de parcours (A→B ou inversé) et l'apparence (couleur, épaisseur) sont des réglages d'affichage, pas des modifications du fichier
- Un détour ou un guidage vers un point tapé sur la carte se dessine à côté de la trace, jamais à sa place

**Enregistrer sa sortie**
- Au démarrage du suivi, l'app te propose d'enregistrer la sortie ; tu peux aussi démarrer, mettre en pause ou reprendre d'un bouton
- L'enregistrement continue dans les autres onglets, écran verrouillé ou dans une autre app, et survit à une fermeture accidentelle
- Export GPX en fin de sortie, sauvegardé automatiquement dans la bibliothèque avec une couleur ambre distinctive et un aperçu carte immédiat
- Points d'intérêt signalables en un tap pendant le trajet

**Bibliothèque**
- Toutes les traces importées ou enregistrées, triées par date et rangées dans des dossiers si tu le souhaites
- Aperçu cartographique par trace avec chevrons de direction et repères de départ/arrivée
- Réglages indépendants par trace (sens, couleur, épaisseur, espacement des chevrons)
- Partage et export GPX (une trace à la fois) via le partage système iOS standard

**Langues et aide**
- Français, anglais, allemand, espagnol et italien — automatique selon la langue du téléphone, ou au choix dans les réglages
- Un tutoriel intégré, une page par onglet, consultable hors-ligne

## Comment ça s'utilise

1. **Importer une trace** — depuis Fichiers, Mail, Safari ou directement dans l'app (onglet Bibliothèque)
2. **La rendre active** — un tap sur la trace dans la Bibliothèque
3. **Partir** — onglet Ride, la carte se centre et suit ta position ; accepte l'enregistrement de la sortie si tu veux la garder
4. **Suivre le roadbook** — la bannière latérale annonce les virages, les épingles sur la carte indiquent leur intensité
5. **S'arrêter si besoin** — le bouton Pause coupe le guidage sans rien perdre ; un tap le relance
6. **Terminer** — bouton « Terminer la sortie » dans le panneau de vitesse, la trace parcourue est enregistrée et exportable

## Pourquoi hors-ligne d'abord

Beaucoup de sorties moto ou rando se font là où le réseau mobile ne suit pas. GPXroad télécharge les cartes à l'avance (autour de la trace, ou sur une zone choisie) pour que rien ne dépende d'une connexion pendant la sortie.

## Stack technique

- SwiftUI, iOS 16+
- [MapLibre Native](https://maplibre.org/) pour la carte (tuiles OSM ou fond vectoriel, hors-ligne)
- Logique partagée en [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) :
  Road Book (virages, ronds-points, repères, reprise de la trace), guidage « Aller à »,
  enregistrement, statistiques, détours, palettes de carte et cartes hors ligne sont calculés par
  le même code sur iPhone et Android
- Aucune autre dépendance tierce que MapLibre et la bibliothèque standard Kotlin

Le dépôt contient trois dossiers : `iosApp/` (l'app iPhone), `shared/` (le code commun) et
`androidApp/` (l'app Android, Jetpack Compose).

## Version Android (état au 01/10/2026)

Toutes les fonctionnalités de l'iPhone sont portées et vérifiées sur émulateur : Bibliothèque
(import, trace active, dossiers, fiche avec statistiques, apparence par trace, partage, carte hors
ligne), Ride (carte, virages, flash des 100 derniers mètres, hors trace, reprise automatique et
« Reprendre la trace ici », chevrons, pentes, mesures, enregistrement de la sortie, pause du guidage,
« Chemin bloqué », signalements partagés, thèmes de carte, réglages de caméra), « Aller à » (recherche,
Domicile/Travail, guidage détaillé Valhalla avec voix et limitation de vitesse, ou guidage simple),
Road Book (assisté GPS et liste, ronds-points, repères, palette jour/nuit, export PDF, élément montré
sur la carte), cartes hors ligne (couloir d'une trace ou zone choisie sur la carte), tutoriel, accueil
avec trace d'exemple, 5 langues au choix. L'interface a été entièrement refaite (thème, Réglages
groupés, Bibliothèque en cartes, boutons de carte à icônes).

Reste : le test terrain sur la tablette (version installée), puis les retouches qui en sortiront.

**Donner l'app à des testeurs Android** : `./gradlew :androidApp:assembleRelease` produit
`androidApp/build/outputs/apk/release/androidApp-release.apk` (arm64, Android 8 ou plus). Le
testeur l'ouvre, autorise « installer des apps inconnues » pour son navigateur ou son gestionnaire
de fichiers, et installe. Les mises à jour s'installent par-dessus tant que l'APK est signé avec la
même clé (aujourd'hui la clé de débogage ; changer de clé oblige à réinstaller).

**APK de test avec tes serveurs intégrés (privé)** : `./scripts/build-tester-apk.sh` demande les
identifiants Valhalla et Overpass (saisie masquée, jamais écrits dans un fichier ni dans le dépôt)
et produit `gpxroad-v<version> TEST android.apk`, ignoré par git. Les testeurs n'ont alors rien à
régler ; leurs propres réglages, s'ils en font, restent prioritaires. ⚠ Tout ce qui est dans un APK
s'extrait : à ne donner qu'à des proches de confiance, puis renouveler les mots de passe.

---

*Ce README suit les fonctionnalités clés de l'app au fil des itérations — voir `CLAUDE.md` pour le détail technique et `TODO.md` pour l'historique des itérations.*
