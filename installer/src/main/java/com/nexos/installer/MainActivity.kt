package com.nexos.installer

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
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
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

/** Info de una app ya instalada en el móvil. */
internal data class InstalledInfo(
    val versionName: String,
    val versionCode: Long,
    val signers: List<String>
)

/** Una entrada del historial persistente. */
internal data class HistoryEntry(
    val date: String,
    val name: String,
    val pkg: String,
    val ver: String,
    val result: String,
    val kept: String
)

/** Cancelación solicitada con el botón Cancelar. */
private class CancelledException : IOException("Cancelado por el usuario")

class MainActivity : Activity() {

    private lateinit var infoText: TextView
    private lateinit var compareText: TextView
    private lateinit var checksText: TextView
    private lateinit var permsText: TextView
    private lateinit var hashText: TextView
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var historyText: TextView
    private lateinit var iconView: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var pickButton: Button
    private lateinit var installButton: Button
    private lateinit var retryButton: Button
    private lateinit var cancelButton: Button
    private lateinit var copyHashButton: Button
    private lateinit var openButton: Button
    private lateinit var uninstallButton: Button
    private lateinit var shareButton: Button
    private lateinit var saveButton: Button

    private var pickedUri: Uri? = null
    private var stagedFile: File? = null
    private var stagedApks: List<File> = emptyList()
    private var multiApk = false
    private var apkPackage = ""
    private var apkVersion = ""
    private var apkVersionCode = 0L
    private var apkLabel = ""
    private var fileName = ""
    private var fileSize = 0L
    private var apkSha256 = ""
    private var signerSha256 = ""
    private var archiveSigners: List<String> = emptyList()
    private var requestedPerms: Array<String>? = null
    private var installed: InstalledInfo? = null
    private var signatureMismatch = false
    private var relationText = ""
    private var checkLines: List<String> = emptyList()
    private var dangerousPerms: List<String> = emptyList()
    private var totalPerms = 0
    private var lastInstalledPackage = ""
    private var awaitingUnknownSources = false
    private var pendingAfterUnknownSources: (() -> Unit)? = null
    @Volatile private var cancelled = false
    @Volatile private var working = false
    private val logBuffer = StringBuilder()

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
                    updateButtons()
                    notifyDone("Instalado: $apkLabel")
                    Toast.makeText(context, "APK instalado", Toast.LENGTH_LONG).show()
                    Thread {
                        val kept = keepApkCopy()
                        addHistory("OK", kept)
                    }.start()
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
        compareText = findViewById(R.id.compare)
        checksText = findViewById(R.id.checks)
        permsText = findViewById(R.id.perms)
        hashText = findViewById(R.id.hashinfo)
        statusText = findViewById(R.id.status)
        logText = findViewById(R.id.log)
        historyText = findViewById(R.id.history)
        iconView = findViewById(R.id.icon)
        progressBar = findViewById(R.id.progress)
        pickButton = findViewById(R.id.pick)
        installButton = findViewById(R.id.install)
        retryButton = findViewById(R.id.retry)
        cancelButton = findViewById(R.id.cancel)
        copyHashButton = findViewById(R.id.copyhash)
        openButton = findViewById(R.id.open)
        uninstallButton = findViewById(R.id.uninstall)
        shareButton = findViewById(R.id.share)
        saveButton = findViewById(R.id.save)
        logText.movementMethod = ScrollingMovementMethod()
        historyText.movementMethod = ScrollingMovementMethod()

        lastInstalledPackage = prefs().getString("last_pkg", "") ?: ""
        pickButton.setOnClickListener { pickApk() }
        installButton.setOnClickListener { if (stagedFile != null) showConfirmDialog() }
        retryButton.setOnClickListener { if (stagedFile != null) showConfirmDialog() }
        cancelButton.setOnClickListener {
            if (working) {
                cancelled = true
                log("Cancelando...")
            }
        }
        copyHashButton.setOnClickListener { copyHash() }
        openButton.setOnClickListener { openInstalled() }
        uninstallButton.setOnClickListener { uninstallLast() }
        shareButton.setOnClickListener { shareApk() }
        saveButton.setOnClickListener { saveCopy() }
        findViewById<Button>(R.id.history_open).setOnClickListener { showHistoryDialog() }
        findViewById<Button>(R.id.history_export).setOnClickListener { exportHistory() }
        findViewById<Button>(R.id.history_clear).setOnClickListener { clearHistory() }
        findViewById<Button>(R.id.log_share).setOnClickListener { shareLog() }
        findViewById<Button>(R.id.log_save).setOnClickListener { saveLog() }
        findViewById<Button>(R.id.log_clear).setOnClickListener { clearLog() }
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
        if (savedInstanceState == null) handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleIncomingIntent(intent)
        }
    }

    override fun onDestroy() {
        cancelled = true
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("nexos", MODE_PRIVATE)

    /** APK recibido desde otra app (VIEW) o compartido (SEND). */
    private fun handleIncomingIntent(intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            log("APK recibido desde otra app.")
            prepareApk(uri)
        } else if (action == Intent.ACTION_SEND) {
            val uri = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            } ?: return
            log("APK compartido desde otra app.")
            prepareApk(uri)
        }
    }

    // ---------- Selección y preparación ----------

    private fun pickApk() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/vnd.android.package-archive",
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream"
                )
            )
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
        if (working) {
            Toast.makeText(this, "Espera a que termine la operación actual", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        val stream: InputStream
        try {
            stream = contentResolver.openInputStream(uri)
                ?: throw IOException("No se pudo leer el archivo")
        } catch (e: Exception) {
            statusText.text = "Error: ${e.message}"
            log("Error abriendo: ${e.message}")
            return
        }
        resolveDisplayInfo(uri)
        pickedUri = uri
        stageAndAnalyze(stream, fileSize, fileName)
    }

    private fun resolveDisplayInfo(uri: Uri) {
        if (uri.scheme == "file") {
            val f = uri.path?.let { File(it) }
            fileName = f?.name?.ifEmpty { "archivo.apk" } ?: "archivo.apk"
            fileSize = if (f != null && f.isFile) f.length() else 0L
            return
        }
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

    /** Copia a caché con progreso y cancelación, y analiza el APK (o paquete split). */
    private fun stageAndAnalyze(source: InputStream, total: Long, displayName: String) {
        working = true
        cancelled = false
        resetAnalysis()
        updateButtons()
        statusText.text = "Leyendo $displayName..."
        setProgress(0, true)
        log("Elegido: $displayName (${ApkUtils.formatSize(total)})")
        Thread {
            try {
                val staged = File(cacheDir, "staged.apk")
                if (staged.exists()) staged.delete()
                source.use { input ->
                    staged.outputStream().use { output ->
                        copyWithProgress(input, output, total) { setProgress(it, true) }
                    }
                }
                analyzeStaged(staged)
                runOnUiThread {
                    setProgress(100, false)
                    renderAnalysis()
                    updateButtons()
                    log("APK válido: $apkLabel ($apkPackage) v$apkVersion")
                    showConfirmDialog()
                }
            } catch (e: CancelledException) {
                runOnUiThread {
                    setProgress(0, false)
                    statusText.text = "Cancelado."
                    log("Operación cancelada.")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setProgress(0, false)
                    statusText.text = "Error: ${e.message}"
                    log("Error preparando: ${e.message}")
                }
            } finally {
                working = false
                runOnUiThread { updateButtons() }
            }
        }.start()
    }

    private fun resetAnalysis() {
        stagedFile = null
        stagedApks = emptyList()
        multiApk = false
        apkPackage = ""
        apkVersion = ""
        apkVersionCode = 0L
        apkLabel = ""
        apkSha256 = ""
        signerSha256 = ""
        archiveSigners = emptyList()
        requestedPerms = null
        installed = null
        signatureMismatch = false
        relationText = ""
        checkLines = emptyList()
        dangerousPerms = emptyList()
        totalPerms = 0
        iconView.visibility = View.GONE
        infoText.text = "Sin archivo elegido."
        compareText.visibility = View.GONE
        checksText.visibility = View.GONE
        permsText.visibility = View.GONE
        hashText.visibility = View.GONE
    }

    /** Hilo de fondo: distingue APK único de contenedor split y calcula hash, firma y chequeos. */
    private fun analyzeStaged(staged: File) {
        if (readArchiveInfo(staged.absolutePath)) {
            stagedFile = staged
            stagedApks = listOf(staged)
            multiApk = false
            fileSize = staged.length()
        } else {
            val parts = try {
                extractSplitApks(staged)
            } catch (_: Exception) {
                emptyList()
            }
            if (parts.isEmpty()) throw IOException("El archivo no es un APK válido ni un paquete split (.apks/.xapk/.apkm)")
            val ordered = ApkUtils.orderSplitCandidates(parts.map { SplitEntry(it.name, it.length()) })
            val byName = parts.associateBy { it.name }
            val valid = mutableListOf<File>()
            var main: File? = null
            var mainPkg = ""
            for (entry in ordered) {
                val f = byName[entry.name] ?: continue
                val pkg = archivePackage(f.absolutePath)
                if (pkg == null) {
                    log("Parte ignorada (no válida): ${entry.name}")
                    continue
                }
                if (main == null) {
                    main = f
                    mainPkg = pkg
                } else if (pkg != mainPkg) {
                    log("Parte ignorada (otro paquete): ${entry.name}")
                    continue
                }
                valid.add(f)
            }
            val base = main ?: throw IOException("El paquete split no contiene ningún APK válido")
            if (!readArchiveInfo(base.absolutePath)) throw IOException("No se pudo leer el APK base del paquete")
            if (Build.VERSION.SDK_INT < 29 && valid.size > 1) {
                throw IOException("Este paquete trae ${valid.size} APKs y tu Android (API ${Build.VERSION.SDK_INT}) no soporta instalación múltiple (requiere Android 9+)")
            }
            stagedFile = base
            stagedApks = valid
            multiApk = valid.size > 1
            fileSize = valid.sumOf { it.length() }
            if (valid.size < parts.size) log("Aviso: ${parts.size - valid.size} partes ignoradas.")
            log("Paquete split: ${valid.size} APKs (${ApkUtils.formatSize(fileSize)})")
        }
        val main = stagedFile ?: throw IOException("Sin archivo")
        apkSha256 = sha256Of(main)
        archiveSigners = archiveSignerDigests(main.absolutePath)
        signerSha256 = archiveSigners.firstOrNull() ?: ""
        loadComparison()
        runPreChecks(main)
    }

    /** Extrae los .apk de un contenedor split (.apks/.xapk/.apkm son ZIP). */
    private fun extractSplitApks(container: File): List<File> {
        val dir = File(cacheDir, "split")
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        val found = mutableListOf<File>()
        ZipFile(container).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements() && found.size < 50) {
                val e = entries.nextElement()
                if (!e.isDirectory && e.name.lowercase(Locale.US).endsWith(".apk")) {
                    val safe = e.name.substringAfterLast('/').ifEmpty { "part${found.size}.apk" }
                    val out = File(dir, safe)
                    zip.getInputStream(e).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    found.add(out)
                }
            }
        }
        return found
    }

    private fun archivePackage(path: String): String? {
        return try {
            packageManager.getPackageArchiveInfo(path, 0)?.packageName
        } catch (_: Exception) {
            null
        }
    }

    private fun readArchiveInfo(path: String): Boolean {
        val pm = packageManager
        val info = pm.getPackageArchiveInfo(path, PackageManager.GET_PERMISSIONS) ?: return false
        apkPackage = info.packageName ?: return false
        val vCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
        apkVersionCode = vCode
        apkVersion = "${info.versionName ?: "?"} ($vCode)"
        requestedPerms = try {
            info.requestedPermissions
        } catch (_: Exception) {
            null
        }
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

    /** Huellas SHA-256 de los certificados firmantes del APK (archivo). */
    private fun archiveSignerDigests(path: String): List<String> {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val info = packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
                info?.signingInfo?.apkContentsSigners?.map { ApkUtils.sha256HexOf(it.toByteArray()) }
                    ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                val info = packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info?.signatures?.map { ApkUtils.sha256HexOf(it.toByteArray()) } ?: emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Huellas SHA-256 de los certificados firmantes de la app instalada. */
    private fun installedSignerDigests(pkg: String): List<String> {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val info = packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                info?.signingInfo?.apkContentsSigners?.map { ApkUtils.sha256HexOf(it.toByteArray()) }
                    ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                val info = packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info?.signatures?.map { ApkUtils.sha256HexOf(it.toByteArray()) } ?: emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Compara con la app instalada: actualización/downgrade y conflicto de firma. */
    private fun loadComparison() {
        signatureMismatch = false
        val inst = try {
            val pi = packageManager.getPackageInfo(apkPackage, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
                else @Suppress("DEPRECATION") pi.versionCode.toLong()
            InstalledInfo(pi.versionName ?: "?", code, installedSignerDigests(apkPackage))
        } catch (_: Exception) {
            null
        }
        installed = inst
        if (inst == null) {
            relationText = "No instalada: será una instalación nueva."
            return
        }
        relationText = when (ApkUtils.versionRelation(inst.versionCode, apkVersionCode)) {
            "UPDATE" -> "Actualización: instalada ${inst.versionName} (${inst.versionCode}) → nueva $apkVersion."
            "DOWNGRADE" -> "⚠ DOWNGRADE: la instalada ${inst.versionName} (${inst.versionCode}) es más nueva que $apkVersion. Android lo rechazará salvo que desinstales primero."
            else -> "Misma versión que la instalada (${inst.versionName}). Se reinstalará."
        }
        if (inst.signers.isNotEmpty() && archiveSigners.isNotEmpty() &&
            inst.signers.toSet() != archiveSigners.toSet()
        ) {
            signatureMismatch = true
        }
    }

    /** Chequeos previos: minSdk, CPU, espacio libre, firma y permisos peligrosos. */
    private fun runPreChecks(main: File) {
        val lines = mutableListOf<String>()
        val minSdk = try {
            packageManager.getPackageArchiveInfo(main.absolutePath, 0)
                ?.applicationInfo?.minSdkVersion ?: 1
        } catch (_: Exception) {
            1
        }
        if (Build.VERSION.SDK_INT < minSdk) {
            lines.add("✗ Requiere Android API $minSdk; este móvil es API ${Build.VERSION.SDK_INT}. No se podrá instalar.")
        } else {
            lines.add("✓ Compatible con tu Android (requiere API $minSdk, móvil API ${Build.VERSION.SDK_INT}).")
        }
        val abis = apkAbis(main)
        if (abis.isEmpty()) {
            lines.add("✓ Sin librerías nativas: vale para cualquier CPU.")
        } else {
            val supported = Build.SUPPORTED_ABIS.toSet()
            if (abis.any { it in supported }) {
                lines.add("✓ CPU compatible (${abis.joinToString(", ")}).")
            } else {
                lines.add("✗ CPU incompatible: el APK trae ${abis.joinToString(", ")} y este móvil ${supported.joinToString(", ")}.")
            }
        }
        val free = try {
            filesDir.usableSpace
        } catch (_: Exception) {
            0L
        }
        if (free > 0 && fileSize > 0 && free < fileSize) {
            lines.add("✗ Poco espacio: necesita ${ApkUtils.formatSize(fileSize)} y hay libres ${ApkUtils.formatSize(free)}.")
        } else if (free > 0) {
            lines.add("✓ Espacio suficiente (${ApkUtils.formatSize(free)} libres).")
        }
        if (signatureMismatch) {
            lines.add("✗ FIRMA DISTINTA a la instalada: Android rechazará la actualización. Desinstala primero si confías en este APK.")
        }
        checkLines = lines
        val perms = requestedPerms
        totalPerms = perms?.size ?: 0
        dangerousPerms = perms?.filter { isDangerousPermission(it) } ?: emptyList()
    }

    @Suppress("DEPRECATION") // protectionLevel: única API disponible en minSdk 24.
    private fun isDangerousPermission(name: String): Boolean {
        return try {
            val pi = packageManager.getPermissionInfo(name, 0)
            pi.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE == PermissionInfo.PROTECTION_DANGEROUS
        } catch (_: Exception) {
            false
        }
    }

    /** ABIs con librerías nativas dentro del APK (lib/<abi>/...). */
    private fun apkAbis(file: File): List<String> {
        val abis = mutableSetOf<String>()
        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    if (name.startsWith("lib/")) {
                        val parts = name.split('/')
                        if (parts.size >= 3) abis.add(parts[1])
                    }
                }
            }
        } catch (_: Exception) {
        }
        return abis.sorted()
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                if (cancelled) throw CancelledException()
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return ApkUtils.bytesToHex(md.digest())
    }

    private fun renderAnalysis() {
        val splitSuffix = if (multiApk) "\nPaquete split: ${stagedApks.size} APKs" else ""
        infoText.text = "App: $apkLabel\nPaquete: $apkPackage\nVersión: $apkVersion\nArchivo: $fileName (${ApkUtils.formatSize(fileSize)})$splitSuffix"
        compareText.text = relationText
        compareText.visibility = View.VISIBLE
        checksText.text = checkLines.joinToString("\n")
        checksText.visibility = View.VISIBLE
        permsText.text = when {
            totalPerms == 0 -> "Permisos: ninguno declarado."
            dangerousPerms.isEmpty() -> "Permisos: $totalPerms declarados, ninguno peligroso."
            else -> {
                val shown = dangerousPerms.take(8).joinToString(", ") { ApkUtils.shortPermName(it) }
                val extra = if (dangerousPerms.size > 8) " +${dangerousPerms.size - 8} más" else ""
                "Permisos peligrosos (${dangerousPerms.size}/$totalPerms): $shown$extra"
            }
        }
        permsText.visibility = View.VISIBLE
        hashText.text = "SHA-256: $apkSha256" +
            (if (signerSha256.isNotEmpty()) "\nFirmante: $signerSha256" else "")
        hashText.visibility = View.VISIBLE
        statusText.text = if (multiApk) "Paquete split listo para instalar." else "APK listo para instalar."
    }

    // ---------- Confirmación e instalación ----------

    private fun showConfirmDialog() {
        if (stagedFile == null) return
        val warnings = mutableListOf<String>()
        if (signatureMismatch) warnings.add("• FIRMA DISTINTA a la instalada: la actualización fallará.")
        checkLines.filter { it.startsWith("✗") }.forEach { warnings.add("• $it") }
        val warnBlock = if (warnings.isEmpty()) "" else "\n\nAvisos:\n" + warnings.joinToString("\n")
        val splitBlock = if (multiApk) "\nPartes: ${stagedApks.size} APKs" else ""
        val hashShort = if (apkSha256.length >= 16) "${apkSha256.take(16)}…" else apkSha256
        AlertDialog.Builder(this)
            .setTitle("Confirmar instalación")
            .setMessage("App: $apkLabel\nPaquete: $apkPackage\nVersión: $apkVersion\nArchivo: $fileName (${ApkUtils.formatSize(fileSize)})$splitBlock\nSHA-256: $hashShort\n\nAndroid pedirá confirmación final.$warnBlock")
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

    private fun runInstall() {
        val apks = stagedApks
        if (apks.isEmpty()) return
        working = true
        cancelled = false
        updateButtons()
        statusText.text = "Instalando $apkLabel..."
        setProgress(0, true)
        log("Creando sesión de instalación...")
        requestNotifyPermissionIfNeeded()
        Thread {
            try {
                if (multiApk) runMultiSession(apks) else runInstallSession(apks.first())
                runOnUiThread {
                    setProgress(100, false)
                    statusText.text = "Sesión enviada, esperando confirmación del sistema..."
                    log("Sesión enviada, esperando al sistema...")
                }
            } catch (e: CancelledException) {
                runOnUiThread {
                    setProgress(0, false)
                    statusText.text = "Instalación cancelada."
                    log("Instalación cancelada.")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setProgress(0, false)
                    statusText.text = "Error: ${e.message}"
                    log("Error instalando: ${e.message}")
                    addHistory("FALLO: ${e.message}")
                }
            } finally {
                working = false
                runOnUiThread { updateButtons() }
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
                session.commit(makeStatusSender(sessionId))
            }
        } catch (e: Exception) {
            try {
                installer.abandonSession(sessionId)
            } catch (_: Exception) {
            }
            throw e
        }
    }

    /** Instalación multipaquete para splits: una sesión hija por APK bajo una sesión padre. */
    private fun runMultiSession(apks: List<File>) {
        if (Build.VERSION.SDK_INT < 29) throw IOException("Instalación múltiple no soportada en este Android")
        val installer = packageManager.packageInstaller
        val total = apks.sumOf { it.length() }.coerceAtLeast(1L)
        val parentParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        parentParams.setMultiPackage()
        val parentId = installer.createSession(parentParams)
        val created = mutableListOf(parentId)
        try {
            var done = 0L
            val childIds = mutableListOf<Int>()
            for (apk in apks) {
                if (cancelled) throw CancelledException()
                val childParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val childId = installer.createSession(childParams)
                created.add(childId)
                installer.openSession(childId).use { session ->
                    apk.inputStream().use { input ->
                        session.openWrite("package", 0, apk.length()).use { output ->
                            val base = done
                            val len = apk.length()
                            copyWithProgress(input, output, len) { pct ->
                                val overall = ((base + len * pct / 100) * 100 / total).toInt().coerceAtMost(100)
                                setProgress(overall, true)
                            }
                            session.fsync(output)
                        }
                    }
                }
                childIds.add(childId)
                done += apk.length()
            }
            installer.openSession(parentId).use { parent ->
                for (childId in childIds) parent.addChildSessionId(childId)
                parent.commit(makeStatusSender(parentId))
            }
        } catch (e: Exception) {
            for (id in created) {
                try {
                    installer.abandonSession(id)
                } catch (_: Exception) {
                }
            }
            throw e
        }
    }

    private fun makeStatusSender(sessionId: Int): android.content.IntentSender {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        val broadcast = Intent(installAction).setPackage(packageName)
        return PendingIntent.getBroadcast(this, sessionId, broadcast, flags).intentSender
    }

    private fun copyWithProgress(input: InputStream, output: OutputStream, total: Long, onProgress: (Int) -> Unit) {
        val buffer = ByteArray(256 * 1024)
        var copied = 0L
        var last = -1
        while (true) {
            if (cancelled) throw CancelledException()
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

    private fun copyHash() {
        if (apkSha256.isEmpty()) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("SHA-256 APK", apkSha256))
        Toast.makeText(this, "SHA-256 copiado", Toast.LENGTH_SHORT).show()
        log("SHA-256 copiado al portapapeles.")
    }

    // ---------- Historial ----------

    private fun historyFile() = File(filesDir, "history.json")

    private fun keptDir() = File(filesDir, "kept")

    private fun addHistory(result: String, kept: String = "") {
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
                .put("ver", apkVersion.ifEmpty { "?" })
                .put("result", result)
                .put("kept", kept)
            val capped = JSONArray().put(entry)
            for (i in 0 until minOf(arr.length(), 19)) capped.put(arr.get(i))
            historyFile().writeText(capped.toString())
            runOnUiThread { renderHistory() }
        } catch (e: Exception) {
            log("No se pudo guardar historial: ${e.message}")
        }
    }

    private fun parseHistory(): List<HistoryEntry> {
        return try {
            val arr = JSONArray(historyFile().readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(
                    o.optString("date"),
                    o.optString("name"),
                    o.optString("pkg"),
                    o.optString("ver"),
                    o.optString("result"),
                    o.optString("kept")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun renderHistory() {
        val entries = parseHistory()
        if (entries.isEmpty()) {
            historyText.text = "Historial vacío."
            return
        }
        val lines = StringBuilder()
        for (o in entries) {
            val mark = if (o.result == "OK") "✓" else "✗"
            lines.append("$mark ${o.date} ${o.name} ${o.ver} (${o.pkg}) ${o.result}\n")
        }
        historyText.text = lines.toString().trim()
    }

    private fun showHistoryDialog() {
        val entries = parseHistory()
        if (entries.isEmpty()) {
            Toast.makeText(this, "Historial vacío", Toast.LENGTH_SHORT).show()
            return
        }
        val items = entries.map {
            val mark = if (it.result == "OK") "✓" else "✗"
            "$mark ${it.date} ${it.name} ${it.result}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Historial")
            .setItems(items) { _, which -> showHistoryEntryDialog(entries[which], which) }
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun showHistoryEntryDialog(entry: HistoryEntry, index: Int) {
        val keptFile = if (entry.kept.isNotEmpty()) File(keptDir(), entry.kept) else null
        val canReinstall = keptFile != null && keptFile.exists()
        val detail = "Fecha: ${entry.date}\nArchivo: ${entry.name}\nPaquete: ${entry.pkg}\n" +
            "Versión: ${entry.ver.ifEmpty { "?" }}\nResultado: ${entry.result}" +
            (if (canReinstall) "\n\nHay copia guardada para reinstalar." else "")
        val builder = AlertDialog.Builder(this)
            .setTitle("Detalle")
            .setMessage(detail)
            .setPositiveButton("Cerrar", null)
            .setNegativeButton("Borrar entrada") { _, _ ->
                deleteHistoryAt(index)
                renderHistory()
            }
        if (canReinstall && keptFile != null) {
            builder.setNeutralButton("Reinstalar") { _, _ -> reinstallKept(keptFile, entry) }
        }
        builder.show()
    }

    private fun deleteHistoryAt(index: Int) {
        try {
            val arr = JSONArray(historyFile().readText())
            if (index in 0 until arr.length()) {
                arr.remove(index)
                historyFile().writeText(arr.toString())
                log("Entrada del historial borrada.")
            }
        } catch (e: Exception) {
            log("No se pudo borrar: ${e.message}")
        }
    }

    private fun clearHistory() {
        AlertDialog.Builder(this)
            .setTitle("Borrar historial")
            .setMessage("Se borrarán las entradas y las copias guardadas para reinstalar.")
            .setPositiveButton("Borrar") { _, _ ->
                try {
                    if (historyFile().exists()) historyFile().delete()
                } catch (_: Exception) {
                }
                try {
                    keptDir().deleteRecursively()
                } catch (_: Exception) {
                }
                renderHistory()
                log("Historial borrado.")
                Toast.makeText(this, "Historial borrado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun exportHistory() {
        val text = try {
            if (historyFile().exists()) historyFile().readText() else ""
        } catch (_: Exception) {
            ""
        }
        if (text.isEmpty()) {
            Toast.makeText(this, "Historial vacío", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Historial Nexos Instalador")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Exportar historial"))
    }

    /** Guarda copia del APK instalado (solo APK único) para reinstalar desde el historial. */
    private fun keepApkCopy(): String {
        if (multiApk) return ""
        val staged = stagedFile ?: return ""
        if (apkPackage.isEmpty() || !staged.exists()) return ""
        return try {
            val dir = keptDir()
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, "$apkPackage.apk")
            staged.inputStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            pruneKept(dir, 5)
            log("Copia guardada para reinstalar: ${dest.name}")
            dest.name
        } catch (e: Exception) {
            log("No se pudo guardar copia: ${e.message}")
            ""
        }
    }

    private fun pruneKept(dir: File, max: Int) {
        try {
            val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            for (extra in files.drop(max)) {
                try {
                    extra.delete()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun reinstallKept(file: File, entry: HistoryEntry) {
        if (working) {
            Toast.makeText(this, "Espera a que termine la operación actual", Toast.LENGTH_SHORT).show()
            return
        }
        pickedUri = null
        fileName = entry.name.ifEmpty { file.name }
        fileSize = try {
            file.length()
        } catch (_: Exception) {
            0L
        }
        try {
            stageAndAnalyze(file.inputStream(), file.length(), fileName)
        } catch (e: Exception) {
            statusText.text = "Error: ${e.message}"
            log("Error reinstalando: ${e.message}")
        }
    }

    // ---------- Registro ----------

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $msg"
        synchronized(logBuffer) {
            logBuffer.append(line).append('\n')
            if (logBuffer.length > 20000) logBuffer.delete(0, logBuffer.length - 20000)
        }
        runOnUiThread {
            logText.append("$line\n")
            val layout = logText.layout
            if (layout != null) {
                val y = layout.getLineTop(logText.lineCount) - logText.height
                if (y > 0) logText.scrollTo(0, y)
            }
        }
    }

    private fun logSnapshot(): String = synchronized(logBuffer) { logBuffer.toString() }

    private fun shareLog() {
        val text = logSnapshot()
        if (text.isEmpty()) {
            Toast.makeText(this, "Registro vacío", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Registro Nexos Instalador")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Compartir registro"))
    }

    private fun clearLog() {
        synchronized(logBuffer) { logBuffer.clear() }
        logText.text = ""
        log("Registro limpio.")
    }

    private fun saveLog() {
        val text = logSnapshot()
        if (text.isEmpty()) {
            Toast.makeText(this, "Registro vacío", Toast.LENGTH_SHORT).show()
            return
        }
        statusText.text = "Guardando registro..."
        Thread {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
                val dest = writeTextToDownloads("nexos-log-$stamp.txt", "text/plain", text)
                runOnUiThread {
                    statusText.text = "Registro guardado: $dest"
                    log("Registro guardado: $dest")
                    Toast.makeText(this, "Registro guardado", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "No se pudo guardar: ${e.message}"
                    log("Error guardando registro: ${e.message}")
                }
            }
        }.start()
    }

    private fun writeTextToDownloads(name: String, mime: String, text: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val dest = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("No se pudo crear el destino")
            contentResolver.openOutputStream(dest)?.use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
            } ?: throw IOException("No se pudo escribir el destino")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(dest, values, null, null)
            return "Descargas/$name"
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw IOException("Sin almacenamiento externo")
            val out = File(dir, name)
            out.writeText(text, Charsets.UTF_8)
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
        val busy = working
        pickButton.isEnabled = !busy
        installButton.isEnabled = hasFile && !busy
        retryButton.isEnabled = hasFile && !busy
        cancelButton.isEnabled = busy
        copyHashButton.isEnabled = apkSha256.isNotEmpty()
        shareButton.isEnabled = pickedUri != null
        saveButton.isEnabled = pickedUri != null
        val hasPkg = lastInstalledPackage.isNotEmpty()
        openButton.isEnabled = hasPkg
        uninstallButton.isEnabled = hasPkg
    }

    // ---------- Notificaciones ----------

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

    @Suppress("DEPRECATION") // addAction(int,...): variante compatible con minSdk 24.
    private fun notifyDone(text: String) {
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
            .setContentTitle("Nexos Instalador")
            .setContentText(text)
            .setAutoCancel(true)
        val launch = if (lastInstalledPackage.isNotEmpty()) {
            try {
                packageManager.getLaunchIntentForPackage(lastInstalledPackage)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        if (launch != null) {
            val pi = PendingIntent.getActivity(
                this,
                2001,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_view, "Abrir", pi)
        }
        nm.notify(notifyId, builder.build())
    }
}
