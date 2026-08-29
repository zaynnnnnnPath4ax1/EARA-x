package com.eara.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : AppCompatActivity(), GestureEventBus.Listener {

    private lateinit var webView: WebView
    private lateinit var bluetoothStatusManager: BluetoothStatusManager
    private lateinit var configStore: GestureConfigStore
    private lateinit var controlStateStore: ControlStateStore
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())

    private val requestBtPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Whatever the user chose, just reflect the real resulting state.
        bluetoothStatusManager.refreshState()
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Foreground service still runs either way; this just controls its notification. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        // Settings required for existing UI (Three.js + localStorage) to work as-is.
        // File/content access is deliberately OFF: index.html has no local file
        // dependencies (only the bundled asset + CDN <script> tags), and leaving
        // these on would let any future/injected navigation read arbitrary
        // on-device files through the WebView.
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // required for localStorage
            allowFileAccess = false
            allowContentAccess = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
        }

        // Security: the native JS bridge (AndroidBridge) is only safe to expose to
        // EARA's own bundled asset. If anything ever tried to navigate this WebView
        // away from file:///android_asset/ (a bug, a malicious link, etc.), send
        // that navigation to the system browser instead of loading it here — never
        // let outside content run alongside the bridge.
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean {
                val url = request.url
                if (url.scheme == "file" && url.path?.startsWith("/android_asset/") == true) {
                    return false // allow, it's our own bundled UI
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true // handled externally, never loaded into this WebView
                } catch (_: Exception) {
                    true // no app to handle it; just don't load it here
                }
            }
        }
        webView.webChromeClient = WebChromeClient()

        // Native <-> JS bridge. UI can call: AndroidBridge.someMethod(...)
        webView.addJavascriptInterface(EaraBridge(), "AndroidBridge")

        bluetoothStatusManager = BluetoothStatusManager(this, webView)
        configStore = GestureConfigStore(applicationContext)
        controlStateStore = ControlStateStore(applicationContext)

        // Load existing UI exactly as-is, no changes
        webView.loadUrl("file:///android_asset/index.html")

        // Ask for BLUETOOTH_CONNECT only where it actually exists (Android 12+).
        // Pairing/connecting itself is never done here — only reading current state.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestBtPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // Notification is only for the foreground service that keeps listening
        // for earbud button events while backgrounded/screen-locked.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        bluetoothStatusManager.start()

        // Only run the background listener if the user had CONTROL ON last time.
        activityScope.launch {
            val enabled = controlStateStore.isEnabled()
            if (enabled) startGestureService()
            runOnUiThread {
                webView.evaluateJavascript("eara_setControlState($enabled)", null)
            }
        }

        // WebView handles its own back navigation (Home <-> Customize etc.)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // User may have paired/connected earbuds from system Bluetooth settings
        // while the app was in the background — reflect the real state now.
        if (::bluetoothStatusManager.isInitialized) {
            bluetoothStatusManager.refreshState()
        }
        GestureEventBus.attach(this)

        // Recovery: if control is ON but the service got killed (e.g. by the
        // OS, or after a Bluetooth reconnect), bring it back. Starting an
        // already-running started service is a harmless no-op.
        activityScope.launch {
            if (controlStateStore.isEnabled()) startGestureService()
        }
    }

    override fun onPause() {
        GestureEventBus.detach(this)
        super.onPause()
    }

    override fun onDestroy() {
        bluetoothStatusManager.stop()
        activityScope.cancel()
        webView.destroy()
        super.onDestroy()
    }

    private fun startGestureService() {
        val intent = Intent(this, EaraGestureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopGestureService() {
        val intent = Intent(this, EaraGestureService::class.java).apply {
            action = EaraGestureService.ACTION_STOP
        }
        startService(intent) // deliver the stop action; service stops itself cleanly
    }

    // --- GestureEventBus.Listener: real gesture fired by the service ---
    override fun onGestureFired(gestureId: String, action: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "eara_onGestureFired('${escape(gestureId)}','${escape(action)}')", null
            )
        }
    }

    override fun onGestureUnsupported(reason: String) {
        // Honest reporting only — never fake success. Logged for now; the UI
        // is left unchanged as instructed, so no new visible element is added.
        runOnUiThread {
            webView.evaluateJavascript(
                "console.warn('EARA gesture limitation: ${escape(reason)}')", null
            )
        }
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    /**
     * Native backend bridge. Add real methods here only when the UI actually
     * needs native functionality (file system, sensors, etc). Nothing fake added.
     */
    inner class EaraBridge {
        @JavascriptInterface
        fun getPlatform(): String = "android"

        // Called by the UI on load / on-demand to get the real current
        // Bluetooth audio device state (no scanning, no fake data).
        @JavascriptInterface
        fun requestBluetoothState() {
            runOnUiThread { bluetoothStatusManager.refreshState() }
        }

        // Called when user taps the existing CONTROL ON/OFF toggle.
        @JavascriptInterface
        fun setControlEnabled(enabled: Boolean) {
            activityScope.launch {
                controlStateStore.setEnabled(enabled)
                if (enabled) startGestureService() else stopGestureService()
            }
        }

        // Called on load so the toggle reflects the real persisted/service state.
        @JavascriptInterface
        fun requestControlState() {
            activityScope.launch {
                val enabled = controlStateStore.isEnabled()
                runOnUiThread { webView.evaluateJavascript("eara_setControlState($enabled)", null) }
            }
        }

        // Opens Android's own Accessibility Settings screen (system screen,
        // not a new EARA screen) — only invoked if/when the UI explicitly asks.
        // EARA never turns the service on itself.
        @JavascriptInterface
        fun openAccessibilitySettings() {
            runOnUiThread {
                startActivity(
                    Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        // Called when the user saves changes on the Customize screen.
        @JavascriptInterface
        fun saveGestureConfig(json: String) {
            activityScope.launch { configStore.saveConfigJson(json) }
        }

        // Called on app load so the Customize screen reflects whatever was
        // actually persisted natively (survives app restart/process death).
        @JavascriptInterface
        fun requestGestureConfig() {
            activityScope.launch {
                val cfg = configStore.getConfig()
                val json = JSONObject(cfg as Map<*, *>).toString()
                runOnUiThread {
                    webView.evaluateJavascript("eara_applyGestureConfig('${escape(json)}')", null)
                }
            }
        }
    }
}
