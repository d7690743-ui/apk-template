package com.nexos.installer

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var infoText: TextView
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var historyText: TextView
    private lateinit var iconView: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var installButton: Button
    private lateinit var retryButton: Button
    private lateinit var openButton: Button
    private lateinit var uninstallButton: Button
    private lateinit var shareButton: Button
    private lateinit var saveButton: Button

    private var pickedUri: Uri? = null
    private var stagedFile: File? = null
    private var apkPackage = ""
    private var apkVersion = ""
    private var apkLabel = ""
    private var fileName = ""
    private var fileSize = 0L
    private var lastInstalledPackage = ""
    private var awaitingUnknownSources = false

    private val pickApkCode = 1001
    private val unknownSourcesCode = 1002
    private val notifyPermCode = 1003
    private val installAction = "com.nexos.installer.INSTALL_STATUS"
    private val channelId = "nexos_install"
    private val notifyId = 1001

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            when (status) {
                PackageInstaller.STATUS_SUCCESS -> {
                    log("Estado: éxito")
                    statusText.text = "Instalación completada."
                    lastInstalledPackage = apkPackage
                    prefs().edit().putString("last_pkg", apkPackage).apply()
                    addHistory("OK")
                    updateButtons()
                    notify("Nexos Instalador", "Instalado: $apkLabel")
                    Toast.makeText(context, "APK instalado", Toast.LENGTH_LONG).show()
                }
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    log("Estado: esperando confirmación del usuario")
                    val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(confirm)
                    } else {
                        statusText.text = "No se pudo abrir la confirmación del sistema."
                    }
                }
                else -> {
                    log("Estado: fallo (${msg ?: "error $status"})")
                    statusText.text = "Falló la instalación: ${msg ?: "error $status"}"
                    addHistory("FALLO: ${msg ?: "error $status"}")
                    updateButtons()
                    notify("Nexos Instalador", "Falló: $fileName")
                    Toast.makeText(context, "Falló: ${msg ?: "error $status"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = prefs().getBoolean("dark", false)
        setTheme(if (dark) R.style.Theme_Nexos_Dark else R.style.Theme_Nexos_Light)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        infoText = findViewById(R.id.info)
        statusText = findViewById(R.id.status)
        logText = findViewById(R.id.log)
        historyText = findViewById(R.id.history)
        iconView = findViewById(R.id.icon)
        progressBar = findViewById(R.id.progress)
        installButton = findViewById(R.id.install)
        retryButton = findViewById(R.id.retry)
        openButton = findViewById(R.id.open)
        uninstallButton = findViewById(R.id.uninstall)
        shareButton = findViewById(R.id.share)
        saveButton = findViewById(R.id.save)
        logText.movementMethod = ScrollingMovementMethod()
        historyText.movementMethod = ScrollingMovementMethod()

        lastInstalledPackage = prefs().getString("last_pkg", "") ?: ""
        findViewById<Button>(R.id.pick).setOnClickListener { pickApk() }
        installButton.setOnClickListener { if (stagedFile != null) showConfirmDialog() }
        retryButton.setOnClickListener { if (stagedFile != null) showConfirmDialog() }
        openButton.setOnClickListener { openInstalled() }
        uninstallButton.setOnClickListener { uninstallLast() }
        shareButton.setOnClickListener { shareApk() }
        saveButton.setOnClickListener { saveCopy() }
        findViewById<Button>(R.id.theme).setOnClickListener { toggleTheme() }
        findViewById<Button>(R.id.about).setOnClickListener { showAbout() }

        val filter = IntentFilter(installAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
        createNotifyChannel()
        renderHistory()
        updateButtons()
        log("Lista. Elige un APK para empezar.")
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("nexos", MODE_PRIVATE)

    // ---------- Selección y preparación ----------

    private fun pickApk() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.android.package-archive"
        }
        @Suppress("DEPRECATION") // Sin AndroidX a propósito: plantilla mínima.
        startActivityForResult(intent, pickApkCode)
    }

    @Deprecated("Framework sin AndroidX a propósito: plantilla mínima")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickApkCode && resultCode == RESULT_OK) {
            data?.data?.let { prepareApk(it) }
        } else if (requestCode == unknownSourcesCode && awaitingUnknownSources) {
            awaitingUnknownSources = false
            val action = pendingAfterUnknownSources
            pendingAfterUnknownSources = null
            if (action != null) action() else log("Sin acción pendiente.")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == notifyPermCode) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            log(if (ok) "Notificaciones permitidas." else "Notificaciones denegadas (se omiten).")
        }
    }

    private fun prepareApk(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        pickedUri = uri
        resolveDisplayInfo(uri)
        statusText.text = "Leyendo $fileName..."
        setProgress(0, true)
        log("Elegido: $fileName (${formatSize(fileSize)})")
        Thread {
            try {
                val staged = File(cacheDir, "staged.apk")
                if (staged.exists()) staged.delete()
                contentResolver.openInputStream(uri)?.use { input ->
                    staged.outputStream().use { output ->
                        copyWithProgress(input, output, fileSize) { setProgress(it, true) }
                    }
                } ?: throw IOException("No se pudo leer el archivo")
                stagedFile = staged
                if (!readArchiveInfo(staged.absolutePath)) throw IOException("El archivo no es un APK válido")
                runOnUiThread {
                    setProgress(100, false)
                    renderApkInfo()
                    updateButtons()
                    log("APK válido: $apkLabel ($apkPackage) v$apkVersion")
                    showConfirmDialog()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setProgress(0, false)
                    statusText.text = "Error: ${e.message}"
                    log("Error preparando: ${e.message}")
                    updateButtons()
                }
            }
        }.start()
    }

    private fun resolveDisplayInfo(uri: Uri) {
        var name = "archivo.apk"
        var size = 0L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0) ?: name
                    size = c.getLong(1)
                }
            }
        } catch (_: Exception) {
        }
        fileName = name
        fileSize = size
    }

    private fun readArchiveInfo(path: String): Boolean {
        val pm = packageManager
        val info = pm.getPackageArchiveInfo(path, 0) ?: return false
        apkPackage = info.packageName ?: return false
        val vCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
        apkVersion = "${info.versionName ?: "?"} ($vCode)"
        val appInfo = info.applicationInfo ?: return false
        appInfo.sourceDir = path
        appInfo.publicSourceDir = path
        apkLabel = try {
            appInfo.loadLabel(pm).toString()
        } catch (_: Exception) {
            apkPackage
        }
        val icon = try {
            appInfo.loadIcon(pm)
        } catch (_: Exception) {
            null
        }
        runOnUiThread {
            if (icon != null) {
                iconView.setImageDrawable(icon)
                iconView.visibility = View.VISIBLE
            } else {
                iconView.visibility = View.GONE
            }
        }
        return true
    }

    private fun renderApkInfo() {
        infoText.text = "App: $apkLabel\nPaquete: $apkPackage\nVersión: $apkVersion\nArchivo: $fileName (${formatSize(fileSize)})"
        statusText.text = "APK listo para instalar."
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "tamaño desconocido"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.0f KB", kb)
        return String.format(Locale.getDefault(), "%.1f MB", kb / 1024.0)
    }

    // ---------- Confirmación e instalación ----------

    private fun showConfirmDialog() {
        if (stagedFile == null) return
        AlertDialog.Builder(this)
            .setTitle("Confirmar instalación")
            .setMessage("App: $apkLabel\nPaquete: $apkPackage\nVersión: $apkVersion\nArchivo: $fileName (${formatSize(fileSize)})\n\nAndroid pedirá confirmación final.")
            .setPositiveButton("Instalar") { _, _ -> ensureUnknownSourcesThen { runInstall() } }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun ensureUnknownSourcesThen(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            awaitingUnknownSources = true
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            @Suppress("DEPRECATION") // Sin AndroidX a propósito: plantilla mínima.
            startActivityForResult(intent, unknownSourcesCode)
            Toast.makeText(this, "Permite instalar apps desconocidas y vuelve", Toast.LENGTH_LONG).show()
            log("Esperando permiso de orígenes desconocidos...")
            pendingAfterUnknownSources = action
            return
        }
        action()
    }

    private var pendingAfterUnknownSources: (() -> Unit)? = null

    private fun runInstall() {
        val staged = stagedFile ?: return
        statusText.text = "Instalando $apkLabel..."
        setProgress(0, true)
        log("Creando sesión de instalación...")
        requestNotifyPermissionIfNeeded()
        Thread {
            try {
                runInstallSession(staged)
            } catch (e: Exception) {
                runOnUiThread {
                    setProgress(0, false)
                    statusText.text = "Error: ${e.message}"
                    log("Error instalando: ${e.message}")
                    addHistory("FALLO: ${e.message}")
                    updateButtons()
                }
            }
        }.start()
    }

    private fun runInstallSession(staged: File) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                staged.inputStream().use { input ->
                    session.openWrite("package", 0, staged.length()).use { output ->
                        copyWithProgress(input, output, staged.length()) { setProgress(it, true) }
                        session.fsync(output)
                    }
                }
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags = flags or PendingIntent.FLAG_MUTABLE
                }
                val broadcast = Intent(installAction).setPackage(packageName)
                val sender = PendingIntent.getBroadcast(this, sessionId, broadcast, flags).intentSender
                session.commit(sender)
                runOnUiThread { setProgress(100, false) }
                log("Sesión enviada, esperando al sistema...")
            }
        } catch (e: Exception) {
            try {
                installer.abandonSession(sessionId)
            } catch (_: Exception) {
            }
            throw e
        }
    }

    private fun copyWithProgress(input: InputStream, output: OutputStream, total: Long, onProgress: (Int) -> Unit) {
        val buffer = ByteArray(256 * 1024)
        var copied = 0L
        var last = -1
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            copied += n
            if (total > 0) {
                val pct = (copied * 100 / total).toInt().coerceAtMost(100)
                if (pct != last) {
                    last = pct
                    onProgress(pct)
                }
            }
        }
        output.flush()
    }

    private fun setProgress(pct: Int, visible: Boolean) {
        runOnUiThread {
            progressBar.visibility = if (visible) View.VISIBLE else View.GONE
            progressBar.progress = pct
        }
    }

    // ---------- Acciones post-instalación ----------

    private fun openInstalled() {
        val pkg = lastInstalledPackage
        if (pkg.isEmpty()) return
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            startActivity(launch)
        } else {
            Toast.makeText(this, "La app no tiene pantalla principal", Toast.LENGTH_SHORT).show()
        }
    }

    private fun uninstallLast() {
        val pkg = lastInstalledPackage
        if (pkg.isEmpty()) return
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
    }

    private fun shareApk() {
        val uri = pickedUri ?: return
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Compartir APK"))
    }

    private fun saveCopy() {
        val uri = pickedUri ?: return
        statusText.text = "Guardando copia..."
        Thread {
            try {
                val dest = copyToDownloads(uri, fileName.ifEmpty { "app.apk" })
                runOnUiThread {
                    statusText.text = "Copia guardada: $dest"
                    log("Copia guardada: $dest")
                    Toast.makeText(this, "Copia guardada", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "No se pudo guardar: ${e.message}"
                    log("Error guardando copia: ${e.message}")
                }
            }
        }.start()
    }

    private fun copyToDownloads(uri: Uri, name: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val dest = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("No se pudo crear el destino")
            contentResolver.openInputStream(uri)?.use { input ->
                contentResolver.openOutputStream(dest)?.use { output ->
                    input.copyTo(output)
                } ?: throw IOException("No se pudo escribir el destino")
            } ?: throw IOException("No se pudo leer el original")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(dest, values, null, null)
            return "Descargas/$name"
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw IOException("Sin almacenamiento externo")
            val out = File(dir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("No se pudo leer el original")
            return out.absolutePath
        }
    }

    // ---------- Tema y acerca de ----------

    private fun toggleTheme() {
        val dark = !prefs().getBoolean("dark", false)
        prefs().edit().putBoolean("dark", dark).apply()
        recreate()
    }

    private fun showAbout() {
        val info = try {
            val p = packageManager.getPackageInfo(packageName, 0)
            "Versión ${p.versionName} (${if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()})"
        } catch (_: Exception) {
            "Versión desconocida"
        }
        AlertDialog.Builder(this)
            .setTitle("Acerca de")
            .setMessage("Nexos Instalador\nMarca Nexos\n$info\nPaquete $packageName\n\nInstaladora por sesiones (estilo SAI), sin root.")
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun updateButtons() {
        val hasFile = stagedFile != null
        installButton.isEnabled = hasFile
        retryButton.isEnabled = hasFile
        shareButton.isEnabled = pickedUri != null
        saveButton.isEnabled = pickedUri != null
        val hasPkg = lastInstalledPackage.isNotEmpty()
        openButton.isEnabled = hasPkg
        uninstallButton.isEnabled = hasPkg
    }

    // ---------- Historial, log y notificaciones ----------

    private fun historyFile() = File(filesDir, "history.json")

    private fun addHistory(result: String) {
        try {
            val arr = try {
                JSONArray(historyFile().readText())
            } catch (_: Exception) {
                JSONArray()
            }
            val entry = JSONObject()
                .put("date", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
                .put("name", fileName.ifEmpty { "?" })
                .put("pkg", apkPackage.ifEmpty { "?" })
                .put("result", result)
            val capped = JSONArray().put(entry)
            for (i in 0 until minOf(arr.length(), 19)) capped.put(arr.get(i))
            historyFile().writeText(capped.toString())
            runOnUiThread { renderHistory() }
        } catch (e: Exception) {
            log("No se pudo guardar historial: ${e.message}")
        }
    }

    private fun renderHistory() {
        try {
            val arr = JSONArray(historyFile().readText())
            if (arr.length() == 0) {
                historyText.text = "Historial vacío."
                return
            }
            val lines = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val mark = if (o.optString("result") == "OK") "✓" else "✗"
                lines.append("$mark ${o.optString("date")} ${o.optString("name")} (${o.optString("pkg")}) ${o.optString("result")}\n")
            }
            historyText.text = lines.toString().trim()
        } catch (_: Exception) {
            historyText.text = "Historial vacío."
        }
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            logText.append("[$time] $msg\n")
            val layout = logText.layout
            if (layout != null) {
                val y = layout.getLineTop(logText.lineCount) - logText.height
                if (y > 0) logText.scrollTo(0, y)
            }
        }
    }

    private fun createNotifyChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(channelId, "Instalaciones", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun requestNotifyPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), notifyPermCode)
        }
    }

    private fun notify(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
        nm.notify(notifyId, builder.build())
    }
}
