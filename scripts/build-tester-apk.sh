#!/bin/bash
# Construit l'APK Android de test AVEC les serveurs Valhalla et Overpass intégrés (usage PRIVÉ).
#
# Les identifiants ne sont jamais écrits dans un fichier : ils sont demandés ici (saisie masquée) ou lus
# dans l'environnement, passés au build par une variable d'environnement, puis oubliés. L'APK produit
# les contient (tout ce qui est dans un APK s'extrait) : ne le donne qu'à des testeurs de confiance,
# ne le publie nulle part, et renouvelle les mots de passe côté serveur après la phase de test.
#
# Lancer :  ./scripts/build-tester-apk.sh        (ou  bash scripts/build-tester-apk.sh)
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi   # lancé par zsh ou sh : on repasse sous bash
set -euo pipefail
cd "$(dirname "$0")/.."

VALHALLA_URL="${GPXROAD_VALHALLA_URL:-https://valhalla.zim.ovh}"
OVERPASS_URL="${GPXROAD_OVERPASS_URL:-https://overpass.zim.ovh/api/interpreter}"

# ask VARIABLE "Question" [secret] : lit la valeur au clavier (terminal), ou garde celle de l'environnement.
ask() {
  local name="$1" prompt="$2" secret="${3:-}" value="${!1:-}"
  if [ -z "$value" ]; then
    if [ ! -r /dev/tty ]; then echo "Erreur : pas de terminal pour saisir « $prompt »." >&2; exit 1; fi
    printf '%s : ' "$prompt" > /dev/tty
    if [ -n "$secret" ]; then IFS= read -r -s value < /dev/tty; printf '\n' > /dev/tty; else IFS= read -r value < /dev/tty; fi
  fi
  if [ -z "$value" ]; then echo "Erreur : « $prompt » est vide." >&2; exit 1; fi
  printf -v "$name" '%s' "$value"
  export "$name"   # indispensable : le programme qui prépare la valeur lit l'environnement
}
ask GPXROAD_VALHALLA_USER "Valhalla — identifiant"
ask GPXROAD_VALHALLA_PASS "Valhalla — mot de passe" secret
ask GPXROAD_OVERPASS_USER "Overpass — identifiant"
ask GPXROAD_OVERPASS_PASS "Overpass — mot de passe" secret

BUNDLED="$(VU="$VALHALLA_URL" OU="$OVERPASS_URL" python3 -c '
import base64, json, os
e = os.environ
print(base64.b64encode(json.dumps({
    "valhalla": {"url": e["VU"], "user": e["GPXROAD_VALHALLA_USER"], "pass": e["GPXROAD_VALHALLA_PASS"]},
    "overpass": {"url": e["OU"], "user": e["GPXROAD_OVERPASS_USER"], "pass": e["GPXROAD_OVERPASS_PASS"]},
}).encode()).decode())')"
if [ -z "$BUNDLED" ]; then echo "Erreur : la valeur à intégrer est vide, build annulé." >&2; exit 1; fi
export GPXROAD_BUNDLED_SERVERS="$BUNDLED"
unset BUNDLED GPXROAD_VALHALLA_USER GPXROAD_VALHALLA_PASS GPXROAD_OVERPASS_USER GPXROAD_OVERPASS_PASS

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
./gradlew -q :androidApp:assembleRelease

# Vérification : la valeur est-elle bien dans l'APK ? (seule la longueur est affichée, jamais le contenu)
CONFIG="$(find androidApp/build/generated/source/buildConfig/release -name BuildConfig.java | head -1)"
LEN="$(awk -F'"' '/BUNDLED_SERVERS/ {print length($2)}' "$CONFIG")"
if [ "${LEN:-0}" -lt 20 ]; then
  echo "ERREUR : les serveurs ne sont PAS dans l'APK (valeur de ${LEN:-0} caractères). Ne le diffuse pas." >&2
  exit 1
fi

VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' androidApp/build.gradle.kts | head -1)"
OUT="${GPXROAD_OUT:-gpxroad-v${VERSION} TEST android.apk}"
cp androidApp/build/outputs/apk/release/androidApp-release.apk "$OUT"
echo "OK : $OUT — serveurs intégrés vérifiés (${LEN} caractères)."
echo "Il contient tes accès : ne pas publier, ne pas committer (les .apk sont ignorés par git)."
