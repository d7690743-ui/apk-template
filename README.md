# APK Template universal

Template para compilar APKs de depuración (debug) automáticamente con GitHub Actions.
Incluye un proyecto base **Android nativo mínimo** (Kotlin + Gradle + WebView) y un
workflow que detecta solo el tipo de app presente en el repo.

> Nota: este repo también contiene un ejemplo web con Flask (`app.py`,
> `notas.json`, `requirements.txt`) que no interfiere con la compilación del APK.

## Cómo compilar el APK

No necesitas Android Studio ni SDK en tu máquina. Todo se compila en la nube:

1. Inicializa el repo y súbelo a GitHub:
   ```bash
   git init
   git add .
   git commit -m "Template APK listo"
   git branch -M main
   git remote add origin https://github.com/TU_USUARIO/TU_REPO.git
   git push -u origin main
   ```
2. En GitHub abre la pestaña **Actions** → workflow **Build APK (debug)**.
   Cada `git push` lo lanza solo; también puedes lanzarlo a mano con
   **Run workflow**.
3. Espera a que el job termine en verde (~3-6 min la primera vez).
4. Descarga el APK desde **Artifacts** → `app-debug` (es un ZIP, descomprímelo).

## Cómo ver el APK en Android

1. Copia el archivo `app-debug.apk` a tu móvil (USB, Drive, Telegram, etc.).
2. Ábrelo con el gestor de archivos y pulsa **Instalar**.
3. Si Android lo pide, permite **instalar apps desconocidas** para esa fuente.
4. Abre la app **APK Template**: verás una pantalla con
   **"Hola Mundo APK - Template listo"**.

El APK está firmado con la clave de depuración (solo para pruebas, no para
publicar en Play Store).

## Estructura del template

```
.
├── .github/workflows/build-apk.yml   # Workflow universal (nativo/Flutter/Buildozer)
├── app/
│   ├── build.gradle                  # Módulo app (package com.example.apktemplate)
│   ├── proguard-rules.pro
│   └── src/main/
│         ├── AndroidManifest.xml
│         ├── java/com/example/apktemplate/MainActivity.kt  # WebView "Hola Mundo"
│         └── res/values/strings.xml
├── installer/                        # Nexos Instalador estilo SAI
│   └── src/main/
│         ├── AndroidManifest.xml     # Permiso REQUEST_INSTALL_PACKAGES
│         ├── java/com/nexos/installer/MainActivity.kt  # Sesión PackageInstaller
│         └── res/layout/activity_main.xml, res/values/strings.xml
├── build.gradle                      # Plugins raíz (AGP 8.5.2, Kotlin 1.9.24)
├── settings.gradle
├── gradle.properties
├── gradle/wrapper/                   # Wrapper oficial de Gradle 8.9
├── gradlew / gradlew.bat
└── README.md
```

## Cómo funciona lo "universal"

El workflow tiene 3 jobs y cada uno solo corre si detecta su proyecto:

| Job              | Se activa si existe | Qué hace                        |
|------------------|---------------------|---------------------------------|
| Android nativo   | `settings.gradle`   | `./gradlew assembleDebug`       |
| Flutter          | `pubspec.yaml`      | `flutter build apk --debug`     |
| Buildozer/Kivy   | `buildozer.spec`    | `buildozer android debug`       |

Para cambiar de tecnología, sustituye el contenido del repo por tu app
(Flutter, Kivy o nativa) y haz push: el job correcto se activa solo, sin
editar el workflow.

## Nexos Instalador (módulo `installer`)

App genérica estilo SAI para los móviles donde el instalador del sistema falla:
elige cualquier `.apk` con el selector de archivos y lo instala por
**sesión de `PackageInstaller`** (técnica "rootless", sin root).

- Package: `com.nexos.installer`. Su APK sale en el mismo artifact
  (`installer/build/outputs/apk/debug/*.apk`).
- Pide el permiso "instalar apps desconocidas" para sí misma la primera vez.
- Límites: Android siempre muestra la confirmación de instalación; no evita
  Play Protect ni conflictos de firma.

Funciones incluidas (15 mejoras):

1. Info del APK antes de instalar: nombre, icono, paquete, versión y tamaño.
2. Diálogo de confirmación con esa info (Instalar/Cancelar).
3. Barra de progreso al copiar e instalar.
4. Historial persistente de instalaciones (OK/fallo).
5. Botón Abrir la app instalada.
6. Botón Reintentar tras un fallo.
7. Permiso persistido del archivo elegido.
8. Botón Desinstalar la última app.
9. Botón Compartir el APK.
10. Botón Guardar copia en Descargas.
11. Notificación de resultado (éxito/fallo).
12. Registro de eventos en pantalla.
13. Nombre y tamaño del archivo elegido.
14. Tema claro/oscuro conmutable.
15. Diálogo Acerca de (marca Nexos, versión).

## Versiones fijadas

- Java 17 (Temurin), Gradle 8.9, AGP 8.5.2, Kotlin 1.9.24
- `compileSdk`/`targetSdk` 34, `minSdk` 24 (Android 7.0+)
