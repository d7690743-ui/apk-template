# Diagnóstico Teléfono (APK debug)

App Android que diagnostica por qué Chrome tarda en cargar: almacenamiento, RAM,
batería, CPU, red, datos de Chrome y tiempo de arranque. Se compila con
GitHub Actions (`./gradlew assembleDebug`).

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
4. Abre la app **Diagnóstico Teléfono**: verás las tarjetas de
   diagnóstico y el botón **Test rápido**.

El APK está firmado con la clave de depuración (solo para pruebas, no para
publicar en Play Store).

## Estructura del template

```
.
├── .github/workflows/build-apk.yml   # Compila la app nativa con Gradle
├── app/
│   ├── build.gradle                  # Módulo app (package com.example.diagnostico)
│   ├── proguard-rules.pro
│   └── src/main/
│         ├── AndroidManifest.xml     # Permisos + queries para Chrome
│         ├── java/com/example/diagnostico/MainActivity.kt  # Diagnóstico
│         └── res/values/strings.xml, themes.xml
├── installer/                        # Nexos Instalador estilo SAI
│   └── src/main/
│         ├── AndroidManifest.xml     # Permiso REQUEST_INSTALL_PACKAGES
│         ├── java/com/nexos/installer/MainActivity.kt  # Sesión PackageInstaller
│         └── res/layout/activity_main.xml, res/values/strings.xml
├── build.gradle                      # Plugins raíz (AGP 8.7.3, Kotlin 1.9.24)
├── settings.gradle
├── gradle.properties
├── gradle/wrapper/                   # Wrapper oficial de Gradle 8.9
├── gradlew / gradlew.bat
└── README.md
```

## Cómo funciona

El workflow tiene un solo job para Android nativo: detecta el proyecto por
`settings.gradle`, corre `./gradlew assembleDebug` y sube los APK de `app` e
`installer` como artifact `app-debug`.

## Nexos Instalador (módulo `installer`)

App genérica estilo SAI para los móviles donde el instalador del sistema falla:
elige cualquier `.apk` con el selector de archivos y lo instala por
**sesión de `PackageInstaller`** (técnica "rootless", sin root).

- Package: `com.nexos.installer`. Su APK sale en el mismo artifact
  (`installer/build/outputs/apk/debug/*.apk`).
- Pide el permiso "instalar apps desconocidas" para sí misma la primera vez.
- Límites: Android siempre muestra la confirmación de instalación; no evita
  Play Protect ni conflictos de firma.

Funciones incluidas:

1. Info del APK antes de instalar: nombre, icono, paquete, versión y tamaño.
2. Diálogo de confirmación con esa info (Instalar/Cancelar).
3. Barra de progreso al copiar e instalar, con botón Cancelar.
4. Historial persistente de instalaciones (OK/fallo) con detalle por entrada.
5. Reinstalar desde el historial (guarda copia del último APK por paquete).
6. Borrar entradas del historial y exportarlo como texto.
7. Botón Abrir la app instalada.
8. Botón Reintentar tras un fallo.
9. Permiso persistido del archivo elegido.
10. Botón Desinstalar la última app.
11. Botón Compartir el APK.
12. Botón Guardar copia en Descargas.
13. Notificación de resultado (éxito con acción Abrir / fallo).
14. Registro de eventos en pantalla, exportable (compartir/guardar/limpiar).
15. Nombre y tamaño del archivo elegido.
16. Tema claro/oscuro conmutable.
17. Diálogo Acerca de (marca Nexos, versión).
18. Abrir `.apk` tocado en el gestor de archivos u otra app (VIEW/SEND).
19. Comparativa con la app instalada: actualización, misma versión o downgrade.
20. Detección de conflicto de firma antes de instalar.
21. Chequeos previos: minSdk, CPU (ABIs), espacio libre y permisos peligrosos.
22. SHA-256 del APK y del firmante, con botón para copiar.
23. Soporte de paquetes split (`.apks`/`.xapk`/`.apkm`) en sesión multipaquete.

Notas:

- Los splits necesitan Android 9+ (API 29); en versiones anteriores se avisa.
- Los `.apks`/`.xapk` se eligen desde el botón "Elegir APK" de la app.
- Lógica pura en `ApkUtils` con test unitario (`ApkUtilsTest`, JUnit).

## Versiones fijadas

- Java 17 (Temurin), Gradle 8.9, AGP 8.7.3, Kotlin 1.9.24
- `compileSdk`/`targetSdk` 35, `minSdk` 24 (Android 7.0+)
