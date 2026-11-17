package com.example.discordwebapp

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var sharedPreferences: SharedPreferences

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = getSharedPreferences("DiscordPrefs", Context.MODE_PRIVATE)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                setUserAgentString("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            }

            addJavascriptInterface(WebAppInterface(this@MainActivity), "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null && (url.contains("discord.com") || url.contains("discordapp.com"))) {
                        injectTokenAndSetupListeners(view)
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.grant(request.resources)
                }
            }
        }

        setContentView(webView)

        val savedToken = sharedPreferences.getString("discord_token", null)
        if (savedToken.isNullOrEmpty()) {
            promptForToken()
        } else {
            loadDiscord(savedToken)
        }
    }

    private fun promptForToken() {
        val input = EditText(this).apply {
            hint = "Paste your Discord User Token here"
        }
        AlertDialog.Builder(this)
            .setTitle("Discord Login Token")
            .setMessage("Enter your Discord account token for instant login and messaging:")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Save & Login") { _, _ ->
                val token = input.text.toString().trim()
                if (token.isNotEmpty()) {
                    sharedPreferences.edit().putString("discord_token", token).apply()
                    loadDiscord(token)
                } else {
                    promptForToken()
                }
            }
            .setNegativeButton("Exit") { _, _ -> finish() }
            .show()
    }

    private fun loadDiscord(token: String) {
        val initialHtml = """
            <html>
            <head>
                <script>
                    localStorage.setItem('token', '"$token"');
                    window.location.href = 'https://discord.com/app';
                </script>
            </head>
            <body>Loading Discord...</body>
            </html>
        """
        webView.loadDataWithBaseURL("https://discord.com", initialHtml, "text/html", "UTF-8", "https://discord.com")
    }

    private fun injectTokenAndSetupListeners(view: WebView?) {
        val token = sharedPreferences.getString("discord_token", "")
        val js = """
            (function() {
                const token = "$token";
                if (token && localStorage.getItem('token') !== '"' + token + '"' && localStorage.getItem('token') !== token) {
                    localStorage.setItem('token', '"' + token + '"');
                }
                
                // Monitor DOM for new incoming messages to trigger native notifications
                if (!window._notificationObserverInitialized) {
                    window._notificationObserverInitialized = true;
                    const observer = new MutationObserver((mutations) => {
                        const messages = document.querySelectorAll('[id^="chat-messages-"]');
                        if (messages.length > 0) {
                            const latest = messages[messages.length - 1];
                            const text = latest.innerText;
                            if (text && window._lastNotified !== text) {
                                window._lastNotified = text;
                                try {
                                    AndroidBridge.sendNotification("Discord", text.substring(0, 100));
                                } catch(e) {}
                            }
                        }
                    });
                    observer.observe(document.body, { childList: true, subtree: true });
                }
            })();
        """
        view?.evaluateJavascript(js, null)
    }

    class WebAppInterface(private val context: Context) {
        @JavascriptInterface
        fun sendNotification(title: String, message: String) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "discord_notifications"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(channelId, "Discord Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Notifications for incoming Discord messages"
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

            notificationManager.notify((System.currentTimeMillis() % 10000).toInt(), notification)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
