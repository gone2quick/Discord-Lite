package com.example.discordwebapp

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result not needed */ }

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

        requestNotificationPermissionIfNeeded()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Close the drawer if it's open; otherwise let back leave the app.
                val js = "(function(){var n=document.getElementById('notif-panel');" +
                    "if(n && n.classList.contains('open')){n.classList.remove('open');return true;}" +
                    "var d=document.getElementById('drawer');" +
                    "if(d && !d.classList.contains('closed')){d.classList.add('closed');return true;}" +
                    "return false;})()"
                webView.evaluateJavascript(js) { result ->
                    if (result != "true") finish()
                }
            }
        })
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
                val responseText = stream?.let {
                    BufferedReader(InputStreamReader(it)).use { r -> r.readText() }
                } ?: ""

                if (code !in 200..299) {
                    errorJson("HTTP $code", responseText)
                } else {
                    responseText.ifEmpty { "{}" }
                }
            } catch (e: Exception) {
                errorJson(e.message ?: e.javaClass.simpleName, null)
            }
        }

        private fun errorJson(error: String, details: String?): String {
            val obj = JSONObject().put("error", error)
            if (details != null) obj.put("details", details)
            return obj.toString()
        }

        @JavascriptInterface
        fun sendNotification(title: String, message: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return
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

        @JavascriptInterface
        fun sendCallNotification(title: String, message: String, channelId: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return
            // Channel ids are numeric snowflakes; reject anything else.
            if (!channelId.matches(Regex("\\d{5,25}"))) return

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val chId = "litecord_calls"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(chId, "LiteCord Calls", NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        description = "Incoming Discord calls"
                        enableVibration(true)
                    }
                nm.createNotificationChannel(channel)
            }

            // Opens the official Discord app if it handles this link, otherwise the browser.
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.com/channels/@me/$channelId"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(
                context, channelId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, chId)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setTimeoutAfter(45_000)
                .build()

            nm.notify(channelId.hashCode(), notification)
        }
    }
}
