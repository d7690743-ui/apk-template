package com.nexos.installer

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.io.IOException

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private var pendingApk: Uri? = null

    private val pickApkCode = 1001
    private val unknownSourcesCode = 1002
    private val installAction = "com.nexos.installer.INSTALL_STATUS"

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            when (status) {
                PackageInstaller.STATUS_SUCCESS -> {
                    statusText.text = "Instalación completada."
                    Toast.makeText(context, "APK instalado", Toast.LENGTH_LONG).show()
                }
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
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
                    statusText.text = "Falló la instalación: ${msg ?: "error $status"}"
                    Toast.makeText(context, "Falló: ${msg ?: "error $status"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.status)
        findViewById<Button>(R.id.pick).setOnClickListener { pickApk() }

        val filter = IntentFilter(installAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }

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
            data?.data?.let { installApk(it) }
        } else if (requestCode == unknownSourcesCode) {
            pendingApk?.let { installApk(it) }
        }
    }

    private fun installApk(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            pendingApk = uri
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            @Suppress("DEPRECATION") // Sin AndroidX a propósito: plantilla mínima.
            startActivityForResult(intent, unknownSourcesCode)
            Toast.makeText(this, "Permite instalar apps desconocidas y vuelve", Toast.LENGTH_LONG).show()
            return
        }
        pendingApk = null
        statusText.text = "Instalando..."
        Thread {
            try {
                runInstallSession(uri)
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Error: ${e.message}" }
            }
        }.start()
    }

    private fun runInstallSession(uri: Uri) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                contentResolver.openInputStream(uri)?.use { input ->
                    session.openWrite("package", 0, -1).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                } ?: throw IOException("No se pudo leer el archivo")
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags = flags or PendingIntent.FLAG_MUTABLE
                }
                val broadcast = Intent(installAction).setPackage(packageName)
                val sender = PendingIntent.getBroadcast(this, sessionId, broadcast, flags).intentSender
                session.commit(sender)
            }
        } catch (e: Exception) {
            try {
                installer.abandonSession(sessionId)
            } catch (_: Exception) {
            }
            throw e
        }
    }
}
