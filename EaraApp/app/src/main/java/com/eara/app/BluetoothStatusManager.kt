package com.eara.app

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.WebView
import androidx.core.content.ContextCompat

/**
 * Reads the REAL Bluetooth audio connection state using only public Android APIs.
 * - No scanning for nearby devices.
 * - No custom/unauthorized pairing.
 * - Pairing/connecting is always done by the user via phone's normal Bluetooth settings.
 * - Only reports: BT off, no permission, unsupported hardware, connected, disconnected.
 */
class BluetoothStatusManager(
    private val activity: MainActivity,
    private val webView: WebView
) {
    private val adapter: BluetoothAdapter? =
        (activity.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var a2dpProxy: BluetoothProfile? = null
    private var headsetProxy: BluetoothProfile? = null
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED,
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> refreshState()
            }
        }
    }

    private fun hasPermission(): Boolean {
        // BLUETOOTH_CONNECT only required/exists from Android 12 (S) onward.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(
                activity, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun start() {
        if (started) return
        started = true

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(receiver, filter)
        }

        connectProfileProxies()
        refreshState()
    }

    fun stop() {
        if (!started) return
        started = false
        try { activity.unregisterReceiver(receiver) } catch (_: Exception) { }
        try {
            a2dpProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
            headsetProxy?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        } catch (_: Exception) { }
    }

    private fun connectProfileProxies() {
        val a = adapter ?: return
        if (!hasPermission()) return

        a.getProfileProxy(activity, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                a2dpProxy = proxy
                refreshState()
            }
            override fun onServiceDisconnected(profile: Int) { a2dpProxy = null }
        }, BluetoothProfile.A2DP)

        a.getProfileProxy(activity, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                headsetProxy = proxy
                refreshState()
            }
            override fun onServiceDisconnected(profile: Int) { headsetProxy = null }
        }, BluetoothProfile.HEADSET)
    }

    /** Re-checks real state. Safe to call anytime (app start, resume, broadcast, JS request). */
    fun refreshState() {
        val a = adapter
        if (a == null) {
            pushState("UNSUPPORTED")
            return
        }
        if (!a.isEnabled) {
            pushState("BT_OFF")
            return
        }
        if (!hasPermission()) {
            pushState("PERMISSION_DENIED")
            return
        }

        val a2dpConnected = try {
            a2dpProxy?.connectedDevices?.isNotEmpty() == true
        } catch (_: SecurityException) { false }

        val headsetConnected = try {
            headsetProxy?.connectedDevices?.isNotEmpty() == true
        } catch (_: SecurityException) { false }

        pushState(if (a2dpConnected || headsetConnected) "CONNECTED" else "DISCONNECTED")
    }

    private fun pushState(state: String) {
        activity.runOnUiThread {
            webView.evaluateJavascript("eara_setBluetoothState('$state')", null)
        }
    }
}
