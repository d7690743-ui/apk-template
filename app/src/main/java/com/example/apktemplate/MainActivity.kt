package com.example.apktemplate

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.loadDataWithBaseURL(
            null,
            """
            <!doctype html>
            <html lang="es">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
            </head>
            <body style="font-family:sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0;text-align:center;">
              <h1>Hola Mundo APK - Template listo</h1>
            </body>
            </html>
            """.trimIndent(),
            "text/html",
            "utf-8",
            null
        )
        setContentView(webView)
    }
}
