#!/bin/sh
# It32 — construit le framework Kotlin Multiplatform (shared/) consommé par l'app iOS, SEULEMENT
# si ses sources ont changé depuis le dernier build : le cycle iOS quotidien (shared/ inchangé)
# ne lance jamais Gradle. Appelé en pré-build par Xcode (project.yml) ; utilisable à la main.
set -e
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
XCF="$REPO/shared/build/XCFrameworks/release/GPXroadShared.xcframework"
STAMP="$XCF/.built"

if [ -f "$STAMP" ] && [ -z "$(find "$REPO/shared/src" "$REPO/shared/build.gradle.kts" "$REPO/gradle/libs.versions.toml" -newer "$STAMP" -print -quit)" ]; then
    exit 0
fi

# Xcode ne transmet pas l'environnement du shell : JDK d'Android Studio par défaut.
if [ -z "$JAVA_HOME" ]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
echo "shared/ a changé : build du framework Kotlin (GPXroadShared.xcframework)"
cd "$REPO"
./gradlew :shared:assembleGPXroadSharedReleaseXCFramework --console=plain -q
touch "$STAMP"
