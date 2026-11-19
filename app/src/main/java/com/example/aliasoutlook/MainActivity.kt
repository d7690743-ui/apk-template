package com.example.aliasoutlook

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    companion object {
        const val OUTLOOK_URL = "https://outlook.live.com/owa/"
        const val MICROSOFT_ACCOUNT_URL = "https://account.microsoft.com"
        const val PREFS = "alias_outlook_prefs"
        const val KEY_BASE = "base_email"
        const val KEY_HIST = "history"
        const val MAX_HIST = 10
        const val ID_OUTLOOK = 1
        const val ID_ALIAS = 2
    }

    private lateinit var webContainer: LinearLayout
    private lateinit var aliasContainer: ScrollView
    private lateinit var webView: WebView
    private lateinit var webProgress: LinearProgressIndicator
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private lateinit var baseInput: EditText
    private lateinit var tagInput: EditText
    private lateinit var modeGroup: RadioGroup
    private lateinit var modeGmail: RadioButton
    private lateinit var modeOutlook: RadioButton
    private lateinit var resultBox: LinearLayout
    private lateinit var formMsg: TextView
    private lateinit var historyBox: LinearLayout
    private lateinit var historyEmpty: TextView

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = if (result.resultCode == RESULT_OK) {
                val data = result.data
                when {
                    data?.clipData != null -> Array(data.clipData!!.itemCount) { i ->
                        data.clipData!!.getItemAt(i).uri
                    }
                    data?.data != null -> arrayOf(data.data!!)
                    else -> null
                }
            } else null
            fileCallback?.onReceiveValue(uris)
            fileCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(content)

        webContainer = buildOutlookTab()
        aliasContainer = buildAliasTab()
        content.addView(webContainer)
        content.addView(aliasContainer)

        val nav = BottomNavigationView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        nav.menu.add(0, ID_OUTLOOK, 0, "Outlook").setIcon(R.drawable.ic_mail)
        nav.menu.add(0, ID_ALIAS, 1, "Alias").setIcon(R.drawable.ic_tag)
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                ID_OUTLOOK -> showTab(true)
                ID_ALIAS -> showTab(false)
            }
            true
        }
        root.addView(nav)
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webContainer.visibility == View.VISIBLE && ::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState.getBundle("webState"))
            if (savedInstanceState.getBoolean("aliasTab", false)) {
                nav.selectedItemId = ID_ALIAS
            } else {
                showTab(true)
            }
        } else {
            webView.loadUrl(OUTLOOK_URL)
            showTab(true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) {
            val webBundle = Bundle()
            webView.saveState(webBundle)
            outState.putBundle("webState", webBundle)
        }
        outState.putBoolean("aliasTab", aliasContainer.visibility == View.VISIBLE)
    }

    private fun showTab(outlook: Boolean) {
        webContainer.visibility = if (outlook) View.VISIBLE else View.GONE
        aliasContainer.visibility = if (outlook) View.GONE else View.VISIBLE
    }

    // ---------- Pestaña Outlook (WebView) ----------

    private fun buildOutlookTab(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        webProgress = LinearProgressIndicator(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        col.addView(webProgress)

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val btnBack = smallButton("‹ Atrás") { if (::webView.isInitialized && webView.canGoBack()) webView.goBack() }
        val btnReload = smallButton("Recargar") { if (::webView.isInitialized) webView.reload() }
        val btnBrowser = smallButton("Abrir en navegador") { openExternal(OUTLOOK_URL) }
        toolbar.addView(btnBack)
        toolbar.addView(btnReload)
        toolbar.addView(btnBrowser)
        col.addView(toolbar)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = true
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return if (url.startsWith("http")) {
                    false
                } else {
                    openExternal(url)
                    true
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, progress: Int) {
                webProgress.setProgressCompat(progress, true)
                webProgress.visibility = if (progress >= 100) View.GONE else View.VISIBLE
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    filePicker.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    toast("No se pudo abrir el selector de archivos")
                    false
                }
            }
        }
        col.addView(webView)
        return col
    }

    private fun smallButton(label: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("No se pudo abrir el enlace")
        }
    }

    // ---------- Pestaña Alias ----------

    private fun buildAliasTab(): ScrollView {
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Generador de alias"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Alias reales para Gmail y Outlook que sí aceptan el IMSS, bancos y gobierno."
            textSize = 13f
            setPadding(0, dp(4), 0, dp(12))
        })

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        root.addView(card("Tu correo base") { inner ->
            baseInput = EditText(this).apply {
                hint = "tucorreo@gmail.com"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                setText(prefs.getString(KEY_BASE, ""))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            inner.addView(baseInput)
        })

        root.addView(card("Etiqueta") { inner ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            tagInput = EditText(this).apply {
                hint = "imss"
                setText("imss")
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnRandom = MaterialButton(this).apply {
                text = "Aleatorio"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) }
                setOnClickListener {
                    tagInput.setText(randomTag())
                    renderAlias()
                }
            }
            row.addView(tagInput)
            row.addView(btnRandom)
            inner.addView(row)
        })

        root.addView(card("Tipo de alias") { inner ->
            modeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
            modeGmail = RadioButton(this).apply { text = "Gmail con +"; isChecked = true }
            modeOutlook = RadioButton(this).apply { text = "Alias Outlook" }
            modeGroup.addView(modeGmail)
            modeGroup.addView(modeOutlook)
            modeGroup.setOnCheckedChangeListener { _, _ -> renderAlias() }
            inner.addView(modeGroup)
        })

        root.addView(card("Resultado") { inner ->
            resultBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            inner.addView(resultBox)
            formMsg = TextView(this).apply {
                textSize = 13f
                setPadding(0, dp(6), 0, 0)
            }
            inner.addView(formMsg)
        })

        root.addView(card("Historial") { inner ->
            historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            inner.addView(historyBox)
            historyEmpty = TextView(this).apply {
                text = "Aquí aparecerán los alias que copies."
                textSize = 13f
            }
            inner.addView(historyEmpty)
            inner.addView(MaterialButton(this).apply {
                text = "Borrar historial"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                setOnClickListener {
                    prefs.edit().remove(KEY_HIST).apply()
                    renderHistory()
                    toast("Historial borrado")
                }
            })
        })

        root.addView(card("Agregar alias en Outlook") { inner ->
            inner.addView(TextView(this).apply {
                text = "1. Abre tu cuenta Microsoft.\n" +
                    "2. Ve a Tu información → agregar correo.\n" +
                    "3. Crea la dirección, verifícala y úsala."
                textSize = 13f
            })
            inner.addView(MaterialButton(this).apply {
                text = "Abrir mi cuenta Microsoft"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                setOnClickListener { openExternal(MICROSOFT_ACCOUNT_URL) }
            })
        })

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { renderAlias() }
        }
        baseInput.addTextChangedListener(watcher)
        tagInput.addTextChangedListener(watcher)

        renderAlias()
        renderHistory()
        return scroll
    }

    private fun card(title: String, fill: (LinearLayout) -> Unit): MaterialCardView {
        val c = MaterialCardView(this).apply {
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
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        fill(inner)
        c.addView(inner)
        return c
    }

    private data class Base(val local: String, val domain: String, val full: String)

    private fun parseBase(raw: String): Base? {
        val v = raw.trim().lowercase()
        val at = v.indexOf('@')
        if (at < 1 || at != v.lastIndexOf('@') || at == v.length - 1) return null
        val local = v.substring(0, at).filter { it.isLetterOrDigit() || it in "._+-" }
        val domain = v.substring(at + 1).filter { it.isLetterOrDigit() || it in ".-" }
        if (local.isEmpty() || !domain.contains('.')) return null
        val clean = local.substringBefore('+')
        return Base(clean, domain, "$clean@$domain")
    }

    private fun cleanTag(raw: String): String =
        raw.trim().lowercase().filter { it.isLetterOrDigit() || it in "._-" }.take(20)

    private fun outlookIdeas(base: Base, tag: String): List<String> {
        val cands = listOf(
            "${base.local}.$tag", "$tag.${base.local}", "${base.local}-$tag",
            "$tag${base.local}", "${base.local}$tag${Random.nextInt(10, 99)}"
        )
        return cands.map { it.filter { ch -> ch.isLetterOrDigit() || ch in "._-" } + "@outlook.com" }
            .filter { it.length >= 4 + "@outlook.com".length }
            .distinct()
            .take(3)
    }

    private val tagWords = listOf("imss", "banco", "gobierno", "salud", "tramites", "compras", "escuela", "trabajo")

    private fun randomTag(): String = tagWords.random() + Random.nextInt(10, 99)

    private fun renderAlias() {
        if (!::resultBox.isInitialized) return
        resultBox.removeAllViews()
        val base = parseBase(baseInput.text.toString())
        val tag = cleanTag(tagInput.text.toString())
        if (base == null) {
            formMsg.text = if (baseInput.text.toString().trim().isEmpty()) "" else "Escribe un correo base válido."
            return
        }
        if (tag.length < 2) {
            formMsg.text = "La etiqueta debe tener al menos 2 caracteres."
            return
        }
        formMsg.text = ""
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_BASE, base.full).apply()

        if (modeGmail.isChecked) {
            addResultRow("${base.local}+$tag@${base.domain}", "gmail")
        } else {
            outlookIdeas(base, tag).forEach { addResultRow(it, "outlook") }
        }
    }

    private fun addResultRow(addr: String, type: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(this).apply {
            text = addr
            textSize = 14f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(MaterialButton(this).apply {
            text = "Copiar"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            setOnClickListener {
                copyText(addr)
                addHistory(type, addr)
            }
        })
        resultBox.addView(row)
    }

    private fun loadHistory(): MutableList<Pair<String, String>> {
        val raw = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HIST, "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return raw.lines().mapNotNull { line ->
            val parts = line.split('|', limit = 2)
            if (parts.size == 2 && parts[1].contains('@')) parts[0] to parts[1] else null
        }.toMutableList()
    }

    private fun saveHistory(hist: List<Pair<String, String>>) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HIST, hist.joinToString("\n") { "${it.first}|${it.second}" })
            .apply()
    }

    private fun addHistory(type: String, addr: String) {
        val hist = loadHistory().filter { it.second != addr }.toMutableList()
        hist.add(0, type to addr)
        saveHistory(hist.take(MAX_HIST))
        renderHistory()
    }

    private fun renderHistory() {
        if (!::historyBox.isInitialized) return
        historyBox.removeAllViews()
        val hist = loadHistory()
        historyEmpty.visibility = if (hist.isEmpty()) View.VISIBLE else View.GONE
        hist.forEach { (type, addr) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(this).apply {
                text = addr
                textSize = 13f
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(MaterialButton(this).apply {
                text = "Copiar"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) }
                setOnClickListener { copyText(addr) }
            })
            historyBox.addView(row)
        }
    }

    private fun copyText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("alias", text))
        toast("Copiado: $text")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
