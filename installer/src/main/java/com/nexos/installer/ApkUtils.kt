package com.nexos.installer

import java.security.MessageDigest
import java.util.Locale

/** Un APK candidato dentro de un contenedor split (.apks/.xapk/.apkm). */
data class SplitEntry(val name: String, val size: Long)

/**
 * Lógica pura (sin Android) del instalador: se puede probar con JUnit local.
 */
object ApkUtils {

    fun formatSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes <= 0) return "tamaño desconocido"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(locale, "%.0f KB", kb)
        return String.format(locale, "%.1f MB", kb / 1024.0)
    }

    fun bytesToHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(digits[v ushr 4]).append(digits[v and 0x0F])
        }
        return out.toString()
    }

    fun sha256HexOf(bytes: ByteArray): String =
        bytesToHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Relación entre la versión instalada y la candidata: UPDATE, SAME o DOWNGRADE. */
    fun versionRelation(installedCode: Long, candidateCode: Long): String = when {
        candidateCode > installedCode -> "UPDATE"
        candidateCode < installedCode -> "DOWNGRADE"
        else -> "SAME"
    }

    fun shortPermName(name: String): String =
        name.removePrefix("android.permission.")

    fun isSplitContainerName(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".apks") || lower.endsWith(".xapk") || lower.endsWith(".apkm")
    }

    /**
     * Ordena candidatos split para elegir el base: primero base.apk,
     * luego por tamaño descendente, luego por nombre.
     */
    fun orderSplitCandidates(entries: List<SplitEntry>): List<SplitEntry> =
        entries.sortedWith(
            compareBy(
                { !it.name.equals("base.apk", ignoreCase = true) },
                { -it.size },
                { it.name.lowercase(Locale.US) }
            )
        )
}
