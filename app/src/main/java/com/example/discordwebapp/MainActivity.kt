package com.example.discordwebapp

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var pageReady = false
    private var pendingCallback: String? = null

    // Custom Tabs pre-warming: binding the service early and requesting a session
    // lets Chrome start spinning up before the user even taps "Log in", and
    // mayLaunchUrl() below starts loading discord.com before the tab is shown.
    private var ctSession: CustomTabsSession? = null
    private var ctConnection: CustomTabsServiceConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindCustomTabsService()

        webView = WebView(this)
        setContentView(webView)

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // localStorage ("Keep me logged in")
            allowFileAccess = true            // needed for file:///android_asset
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                routeUrl(request.url)

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                pageReady = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                pageReady = true
                flushPendingCallback()
            }
        }

        webView.loadUrl("file:///android_asset/index.html")

        // Cold start via the redirect (process was killed while the browser was open)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Decides what stays in the WebView and what goes to the system browser. */
    private fun routeUrl(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false
        val host = uri.host.orEmpty()

        return when {
            // Local app content stays in the WebView
            scheme == "file" -> false

            // Redirect arrived inside the WebView (rare) -> treat like the intent
            scheme == "discordwebapp" -> { deliverCallback(uri); true }

            // Discord blocks embedded WebViews (disallowed_useragent): use Custom Tabs
            (scheme == "https" || scheme == "http") &&
                (host == "discord.com" || host.endsWith(".discord.com")) &&
                uri.path.orEmpty().contains("/oauth2/authorize") -> { prewarm(uri); openExternally(uri); true }

            // Any other web link: open in the browser, never navigate the app away
            scheme == "https" || scheme == "http" -> { openExternally(uri); true }

            else -> true
        }
    }

    private fun openExternally(uri: Uri) {
        try {
            CustomTabsIntent.Builder(ctSession)
                .setShowTitle(true)
                .setToolbarColor(Color.parseColor("#14151A")) // matches the app's background
                .setNavigationBarColor(Color.parseColor("#14151A"))
                .setStartAnimations(this, android.R.anim.slide_in_left, android.R.anim.fade_out)
                .setExitAnimations(this, android.R.anim.fade_in, android.R.anim.slide_out_right)
                .build()
                .launchUrl(this, uri)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: ActivityNotFoundException) { /* no browser installed */ }
        }
    }

    /** Binds Chrome's Custom Tabs service so the tab is ready to open instantly. */
    private fun bindCustomTabsService() {
        val packageName = "com.android.chrome" // falls back silently if not installed
        ctConnection = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                client.warmup(0L)
                ctSession = client.newSession(null)
            }
            override fun onServiceDisconnected(name: ComponentName) { ctSession = null }
        }
        try {
            CustomTabsClient.bindCustomTabsService(this, packageName, ctConnection as CustomTabsServiceConnection)
        } catch (_: SecurityException) { /* Chrome not installed: openExternally() falls back fine */ }
    }

    /** Called right when the user taps "Log in", before the tab is shown, to start the page loading early. */
    private fun prewarm(uri: Uri) {
        ctSession?.mayLaunchUrl(uri, null, null)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "discordwebapp" && data.host == "callback") {
            deliverCallback(data)
            intent.data = null // don't re-deliver on re-create
        }
    }

    /** The authorization code (and state) arrive in the query string; an error, if any, may arrive in either. */
    private fun deliverCallback(uri: Uri) {
        val payload = uri.query ?: uri.fragment ?: return
        pendingCallback = payload
        if (pageReady) flushPendingCallback()
    }

    private fun flushPendingCallback() {
        val payload = pendingCallback ?: return
        pendingCallback = null
        val js = "if (window.handleOAuthCallback) { window.handleOAuthCallback(${JSONObject.quote("?$payload")}); }"
        webView.evaluateJavascript(js, null)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.destroy()
        ctConnection?.let { unbindService(it) }
        ctConnection = null
        super.onDestroy()
    }
}
