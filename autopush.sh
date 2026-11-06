#!/bin/sh
# Push en un paso de la app de diagnóstico.
# Uso: bash autopush.sh "mensaje del commit"
# Añade solo los archivos del proyecto (no un add general).
set -e
cd "$(dirname "$0")"
MSG="${1:-App Diagnóstico Teléfono}"
git add app/build.gradle \
  app/src/main/AndroidManifest.xml \
  app/src/main/res/values/strings.xml \
  app/src/main/res/values/themes.xml \
  app/src/main/java/com/example/diagnostico/MainActivity.kt \
  app/src/main/java/com/example/apktemplate/MainActivity.kt \
  settings.gradle \
  gradle.properties \
  .github/workflows/build-apk.yml \
  README.md
git commit -m "$MSG" || echo "Nada nuevo que commitear."
git push -u origin master
