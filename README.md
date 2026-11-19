# Alias Outlook (APK debug)

App Android con dos pestañas:

- **Outlook**: abre tu correo de Outlook en un WebView dentro de la app
  (con botón atrás, recargar, subir archivos adjuntos y abrir en navegador).
- **Alias**: generador de alias reales para Gmail (`tucorreo+imss@gmail.com`)
  e ideas de alias para Outlook, con historial guardado en el teléfono.
  Sirven para registrarte en el IMSS, bancos y gobierno.

Se compila con GitHub Actions (`./gradlew assembleDebug`).

## Cómo compilar el APK

1. Haz `git push` a `main` (o abre Actions → **Build APK (debug)** → **Run workflow**).
2. Espera a que el job termine en verde (~3-6 min la primera vez).
3. Descarga el APK desde **Artifacts** → `app-debug` (es un ZIP, descomprímelo).

## Cómo instalarlo en Android

1. Copia el archivo `app-debug.apk` a tu móvil (USB, Drive, Telegram, etc.).
2. Ábrelo con el gestor de archivos y pulsa **Instalar**.
3. Si Android lo pide, permite **instalar apps desconocidas** para esa fuente.
4. Abre la app **Alias Outlook**.

El APK está firmado con la clave de depuración (solo para pruebas, no para
publicar en Play Store).

## Notas

- El inicio de sesión de Outlook ocurre en el sitio oficial de Microsoft
  dentro del WebView; la app nunca ve ni guarda tu contraseña.
- El generador de alias funciona sin internet; todo se guarda solo en tu teléfono.
