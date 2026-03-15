package com.ultronai.productarglasses

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import java.net.NetworkInterface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ultronai.productarglasses.camera.CameraController
import com.ultronai.productarglasses.databinding.ActivityMainBinding
import com.ultronai.productarglasses.network.MjpegServer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.launch

class MainActivity : BaseMirrorActivity<ActivityMainBinding>() {

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val SERVER_PORT = 8080
        private const val TARGET_FPS = 30
    }

    private var cameraController: CameraController? = null
    private var mjpegServer: MjpegServer? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ipRefreshRunnable: Runnable? = null

    @Volatile private var frameCount = 0
    @Volatile private var lastFpsTime = System.currentTimeMillis()
    @Volatile private var lastFrameTime = 0L
    private val frameIntervalMs = 1000 / TARGET_FPS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBindingPair.updateView {
            tvTitle.text = "Product AR"
            tvStatus.text = "Status: Initializing..."
            tvIpAddress.text = "IP: obtaining..."
        }

        startIpRefresh()
        setupTempleActions()
        checkPermissions()
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        } else {
            startCameraAndServer()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCameraAndServer()
        } else {
            mBindingPair.updateView {
                tvStatus.text = "Status: Camera permission denied"
            }
        }
    }

    private fun startCameraAndServer() {
        // Start MJPEG server
        mjpegServer = MjpegServer(
            port = SERVER_PORT,
            onClientConnected = { count ->
                runOnUiThread {
                    mBindingPair.updateView {
                        tvStatus.text = if (count > 0) "Status: $count client(s)" else "Status: Waiting..."
                        tvStatus.setTextColor(if (count > 0) 0xFF00FF00.toInt() else 0xFFFFFF00.toInt())
                    }
                }
            }
        )
        mjpegServer?.start()

        // Start camera
        cameraController = CameraController(
            context = this,
            onFrameAvailable = { jpeg, width, height ->
                val now = System.currentTimeMillis()
                if (now - lastFrameTime >= frameIntervalMs) {
                    lastFrameTime = now
                    mjpegServer?.sendFrame(jpeg)
                    updateFps()
                }
            },
            onError = { error ->
                runOnUiThread {
                    mBindingPair.updateView {
                        tvStatus.text = "Error: $error"
                    }
                }
            }
        )
        cameraController?.start()

        mBindingPair.updateView {
            tvStatus.text = "Status: Waiting..."
        }
    }

    private fun updateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1000) {
            val fps = frameCount
            frameCount = 0
            lastFpsTime = now
            runOnUiThread {
                mBindingPair.updateView {
                    tvFps.text = "FPS: $fps"
                }
            }
        }
    }

    private fun startIpRefresh() {
        ipRefreshRunnable = object : Runnable {
            override fun run() {
                val ip = getDeviceIpAddress()
                mBindingPair.updateView {
                    tvIpAddress.text = "IP: $ip:$SERVER_PORT"
                }
                if (ip == "0.0.0.0") {
                    mainHandler.postDelayed(this, 3000)
                } else {
                    mainHandler.postDelayed(this, 10000)
                }
            }
        }
        mainHandler.post(ipRefreshRunnable!!)
    }

    private fun getDeviceIpAddress(): String {
        // 1) Try WifiManager (may be wrong on glasses tethered to phone)
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipInt = wifiManager.connectionInfo.ipAddress
        if (ipInt != 0) {
            val fromWifi = "${ipInt and 0xFF}.${ipInt shr 8 and 0xFF}.${ipInt shr 16 and 0xFF}.${ipInt shr 24 and 0xFF}"
            if (fromWifi != "0.0.0.0") return fromWifi
        }
        // 2) Fallback: enumerate network interfaces (glasses' real IP, e.g. wlan0)
        try {
            for (ni in NetworkInterface.getNetworkInterfaces() ?: emptyList()) {
                if (ni.isLoopback || !ni.isUp) continue
                for (addr in ni.inetAddresses) {
                    val host = addr.hostAddress ?: continue
                    if (host.contains("%")) continue
                    if (addr.isSiteLocalAddress && host.matches(Regex("^(10|192\\.168|172\\.(1[6-9]|2[0-9]|3[01]))\\..*")))
                        return host
                }
            }
        } catch (_: Exception) { }
        return "0.0.0.0"
    }

    private fun setupTempleActions() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collect { action ->
                    when (action) {
                        is TempleAction.DoubleClick -> finish()
                        else -> Unit
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ipRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
        cameraController?.stop()
        mjpegServer?.stop()
    }
}