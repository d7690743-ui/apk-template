package com.example.diagnostico

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import android.app.usage.StorageStatsManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private enum class Status { OK, WARN, BAD }

    private val green = Color.rgb(0x1E, 0x88, 0x34)
    private val amber = Color.rgb(0xB2, 0x6A, 0x00)
    private val red = Color.rgb(0xC6, 0x28, 0x28)

    private data class Section(val body: TextView, val state: TextView)

    private lateinit var verdictView: TextView
    private lateinit var actionsResult: TextView
    private lateinit var storageSection: Section
    private lateinit var storageBar: LinearProgressIndicator
    private lateinit var ramSection: Section
    private lateinit var batterySection: Section
    private lateinit var cpuSection: Section
    private lateinit var netSection: Section
    private lateinit var chromeSection: Section
    private lateinit var chromeTimeBody: TextView
    private lateinit var historySection: Section

    private val buttons = mutableListOf<MaterialButton>()
    private var measureStart = 0L
    private var testing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Diagnóstico Teléfono"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })

        verdictView = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(verdictView)
        root.addView(TextView(this).apply {
            text = "Toca una tarjeta para repetir esa prueba."
            textSize = 13f
            setPadding(0, 0, 0, dp(8))
        })

        storageSection = addSection(root, "Almacenamiento") { retest(::runStorage) }
        storageBar = LinearProgressIndicator(this).apply {
            setPadding(0, dp(4), 0, dp(4))
        }
        (storageSection.body.parent as LinearLayout).addView(
            storageBar, 1,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        ramSection = addSection(root, "RAM") { retest(::runRam) }
        batterySection = addSection(root, "Batería") { retest(::runBattery) }
        cpuSection = addSection(root, "CPU") { retest(::runCpu) }
        netSection = addSection(root, "Red") { retest(::runNetwork) }
        chromeSection = addSection(root, "Chrome") { retest(::runChromeInfo) }

        val timeCard = addSection(root, "Tiempo de arranque de Chrome")
        chromeTimeBody = timeCard.body
        historySection = addSection(root, "Historial")

        addButton(root, "Test rápido") { quickTest() }
        addButton(root, "Medir Chrome") { measureChrome() }
        addButton(root, "Limpiar caché Chrome") { openChromeSettings() }
        addButton(root, "Cerrar apps en segundo plano") { closeBackgroundApps() }
        addButton(root, "Liberar espacio") { openStorageSettings() }
        addButton(root, "Compartir diagnóstico") { shareReport() }

        actionsResult = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(actionsResult)

        quickTest()
    }

    override fun onResume() {
        super.onResume()
        if (measureStart > 0) {
            val secs = (SystemClock.elapsedRealtime() - measureStart) / 1000
            chromeTimeBody.text = "${chromeTimeBody.text}\nTiempo fuera de esta app: ${secs}s " +
                "(incluye lo que tardaste en volver)."
            measureStart = 0L
        }
    }

    // ---------- UI ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun addSection(parent: LinearLayout, title: String, onTap: (() -> Unit)? = null): Section {
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            cardElevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        inner.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })
        val body = TextView(this).apply { textSize = 14f }
        val state = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
        }
        inner.addView(body)
        inner.addView(state)
        card.addView(inner)
        if (onTap != null) {
            card.isClickable = true
            card.isFocusable = true
            card.setOnClickListener { onTap() }
        }
        parent.addView(card)
        return Section(body, state)
    }

    private fun addButton(parent: LinearLayout, label: String, onClick: () -> Unit) {
        val b = MaterialButton(this).apply {
            text = label
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            setOnClickListener { onClick() }
        }
        buttons.add(b)
        parent.addView(b)
    }

    private fun paint(section: Section, body: String, state: String, status: Status) {
        section.body.text = body
        section.state.text = state
        section.state.setTextColor(
            when (status) {
                Status.OK -> green
                Status.WARN -> amber
                Status.BAD -> red
            }
        )
    }

    private fun verdict(text: String, status: Status?) {
        verdictView.text = text
        if (status != null) {
            verdictView.setTextColor(
                when (status) {
                    Status.OK -> green
                    Status.WARN -> amber
                    Status.BAD -> red
                }
            )
        }
    }

    private fun fmtBytes(b: Long): String = when {
        b >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", b / 1_000_000_000.0)
        b >= 1_000_000L -> String.format(Locale.US, "%.0f MB", b / 1_000_000.0)
        b >= 1024L -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    // ---------- 1. Almacenamiento ----------

    private fun runStorage(): Status {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            val freePct = free * 100.0 / total
            val usedPct = 100.0 - freePct
            val status = if (freePct < 15) Status.BAD else if (freePct < 25) Status.WARN else Status.OK
            val body = "Total: ${fmtBytes(total)}\nLibre: ${fmtBytes(free)} " +
                "(${String.format(Locale.US, "%.1f", freePct)} %)\n" +
                "Usado: ${String.format(Locale.US, "%.1f", usedPct)} %"
            val state = if (status == Status.BAD) "Almacenamiento lleno"
            else if (status == Status.WARN) "Espacio libre bajo" else "Almacenamiento OK"
            runOnUiThread {
                paint(storageSection, body, state, status)
                storageBar.setProgressCompat(usedPct.toInt(), true)
                storageBar.setIndicatorColor(
                    when (status) {
                        Status.OK -> green
                        Status.WARN -> amber
                        Status.BAD -> red
                    }
                )
            }
            status
        } catch (e: Exception) {
            runOnUiThread { paint(storageSection, "No se pudo leer.", "Error: ${e.message}", Status.BAD) }
            Status.BAD
        }
    }

    // ---------- 2. RAM ----------

    private fun runRam(): Status {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val used = mi.totalMem - mi.availMem
            val usedPct = used * 100.0 / mi.totalMem
            val status = if (usedPct > 85) Status.BAD else if (usedPct > 70) Status.WARN else Status.OK
            val swapTotal = memInfoKb("SwapTotal:")
            val swapFree = memInfoKb("SwapFree:")
            val swapLine = if (swapTotal != null && swapTotal > 0) {
                "\nSwap: total ${fmtBytes(swapTotal * 1024)} / libre ${fmtBytes((swapFree ?: 0) * 1024)}"
            } else {
                "\nSwap: no disponible"
            }
            val body = "Total: ${fmtBytes(mi.totalMem)}\nDisponible: ${fmtBytes(mi.availMem)}\n" +
                "Usada: ${fmtBytes(used)} (${String.format(Locale.US, "%.0f", usedPct)} %)$swapLine\n\n" +
                "Top RAM:\n${topRamApps()}"
            val state = if (status == Status.BAD) "RAM saturada: cierra apps"
            else if (status == Status.WARN) "RAM alta" else "RAM OK"
            runOnUiThread { paint(ramSection, body, state, status) }
            status
        } catch (e: Exception) {
            runOnUiThread { paint(ramSection, "No se pudo leer.", "Error: ${e.message}", Status.BAD) }
            Status.BAD
        }
    }

    private fun memInfoKb(key: String): Long? {
        return try {
            java.io.File("/proc/meminfo").bufferedReader().useLines { seq ->
                seq.firstOrNull { it.startsWith(key) }
                    ?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun topRamApps(): String {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val procs = am.runningAppProcesses?.filter { it.pid > 0 } ?: return "No disponible."
            if (procs.isEmpty()) return "No disponible."
            val mems = am.getProcessMemoryInfo(procs.map { it.pid }.toIntArray())
            procs.mapIndexed { i, p ->
                val pkg = p.processName.substringBefore(':')
                val label = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    pkg.substringAfterLast('.')
                }
                label to (if (i < mems.size) mems[i].totalPss else 0)
            }.sortedByDescending { it.second }.take(5)
                .joinToString("\n") { "• ${it.first}: ${fmtBytes(it.second * 1024L)}" }
        } catch (e: Exception) {
            "No disponible en este Android."
        }
    }

    // ---------- 3. Batería ----------

    private fun runBattery(): Status {
        return try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (level >= 0) level * 100 / scale else -1
            val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val health = when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Buena"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Sobrecalentamiento"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Agotada"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Sobrevoltaje"
                BatteryManager.BATTERY_HEALTH_COLD -> "Fría"
                else -> "Desconocida"
            }
            val status = if (temp >= 42) Status.BAD else if (temp >= 37) Status.WARN else Status.OK
            val chargeStatus = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val chargeState = when (chargeStatus) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Cargando"
                BatteryManager.BATTERY_STATUS_FULL -> "Carga completa"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Descargando"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "No cargando"
                else -> "?"
            }
            val via = listOfNotNull(
                if (plugged and BatteryManager.BATTERY_PLUGGED_AC != 0) "AC" else null,
                if (plugged and BatteryManager.BATTERY_PLUGGED_USB != 0) "USB" else null,
                if (plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0) "inalámbrica" else null
            ).joinToString("+")
            val body = "Nivel: ${if (pct >= 0) "$pct %" else "?"}\n" +
                "Temperatura: ${String.format(Locale.US, "%.1f", temp)} °C\nSalud: $health\n" +
                "Estado: $chargeState" + (if (via.isNotEmpty()) " ($via)" else "")
            val state = if (status == Status.BAD) "Móvil caliente: baja rendimiento"
            else if (status == Status.WARN) "Temperatura algo alta" else "Batería OK"
            runOnUiThread { paint(batterySection, body, state, status) }
            status
        } catch (e: Exception) {
            runOnUiThread { paint(batterySection, "No se pudo leer.", "Error: ${e.message}", Status.BAD) }
            Status.BAD
        }
    }

    // ---------- 4. CPU ----------

    private fun readCpu(): Pair<Long, Long>? {
        return try {
            val line = java.io.File("/proc/stat").bufferedReader().use { it.readLine() } ?: return null
            val parts = line.trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
            if (parts.size < 4) return null
            val idle = parts[3] + if (parts.size > 4) parts[4] else 0L
            parts.sum() to idle
        } catch (e: Exception) {
            null
        }
    }

    private fun cpuFreqMHz(): Long? {
        return try {
            java.io.File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")
                .readText().trim().toLongOrNull()?.div(1000)
        } catch (e: Exception) {
            null
        }
    }

    private fun runCpu(): Status {
        return try {
            val first = readCpu()
            Thread.sleep(400)
            val second = readCpu()
            if (first == null || second == null) throw IllegalStateException("sin /proc/stat")
            val totalDiff = (second.first - first.first).coerceAtLeast(1)
            val idleDiff = second.second - first.second
            val usage = ((1.0 - idleDiff.toDouble() / totalDiff) * 100).coerceIn(0.0, 100.0)
            val status = if (usage > 80) Status.BAD else if (usage > 50) Status.WARN else Status.OK
            val cores = Runtime.getRuntime().availableProcessors()
            val freq = cpuFreqMHz()
            val body = "Uso actual: ${String.format(Locale.US, "%.0f", usage)} %\n" +
                "Núcleos: $cores" + (if (freq != null) "\nFrecuencia: $freq MHz" else "")
            val state = if (status == Status.BAD) "CPU saturada" else if (status == Status.WARN) "CPU alta" else "CPU OK"
            runOnUiThread { paint(cpuSection, body, state, status) }
            status
        } catch (e: Exception) {
            runOnUiThread { paint(cpuSection, "No se pudo medir.", "Error: ${e.message}", Status.WARN) }
            Status.WARN
        }
    }

    // ---------- 5. Red ----------

    private fun netType(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Sin conexión"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Datos móviles"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Red activa"
            }
        } catch (e: Exception) {
            "Desconocida"
        }
    }

    private fun pingGoogle(): Pair<String, Status> {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("ping", "-c", "3", "-W", "2", "google.com"))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            p.destroy()
            val times = Regex("time=([\\d.]+)").findAll(out)
                .mapNotNull { it.groupValues.getOrNull(1)?.toFloatOrNull() }.toList()
            val counts = Regex("(\\d+) packets transmitted, (\\d+) received").find(out)
            val sent = counts?.groupValues?.getOrNull(1) ?: "?"
            val got = counts?.groupValues?.getOrNull(2) ?: "?"
            if (times.isNotEmpty()) {
                val avg = times.average()
                val s = if (avg > 300) Status.BAD else if (avg > 150) Status.WARN else Status.OK
                "Ping google.com: ${avg.toInt()} ms prom. ($got/$sent)" to s
            } else {
                "Ping google.com: sin respuesta ($got/$sent)" to Status.BAD
            }
        } catch (e: Exception) {
            "Ping google.com: error (${e.message})" to Status.BAD
        }
    }

    private fun measureSpeed(): String {
        return try {
            val conn = (URL("https://www.google.com").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
            }
            val t0 = SystemClock.elapsedRealtime()
            var bytes = 0L
            conn.inputStream.use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    bytes += n
                }
            }
            conn.disconnect()
            val dt = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1)
            val kbs = bytes * 1000.0 / dt / 1024.0
            "Velocidad estimada: ~${String.format(Locale.US, "%.0f", kbs)} KB/s"
        } catch (e: Exception) {
            "Velocidad estimada: no se pudo medir"
        }
    }

    private fun runNetwork(): Status {
        val (pingText, pingStatus) = pingGoogle()
        val body = "Conexión: ${netType()}\n$pingText\n${measureSpeed()}"
        val state = if (pingStatus == Status.BAD) "Red lenta o sin respuesta"
        else if (pingStatus == Status.WARN) "Red algo lenta" else "Red OK"
        runOnUiThread { paint(netSection, body, state, pingStatus) }
        return pingStatus
    }

    // ---------- 6. Chrome ----------

    private fun runChromeInfo(): Status {
        val pkg = "com.android.chrome"
        return try {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageInfo(pkg, 0)
            val ver = info.versionName ?: "?"
            var sizes = "Caché/datos: sin permiso de estadísticas de uso"
            var cacheBytes = 0L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val ssm = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
                    val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
                    val stats = ssm.queryStatsForPackage(sm.getUuidForPath(filesDir), pkg, Process.myUserHandle())
                    cacheBytes = stats.cacheBytes
                    sizes = "Caché: ${fmtBytes(stats.cacheBytes)}\nDatos: ${fmtBytes(stats.dataBytes)}\n" +
                        "App: ${fmtBytes(stats.appBytes)}"
                } catch (e: Exception) {
                    sizes = "Caché/datos: no accesible (${e.javaClass.simpleName})"
                }
            } else {
                sizes = "Caché/datos: requiere Android 8+"
            }
            val updated = fmtDate(info.lastUpdateTime)
            val installer = try {
                if (Build.VERSION.SDK_INT >= 30) {
                    packageManager.getInstallSourceInfo(pkg).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getInstallerPackageName(pkg)
                }
            } catch (e: Exception) {
                null
            }
            val status = if (cacheBytes > 500L * 1_000_000L) Status.WARN else Status.OK
            val body = "Versión: $ver\nActualizado: $updated\nInstalado por: ${installer ?: "?"}\n$sizes\n" +
                "Pestañas abiertas: no accesible (Android no expone ese dato)"
            val state = if (status == Status.WARN) "Caché grande: conviene limpiar" else "Chrome OK"
            runOnUiThread { paint(chromeSection, body, state, status) }
            status
        } catch (e: PackageManager.NameNotFoundException) {
            runOnUiThread { paint(chromeSection, "Chrome no está instalado.", "Sin Chrome", Status.WARN) }
            Status.WARN
        }
    }

    // ---------- 7. Acciones ----------

    private fun measureChrome() {
        val intent = packageManager.getLaunchIntentForPackage("com.android.chrome")
        if (intent == null) {
            chromeTimeBody.text = "Chrome no está instalado."
            return
        }
        val t0 = SystemClock.elapsedRealtime()
        startActivity(intent)
        val dispatch = SystemClock.elapsedRealtime() - t0
        measureStart = t0
        chromeTimeBody.text = "Intent de apertura enviado en ${dispatch} ms.\n" +
            "Vuelve a esta app para ver el tiempo total."
    }

    private fun openChromeSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:com.android.chrome")
                )
            )
        } catch (e: Exception) {
            actionsResult.text = "No se pudo abrir ajustes de Chrome: ${e.message}"
        }
    }

    private fun closeBackgroundApps() {
        actionsResult.text = "Cerrando apps en segundo plano..."
        thread {
            var n = 0
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val pkgs = am.runningAppProcesses
                    ?.map { it.processName.substringBefore(':') }
                    ?.distinct() ?: emptyList()
                for (pkg in pkgs) {
                    if (pkg == packageName || pkg.startsWith("com.android.system")) continue
                    try {
                        am.killBackgroundProcesses(pkg)
                        n++
                    } catch (e: Exception) { /* sin permiso para ese paquete */ }
                }
            } catch (e: Exception) {
                runOnUiThread { actionsResult.text = "Error: ${e.message}" }
                return@thread
            }
            runOnUiThread {
                actionsResult.text = "Se pidió cierre de $n procesos. " +
                    "Android puede reabrir los que necesite."
            }
        }
    }

    private fun quickTest() {
        if (testing) return
        testing = true
        buttons.forEach { it.isEnabled = false }
        verdict("Midiendo todo...", null)
        listOf(storageSection, ramSection, batterySection, cpuSection, netSection, chromeSection).forEach {
            it.body.text = "Midiendo…"
            it.state.text = ""
        }
        thread {
            val results = listOf(
                runStorage(), runRam(), runBattery(), runCpu(), runNetwork(), runChromeInfo()
            )
            val names = listOf("Almacenamiento", "RAM", "Batería", "CPU", "Red", "Chrome")
            val problems = names.zip(results).filter { it.second != Status.OK }
                .sortedByDescending { it.second.ordinal }
            val worst = results.maxByOrNull { it.ordinal } ?: Status.OK
            saveHistory(worst, problems.map { it.first })
            runOnUiThread {
                val causes = if (problems.isEmpty()) {
                    ""
                } else {
                    "\nCausas probables: " + problems.joinToString(", ") { it.first }
                }
                when (worst) {
                    Status.BAD -> verdict(
                        "Hay al menos un problema (en rojo): esa es la causa probable de que Chrome tarde.$causes",
                        Status.BAD
                    )
                    Status.WARN -> verdict(
                        "Sin fallos graves, pero hay avisos (en amarillo) que pueden ralentizar Chrome.$causes",
                        Status.WARN
                    )
                    Status.OK -> verdict("Todo bien: si Chrome tarda, prueba limpiar su caché.", Status.OK)
                }
                paintHistory()
                buttons.forEach { it.isEnabled = true }
                testing = false
            }
        }
    }

    private fun retest(fn: () -> Status) {
        thread {
            try {
                fn()
            } catch (_: Exception) {
            }
        }
    }

    private fun fmtDate(ms: Long): String {
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(ms))
        } catch (e: Exception) {
            "?"
        }
    }

    private fun openStorageSettings() {
        try {
            startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                actionsResult.text = "No se pudo abrir ajustes de almacenamiento."
            }
        }
    }

    private fun shareReport() {
        val whenDone = try {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date())
        } catch (e: Exception) {
            ""
        }
        val sb = StringBuilder("Diagnóstico Teléfono ($whenDone)\n\n")
        sb.append(verdictView.text).append("\n\n")
        val sections = listOf(
            "Almacenamiento" to storageSection,
            "RAM" to ramSection,
            "Batería" to batterySection,
            "CPU" to cpuSection,
            "Red" to netSection,
            "Chrome" to chromeSection
        )
        for ((title, s) in sections) {
            sb.append("== ").append(title).append(" ==\n")
                .append(s.body.text).append("\n").append(s.state.text).append("\n\n")
        }
        sb.append("== Arranque Chrome ==\n").append(chromeTimeBody.text).append("\n")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(intent, "Compartir diagnóstico"))
    }

    private fun saveHistory(worst: Status, problems: List<String>) {
        try {
            val stamp = try {
                java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US).format(java.util.Date())
            } catch (e: Exception) {
                ""
            }
            val tag = when (worst) {
                Status.OK -> "OK"
                Status.WARN -> "Avisos"
                Status.BAD -> "Problemas"
            }
            val line = if (problems.isEmpty()) "$stamp — OK" else "$stamp — $tag: ${problems.joinToString(", ")}"
            val prev = try {
                openFileInput("history.log").bufferedReader().readLines()
            } catch (e: Exception) {
                emptyList()
            }
            val keep = (prev + line).takeLast(20).joinToString("\n")
            openFileOutput("history.log", MODE_PRIVATE).use { it.write(keep.toByteArray()) }
        } catch (e: Exception) {
            // El historial es opcional; no rompe el diagnóstico.
        }
    }

    private fun paintHistory() {
        try {
            val lines = openFileInput("history.log").bufferedReader().readLines().takeLast(5)
            if (lines.isEmpty()) {
                paint(historySection, "Sin historial todavía.", "", Status.OK)
            } else {
                paint(historySection, lines.joinToString("\n"), "${lines.size} últimos", Status.OK)
            }
        } catch (e: Exception) {
            paint(historySection, "Sin historial todavía.", "", Status.OK)
        }
    }
}
