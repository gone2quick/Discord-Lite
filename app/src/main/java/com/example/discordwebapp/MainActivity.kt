package com.example.discordwebapp

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
            }
            webViewClient = WebViewClient()
            addJavascriptInterface(DiscordBridge(this@MainActivity), "AndroidBridge")
            loadUrl("file:///android_asset/index.html")
        }

        setContentView(webView)
    }

    class DiscordBridge(private val context: Context) {

        @JavascriptInterface
        fun apiRequest(method: String, urlString: String, token: String, jsonBody: String): String {
            return try {
                // Clean any accidental quotes or whitespace out of the token
                val cleanToken = token.trim().replace("\"", "").replace("'", "")

                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = method
                
                // USER TOKENS MUST BE PASSED RAW WITHOUT "Bearer" OR "Bot" PREFIXES
                conn.setRequestProperty("Authorization", cleanToken)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (jsonBody.isNotEmpty() && (method == "POST" || method == "PATCH" || method == "PUT")) {
                    conn.doOutput = true
                    OutputStreamWriter(conn.outputStream).use { it.write(jsonBody) }
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val responseText = BufferedReader(InputStreamReader(stream)).use { it.readText() }

                if (code !in 200..299) {
                    """{"error": "HTTP $code", "details": ${JSONObjectQuote(responseText)}}"""
                } else {
                    responseText.ifEmpty { "{}" }
                }
            } catch (e: Exception) {
                """{"error": "${e.message?.replace("\"", "'")}"}"""
            }
        }

        private fun JSONObjectQuote(text: String): String {
            val safe = text.replace("\"", "\\\"").replace("\n", " ")
            return "\"$safe\""
        }

        @JavascriptInterface
        fun sendNotification(title: String, message: String) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "litecord_messages"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "LiteCord Messages",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Real-time Discord message notifications"
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            notificationManager.notify((System.currentTimeMillis() % 100000).toInt(), notification)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val drawerJs = "if(typeof toggleDrawer === 'function') { toggleDrawer(); } else { document.getElementById('drawer').classList.toggle('closed'); }"
        webView.evaluateJavascript(drawerJs, null)
    }
}
