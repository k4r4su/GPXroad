#!/bin/bash
# Construit l'APK Android de test AVEC les serveurs Valhalla et Overpass intégrés (usage PRIVÉ).
#
# Les identifiants ne sont jamais écrits dans un fichier : ils sont demandés ici (saisie masquée) ou lus
# dans l'environnement, passés au build par une variable d'environnement, puis oubliés. L'APK produit
# les contient (tout ce qui est dans un APK s'extrait) : ne le donne qu'à des testeurs de confiance,
# ne le publie nulle part, et renouvelle les mots de passe côté serveur après la phase de test.
set -euo pipefail
cd "$(dirname "$0")/.."

VALHALLA_URL="${GPXROAD_VALHALLA_URL:-https://valhalla.zim.ovh}"
OVERPASS_URL="${GPXROAD_OVERPASS_URL:-https://overpass.zim.ovh/api/interpreter}"
ask() { # ask VARIABLE "Question" [secret]
  local name="$1" prompt="$2" secret="${3:-}"
  if [ -z "${!name:-}" ]; then
    if [ -n "$secret" ]; then read -r -s -p "$prompt : " "$name"; echo; else read -r -p "$prompt : " "$name"; fi
  fi
}
ask GPXROAD_VALHALLA_USER "Valhalla — identifiant"
ask GPXROAD_VALHALLA_PASS "Valhalla — mot de passe" secret
ask GPXROAD_OVERPASS_USER "Overpass — identifiant"
ask GPXROAD_OVERPASS_PASS "Overpass — mot de passe" secret

export GPXROAD_BUNDLED_SERVERS="$(VU="$VALHALLA_URL" OU="$OVERPASS_URL" python3 -c '
import base64, json, os
e = os.environ
print(base64.b64encode(json.dumps({
    "valhalla": {"url": e["VU"], "user": e["GPXROAD_VALHALLA_USER"], "pass": e["GPXROAD_VALHALLA_PASS"]},
    "overpass": {"url": e["OU"], "user": e["GPXROAD_OVERPASS_USER"], "pass": e["GPXROAD_OVERPASS_PASS"]},
}).encode()).decode())')"
unset GPXROAD_VALHALLA_USER GPXROAD_VALHALLA_PASS GPXROAD_OVERPASS_USER GPXROAD_OVERPASS_PASS

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
./gradlew -q :androidApp:assembleRelease
OUT="GPXroad-android-serveurs-integres.apk"
cp androidApp/build/outputs/apk/release/androidApp-release.apk "$OUT"
echo "OK : $OUT (contient tes accès : ne pas publier, ne pas committer — les .apk sont ignorés par git)"
